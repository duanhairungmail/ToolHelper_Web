package com.toolhelper.service;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.electerm.*;
import com.toolhelper.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class RemotePluginServiceTest {
    @TempDir Path root;
    private final JsonMapper json = new JsonMapper();
    private ToolHelperProperties properties;
    private TestStore store;
    private TestRelease releases;
    private TestProcesses processes;
    private TaskCoordinator tasks;
    private RemotePluginService service;
    private Asset asset;
    @BeforeEach void setUp() throws Exception {
        properties = ToolHelperProperties.forTests(root);
        Path archive = root.resolve("fixture.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("package/electerm.exe")); zip.write("fixture-exe".getBytes()); zip.closeEntry();
        }
        asset = new Asset(1, "v5.12.6", 2, "fixture.zip", Files.size(archive), SecurePaths.sha256(archive), "https://github.com/fixture");
        store = new TestStore(); releases = new TestRelease(archive); processes = new TestProcesses();
        tasks = new TaskCoordinator(store, new JsonFiles(json), properties);
        service = new RemotePluginService(store, releases, new ArchiveExtractor(properties), processes, tasks, properties, json);
    }
    @AfterEach void tearDown() throws Exception { tasks.close(); releases.close(); store.close(); }
    private InstallManifest installOld() throws Exception {
        Files.createDirectories(store.current().resolve("app/package"));
        Files.writeString(store.current().resolve("app/package/electerm.exe"), "old-exe");
        return store.makeCandidate(store.current(), new Asset(1, "v5.12.5", 2, "old.zip", 1, "a".repeat(64), "https://github.com/old"), List.of("package/electerm.exe"));
    }
    private TaskRecord await(Map<String, Object> response) throws Exception {
        String id = (String) response.get("taskId");
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (tasks.active() != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNull(tasks.active(), "后台任务应在期限内结束");
        return tasks.get(id);
    }
    private MutationRequest request(InstallManifest old) { return new MutationRequest("selection", old == null ? null : old.revision(), true); }
    @Test void installsOnceAndReplaysSameIdempotencyKey() throws Exception {
        var body = request(null);
        Map<String, Object> response = service.mutate("INSTALL", body, "install-request-key");
        assertEquals("SUCCEEDED", await(response).status());
        assertEquals(response.get("taskId"), service.mutate("INSTALL", body, "install-request-key").get("taskId"));
        assertEquals(1, releases.downloads);
        assertEquals("INSTALLED", service.status().get("state"));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> service.localAction("LAUNCH", null, "install-request-key")).code());
    }
    @Test void failedDownloadLeavesOldInstallationUsable() throws Exception {
        InstallManifest old = installOld(); releases.fail = true;
        TaskRecord result = await(service.mutate("UPDATE", request(old), "failed-download-key"));
        assertEquals("FAILED", result.status());
        assertEquals(old.revision(), store.requireInstalled().revision());
        assertEquals("old-exe", Files.readString(store.executable(old)));
    }
    @Test void failureAfterSwitchRestoresOldAndStatusWorksDuringSwitch() throws Exception {
        InstallManifest old = installOld(); store.failAfterNewMove = true;
        AtomicBoolean statusReadable = new AtomicBoolean();
        store.afterOldMove = () -> {
            try { statusReadable.set(service.status().get("state").equals("INSTALLED")); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
        TaskRecord result = await(service.mutate("UPDATE", request(old), "failed-switch-key"));
        assertEquals("FAILED", result.status());
        assertTrue(statusReadable.get());
        assertEquals(old.revision(), store.requireInstalled().revision());
        assertFalse(Files.exists(store.journalPath()));
    }
    @Test void updateAndUninstallPreserveDataInIsolatedFixture() throws Exception {
        InstallManifest old = installOld();
        Path data = store.relative(store.current(), old.userData()); Files.createDirectories(data);
        Files.writeString(data.resolve("db"), "user-data");
        TaskRecord update = await(service.mutate("UPDATE", request(old), "successful-update-key"));
        assertEquals("SUCCEEDED", update.status());
        InstallManifest installed = store.requireInstalled();
        assertEquals("user-data", Files.readString(store.relative(store.current(), installed.userData()).resolve("db")));
        assertTrue(Files.exists(Path.of((String) update.result().get("backupDirectory"))));
        TaskRecord uninstall = await(service.mutate("UNINSTALL", request(installed), "fixture-uninstall-key"));
        assertEquals("SUCCEEDED", uninstall.status());
        assertEquals("NOT_INSTALLED", store.inspect().state());
        assertEquals("user-data", Files.readString(Path.of((String) uninstall.result().get("preservedDirectory")).resolve("db")));
    }
    @Test void blocksBusyAndRejectsStaleRevision() throws Exception {
        InstallManifest old = installOld();
        assertEquals("INSTALL_REVISION_CONFLICT", assertThrows(ApiException.class, () -> service.mutate("UPDATE",
                new MutationRequest("selection", "stale", true), "stale-revision-key")).code());
        TaskRecord claimed = tasks.claim("UPDATE", "claimed-task-key", "fingerprint");
        assertEquals("PLUGIN_BUSY", assertThrows(ApiException.class, () -> service.localAction("LAUNCH", null, "launch-while-busy-key")).code());
        tasks.execute(claimed.taskId(), () -> Map.of());
    }
    @Test void keepsBusinessStatusAndAllowsDiagnosticDirectoryDuringRecovery() throws Exception {
        installOld(); processes.failLaunch = true;
        ApiException launch = assertThrows(ApiException.class, () -> service.localAction("LAUNCH", null, "launch-error-key"));
        assertEquals(409, launch.status());
        assertEquals("PLUGIN_INSTANCE_CONFLICT", launch.code());
        store.recoveryRequired("fixture recovery");
        assertEquals("OPEN_REQUESTED", service.localAction("OPEN_DIRECTORY", null, "diagnostic-open-key").get("status"));
        assertEquals(store.pluginRoot(), processes.opened);
        assertEquals("RECOVERY_REQUIRED", assertThrows(ApiException.class, () -> service.localAction("LAUNCH", null, "recovery-launch-key")).code());
    }
    private class TestStore extends InstallationStore {
        boolean failAfterNewMove;
        Runnable afterOldMove;
        TestStore() throws IOException { super(properties, new JsonFiles(json)); }
        @Override public void move(Path from, Path to) throws IOException {
            super.move(from, to);
            if (from.equals(current()) && to.startsWith(backupRoot()) && afterOldMove != null) afterOldMove.run();
            if (failAfterNewMove && from.getFileName().toString().equals("candidate") && to.equals(current())) throw new IOException("模拟切换后失败");
        }
    }
    private class TestRelease extends ReleaseClient {
        final Path archive; boolean fail; int downloads;
        TestRelease(Path archive) { super(properties, json); this.archive = archive; }
        @Override public Asset selected(String id) { return asset; }
        @Override public void download(Asset asset, Path target, BiConsumer<Long, Long> progress) throws IOException {
            downloads++;
            if (fail) throw new IOException("模拟下载中断");
            Files.copy(archive, target); progress.accept(asset.size(), asset.size());
        }
    }
    private class TestProcesses extends ProcessAdapter {
        boolean failLaunch; Path opened;
        TestProcesses() { super(json); }
        @Override public void requireDesktop() {}
        @Override public void requireStopped(Path path) {}
        @Override public ProcessObservation observe(Path path) { return new ProcessObservation("NOT_OBSERVED", List.of(), false); }
        @Override public Map<String, Object> launch(Path exe, Path data) {
            if (failLaunch) throw new ApiException(409, "PLUGIN_INSTANCE_CONFLICT", "模拟其他安装运行中");
            return Map.of("status", "LAUNCH_REQUESTED");
        }
        @Override public Map<String, Object> openDirectory(Path path) { opened = path; return Map.of("status", "OPEN_REQUESTED"); }
    }
}
