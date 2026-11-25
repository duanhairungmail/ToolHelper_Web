package com.toolhelper.service;

import com.toolhelper.config.DeviceMacProperties;
import com.toolhelper.devicemac.*;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import static com.toolhelper.devicemac.DeviceMacModels.*;

/** 单个模块任务、有限目标并发；进程退出后才允许新任务占用槽位。 */
@Service
public class DeviceMacService {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceMacService.class);
    private static final Set<String> FATAL = Set.of("COMMAND_UNAVAILABLE", "COMMAND_FAILED", "ACCESS_DENIED", "UNSUPPORTED_PLATFORM");
    private final MacResolver resolver;
    private final DeviceMacStore store;
    private final DeviceMacProperties properties;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("mac-coordinator").factory());
    private final ExecutorService workers;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private Job active;
    private boolean closing;
    public DeviceMacService(MacResolver resolver, DeviceMacStore store, DeviceMacProperties properties) {
        this.resolver = resolver; this.store = store; this.properties = properties;
        workers = Executors.newFixedThreadPool(properties.concurrency(), Thread.ofPlatform().name("mac-query-", 0).factory());
    }
    public Map<String, Object> capabilities() { return resolver.capabilities(); }
    public Ledger ledger() { return store.read(); }
    public synchronized Ledger save(SaveRequest request) { requireIdle(); return store.save(request); }
    public synchronized byte[] export(ExportRequest request) { requireIdle(); return DeviceMacExport.generate(request, store); }
    private void requireIdle() { if (active != null || closing) throw new ApiException(409, "MAC_QUERY_BUSY", "正在查询或清理，请结束后再操作"); }
    public synchronized Task active() { return active == null ? null : snapshot(active); }
    public synchronized Task task(String id) { prune(); return snapshot(find(id)); }
    private Job find(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ApiException(404, "TASK_NOT_FOUND", "任务不存在或已过保留期");
        return job;
    }
    public synchronized Task cancel(String id) {
        Job job = find(id);
        if (job == active) { job.cancelled = true; job.status = "CANCELLING"; }
        return snapshot(job);
    }
    public synchronized Task submit(QueryRequest request) {
        if (request == null || !DeviceMacStore.uuid(request.requestKey()) || request.items() == null
                || request.items().isEmpty() || request.items().size() > properties.maxRows())
            throw new ApiException(400, "INVALID_QUERY", "查询需要请求 UUID 和配额内的设备行");
        Set<String> ids = new HashSet<>(); List<Item> items = new ArrayList<>();
        for (Item item : request.items()) {
            if (item == null || !DeviceMacStore.uuid(item.recordId()) || !ids.add(item.recordId()) || item.ipAddress() == null || item.ipAddress().length() > properties.maxFieldCharacters())
                throw new ApiException(400, "INVALID_QUERY", "查询行标识重复或字段不正确");
            items.add(new Item(item.recordId(), item.ipAddress().trim()));
        }
        prune();
        for (Job job : jobs.values()) if (job.key.equals(request.requestKey())) {
            if (!job.items.equals(items)) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "相同请求标识不能用于不同目标");
            return snapshot(job);
        }
        requireIdle();
        if (jobs.size() >= 20) throw new ApiException(503, "TASK_HISTORY_FULL", "任务记录已达上限，请稍后重试");
        Job job = new Job(request.requestKey(), List.copyOf(items)); jobs.put(job.id, job); active = job;
        coordinator.submit(() -> execute(job));
        LOG.info("MAC查询开始 taskId={} rows={}", job.id, job.items.size());
        return snapshot(job);
    }
    private void execute(Job job) {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        synchronized (this) { if (!job.cancelled) job.status = "RUNNING"; }
        for (Item item : job.items) {
            if (item.ipAddress().isBlank()) { finishRows(job, List.of(item), new Outcome("SKIPPED", "空 IP，已跳过", null)); continue; }
            try { groups.computeIfAbsent(MacValues.ipv4(item.ipAddress()), key -> new ArrayList<>()).add(item); }
            catch (ApiException error) { finishRows(job, List.of(item), new Outcome(error.code(), error.getMessage(), null)); }
        }
        List<Map.Entry<String, List<Item>>> pending = new ArrayList<>(groups.entrySet());
        var completion = new ExecutorCompletionService<Completed>(workers);
        int next = 0, inFlight = 0;
        try {
            // 只保留 concurrency 个执行中的目标，不预先排队全部地址；取消后不再创建探测。
            while (next < pending.size() || inFlight > 0) {
                while (!job.cancelled && next < pending.size() && inFlight < properties.concurrency()) {
                    var group = pending.get(next++); inFlight++;
                    completion.submit(() -> {
                        Outcome result;
                        try { result = job.cancelled ? WindowsMacResolver.failure("QUERY_CANCELLED") : resolver.resolve(group.getKey(), () -> job.cancelled); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); result = WindowsMacResolver.failure("QUERY_CANCELLED"); }
                        catch (RuntimeException error) { LOG.warn("MAC目标执行异常 taskId={} type={}", job.id, error.getClass().getSimpleName()); result = WindowsMacResolver.failure("COMMAND_FAILED"); }
                        return new Completed(group.getValue(), result);
                    });
                }
                if (inFlight == 0) break;
                Future<Completed> completed = completion.take(); inFlight--;
                Completed result = completed.get();
                finishRows(job, result.items(), result.outcome());
                if (FATAL.contains(result.outcome().status())) {
                    synchronized (this) { if (job.errorCode == null) { job.errorCode = result.outcome().status(); job.message = result.outcome().message(); } }
                    job.cancelled = true;
                }
            }
            for (; next < pending.size(); next++) finishRows(job, pending.get(next).getValue(), WindowsMacResolver.failure("QUERY_CANCELLED"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); job.cancelled = true;
            synchronized (this) { job.errorCode = "QUERY_CANCELLED"; job.message = "服务正在结束任务"; }
        } catch (ExecutionException error) {
            job.cancelled = true;
            synchronized (this) { job.errorCode = "COMMAND_FAILED"; job.message = "查询工作线程异常"; }
            LOG.error("MAC任务失败 taskId={}", job.id, error);
        } finally {
            // 即使协调线程被中断，也必须等目标清理完毕，不能提前把模块标为空闲。
            boolean interrupted = Thread.interrupted();
            if (inFlight > 0) job.cancelled = true;
            while (inFlight > 0) {
                try {
                    Future<Completed> completed = completion.take(); inFlight--;
                    Completed result = completed.get();
                    finishRows(job, result.items(), result.outcome());
                } catch (InterruptedException error) { interrupted = true; }
                catch (ExecutionException error) { LOG.warn("MAC清理阶段工作线程失败 taskId={}", job.id); }
            }
            synchronized (this) {
                for (Item item : job.items) if (!job.results.containsKey(item.recordId()))
                    job.results.put(item.recordId(), new RowResult(item.recordId(), item.ipAddress(), "QUERY_CANCELLED", "查询已取消", null));
                job.status = job.errorCode != null ? "FAILED" : job.cancelled ? "CANCELLED" : "COMPLETED";
                job.finishedAt = Instant.now(); active = null;
                LOG.info("MAC查询结束 taskId={} status={} done={}", job.id, job.status, job.results.size());
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private synchronized void finishRows(Job job, List<Item> items, Outcome outcome) {
        for (Item item : items) job.results.put(item.recordId(), new RowResult(item.recordId(), item.ipAddress(), outcome.status(), outcome.message(), outcome.observation()));
    }
    private Task snapshot(Job job) {
        List<RowResult> ordered = job.items.stream().map(i -> job.results.get(i.recordId())).filter(Objects::nonNull).toList();
        return new Task(job.id, job.status, job.items.size(), ordered.size(), ordered, job.errorCode, job.message, job.created);
    }
    private void prune() {
        Instant before = Instant.now().minus(Duration.ofMinutes(30));
        jobs.values().removeIf(job -> job != active && job.finishedAt != null && job.finishedAt.isBefore(before));
    }
    @PreDestroy public void close() {
        synchronized (this) { closing = true; if (active != null) active.cancelled = true; }
        coordinator.shutdown();
        try {
            if (!coordinator.awaitTermination(properties.timeoutSeconds() + 5L, TimeUnit.SECONDS)) coordinator.shutdownNow();
            workers.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException error) { coordinator.shutdownNow(); workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    private record Completed(List<Item> items, Outcome outcome) {}
    private static class Job {
        final String id = UUID.randomUUID().toString(), key;
        final List<Item> items;
        final Instant created = Instant.now();
        final Map<String, RowResult> results = new HashMap<>();
        volatile boolean cancelled;
        String status = "QUEUED", errorCode, message = "";
        Instant finishedAt;
        Job(String key, List<Item> items) { this.key = key; this.items = items; }
    }
}
