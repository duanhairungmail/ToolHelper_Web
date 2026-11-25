package com.toolhelper.kylin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class KylinModels {
    private KylinModels() {}
    public record Host(String host, int port) {}
    public record HostKey(String host, int port, String algorithm, String fingerprint, boolean trusted) {}
    public record TrustRequest(String host, int port, String fingerprint, boolean confirmed) {}
    public record ConnectRequest(String host, int port, String username, String password) {
        @Override public String toString() { return "ConnectRequest[redacted]"; }
    }
    public record Connection(String connectionId, String host, int port, String username, String fingerprint,
            boolean connected, Map<String, String> system, Instant expiresAt) {}
    public record Feature(String id, String name, String description, List<String> actions) {}
    public record Finding(String id, String status, String detail) {}
    public record ScanRequest(String connectionId, String feature, String sudoPassword) {
        @Override public String toString() { return "ScanRequest[redacted]"; }
    }
    public record Scan(String feature, String revision, List<Finding> findings, Instant checkedAt) {}
    public record Params(String desktopUser, String schedule, Integer journalDays, Integer fileDays, String listenAddress,
            Integer port, String allowedCidr, List<String> optimizationIds, String backupId) {}
    public record PlanRequest(String connectionId, String feature, String action, Params params, String sudoPassword) {
        @Override public String toString() { return "PlanRequest[redacted]"; }
    }
    public record Change(String path, String beforeHash, String afterHash, String content, String mode) {}
    public record Plan(String planId, String connectionId, String target, String feature, String action,
            String revision, List<Change> changes, List<String> serviceActions, List<String> impacts,
            String backupId, Instant expiresAt) {}
    public record ExecuteRequest(String planId, boolean confirmed, String sudoPassword, String vncPassword) {
        @Override public String toString() { return "ExecuteRequest[redacted]"; }
    }
    public record Task(String taskId, String connectionId, String feature, String action, String status,
            String step, String code, String message, String backupId, Instant createdAt, Instant finishedAt) {}
    public record Backup(String backupId, String host, int port, String fingerprint, String feature,
            List<String> paths, List<String> units, Map<String, String> state, Instant createdAt) {}
}
