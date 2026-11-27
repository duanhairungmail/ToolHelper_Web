package com.toolhelper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.FileAppender;
import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.validation.TargetFixture;
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
        try (var target = new TargetFixture();
             var context = app.run("--server.port=0", "--toolhelper.data-root=" + root,
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
            var modbusUnauthorized = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/modbus/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, modbusUnauthorized.statusCode());
            var modbusState = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/modbus/session"))
                    .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, modbusState.statusCode(), modbusState.body());
            assertTrue(json.readTree(modbusState.body()).path("data").isNull(), modbusState.body());
            var invalidModbusOpen = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/modbus/session"))
                    .header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"port\":\"COM0\",\"baudRate\":9600,\"protocol\":\"SHEN_HONG\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidModbusOpen.statusCode(), invalidModbusOpen.body());
            assertEquals("INVALID_MODBUS_CONFIG", json.readTree(invalidModbusOpen.body()).path("error").path("code").asText());
            var status = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plugins/electerm"))
                    .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("NOT_INSTALLED", json.readTree(status.body()).path("data").path("state").asText(), status.body());
            var dbxStatus = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plugins/dbx"))
                    .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dbxStatus.statusCode(), dbxStatus.body());
            var dbxData = json.readTree(dbxStatus.body()).path("data");
            assertEquals("dbx", dbxData.path("pluginId").asText());
            assertEquals("NOT_INSTALLED", dbxData.path("state").asText());
            assertTrue(dbxData.path("directory").asText().endsWith("plugins" + java.io.File.separator + "dbx"));
            // 阶段4 HTTP 契约不探测真实网络：台账、版本冲突、空IP任务和导出。
            String mac = base + "/api/v1/device-mac";
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(mac + "/ledger")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var macActive = client.send(HttpRequest.newBuilder(URI.create(mac + "/queries/active")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, macActive.statusCode()); assertTrue(json.readTree(macActive.body()).path("data").isNull());
            String macId = UUID.randomUUID().toString();
            String macRows = "[{\"recordId\":\"" + macId + "\",\"stationName\":\"contract\",\"ipAddress\":\"\",\"deviceName\":\"\",\"deviceModel\":\"\",\"serialNumber\":\"90071992547409931234\",\"location\":\"\",\"lastObservation\":null}]";
            String saveMac = "{\"expectedRevision\":\"initial\",\"records\":" + macRows + "}";
            var macSaved = client.send(HttpRequest.newBuilder(URI.create(mac + "/ledger/save")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(saveMac)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, macSaved.statusCode(), macSaved.body());
            assertEquals(1, json.readTree(macSaved.body()).path("data").path("records").size());
            assertEquals(409, client.send(HttpRequest.newBuilder(URI.create(mac + "/ledger/save")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(saveMac)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var invalidMac = client.send(HttpRequest.newBuilder(URI.create(mac + "/queries")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"items\":[],\"script\":\"bad\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidMac.statusCode()); assertEquals("INVALID_REQUEST", json.readTree(invalidMac.body()).path("error").path("code").asText());
            var macQuery = client.send(HttpRequest.newBuilder(URI.create(mac + "/queries")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"items\":[{\"recordId\":\"" + macId + "\",\"ipAddress\":\"\"}]}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, macQuery.statusCode());
            String macTaskId = json.readTree(macQuery.body()).path("data").path("taskId").asText();
            for (int i = 0; i < 100; i++) {
                var macTask = client.send(HttpRequest.newBuilder(URI.create(mac + "/queries/" + macTaskId)).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
                var data = json.readTree(macTask.body()).path("data");
                if (data.path("status").asText().equals("COMPLETED")) { assertEquals("SKIPPED", data.path("results").get(0).path("status").asText()); break; }
                if (i == 99) fail("MAC空IP任务未结束");
                Thread.sleep(10);
            }
            for (String format : List.of("xlsx", "docx")) {
                String body = "{\"format\":\"" + format + "\",\"columns\":[\"index\",\"serialNumber\"],\"records\":" + macRows + "}";
                var macExport = client.send(HttpRequest.newBuilder(URI.create(mac + "/export")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, macExport.statusCode()); assertEquals('P', macExport.body()[0]); assertEquals('K', macExport.body()[1]);
            }
            // 阶段5真实HTTP契约：仅访问本机夹具，不探测用户设备。
            String druid = base + "/api/v1/druid";
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(druid + "/capabilities")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var druidCaps = client.send(HttpRequest.newBuilder(URI.create(druid + "/capabilities")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("DIRECT", json.readTree(druidCaps.body()).path("data").path("networkMode").asText());
            var druidActive = client.send(HttpRequest.newBuilder(URI.create(druid + "/scans/active")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(json.readTree(druidActive.body()).path("data").isNull());
            var druidInvalid = client.send(HttpRequest.newBuilder(URI.create(druid + "/scans")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"requestKey\":\"" + UUID.randomUUID() + "\",\"targets\":[],\"method\":\"POST\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, druidInvalid.statusCode());
            target.handler = exchange -> TargetFixture.reply(exchange, 200, exchange.getRequestURI().getPath().endsWith("basic.json")
                    ? "{\"ResultCode\":1,\"Content\":{\"Version\":\"1.2.27\",\"Drivers\":[]}}" : "<html><title>Druid Stat Index</title></html>");
            var druidSubmit = client.send(HttpRequest.newBuilder(URI.create(druid + "/scans")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("requestKey", UUID.randomUUID().toString(), "targets", List.of(target.origin() + "/app/druid/"))))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, druidSubmit.statusCode(), druidSubmit.body());
            String druidId = json.readTree(druidSubmit.body()).path("data").path("taskId").asText();
            for (int i = 0; i < 100; i++) {
                var state = client.send(HttpRequest.newBuilder(URI.create(druid + "/scans/" + druidId)).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (json.readTree(state.body()).path("data").path("status").asText().equals("COMPLETED")) break;
                if (i == 99) fail("Druid HTTP任务没有结束"); Thread.sleep(20);
            }
            var druidResults = client.send(HttpRequest.newBuilder(URI.create(druid + "/scans/" + druidId + "/results")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            String druidTargetId = json.readTree(druidResults.body()).path("data").path("items").get(0).path("targetId").asText();
            assertEquals("DATA_EXPOSED", json.readTree(druidResults.body()).path("data").path("items").get(0).path("retest").path("status").asText());
            var druidDetail = client.send(HttpRequest.newBuilder(URI.create(druid + "/scans/" + druidId + "/results/" + druidTargetId))
                    .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, druidDetail.statusCode());
            assertEquals("DATA_EXPOSED", json.readTree(druidDetail.body()).path("data").path("retest").path("status").asText());
            assertEquals("PAGE_EXPOSED", json.readTree(druidDetail.body()).path("data").path("evidence").get(0).path("retest").path("status").asText());
            for (String format : List.of("xlsx", "docx")) {
                var exported = client.send(HttpRequest.newBuilder(URI.create(druid + "/export")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("taskId", druidId, "targetIds", List.of(druidTargetId), "columns", List.of("index", "target", "severity"), "format", format, "details", true)))).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, exported.statusCode()); assertEquals('P', exported.body()[0]);
            }
            var druidClear = client.send(HttpRequest.newBuilder(URI.create(druid + "/results")).header("X-ToolHelper-Session", token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, druidClear.statusCode());
            // 阶段6契约只检查本机接口，不连接真实SSH主机。
            String kylin = base + "/api/v1/kylin";
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(kylin + "/capabilities")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var kylinCaps = client.send(HttpRequest.newBuilder(URI.create(kylin + "/capabilities")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, kylinCaps.statusCode());
            assertEquals(7, json.readTree(kylinCaps.body()).path("data").path("features").size());
            assertEquals(4, json.readTree(kylinCaps.body()).path("data").path("assets").size());
            assertEquals("1.0.0.33-0k1.11", json.readTree(kylinCaps.body()).path("data").path("patch").path("version").asText());
            assertEquals("REPAIR", json.readTree(kylinCaps.body()).path("data").path("features").get(5).path("actions").get(0).asText());
            for (String path : List.of("/connections/current", "/tasks/active", "/activation-tasks/current")) {
                var idle = client.send(HttpRequest.newBuilder(URI.create(kylin + path)).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(json.readTree(idle.body()).path("data").isNull());
            }
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(kylin + "/activation-tasks/current")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var qrMissing = client.send(HttpRequest.newBuilder(URI.create(kylin + "/activation-tasks/missing/qr")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, qrMissing.statusCode()); assertEquals("no-store", qrMissing.headers().firstValue("Cache-Control").orElse(""));
            String activationExportBody = json.writeValueAsString(Map.of("stationName", "测试站", "serialNumber", "000123456", "activationKey", "export-secret-key", "activationCode", "export-generated-code"));
            var activationExport = client.send(HttpRequest.newBuilder(URI.create(kylin + "/activation-export")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(activationExportBody)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, activationExport.statusCode()); assertEquals('P', activationExport.body()[0]); assertEquals('K', activationExport.body()[1]);
            assertEquals("no-store", activationExport.headers().firstValue("Cache-Control").orElse(""));
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(kylin + "/activation-tasks")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"connectionId\":\"none\",\"serialNumber\":\"123456789\",\"command\":\"untrusted\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var invalidPlan = client.send(HttpRequest.newBuilder(URI.create(kylin + "/plans")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"connectionId\":\"none\",\"feature\":\"logs\",\"action\":\"DEPLOY\",\"command\":\"reboot\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidPlan.statusCode());
            var unconfirmed = client.send(HttpRequest.newBuilder(URI.create(kylin + "/executions")).header("X-ToolHelper-Session", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"planId\":\"none\",\"confirmed\":false}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, unconfirmed.statusCode());
            assertEquals("KYLIN_CONFIRMATION_REQUIRED", json.readTree(unconfirmed.body()).path("error").path("code").asText());
            // 阶段3也必须经过真实HTTP、本机会话和现有JSON严格反序列化。
            String validation = base + "/api/v1/api-validation";
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(validation + "/session")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var catalog = client.send(HttpRequest.newBuilder(URI.create(validation + "/endpoints")).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(16, json.readTree(catalog.body()).path("data").size());
            for (String body : List.of("{\"host\":\"localhost\",\"port\":8088.5}", "{\"host\":\"localhost\",\"arbitraryUrl\":\"http://bad\"}")) {
                var invalid = client.send(HttpRequest.newBuilder(URI.create(validation + "/login")).header("X-ToolHelper-Session", token)
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, invalid.statusCode());
            }
            target.handler = exchange -> {
                if (!TargetFixture.loginReply(exchange)) TargetFixture.reply(exchange, 200, "{\"code\":0,\"totalCount\":1,\"data\":[{\"id\":\"9007199254740993\",\"deviceName\":\"fixture-device\",\"password\":\"raw-sensitive-response\"}]}");
            };
            var login = client.send(HttpRequest.newBuilder(URI.create(validation + "/login")).header("X-ToolHelper-Session", token)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(target.login()))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode(), login.body());
            var loginData = json.readTree(login.body()).path("data");
            String connection = loginData.path("connectionId").asText();
            assertTrue(loginData.path("connected").asBoolean()); assertFalse(login.body().contains("fixture-token"));
            var submitted = client.send(HttpRequest.newBuilder(URI.create(validation + "/device-ids")).header("X-ToolHelper-Session", token)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"connectionId\":\"" + connection + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, submitted.statusCode());
            String taskId = json.readTree(submitted.body()).path("data").path("taskId").asText();
            String terminal = "";
            for (int attempt = 0; attempt < 100; attempt++) {
                var task = client.send(HttpRequest.newBuilder(URI.create(validation + "/tasks/" + taskId)).header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofString());
                terminal = json.readTree(task.body()).path("data").path("status").asText();
                if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(terminal)) break;
                Thread.sleep(20);
            }
            assertEquals("SUCCEEDED", terminal);
            for (String format : List.of("xlsx", "docx")) {
                var exported = client.send(HttpRequest.newBuilder(URI.create(validation + "/export?connectionId=" + connection + "&view=devices&format=" + format))
                        .header("X-ToolHelper-Session", token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, exported.statusCode()); assertTrue(exported.headers().firstValue("Content-Disposition").orElse("").endsWith("." + format));
                assertEquals('P', exported.body()[0]); assertEquals('K', exported.body()[1]);
            }
            var stopped = client.send(HttpRequest.newBuilder(URI.create(validation + "/auto-detection?connectionId=" + connection)).header("X-ToolHelper-Session", token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stopped.statusCode(), stopped.body());
            String log = Files.readString(logFile);
            for (String secret : List.of("fixture-password", "fixture-token", "raw-sensitive-response", "export-secret-key", "export-generated-code")) assertFalse(log.contains(secret), "日志泄漏测试字段");
            for (String body : List.of(
                    "{\"type\":\"MYSQL\",\"host\":\"localhost\",\"port\":3306,\"username\":\"u\",\"args\":[\"--help\"]}",
                    "{\"type\":\"POSTGRESQL\",\"host\":\"localhost\",\"port\":5432.5,\"username\":\"u\"}",
                    "{\"type\":\"MYSQL\",\"host\":\"localhost\",\"port\":3306,\"username\":\"u\",\"url\":\"dbx://custom\"}")) {
                var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/plugins/dbx/connect"))
                        .header("X-ToolHelper-Session", token).header("Idempotency-Key", "dbx-contract-key")
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, invalid.statusCode(), invalid.body());
                assertEquals("INVALID_REQUEST", json.readTree(invalid.body()).path("error").path("code").asText());
            }
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
