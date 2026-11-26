package com.toolhelper.service;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.kylin.*;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.kylin.KylinModels.*;

/** 连接、预览与执行互斥；一次性计划在提交后立即消耗，网络失败不得重放。 */
@Service
public class KylinOpsService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(KylinOpsService.class);
    private final KylinHostKeyStore keys;
    private final KylinBackupStore backups;
    private final KylinPlanService planner;
    private final KylinExecutor executor;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("kylin-operations").factory());
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("kylin-idle").factory());
    private final Map<String, KylinPlanService.Prepared> plans = new LinkedHashMap<>();
    private final Map<String, Job> tasks = new LinkedHashMap<>();
    private KylinSshClient ssh;
    private Connection connection;
    private Instant touched;
    private boolean busy;
    private Job active;
    private KylinActivation.Operation activation;
    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Connection connection; final KylinPlanService.Prepared prepared;
        final Instant created = Instant.now(); final AtomicBoolean cancelled = new AtomicBoolean();
        volatile boolean mutationStarted;
        volatile String status = "QUEUED", step = "QUEUED", code, message = "等待执行";
        volatile Instant finished;
        Job(Connection connection, KylinPlanService.Prepared prepared) { this.connection = connection; this.prepared = prepared; }
        Task snapshot() { return new Task(id, connection.connectionId(), prepared.view().feature(), prepared.view().action(), status,
                step, code, message, prepared.view().backupId(), created, finished); }
    }
    public KylinOpsService(ToolHelperProperties properties, JsonMapper json) throws IOException {
        Path root = KylinFiles.directory(properties.root().resolve("kylin"));
        keys = new KylinHostKeyStore(root, json); backups = new KylinBackupStore(root, json);
        planner = new KylinPlanService(backups); executor = new KylinExecutor(backups);
        sweeper.scheduleWithFixedDelay(this::expire, 30, 30, TimeUnit.SECONDS);
    }
    public Map<String, Object> capabilities() {
        return Map.of("version", KylinPolicyCatalog.VERSION, "features", KylinPolicyCatalog.FEATURES,
                "optimizations", KylinPolicyCatalog.OPTIMIZATIONS, "limits", Map.of("connections", 1, "tasks", 1, "outputBytes", KylinSshClient.OUTPUT_LIMIT, "idleSeconds", 900, "planSeconds", 300),
                "patchStatus", "USER_PROVIDED_PATCH", "patch", KylinPatch.metadata(), "activation", "INTERACTIVE_QR", "assets", KylinTemplates.assets());
    }
    public synchronized HostKey probe(Host request) { idle(); return keys.probe(KylinSshClient.validate(request.host(), request.port())); }
    public synchronized HostKey trust(TrustRequest request) { idle(); return keys.trust(request); }
    public synchronized Connection connect(ConnectRequest request) {
        idle();
        if (ssh != null) throw new ApiException(409, "KYLIN_ALREADY_CONNECTED", "请先断开当前SSH连接");
        Host host = KylinSshClient.validate(request.host(), request.port());
        String fingerprint = keys.trusted(host);
        if (fingerprint == null) throw new ApiException(409, "SSH_HOST_KEY_UNTRUSTED", "请先探测并核对主机指纹");
        KylinSshClient candidate = KylinSshClient.connect(host, request.username(), request.password(), fingerprint);
        try {
            var system = KylinProbe.system(candidate); touched = Instant.now();
            connection = new Connection(UUID.randomUUID().toString(), host.host(), host.port(), request.username(), fingerprint, true, system, touched.plusSeconds(900));
            ssh = candidate;
            LOG.info("Kylin连接建立 connectionId={} target={}:{}", connection.connectionId(), host.host(), host.port());
            return connection;
        } catch (RuntimeException failure) { candidate.close(); throw failure; }
    }
    public synchronized Connection connection() {
        if (ssh == null) return null;
        if (!busy && (!ssh.connected() || Instant.now().isAfter(touched.plusSeconds(900)))) { disconnectInternal(); return null; }
        return new Connection(connection.connectionId(), connection.host(), connection.port(), connection.username(), connection.fingerprint(), ssh.connected(), connection.system(), touched.plusSeconds(900));
    }
    public synchronized void disconnect(String id) { idle(); require(id); disconnectInternal(); }
    private void disconnectInternal() {
        if (activation != null) { activation.cancelled = true; activation.clear(); activation = null; }
        if (ssh != null) ssh.close(); ssh = null; connection = null; plans.clear();
    }
    private Connection require(String id) {
        return require(id, true);
    }
    private Connection require(String id, boolean touch) {
        Connection current = connection();
        if (current == null || !current.connectionId().equals(id) || !current.connected())
            throw new ApiException(409, "SSH_DISCONNECTED", "连接已失效，请重新连接并重新预览");
        if (touch) touched = Instant.now(); return current;
    }
    private boolean sudo() { return !connection.system().get("uid").equals("0"); }
    public synchronized Scan scan(ScanRequest request) {
        idle(); require(request.connectionId()); KylinPolicyCatalog.feature(request.feature());
        return KylinProbe.scan(ssh, request.feature(), sudo(), request.sudoPassword());
    }
    public synchronized Plan plan(PlanRequest request) {
        idle(); Connection current = require(request.connectionId());
        plans.values().removeIf(p -> p.view().expiresAt().isBefore(Instant.now()));
        if (plans.size() >= 10) plans.clear();
        var prepared = planner.prepare(current, ssh, sudo(), request);
        plans.put(prepared.view().planId(), prepared);
        LOG.info("Kylin计划生成 planId={} feature={} action={}", prepared.view().planId(), request.feature(), request.action());
        return prepared.view();
    }
    public synchronized Task execute(ExecuteRequest request) {
        idle();
        if (!request.confirmed()) throw new ApiException(400, "KYLIN_CONFIRMATION_REQUIRED", "需要核对计划并明确确认");
        var prepared = plans.get(request.planId());
        if (prepared == null || prepared.view().expiresAt().isBefore(Instant.now())) throw new ApiException(409, "KYLIN_PLAN_EXPIRED", "计划已使用、失效或过期，请重新预览");
        Connection current = require(prepared.view().connectionId());
        if (prepared.files().stream().anyMatch(KylinTemplates.File::secret)) {
            byte[] check = KylinTemplates.vncPassword(request.vncPassword()); Arrays.fill(check, (byte) 0);
        }
        if (request.sudoPassword() != null && (request.sudoPassword().length() > 2048 || request.sudoPassword().codePoints().anyMatch(Character::isISOControl)))
            throw new ApiException(400, "INVALID_SUDO_PASSWORD", "sudo密码格式不正确");
        plans.remove(request.planId()); busy = true;
        tasks.values().removeIf(j -> j.finished != null && j.finished.isBefore(Instant.now().minusSeconds(1800)));
        if (tasks.size() >= 20) tasks.remove(tasks.keySet().iterator().next());
        Job job = new Job(current, prepared); tasks.put(job.id, job); active = job;
        KylinSshClient transport = ssh; boolean sudo = sudo();
        worker.submit(() -> {
            job.status = "RUNNING";
            LOG.info("Kylin任务开始 taskId={} feature={} action={}", job.id, job.prepared.view().feature(), job.prepared.view().action());
            try {
                executor.execute(current, transport, sudo, prepared, request.sudoPassword(), request.vncPassword(), job.cancelled::get,
                        value -> job.step = value, () -> job.mutationStarted = true);
                job.status = "SUCCEEDED"; job.message = prepared.view().feature().equals("patch")
                        ? "补丁安装完成，包版本、配置状态及服务验证通过；可点击验证重新检查。结果基于用户提供的补丁版本，不代表其他漏洞均已修复。"
                        : "步骤执行及后置验证完成；配置生效范围见计划说明";
            } catch (ApiException failure) {
                job.code = failure.code();
                boolean unknown = Set.of("SSH_COMMAND_TIMEOUT", "SSH_IO_ERROR", "SSH_EXIT_UNKNOWN", "SSH_OUTPUT_LIMIT", "CANCELLED").contains(failure.code());
                job.status = job.mutationStarted ? unknown ? "UNKNOWN_REMOTE_STATE" : "PARTIAL" : job.cancelled.get() ? "CANCELLED" : "FAILED";
                job.message = failure.getMessage();
                LOG.warn("Kylin任务未完成 taskId={} code={} status={}", job.id, job.code, job.status);
            } catch (RuntimeException failure) {
                job.code = "KYLIN_EXECUTION_ERROR"; job.status = job.mutationStarted ? "UNKNOWN_REMOTE_STATE" : "FAILED";
                job.message = "执行发生异常，请核对远程状态与备份；未自动重试";
                LOG.error("Kylin任务异常 taskId={} type={}", job.id, failure.getClass().getSimpleName());
            } finally {
                job.finished = Instant.now();
                synchronized (KylinOpsService.this) { busy = false; active = null; touched = Instant.now(); }
            }
        });
        return job.snapshot();
    }
    public synchronized ActivationTask activate(ActivationRequest request) {
        idle(); Connection current = require(request.connectionId());
        if (!request.confirmed()) throw new ApiException(400, "KYLIN_CONFIRMATION_REQUIRED", "请核对目标并确认获取二维码");
        KylinActivation.text(request.serialNumber(), "服务序列号", 128, true);
        if (!request.serialNumber().matches("[0-9]{8,128}")) throw new ApiException(400, "INVALID_ACTIVATION_SERIAL", "服务序列号需为至少8位数字");
        KylinActivation.text(request.sudoPassword(), "sudo密码", 2048, false);
        if (activation != null) activation.clear();
        var operation = new KylinActivation.Operation(current.connectionId()); activation = operation;
        busy = true; plans.clear();
        KylinSshClient transport = ssh; boolean needsSudo = sudo();
        worker.submit(() -> {
            LOG.info("Kylin激活任务开始 taskId={}", operation.id);
            try { KylinActivationExecutor.execute(operation, transport, needsSudo, request); }
            catch (ApiException failure) {
                operation.errorCode = failure.code();
                operation.status = operation.submitted ? "UNKNOWN_REMOTE_STATE" : operation.cancelled ? "CANCELLED" : "FAILED";
                if (failure.code().equals("KYLIN_ACTIVATION_REJECTED")) operation.status = "FAILED";
                operation.message = failure.getMessage();
                LOG.warn("Kylin激活任务未完成 taskId={} code={} status={}", operation.id, failure.code(), operation.status);
            } catch (RuntimeException failure) {
                operation.errorCode = "KYLIN_ACTIVATION_ERROR"; operation.status = operation.submitted ? "UNKNOWN_REMOTE_STATE" : "FAILED";
                operation.message = "激活交互异常，请重新检测目标状态；未自动重试";
                LOG.error("Kylin激活任务异常 taskId={} type={}", operation.id, failure.getClass().getSimpleName());
            } finally {
                operation.clear(); operation.finished = Instant.now();
                synchronized (KylinOpsService.this) { busy = false; touched = Instant.now(); }
                LOG.info("Kylin激活任务结束 taskId={} status={} code={}", operation.id, operation.status, operation.errorCode);
            }
        });
        return operation.snapshot();
    }
    public synchronized ActivationTask activationCurrent() { return activation == null ? null : activation.snapshot(); }
    private KylinActivation.Operation activation(String id) {
        if (activation == null || !activation.id.equals(id) || connection == null || !activation.connectionId.equals(connection.connectionId()))
            throw new ApiException(404, "KYLIN_ACTIVATION_NOT_FOUND", "激活任务不存在或连接已变更");
        return activation;
    }
    public synchronized ActivationTask activationTask(String id) { return activation(id).snapshot(); }
    public synchronized byte[] activationQr(String id) { return activation(id).qr(); }
    public synchronized ActivationTask continueActivation(String id, ActivationContinue request) {
        var operation = activation(id); require(operation.connectionId); operation.submit(request.activationCode()); return operation.snapshot();
    }
    public synchronized ActivationTask cancelActivation(String id) {
        var operation = activation(id);
        if (operation.finished == null) { operation.cancelled = true; operation.status = "CANCELLING"; operation.clear(); }
        return operation.snapshot();
    }
    public synchronized Task active() { return activation != null && activation.finished == null ? activation.task() : active == null ? null : active.snapshot(); }
    public synchronized Task task(String id) {
        if (activation != null && activation.id.equals(id)) return activation.task();
        Job job = tasks.get(id); if (job == null) throw new ApiException(404, "KYLIN_TASK_NOT_FOUND", "任务不存在或服务已重启");
        return job.snapshot();
    }
    public synchronized Task cancel(String id) {
        if (activation != null && activation.id.equals(id)) { cancelActivation(id); return activation.task(); }
        Job job = tasks.get(id); if (job == null) throw new ApiException(404, "KYLIN_TASK_NOT_FOUND", "任务不存在");
        if (job.finished == null) { job.cancelled.set(true); job.status = "CANCELLING"; } return job.snapshot();
    }
    // 页面定时读取备份列表不算用户操作，避免后台轮询使空闲连接永不过期。
    public synchronized List<Backup> backups(String id) { Connection current = require(id, false); return backups.list(current.host(), current.port(), current.fingerprint()); }
    private void idle() { if (busy) throw new ApiException(409, "KYLIN_BUSY", "当前主机有执行任务，请等待收尾再操作"); }
    private synchronized void expire() {
        if (!busy && ssh != null && Instant.now().isAfter(touched.plusSeconds(900))) disconnectInternal();
    }
    @PreDestroy public void close() {
        sweeper.shutdownNow();
        synchronized (this) { if (active != null) active.cancelled.set(true); if (activation != null) activation.cancelled = true; }
        worker.shutdown();
        try { worker.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        synchronized (this) { disconnectInternal(); }
    }
}
