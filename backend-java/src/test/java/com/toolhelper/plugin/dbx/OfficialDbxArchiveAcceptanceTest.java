package com.toolhelper.plugin.dbx;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.common.LocalDataRoot;
import com.toolhelper.plugin.electerm.ArchiveExtractor;
import com.toolhelper.support.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

/** 指定本地实包才执行；只校验/解压，不启动 GUI，也不代表真实数据库连接验收。 */
@EnabledIfSystemProperty(named = "toolhelper.dbx.archive", matches = ".+")
class OfficialDbxArchiveAcceptanceTest {
    @TempDir Path root;
    @Test void verifiesOfficialArchiveLayoutMarkerMetadataAndExecutableHash() throws Exception {
        Path archive = Path.of(System.getProperty("toolhelper.dbx.archive"));
        String sha = "e1fa98463fa89b647f9358ed944d6e1924cee9b2c754c54926de443f722b50a9";
        assertEquals(45_910_741L, Files.size(archive)); assertEquals(sha, SecurePaths.sha256(archive));
        var props = new ToolHelperProperties(root.toString(), List.of(), List.of("5.12.6"), 512L << 20, 2L << 30, 1L << 30, 30000, 24, 2L << 30, 7, 1000);
        try (var data = new LocalDataRoot(props)) {
            var store = new DbxInstallationStore(props, new JsonFiles(new JsonMapper()), data);
            var files = new ArchiveExtractor(props).extract(archive, "DBX_0.6.72_x64-portable.zip", store.current().resolve("app"));
            var manifest = store.makeCandidate(store.current(), new Asset(1, "v0.6.72", 2, "DBX_0.6.72_x64-portable.zip", Files.size(archive), sha, "https://github.com/t8y2/dbx/releases/download/v0.6.72/DBX_0.6.72_x64-portable.zip"), files);
            store.verifyOwnedFiles(manifest);
            assertEquals("f658f6fa855f72bacf0920083fd1f740f24e6eb5900227de9fc2cd519ade8465", SecurePaths.sha256(store.executable(manifest)));
            assertEquals(0, Files.size(store.current().resolve("app/portable.dbx")));
            assertFalse(Files.exists(store.current().resolve("app/data")));
        }
    }
}
