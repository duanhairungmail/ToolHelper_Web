package com.toolhelper.plugin.electerm;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PluginModels {
    private PluginModels() {}

    public record ConnectionRequest(String protocol, String host, Integer port, String username) {}
    public record MutationRequest(String releaseSelectionId, String expectedInstallRevision, Boolean confirmed) {}
    public record Asset(long releaseId, String tag, long assetId, String name,
                        long size, String sha256, String downloadUrl) {}
    public record ReleaseSelection(String id, Asset asset, Instant expiresAt) {}
    public record InstallManifest(String pluginId, long releaseId, String tag, long assetId,
                                  String assetName, String sha256, String executable,
                                  String userData, String layout, String revision,
                                  Instant installedAt, List<String> packageFiles) {}
    public record Inspection(String state, InstallManifest manifest, String message) {}
    public record ProcessObservation(String state, List<Long> pids, boolean foreignInstance) {}
    public record Journal(String operation, String taskId, String oldRevision,
                          String newRevision, String phase, boolean userDataPresent) {}
    public record ErrorInfo(int status, String code, String message, boolean retryable) {}
    public record TaskRecord(String taskId, String operation, String keyHash, String fingerprint,
                             String status, String phase, long downloadedBytes, Long totalBytes,
                             String message, Instant createdAt, Instant updatedAt,
                             Map<String, Object> result, ErrorInfo error) {
        public TaskRecord withProgress(String phase, long downloaded, Long total, String message) {
            return new TaskRecord(taskId, operation, keyHash, fingerprint, "RUNNING", phase,
                    downloaded, total, message, createdAt, Instant.now(), result, null);
        }
        public TaskRecord finish(String status, String message, Map<String, Object> result, ErrorInfo error) {
            return new TaskRecord(taskId, operation, keyHash, fingerprint, status, phase,
                    downloadedBytes, totalBytes, message, createdAt, Instant.now(), result, error);
        }
    }
}
