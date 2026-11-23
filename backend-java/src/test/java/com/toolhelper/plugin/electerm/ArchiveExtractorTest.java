package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveExtractorTest {
    @TempDir Path root;
    private Path zip(String... names) throws IOException {
        Path path = root.resolve(UUID.randomUUID() + ".zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
            }
        }
        return path;
    }
    private Path target() { return root.resolve(UUID.randomUUID().toString()); }
    @Test void extractsRegularFilesAndInventory() throws Exception {
        Path target = target();
        List<String> files = new ArchiveExtractor(ToolHelperProperties.forTests(root)).extract(zip("dir/electerm.exe", "dir/resources/a"), "package.zip", target);
        assertEquals(List.of("dir/electerm.exe", "dir/resources/a"), files);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.resolve("dir/electerm.exe")));
    }
    @Test void rejectsWindowsTraversalAndUnsafeNames() {
        for (String name : List.of("../escape", "/absolute", "C:/temp/x", "\\\\server\\x", "a:stream", "dir/CON.txt", "dir/trailing.", "a/../b", "a\\b")) {
            assertThrows(ApiException.class, () -> SecurePaths.archivePath(root, name, 12), name);
        }
    }
    @Test void rejectsDuplicateCaseAndParentCaseConflicts() throws Exception {
        ArchiveExtractor extractor = new ArchiveExtractor(ToolHelperProperties.forTests(root));
        assertThrows(ApiException.class, () -> extractor.extract(zip("dir/A", "dir/a"), "x.zip", target()));
        assertThrows(ApiException.class, () -> extractor.extract(zip("DIR/a", "dir/b"), "x.zip", target()));
    }
    @Test void rejectsTarLinksAndEscape() throws Exception {
        for (boolean link : List.of(true, false)) {
            Path path = root.resolve(UUID.randomUUID() + ".tar.gz");
            try (var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(path)))) {
                TarArchiveEntry entry = link ? new TarArchiveEntry("link", TarConstants.LF_SYMLINK) : new TarArchiveEntry("../escape");
                if (link) entry.setLinkName("outside");
                tar.putArchiveEntry(entry); tar.closeArchiveEntry();
            }
            assertThrows(ApiException.class, () -> new ArchiveExtractor(ToolHelperProperties.forTests(root)).extract(path, "x.tar.gz", target()));
        }
    }
    @Test void enforcesActualUncompressedQuota() throws Exception {
        var normal = ToolHelperProperties.forTests(root);
        var limited = new ToolHelperProperties(root.toString(), normal.allowedOrigins(), normal.supportedVersions(),
                10000, 4, 4, 10, 4, 1000, 7, 100);
        assertThrows(ApiException.class, () -> new ArchiveExtractor(limited).extract(zip("one", "two"), "x.zip", target()));
    }
    @Test void refusesDeletingManagedRootOrOutsidePath() {
        assertThrows(ApiException.class, () -> SecurePaths.deleteTree(root, root));
        assertThrows(ApiException.class, () -> SecurePaths.deleteTree(root, root.resolve("../other")));
    }
}
