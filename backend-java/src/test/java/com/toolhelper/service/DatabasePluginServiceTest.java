package com.toolhelper.service;

import com.toolhelper.config.*;
import com.toolhelper.plugin.common.*;
import com.toolhelper.plugin.dbx.*;
import com.toolhelper.plugin.electerm.*;
import com.toolhelper.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class DatabasePluginServiceTest {
    @TempDir Path root;
    private final JsonMapper mapper = new JsonMapper();
    private ToolHelperProperties properties;
    private final DbxProperties dbx = new DbxProperties(List.of("0.6.72"));
    private LocalDataRoot data;
    private TaskCoordinator tasks;
    private TestStore store;
    private TestProcesses processes;
    private TestReleases releases;
    private OfficialDownloadClient downloads;
    private DatabasePluginService service;
    private Asset asset;
    private Path archive;
    private final List<String> files = List.of("DBX.exe", "portable.dbx", "portable-update.json", "LICENSE", "README.md");

    @BeforeEach void setup() throws Exception {
        properties = ToolHelperProperties.forTests(root);
        data = new LocalDataRoot(properties);
        store = new TestStore();
        tasks = new TaskCoordinator(data, new JsonFiles(mapper), properties);
        downloads = new OfficialDownloadClient(properties);
        releases = new TestReleases(); processes = new TestProcesses();
        service = new DatabasePluginService(store, releases, new ArchiveExtractor(properties), processes, tasks, properties, dbx, mapper);
        archive = root.resolve("fixture.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String file : files) {
                zip.putNextEntry(new ZipEntry(file));
                byte[] bytes = file.equals("portable-update.json") ? mapper.writeValueAsBytes(Map.of("schema_version", 1, "arch", "x64", "version", "0.6.72-beta.20261119.2", "executable", "DBX.exe", "executable_sha256", SecurePaths.hash("fixture"))) : file.equals("portable.dbx") ? new byte[0] : "fixture".getBytes();
                zip.write(bytes); zip.closeEntry();
            }
        }
        asset = new Asset(1, "v0.6.72", 2, "DBX_0.6.72_x64-portable.zip", Files.size(archive), SecurePaths.sha256(archive), "https://github.com/t8y2/dbx/releases/download/v0.6.72/DBX_0.6.72_x64-portable.zip");
    }
    @AfterEach void close() throws Exception { tasks.close(); downloads.close(); data.close(); }
    private TaskRecord await(Map<String, Object> response) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (tasks.active() != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertNull(tasks.active());
        return tasks.get((String) response.get("taskId"));
    }
    private InstallManifest install() throws Exception {
        var task = await(service.mutate("INSTALL", new MutationRequest("selection", null, true), "install-request-key"));
        assertEquals("SUCCEEDED", task.status(), task.message()); assertEquals("dbx", task.pluginId());
        return store.requireInstalled();
    }
    private InstallManifest oldFixture() throws Exception {
        var current = install();
        // 仅模拟较旧的受管修订，以验证事务；不把夹具视为真实旧版 DBX 的升级验收。
        var old = new InstallManifest(current.pluginId(), current.releaseId(), "v0.6.71", current.assetId(), current.assetName(), current.sha256(), current.executable(), current.userData(), current.layout(), current.revision(), current.installedAt(), current.packageFiles(), current.packageHashes());
        new JsonFiles(mapper).write(store.current().resolve("install.json"), old);
        Path userData = store.current().resolve("app/data/.dbx"); Files.createDirectories(userData);
        Files.writeString(userData.resolve("secret.key"), "key-fixture"); Files.writeString(userData.getParent().resolve("dbx.db"), "db-fixture");
        return old;
    }
    @Test void fiveManagementActionsUseRealStoreAndPreserveCompleteUserData() throws Exception {
        var old = oldFixture();
        var update = await(service.mutate("UPDATE", new MutationRequest("selection", old.revision(), true), "update-request-key"));
        assertEquals("SUCCEEDED", update.status(), update.message());
        assertEquals("key-fixture", Files.readString(store.current().resolve("app/data/.dbx/secret.key")));
        assertTrue(Files.isRegularFile(Path.of((String) update.result().get("backupDirectory")).resolve("app/data/dbx.db")));
        var current = store.requireInstalled();
        assertEquals("LAUNCH_REQUESTED", service.localAction("LAUNCH", null, "launch-request-key").get("status"));
        assertEquals("OPEN_REQUESTED", service.localAction("OPEN_DIRECTORY", null, "directory-request-key").get("status"));
        var uninstall = await(service.mutate("UNINSTALL", new MutationRequest(null, current.revision(), true), "uninstall-request-key"));
        assertEquals("SUCCEEDED", uninstall.status()); assertEquals("NOT_INSTALLED", store.inspect().state());
        Path preserved = Path.of((String) uninstall.result().get("preservedDirectory"));
        assertEquals("key-fixture", Files.readString(preserved.resolve(".dbx/secret.key")));
        assertEquals("db-fixture", Files.readString(preserved.resolve("dbx.db")));
    }
    @Test void failedDownloadAndFailedSwitchRetainOldRevisionAndData() throws Exception {
        var old = oldFixture(); releases.fail = true;
        var failed = await(service.mutate("UPDATE", new MutationRequest("selection", old.revision(), true), "failed-download-key"));
        assertEquals("FAILED", failed.status()); assertEquals(old.revision(), store.requireInstalled().revision());
        releases.fail = false; store.failSwitch = true;
        failed = await(service.mutate("UPDATE", new MutationRequest("selection", old.revision(), true), "failed-switch-key"));
        assertEquals("FAILED", failed.status()); assertEquals(old.revision(), store.requireInstalled().revision());
        assertEquals("key-fixture", Files.readString(store.current().resolve("app/data/.dbx/secret.key")));
    }
    @Test void connectionIsEphemeralIdempotentAndNeverPersistsCredentials() throws Exception {
        install();
        var body = new DbxModels.ConnectionRequest("MYSQL", "localhost", 3306, "user", "db", "test", true, "sensitive-密码-marker");
        assertEquals("CONNECTION_REQUESTED", service.connect(body, "sensitive-connect-key").get("status"));
        service.connect(body, "sensitive-connect-key"); assertEquals(1, processes.connections);
        try (var records = Files.list(data.tasksRoot())) {
            for (Path path : records.toList()) {
                String text = Files.readString(path);
                assertFalse(text.contains("sensitive-密码-marker")); assertFalse(text.contains("dbx://"));
                assertFalse(text.contains(SecurePaths.hash(mapper.writeValueAsString(body))));
            }
        }
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class, () -> service.connect(new DbxModels.ConnectionRequest("MYSQL", "localhost", 3306, "user", "db", "test", true, "changed"), "sensitive-connect-key")).code());
    }
    private class TestStore extends DbxInstallationStore {
        boolean failSwitch;
        TestStore() throws IOException { super(DatabasePluginServiceTest.this.properties, new JsonFiles(mapper), data); }
        @Override public void move(Path from, Path to) throws IOException {
            super.move(from, to);
            if (failSwitch && from.getFileName().toString().equals("candidate") && to.equals(current())) throw new IOException("fixture switch failure");
        }
    }
    private class TestReleases extends DbxReleaseClient {
        boolean fail;
        TestReleases() { super(properties, dbx, mapper, downloads); }
        @Override public Asset selected(String id) { return asset; }
        @Override public void download(Asset asset, Path target, BiConsumer<Long, Long> progress) throws IOException {
            if (fail) throw new IOException("fixture download failure");
            Files.copy(archive, target); progress.accept(asset.size(), asset.size());
        }
    }
    private static class TestProcesses extends DbxProcessAdapter {
        int connections;
        @Override public void requireDesktop() {}
        @Override public void requireStopped(Path path) {}
        @Override public ProcessObservation observe(Path path) { return new ProcessObservation("NOT_OBSERVED", List.of(), false); }
        @Override public Map<String, Object> launch(Path exe, Path data) { return Map.of("status", "LAUNCH_REQUESTED"); }
        @Override public Map<String, Object> openDirectory(Path path) { return Map.of("status", "OPEN_REQUESTED"); }
        @Override public Map<String, Object> connect(Path exe, Path data, DbxModels.ConnectionRequest body) {
            connections++; return Map.of("status", "CONNECTION_REQUESTED");
        }
    }
}
