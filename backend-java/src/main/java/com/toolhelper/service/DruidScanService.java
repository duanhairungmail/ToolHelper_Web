package com.toolhelper.service;

import com.toolhelper.config.DruidScanProperties;
import com.toolhelper.druid.*;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.druid.DruidModels.*;

/** 一次一个模块任务，同源串行；任务只有在所有实际工作槽收尾后才释放。 */
@Service
public class DruidScanService {
    private static final Logger LOG = LoggerFactory.getLogger(DruidScanService.class);
    private final DruidScanProperties properties;
    private final DruidScanner scanner;
    private final JsonMapper json;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("druid-coordinator").factory());
    private final ExecutorService workers;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private Job active;
    private boolean closing;
    private record Input(String id, URI root) {}
    private static final class Job {
        final String id = UUID.randomUUID().toString(), key, kind;
        final List<Input> inputs;
        final Instant createdAt = Instant.now();
        final LinkedHashMap<String, TargetResult> results = new LinkedHashMap<>();
        volatile boolean cancelled;
        String status = "QUEUED", errorCode;
        Instant finishedAt;
        Job(String key, String kind, List<URI> roots) {
            this.key = key; this.kind = kind; inputs = roots.stream().map(root -> new Input(UUID.randomUUID().toString(), root)).toList();
        }
    }
    public DruidScanService(DruidScanProperties properties, JsonMapper json) {
        this.properties = properties; this.json = json; scanner = new DruidScanner(properties, json);
        workers = Executors.newFixedThreadPool(properties.concurrency(), Thread.ofPlatform().name("druid-target-", 0).factory());
    }
    public Object capabilities() { return Map.of("networkMode", "DIRECT", "ruleVersion", DruidRuleCatalog.VERSION,
            "rules", DruidRuleCatalog.ALL, "limits", properties, "remediation", DruidRuleCatalog.REMEDIATION,
            "credentialMode", "SINGLE_PAIR", "retentionMinutes", 30, "maxTasks", 20); }
    public synchronized Task active() { return active == null ? null : snapshot(active); }
    public synchronized List<Task> history() { prune(); return jobs.values().stream().map(this::snapshot).toList().reversed(); }
    public synchronized Task task(String id) { return snapshot(find(id)); }
    public synchronized Page results(String id, int page, int size) {
        if (page < 1 || page > 100 || size < 1 || size > 50) throw new ApiException(400, "INVALID_PAGE", "结果分页需在1～50条内");
        Job job = find(id);
        var all = job.inputs.stream().map(i -> job.results.get(i.id())).filter(Objects::nonNull).map(Summary::of).toList();
        return new Page(page, size, all.size(), all.stream().skip((long) (page - 1) * size).limit(size).toList());
    }
    public synchronized TargetResult result(String id, String targetId) {
        TargetResult result = find(id).results.get(targetId);
        if (result == null) throw new ApiException(404, "DRUID_RESULT_NOT_FOUND", "目标结果尚未产生或不存在");
        return result;
    }
    public Task submit(ScanRequest request) {
        if (request == null || request.targets() == null || request.targets().isEmpty() || request.targets().size() > properties.maxTargets())
            throw new ApiException(400, "INVALID_DRUID_SCAN", "请提供配额内的显式目标列表");
        List<URI> roots = request.targets().stream().map(DruidTargetPolicy::normalize).distinct().toList();
        return create(request.requestKey(), "ANONYMOUS", roots, null);
    }
    public Task credentials(CredentialRequest request) {
        if (request == null) throw new ApiException(400, "INVALID_CREDENTIAL_REQUEST", "凭据验证请求为空");
        URI root = DruidTargetPolicy.normalize(request.target());
        if (root.getScheme().equals("http") && !Boolean.TRUE.equals(request.allowInsecureHttp()))
            throw new ApiException(400, "INSECURE_HTTP_CONFIRMATION_REQUIRED", "HTTP传递凭据前需确认可信网络和明文传输限制");
        if (!field(request.username(), 256, true) || !field(request.password(), 2048, false))
            throw new ApiException(400, "INVALID_CREDENTIAL_FIELD", "凭据为空、过长或含控制字符");
        var secret = new DruidScanner.Secret(request.username(), request.password());
        try { return create(request.requestKey(), "CREDENTIAL", List.of(root), secret); }
        catch (RuntimeException failure) { secret.clear(); throw failure; }
    }
    private static boolean field(String value, int limit, boolean required) {
        return value != null && value.length() <= limit && (!required || !value.isBlank()) && value.codePoints().noneMatch(Character::isISOControl);
    }
    private synchronized Task create(String key, String kind, List<URI> roots, DruidScanner.Secret secret) {
        try { UUID.fromString(key); } catch (RuntimeException invalid) { throw new ApiException(400, "INVALID_REQUEST_KEY", "需要有效请求UUID"); }
        prune();
        for (Job job : jobs.values()) if (job.key.equals(key)) {
            if (!job.kind.equals(kind) || !job.inputs.stream().map(Input::root).toList().equals(roots))
                throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同请求标识对应不同输入");
            if (secret != null) secret.clear();
            return snapshot(job); // 不比较或保存密码副本；再次验证必须使用新requestKey。
        }
        requireIdle();
        if (jobs.size() >= 20) throw new ApiException(503, "DRUID_HISTORY_FULL", "结果保留数量已满，请先清空已结束结果");
        Job job = new Job(key, kind, roots); jobs.put(job.id, job); active = job;
        coordinator.submit(() -> execute(job, secret));
        LOG.info("Druid检测开始 taskId={} kind={} targets={}", job.id, kind, roots.size());
        return snapshot(job);
    }
    private void execute(Job job, DruidScanner.Secret secret) {
        synchronized (this) { if (!job.cancelled) job.status = "RUNNING"; }
        var groups = new LinkedHashMap<String, List<Input>>();
        job.inputs.forEach(input -> groups.computeIfAbsent(DruidTargetPolicy.origin(input.root()), ignored -> new ArrayList<>()).add(input));
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (var group : groups.values()) futures.add(workers.submit(() -> {
                for (Input input : group) {
                    TargetResult result = scanner.scan(input.id(), input.root(), () -> job.cancelled, secret);
                    synchronized (this) {
                        // 结果上限保护；正文不持久化，只保存固定字段摘要。
                        if (json.writeValueAsBytes(result).length > 2 * 1024 * 1024 / properties.maxTargets())
                            throw new IllegalStateException("Druid单目标结果超限");
                        job.results.put(input.id(), result);
                    }
                }
            }));
            for (Future<?> future : futures) future.get();
        } catch (InterruptedException failure) { job.cancelled = true; Thread.currentThread().interrupt(); job.errorCode = "CANCELLED"; }
        catch (ExecutionException | RuntimeException failure) {
            job.cancelled = true; job.errorCode = "DRUID_INTERNAL_ERROR";
            LOG.warn("Druid任务失败 taskId={} type={}", job.id, failure.getClass().getSimpleName());
        } finally {
            // 不通过Future.cancel将仍存活的DNS/IO错误标为已退出；等待其他工作真正结束。
            boolean interrupted = Thread.interrupted();
            for (var future : futures) while (!future.isDone()) {
                try { future.get(); } catch (InterruptedException failure) { interrupted = true; }
                catch (ExecutionException failure) { break; }
            }
            if (secret != null) secret.clear();
            synchronized (this) {
                job.status = job.errorCode != null ? "FAILED" : job.cancelled ? "CANCELLED" : "COMPLETED";
                job.finishedAt = Instant.now(); active = null;
            }
            LOG.info("Druid检测结束 taskId={} status={} done={}", job.id, job.status, job.results.size());
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    public synchronized Task cancel(String id) {
        Job job = find(id); if (job == active) { job.cancelled = true; job.status = "CANCELLING"; }
        return snapshot(job);
    }
    public synchronized Object clear() { requireIdle(); int count = jobs.size(); jobs.clear(); return Map.of("cleared", count); }
    public synchronized byte[] export(ExportRequest request) {
        requireIdle();
        if (request == null) throw new ApiException(400, "INVALID_EXPORT", "导出请求为空");
        Job job = find(request.taskId());
        return DruidExport.generate(request, job.inputs.stream().map(i -> job.results.get(i.id())).filter(Objects::nonNull).toList());
    }
    private Task snapshot(Job job) { return new Task(job.id, job.kind, job.status, job.inputs.size(), job.results.size(),
            DruidRuleCatalog.VERSION, job.createdAt, job.finishedAt, job.errorCode); }
    private void requireIdle() { if (active != null || closing) throw new ApiException(409, "DRUID_SCAN_BUSY", "正在检测或清理，请结束后操作"); }
    private void prune() { jobs.values().removeIf(job -> job != active && job.finishedAt != null && job.finishedAt.isBefore(Instant.now().minus(Duration.ofMinutes(30)))); }
    private Job find(String id) { prune(); Job job = jobs.get(id); if (job == null) throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在或结果已过保留期"); return job; }
    @PreDestroy public void close() {
        synchronized (this) { closing = true; if (active != null) active.cancelled = true; }
        coordinator.shutdown(); workers.shutdown();
        try { if (!coordinator.awaitTermination(15, TimeUnit.SECONDS)) LOG.warn("Druid底层IO仍在收尾，服务关闭未宣称立即释放"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }
}
