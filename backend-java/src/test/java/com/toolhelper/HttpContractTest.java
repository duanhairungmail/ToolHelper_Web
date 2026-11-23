package com.toolhelper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class HttpContractTest {
    @TempDir Path root;
    @Test void realHttpServerRequiresSessionRejectsUnknownFieldsAndNeverFallsBackForApi() throws Exception {
        var app = new SpringApplication(ToolHelperApplication.class);
        try (var context = app.run("--server.port=0", "--toolhelper.data-root=" + root,
                "--logging.file.name=" + Path.of("target", "http-contract.log").toAbsolutePath(), "--spring.main.banner-mode=off");
             var client = HttpClient.newHttpClient()) {
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
        }
    }
}
