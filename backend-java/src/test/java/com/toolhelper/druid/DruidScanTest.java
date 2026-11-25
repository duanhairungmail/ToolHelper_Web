package com.toolhelper.druid;

import com.sun.net.httpserver.*;
import com.toolhelper.config.DruidScanProperties;
import com.toolhelper.service.DruidScanService;
import com.toolhelper.support.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.druid.DruidModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DruidScanTest {
    final JsonMapper json = new JsonMapper();
    static DruidScanProperties properties() { return new DruidScanProperties(100, 4, 2, 15, 16, 1048576, 262144); }
    private static final String SECRET = "fixture-private-original";
    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger logins = new AtomicInteger(), inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        volatile boolean auth, generic, huge, slow, limited, wrongSuccess, mixed, businessBlock;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
            server.createContext("/", exchange -> {
                calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                int count = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(count, Math::max);
                try {
                    String path = exchange.getRequestURI().getPath(), name = path.substring(path.lastIndexOf('/') + 1);
                    if (slow) { try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                    if (limited) { reply(exchange, 429, "limited"); return; }
                    if (businessBlock) { reply(exchange, 402, "{\"code\":402,\"success\":false,\"msg\":\"请登录系统再操作！\"}"); return; }
                    if (huge) { reply(exchange, 200, "x".repeat(1048577)); return; }
                    if (name.equals("submitLogin")) {
                        logins.incrementAndGet(); String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (body.equals("loginUsername=fixture-user&loginPassword=fixture-password")) {
                            if (!wrongSuccess) exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=fixture-private-cookie; Path=/app/monitor/; HttpOnly");
                            reply(exchange, 200, "success");
                        } else reply(exchange, 200, "error");
                        return;
                    }
                    if (name.equals("login.html")) { reply(exchange, 200, "<html><title>Druid Stat Login</title><input name=loginUsername><input name=loginPassword></html>"); return; }
                    String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                    if (auth && !(mixed && name.equals("sql.json")) && (cookie == null || !cookie.contains("fixture-private-cookie"))) {
                        exchange.getResponseHeaders().add("Location", "login.html"); reply(exchange, 302, ""); return;
                    }
                    if (generic) { reply(exchange, 200, "{\"ResultCode\":1,\"Content\":{\"Version\":\"x\",\"Drivers\":[],\"ID\":1,\"SQL\":\"" + SECRET + "\",\"ExecuteCount\":2}}"); return; }
                    String value = switch (name) {
                        case "basic.json" -> "{\"Version\":\"1.2.27\",\"Drivers\":[]}";
                        case "datasource.json" -> "[{\"Name\":\"fixture\",\"URL\":\"jdbc:" + SECRET + "\",\"ActiveCount\":1}]";
                        case "sql.json" -> "[{\"ID\":1,\"SQL\":\"select '" + SECRET + "'\",\"ExecuteCount\":2}]";
                        case "websession.json" -> "[{\"SESSIONID\":\"" + SECRET + "\",\"RequestCount\":1}]";
                        case "weburi.json" -> "[{\"URI\":\"/" + SECRET + "\",\"RequestCount\":1}]";
                        case "webapp.json" -> "[{\"ContextPath\":\"/app\",\"RequestCount\":1}]";
                        case "spring.json" -> "[{\"Class\":\"demo\",\"Method\":\"x\",\"ExecuteCount\":1}]";
                        default -> null;
                    };
                    if (value == null) reply(exchange, 200, "<html><title>Druid Stat Index</title></html>");
                    else reply(exchange, 200, "{\"ResultCode\":1,\"Content\":" + value + "}");
                } finally { inFlight.decrementAndGet(); exchange.close(); }
            }); server.start();
        }
        URI root() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/app/monitor/"); }
        static void reply(HttpExchange e, int status, String value) throws java.io.IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", value.startsWith("{") ? "application/json" : "text/html;charset=UTF-8");
            e.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) e.getResponseBody().write(bytes);
        }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
    @Test void preservesContextAndRejectsAmbiguousOrUnsafeInput() throws Exception {
        assertEquals("http://example.test/app/monitor/", DruidTargetPolicy.normalize("http://EXAMPLE.test:80/app/monitor/index.html").toString());
        assertEquals("https://example.test/druid/", DruidTargetPolicy.normalize("https://example.test").toString());
        for (String bad : List.of("ftp://x/", "http://u:p@x/druid/", "http://x:0/", "http://127.1/", "http://01.2.3.4/", "http://x/a/../b/", "http://x/a%2fb/", "http://x/?x=1", "http://[::1]/", "http://x/a;z/"))
            assertThrows(ApiException.class, () -> DruidTargetPolicy.normalize(bad), bad);
        assertThrows(ApiException.class, () -> DruidTargetPolicy.checkAddresses(URI.create("http://169.254.169.254/druid/")));
        assertThrows(ApiException.class, () -> DruidTargetPolicy.checkAddresses(URI.create("http://100.100.100.200/druid/")));
        URI root = URI.create("https://example.test/app/monitor/");
        assertNull(DruidTargetPolicy.redirect(root, root, "https://other.test/app/monitor/login.html"));
        assertNull(DruidTargetPolicy.redirect(root, root, "http://example.test/app/monitor/login.html"));
        assertNull(DruidTargetPolicy.redirect(root, root, "../login.html"));
        assertEquals(root.resolve("login.html"), DruidTargetPolicy.redirect(root, root, "login.html"));
    }
    private Evidence analyze(String ruleId, int status, String body, boolean identified) {
        var rule = DruidRuleCatalog.ALL.stream().filter(r -> r.id().equals(ruleId)).findFirst().orElseThrow();
        return new DruidResponseAnalyzer(json).analyze(rule, new DruidHttpClient.Reply(status, body.getBytes(StandardCharsets.UTF_8), "application/json", null, 1), identified);
    }
    @Test void distinguishesHtmlAuthUnknownAndEmptyJsonWithoutLeakingValues() {
        assertEquals("PAGE_IDENTIFIED", analyze("SQL_PAGE", 200, "<html><title>Druid Stat SQL</title></html>", false).status());
        assertEquals("AUTH_REQUIRED", analyze("SQL", 200, "<html>Druid Stat Login</html>", false).status());
        assertFalse(analyze("SQL", 200, "{\"code\":200,\"data\":[]}", true).jsonAccessible());
        assertFalse(analyze("SQL", 200, "{\"ResultCode\":0,\"Content\":[]}", true).jsonAccessible());
        assertFalse(analyze("SQL", 200, "{\"ResultCode\":1,\"Content\":[]}", false).jsonAccessible());
        assertTrue(analyze("SQL", 200, "{\"ResultCode\":1,\"Content\":[]}", true).empty());
        assertEquals("PATH_NOT_FOUND", analyze("BASIC", 404, "Not Found", false).status());
        assertEquals("UNRATED", analyze("BASIC", 404, "Not Found", false).severity());
        var e = analyze("SQL", 200, "{\"ResultCode\":1,\"Content\":[{\"SQL\":\"" + SECRET + "\",\"ExecuteCount\":1}]}", false);
        assertEquals("HIGH", e.severity()); assertFalse(json.writeValueAsString(e).contains(SECRET));
        assertEquals("HIGH", analyze("SQL", 200, "{\"ResultCode\":1,\"Content\":[{\"SQL\":\"select '<html>Druid Stat Login'\",\"ExecuteCount\":1}]}", false).severity());
        assertEquals("UNKNOWN_RESPONSE", analyze("BASIC", 200, "[".repeat(65) + "0" + "]".repeat(65), false).status());
    }
    @Test void anonymousScanChecksEveryRequestedPageAndNeverSendsLoginOrReset() throws Exception {
        try (var fixture = new Fixture()) {
            var result = new DruidScanner(properties(), json).scan("target", fixture.root(), () -> false, null);
            assertEquals("COMPLETED", result.executionStatus()); assertEquals(13, result.completedRules()); assertEquals(13, result.requests());
            assertEquals("ANONYMOUS_ACCESS", result.accessStatus()); assertEquals("HIGH", result.severity());
            assertEquals("DATA_EXPOSED", result.retest().status());
            assertEquals(result.retest(), Summary.of(result).retest());
            assertEquals(13, fixture.calls.size()); assertEquals(0, fixture.logins.get());
            assertTrue(fixture.calls.contains("GET /app/monitor/spring.html"));
            assertTrue(fixture.calls.stream().noneMatch(call -> call.contains("reset") || call.contains("submitLogin")));
            String stored = json.writeValueAsString(result); assertFalse(stored.contains(SECRET)); assertFalse(stored.contains("jdbc:"));
        }
    }
    @Test void historicalRetestSeparatesPagesDataBlockingAndIncompleteChecks() {
        var analyzer = new DruidResponseAnalyzer(json);
        for (int status : List.of(401, 403, 404, 402)) {
            var evidence = new ArrayList<>(DruidRuleCatalog.ALL.stream().map(rule -> analyzer.analyze(rule,
                    new DruidHttpClient.Reply(status, "response".getBytes(StandardCharsets.UTF_8), "text/plain", null, 1), false)).toList());
            String expected = status == 401 || status == 403 ? "BLOCKED" : status == 404 ? "PATH_NOT_FOUND" : "INCONCLUSIVE";
            assertEquals(expected, DruidRetest.summarize(evidence).status());
            evidence.set(1, DruidResponseAnalyzer.failure(DruidRuleCatalog.ALL.get(1), "TIMEOUT"));
            assertEquals("INCONCLUSIVE", DruidRetest.summarize(evidence).status());
            evidence.set(0, analyze("INDEX", 200, "<html>Druid Stat Index</html>", false));
            assertEquals("PAGE_EXPOSED", DruidRetest.summarize(evidence).status());
            evidence.set(3, analyze("SQL", 200, "{\"ResultCode\":1,\"Content\":[{\"SQL\":\"fixture\",\"ExecuteCount\":1}]}", false));
            assertEquals("DATA_EXPOSED", DruidRetest.summarize(evidence).status());
        }
        assertEquals("INCONCLUSIVE", DruidRetest.summarize(List.of()).status());
        assertEquals("INCONCLUSIVE", DruidRetest.summarize(List.of(analyze("INDEX", 401, "", false))).status());
        assertEquals("BLOCKED", analyze("INDEX", 200, "<html>Druid Stat Login</html>", false).retest().status());
        assertEquals("INCONCLUSIVE", analyze("SQL", 200, "<html>Druid Stat Index</html>", false).retest().status());
        assertEquals("DATA_EXPOSED", analyze("SQL", 200, "{\"ResultCode\":1,\"Content\":[]}", true).retest().status());
    }
    @Test void businessLoginBlockDoesNotProveDruidAuthenticationOrTriggerCredentialSubmission() throws Exception {
        String response = "{\"code\":402,\"success\":false,\"msg\":\"请登录系统再操作！\"}";
        assertEquals("BUSINESS_AUTH_REQUIRED", analyze("SQL", 402, response, false).status());
        assertEquals("HTTP_ERROR", analyze("SQL", 402, "payment required", false).status());
        assertEquals("HTTP_ERROR", analyze("SQL", 402, response.replace("false", "true"), false).status());
        assertEquals("HTTP_ERROR", analyze("SQL", 402, response.replace("请登录系统再操作！", "other"), false).status());
        assertEquals("UNKNOWN_RESPONSE", analyze("SQL", 200, response, false).status());
        try (var fixture = new Fixture()) {
            fixture.businessBlock = true;
            var result = new DruidScanner(properties(), json).scan("id", fixture.root(), () -> false,
                    new DruidScanner.Secret("fixture-user", "fixture-password"));
            assertEquals(13, result.completedRules()); assertEquals(13, result.requests());
            assertEquals("BUSINESS_AUTH_REQUIRED", result.accessStatus()); assertEquals("UNIDENTIFIED", result.identification());
            assertEquals("UNRATED", result.severity()); assertEquals("SKIPPED_NO_SUPPORTED_LOGIN", result.credentialStatus());
            assertEquals("BLOCKED", result.retest().status());
            assertTrue(result.evidence().stream().allMatch(e -> e.retest().status().equals("BLOCKED")));
            assertEquals(0, fixture.logins.get());
        }
    }
    @Test void genericRepeatedResponseIsNotReportedAsExposure() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.generic = true;
            var result = new DruidScanner(properties(), json).scan("id", fixture.root(), () -> false, null);
            assertNotEquals("ANONYMOUS_ACCESS", result.accessStatus());
            assertTrue(result.evidence().stream().anyMatch(e -> e.status().equals("AMBIGUOUS_RESPONSE")));
        }
    }
    @Test void credentialsUseFreshCookieContextAndRequireSuccessCookieAndProtectedEndpoint() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.auth = true; var scanner = new DruidScanner(properties(), json);
            var result = scanner.scan("id", fixture.root(), () -> false, new DruidScanner.Secret("fixture-user", "fixture-password"));
            assertEquals("COMPLETED", result.executionStatus()); assertEquals("VERIFIED", result.credentialStatus());
            assertEquals("AUTH_REQUIRED", result.accessStatus()); assertTrue(result.requests() <= 16);
            assertEquals("BLOCKED", result.retest().status());
            assertTrue(result.evidence().stream().noneMatch(Evidence::jsonAccessible));
            assertEquals(1, fixture.logins.get());
            for (String secret : List.of("fixture-password", "fixture-private-cookie", SECRET)) assertFalse(json.writeValueAsString(result).contains(secret));
            var next = scanner.scan("id2", fixture.root(), () -> false, null);
            assertEquals("AUTH_REQUIRED", next.accessStatus()); assertEquals(1, fixture.logins.get());
            var rejected = scanner.scan("id3", fixture.root(), () -> false, new DruidScanner.Secret("fixture-user", "wrong"));
            assertEquals("REJECTED", rejected.credentialStatus()); assertEquals(2, fixture.logins.get());
            fixture.wrongSuccess = true;
            var unconfirmed = scanner.scan("id4", fixture.root(), () -> false, new DruidScanner.Secret("fixture-user", "fixture-password"));
            assertNotEquals("VERIFIED", unconfirmed.credentialStatus());
        }
    }
    @Test void protectedHomeDoesNotHidePublicJson() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.auth = true; fixture.mixed = true;
            var result = new DruidScanner(properties(), json).scan("id", fixture.root(), () -> false, null);
            assertEquals("ANONYMOUS_ACCESS", result.accessStatus()); assertEquals("HIGH", result.severity());
        }
    }
    @Test void rateLimitAndBodyLimitStopRemainingRequestsAndNeverClaimSafe() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.huge = true;
            var result = new DruidScanner(properties(), json).scan("id", fixture.root(), () -> false, null);
            assertEquals("PARTIAL", result.executionStatus()); assertEquals("RESPONSE_TOO_LARGE", result.evidence().getFirst().status());
            assertEquals(1, result.requests()); assertEquals("UNRATED", result.severity());
            fixture.huge = false; fixture.limited = true;
            var limited = new DruidScanner(properties(), json).scan("id2", fixture.root(), () -> false, null);
            assertEquals(1, limited.requests()); assertEquals("RATE_LIMITED", limited.evidence().getFirst().status());
        }
    }
    private Task waitFor(DruidScanService service, String id) throws Exception {
        for (int i = 0; i < 400; i++) { Task task = service.task(id); if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(task.status())) return task; Thread.sleep(20); }
        fail("检测任务没有按时结束"); return null;
    }
    @Test void serviceDeduplicatesRequestsAndTargetsSerializesOriginAndExportsSanitizedReports() throws Exception {
        try (var fixture = new Fixture(); var ignored = new ServiceScope(properties(), json)) {
            var service = ignored.service; String key = UUID.randomUUID().toString(); String url = fixture.root().toString();
            var task = service.submit(new ScanRequest(key, List.of(url, url, url.replace("/monitor/", "/another/"))));
            assertEquals(2, task.total()); assertEquals(task.taskId(), service.submit(new ScanRequest(key, List.of(url, url.replace("/monitor/", "/another/")))).taskId());
            assertThrows(ApiException.class, () -> service.submit(new ScanRequest(key, List.of(url))));
            assertThrows(ApiException.class, service::clear);
            assertEquals("COMPLETED", waitFor(service, task.taskId()).status()); assertEquals(1, fixture.maxInFlight.get());
            var page = service.results(task.taskId(), 1, 50); assertEquals(2, page.total());
            var ids = page.items().stream().map(Summary::targetId).toList();
            for (String format : List.of("xlsx", "docx")) {
                byte[] bytes = service.export(new ExportRequest(task.taskId(), ids, DruidExport.KEYS, format, true));
                Map<String, String> entries = new HashMap<>();
                try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                    ZipEntry entry; while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
                assertTrue(entries.values().stream().noneMatch(value -> value.contains(SECRET) || value.contains("fixture-password")));
                if (format.equals("xlsx")) {
                    assertTrue(entries.containsKey("xl/worksheets/sheet3.xml")); assertTrue(entries.get("xl/workbook.xml").contains("端点详情"));
                    assertTrue(entries.get("xl/worksheets/sheet1.xml").contains("历史漏洞复测结论"));
                    assertTrue(entries.get("xl/worksheets/sheet1.xml").contains("仍存在未授权暴露"));
                    assertTrue(entries.get("xl/worksheets/sheet2.xml").contains("仍存在匿名监控接口暴露"));
                } else {
                    assertTrue(entries.get("word/document.xml").contains("整改建议与检测限制"));
                    assertTrue(entries.get("word/document.xml").contains("历史漏洞复测结论"));
                    assertTrue(entries.get("word/document.xml").contains("仍存在未授权暴露"));
                    assertTrue(entries.get("word/document.xml").contains("仍存在匿名监控接口暴露"));
                }
            }
            assertEquals(1, service.history().size()); service.clear(); assertTrue(service.history().isEmpty());
            assertThrows(ApiException.class, () -> service.task(task.taskId()));
        }
    }
    @Test void cancelStopsRequestsAndReleasesOnlyAfterCleanupWithoutAutomaticReplay() throws Exception {
        try (var fixture = new Fixture(); var scope = new ServiceScope(properties(), json)) {
            fixture.slow = true; var service = scope.service;
            var task = service.submit(new ScanRequest(UUID.randomUUID().toString(), List.of(fixture.root().toString())));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fixture.calls.isEmpty() && System.nanoTime() < limit) Thread.sleep(10);
            assertEquals("CANCELLING", service.cancel(task.taskId()).status());
            assertEquals("CANCELLED", waitFor(service, task.taskId()).status());
            assertNull(service.active()); assertEquals(1, fixture.calls.size());
            assertEquals("CANCELLED", service.result(task.taskId(), service.results(task.taskId(), 1, 50).items().getFirst().targetId()).executionStatus());
        }
    }
    @Test void httpCredentialsRequireExplicitConfirmationAndNeverWritePasswordToJob() throws Exception {
        try (var scope = new ServiceScope(properties(), json)) {
            var failure = assertThrows(ApiException.class, () -> scope.service.credentials(new CredentialRequest(UUID.randomUUID().toString(), "http://127.0.0.1:1/druid/", "u", "p", false)));
            assertEquals("INSECURE_HTTP_CONFIRMATION_REQUIRED", failure.code());
            assertEquals("CredentialRequest[redacted]", new CredentialRequest("key", "url", "secret-user", "secret-password", true).toString());
            assertTrue(scope.service.history().isEmpty());
        }
    }
    @Test void concurrencyIsBoundedAndQueuedTargetsDoNotRunAfterCancellation() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        try (var scope = new ServiceScope(properties(), json)) {
            for (int i = 0; i < 5; i++) { var fixture = new Fixture(); fixture.slow = true; fixtures.add(fixture); }
            var task = scope.service.submit(new ScanRequest(UUID.randomUUID().toString(), fixtures.stream().map(f -> f.root().toString()).toList()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (fixtures.stream().mapToInt(f -> f.calls.size()).sum() < 4 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, fixtures.get(4).calls.size());
            assertTrue(fixtures.stream().mapToInt(f -> f.inFlight.get()).sum() <= 4);
            scope.service.cancel(task.taskId());
            assertEquals("CANCELLED", waitFor(scope.service, task.taskId()).status());
            assertEquals(5, scope.service.results(task.taskId(), 1, 50).total());
            assertEquals(0, fixtures.get(4).calls.size());
        } finally { fixtures.forEach(Fixture::close); }
    }
    @Test void requestTimeoutAndInvalidTlsAreReportedWithoutBypass() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.slow = true;
            var shortBudget = new DruidScanProperties(100, 4, 1, 1, 16, 1048576, 262144);
            var result = new DruidScanner(shortBudget, json).scan("id", fixture.root(), () -> false, null);
            assertEquals("PARTIAL", result.executionStatus()); assertTrue(result.completedRules() < 13);
            assertNotEquals("SAFE", result.severity());
        }
        var tls = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tls.setHttpsConfigurator(new HttpsConfigurator(javax.net.ssl.SSLContext.getDefault()));
        tls.createContext("/", exchange -> Fixture.reply(exchange, 200, "<html>Druid Stat Index</html>")); tls.start();
        try {
            URI root = URI.create("https://127.0.0.1:" + tls.getAddress().getPort() + "/druid/");
            var result = new DruidScanner(properties(), json).scan("tls", root, () -> false, null);
            assertEquals("PARTIAL", result.executionStatus()); assertEquals("TLS_ERROR", result.evidence().getFirst().status());
        } finally { tls.stop(0); }
    }
    private static class ServiceScope implements AutoCloseable {
        final DruidScanService service;
        ServiceScope(DruidScanProperties properties, JsonMapper json) { service = new DruidScanService(properties, json); }
        public void close() { service.close(); }
    }
}
