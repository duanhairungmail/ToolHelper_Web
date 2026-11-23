package com.toolhelper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.FileAppender;
import com.toolhelper.config.ToolHelperProperties;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HttpContractTest {
    @TempDir Path root;
    @Test void realHttpServerRequiresSessionRejectsUnknownFieldsAndNeverFallsBackForApi() throws Exception {
        var app = new SpringApplication(ToolHelperApplication.class);
        try (var context = app.run("--server.port=0", "--toolhelper.data-root=" + root,
                "--spring.main.banner-mode=off");
             var client = HttpClient.newHttpClient()) {
            // 只覆盖数据目录时，日志也必须跟随同一目录，避免另行写入用户 C 盘。
            Path dataRoot = context.getBean(ToolHelperProperties.class).root();
            assertEquals(root.toAbsolutePath().normalize(), dataRoot);
            Path logFile = Path.of(context.getEnvironment().getRequiredProperty("logging.file.name"));
            assertEquals(dataRoot.resolve("logs/service.log"), logFile);
            assertTrue(Files.isRegularFile(logFile));
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String base = "http://127.0.0.1:" + port;
            var json = new JsonMapper();
            var session = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/session"))
                    .header("X-ToolHelper-Client", "web").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, session.statusCode(), session.body());
            String token = json.readTree(session.body()).path("data").path("token").asText();
            assertFalse(token.isBlank());
            var unauthorized = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plugins/electerm")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());
            var status = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plugins/electerm"))
                    .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("NOT_INSTALLED", json.readTree(status.body()).path("data").path("state").asText(), status.body());
            for (String body : List.of(
                    "{\"protocol\":\"SSH\",\"host\":\"127.0.0.1\",\"port\":22,\"username\":\"u\",\"command\":\"calc\"}",
                    "{\"protocol\":\"SSH\",\"host\":\"127.0.0.1\",\"port\":22.5,\"username\":\"u\"}")) {
                var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plugins/electerm/connect"))
                        .header("X-ToolHelper-Session", token).header("Idempotency-Key", "http-contract-key")
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, invalid.statusCode(), invalid.body());
                assertEquals("INVALID_REQUEST", json.readTree(invalid.body()).path("error").path("code").asText());
            }
            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/unknown"))
                    .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode(), missing.body());
            assertFalse(missing.body().contains("<html"));
            var page = client.send(HttpRequest.newBuilder(URI.create(base + "/tools/remote")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode(), page.body());
            assertTrue(page.body().contains("<div id=\"app\""));
        } finally {
            // 独立 SpringApplication 的文件日志默认保持到 JVM 退出；测试需提前释放自身日志，
            // 否则 Windows 无法清理 @TempDir。控制台日志继续供后续测试使用。
            Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            logger.iteratorForAppenders().forEachRemaining(appender -> {
                if (appender instanceof FileAppender<?> file
                        && Path.of(file.getFile()).equals(root.resolve("logs/service.log"))) {
                    logger.detachAppender(appender);
                    appender.stop();
                }
            });
        }
    }
}
