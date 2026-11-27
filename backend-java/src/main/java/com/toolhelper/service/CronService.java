package com.toolhelper.service;

import com.toolhelper.config.CronProperties;
import com.toolhelper.cron.*;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import static com.toolhelper.cron.CronModels.*;
import static com.toolhelper.cron.CronDialect.*;

@Service
public class CronService {
    private static final Logger LOG = LoggerFactory.getLogger(CronService.class);
    private final CronProperties properties;
    private final CronCalculator calculator;
    public CronService(CronProperties properties) {
        this.properties = properties;
        calculator = new CronCalculator(properties);
    }
    public Map<String, Object> capabilities() {
        return Map.of("modes", List.of(SIX, FIVE), "defaultZone", "Asia/Shanghai", "zones", ZoneId.getAvailableZoneIds().stream().filter(z -> z.contains("/") || z.equals("UTC")).sorted().toList(),
                "maxCount", 100, "defaultCount", 10, "windowYears", 8, "templates", Map.of(SIX, templates(true), FIVE, templates(false)));
    }
    public static List<Template> templates(boolean six) {
        if (!six) return List.of(new Template("每分钟", "* * * * *"), new Template("每5分钟", "*/5 * * * *"),
                new Template("每小时", "0 * * * *"), new Template("每天凌晨2点", "0 2 * * *"), new Template("每天中午12点", "0 12 * * *"),
                new Template("每周一9点", "0 9 * * 1"), new Template("每月1日", "0 0 1 * *"), new Template("每年1月1日", "0 0 1 1 *"), new Template("周一至周五9点和18点", "0 9,18 * * 1-5"));
        return List.of(new Template("每秒", "* * * * * ?"), new Template("每分钟", "0 * * * * ?"), new Template("每5分钟", "0 */5 * * * ?"),
                new Template("每小时", "0 0 * * * ?"), new Template("每天凌晨2点", "0 0 2 * * ?"), new Template("每天中午12点", "0 0 12 * * ?"),
                new Template("每周一9点", "0 0 9 ? * MON"), new Template("每月1日", "0 0 0 1 * ?"), new Template("每年1月1日", "0 0 0 1 1 ?"),
                new Template("周一至周五9点和18点", "0 0 9,18 ? * MON-FRI"), new Template("每月最后一天9点", "0 0 9 L * ?"), new Template("每月第二个周一9点", "0 0 9 ? * MON#2"));
    }
    public CompletableFuture<Analysis> analyze(AnalyzeRequest request) {
        if (request == null) throw invalid("INVALID_CRON_FIELDS", "请提供表达式与时间参数");
        return calculator.submit(() -> compute(request));
    }
    public CompletableFuture<Analysis> generate(GenerateRequest request) {
        if (request == null) throw invalid("INVALID_CRON_FIELDS", "请提供生成规则");
        return calculator.submit(() -> compute(new AnalyzeRequest(request.mode(), CronDialect.generate(request.mode(), request.fields()), request.zoneId(), request.startLocal(), request.startOffset(), request.count())));
    }
    private Analysis compute(AnalyzeRequest request) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.timeoutMs());
        CronDialect.Parsed parsed = CronDialect.parse(request.mode(), request.expression());
        int count = request.count() == null ? 10 : request.count();
        if (count < 1 || count > 100) throw invalid("INVALID_CRON_COUNT", "预览条数须在1–100之间");
        String zoneText = request.zoneId() == null ? "Asia/Shanghai" : request.zoneId();
        if ((!zoneText.contains("/") && !zoneText.equals("UTC")) || !ZoneId.getAvailableZoneIds().contains(zoneText))
            throw invalid("INVALID_CRON_ZONE", "请选择支持的 IANA 时区或 UTC，不接受 CST 等模糊缩写");
        ZoneId zone = ZoneId.of(zoneText);
        ZonedDateTime start = start(request.startLocal(), request.startOffset(), zone), cursor = start;
        if (start.getYear() < 1 || start.getYear() > 9991) throw invalid("INVALID_CRON_START", "起算年份须在1–9991之间");
        ZonedDateTime end = start.plusYears(8);
        List<TimeRow> times = new ArrayList<>(); String outcome = "COMPLETE";
        while (times.size() < count) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new ApiException(503, "CRON_CALCULATION_TIMEOUT", "Cron 计算超时，请简化表达式");
            ZonedDateTime next = parsed.expression().next(cursor);
            if (next == null) { outcome = "NO_FUTURE_TIME"; break; }
            if (next.toInstant().isAfter(end.toInstant())) { outcome = "WINDOW_END"; break; }
            if (!next.toInstant().isAfter(cursor.toInstant())) throw new IllegalStateException("Cron返回非递增执行时间");
            times.add(new TimeRow(next.toLocalDateTime().toString(), next.getOffset().toString(), next.toInstant().toString(), next.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA)));
            cursor = next;
        }
        LOG.debug("Cron预览完成 mode={} zone={} requested={} returned={} outcome={}", request.mode(), zoneText, count, times.size(), outcome);
        return new Analysis(request.mode(), parsed.normalized(), parsed.engine(), parsed.fields(), zoneText, start.toString(), List.copyOf(times), count, times.size(), end.toString(), outcome,
                List.of("星期0/7为周日、1为周一；周一至周五不包含法定节假日调整。", "步进按字段范围计算，并非从点击时刻起固定间隔；每月31日不自动顺延。", "夏令时跳过不存在的执行时刻，回拨时可能出现同一本地时间的两个偏移；以每行偏移和UTC瞬间区分。"));
    }
    private static ZonedDateTime start(String text, String offset, ZoneId zone) {
        if (text == null || text.isBlank()) {
            if (offset != null && !offset.isBlank()) throw invalid("INVALID_CRON_START", "指定偏移时必须提供本地起算时间");
            return ZonedDateTime.now(zone);
        }
        LocalDateTime local;
        try { local = LocalDateTime.parse(text); }
        catch (DateTimeException failure) { throw invalid("INVALID_CRON_START", "起算时间须为有效本地日期时间，例如2026-11-27T09:00:00"); }
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw invalid("CRON_START_TIME_GAP", "该本地起算时间处于夏令时缺口，请选择有效时间");
        if ((offset == null || offset.isBlank()) && offsets.size() > 1)
            throw invalid("CRON_START_OFFSET_REQUIRED", "该时间出现两次，请选择有效 UTC 偏移：" + offsets);
        ZoneOffset chosen = offsets.getFirst();
        if (offset != null && !offset.isBlank()) {
            try { chosen = ZoneOffset.of(offset); }
            catch (DateTimeException failure) { throw invalid("INVALID_CRON_START", "UTC 偏移格式不合法"); }
            if (!offsets.contains(chosen)) throw invalid("INVALID_CRON_START", "偏移与该时区的起算时间不符；有效值：" + offsets);
        }
        return ZonedDateTime.ofStrict(local, chosen, zone);
    }
    @PreDestroy public void close() { calculator.close(); }
}
