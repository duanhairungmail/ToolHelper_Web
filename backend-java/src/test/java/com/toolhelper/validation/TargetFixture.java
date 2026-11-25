package com.toolhelper.validation;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.validation.ValidationModels.*;

/** 使用真实回环 HTTP 服务验证传输和分页，不连接用户设备。 */
public final class TargetFixture implements AutoCloseable {
    public final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public volatile Consumer<HttpExchange> handler = exchange -> reply(exchange, 200, "{\"code\":0,\"data\":[]}");
    public TargetFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.accept(exchange));
        server.setExecutor(executor); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public URI origin() { return URI.create("http://127.0.0.1:" + port()); }
    public LoginRequest login() { return new LoginRequest("127.0.0.1", port(), "http", "test-user", "fixture-password", "PLAIN", "JSON", true); }
    public TargetApiClient client() { return new TargetApiClient(new JsonMapper(), 2, 2 * 1024 * 1024); }
    public static void reply(HttpExchange exchange, int status, String value) {
        try (exchange) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        } catch (IOException disconnected) { /* 取消测试会主动断开 fixture socket。 */ }
    }
    public static boolean loginReply(HttpExchange exchange) {
        if (!exchange.getRequestURI().getPath().equals("/sys/login")) return false;
        reply(exchange, 200, "{\"code\":200,\"token\":\"fixture-token\"}"); return true;
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
}
