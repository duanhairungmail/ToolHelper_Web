package com.toolhelper.service;

import com.toolhelper.config.PingProperties;
import com.toolhelper.ping.*;
import com.toolhelper.support.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import static com.toolhelper.ping.PingModels.*;

/** 一个模块任务槽位、有限目标并发、内存结果；停止后等待子进程清理再释放槽位。 */
@Service
public class PingService {
    private static final Logger LOG = LoggerFactory.getLogger(PingService.class);
    private final PingProperties properties;
    private final PingProbe probe;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ping-coordinator").factory());
    private final ExecutorService workers;
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private Job active;
    private boolean closing;
    public PingService(PingProperties properties, PingProbe probe) {
        this.properties = properties; this.probe = probe;
        workers = Executors.newFixedThreadPool(properties.maxConcurrency(), Thread.ofPlatform().name("ping-target-", 0).factory());
    }
    public Map<String, Object> capabilities() {
        return Map.of("platform", probe.platform(), "available", probe.available(), "inputs", List.of("IPV4", "CIDR", "LAST_OCTET_RANGE", "MULTILINE", "UTF8_TXT"),
                "limits", Map.ofEntries(Map.entry("maxTargets", properties.maxTargets()), Map.entry("maxConcurrency", properties.maxConcurrency()),
                        Map.entry("defaultConcurrency", Math.min(50, properties.maxConcurrency())), Map.entry("minCount", 1), Map.entry("maxCount", 10),
                        Map.entry("defaultCount", 4), Map.entry("minTimeoutMs", 100), Map.entry("maxTimeoutMs", 10000),
                        Map.entry("defaultTimeoutMs", 1000), Map.entry("maxFileBytes", properties.maxFileBytes()),
                        Map.entry("maxRequestBytes", properties.maxRequestBytes()), Map.entry("taskRetentionMinutes", properties.retentionMinutes())),
                "statuses", Map.ofEntries(Map.entry("RESPONSIVE", "有响应"), Map.entry("PARTIAL_LOSS", "部分丢包"),
                        Map.entry("NO_REPLY", "无响应"), Map.entry("ERROR", "探测错误"), Map.entry("CANCELLED", "已停止")));
    }
    public ParsedTargets parse(ParseRequest request) { return PingTargets.parse(request, properties); }
    public synchronized Task active() { return active == null ? null : snapshot(active); }
    public synchronized Task task(String id) { prune(); return snapshot(find(id)); }
    private Job find(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ApiException(404, "PING_TASK_NOT_FOUND", "任务不存在或已过保留期");
        return job;
    }
    public synchronized Task submit(TaskRequest request) {
        if (request == null || !isUuid(request.requestKey())
                || request.targets() == null || request.targets().isEmpty() || request.targets().size() > properties.maxTargets())
            throw new ApiException(400, "INVALID_PING_TASK", "请求标识或目标列表不正确");
        List<String> targets = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (String target : request.targets()) {
            long ip = PingTargets.ipv4(target);
            String normalized = PingTargets.format(ip);
            if (!unique.add(normalized)) throw new ApiException(400, "INVALID_PING_TASK", "目标列表中存在重复 IPv4 地址");
            targets.add(normalized);
        }
        int concurrency = value(request.concurrency(), 50, 1, properties.maxConcurrency(), "并发数须在 1 到服务上限之间");
        int count = value(request.count(), 4, 1, 10, "每目标次数须在 1–10 之间");
        int timeout = value(request.timeoutMs(), 1000, 100, 10000, "单次超时须在 100–10000ms 之间");
        Options options = new Options(concurrency, count, timeout);
        prune();
        for (Job job : jobs.values()) if (job.key.equals(request.requestKey())) {
            if (!job.targets.equals(targets) || !job.options.equals(options)) throw new ApiException(409, "PING_IDEMPOTENCY_CONFLICT", "相同请求标识不能用于不同目标或参数");
            return snapshot(job);
        }
        if (closing) throw new ApiException(503, "PING_SERVICE_STOPPING", "本机服务正在关闭");
        if (active != null) throw new ApiException(409, "PING_TASK_BUSY", "已有群 Ping 任务正在运行或清理");
        if (jobs.size() >= properties.maxTasks()) throw new ApiException(503, "PING_TASK_HISTORY_FULL", "内存任务记录达到上限，请稍后重试");
        if (!probe.available()) throw new ApiException(503, "PING_UNAVAILABLE", "当前操作系统的 Ping 工具不可用或平台不受支持");
        Job job = new Job(request.requestKey(), List.copyOf(targets), options); jobs.put(job.id, job); active = job;
        coordinator.submit(() -> execute(job));
        LOG.info("群Ping任务开始 taskId={} targets={} concurrency={} count={} timeoutMs={} platform={}", job.id, targets.size(), concurrency, count, timeout, probe.platform());
        return snapshot(job);
    }
    private static int value(Integer actual, int defaultValue, int min, int max, String message) {
        int value = actual == null ? defaultValue : actual;
        if (value < min || value > max) throw new ApiException(400, "INVALID_PING_OPTIONS", message);
        return value;
    }
    public synchronized Task cancel(String id) {
        Job job = find(id);
        if (job == active) { job.cancelled = true; job.status = "CANCELLING"; }
        return snapshot(job);
    }
    public synchronized byte[] export(ExportRequest request) {
        if (active != null) throw new ApiException(409, "PING_TASK_BUSY", "任务运行或清理期间不能导出");
        if (request == null || request.taskId() == null || request.taskId().length() > 36 || request.format() == null)
            throw new ApiException(400, "INVALID_EXPORT_REQUEST", "导出任务或格式不正确");
        Job job = find(request.taskId());
        if (!Set.of("csv", "xlsx").contains(request.format())) throw new ApiException(400, "INVALID_EXPORT_FORMAT", "仅支持 CSV 和 XLSX");
        List<Row> rows = ordered(job);
        if (rows.isEmpty()) throw new ApiException(409, "NO_EXPORT_DATA", "当前任务没有已完成结果");
        List<String> headers = List.of("目标IP", "结论", "成功次数", "探测次数", "丢包率", "平均RTT(ms)", "完成时间", "错误码", "说明");
        List<List<String>> table = new ArrayList<>(); table.add(headers);
        for (Row row : rows) {
            table.add(List.of(safe(row.ip()), label(row.status()), nullable(row.received()), nullable(row.sent()), row.lossPercent() == null ? "—" : String.format(Locale.ROOT, "%.1f%%", row.lossPercent()),
                    row.averageRttMs() == null ? "—" : row.rttBelowOneMs() ? "<1" : String.format(Locale.ROOT, "%.3f", row.averageRttMs()),
                    row.completedAt() == null ? "—" : row.completedAt().toString(), nullable(row.errorCode()), safe(row.message())));
        }
        if (request.format().equals("csv")) return csv(table).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return TableExport.generate("群 Ping 结果", "xlsx", table, new int[0]);
    }
    private static String nullable(Object value) { return value == null ? "—" : String.valueOf(value); }
    private static String safe(String value) { return value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " "); }
    private static String label(String value) { return switch (value) { case "RESPONSIVE" -> "有响应"; case "PARTIAL_LOSS" -> "部分丢包"; case "NO_REPLY" -> "无响应"; case "ERROR" -> "探测错误"; default -> "已停止"; }; }
    private static String csv(List<List<String>> table) {
        StringBuilder out = new StringBuilder("\uFEFF");
        for (List<String> row : table) {
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) out.append(',');
                String cell = safe(row.get(index));
                // CSV 防公式注入；导出是字符串，不让表格软件执行结果说明文本。
                if (cell.matches("^[\\s]*[=+@-].*")) cell = "'" + cell;
                out.append('"').append(cell.replace("\"", "\"\"")).append('"');
            }
            out.append("\r\n");
        }
        return out.toString();
    }
    private void execute(Job job) {
        synchronized (this) { if (!job.cancelled) job.status = "RUNNING"; }
        ExecutorCompletionService<Row> completion = new ExecutorCompletionService<>(workers);
        int next = 0, inFlight = 0;
        try {
            // 仅创建运行中目标的任务，不把 4096 个进程预先排入执行器队列。
            while (next < job.targets.size() || inFlight > 0) {
                while (!job.cancelled && next < job.targets.size() && inFlight < job.options.concurrency()) {
                    String ip = job.targets.get(next++); inFlight++;
                    completion.submit(() -> {
                        try { return probe.probe(ip, job.options, () -> job.cancelled); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); return SystemPingProbe.cancelled(ip); }
                        catch (RuntimeException error) { LOG.warn("群Ping目标执行异常 taskId={} type={}", job.id, error.getClass().getSimpleName()); return SystemPingProbe.error(ip, "PING_INTERNAL_ERROR", "本机探测失败，无法判断"); }
                    });
                }
                if (inFlight == 0) break;
                finish(completion.take().get()); inFlight--;
            }
            // 未启动目标不生成虚构结果行；停止后保留已完成行供导出。
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); job.cancelled = true; job.message = "服务正在结束任务";
        } catch (ExecutionException error) {
            job.cancelled = true; job.message = "本机探测工作线程异常"; LOG.error("群Ping任务协调失败 taskId={}", job.id, error);
        } finally {
            boolean interrupted = Thread.interrupted(); if (inFlight > 0) job.cancelled = true;
            // 任务槽仅在所有工作线程及其系统进程退出后释放。
            while (inFlight > 0) {
                try { finish(completion.take().get()); inFlight--; }
                catch (InterruptedException error) { interrupted = true; }
                catch (ExecutionException error) { inFlight--; }
            }
            synchronized (this) {
                job.status = job.cancelled ? "CANCELLED" : job.results().stream().anyMatch(r -> r.status().equals("ERROR")) ?
                        job.results().stream().allMatch(r -> r.status().equals("ERROR")) ? "FAILED" : "PARTIAL" : "SUCCEEDED";
                job.finishedAt = Instant.now(); active = null;
                LOG.info("群Ping任务结束 taskId={} status={} done={}", job.id, job.status, job.resultMap.size());
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private synchronized void finish(Row result) {
        if (active != null && !result.status().equals("CANCELLED")) active.resultMap.put(result.ip(), result);
    }
    private static List<Row> ordered(Job job) { return job.targets.stream().map(job.resultMap::get).filter(Objects::nonNull).toList(); }
    private Task snapshot(Job job) {
        List<Row> rows = ordered(job);
        int responsive = (int) rows.stream().filter(r -> r.status().equals("RESPONSIVE")).count();
        int partial = (int) rows.stream().filter(r -> r.status().equals("PARTIAL_LOSS")).count();
        int noReply = (int) rows.stream().filter(r -> r.status().equals("NO_REPLY")).count();
        int errors = (int) rows.stream().filter(r -> r.status().equals("ERROR")).count();
        int cancelled = job.status.equals("CANCELLED") ? Math.max(0, job.targets.size() - rows.size()) : 0;
        double[] averages = rows.stream().filter(r -> Set.of("RESPONSIVE", "PARTIAL_LOSS").contains(r.status()) && r.averageRttMs() != null).mapToDouble(Row::averageRttMs).toArray();
        Double average = averages.length == 0 ? null : Arrays.stream(averages).average().orElseThrow();
        int remaining = Math.max(0, job.targets.size() - rows.size());
        Summary summary = new Summary(rows.size(), activeStatuses().contains(job.status) ? remaining : 0, responsive, partial, noReply, errors, cancelled, average);
        return new Task(job.id, job.key, job.status, job.targets.size(), job.options, summary, rows, job.created, job.finishedAt, job.message);
    }
    private void prune() { Instant before = Instant.now().minus(Duration.ofMinutes(properties.retentionMinutes())); jobs.values().removeIf(job -> job != active && job.finishedAt != null && job.finishedAt.isBefore(before)); }
    private static Set<String> activeStatuses() { return Set.of("QUEUED", "RUNNING", "CANCELLING"); }
    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException error) { return false; }
    }
    @PreDestroy public void close() {
        synchronized (this) { closing = true; if (active != null) active.cancelled = true; }
        coordinator.shutdown();
        try {
            if (!coordinator.awaitTermination(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)) coordinator.shutdownNow();
            workers.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException error) { coordinator.shutdownNow(); workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }
    private static class Job {
        final String id = UUID.randomUUID().toString(), key; final List<String> targets; final Options options;
        final Instant created = Instant.now(); final Map<String, Row> resultMap = new HashMap<>(); volatile boolean cancelled;
        String status = "QUEUED", message = ""; Instant finishedAt;
        List<Row> results() { return ordered(this); }
        Job(String key, List<String> targets, Options options) { this.key = key; this.targets = targets; this.options = options; }
    }
}
