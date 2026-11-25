package com.toolhelper.service;

import com.toolhelper.support.ApiException;
import com.toolhelper.validation.*;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import static com.toolhelper.validation.ValidationModels.*;

/** 目标Token和任务仅驻留内存，与外挂磁盘任务完全分离。单个连接修订防止旧页面操作新目标。 */
@Service
public class ApiValidationService {
    private static final Logger LOG = LoggerFactory.getLogger(ApiValidationService.class);
    private static final int MAX_FIELD_CHARACTERS = 4096;
    private static final int MAX_TABLE_CHARACTERS = 8 * 1024 * 1024;
    private final TargetApiClient client;
    private final int maxPages, maxRows;
    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("api-validation").factory());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("api-detection").factory());
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();
    private URI origin;
    private String token, connectionId;
    private Job active, last;
    private List<Device> devices = List.of();
    private List<Topic> topics = List.of();
    private List<Result> results = List.of();
    private boolean autoRunning;
    private int cycleCount;
    private String cronText = "", autoStopReason = "";
    private ZonedDateTime nextRun;
    private ScheduledFuture<?> scheduled;
    private final List<Map<String, Object>> history = new ArrayList<>();

    @Autowired
    public ApiValidationService(TargetApiClient client,
            @Value("${toolhelper.api-validation.max-pages:1000}") int maxPages,
            @Value("${toolhelper.api-validation.max-rows:100000}") int maxRows) {
        this(client, maxPages, maxRows, Clock.systemDefaultZone());
    }
    public ApiValidationService(TargetApiClient client, int maxPages, int maxRows, Clock clock) {
        if (maxPages < 1 || maxRows < 1) throw new IllegalArgumentException("分页配额必须为正数");
        this.client = client; this.maxPages = maxPages; this.maxRows = maxRows; this.clock = clock;
    }
    public synchronized Map<String, Object> login(LoginRequest request) throws InterruptedException {
        requireIdle();
        clearConnection(); // 新目标/失败登录均不能继续使用旧Token。
        URI target = TargetAddressPolicy.origin(request);
        String acquired = client.login(target, request);
        origin = target; token = acquired; connectionId = UUID.randomUUID().toString();
        LOG.info("API目标登录成功 connectionId={} transport={} format={}", connectionId, request.scheme(), request.loginFormat());
        return state();
    }
    public synchronized Map<String, Object> logout(String expected) {
        requireConnection(expected); requireIdle(); clearConnection(); return state();
    }
    public synchronized Map<String, Object> state() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("connected", token != null); value.put("connectionId", connectionId);
        value.put("target", origin == null ? null : origin.toString());
        value.put("busy", active != null); value.put("currentTask", last == null ? null : last.snapshot());
        value.put("devices", devices); value.put("topics", topics); value.put("results", results);
        value.put("auto", autoState()); value.put("singleTarget", true);
        return value;
    }
    public synchronized Map<String, Object> verify(RunRequest request) {
        requireConnection(request.connectionId()); requireIdle();
        var selected = LegacyApiCatalog.select(request.endpointIds(), false, Boolean.TRUE.equals(request.confirmedSideEffects()));
        return submit("VERIFY", selected, false).snapshot();
    }
    public synchronized Map<String, Object> query(String expected, boolean mqtt) {
        requireConnection(expected); requireIdle();
        return submit(mqtt ? "MQTT_TOPICS" : "DEVICE_IDS", List.of(), false).snapshot();
    }
    public synchronized Map<String, Object> task(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new ApiException(404, "VALIDATION_TASK_NOT_FOUND", "任务不存在，可能已因重启或退出登录清除");
        return job.snapshot();
    }
    public synchronized Map<String, Object> cancel(String id, String expected) {
        requireConnection(expected);
        Job job = jobs.get(id);
        if (job == null) throw new ApiException(404, "VALIDATION_TASK_NOT_FOUND", "任务不存在");
        if (job == active) { job.cancelled = true; if (job.thread != null) job.thread.interrupt(); }
        return job.snapshot();
    }
    private Job submit(String operation, List<LegacyApiCatalog.Endpoint> endpoints, boolean automatic) {
        if (active != null) throw new ApiException(409, "VALIDATION_BUSY", "已有接口验证任务正在执行");
        Job job = new Job(operation, automatic); active = last = job; jobs.put(job.id, job);
        while (jobs.size() > 20) jobs.remove(jobs.keySet().iterator().next());
        URI target = origin; String credential = token;
        worker.submit(() -> execute(job, target, credential, endpoints));
        LOG.info("API任务提交 taskId={} operation={} count={} automatic={}", job.id, operation, endpoints.size(), automatic);
        return job;
    }
    private void execute(Job job, URI target, String credential, List<LegacyApiCatalog.Endpoint> endpoints) {
        synchronized (this) { job.thread = Thread.currentThread(); job.status = "RUNNING"; }
        try {
            checkCancelled(job);
            if (job.operation.equals("VERIFY")) runVerification(job, target, credential, endpoints);
            else fetchPages(job, target, credential);
            synchronized (this) { checkCancelled(job); job.status = "SUCCEEDED"; }
        } catch (InterruptedException cancelled) {
            synchronized (this) { job.status = "CANCELLED"; job.message = "本机等待及后续请求已取消，已到达目标的请求不能撤回"; }
        } catch (ApiException failure) {
            synchronized (this) {
                job.status = "FAILED"; job.errorCode = failure.code(); job.message = failure.getMessage();
                if (failure.code().equals("TARGET_AUTH_REQUIRED")) {
                    token = null; stopSchedule("目标登录失效或权限不足，请手动重新登录");
                }
            }
            LOG.warn("API任务失败 taskId={} code={}", job.id, failure.code());
        } catch (RuntimeException failure) {
            // 不把携带上游值的异常对象交给全局日志；状态仍明确失败，禁止静默吞错。
            synchronized (this) { job.status = "FAILED"; job.errorCode = "VALIDATION_INTERNAL_ERROR"; job.message = "本机验证失败，请按任务标识核对"; }
            LOG.error("API任务内部失败 taskId={} type={}", job.id, failure.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                if (active == job) active = null;
                job.thread = null;
                if (job.automatic && job.status.equals("SUCCEEDED")) {
                    int count = job.rows.size();
                    long passed = job.rows.stream().filter(item -> Set.of("HTTP_OK", "BUSINESS_OK").contains(item.outcome())).count();
                    history.add(Map.of("cycle", cycleCount, "time", clock.instant().toString(), "total", count, "passed", passed));
                    if (history.size() > 10) history.remove(0);
                }
                if (job.automatic && job.status.equals("FAILED")) stopSchedule(job.message);
            }
            Thread.interrupted(); // 线程池下一个任务不能继承上一轮的取消中断。
            LOG.info("API任务结束 taskId={} status={}", job.id, job.status);
        }
    }
    private void runVerification(Job job, URI target, String credential, List<LegacyApiCatalog.Endpoint> endpoints) throws InterruptedException {
        synchronized (this) { job.total = endpoints.size(); results = List.of(); }
        String deviceId = "", missing = "设备列表没有可用ID";
        if (endpoints.stream().anyMatch(LegacyApiCatalog.Endpoint::needsDevice)) {
            try {
                var page = client.page(target, credential, 1, 1);
                checkCancelled(job);
                if (!page.rows().isEmpty()) deviceId = displayField(page.rows().getFirst(), "id");
            } catch (ApiException failure) {
                if (failure.code().equals("TARGET_AUTH_REQUIRED")) throw failure;
                missing = "预取设备ID失败：" + failure.code();
            }
        }
        for (var endpoint : endpoints) {
            checkCancelled(job);
            long start = System.nanoTime(); Result row;
            if (endpoint.needsDevice() && deviceId.isBlank()) {
                row = new Result(job.rows.size() + 1, endpoint.id(), endpoint.name(), endpoint.method(), endpoint.path(), null, 0, false, "SKIPPED", missing);
            } else {
                String path = endpoint.path().replace("{deviceId}", TargetAddressPolicy.encode(deviceId));
                try {
                    var reply = client.call(target, credential, endpoint.method(), path);
                    String outcome = reply.status() < 200 || reply.status() >= 300 ? "HTTP_ERROR"
                            : TargetApiClient.businessFailure(reply.body()) ? "BUSINESS_ERROR"
                            : !reply.json() ? "INVALID_RESPONSE"
                            : TargetApiClient.businessSuccess(reply.body()) ? "BUSINESS_OK" : "HTTP_OK";
                    row = new Result(job.rows.size() + 1, endpoint.id(), endpoint.name(), endpoint.method(), endpoint.path(), reply.status(), elapsed(start), true, outcome,
                            outcome.equals("HTTP_OK") ? "HTTP成功，未确认业务码" : outcome.equals("BUSINESS_OK") ? "HTTP及已识别业务码成功" : "目标响应未通过（不显示原始敏感内容）");
                } catch (ApiException failure) {
                    if (failure.code().equals("TARGET_AUTH_REQUIRED")) {
                        append(job, new Result(job.rows.size() + 1, endpoint.id(), endpoint.name(), endpoint.method(), endpoint.path(), failure instanceof TargetApiClient.AuthException auth ? auth.upstreamStatus : null, elapsed(start), true, "AUTH_REQUIRED", failure.getMessage()));
                        throw failure;
                    }
                    row = new Result(job.rows.size() + 1, endpoint.id(), endpoint.name(), endpoint.method(), endpoint.path(), null, elapsed(start), false,
                            failure.code().equals("TARGET_TIMEOUT") ? "TIMEOUT" : "NETWORK_ERROR", failure.getMessage());
                }
            }
            append(job, row);
        }
    }
    private synchronized void append(Job job, Result row) throws InterruptedException {
        checkCancelled(job);
        job.rows.add(row); job.done = job.rows.size(); results = List.copyOf(job.rows);
    }
    /** 按真实totalCount读取全部页；无总数时继续到空页，重复页/总数变化/限额均明确失败而非伪报全量。 */
    private void fetchPages(Job job, URI target, String credential) throws InterruptedException {
        List<Device> deviceRows = new ArrayList<>(); List<Topic> topicRows = new ArrayList<>();
        Set<String> seenPages = new HashSet<>(), ids = new HashSet<>(); Long expected = null;
        long characters = 0;
        for (int page = 1; page <= maxPages; page++) {
            checkCancelled(job);
            var response = client.page(target, credential, page, 100);
            checkCancelled(job);
            if (response.total() != null) {
                if (expected != null && !expected.equals(response.total())) throw new ApiException(409, "PAGINATION_CHANGED", "设备总数在分页期间发生变化，请重新查询");
                expected = response.total();
                if (expected > maxRows) throw new ApiException(422, "PAGINATION_LIMIT", "设备总数超出配置上限，不会把部分结果当作全量");
            }
            if (!response.rows().isEmpty() && !seenPages.add(pageDigest(response.rows().toString()))) throw new ApiException(422, "PAGINATION_REPEATED", "目标重复返回相同页，已停止，不能确认全量");
            for (var item : response.rows()) {
                checkCancelled(job);
                String id = displayField(item, "id");
                if (!id.isEmpty() && !ids.add(id)) throw new ApiException(409, "PAGINATION_REPEATED", "分页存在重复设备ID，不能确认全量");
                Device device = new Device(displayField(item, "deviceName"), displayField(item, "comAddress"), id);
                Topic topic = new Topic(displayField(item, "gateName"), displayField(item, "mqttDownTopic", "mqttDown", "downTopic"), displayField(item, "mqttUpTopic", "mqttUp", "upTopic"));
                characters += (long) device.name().length() + device.address().length() + id.length()
                        + topic.gateName().length() + topic.downTopic().length() + topic.upTopic().length();
                if (characters > MAX_TABLE_CHARACTERS) throw new ApiException(422, "DEVICE_DATA_TOO_LARGE", "设备与主题文本超出内存配额，未发布不完整结果");
                deviceRows.add(device); topicRows.add(topic);
                if (deviceRows.size() > maxRows) throw new ApiException(422, "PAGINATION_LIMIT", "设备数量超出配置上限，未发布不完整结果");
            }
            synchronized (this) { job.done = deviceRows.size(); job.total = expected == null ? 0 : expected.intValue(); job.page = page; }
            boolean finished = expected == null ? response.rows().isEmpty() : deviceRows.size() == expected;
            if (expected != null && (deviceRows.size() > expected || response.rows().isEmpty() && !finished)) throw new ApiException(422, "PAGINATION_INVALID", "设备条数与总数不符，不能确认全量");
            if (finished) {
                synchronized (this) {
                    checkCancelled(job);
                    if (job.operation.equals("MQTT_TOPICS")) topics = List.copyOf(topicRows); else devices = List.copyOf(deviceRows);
                    job.message = "已读取全部页，共 " + deviceRows.size() + " 条";
                }
                return;
            }
        }
        throw new ApiException(422, "PAGINATION_LIMIT", "超过分页页数上限，未发布不完整结果");
    }
    public synchronized Map<String, Object> startSchedule(ScheduleRequest request) {
        requireConnection(request.connectionId()); requireIdle();
        var endpoints = LegacyApiCatalog.select(request.endpointIds(), true, false);
        ValidationCron cron = ValidationCron.parse(request.cron());
        nextRun = cron.next(ZonedDateTime.now(clock));
        cronText = request.cron(); autoRunning = true; autoStopReason = ""; cycleCount = 0; history.clear();
        scheduleNext(cron, endpoints);
        LOG.info("API自动检测启动 count={} cron={}", endpoints.size(), cronText);
        return autoState();
    }
    private void scheduleNext(ValidationCron cron, List<LegacyApiCatalog.Endpoint> endpoints) {
        // 向上取整，避免在整秒前不足1ms时零延迟触发，再计算出同一个Cron时间。
        Duration remaining = Duration.between(clock.instant(), nextRun.toInstant());
        long delay = remaining.isNegative() ? 0 : remaining.toMillis() + (remaining.getNano() % 1_000_000 == 0 ? 0 : 1);
        scheduled = scheduler.schedule(() -> {
            synchronized (this) {
                if (!autoRunning || token == null) return;
                if (active == null) { cycleCount++; submit("VERIFY", endpoints, true); }
                // 触发点已过去或上一轮占用时跳过，不补跑、不堆积并行请求。
                ZonedDateTime now = ZonedDateTime.now(clock);
                nextRun = cron.next(now.isBefore(nextRun) ? nextRun : now);
                scheduleNext(cron, endpoints);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }
    public synchronized Map<String, Object> preview(String cron) {
        return Map.of("nextRun", ValidationCron.parse(cron).next(ZonedDateTime.now(clock)).toString(), "zone", clock.getZone().toString());
    }
    public synchronized Map<String, Object> stopAuto(String expected) {
        requireConnection(expected); stopSchedule("用户已停止");
        if (active != null && active.automatic) { active.cancelled = true; if (active.thread != null) active.thread.interrupt(); }
        return autoState();
    }
    public synchronized Map<String, Object> autoState() {
        long total = history.stream().mapToLong(row -> ((Number) row.get("total")).longValue()).sum();
        long passed = history.stream().mapToLong(row -> ((Number) row.get("passed")).longValue()).sum();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("running", autoRunning); state.put("cron", cronText); state.put("nextRun", nextRun == null ? null : nextRun.toString());
        state.put("zone", clock.getZone().toString()); state.put("cycles", cycleCount); state.put("history", List.copyOf(history));
        state.put("successRate", total == 0 ? null : passed * 100.0 / total); state.put("stopReason", autoStopReason);
        return state;
    }
    public synchronized byte[] export(String expected, String view, String format) {
        if (connectionId == null || !Objects.equals(expected, connectionId)) throw new ApiException(409, "TARGET_CHANGED", "连接已更换或结果已清除，请刷新后重试");
        return ValidationExport.generate(view, format, results, devices, topics);
    }
    private void stopSchedule(String reason) { autoRunning = false; nextRun = null; autoStopReason = reason; if (scheduled != null) scheduled.cancel(false); }
    private void requireConnection(String expected) {
        if (token == null) throw new ApiException(409, "TARGET_LOGIN_REQUIRED", "请先登录目标系统");
        if (!Objects.equals(connectionId, expected)) throw new ApiException(409, "TARGET_CHANGED", "连接已由其他窗口更换，请刷新状态后确认目标");
    }
    private void requireIdle() { if (active != null || autoRunning) throw new ApiException(409, "VALIDATION_BUSY", "请先停止自动检测或等待当前任务结束"); }
    private void clearConnection() {
        stopSchedule(""); cronText = ""; cycleCount = 0;
        token = null; origin = null; connectionId = null; devices = List.of(); topics = List.of(); results = List.of(); jobs.clear(); last = null; history.clear();
    }
    private static String displayField(tools.jackson.databind.JsonNode item, String... keys) {
        String value = TargetApiClient.value(item, keys);
        if (value.length() > MAX_FIELD_CHARACTERS) throw new ApiException(422, "DEVICE_DATA_TOO_LARGE", "设备字段超过4096字符上限，未截断或发布不完整结果");
        return value;
    }
    private static String pageDigest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("本机SHA-256不可用"); }
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void checkCancelled(Job job) throws InterruptedException { if (job.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    @PreDestroy public synchronized void close() {
        stopSchedule("服务关闭"); if (active != null) active.cancelled = true;
        worker.shutdownNow(); scheduler.shutdownNow(); token = null;
    }
    private static final class Job {
        final String id = UUID.randomUUID().toString(), operation;
        final boolean automatic;
        final List<Result> rows = new ArrayList<>();
        String status = "QUEUED", message = "", errorCode = "";
        int done, total, page;
        volatile boolean cancelled;
        Thread thread;
        Job(String operation, boolean automatic) { this.operation = operation; this.automatic = automatic; }
        Map<String, Object> snapshot() {
            return Map.of("taskId", id, "operation", operation, "status", status, "done", done, "total", total, "page", page,
                    "results", List.copyOf(rows), "message", message, "errorCode", errorCode, "automatic", automatic);
        }
    }
}
