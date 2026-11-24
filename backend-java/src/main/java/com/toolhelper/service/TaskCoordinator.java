package com.toolhelper.service;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.electerm.InstallationStore;
import com.toolhelper.plugin.common.LocalDataRoot;
import com.toolhelper.support.*;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@Service
public class TaskCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(TaskCoordinator.class);
    private final LocalDataRoot store;
    private final JsonFiles json;
    private final ToolHelperProperties properties;
    private final Map<String, TaskRecord> tasks = new LinkedHashMap<>();
    private final Map<String, Instant> lastPersisted = new HashMap<>();
    private final Map<String, String> ephemeralFingerprints = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("plugin-worker").factory());
    private String active;

    public TaskCoordinator(InstallationStore store, JsonFiles json, ToolHelperProperties properties) throws IOException {
        this(store.dataRoot(), json, properties);
    }

    @Autowired
    public TaskCoordinator(LocalDataRoot store, JsonFiles json, ToolHelperProperties properties) throws IOException {
        this.store = store;
        this.json = json;
        this.properties = properties;
        try (var files = Files.list(store.tasksRoot())) {
            for (Path path : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                TaskRecord task = json.read(path, TaskRecord.class);
                if (!path.getFileName().toString().equals(task.taskId() + ".json")) throw new IOException("任务文件与标识不符");
                if (task.status().equals("RUNNING") || task.status().equals("QUEUED")) {
                    task = task.finish("INTERRUPTED", "服务重启，原请求结果请结合插件状态核对，勿自动重发", null,
                            new ErrorInfo(409, "REQUEST_OUTCOME_UNKNOWN", "操作中断，文件切换将按恢复记录检查", false));
                    json.write(path, task);
                }
                tasks.put(task.taskId(), task);
            }
        }
        prune();
    }

    public synchronized TaskRecord active() { return active == null ? null : tasks.get(active); }

    public synchronized TaskRecord get(String id) {
        TaskRecord record = tasks.get(id);
        if (record == null) throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在或已过保留期");
        return record;
    }

    public synchronized TaskRecord replay(String key, String fingerprint) {
        return replay("electerm", key, fingerprint);
    }

    public synchronized TaskRecord replay(String pluginId, String key, String fingerprint) {
        String hash = keyHash(key);
        for (TaskRecord record : tasks.values()) {
            if (record.pluginId().equals(pluginId) && record.keyHash().equals(hash)) {
                String expected = record.fingerprint().equals("EPHEMERAL") ? ephemeralFingerprints.get(record.taskId()) : record.fingerprint();
                if (expected == null) throw new ApiException(409, "REQUEST_OUTCOME_UNKNOWN", "服务重启后不能核对敏感连接，请查看 DBX 中的实际结果，不会自动重发");
                if (!expected.equals(fingerprint)) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能用于不同请求");
                return record;
            }
        }
        return null;
    }

    public synchronized TaskRecord claim(String operation, String key, String fingerprint) throws IOException {
        return claim("electerm", operation, key, fingerprint);
    }

    public synchronized TaskRecord claim(String pluginId, String operation, String key, String fingerprint) throws IOException {
        return claim(pluginId, operation, key, fingerprint, false);
    }

    public synchronized TaskRecord claim(String pluginId, String operation, String key, String fingerprint, boolean sensitive) throws IOException {
        if (!Set.of("electerm", "dbx").contains(pluginId)) throw new IllegalArgumentException("未知插件标识");
        if (active != null) throw new ApiException(409, "PLUGIN_BUSY", "插件正在执行任务 " + active, true);
        prune();
        if (tasks.size() >= properties.maxTaskRecords()) throw new ApiException(503, "TASK_HISTORY_FULL", "任务记录已达上限，请在保留期结束后重试");
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        TaskRecord task = new TaskRecord(id, operation, keyHash(key), sensitive ? "EPHEMERAL" : fingerprint, "RUNNING", "PREPARING",
                0, null, "正在准备操作", now, now, null, null, pluginId);
        persist(task);
        if (sensitive) ephemeralFingerprints.put(id, fingerprint);
        active = id;
        LOG.info("任务开始 taskId={} operation={}", id, operation);
        return task;
    }

    public synchronized void progress(String id, String phase, long downloaded, Long total, String message) {
        TaskRecord old = get(id);
        TaskRecord updated = old.withProgress(phase, downloaded, total, message);
        try {
            if (!old.phase().equals(phase) || lastPersisted.getOrDefault(id, Instant.EPOCH).plusSeconds(2).isBefore(Instant.now())) persist(updated);
            else tasks.put(id, updated);
            if (!old.phase().equals(phase)) LOG.info("任务阶段 taskId={} phase={}", id, phase);
        } catch (IOException error) { throw new IllegalStateException("任务记录无法持久化，停止操作", error); }
    }

    public void submit(String id, Work operation) {
        worker.submit(() -> execute(id, operation));
    }

    public TaskRecord execute(String id, Work operation) {
        try {
            Map<String, Object> result = operation.run();
            return complete(id, "SUCCEEDED", "操作完成", result, null);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            ApiException api = error instanceof ApiException known ? known
                    : new ApiException(500, "OPERATION_FAILED", "操作未完成，请结合请求标识查看本机日志", true);
            LOG.error("任务失败 taskId={} code={}", id, api.code(), error);
            return complete(id, "FAILED", api.getMessage(), null, new ErrorInfo(api.status(), api.code(), api.getMessage(), api.retryable()));
        } finally {
            synchronized (this) { active = null; }
        }
    }

    private synchronized TaskRecord complete(String id, String status, String message,
                                               Map<String, Object> result, ErrorInfo error) {
        TaskRecord record = get(id).finish(status, message, result, error);
        try { persist(record); }
        catch (IOException failure) {
            store.recoveryRequired("任务结果无法可靠写入，请检查日志与磁盘状态后重启服务");
            record = record.finish("FAILED", "结果记录写入失败，操作结果需核对", null,
                    new ErrorInfo(409, "REQUEST_OUTCOME_UNKNOWN", "操作结果无法持久化，请核对插件实际状态", false));
            tasks.put(id, record);
            LOG.error("任务结果记录失败 taskId={}", id, failure);
        }
        return record;
    }

    private void persist(TaskRecord task) throws IOException {
        json.write(store.tasksRoot().resolve(task.taskId() + ".json"), task);
        tasks.put(task.taskId(), task);
        lastPersisted.put(task.taskId(), Instant.now());
    }

    private void prune() throws IOException {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.taskRetentionDays()));
        Iterator<TaskRecord> iterator = tasks.values().iterator();
        while (iterator.hasNext()) {
            TaskRecord task = iterator.next();
            if (!task.taskId().equals(active) && task.updatedAt().isBefore(cutoff)) {
                Path path = store.tasksRoot().resolve(task.taskId() + ".json");
                SecurePaths.checkAncestors(path);
                Files.deleteIfExists(path);
                iterator.remove();
                lastPersisted.remove(task.taskId());
                ephemeralFingerprints.remove(task.taskId());
            }
        }
    }

    private static String keyHash(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}")) throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "需要 16～128 位合法 Idempotency-Key");
        return SecurePaths.hash(key);
    }

    @FunctionalInterface public interface Work { Map<String, Object> run() throws Exception; }
    @PreDestroy public void close() {
        worker.shutdownNow();
        try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) LOG.warn("任务线程未在关闭期限内退出，重启时将检查恢复记录"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
