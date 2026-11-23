package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

@Component
public class ArchiveExtractor {
    private final ToolHelperProperties properties;
    public ArchiveExtractor(ToolHelperProperties properties) { this.properties = properties; }

    public List<String> extract(Path archive, String assetName, Path destination) throws IOException {
        SecurePaths.createDirectories(destination);
        Extraction state = new Extraction(destination);
        if (assetName.endsWith(".tar.gz")) {
            try (var gzip = new GzipCompressorInputStream(Files.newInputStream(archive));
                 var tar = new TarArchiveInputStream(gzip)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if ((!entry.isFile() && !entry.isDirectory()) || entry.isSymbolicLink() || entry.isLink()
                            || entry.isSparse() || !tar.canReadEntryData(entry)) throw SecurePaths.unsafe("不支持压缩包中的链接/特殊条目");
                    state.entry(entry.getName(), entry.isDirectory(), entry.getSize(), tar);
                }
            }
        } else if (assetName.endsWith(".zip")) {
            try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    int fileType = entry.getUnixMode() & 0170000;
                    if (entry.isUnixSymlink() || (fileType != 0 && fileType != 0100000 && fileType != 0040000)
                            || !zip.canReadEntryData(entry)) throw SecurePaths.unsafe("不支持 ZIP 链接或加密/特殊压缩条目");
                    try (InputStream input = zip.getInputStream(entry)) {
                        state.entry(entry.getName(), entry.isDirectory(), entry.getSize(), input);
                    }
                }
            }
        } else throw new ApiException(422, "ARCHIVE_UNSUPPORTED", "不支持该资产的压缩格式");
        return List.copyOf(state.files);
    }

    private final class Extraction {
        private final Path root;
        private final Set<String> seen = new HashSet<>();
        private final Map<String, String> spellings = new HashMap<>();
        private final List<String> files = new ArrayList<>();
        private long total;
        private int entries;
        private Extraction(Path root) { this.root = root; }

        void entry(String name, boolean directory, long size, InputStream input) throws IOException {
            if (++entries > properties.maxArchiveEntries()) throw SecurePaths.unsafe("压缩包文件数超限");
            Path target = SecurePaths.archivePath(root, name, properties.maxPathDepth());
            String relative = root.relativize(target).toString().replace('\\', '/');
            String prefix = "";
            for (String segment : relative.split("/")) {
                prefix = prefix.isEmpty() ? segment : prefix + "/" + segment;
                String previous = spellings.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
                if (previous != null && !previous.equals(prefix)) throw SecurePaths.unsafe("压缩包父目录存在大小写冲突");
            }
            if (!seen.add(relative.toLowerCase(Locale.ROOT))) throw SecurePaths.unsafe("压缩包包含重复或大小写冲突路径");
            if (directory) { SecurePaths.createDirectories(target); return; }
            if (size < 0 || size > properties.maxEntryBytes()) throw SecurePaths.unsafe("压缩包单文件大小超限");
            SecurePaths.createDirectories(target.getParent());
            long written = 0;
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("解压已中断");
                    written += count;
                    total += count;
                    if (written > properties.maxEntryBytes() || total > properties.maxExtractedBytes()) throw SecurePaths.unsafe("解压总量超限");
                    output.write(buffer, 0, count);
                }
            }
            if (written != size) throw new IOException("压缩包条目长度不一致");
            files.add(relative);
        }
    }
}
