package com.toolhelper.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("toolhelper")
public record ToolHelperProperties(
        String dataRoot, List<String> allowedOrigins, List<String> supportedVersions,
        long maxDownloadBytes, long maxExtractedBytes, long maxEntryBytes,
        int maxArchiveEntries, int maxPathDepth, long maxUserDataBytes,
        int taskRetentionDays, int maxTaskRecords) {

    public ToolHelperProperties {
        if (maxDownloadBytes <= 0 || maxExtractedBytes <= 0 || maxEntryBytes <= 0
                || maxArchiveEntries <= 0 || maxPathDepth <= 0 || maxUserDataBytes <= 0
                || taskRetentionDays <= 0 || maxTaskRecords <= 0) {
            throw new IllegalArgumentException("文件配额和任务保留参数必须大于零");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
        supportedVersions = List.copyOf(supportedVersions);
    }

    public Path root() {
        if (dataRoot != null && !dataRoot.isBlank()) {
            return Path.of(dataRoot).toAbsolutePath().normalize();
        }
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local == null ? System.getProperty("user.home") : local,
                "ToolHelper_Web").toAbsolutePath().normalize();
    }

    public static ToolHelperProperties forTests(Path root) {
        return new ToolHelperProperties(root.toString(), List.of("http://127.0.0.1:5173"),
                List.of("5.12.6"), 4_000_000, 8_000_000, 4_000_000,
                1000, 12, 4_000_000, 7, 1000);
    }
}
