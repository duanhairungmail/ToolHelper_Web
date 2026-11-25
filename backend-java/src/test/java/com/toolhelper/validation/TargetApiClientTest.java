package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.validation.ValidationModels.*;
import static com.toolhelper.validation.TargetFixture.*;

class TargetApiClientTest {
    @Test void jsonAndLegacyLoginsUseExactFormatsAndTokenShapes() throws Exception {
        try (var target = new TargetFixture(); var client = target.client()) {
            var bodies = new ArrayList<String>(); var queries = new ArrayList<String>();
            var responses = new ArrayDeque<>(List.of("{\"data\":{\"token\":\"nested\"}}", "{\"code\":200,\"token\":\"root\"}", "{\"data\":\"text\"}"));
            target.handler = ex -> {
                try { bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); queries.add(ex.getRequestURI().getRawQuery()); }
                catch (Exception failure) { throw new AssertionError(failure); }
                reply(ex, 200, responses.removeFirst());
            };
            assertEquals("nested", client.login(target.origin(), target.login()));
            var request = new LoginRequest("127.0.0.1", target.port(), "http", "test-user", "fixture-password", "AES_CBC", "LEGACY_QUERY", true);
            assertEquals("root", client.login(target.origin(), request));
            assertEquals("text", client.login(target.origin(), target.login()));
            assertTrue(bodies.getFirst().contains("\"userName\":\"test-user\""));
            assertNull(queries.getFirst()); assertEquals("", bodies.get(1));
            assertEquals("userName=" + TargetAddressPolicy.encode(TargetApiClient.encrypt("test-user")) + "&password=" + TargetAddressPolicy.encode(TargetApiClient.encrypt("fixture-password")), queries.get(1));
            assertFalse(request.toString().contains("fixture-password"));
        }
    }
    @Test void aesMatchesIndependentNodeKnownVector() {
        // 由 Node crypto 使用同一公开协议参数对无敏感测试文本生成。
        assertEquals("Ehlc5psvVPe9C9DGlQ0VcA==", TargetApiClient.encrypt("phase3-test"));
    }
    @Test void rejectedLoginNeverFallsBackAndUnsafeTokenDoesNotReachHeaders() throws Exception {
        try (var target = new TargetFixture(); var client = target.client()) {
            var count = new AtomicInteger();
            target.handler = ex -> { count.incrementAndGet(); reply(ex, 400, "{\"msg\":\"fixture-password\"}"); };
            ApiException failure = assertThrows(ApiException.class, () -> client.login(target.origin(), target.login()));
            assertEquals("TARGET_LOGIN_REJECTED", failure.code()); assertEquals(1, count.get());
            assertFalse(failure.getMessage().contains("fixture-password"));
            target.handler = ex -> reply(ex, 200, "{\"token\":\"秘密token\"}");
            assertEquals("TARGET_TOKEN_INVALID", assertThrows(ApiException.class, () -> client.login(target.origin(), target.login())).code());
            target.handler = ex -> reply(ex, 200, "{\"success\":true}");
            assertEquals("TARGET_TOKEN_MISSING", assertThrows(ApiException.class, () -> client.login(target.origin(), target.login())).code());
        }
    }
    @Test void authHeadersRedirectAndBusinessClassification() throws Exception {
        try (var target = new TargetFixture(); var client = target.client()) {
            AtomicReference<String> authorization = new AtomicReference<>(), token = new AtomicReference<>();
            target.handler = ex -> { authorization.set(ex.getRequestHeaders().getFirst("Authorization")); token.set(ex.getRequestHeaders().getFirst("token")); ex.getResponseHeaders().set("Location", "/redirected"); reply(ex, 302, "{}"); };
            assertEquals(302, client.call(target.origin(), "fixture-token", "GET", "/probe").status());
            assertEquals("Bearer fixture-token", authorization.get()); assertEquals("fixture-token", token.get());
            var json = new JsonMapper();
            assertFalse(TargetApiClient.authCode(json.readTree("{\"code\":402,\"msg\":\"Payment required\",\"success\":false}")));
            assertTrue(TargetApiClient.authCode(json.readTree("{\"code\":402,\"msg\":\"请登录系统再操作！\",\"success\":false}")));
            for (int status : List.of(401, 403)) {
                target.handler = ex -> reply(ex, status, "{}");
                assertThrows(TargetApiClient.AuthException.class, () -> client.call(target.origin(), "t", "GET", "/probe"));
            }
            assertTrue(TargetApiClient.businessFailure(json.readTree("{\"code\":500}")));
            assertFalse(TargetApiClient.businessFailure(json.readTree("{\"code\":0}")));
            assertFalse(TargetApiClient.businessSuccess(json.readTree("{\"code\":null,\"success\":null}")));
            assertTrue(TargetApiClient.businessSuccess(json.readTree("{\"success\":true}")));
        }
    }
    @Test void limitsAndWholeBodyTimeoutAreEnforced() throws Exception {
        try (var target = new TargetFixture(); var client = new TargetApiClient(new JsonMapper(), 1, 128)) {
            target.handler = ex -> reply(ex, 200, "x".repeat(1024));
            assertEquals("TARGET_RESPONSE_TOO_LARGE", assertThrows(ApiException.class, () -> client.call(target.origin(), "t", "GET", "/probe")).code());
            target.handler = ex -> { try { Thread.sleep(1600); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } reply(ex, 200, "{}"); };
            assertEquals("TARGET_TIMEOUT", assertThrows(ApiException.class, () -> client.call(target.origin(), "t", "GET", "/probe")).code());
        }
    }
    @Test void pagesAcceptSupportedShapesAndRejectConflictingTotals() throws Exception {
        try (var target = new TargetFixture(); var client = target.client()) {
            for (String shape : List.of("[{\"id\":9007199254740993}]", "{\"list\":[{\"id\":9007199254740993}]}", "{\"records\":[{\"id\":9007199254740993}]}", "{\"rows\":[{\"id\":9007199254740993}]}")) {
                target.handler = ex -> reply(ex, 200, "{\"totalCount\":1,\"data\":" + shape + "}");
                var page = client.page(target.origin(), "t", 1, 100);
                assertEquals(1L, page.total()); assertEquals("9007199254740993", TargetApiClient.value(page.rows().getFirst(), "id"));
            }
            for (String data : List.of("{\"totalCount\":1,\"data\":{\"total\":2,\"list\":[]}}", "{\"totalCount\":-1,\"data\":[]}", "{\"total\":\"bad\",\"data\":[]}")) {
                target.handler = ex -> reply(ex, 200, data);
                assertEquals("PAGINATION_INVALID", assertThrows(ApiException.class, () -> client.page(target.origin(), "t", 1, 100)).code());
            }
        }
    }
    @Test void targetAndCronValidationMatchDocumentedSubset() {
        var base = new LoginRequest("2001:db8::1", 8088, "https", "u", "p", "PLAIN", "JSON", false);
        assertEquals("https://[2001:db8::1]:8088", TargetAddressPolicy.origin(base).toString());
        for (String host : List.of("https://example.com", "host/path", "host?x=1", "user@host", "256.1.1.1", "01.1.1.1", "fe80::1%1", "a..b", "")) {
            assertThrows(ApiException.class, () -> TargetAddressPolicy.origin(new LoginRequest(host, 8088, "https", "u", "p", "PLAIN", "JSON", false)), host);
        }
        assertThrows(ApiException.class, () -> TargetAddressPolicy.origin(new LoginRequest("localhost", 80, "http", "u", "p", "PLAIN", "JSON", false)));
        var from = ZonedDateTime.parse("2026-11-24T10:01:02+08:00[Asia/Shanghai]");
        assertEquals(from.withMinute(5).withSecond(0), ValidationCron.parse("*/5 * * * *").next(from));
        assertEquals(from.plusSeconds(1), ValidationCron.parse("* * * * * ?").next(from));
        for (String cron : List.of("0 0 0 * * ? 2027", "61 * * * * ?", "* * *", "0 0 0 30 2 ?", "@daily")) assertThrows(ApiException.class, () -> ValidationCron.parse(cron).next(from));
    }
}
