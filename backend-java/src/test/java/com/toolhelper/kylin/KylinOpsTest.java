package com.toolhelper.kylin;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.service.KylinOpsService;
import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.AbstractCommandSupport;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.kylin.KylinModels.*;
import static org.junit.jupiter.api.Assertions.*;

class KylinOpsTest {
    @TempDir Path root;
    final JsonMapper json = new JsonMapper();
    static Params params() { return new Params("desktop", "0 0 1 * *", 30, 365, "127.0.0.1", 5901, "192.168.31.0/24", List.of("weather"), null); }
    static class Fixture implements AutoCloseable {
        final SshServer server = SshServer.setUpDefaultServer();
        final AtomicInteger authentications = new AtomicInteger();
        final List<String> commands = new CopyOnWriteArrayList<>();
        volatile String activation = "系统未激活";
        volatile int activationExitCode;
        volatile String fileState = "MISSING";
        volatile boolean failApply;
        volatile String restoreState;
        volatile String commandError;
        volatile boolean patchMode, failPatchValidate, failPatchInstall, failPatchQuery, patchNoVersionChange;
        volatile String patchVersion = "1.0.0.33-0k1.9", patchStatus = "install ok installed", patchUnit = "loaded|active|enabled", uploadedHash;
        Fixture(Path path) throws Exception {
            Files.createDirectories(path);
            var keys = new SimpleGeneratorHostKeyProvider(path.resolve("host-key")); keys.setAlgorithm("RSA"); keys.setKeySize(2048);
            server.setHost("127.0.0.1"); server.setPort(0); server.setKeyPairProvider(keys);
            server.setPasswordAuthenticator((user, password, session) -> { authentications.incrementAndGet(); return user.equals("fixture") && password.equals("fixture-password"); });
            server.setFileSystemFactory(new VirtualFileSystemFactory(path));
            server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
            server.setCommandFactory((channel, command) -> new AbstractCommandSupport(command, null) {
                @Override public void run() {
                    try {
                        commands.add(command);
                        if (command.contains("slow-command")) { Thread.sleep(2500); onExit(0); return; }
                        if (command.contains("huge-command")) { getOutputStream().write(new byte[KylinSshClient.OUTPUT_LIMIT + 1]); onExit(0); return; }
                        if (commandError != null) { getErrorStream().write(commandError.getBytes(StandardCharsets.UTF_8)); getErrorStream().flush(); onExit(1); return; }
                        if (patchMode && command.contains("echo comparison=OLDER")) {
                            if (failPatchQuery) { onExit(1); return; }
                            String comparison = patchVersion.equals(KylinPatch.VERSION) ? "EQUAL" : patchVersion.equals("2:1.0.0.33-0k1.1") ? "NEWER" : "OLDER";
                            getOutputStream().write(("os=kylin\nversion=v10\narch=amd64\npackage.status=" + patchStatus + "\npackage.version=" + patchVersion
                                    + "\npackage.arch=amd64\ncomparison=" + comparison + "\naudit=CLEAN\nunit.0=" + patchUnit + "\n").getBytes(StandardCharsets.UTF_8));
                            getOutputStream().flush(); onExit(0); return;
                        }
                        if (patchMode && command.contains("mkdir -m 700") && command.contains("/tmp/toolhelper-")) {
                            var staging = java.util.regex.Pattern.compile("/tmp/toolhelper-[a-f0-9-]{36}").matcher(command);
                            if (!staging.find()) throw new IllegalStateException("Missing staging");
                            Files.createDirectories(path.resolve(staging.group().substring(1)));
                        }
                        if (patchMode && command.contains("dpkg-deb -f")) {
                            var staging = java.util.regex.Pattern.compile("/tmp/toolhelper-[a-f0-9-]{36}").matcher(command);
                            if (!staging.find()) throw new IllegalStateException("Missing staging");
                            uploadedHash = KylinFiles.hash(Files.readAllBytes(path.resolve(staging.group().substring(1)).resolve("patch.deb")));
                            if (failPatchValidate || !uploadedHash.equals(KylinPatch.SHA256)) { onExit(1); return; }
                        }
                        if (patchMode && command.contains("dpkg --force-confold --install")) {
                            if (failPatchInstall) { patchStatus = "install ok unpacked"; onExit(1); return; }
                            if (!patchNoVersionChange) patchVersion = KylinPatch.VERSION;
                        }
                        if (command.contains("sudo-secret-check")) {
                            String stdin = new String(getInputStream().readNBytes(15), StandardCharsets.UTF_8);
                            getOutputStream().write((stdin.equals("fixture-sudo!!\n") ? "stdin-ok" : "stdin-invalid").getBytes(StandardCharsets.UTF_8));
                        } else if (command.contains("os=")) getOutputStream().write("os=kylin\nversion=V10\narch=x86_64\nuid=0\nsystemd=yes\ndpkg=yes\ndisplay=/usr/sbin/lightdm\ntimezone=CST\n".getBytes(StandardCharsets.UTF_8));
                        else if (command.contains("printf") && command.contains("file.0=")) getOutputStream().write(("file.0=" + fileState + "\n").getBytes(StandardCharsets.UTF_8));
                        else if (command.contains("chmod a-x")) {
                            if (failApply) { onExit(1); return; }
                            String[] attrs = fileState.split("\\|");
                            fileState = attrs[0] + "|644|" + attrs[2] + "|" + attrs[3];
                        }
                        else if (command.contains("install -o") && restoreState != null) fileState = restoreState;
                        else if (command.contains("kylin_activation_check")) {
                            getOutputStream().write(activation.getBytes(StandardCharsets.UTF_8));
                            getOutputStream().flush(); onExit(activationExitCode); return;
                        }
                        else if (command.contains("exit-seven")) { onExit(7); return; }
                        else getOutputStream().write("fixture-ok".getBytes(StandardCharsets.UTF_8));
                        onExit(0);
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); onExit(130); }
                    catch (Exception failure) { onExit(1); }
                }
            });
            server.start();
        }
        Host host() { return new Host("127.0.0.1", server.getPort()); }
        public void close() throws Exception { server.close(false).await(5000); }
    }
    @Test void sudoFailuresUseCurrentUserGuidanceAndDoNotLeakRemoteOutput() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            var key = KylinSshClient.probe(fixture.host());
            try (var ssh = KylinSshClient.connect(fixture.host(), "fixture", "fixture-password", key.fingerprint())) {
                var cases = Map.of(
                        "sudo: no password was provided", "KYLIN_SUDO_PASSWORD_REQUIRED",
                        "Sorry, try again.\nsudo: 1 incorrect password attempt", "KYLIN_SUDO_AUTH_FAILED",
                        "fixture is not in the sudoers file.", "KYLIN_SUDO_FORBIDDEN",
                        "sudo: a terminal is required to read the password", "KYLIN_SUDO_TTY_REQUIRED");
                for (var item : cases.entrySet()) {
                    fixture.commandError = item.getKey() + "\nremote-sensitive-token";
                    String password = item.getValue().equals("KYLIN_SUDO_PASSWORD_REQUIRED") ? "" : "fixture-sudo!!";
                    var failure = assertThrows(ApiException.class, () -> KylinProbe.state(ssh, List.of(), List.of(), true, password, () -> false));
                    assertEquals(item.getValue(), failure.code());
                    assertFalse(failure.getMessage().contains("remote-sensitive-token"));
                }
                assertTrue(fixture.commands.stream().allMatch(c -> c.startsWith("LC_ALL=C sudo -k -S -p '' -- /bin/bash -c ")));
                assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("fixture-sudo!!")));
                // 普通脚本错误不能被误判成密码错误；原始stderr不进入Reply.toString或对外消息。
                fixture.commandError = "bash: sha256sum: command not found\nremote-sensitive-token";
                var result = ssh.run("read-only-state", true, "fixture-sudo!!", 2, () -> false);
                assertEquals(fixture.commandError, result.error());
                assertFalse(result.toString().contains("remote-sensitive-token"));
                var failure = KylinProbe.stateFailure(result);
                assertEquals("KYLIN_PROBE_TOOL_MISSING", failure.code());
                assertFalse(failure.getMessage().contains("remote-sensitive-token"));
            }
        }
    }
    @Test void stateFailureClassificationPreservesUnknownFailuresAndProtectsDiagnostics() {
        for (var item : Map.of(
                "sha256sum: permission denied", "KYLIN_PROBE_PERMISSION_DENIED",
                "Failed to connect to bus: error", "KYLIN_PROBE_SYSTEMD_ERROR",
                "tool: error while loading shared libraries: libcrypto.so", "KYLIN_PROBE_LIBRARY_ERROR",
                "unrecognized target error", "KYLIN_STATE_PROBE_FAILED").entrySet()) {
            var failure = KylinProbe.stateFailure(new KylinSshClient.Reply(7, "", item.getKey() + " secret"));
            assertEquals(item.getValue(), failure.code()); assertFalse(failure.getMessage().contains("secret"));
        }
        // 密码耗尽时sudo可能同时报告输入结束；已提供密码时应显示验证失败。
        assertEquals("KYLIN_SUDO_AUTH_FAILED", assertThrows(ApiException.class, () ->
                KylinSshClient.checkSudoFailure("sudo: no password was provided\nsudo: 1 incorrect password attempt", true)).code());
    }
    @Test void realSshHandshakePinsHostKeyAuthenticatesAndUsesSftp() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            Host host = fixture.host(); var key = KylinSshClient.probe(host);
            assertEquals(0, fixture.authentications.get()); assertTrue(key.fingerprint().startsWith("SHA256:"));
            var wrong = assertThrows(ApiException.class, () -> KylinSshClient.connect(host, "fixture", "fixture-password", "SHA256:wrong"));
            assertEquals("SSH_HOST_KEY_CHANGED", wrong.code()); assertEquals(0, fixture.authentications.get());
            assertThrows(ApiException.class, () -> KylinSshClient.connect(host, "fixture", "wrong", key.fingerprint()));
            try (var ssh = KylinSshClient.connect(host, "fixture", "fixture-password", key.fingerprint())) {
                assertTrue(ssh.connected()); assertEquals("kylin", KylinProbe.system(ssh).get("os"));
                var result = ssh.run("sudo-secret-check", true, "fixture-sudo!!", 2, () -> false);
                assertEquals(0, result.exitCode()); assertEquals("stdin-ok", result.output());
                assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("fixture-sudo!!")));
                ssh.upload("/uploaded", new byte[]{1, 2, 3}); assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("ssh/uploaded")));
                assertEquals(7, ssh.run("exit-seven", false, null, 2, () -> false).exitCode());
            }
        }
    }
    @Test void outputTimeoutAndCancellationDoNotClaimSuccess() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            var key = KylinSshClient.probe(fixture.host());
            try (var ssh = KylinSshClient.connect(fixture.host(), "fixture", "fixture-password", key.fingerprint())) {
                assertEquals("SSH_OUTPUT_LIMIT", assertThrows(ApiException.class, () -> ssh.run("huge-command", false, null, 2, () -> false)).code());
                assertEquals("SSH_COMMAND_TIMEOUT", assertThrows(ApiException.class, () -> ssh.run("slow-command", false, null, 1, () -> false)).code());
                int previous = fixture.commands.size();
                assertEquals("CANCELLED", assertThrows(ApiException.class, () -> ssh.run("never-run", false, null, 2, () -> true)).code());
                assertEquals(previous, fixture.commands.size());
            }
        }
    }
    @Test void trustRequiresFreshConfirmationAndDoesNotReplaceChangedKeys() throws Exception {
        var store = new KylinHostKeyStore(root.resolve("data"), json);
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            var host = fixture.host(); var key = store.probe(host);
            assertThrows(ApiException.class, () -> store.trust(new TrustRequest(host.host(), host.port(), key.fingerprint(), false)));
            assertThrows(ApiException.class, () -> store.trust(new TrustRequest(host.host(), host.port(), "SHA256:other", true)));
            assertTrue(store.trust(new TrustRequest(host.host(), host.port(), key.fingerprint(), true)).trusted());
            assertEquals(key.fingerprint(), store.trusted(host));
            var loaded = new KylinHostKeyStore(root.resolve("data"), json); assertEquals(key.fingerprint(), loaded.trusted(host));
        }
    }
    @Test void activationRecognizesChineseScreenshotsAndEnglishSudoOutput() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            var key = KylinSshClient.probe(fixture.host());
            try (var ssh = KylinSshClient.connect(fixture.host(), "fixture", "fixture-password", key.fingerprint())) {
                // 中文来自用户两张截图；英文两种状态文本已在目标命令文件中核对。
                var cases = List.of(
                        Map.entry("试用到期时间： 2026-07-29\n系统未被激活\n", "NOT_ACTIVATED"),
                        Map.entry("系统已激活\n技术服务到期时间： 2025-01-01\n", "ACTIVATED"),
                        Map.entry("System is not activated.\n", "NOT_ACTIVATED"),
                        Map.entry("System is activated.\n", "ACTIVATED"),
                        Map.entry("系统未激活\r\n", "NOT_ACTIVATED"),
                        Map.entry("  系统激活  \r\n", "ACTIVATED"));
                for (var item : cases) {
                    fixture.activation = item.getKey();
                    var scan = KylinProbe.scan(ssh, "activation", true, "fixture-sudo!!");
                    assertEquals(item.getValue(), scan.findings().getFirst().status(), item.getKey());
                }
                assertTrue(fixture.commands.stream().allMatch(c -> c.startsWith("LC_ALL=C sudo -k -S -p '' -- /bin/bash -c ")));
                assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("fixture-sudo!!")));
            }
        }
    }
    @Test void activationShowsOnlyTheExpirationDateForItsStatus() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            var key = KylinSshClient.probe(fixture.host());
            try (var ssh = KylinSshClient.connect(fixture.host(), "fixture", "fixture-password", key.fingerprint())) {
                var cases = List.of(
                        Map.entry("试用到期时间： 2026-07-29\n系统未被激活", "试用期到期时间：2026-07-29"),
                        Map.entry("系统已激活\n技术服务到期时间： 2025-01-01", "技术服务到期时间：2025-01-01"),
                        Map.entry("Expiration date of trial: 2026-07-29\nSystem is not activated.", "试用期到期时间：2026-07-29"),
                        Map.entry("System is activated.\nExpiration date of technical service: 2025-01-01", "技术服务到期时间：2025-01-01"),
                        Map.entry("系统未激活\n试用期到期时间: 2024-02-29\n技术服务到期时间：2025-01-01", "试用期到期时间：2024-02-29"),
                        Map.entry("系统已激活\n试用到期时间：2026-07-29\n技术服务到期时间：2025-01-01", "技术服务到期时间：2025-01-01"),
                        Map.entry("系统未被激活\n技术服务到期时间：2025-01-01", "试用期到期时间：未获取"),
                        Map.entry("系统已激活\n试用到期时间：2026-07-29", "技术服务到期时间：未获取"),
                        Map.entry("系统未激活\n试用到期时间：2025-02-29", "试用期到期时间：未获取"),
                        Map.entry("系统已激活\n技术服务到期时间：2025-01-01\n技术服务到期时间：2026-01-01", "技术服务到期时间：未获取"),
                        Map.entry("系统已激活\n技术服务到期时间：2025-01-01 sensitive-data", "技术服务到期时间：未获取"),
                        Map.entry("系统未被激活\n系统已激活\n试用到期时间：2026-07-29", "未获取明确激活状态"));
                for (var item : cases) {
                    fixture.activation = item.getKey();
                    var finding = KylinProbe.scan(ssh, "activation", true, "fixture-sudo!!").findings().getFirst();
                    assertEquals(item.getValue(), finding.detail(), item.getKey());
                    if (!item.getValue().equals("未获取明确激活状态"))
                        assertEquals(item.getValue().startsWith("试用期") ? "NOT_ACTIVATED" : "ACTIVATED", finding.status());
                }
                fixture.activation = "系统已激活\n技术服务到期时间：2025-01-01";
                fixture.activationExitCode = 7;
                assertEquals("未获取明确激活状态", KylinProbe.scan(ssh, "activation", false, null).findings().getFirst().detail());
            }
        }
    }
    @Test void activationRejectsFailedAmbiguousAndDateOnlyOutput() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh"))) {
            var key = KylinSshClient.probe(fixture.host());
            try (var ssh = KylinSshClient.connect(fixture.host(), "fixture", "fixture-password", key.fingerprint())) {
                for (String output : List.of("", "试用到期时间：2026-07-29", "技术服务到期时间：2025-01-01",
                        "System is activated.\nSystem is not activated.", "系统已激活\nSystem is not activated.",
                        "系统未被激活\nSystem is activated.", "Activation service is active")) {
                    fixture.activation = output;
                    assertEquals("UNKNOWN", KylinProbe.scan(ssh, "activation", false, null).findings().getFirst().status(), output);
                }
                // 清晰文字不能掩盖命令执行失败；也不能把未激活中的activated子串当成已激活。
                fixture.activationExitCode = 7;
                for (String output : List.of("System is activated.", "System is not activated.", "系统已激活", "系统未被激活")) {
                    fixture.activation = output;
                    assertEquals("UNKNOWN", KylinProbe.scan(ssh, "activation", false, null).findings().getFirst().status(), output);
                }
            }
        }
    }
    @Test void serviceKeepsCredentialsOutOfConnectionAndActivationIsReadOnly() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh")); var service = new KylinOpsService(ToolHelperProperties.forTests(root.resolve("data")), json)) {
            Host host = fixture.host();
            assertThrows(ApiException.class, () -> service.connect(new ConnectRequest(host.host(), host.port(), "fixture", "fixture-password")));
            var key = service.probe(host); service.trust(new TrustRequest(host.host(), host.port(), key.fingerprint(), true));
            var connection = service.connect(new ConnectRequest(host.host(), host.port(), "fixture", "fixture-password"));
            assertFalse(json.writeValueAsString(connection).contains("fixture-password"));
            var scan = service.scan(new ScanRequest(connection.connectionId(), "activation", null));
            assertEquals("NOT_ACTIVATED", scan.findings().getFirst().status());
            fixture.activation = "系统已激活\n系统未激活";
            assertEquals("UNKNOWN", service.scan(new ScanRequest(connection.connectionId(), "activation", null)).findings().getFirst().status());
            assertEquals("KYLIN_ACTION_NOT_SUPPORTED", assertThrows(ApiException.class,
                    () -> service.plan(new PlanRequest(connection.connectionId(), "activation", "DEPLOY", params(), null))).code());
            assertThrows(ApiException.class, () -> service.execute(new ExecuteRequest("unknown", false, "secret", null)));
            assertThrows(ApiException.class, () -> service.execute(new ExecuteRequest("unknown", true, "secret", null)));
            Instant idleExpiry = service.connection().expiresAt();
            service.backups(connection.connectionId());
            assertEquals(idleExpiry, service.connection().expiresAt(), "后台备份轮询不能续期SSH空闲连接");
            service.disconnect(connection.connectionId()); assertNull(service.connection());
            assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("reboot") || c.contains("install ")));
        }
    }
    @Test void templatesUseValidatedParametersScopedAutoLoginAndRestrictedDatabaseSources() {
        for (String bad : List.of("0 0 * * *; reboot", "60 0 1 * *", "0 0 0 0 *", "@reboot")) assertThrows(ApiException.class, () -> KylinTemplates.cron(bad));
        assertThrows(ApiException.class, () -> KylinTemplates.user("u;reboot"));
        for (String bad : List.of("0.0.0.0/0", "192.168.31.1/24", "192.168.999.0/24", "x/24")) assertThrows(ApiException.class, () -> KylinTemplates.cidr(bad));
        assertEquals("192.168.31.0/24", KylinTemplates.cidr("192.168.31.0/24"));
        String reboot = KylinTemplates.reboot(params()).stream().map(f -> new String(f.bytes(), StandardCharsets.UTF_8)).reduce("", String::concat);
        assertTrue(reboot.contains("autologin-user=%s")); assertTrue(reboot.contains("/sbin/reboot")); assertFalse(reboot.contains("NOPASSWD"));
        assertTrue(reboot.contains("boot_id")); assertTrue(reboot.contains("loginctl show-session")); assertTrue(reboot.contains("{1..30}"));
        var files = KylinTemplates.database(params(), KylinTemplates.asset("pg_hba.conf"), KylinTemplates.asset("postgresql.conf"));
        String hba = new String(files.getFirst().bytes(), StandardCharsets.UTF_8);
        var activeRules = hba.lines().filter(s -> !s.isBlank() && !s.stripLeading().startsWith("#")).toList();
        assertTrue(activeRules.stream().noneMatch(s -> s.contains("0.0.0.0/0") || s.matches(".*\\btrust\\b.*")));
        assertTrue(hba.contains("192.168.31.0/24 sha256"));
        String config = new String(files.get(1).bytes(), StandardCharsets.UTF_8); assertTrue(config.contains("listen_addresses = '127.0.0.1'"));
        assertEquals(List.of("/usr/local/bin/clean-logs.sh"), KylinTemplates.logs(params(), false).stream().map(KylinTemplates.File::path).toList());
        assertThrows(ApiException.class, () -> KylinPolicyCatalog.selected(List.of("audit")));
        assertThrows(ApiException.class, () -> KylinPolicyCatalog.selected(List.of("not-a-policy")));
    }
    @Test void remoteStatusCannotInjectShellAndBackupsCannotEscapeFixedPolicies() throws Exception {
        assertThrows(ApiException.class, () -> KylinProbe.validate(Map.of("file.0", "hash|600|0;reboot|0"), 1, 0));
        var store = new KylinBackupStore(root, json);
        var backup = new Backup(UUID.randomUUID().toString(), "host", 22, "fingerprint", "logs", List.of("/etc/passwd"), List.of(), Map.of("file.0", "MISSING"), Instant.now());
        store.save(backup); assertThrows(ApiException.class, () -> store.get(backup.backupId()));
        assertThrows(ApiException.class, () -> store.get("../../escape"));
        assertEquals("inactive", KylinProbe.labels("active=inactive\n").get("active"));
    }
    @Test void traditionalVncPasswordMatchesPublishedDesKeyConventionAndRejectsTruncation() throws Exception {
        byte[] encoded = KylinTemplates.vncPassword("Fixture!");
        byte[] reversedKey = {(byte) 232, 74, (byte) 214, 96, (byte) 196, 114, 26, (byte) 224};
        var cipher = Cipher.getInstance("DES/ECB/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(reversedKey, "DES"));
        assertEquals("Fixture!", new String(cipher.doFinal(encoded), StandardCharsets.US_ASCII));
        assertThrows(ApiException.class, () -> KylinTemplates.vncPassword("too-long-password"));
        assertThrows(ApiException.class, () -> KylinTemplates.vncPassword("中文123456"));
    }
    @Test void stalePlanNeverDispatchesMutationAndSuccessfulPlanCannotReplay() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh")); var service = connectedService(fixture)) {
            fixture.fileState = "a".repeat(64) + "|755|0|0";
            String connectionId = service.connection().connectionId();
            Plan stale = service.plan(new PlanRequest(connectionId, "optimization", "APPLY", params(), null));
            fixture.fileState = "b".repeat(64) + "|755|0|0";
            Task rejected = await(service, service.execute(new ExecuteRequest(stale.planId(), true, null, null)));
            assertEquals("FAILED", rejected.status()); assertEquals("KYLIN_PLAN_STALE", rejected.code());
            assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("chmod a-x")));
            Plan fresh = service.plan(new PlanRequest(connectionId, "optimization", "APPLY", params(), null));
            assertEquals("KYLIN_CONFIRMATION_REQUIRED", assertThrows(ApiException.class,
                    () -> service.execute(new ExecuteRequest(fresh.planId(), false, null, null))).code());
            Task completed = await(service, service.execute(new ExecuteRequest(fresh.planId(), true, null, null)));
            assertEquals("SUCCEEDED", completed.status()); assertTrue(fixture.fileState.contains("|644|"));
            assertEquals("KYLIN_PLAN_EXPIRED", assertThrows(ApiException.class,
                    () -> service.execute(new ExecuteRequest(fresh.planId(), true, null, null))).code());
            assertTrue(service.backups(connectionId).stream().anyMatch(b -> b.backupId().equals(completed.backupId())));
            fixture.restoreState = "b".repeat(64) + "|755|0|0";
            Params restore = new Params(null, null, null, null, null, null, null, null, completed.backupId());
            Plan restorePlan = service.plan(new PlanRequest(connectionId, "optimization", "RESTORE", restore, null));
            assertEquals("SUCCEEDED", await(service, service.execute(new ExecuteRequest(restorePlan.planId(), true, null, null))).status());
            assertEquals(fixture.restoreState, fixture.fileState);
        }
    }
    @Test void applyFailureReportsPartialAndRecordsActualStateWithoutAutomaticRetry() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh")); var service = connectedService(fixture)) {
            fixture.fileState = "a".repeat(64) + "|755|0|0"; fixture.failApply = true;
            Plan plan = service.plan(new PlanRequest(service.connection().connectionId(), "optimization", "APPLY", params(), null));
            Task failed = await(service, service.execute(new ExecuteRequest(plan.planId(), true, null, null)));
            assertEquals("PARTIAL", failed.status()); assertEquals("KYLIN_APPLY_FAILED", failed.code());
            assertEquals(1, fixture.commands.stream().filter(c -> c.contains("chmod a-x")).count());
            var backups = new KylinBackupStore(root.resolve("data/kylin"), json);
            assertEquals(fixture.fileState, backups.expected(failed.backupId()).get("file.0"));
            fixture.fileState = "c".repeat(64) + "|644|0|0";
            Params restore = new Params(null, null, null, null, null, null, null, null, failed.backupId());
            assertEquals("KYLIN_RESTORE_CONFLICT", assertThrows(ApiException.class,
                    () -> service.plan(new PlanRequest(service.connection().connectionId(), "optimization", "RESTORE", restore, null))).code());
        }
    }
    private KylinOpsService connectedService(Fixture fixture) throws Exception {
        var service = new KylinOpsService(ToolHelperProperties.forTests(root.resolve("data")), json);
        Host host = fixture.host(); HostKey key = service.probe(host);
        service.trust(new TrustRequest(host.host(), host.port(), key.fingerprint(), true));
        service.connect(new ConnectRequest(host.host(), host.port(), "fixture", "fixture-password")); return service;
    }
    @Test void uninstallAfterRepeatedChangesRestoresFirstStateAndRejectsExternalEdits() throws Exception {
        try (var fixture = new Fixture(root.resolve("ssh")); var service = connectedService(fixture)) {
            fixture.fileState = "a".repeat(64) + "|755|1000|1000";
            String original = fixture.fileState, connectionId = service.connection().connectionId();
            for (int i = 0; i < 2; i++) {
                Plan plan = service.plan(new PlanRequest(connectionId, "optimization", "APPLY", params(), null));
                assertEquals("SUCCEEDED", await(service, service.execute(new ExecuteRequest(plan.planId(), true, null, null))).status());
            }
            String managed = fixture.fileState;
            fixture.fileState = "b".repeat(64) + "|644|1000|1000";
            assertEquals("KYLIN_RESTORE_CONFLICT", assertThrows(ApiException.class,
                    () -> service.plan(new PlanRequest(connectionId, "optimization", "UNINSTALL", null, null))).code());
            fixture.fileState = managed;
            Plan uninstall = service.plan(new PlanRequest(connectionId, "optimization", "UNINSTALL", null, null));
            assertEquals(original, uninstall.changes().getFirst().afterHash());
            fixture.restoreState = original;
            assertEquals("SUCCEEDED", await(service, service.execute(new ExecuteRequest(uninstall.planId(), true, null, null))).status());
            assertEquals(original, fixture.fileState);
        }
    }
    @Test void initialBackupCombinesDifferentScopesAndKeepsFirstFileSources() throws Exception {
        var store = new KylinBackupStore(root, json);
        String script = "/usr/local/bin/clean-logs.sh", cron = "/etc/cron.d/clean-logs";
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        String hash = "a".repeat(64) + "|700|0|0";
        store.save(new Backup(first, "host", 22, "key", "logs", List.of(script), List.of(), Map.of("file.0", "MISSING"), Instant.EPOCH));
        store.receipt(first, Map.of("file.0", hash));
        store.save(new Backup(second, "host", 22, "key", "logs", List.of(script, cron), List.of("cron.service"),
                Map.of("file.0", hash, "file.1", "MISSING", "unit.0", "loaded|inactive|disabled"), Instant.EPOCH.plusSeconds(1)));
        store.receipt(second, Map.of("file.0", hash, "file.1", hash, "unit.0", "loaded|active|enabled"));
        var point = store.initial("host", 22, "key", "logs");
        assertEquals("MISSING", point.backup().state().get("file.0"));
        assertEquals("MISSING", point.backup().state().get("file.1"));
        assertEquals("loaded|inactive|disabled", point.backup().state().get("unit.0"));
        assertTrue(point.sources().get(script).contains(first));
        assertTrue(point.sources().get(cron).contains(second));
        assertEquals("loaded|active|enabled", point.expected().get("unit.0"));
        String failed = UUID.randomUUID().toString();
        store.save(new Backup(failed, "host", 22, "key", "logs", List.of(script), List.of(), Map.of("file.0", hash), Instant.EPOCH.plusSeconds(2)));
        assertEquals("BACKUP_STATE_UNKNOWN", assertThrows(ApiException.class, () -> store.initial("host", 22, "key", "logs")).code());
        store.unchanged(failed);
        assertEquals(point.expected(), store.initial("host", 22, "key", "logs").expected());
        assertEquals("KYLIN_NOT_MANAGED", assertThrows(ApiException.class, () -> store.initial("other", 22, "key", "logs")).code());
    }
    private static Task await(KylinOpsService service, Task submitted) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Task task = service.task(submitted.taskId());
            if (task.finishedAt() != null) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("Fixture task did not finish");
    }
    @Test void missingSystemdUnitCanBePreviewedButQueryErrorsFailClosed() throws Exception {
        Path bash = Path.of("D:/DevlopEnvironment/hermes-git/bin/bash.exe");
        Assumptions.assumeTrue(Files.isExecutable(bash), "需要已安装的Bash验证状态脚本");
        // 用Shell函数代替systemctl；只执行读取状态的生成脚本，不访问系统服务。
        String probe = KylinProbe.stateScript(List.of(), List.of("fixture.service"));
        for (boolean missing : List.of(true, false)) {
            String response = missing ? "not-found" : "";
            Path script = root.resolve("unit-query-" + missing + ".sh");
            Files.writeString(script, "systemctl() { printf '%s\\n' '" + response + "'; return 1; }\n" + probe);
            var process = new ProcessBuilder(bash.toString(), script.toString().replace('\\', '/')).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (missing) {
                assertEquals(0, exitCode, output);
                assertEquals("MISSING", KylinProbe.labels(output).get("unit.0"));
            } else assertNotEquals(0, exitCode, "查询失败不能当成服务不存在");
        }
    }
    @Test void generatedScriptsParseWithBashWithoutExecutingRemoteActions() throws Exception {
        Path bash = Path.of("D:/DevlopEnvironment/hermes-git/bin/bash.exe");
        Assumptions.assumeTrue(Files.isExecutable(bash), "需要已安装的Bash进行语法检查");
        var scripts = new ArrayList<String>();
        for (var file : KylinTemplates.reboot(params())) if (file.path().endsWith(".sh")) scripts.add(new String(file.bytes(), StandardCharsets.UTF_8));
        scripts.add(new String(KylinTemplates.logs(params()).getFirst().bytes(), StandardCharsets.UTF_8));
        scripts.add(KylinProbe.stateScript(KylinPolicyCatalog.paths("reboot", List.of()), KylinPolicyCatalog.units("reboot", List.of())));
        // 校验真实执行器生成的应用和恢复脚本，不运行其中的服务、文件修改或重启。
        var before = Map.of("file.0", "a".repeat(64) + "|755|1000|1000", "unit.0", "loaded|active|enabled");
        var plan = new Plan("plan", "connection", "fixture", "optimization", "APPLY", "revision", List.of(), List.of(), List.of(), UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
        var selected = KylinPolicyCatalog.selected(List.of("weather"));
        var prepared = new KylinPlanService.Prepared(plan, List.of(), List.of("/usr/bin/kylin-weather"), List.of(), Map.of("file.0", before.get("file.0")), selected, null, params());
        scripts.add(KylinExecutor.apply(prepared, List.of(), "/tmp/toolhelper-fixture"));
        var backup = new Backup(plan.backupId(), "fixture", 22, "fingerprint", "optimization", List.of("/usr/bin/kylin-weather"), List.of("bluetooth.service"), before, Instant.now());
        scripts.add(KylinExecutor.restore(backup));
        for (int i = 0; i < scripts.size(); i++) {
            Path script = root.resolve("script-" + i + ".sh"); Files.writeString(script, scripts.get(i));
            var process = new ProcessBuilder(bash.toString(), "-n", script.toString().replace('\\', '/')).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        }
    }
}
