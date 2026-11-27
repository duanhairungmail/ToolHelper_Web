package com.toolhelper.service;

import com.toolhelper.config.CronProperties;
import com.toolhelper.cron.*;
import com.toolhelper.support.ApiException;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.toolhelper.cron.CronModels.*;
import static com.toolhelper.cron.CronDialect.*;
import static org.junit.jupiter.api.Assertions.*;

class CronServiceTest {
    private CronService service;
    @BeforeEach void open() { service = new CronService(new CronProperties(2, 8, 2000)); }
    @AfterEach void close() { service.close(); }
    private Analysis analyze(String mode, String expression, String zone, String from, String offset, int count) {
        try { return service.analyze(new AnalyzeRequest(mode, expression, zone, from, offset, count)).join(); }
        catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof ApiException error) throw error;
            throw failure;
        }
    }
    private Analysis six(String expression) { return analyze(SIX, expression, "Asia/Shanghai", "2026-11-27T00:00:00", null, 10); }
    private void code(String expected, Runnable action) { assertEquals(expected, assertThrows(ApiException.class, action::run).code()); }

    @Test void modesKeepFieldPositionsAndNormalizeOnlyWhitespaceAndAliases() {
        var five = analyze(FIVE, "  */5  * * * *  ", "Asia/Shanghai", "2026-11-27T00:00:00", null, 2);
        assertEquals("*/5 * * * *", five.normalizedExpression());
        assertEquals("0 */5 * * * *", five.engineExpression());
        assertEquals("minute", five.fields().getFirst().name());
        assertEquals("2026-11-27T00:05", five.times().getFirst().localDateTime());
        assertEquals("0 0 9 ? * MON", six("0 0 9 ? * mon").normalizedExpression());
        code("INVALID_CRON_FIELDS", () -> six("* * * * *"));
        code("INVALID_CRON_FIELDS", () -> six("0 0 9 * * ? 2027"));
        code("INVALID_CRON_FIELDS", () -> six("@daily"));
        code("INVALID_CRON_MODE", () -> analyze("QUARTZ", "0 * * * * ?", "UTC", null, null, 1));
    }
    @Test void strictGrammarRejectsBadFieldsAndDoesNotAcceptExtraCommands() {
        for (String text : List.of("60 * * * * ?", "0 */0 * * * ?", "0 10-5 * * * ?", "0 1,,2 * * * ?", "0 0 9 ? * MON#0", "0 0 9 ? * MON#6", "0 0 9 0W * ?", "? * * * * ?", "0 0 0 * FOO ?", "0 0 9 * * ? root reboot", "x".repeat(129)))
            assertThrows(ApiException.class, () -> six(text), text);
        for (String text : List.of("0 9 ? * 1", "0 9 * * MON", "0 9 L * *"))
            code("INVALID_CRON_FIELD_VALUE", () -> analyze(FIVE, text, "UTC", null, null, 1));
        ApiException field = assertThrows(ApiException.class, () -> six("0 60 9 * * ?"));
        assertTrue(field.getMessage().contains("第 2 字段（分"));
    }
    @Test void dayAndWeekConstraintsNeverSilentlyAdoptEngineAndSemantics() {
        code("CRON_DAY_WEEK_CONFLICT", () -> six("0 0 9 1 * MON"));
        code("CRON_DAY_WEEK_CONFLICT", () -> six("0 0 9 */1 * MON"));
        code("CRON_DAY_WEEK_CONFLICT", () -> analyze(FIVE, "0 9 1 * 1", "UTC", null, null, 1));
        assertEquals("2026-11-30T09:00", six("0 0 9 ? * MON").times().getFirst().localDateTime());
        assertEquals(six("0 0 9 ? * 0").times(), six("0 0 9 ? * 7").times());
        assertEquals("2026-11-29T09:00", six("0 0 9 ? * 0").times().getFirst().localDateTime());
    }
    @Test void advancedDatesLeapYearsAndNoDateUseActualEngineResults() {
        assertEquals("2026-11-30T09:00", six("0 0 9 L * ?").times().getFirst().localDateTime());
        assertEquals("2026-11-30T09:00", six("0 0 9 LW * ?").times().getFirst().localDateTime());
        assertEquals("2026-12-15T09:00", six("0 0 9 15W * ?").times().getFirst().localDateTime());
        assertEquals("2026-12-14T09:00", six("0 0 9 ? * MON#2").times().getFirst().localDateTime());
        assertEquals("2026-11-30T09:00", six("0 0 9 ? * MONL").times().getFirst().localDateTime());
        var leap = six("0 0 0 29 2 ?");
        assertEquals("2028-02-29T00:00", leap.times().getFirst().localDateTime());
        assertEquals(2, leap.returnedCount());
        assertEquals("WINDOW_END", leap.outcome());
        var impossible = six("0 0 0 31 2 ?");
        assertTrue(impossible.times().isEmpty()); assertEquals("NO_FUTURE_TIME", impossible.outcome());
        assertEquals("2027-01-01T00:00", six("0 0 0 1 1 ?").times().getFirst().localDateTime());
    }
    @Test void descriptionsKeepMixedRuleScopes() {
        var result = six("0 0-20/5,30,40-50/2 * * * ?");
        String description = result.fields().get(1).description();
        assertTrue(description.contains("0 至 20，步长 5"));
        assertTrue(description.contains("指定值 30"));
        assertTrue(description.contains("40 至 50，步长 2"));
    }
    @Test void dstGapsRejectStartsAndSkipNonexistentRunsWhileOverlapsRequireOffset() {
        code("CRON_START_TIME_GAP", () -> analyze(SIX, "0 30 2 * * ?", "America/New_York", "2026-03-08T02:30:00", null, 2));
        var spring = analyze(SIX, "0 30 2 * * ?", "America/New_York", "2026-03-08T00:00:00", null, 1);
        assertEquals("2026-03-09T02:30", spring.times().getFirst().localDateTime());
        code("CRON_START_OFFSET_REQUIRED", () -> analyze(SIX, "0 30 1 * * ?", "America/New_York", "2026-11-01T01:00:00", null, 2));
        var fall = analyze(SIX, "0 30 1 * * ?", "America/New_York", "2026-11-01T00:00:00", null, 2);
        assertEquals(fall.times().get(0).localDateTime(), fall.times().get(1).localDateTime());
        assertEquals("-04:00", fall.times().get(0).offset()); assertEquals("-05:00", fall.times().get(1).offset());
        assertNotEquals(fall.times().get(0).instant(), fall.times().get(1).instant());
        var chosen = analyze(SIX, "0 30 1 * * ?", "America/New_York", "2026-11-01T01:00:00", "-05:00", 1);
        assertEquals("-05:00", chosen.times().getFirst().offset());
        code("INVALID_CRON_START", () -> analyze(SIX, "* * * * * ?", "America/New_York", "2026-11-01T01:00:00", "+08:00", 1));
    }
    @Test void countsZonesStartsAndInstantOrderingAreValidated() {
        code("INVALID_CRON_COUNT", () -> analyze(SIX, "* * * * * ?", "UTC", null, null, 101));
        code("INVALID_CRON_COUNT", () -> analyze(SIX, "* * * * * ?", "UTC", null, null, 0));
        code("INVALID_CRON_ZONE", () -> analyze(SIX, "* * * * * ?", "CST", null, null, 1));
        code("INVALID_CRON_START", () -> analyze(SIX, "* * * * * ?", "UTC", "bad", null, 1));
        code("INVALID_CRON_START", () -> analyze(SIX, "* * * * * ?", "UTC", null, "+08:00", 1));
        var result = analyze(SIX, "* * * * * ?", "UTC", "2026-12-31T23:59:59", null, 100);
        assertEquals("2027-01-01T00:00", result.times().getFirst().localDateTime());
        assertEquals("2027-01-01T00:00:00Z", result.times().getFirst().instant());
        assertEquals(100, result.times().size());
    }
    @Test void everyTemplatePassesSameAnalyzerAndGeneratorMatchesManualInput() {
        for (boolean six : List.of(true, false)) for (Template template : CronService.templates(six))
            assertFalse(analyze(six ? SIX : FIVE, template.expression(), "Asia/Shanghai", "2026-11-27T00:00:00", null, 1).times().isEmpty(), template.name());
        Map<String, FieldRule> fields = new LinkedHashMap<>();
        for (String name : NAMES) fields.put(name, new FieldRule("ALL", null, null, null, null));
        fields.put("second", new FieldRule("FIXED", List.of(0), null, null, null));
        fields.put("minute", new FieldRule("STEP", null, 0, null, 5));
        var generated = service.generate(new GenerateRequest(SIX, fields, "UTC", "2026-11-27T00:00:00", null, 10)).join();
        var manual = analyze(SIX, generated.normalizedExpression(), "UTC", "2026-11-27T00:00:00", null, 10);
        assertEquals(manual, generated);
        fields.put("week", new FieldRule("LIST", List.of(1, 3, 5), null, null, null));
        assertTrue(CronDialect.generate(SIX, fields).endsWith("? * MON,WED,FRI"));
        fields.put("day", new FieldRule("FIXED", List.of(1), null, null, null));
        code("CRON_DAY_WEEK_CONFLICT", () -> CronDialect.generate(SIX, fields));
        fields.put("day", new FieldRule("ALL", null, null, null, null));
        fields.put("minute", new FieldRule("LIST", List.of(60), null, null, null));
        code("INVALID_CRON_FIELD_VALUE", () -> CronDialect.generate(SIX, fields));
        fields.remove("second"); fields.put("minute", new FieldRule("FIXED", List.of(0), null, null, null));
        assertEquals("0 * * * 1,3,5", CronDialect.generate(FIVE, fields));
    }
    @Test void closedServiceRejectsWorkAndInvalidLimitsFailFast() {
        service.close();
        code("CRON_BUSY", () -> six("* * * * * ?"));
        assertThrows(IllegalArgumentException.class, () -> new CronProperties(3, 8, 2000));
        assertThrows(IllegalArgumentException.class, () -> new CronProperties(2, 9, 2000));
    }
}
