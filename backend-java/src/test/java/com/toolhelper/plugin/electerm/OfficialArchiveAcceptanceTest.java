package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

/** 显式提供官方包时才执行；只验证包、解压和清单，不运行 EXE 或连接远程设备。 */
@EnabledIfSystemProperty(named = "toolhelper.acceptance.archive", matches = ".+")
class OfficialArchiveAcceptanceTest {
    @TempDir Path root;
    @Test void acceptsPinnedOfficialWindowsPortableArchive() throws Exception {
        Path archive = Path.of(System.getProperty("toolhelper.acceptance.archive"));
        String expectedHash = "352aa63e90ea78847416f01f7c0b7a4bf57da97336810f45fab4df54f5e78cf8";
        assertEquals(expectedHash, SecurePaths.sha256(archive));
        var properties = new ToolHelperProperties(root.toString(), List.of(), List.of("5.12.6"),
                536870912L, 2147483648L, 1073741824L, 30000, 24, 2147483648L, 7, 1000);
        try (var store = new InstallationStore(properties, new JsonFiles(new JsonMapper()))) {
            Path candidate = store.stage(UUID.randomUUID().toString()).resolve("candidate");
            String name = "electerm-5.12.6-win-x64-portable.tar.gz";
            List<String> files = new ArchiveExtractor(properties).extract(archive, name, candidate.resolve("app"));
            InstallManifest manifest = store.makeCandidate(candidate, new Asset(0, "v5.12.6", 777644636,
                    name, Files.size(archive), expectedHash, "https://github.com/electerm/electerm/releases/download/v5.12.6/" + name), files);
            assertEquals("app/electerm-5.12.6-win-x64-portable/electerm.exe", manifest.executable());
            assertEquals("app/electerm-5.12.6-win-x64-portable/electerm", manifest.userData());
            try (var input = Files.newInputStream(store.relative(candidate, manifest.executable()))) {
                assertArrayEquals(new byte[]{'M', 'Z'}, input.readNBytes(2));
            }
            assertTrue(files.size() > 50);
        }
    }
}
