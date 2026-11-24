package com.toolhelper.plugin.common;

import com.toolhelper.service.TaskCoordinator;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.electerm.*;
import com.toolhelper.support.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.slf4j.*;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.electerm.PluginModels.*;

public class ManagedPluginService {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedPluginService.class);
    protected final ManagedInstallationStore store;
    protected final OfficialReleaseClient releases;
    private final ArchiveExtractor archives;
    protected final DesktopProcessAdapter processes;
    protected final TaskCoordinator tasks;
    private final ToolHelperProperties properties;
    protected final JsonMapper json;
    private volatile Inspection operationBaseline;

    protected final String pluginId;
    protected final List<String> supportedVersions;
    protected ManagedPluginService(String pluginId, List<String> supportedVersions, ManagedInstallationStore store, OfficialReleaseClient releases, ArchiveExtractor archives,
                               DesktopProcessAdapter processes, TaskCoordinator tasks, ToolHelperProperties properties, JsonMapper json) {
        this.pluginId = pluginId; this.supportedVersions = List.copyOf(supportedVersions);
        this.store = store; this.releases = releases; this.archives = archives;
        this.processes = processes; this.tasks = tasks; this.properties = properties; this.json = json;
        store.recover();
    }

    public synchronized Map<String, Object> status() throws IOException {
        TaskRecord active = tasks.active();
        Inspection inspection = active != null && pluginId.equals(active.pluginId()) && operationBaseline != null ? operationBaseline : store.inspect();
        InstallManifest manifest = inspection.manifest();
        ProcessObservation observed = processes.observe(manifest == null ? null : store.relative(store.current(), manifest.executable()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pluginId", pluginId); response.put("state", inspection.state());
        response.put("message", inspection.message()); response.put("version", manifest == null ? null : manifest.tag());
        response.put("installRevision", manifest == null ? null : manifest.revision());
        response.put("directory", manifest == null ? store.pluginRoot().toString() : store.current().resolve(manifest.executable()).getParent().toString());
        response.put("platformSupported", processes.platformSupported());
        response.put("supportedVersions", supportedVersions);
        response.put("connectionCompatibility", "SOURCE_VERIFIED_REQUIRES_SERVICE_ACCEPTANCE");
        response.put("operation", active == null ? "IDLE" : active.operation());
        response.put("busy", active != null);
        response.put("activePluginId", active == null ? null : active.pluginId());
        response.put("currentTask", publicTask(active != null && pluginId.equals(active.pluginId()) ? active : null)); response.put("running", observed);
        return response;
    }

    public Map<String, Object> checkUpdate() throws IOException, InterruptedException {
        ReleaseSelection selection = releases.latest();
        Inspection current = store.inspect();
        String result = "AVAILABLE";
        if (current.manifest() != null) {
            int comparison = OfficialReleaseClient.compare(selection.asset().tag(), current.manifest().tag());
            result = comparison == 0 ? "UP_TO_DATE" : comparison < 0 ? "REMOTE_OLDER" : "AVAILABLE";
        }
        return Map.of("releaseSelectionId", selection.id(), "latestVersion", selection.asset().tag(),
                "assetName", selection.asset().name(), "size", selection.asset().size(), "expiresAt", selection.expiresAt(),
                "compatible", supportedVersions.contains(OfficialReleaseClient.version(selection.asset().tag())), "result", result);
    }

    public synchronized Map<String, Object> mutate(String action, MutationRequest request, String key) throws Exception {
        String fingerprint = fingerprint(action, request);
        TaskRecord replay = tasks.replay(pluginId, key, fingerprint);
        if (replay != null) return requireResult(replay, true);
        processes.requireDesktop();
        ensureReady();
        Inspection before = store.inspect();
        InstallManifest old = before.manifest();
        if (before.state().equals("BROKEN")) throw new ApiException(409, "PLUGIN_BROKEN", before.message());
        if (action.equals("INSTALL")) {
            if (!before.state().equals("NOT_INSTALLED")) throw new ApiException(409, "PLUGIN_ALREADY_INSTALLED", "插件已安装，请使用更新");
        } else {
            if (action.equals("UNINSTALL") && before.state().equals("NOT_INSTALLED")) return Map.of("status", "ALREADY_NOT_INSTALLED");
            if (old == null) throw new ApiException(409, "PLUGIN_NOT_INSTALLED", "请先安装插件");
            if (request == null || !Boolean.TRUE.equals(request.confirmed())) throw new ApiException(400, "CONFIRMATION_REQUIRED", "需要先确认更新/删除操作");
            if (!old.revision().equals(request.expectedInstallRevision())) throw new ApiException(409, "INSTALL_REVISION_CONFLICT", "安装已变化，请刷新后重新确认");
            processes.requireStopped(store.executable(old));
            store.verifyOwnedFiles(old);
        }
        Asset asset = action.equals("UNINSTALL") ? null : releases.selected(request == null ? null : request.releaseSelectionId());
        if (old != null && asset != null && OfficialReleaseClient.compare(asset.tag(), old.tag()) <= 0) {
            throw new ApiException(409, "NO_NEWER_VERSION", "目标版本不是更高版本，不会重装或降级");
        }
        TaskRecord task = tasks.claim(pluginId, action, key, fingerprint);
        operationBaseline = before;
        tasks.submit(task.taskId(), () -> {
            try { return action.equals("UNINSTALL") ? uninstall(task.taskId(), old) : install(task.taskId(), old, asset); }
            finally { operationBaseline = null; }
        });
        return publicTask(task);
    }

    public synchronized Map<String, Object> localAction(String action, ConnectionRequest request, String key) throws Exception {
        ConnectionRequest connection = action.equals("CONNECT") ? normalizeConnection(request) : null;
        String fingerprint = fingerprint(action, connection);
        TaskRecord replay = tasks.replay(pluginId, key, fingerprint);
        if (replay != null) return requireResult(replay, false);
        if (action.equals("OPEN_DIRECTORY")) {
            if (tasks.active() != null) throw new ApiException(409, "PLUGIN_BUSY", "插件正在执行任务", true);
        } else ensureReady();
        processes.requireDesktop();
        InstallManifest installed = action.equals("OPEN_DIRECTORY") ? store.inspect().manifest() : store.requireInstalled();
        if (action.equals("CONNECT") && !supportedVersions.contains(OfficialReleaseClient.version(installed.tag()))) {
            throw new ApiException(422, "PLUGIN_VERSION_UNVERIFIED", "该安装版本尚未列入连接适配范围");
        }
        TaskRecord task = tasks.claim(pluginId, action, key, fingerprint);
        TaskRecord result = tasks.execute(task.taskId(), () -> {
            tasks.progress(task.taskId(), "DISPATCHING", 0, null, "正在提交本机请求");
            if (action.equals("OPEN_DIRECTORY")) {
                Path directory = installed == null ? store.pluginRoot() : store.relative(store.current(), installed.executable()).getParent();
                return processes.openDirectory(directory);
            }
            Path executable = store.executable(installed);
            Path data = store.relative(store.current(), installed.userData());
            return action.equals("CONNECT") ? dispatchConnection(executable, data, connection) : processes.launch(executable, data);
        });
        return requireResult(result, false);
    }

    private Map<String, Object> install(String id, InstallManifest old, Asset asset) throws Exception {
        Path stage = store.stage(id);
        Path candidate = stage.resolve("candidate");
        boolean journalWritten = false;
        Journal transaction = null;
        try {
            SecurePaths.createDirectories(stage);
            store.ensureDiskSpace(properties.maxExtractedBytes() + asset.size() + properties.maxUserDataBytes());
            tasks.progress(id, "DOWNLOADING", 0, asset.size(), "正在下载官方插件");
            Path archive = stage.resolve("package.part");
            releases.download(asset, archive, (downloaded, total) -> tasks.progress(id, "DOWNLOADING", downloaded, total, "正在下载官方插件"));
            tasks.progress(id, "VERIFYING", asset.size(), asset.size(), "下载校验已完成，准备解压");
            tasks.progress(id, "EXTRACTING", asset.size(), asset.size(), "正在安全解压");
            List<String> files = archives.extract(archive, asset.name(), candidate.resolve("app"));
            InstallManifest next = store.makeCandidate(candidate, asset, files);
            if (old != null) {
                processes.requireStopped(store.executable(old));
                if (!store.readManifest(store.current()).revision().equals(old.revision())) throw new ApiException(409, "INSTALL_REVISION_CONFLICT", "下载期间安装发生变化");
                store.verifyOwnedFiles(old);
                tasks.progress(id, "PRESERVING_DATA", asset.size(), asset.size(), "正在校验并保留便携用户数据");
                store.copyData(old, store.relative(candidate, next.userData()));
                processes.requireStopped(store.executable(old));
            } else if (Files.exists(store.current(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("安装目录在下载期间被创建");
            tasks.progress(id, "SWITCHING", asset.size(), asset.size(), "正在切换完整安装");
            String operation = old == null ? "INSTALL" : "UPDATE";
            transaction = new Journal(operation, id, old == null ? null : old.revision(), next.revision(), "PREPARED", false);
            store.writeJournal(transaction);
            journalWritten = true;
            if (old != null) {
                store.move(store.current(), store.backup(id));
                store.writeJournal(new Journal(operation, id, old.revision(), next.revision(), "OLD_MOVED", false));
            }
            store.move(candidate, store.current());
            store.writeJournal(new Journal(operation, id, old == null ? null : old.revision(), next.revision(), "NEW_MOVED", false));
            if (!store.readManifest(store.current()).revision().equals(next.revision())) throw new IOException("切换后元数据校验失败");
            store.clearJournal();
            journalWritten = false;
            boolean cleaned = cleanup(stage);
            return Map.of("status", old == null ? "INSTALLED" : "UPDATED", "version", next.tag(),
                    "message", "插件安装完成，未自动启动", "backupDirectory", old == null ? "" : store.backup(id).toString(),
                    "cleanupPending", !cleaned, "cleanupDirectory", cleaned ? "" : stage.toString());
        } catch (Exception failure) {
            if (journalWritten) {
                // 任务记录写失败不能阻断恢复原安装。
                try { tasks.progress(id, "ROLLING_BACK", 0, null, "正在检查并恢复原安装"); }
                catch (RuntimeException progressFailure) { failure.addSuppressed(progressFailure); }
                try { store.rollback(transaction); }
                catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            } else if (!cleanup(stage)) {
                throw new ApiException(500, "CLEANUP_PENDING", "操作失败且暂存清理未完成，请核对目录：" + stage, false);
            }
            throw failure;
        }
    }

    private Map<String, Object> uninstall(String id, InstallManifest old) throws Exception {
        Path stage = store.stage(id);
        boolean journalWritten = false;
        try {
            processes.requireStopped(store.executable(old));
            store.verifyOwnedFiles(old);
            tasks.progress(id, "PRESERVING_DATA", 0, null, "正在备份用户数据");
            store.ensureDiskSpace(properties.maxUserDataBytes());
            boolean hasData = store.copyData(old, store.preserved(id));
            processes.requireStopped(store.executable(old));
            store.writeJournal(new Journal("UNINSTALL", id, old.revision(), null, "PREPARED", hasData));
            journalWritten = true;
            store.move(store.current(), stage.resolve("uninstall"));
            store.writeJournal(new Journal("UNINSTALL", id, old.revision(), null, "CLEANING", hasData));
            tasks.progress(id, "CLEANING", 0, null, "正在清理已卸载的程序文件");
            SecurePaths.deleteTree(store.pluginRoot(), stage);
            store.clearJournal();
            return Map.of("status", "UNINSTALLED", "preservedDirectory", hasData ? store.preserved(id).toString() : "",
                    "message", hasData ? "插件已卸载，用户数据已保留" : "插件已卸载，未发现便携用户数据");
        } catch (Exception failure) {
            if (journalWritten) store.recover();
            throw failure;
        }
    }

    private boolean cleanup(Path stage) {
        try { SecurePaths.deleteTree(store.pluginRoot(), stage); return true; }
        catch (Exception error) { LOG.warn("任务暂存清理未完成，已保留路径 {}", stage, error); return false; }
    }

    protected void ensureReady() {
        if (store.inspect().state().equals("RECOVERY_REQUIRED")) throw new ApiException(409, "RECOVERY_REQUIRED", "需先核对未完成操作，现有文件已保留");
        if (tasks.active() != null) throw new ApiException(409, "PLUGIN_BUSY", "插件正在执行任务 " + tasks.active().taskId(), true);
    }

    private String fingerprint(String action, Object body) { return SecurePaths.hash(action + ":" + json.writeValueAsString(body)); }

    protected Map<String, Object> requireResult(TaskRecord task, boolean asynchronous) {
        if (asynchronous) return publicTask(task);
        if (task.status().equals("SUCCEEDED")) return task.result();
        if (task.error() != null) throw new ApiException(task.error().status() >= 400 ? task.error().status() : 500,
                task.error().code(), task.error().message(), task.error().retryable());
        throw new ApiException(409, "REQUEST_IN_PROGRESS", "相同请求尚在执行，请查询任务状态", true);
    }

    protected ConnectionRequest normalizeConnection(ConnectionRequest request) { throw new ApiException(422, "PROTOCOL_NOT_SUPPORTED", "该连接入口不支持此客户端"); }
    protected Map<String, Object> dispatchConnection(Path executable, Path data, ConnectionRequest request) throws Exception { throw new ApiException(422, "PROTOCOL_NOT_SUPPORTED", "该连接入口不支持此客户端"); }

    public Map<String, Object> task(String id) { return publicTask(tasks.get(id)); }

    public static Map<String, Object> publicTask(TaskRecord task) {
        if (task == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pluginId", task.pluginId());
        result.put("taskId", task.taskId()); result.put("operation", task.operation());
        result.put("status", task.status()); result.put("phase", task.phase());
        result.put("downloadedBytes", task.downloadedBytes()); result.put("totalBytes", task.totalBytes());
        result.put("message", task.message()); result.put("createdAt", task.createdAt());
        result.put("updatedAt", task.updatedAt()); result.put("result", task.result()); result.put("error", task.error());
        return result;
    }
}
