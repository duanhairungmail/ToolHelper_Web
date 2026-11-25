package com.toolhelper.druid;

import java.time.Instant;
import java.util.List;

public final class DruidModels {
    private DruidModels() {}
    public record ScanRequest(String requestKey, List<String> targets) {}
    /** 凭据只用于接收，不写入任务快照；避免record默认toString暴露秘密。 */
    public record CredentialRequest(String requestKey, String target, String username, String password, Boolean allowInsecureHttp) {
        @Override public String toString() { return "CredentialRequest[redacted]"; }
    }
    public record ExportRequest(String taskId, List<String> targetIds, List<String> columns, String format, Boolean details) {}
    public record Retest(String status, String conclusion) {}
    public record Evidence(String ruleId, String path, String method, int httpStatus, String status, String severity,
            boolean identified, boolean jsonAccessible, boolean empty, int sampleCount, List<String> fields,
            String message, long elapsedMs, Instant checkedAt, Retest retest) {}
    public record TargetResult(String targetId, String target, String executionStatus, String identification,
            String accessStatus, String severity, int completedRules, int totalRules, int requests, long elapsedMs,
            Instant checkedAt, String credentialStatus, String message, List<Evidence> evidence, Retest retest) {}
    public record Summary(String targetId, String target, String executionStatus, String identification,
            String accessStatus, String severity, int completedRules, int totalRules, long elapsedMs, String credentialStatus, Retest retest) {
        public static Summary of(TargetResult r) { return new Summary(r.targetId(), r.target(), r.executionStatus(),
                r.identification(), r.accessStatus(), r.severity(), r.completedRules(), r.totalRules(), r.elapsedMs(), r.credentialStatus(), r.retest()); }
    }
    public record Task(String taskId, String kind, String status, int total, int done, String ruleVersion,
            Instant createdAt, Instant finishedAt, String errorCode) {}
    public record Page(int page, int size, int total, List<Summary> items) {}
}
