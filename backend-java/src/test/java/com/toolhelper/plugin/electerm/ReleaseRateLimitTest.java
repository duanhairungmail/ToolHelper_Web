package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class ReleaseRateLimitTest {
    @TempDir Path root;
    private final HttpClient http = mock(HttpClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
    private final MutableClock clock = new MutableClock();
    private final ReleaseClient client = new ReleaseClient(ToolHelperProperties.forTests(Path.of("target")), new JsonMapper(), http, clock);

    @Test void primaryLimitStopsRequestsUntilResetThenAllowsRetry() throws Exception {
        Instant reset = clock.instant().plusSeconds(1200);
        respond(response(403, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "" + reset.getEpochSecond()), ""), success());
        try {
            ApiException first = assertThrows(ApiException.class, client::latest);
            assertEquals("GITHUB_RATE_LIMITED", first.code());
            assertTrue(first.retryable());
            assertTrue(first.getMessage().contains(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault()).format(reset)));
            for (int i = 0; i < 3; i++) assertEquals(first.getMessage(), assertThrows(ApiException.class, client::latest).getMessage());
            verifyRequests(1);
            clock.now = reset.minusSeconds(1);
            assertThrows(ApiException.class, client::latest);
            verifyRequests(1);
            clock.now = reset;
            assertEquals("v5.12.6", client.latest().asset().tag());
            client.latest();
            verifyRequests(2); // 恢复后仍使用原有一分钟元数据缓存。
        } finally { client.close(); }
    }

    @Test void honorsRetryAfterSecondsAndHttpDate() throws Exception {
        Instant date = clock.instant().plusSeconds(90);
        respond(response(429, Map.of("Retry-After", "10"), ""),
                response(403, Map.of("Retry-After", DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(date)), ""), success());
        try {
            assertThrows(ApiException.class, client::latest);
            clock.now = clock.now.plusSeconds(9);
            assertThrows(ApiException.class, client::latest);
            verifyRequests(1);
            clock.now = clock.now.plusSeconds(1);
            assertThrows(ApiException.class, client::latest);
            clock.now = date.minusSeconds(1);
            assertThrows(ApiException.class, client::latest);
            verifyRequests(2);
            clock.now = date;
            assertEquals("v5.12.6", client.latest().asset().tag());
            verifyRequests(3);
        } finally { client.close(); }
    }

    @Test void waitsForBothRetryAfterAndExhaustedQuotaReset() throws Exception {
        respond(response(429, Map.of("Retry-After", "120", "X-RateLimit-Remaining", "0",
                "X-RateLimit-Reset", "" + clock.instant().plusSeconds(30).getEpochSecond()), ""), success());
        try {
            assertThrows(ApiException.class, client::latest);
            clock.now = clock.now.plusSeconds(30);
            assertThrows(ApiException.class, client::latest);
            verifyRequests(1);
            clock.now = clock.now.plusSeconds(90);
            assertEquals("v5.12.6", client.latest().asset().tag());
            verifyRequests(2);
        } finally { client.close(); }
    }

    @Test void invalidOrPastHeadersUseOneMinuteCooldown() throws Exception {
        respond(response(429, Map.of("Retry-After", "9999999999999999999999999"), ""),
                response(403, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "-1"), ""), success());
        try {
            assertThrows(ApiException.class, client::latest);
            clock.now = clock.now.plusSeconds(59);
            assertThrows(ApiException.class, client::latest);
            verifyRequests(1);
            clock.now = clock.now.plusSeconds(1);
            assertThrows(ApiException.class, client::latest);
            clock.now = clock.now.plusSeconds(59);
            assertThrows(ApiException.class, client::latest);
            verifyRequests(2);
            clock.now = clock.now.plusSeconds(1);
            assertEquals("v5.12.6", client.latest().asset().tag());
        } finally { client.close(); }
    }

    @Test void ordinaryForbiddenResponseIsNotMisreportedAsRateLimit() throws Exception {
        respond(response(403, Map.of("X-RateLimit-Remaining", "10"), ""), success());
        try {
            assertEquals("UPSTREAM_FAILED", assertThrows(ApiException.class, client::latest).code());
            assertEquals("v5.12.6", client.latest().asset().tag());
            verifyRequests(2);
        } finally { client.close(); }
    }

    @Test void metadataCooldownDoesNotBlockAlreadySelectedAssetDownload() throws Exception {
        respond(response(403, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "" + clock.instant().plusSeconds(600).getEpochSecond()), ""),
                response(200, Map.of(), "hello"));
        try {
            assertThrows(ApiException.class, client::latest);
            Path archive = root.resolve("package.part");
            client.download(new Asset(1, "v5.12.6", 2, "package.zip", 5, SecurePaths.hash("hello"),
                    "https://github.com/electerm/electerm/releases/download/v5.12.6/package.zip"), archive, (done, total) -> {});
            assertEquals("hello", Files.readString(archive));
            verifyRequests(2);
        } finally { client.close(); }
    }

    @SafeVarargs
    private void respond(HttpResponse<InputStream> first, HttpResponse<InputStream>... following) throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(first, following);
    }

    private void verifyRequests(int count) throws Exception {
        verify(http, times(count)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, Map<String, String> headers, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        when(response.statusCode()).thenReturn(status);
        Map<String, List<String>> values = new HashMap<>();
        headers.forEach((name, value) -> values.put(name, List.of(value)));
        when(response.headers()).thenReturn(HttpHeaders.of(values, (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static HttpResponse<InputStream> success() {
        return response(200, Map.of(), """
                {"id":1,"tag_name":"v5.12.6","draft":false,"prerelease":false,"assets":[{
                "id":2,"name":"electerm-5.12.6-win-x64-portable.tar.gz","size":10,
                "digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "browser_download_url":"https://github.com/electerm/electerm/releases/download/v5.12.6/package.tar.gz"}]}
                """);
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-23T12:20:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
