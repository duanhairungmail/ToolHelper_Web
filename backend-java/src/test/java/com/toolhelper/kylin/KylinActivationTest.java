package com.toolhelper.kylin;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.service.KylinOpsService;
import com.toolhelper.support.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.kylin.KylinModels.*;
import static org.junit.jupiter.api.Assertions.*;

class KylinActivationTest {
    @TempDir Path root;
    static String xpm() {
        return "/* XPM */\nstatic const char *const qrcode_xpm[] = {\n\"21 21 2 1\",\n\"F c #000000\",\n\"B c #ffffff\",\n"
                + ("\"" + "F".repeat(10) + "B".repeat(11) + "\",\n").repeat(21) + "};\n";
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "等待状态超时");
    }
    static class Fixture implements AutoCloseable {
        final SshServer server = SshServer.setUpDefaultServer();
        final List<String> inputs = new CopyOnWriteArrayList<>(), commands = new CopyOnWriteArrayList<>();
        volatile boolean activated, reject, disconnect, malformed, legacy, chineseFragments, passwordless;
        volatile String qr = xpm();
        Fixture(Path root) throws Exception {
            var keys = new SimpleGeneratorHostKeyProvider(root.resolve("host-key")); keys.setAlgorithm("RSA");
            server.setHost("127.0.0.1"); server.setPort(0); server.setKeyPairProvider(keys);
            server.setPasswordAuthenticator((user, pass, session) -> user.equals("fixture") && pass.equals("fixture-password"));
            server.setCommandFactory((channel, command) -> new AbstractCommandSupport(command, null) {
                void output(String value) throws IOException { getOutputStream().write(value.getBytes(StandardCharsets.UTF_8)); getOutputStream().flush(); }
                String input(BufferedReader reader) throws IOException { String value = reader.readLine(); if (value == null) throw new EOFException(); inputs.add(value); return value; }
                void prompt(String value) throws Exception {
                    if (!chineseFragments) { output(value); return; }
                    int middle = value.length() / 2;
                    output(value.substring(0, middle)); Thread.sleep(25); output(value.substring(middle));
                }
                @Override public void run() {
                    try {
                        commands.add(command);
                        if (command.contains("QRCODE_TYPE=XPM")) {
                            var reader = new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                            if (!passwordless) {
                                output("[TOOLHELPER_SUDO]");
                                if (!input(reader).equals("fixture-sudo")) { output("[TOOLHELPER_SUDO]"); onExit(1); return; }
                            }
                            prompt(chineseFragments ? "请按回车键继续..." : "Press Enter to continue..."); input(reader);
                            if (legacy) { output("Enter \"kylin\" to display the QR code of registration information, or directly press Enter to skip"); assertEquals("kylin", input(reader)); }
                            prompt(malformed ? "Registration information TWO-DIMENSIONAL code\nPress Enter to continue..." : qr + (chineseFragments ? "注册信息二维码\n请按回车键继续..." : "Registration information TWO-DIMENSIONAL code\nPress Enter to continue..."));
                            input(reader);
                            if (legacy) output("GTCE-EYJ2-WW6K-YGL4-73M4 123456789\nAbove is the registration code and serial number, please contact Kirin for activation code\n");
                            else { prompt(chineseFragments ? "请输入服务序列号：" : "Please enter the service serial number:"); input(reader); }
                            prompt(chineseFragments ? "请输入激活码" : "Please enter the activation code"); input(reader);
                            if (disconnect) { onExit(7); return; }
                            if (reject) { output("无效的激活码\n请重试："); input(reader); return; }
                            activated = true; output("[system_real]11, ret=0\n"); onExit(0); return;
                        }
                        if (command.contains("serial.status=")) output("serial.status=AVAILABLE\nserial.value=123456789\n");
                        else if (command.contains("kylin_activation_check")) output(activated ? "System is activated.\nExpiration date of technical service: 2030-01-01\n" : "System is not activated.\nExpiration date of trial: 2026-12-01\n");
                        else if (command.contains("os=")) output("os=kylin\nversion=V10\narch=x86_64\nuid=1000\nsystemd=yes\n");
                        onExit(0);
                    } catch (Exception failure) { onExit(1); }
                }
            });
            server.start();
        }
        Host host() { return new Host("127.0.0.1", server.getPort()); }
        @Override public void close() throws Exception { server.stop(true); }
    }
    KylinOpsService service(Fixture fixture) throws Exception {
        var service = new KylinOpsService(ToolHelperProperties.forTests(root.resolve("data")), new JsonMapper());
        var key = service.probe(fixture.host()); service.trust(new TrustRequest(key.host(), key.port(), key.fingerprint(), true));
        service.connect(new ConnectRequest(key.host(), key.port(), "fixture", "fixture-password")); return service;
    }
    ActivationRequest request(KylinOpsService service, String serial, boolean difference, String password) {
        return new ActivationRequest(service.connection().connectionId(), serial, true, difference, password);
    }
    @Test void qrParsingKeepsPixelsAndRejectsUnexpectedPalette() throws Exception {
        byte[] png = KylinActivation.qrPng(xpm()); var image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(135, image.getWidth()); assertEquals(0xffffff, image.getRGB(0, 0) & 0xffffff);
        assertEquals(0, image.getRGB(36, 36) & 0xffffff); assertEquals(0xffffff, image.getRGB(66, 36) & 0xffffff);
        assertNull(KylinActivation.qrPng("not a QR")); assertNull(KylinActivation.qrPng(xpm().substring(0, 80)));
        assertThrows(ApiException.class, () -> KylinActivation.qrPng(xpm().replace("#000000", "#ff0000")));
    }
    @Test void serialAndFiveColumnExportPreserveTextAndSecretsAreRedacted() throws Exception {
        assertEquals("00012345", KylinActivation.parseSerial("serial.status=AVAILABLE\nserial.value=00012345").serialNumber());
        assertNull(KylinActivation.parseSerial("serial.status=AVAILABLE\nserial.value=bad value").serialNumber());
        var request = new ActivationExport("站A", "00012345", "=secret-key", "generated-code");
        assertFalse(request.toString().contains("secret"));
        byte[] bytes = KylinActivation.export(request); String sheet = "";
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) if (entry.getName().equals("xl/worksheets/sheet1.xml")) sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sheet.contains("激活码")); assertTrue(sheet.contains("主机SN码")); assertTrue(sheet.contains("由目标系统自动绑定"));
        assertTrue(sheet.contains("00012345")); assertTrue(sheet.contains("=secret-key")); assertFalse(sheet.contains("<f>"));
        assertThrows(ApiException.class, () -> KylinActivation.export(new ActivationExport("", "1", "", "")));
    }
    @Test void waitsForUserThenUsesOriginalChannelAndGeneratedCodeOnly() throws Exception {
        try (var fixture = new Fixture(root); var service = service(fixture)) {
            var task = service.activate(request(service, "123456789", false, "fixture-sudo"));
            await(() -> service.activationTask(task.taskId()).qrReady());
            assertEquals(List.of("fixture-sudo", ""), fixture.inputs);
            assertEquals("KYLIN_BUSY", assertThrows(ApiException.class, () -> service.scan(new ScanRequest(service.connection().connectionId(), "activation", "fixture-sudo"))).code());
            assertNotNull(ImageIO.read(new ByteArrayInputStream(service.activationQr(task.taskId()))));
            service.continueActivation(task.taskId(), new ActivationContinue("generated-code"));
            assertThrows(ApiException.class, () -> service.continueActivation(task.taskId(), new ActivationContinue("again")));
            await(() -> !List.of("QUEUED", "RUNNING", "CANCELLING").contains(service.activationTask(task.taskId()).status()));
            var finished = service.activationTask(task.taskId()); assertEquals("SUCCEEDED", finished.status(), finished.message());
            assertEquals("ACTIVATED", finished.scan().findings().getFirst().status());
            assertEquals(List.of("fixture-sudo", "", "", "123456789", "generated-code"), fixture.inputs);
            assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("fixture-sudo") || c.contains("generated-code")));
            assertFalse(finished.toString().contains("generated-code")); assertThrows(ApiException.class, () -> service.activationQr(task.taskId()));
            var already = service.activate(request(service, "123456789", false, "fixture-sudo"));
            await(() -> service.activationTask(already.taskId()).code() != null);
            assertEquals("KYLIN_ALREADY_ACTIVATED", service.activationTask(already.taskId()).code());
        }
    }
    @Test void cancellationRejectionDifferenceAndWrongSudoStopWithoutReplaying() throws Exception {
        try (var fixture = new Fixture(root); var service = service(fixture)) {
            var difference = service.activate(request(service, "999999999", false, "fixture-sudo"));
            await(() -> service.activationTask(difference.taskId()).code() != null);
            assertEquals("KYLIN_SERIAL_DIFFERENCE", service.activationTask(difference.taskId()).code()); assertTrue(fixture.inputs.isEmpty());
            var wrong = service.activate(request(service, "123456789", false, "wrong-sudo"));
            await(() -> service.activationTask(wrong.taskId()).code() != null);
            assertEquals("KYLIN_SUDO_AUTH_FAILED", service.activationTask(wrong.taskId()).code());
            fixture.legacy = true;
            var cancel = service.activate(request(service, "123456789", false, "fixture-sudo"));
            await(() -> service.activationTask(cancel.taskId()).qrReady()); service.cancelActivation(cancel.taskId());
            await(() -> service.activationTask(cancel.taskId()).status().equals("CANCELLED")); assertFalse(fixture.activated);
            fixture.reject = true;
            var rejected = service.activate(request(service, "123456789", false, "fixture-sudo"));
            await(() -> service.activationTask(rejected.taskId()).qrReady()); service.continueActivation(rejected.taskId(), new ActivationContinue("bad-code"));
            await(() -> service.activationTask(rejected.taskId()).status().equals("FAILED"));
            assertEquals("KYLIN_ACTIVATION_REJECTED", service.activationTask(rejected.taskId()).code());
            assertEquals(1, Collections.frequency(fixture.inputs, "bad-code"));
        }
    }
    @Test void legacyMismatchMalformedQrAndDisconnectNeverActivateSilently() throws Exception {
        try (var fixture = new Fixture(root); var service = service(fixture)) {
            fixture.legacy = true;
            var mismatch = service.activate(request(service, "999999999", true, "fixture-sudo"));
            await(() -> service.activationTask(mismatch.taskId()).qrReady());
            service.continueActivation(mismatch.taskId(), new ActivationContinue("never-send-code"));
            await(() -> service.activationTask(mismatch.taskId()).code() != null);
            assertEquals("KYLIN_ACTIVATION_UNSUPPORTED", service.activationTask(mismatch.taskId()).code());
            assertFalse(fixture.inputs.contains("never-send-code"));
            fixture.legacy = false; fixture.malformed = true;
            var malformed = service.activate(request(service, "123456789", false, "fixture-sudo"));
            await(() -> service.activationTask(malformed.taskId()).code() != null);
            assertEquals("KYLIN_QR_UNSUPPORTED", service.activationTask(malformed.taskId()).code());
            fixture.malformed = false; fixture.disconnect = true;
            var lost = service.activate(request(service, "123456789", false, "fixture-sudo"));
            await(() -> service.activationTask(lost.taskId()).qrReady()); service.continueActivation(lost.taskId(), new ActivationContinue("generated-code"));
            await(() -> service.activationTask(lost.taskId()).status().equals("UNKNOWN_REMOTE_STATE"));
            assertFalse(fixture.activated);
        }
    }
    @Test void timeoutAndSingleSubmissionAreEnforcedWithoutSecretSnapshots() {
        var operation = new KylinActivation.Operation("c1"); operation.waiting(new byte[]{1}); operation.expiresAt = Instant.now().minusSeconds(1);
        assertThrows(ApiException.class, () -> operation.submit("code")); assertThrows(ApiException.class, operation::qr);
        operation.waiting(new byte[]{1}); operation.submit("temporary-code");
        assertFalse(operation.snapshot().toString().contains("temporary-code")); assertEquals("temporary-code", operation.takeCode()); assertNull(operation.takeCode());
        assertThrows(ApiException.class, () -> operation.submit("second")); operation.clear();
    }

    @Test void chineseFragmentedPromptsAndPasswordlessSudoKeepQrGate() throws Exception {
        try (var fixture = new Fixture(root); var service = service(fixture)) {
            fixture.chineseFragments = true; fixture.passwordless = true;
            var task = service.activate(request(service, "123456789", false, ""));
            await(() -> service.activationTask(task.taskId()).qrReady());
            assertEquals(List.of(""), fixture.inputs);
            service.continueActivation(task.taskId(), new ActivationContinue("generated-code"));
            await(() -> service.activationTask(task.taskId()).status().equals("SUCCEEDED"));
            assertEquals(List.of("", "", "123456789", "generated-code"), fixture.inputs);
        }
    }
}
