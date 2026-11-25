package com.toolhelper.kylin;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.service.KylinOpsService;
import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.kylin.KylinModels.*;
import static org.junit.jupiter.api.Assertions.*;

class KylinPatchTest {
    @TempDir Path root;
    final JsonMapper json = new JsonMapper();
    private KylinOpsService service(KylinOpsTest.Fixture fixture) throws Exception {
        var service = new KylinOpsService(ToolHelperProperties.forTests(root.resolve("data")), json);
        var host = fixture.host(); var key = service.probe(host);
        service.trust(new TrustRequest(host.host(), host.port(), key.fingerprint(), true));
        service.connect(new ConnectRequest(host.host(), host.port(), "fixture", "fixture-password"));
        fixture.patchMode = true; return service;
    }
    private Plan repair(KylinOpsService service) {
        return service.plan(new PlanRequest(service.connection().connectionId(), "patch", "REPAIR", null, null));
    }
    private Task run(KylinOpsService service, Plan plan) throws Exception {
        var task = service.execute(new ExecuteRequest(plan.planId(), true, null, null));
        for (int i = 0; i < 200; i++) {
            task = service.task(task.taskId()); if (task.finishedAt() != null) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("Patch fixture task timeout");
    }
    @Test void bundledAssetMatchesUserDigestAndIsDebianArchive() throws Exception {
        byte[] asset = KylinTemplates.asset(KylinPatch.FILE);
        assertEquals(178172, asset.length); assertEquals(KylinPatch.SHA256, KylinFiles.hash(asset));
        try (var archive = new ArArchiveInputStream(new java.io.ByteArrayInputStream(asset))) {
            var entries = new ArrayList<String>(); org.apache.commons.compress.archivers.ar.ArArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) entries.add(entry.getName());
            assertTrue(entries.containsAll(List.of("debian-binary", "control.tar.xz", "data.tar.xz")));
        }
    }
    @Test void repairUploadsExactAssetInstallsOnceAndVerificationReadsFreshState() throws Exception {
        try (var fixture = new KylinOpsTest.Fixture(root.resolve("ssh")); var service = service(fixture)) {
            var initial = service.scan(new ScanRequest(service.connection().connectionId(), "patch", null));
            assertEquals("PATCH_REQUIRED", initial.findings().getFirst().status());
            Plan plan = repair(service);
            assertTrue(plan.impacts().stream().anyMatch(s -> s.contains("重启kylin-offline-upgrade.service")));
            assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("dpkg --force-confold --install")));
            assertEquals("KYLIN_CONFIRMATION_REQUIRED", assertThrows(ApiException.class, () ->
                    service.execute(new ExecuteRequest(plan.planId(), false, null, null))).code());
            var task = run(service, plan); assertEquals("SUCCEEDED", task.status(), task.message());
            assertEquals(KylinPatch.SHA256, fixture.uploadedHash);
            assertEquals(1, fixture.commands.stream().filter(c -> c.contains("dpkg --force-confold --install")).count());
            assertEquals("KYLIN_PLAN_EXPIRED", assertThrows(ApiException.class, () ->
                    service.execute(new ExecuteRequest(plan.planId(), true, null, null))).code());
            var verification = service.scan(new ScanRequest(service.connection().connectionId(), "patch", null));
            assertEquals("PATCH_VERSION_REACHED", verification.findings().getFirst().status());
            assertTrue(verification.findings().stream().anyMatch(f -> f.status().equals("PATCH_VERIFIED")));
            assertTrue(service.backups(service.connection().connectionId()).isEmpty(), "升级审计不能伪装为可恢复备份");
            assertTrue(Files.exists(root.resolve("data/kylin/operations/" + plan.backupId() + ".patch-after.json")));
            assertEquals("KYLIN_PATCH_ALREADY_CURRENT", assertThrows(ApiException.class, () -> repair(service)).code());
        }
    }
    @Test void validationFailureStopsBeforeInstallAndNewerVersionNeverDowngrades() throws Exception {
        try (var fixture = new KylinOpsTest.Fixture(root.resolve("ssh")); var service = service(fixture)) {
            fixture.failPatchValidate = true;
            var failure = run(service, repair(service));
            assertEquals("FAILED", failure.status()); assertEquals("KYLIN_PATCH_VALIDATION_FAILED", failure.code());
            assertTrue(fixture.commands.stream().noneMatch(c -> c.contains("dpkg --force-confold --install")));
            fixture.patchVersion = "2:1.0.0.33-0k1.1";
            assertEquals("KYLIN_PATCH_ALREADY_CURRENT", assertThrows(ApiException.class, () -> repair(service)).code());
            fixture.patchVersion = "1.0.0.33-0k1.9"; fixture.patchStatus = "install ok unpacked";
            assertEquals("KYLIN_PATCH_PACKAGE_UNHEALTHY", assertThrows(ApiException.class, () -> repair(service)).code());
            fixture.patchStatus = "hold ok installed";
            assertEquals("KYLIN_PATCH_PACKAGE_HELD", assertThrows(ApiException.class, () -> repair(service)).code());
            fixture.failPatchQuery = true;
            assertEquals("KYLIN_PATCH_QUERY_FAILED", assertThrows(ApiException.class, () -> repair(service)).code());
        }
    }
    @Test void stalePackagePlanAndPartialInstallNeverClaimFixedOrReplay() throws Exception {
        try (var fixture = new KylinOpsTest.Fixture(root.resolve("ssh")); var service = service(fixture)) {
            Plan stale = repair(service); fixture.patchUnit = "loaded|inactive|disabled";
            assertEquals("KYLIN_PLAN_STALE", run(service, stale).code());
            assertNull(fixture.uploadedHash);
            fixture.patchUnit = "loaded|active|enabled"; fixture.failPatchInstall = true;
            Task partial = run(service, repair(service));
            assertEquals("PARTIAL", partial.status()); assertEquals("KYLIN_PATCH_INSTALL_FAILED", partial.code());
            assertEquals("PACKAGE_UNHEALTHY", service.scan(new ScanRequest(service.connection().connectionId(), "patch", null)).findings().getFirst().status());
            assertEquals(1, fixture.commands.stream().filter(c -> c.contains("dpkg --force-confold --install")).count());
        }
    }
    @Test void installExitZeroStillRequiresVersionAndServiceVerification() throws Exception {
        try (var fixture = new KylinOpsTest.Fixture(root.resolve("ssh")); var service = service(fixture)) {
            fixture.patchNoVersionChange = true;
            assertEquals("KYLIN_PATCH_VERIFY_FAILED", run(service, repair(service)).code());
            fixture.patchNoVersionChange = false; fixture.patchUnit = "loaded|inactive|disabled";
            Task failure = run(service, repair(service));
            assertEquals("PARTIAL", failure.status()); assertEquals("KYLIN_PATCH_SERVICE_VERIFY_FAILED", failure.code());
            var scan = service.scan(new ScanRequest(service.connection().connectionId(), "patch", null));
            assertEquals("PATCH_VERSION_REACHED", scan.findings().getFirst().status());
            assertTrue(scan.findings().stream().anyMatch(f -> f.status().equals("PATCH_NOT_VERIFIED")));
        }
    }
    @Test void uploadedChecksumAndMetadataMustPassBeforeDpkgPreflight() throws Exception {
        Path bash = Path.of("D:/DevlopEnvironment/hermes-git/bin/bash.exe");
        Assumptions.assumeTrue(Files.isExecutable(bash));
        Path staging = Files.createDirectory(root.resolve("upload")), checked = Files.createDirectory(root.resolve("checked"));
        Path marker = root.resolve("preflight-called");
        String uploadPath = shellPath(staging), checkedPath = shellPath(checked), markerPath = shellPath(marker);
        for (String mode : List.of("valid", "corrupted", "wrong-package", "wrong-version", "wrong-arch", "dependency-failed")) {
            byte[] asset = KylinTemplates.asset(KylinPatch.FILE);
            if (mode.equals("corrupted")) asset[asset.length - 1] ^= 1;
            Files.write(staging.resolve("patch.deb"), asset);
            String functions = "install() { cp -- \"${@: -2:1}\" \"${@: -1}\"; }\n"
                    + "dpkg-deb() { case \"$3\" in Package) echo " + (mode.equals("wrong-package") ? "unrelated-package" : KylinPatch.PACKAGE)
                    + ";; Version) echo " + (mode.equals("wrong-version") ? "1.0.0.1" : KylinPatch.VERSION)
                    + ";; Architecture) echo " + (mode.equals("wrong-arch") ? "arm64" : "amd64") + ";; *) return 1;; esac; }\n"
                    + "dpkg() { test \"$1\" = --no-act && test \"$2\" = --install || return 2; printf '%s\\n' '" + mode + "' >> '" + markerPath + "'; return " + (mode.equals("dependency-failed") ? "1" : "0") + "; }\n";
            Path source = root.resolve("validate-" + mode + ".sh");
            Files.writeString(source, functions + KylinPatch.validateScript(uploadPath, checkedPath));
            var process = new ProcessBuilder(bash.toString(), shellPath(source)).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (mode.equals("valid")) assertEquals(0, exit, output); else assertNotEquals(0, exit, output);
        }
        assertEquals(List.of("valid", "dependency-failed"), Files.readAllLines(marker), "摘要或元数据不匹配不能进入dpkg预检");
    }
    private static String shellPath(Path path) {
        String normalized = path.toAbsolutePath().toString().replace('\\', '/');
        return normalized.matches("^[A-Za-z]:/.*") ? "/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2) : normalized;
    }
    @Test void stateAndInstallScriptsParseAndUnknownQueryCannotBecomeNotInstalled() throws Exception {
        Path bash = Path.of("D:/DevlopEnvironment/hermes-git/bin/bash.exe");
        Assumptions.assumeTrue(Files.isExecutable(bash));
        for (String script : List.of(KylinPatch.stateScript(), KylinPatch.validateScript("/tmp/toolhelper-fixture", "/var/lib/toolhelper/operations/fixture"), KylinPatch.installScript("/var/lib/toolhelper/operations/fixture"))) {
            Path source = root.resolve(UUID.randomUUID() + ".sh"); Files.writeString(source, script);
            var process = new ProcessBuilder(bash.toString(), "-n", source.toString().replace('\\', '/')).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(), output);
        }
        // 真正执行生成的只读脚本，只替换目标外部工具；不运行维护脚本或本机systemctl。
        for (String mode : List.of("older", "equal", "newer", "missing", "query-error", "compare-error")) {
            String compare = switch (mode) { case "equal" -> "eq"; case "newer" -> "gt"; case "compare-error" -> "none"; default -> "lt"; };
            String query = mode.equals("missing") ? "echo 'dpkg-query: no packages found matching kylin-offline-upgrade' >&2; return 1;"
                    : mode.equals("query-error") ? "echo 'database cannot be read' >&2; return 2;" : "printf 'install ok installed|1.0.0.33-0k1.9|amd64';";
            String functions = "awk() { case \"$*\" in *VERSION_ID*) echo v10;; *) echo kylin;; esac; }\n"
                    + "dpkg-query() { " + query + " }\n"
                    + "dpkg() { case \"$1\" in --print-architecture) echo amd64;; --audit) :;; --compare-versions) test \"$3\" = " + compare + ";; *) return 2;; esac; }\n"
                    + "systemctl() { case \"$1\" in show) echo loaded;; is-active) echo active;; is-enabled) echo enabled;; *) return 2;; esac; }\n";
            Path source = root.resolve(mode + ".sh"); Files.writeString(source, functions + KylinPatch.stateScript());
            var process = new ProcessBuilder(bash.toString(), source.toString().replace('\\', '/')).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (mode.endsWith("error")) { assertNotEquals(0, exit, output); continue; }
            assertEquals(0, exit, output); var state = KylinPatch.parseState(output);
            assertEquals(switch (mode) { case "missing" -> "NOT_INSTALLED"; case "equal" -> "EQUAL"; case "newer" -> "NEWER"; default -> "OLDER"; }, state.get("comparison"));
            if (mode.equals("missing")) assertEquals("KYLIN_PATCH_NOT_INSTALLED", assertThrows(ApiException.class, () -> KylinPatch.requireUpgrade(state)).code());
        }
    }
}
