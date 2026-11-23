package com.toolhelper.config;

import com.toolhelper.plugin.electerm.PluginModels.ErrorInfo;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "toolhelper.requestId";
    private final ToolHelperProperties properties;
    private final JsonMapper json;
    private final String token;

    public LocalRequestFilter(ToolHelperProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String sessionToken() { return token; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, id);
        response.setHeader("X-Request-Id", id);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!isLoopback(request.getRemoteAddr()) || !allowedHost(request)) {
            reject(response, id, 403, "LOCAL_REQUEST_REQUIRED", "只允许通过本机受控地址访问"); return;
        }
        String origin = request.getHeader("Origin");
        String sameOrigin = request.getScheme() + "://" + request.getHeader("Host");
        if ((origin != null && !origin.equals(sameOrigin) && !properties.allowedOrigins().contains(origin))
                || "cross-site".equals(request.getHeader("Sec-Fetch-Site")) || request.getMethod().equals("OPTIONS")) {
            reject(response, id, 403, "ORIGIN_REJECTED", "请求来源不在本机允许范围内"); return;
        }
        boolean bootstrap = request.getMethod().equals("GET") && request.getRequestURI().equals("/api/v1/session");
        if (bootstrap) {
            if (!"web".equals(request.getHeader("X-ToolHelper-Client"))) {
                reject(response, id, 403, "CLIENT_HEADER_REQUIRED", "需要本机客户端请求头"); return;
            }
        } else {
            String provided = request.getHeader("X-ToolHelper-Session");
            if (provided == null || provided.length() > 128 || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
                reject(response, id, 401, "SESSION_REQUIRED", "本机会话无效，请重新获取服务状态"); return;
            }
        }
        if (request.getMethod().equals("POST")) {
            String contentType = request.getContentType();
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                reject(response, id, 415, "JSON_REQUIRED", "操作请求必须使用 application/json"); return;
            }
            if (request.getContentLengthLong() > 16 * 1024) {
                reject(response, id, 413, "REQUEST_TOO_LARGE", "请求体超出限制"); return;
            }
            // 同时限制 chunked/无 Content-Length 请求的实际字节数。
            byte[] body = request.getInputStream().readNBytes(16 * 1024 + 1);
            if (body.length > 16 * 1024) {
                reject(response, id, 413, "REQUEST_TOO_LARGE", "请求体超出限制"); return;
            }
            chain.doFilter(buffered(request, body), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowedHost(HttpServletRequest request) {
        String host = request.getHeader("Host");
        int port = request.getLocalPort();
        return host != null && Set.of("127.0.0.1:" + port, "localhost:" + port, "[::1]:" + port).contains(host);
    }

    private static boolean isLoopback(String address) {
        if (address == null || !address.matches("[0-9a-fA-F:.]+")) return false;
        try { return InetAddress.getByName(address).isLoopbackAddress(); }
        catch (UnknownHostException error) { return false; }
    }

    private void reject(HttpServletResponse response, String id, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), Map.of("requestId", id, "error", new ErrorInfo(status, code, message, false)));
    }

    private static HttpServletRequest buffered(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("仅支持同步 JSON 请求"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        };
    }
}
