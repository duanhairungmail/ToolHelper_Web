package com.toolhelper.service;

import com.toolhelper.support.ApiException;
import com.toolhelper.validation.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.validation.TargetFixture.*;
import static com.toolhelper.validation.ValidationModels.*;

class ApiValidationServiceTest {
    TargetFixture target;
    TargetApiClient client;
    ApiValidationService service;
    String connection;
    @BeforeEach void setup() throws Exception {
        target = new TargetFixture(); client = target.client();
        service = new ApiValidationService(client, 5, 500, Clock.systemDefaultZone());
        target.handler = ex -> { if (!loginReply(ex)) reply(ex, 200, "{\"code\":0,\"totalCount\":0,\"data\":[]}"); };
        connection = (String) service.login(target.login()).get("connectionId");
    }
    @AfterEach void cleanup() { service.close(); client.close(); target.close(); }
    static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "等待状态超时");
    }
    Map<String, Object> finish(Map<String, Object> task) throws InterruptedException {
        String id = (String) task.get("taskId");
        await(() -> !Boolean.TRUE.equals(service.state().get("busy")));
        return service.task(id);
    }
    @SuppressWarnings("unchecked") List<Result> rows() { return (List<Result>) service.state().get("results"); }

    @Test void allPagesAndWhitelistedTopicAliasesArePublishedOnlyWhenComplete() throws Exception {
        var pages = new ArrayList<String>();
        target.handler = ex -> {
            String query = ex.getRequestURI().getRawQuery(); pages.add(query);
            reply(ex, 200, "{\"code\":0,\"totalCount\":2,\"data\":[{\"id\":\"" + (query.endsWith("page=1") ? "9007199254740993" : "2") + "\",\"deviceName\":\"测试设备\",\"comAddress\":1,\"gateName\":\"网关\",\"mqttDown\":\"=literal\",\"upTopic\":\"up/1\",\"password\":\"never-publish\"}]}");
        };
        assertEquals("SUCCEEDED", finish(service.query(connection, false)).get("status"));
        assertEquals(2, ((List<?>) service.state().get("devices")).size()); assertEquals(2, pages.size());
        assertEquals("SUCCEEDED", finish(service.query(connection, true)).get("status"));
        var topics = (Topic) ((List<?>) service.state().get("topics")).getFirst();
        assertEquals("=literal", topics.downTopic()); assertEquals("up/1", topics.upTopic());
        assertFalse(service.state().toString().contains("never-publish"));
        assertFalse(service.state().toString().contains("fixture-token"));
    }
    @Test void unknownTotalContinuesToEmptyPageAndEmptyDeviceIdIsNotInvented() throws Exception {
        var count = new AtomicInteger();
        target.handler = ex -> reply(ex, 200, count.incrementAndGet() <= 2 ? "{\"data\":[{\"deviceName\":\"row" + count.get() + "\"}]}" : "{\"data\":[]}");
        assertEquals("SUCCEEDED", finish(service.query(connection, false)).get("status"));
        assertEquals(3, count.get());
        assertEquals("", ((Device) ((List<?>) service.state().get("devices")).getFirst()).id());
    }
    @Test void repeatedChangingPrematureAndOversizedPagesFailWithoutPublishingPartialData() throws Exception {
        for (String mode : List.of("repeat", "changing", "premature", "limit", "field", "malformed")) {
            var count = new AtomicInteger();
            target.handler = ex -> {
                int page = count.incrementAndGet();
                String body = switch (mode) {
                    case "repeat" -> "{\"data\":[{\"id\":1}]}";
                    case "changing" -> "{\"totalCount\":" + (page == 1 ? 3 : 4) + ",\"data\":[{\"id\":" + page + "}]}";
                    case "premature" -> "{\"totalCount\":3,\"data\":" + (page == 1 ? "[{\"id\":1}]" : "[]") + "}";
                    case "limit" -> "{\"totalCount\":501,\"data\":[{\"id\":1}]}";
                    case "field" -> "{\"totalCount\":1,\"data\":[{\"deviceName\":\"" + "x".repeat(4097) + "\"}]}";
                    default -> "{\"data\":[false]}";
                };
                reply(ex, 200, body);
            };
            assertEquals("FAILED", finish(service.query(connection, false)).get("status"), mode);
            assertTrue(((List<?>) service.state().get("devices")).isEmpty(), mode);
        }
    }
    @Test void defaultCatalogRejectsPostAndUnknownIdsAndSkipsMissingDevicePrerequisite() throws Exception {
        assertEquals(16, LegacyApiCatalog.ALL.size());
        assertEquals(13, LegacyApiCatalog.ALL.stream().filter(e -> !e.sideEffect()).count());
        assertThrows(ApiException.class, () -> service.verify(new RunRequest(connection, List.of("reset-alarm"), false)));
        assertThrows(ApiException.class, () -> service.startSchedule(new ScheduleRequest(connection, List.of("reset-alarm"), "* * * * * ?")));
        for (List<String> selection : List.of(List.of("http://bad"), List.of("summary", "summary"))) assertThrows(ApiException.class, () -> service.verify(new RunRequest(connection, selection, true)));
        var requests = new AtomicInteger();
        target.handler = ex -> { requests.incrementAndGet(); reply(ex, 200, "{\"data\":[]}"); };
        assertEquals("SUCCEEDED", finish(service.verify(new RunRequest(connection, List.of("last-data", "history", "cable", "device-alarms"), false))).get("status"));
        assertEquals(1, requests.get()); assertEquals(4, rows().size());
        assertTrue(rows().stream().allMatch(row -> row.outcome().equals("SKIPPED") && !row.reachable()));
    }
    @Test void manualPostRequiresConfirmationAndUsesEmptyJsonOnce() throws Exception {
        var count = new AtomicInteger(); var method = new AtomicReference<String>(); var body = new AtomicReference<String>();
        target.handler = ex -> {
            count.incrementAndGet(); method.set(ex.getRequestMethod());
            try { body.set(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception failure) { throw new AssertionError(failure); }
            reply(ex, 200, "{\"code\":0}");
        };
        assertEquals("SUCCEEDED", finish(service.verify(new RunRequest(connection, List.of("reset-alarm"), true))).get("status"));
        assertEquals(1, count.get()); assertEquals("POST", method.get()); assertEquals("{}", body.get());
    }
    @Test void resultClassificationPreservesHttpReachabilityAndBusinessMeaning() throws Exception {
        var replies = new ArrayDeque<>(List.of("{\"code\":0}", "{\"code\":500}", "{}", "not-json", "{}", "{}"));
        var statuses = new ArrayDeque<>(List.of(200, 200, 200, 200, 404, 500));
        target.handler = ex -> reply(ex, statuses.removeFirst(), replies.removeFirst());
        var ids = List.of("summary", "io-status", "meter-last", "meter-history-ac", "meter-history-dc", "alarms");
        assertEquals("SUCCEEDED", finish(service.verify(new RunRequest(connection, ids, false))).get("status"));
        assertEquals(List.of("BUSINESS_OK", "BUSINESS_ERROR", "HTTP_OK", "INVALID_RESPONSE", "HTTP_ERROR", "HTTP_ERROR"), rows().stream().map(Result::outcome).toList());
        assertTrue(rows().stream().allMatch(Result::reachable));
    }
    @Test void cancelInterruptsWaitAndPreventsLaterRequestsAndMutations() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger();
        target.handler = ex -> { count.incrementAndGet(); entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } reply(ex, 200, "{\"code\":0}"); };
        var task = service.verify(new RunRequest(connection, List.of("summary", "io-status"), false));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(ApiException.class, () -> service.query(connection, false));
            assertThrows(ApiException.class, () -> service.logout(connection));
            service.cancel((String) task.get("taskId"), connection);
            assertEquals("CANCELLED", finish(task).get("status")); assertEquals(1, count.get()); assertTrue(rows().isEmpty());
        } finally { release.countDown(); }
    }
    @Test void expiredLegacyTokenStopsAutomaticDetectionAndRequiresManualLogin() throws Exception {
        var count = new AtomicInteger();
        target.handler = ex -> { count.incrementAndGet(); reply(ex, 402, "{\"code\":402,\"success\":false,\"msg\":\"请登录系统再操作！\"}"); };
        service.startSchedule(new ScheduleRequest(connection, List.of("summary", "io-status"), "* * * * * ?"));
        await(() -> !Boolean.TRUE.equals(service.autoState().get("running")) && !Boolean.TRUE.equals(service.state().get("busy")));
        assertEquals(false, service.state().get("connected")); assertEquals(1, count.get());
        assertEquals("AUTH_REQUIRED", rows().getFirst().outcome()); assertEquals(402, rows().getFirst().httpStatus());
        assertThrows(ApiException.class, () -> service.query(connection, false));
        assertTrue(service.export(connection, "results", "xlsx").length > 0);
        target.handler = TargetFixture::loginReply;
        String newId = (String) service.login(target.login()).get("connectionId");
        assertNotEquals(connection, newId); assertEquals("", service.autoState().get("stopReason")); assertEquals(0, service.autoState().get("cycles"));
        assertTrue(rows().isEmpty()); assertThrows(ApiException.class, () -> service.query(connection, false));
    }
    @Test void schedulerWaitsUntilNextTriggerAndKeepsLastTenRounds() throws Exception {
        target.handler = ex -> reply(ex, 200, "{\"code\":0}");
        service.startSchedule(new ScheduleRequest(connection, List.of("summary"), "* * * * * ?"));
        assertNotNull(service.autoState().get("nextRun"));
        await(() -> ((List<?>) service.autoState().get("history")).size() == 10 && ((Number) service.autoState().get("cycles")).intValue() >= 11 && !Boolean.TRUE.equals(service.state().get("busy")));
        service.stopAuto(connection);
        assertEquals(10, ((List<?>) service.autoState().get("history")).size());
        assertEquals(100.0, service.autoState().get("successRate")); assertEquals(false, service.autoState().get("running"));
    }
    @Test void slowAutomaticRoundNeverOverlapsAndStopCancelsIt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var count = new AtomicInteger();
        target.handler = ex -> { count.incrementAndGet(); entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } reply(ex, 200, "{}"); };
        service.startSchedule(new ScheduleRequest(connection, List.of("summary"), "* * * * * ?"));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); Thread.sleep(1100);
            assertEquals(1, count.get()); assertEquals(1, service.autoState().get("cycles"));
            service.stopAuto(connection); await(() -> !Boolean.TRUE.equals(service.state().get("busy")));
            assertEquals("CANCELLED", ((Map<?, ?>) service.state().get("currentTask")).get("status"));
        } finally { release.countDown(); }
    }
    @Test void failedReloginClearsPreviousSessionAndRetainedResults() throws Exception {
        target.handler = ex -> reply(ex, 200, "{\"code\":0}");
        finish(service.verify(new RunRequest(connection, List.of("summary"), false)));
        target.handler = ex -> reply(ex, 401, "{\"msg\":\"fixture-password\"}");
        assertThrows(ApiException.class, () -> service.login(target.login()));
        assertEquals(false, service.state().get("connected")); assertNull(service.state().get("connectionId")); assertTrue(rows().isEmpty());
    }
}
