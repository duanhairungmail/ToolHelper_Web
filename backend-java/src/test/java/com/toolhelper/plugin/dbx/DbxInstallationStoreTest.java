package com.toolhelper.plugin.dbx;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.common.LocalDataRoot;
import com.toolhelper.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class DbxInstallationStoreTest {
    @TempDir Path root;
    private final JsonMapper mapper = new JsonMapper();
    private static final List<String> FILES = List.of("DBX.exe", "portable.dbx", "portable-update.json", "LICENSE", "README.md");
    private final Asset asset = new Asset(1, "v0.6.72", 2, "DBX_0.6.72_x64-portable.zip", 100, "a".repeat(64), "https://github.com/t8y2/dbx/releases/download/v0.6.72/DBX_0.6.72_x64-portable.zip");

    private void fixture(Path candidate, String version) throws Exception {
        Path app = candidate.resolve("app"); Files.createDirectories(app);
        for (String file : FILES) Files.writeString(app.resolve(file), file.equals("portable.dbx") ? "" : "fixture");
        new JsonFiles(mapper).write(app.resolve("portable-update.json"), Map.of("schema_version", 1, "version", version, "arch", "x64", "executable", "DBX.exe", "executable_sha256", SecurePaths.sha256(app.resolve("DBX.exe"))));
    }
    @Test void requiresExactLayoutAndPackageVersionAndDetectsExternalUpdate() throws Exception {
        var props = ToolHelperProperties.forTests(root);
        try (var data = new LocalDataRoot(props)) {
            var store = new DbxInstallationStore(props, new JsonFiles(mapper), data);
            fixture(store.current(), "0.6.72");
            assertEquals("PLUGIN_LAYOUT_UNSUPPORTED", assertThrows(ApiException.class, () -> store.makeCandidate(store.current(), asset, FILES)).code());
            fixture(store.current(), "0.6.72-beta.20261119.2");
            var manifest = store.makeCandidate(store.current(), asset, FILES);
            assertEquals("app/data", manifest.userData()); assertEquals("dbx-portable-v1", manifest.layout());
            store.verifyOwnedFiles(manifest);
            Files.writeString(store.current().resolve("app/DBX.exe"), "externally updated");
            assertEquals("PLUGIN_FILES_CHANGED", assertThrows(ApiException.class, () -> store.executable(manifest)).code());
            assertThrows(ApiException.class, () -> store.makeCandidate(store.current(), asset, List.of("nested/DBX.exe")));
        }
    }
    @Test void preservesCompleteDataIncludingKeyAndRejectsUnknownApplicationFiles() throws Exception {
        var props = ToolHelperProperties.forTests(root);
        try (var data = new LocalDataRoot(props)) {
            var store = new DbxInstallationStore(props, new JsonFiles(mapper), data);
            fixture(store.current(), "0.6.72-beta.20261119.2");
            var manifest = store.makeCandidate(store.current(), asset, FILES);
            Path userData = store.current().resolve("app/data"); Files.createDirectories(userData.resolve(".dbx"));
            Files.writeString(userData.resolve("dbx.db"), "database"); Files.writeString(userData.resolve(".dbx/secret.key"), "key-fixture");
            store.verifyOwnedFiles(manifest);
            Path backup = store.preserved(UUID.randomUUID().toString()); assertTrue(store.copyData(manifest, backup));
            assertEquals("database", Files.readString(backup.resolve("dbx.db"))); assertEquals("key-fixture", Files.readString(backup.resolve(".dbx/secret.key")));
            Files.writeString(store.current().resolve("app/my-own-file.txt"), "keep");
            assertEquals("USER_DATA_LAYOUT_UNKNOWN", assertThrows(ApiException.class, () -> store.verifyOwnedFiles(manifest)).code());
        }
    }
}
