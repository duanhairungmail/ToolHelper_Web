package com.toolhelper.config;

import com.toolhelper.support.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class LocalRequestFilterTest {
    @TempDir Path root;
    private LocalRequestFilter filter;
    @BeforeEach void setUp() { filter = new LocalRequestFilter(ToolHelperProperties.forTests(root), new JsonMapper()); }
    private MockHttpServletRequest request(String method, String path) {
        return configure(new MockHttpServletRequest(method, path));
    }
    private MockHttpServletRequest configure(MockHttpServletRequest request) {
        request.setRemoteAddr("127.0.0.1"); request.setLocalPort(18081);
        request.addHeader("Host", "127.0.0.1:18081");
        request.addHeader("X-ToolHelper-Session", filter.sessionToken());
        request.setContentType("application/json");
        return request;
    }
    private MockHttpServletResponse reject(MockHttpServletRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (a, b) -> fail("不应放行不合法请求"));
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertNotNull(response.getHeader("X-Request-Id"));
        return response;
    }
    @Test void rejectsMissingOrUntrustedHostAndRemoteRequests() throws Exception {
        var missing = request("GET", "/api/v1/plugins/electerm"); missing.removeHeader("Host"); reject(missing, 403);
        var foreign = request("GET", "/api/v1/plugins/electerm"); foreign.removeHeader("Host"); foreign.addHeader("Host", "evil.test:18081"); reject(foreign, 403);
        var remote = request("GET", "/api/v1/plugins/electerm"); remote.setRemoteAddr("192.168.0.9"); reject(remote, 403);
    }
    @Test void rejectsForeignOriginAndCrossSiteEvenWithValidSession() throws Exception {
        var origin = request("POST", "/api/v1/plugins/electerm/launch"); origin.addHeader("Origin", "https://evil.test"); reject(origin, 403);
        var crossSite = request("GET", "/api/v1/plugins/electerm"); crossSite.addHeader("Sec-Fetch-Site", "cross-site"); reject(crossSite, 403);
        reject(request("OPTIONS", "/api/v1/session"), 403);
    }
    @Test void bootstrapRequiresCustomHeaderAndOtherRoutesRequireToken() throws Exception {
        var bootstrap = request("GET", "/api/v1/session"); reject(bootstrap, 403);
        bootstrap.addHeader("X-ToolHelper-Client", "web");
        AtomicInteger passed = new AtomicInteger();
        filter.doFilter(bootstrap, new MockHttpServletResponse(), (a, b) -> passed.incrementAndGet());
        assertEquals(1, passed.get());
        var noToken = request("GET", "/api/v1/plugins/electerm"); noToken.removeHeader("X-ToolHelper-Session"); reject(noToken, 401);
    }
    @Test void limitsActualChunkedBytesAndKeepsValidBodyReadable() throws Exception {
        var large = configure(new MockHttpServletRequest("POST", "/api/v1/plugins/electerm/connect") {
            @Override public long getContentLengthLong() { return -1; }
        });
        large.setContent(new byte[16 * 1024 + 1]); reject(large, 413);
        var valid = request("POST", "/api/v1/plugins/electerm/launch");
        valid.addHeader("Origin", "http://127.0.0.1:5173");
        valid.setContent("{}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(valid, new MockHttpServletResponse(), (a, b) ->
                assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), ((HttpServletRequest) a).getInputStream().readAllBytes()));
        var wrongType = request("POST", "/api/v1/plugins/electerm/launch"); wrongType.setContentType("text/plain"); reject(wrongType, 415);
    }
    @Test void onlyMacPayloadRoutesReceiveLargerLimitIncludingChunkedRequests() throws Exception {
        for (String path : java.util.List.of("/api/v1/device-mac/ledger/save", "/api/v1/device-mac/queries", "/api/v1/device-mac/export")) {
            var valid = request("POST", path); valid.setContent(new byte[20000]);
            filter.doFilter(valid, new MockHttpServletResponse(), (a, b) -> assertEquals(20000, ((HttpServletRequest) a).getInputStream().readAllBytes().length));
            var large = configure(new MockHttpServletRequest("POST", path) { @Override public long getContentLengthLong() { return -1; } });
            large.setContent(new byte[4 * 1024 * 1024 + 1]); reject(large, 413);
        }
        var unrelated = request("POST", "/api/v1/device-mac/unknown"); unrelated.setContent(new byte[20000]); reject(unrelated, 413);
    }
    @Test void druidScanHasDedicatedLimitButCredentialsAndOtherRoutesDoNot() throws Exception {
        var scan = request("POST", "/api/v1/druid/scans"); scan.setContent(new byte[20000]);
        filter.doFilter(scan, new MockHttpServletResponse(), (a, b) -> assertEquals(20000, ((HttpServletRequest) a).getInputStream().readAllBytes().length));
        var large = configure(new MockHttpServletRequest("POST", "/api/v1/druid/scans") { @Override public long getContentLengthLong() { return -1; } });
        large.setContent(new byte[262145]); reject(large, 413);
        for (String path : java.util.List.of("/api/v1/druid/credential-checks", "/api/v1/druid/export", "/api/v1/druid/unknown")) {
            var body = request("POST", path); body.setContent(new byte[20000]); reject(body, 413);
        }
    }
}
