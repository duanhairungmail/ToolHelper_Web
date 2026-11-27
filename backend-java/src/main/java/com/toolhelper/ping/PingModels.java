package com.toolhelper.ping;

import java.time.Instant;
import java.util.List;

public final class PingModels {
    private PingModels() {}
    public record ParseRequest(String text, String fileBase64) {}
    public record ParsedTargets(List<String> targets, int total) {}
    public record TaskRequest(String requestKey, List<String> targets, Integer concurrency, Integer count, Integer timeoutMs) {}
    public record Options(int concurrency, int count, int timeoutMs) {}
    public record Row(String ip, String status, Integer sent, Integer received, Double lossPercent,
            Double averageRttMs, boolean rttBelowOneMs, Instant completedAt, String errorCode, String message) {}
    public record Summary(int done, int running, int responsive, int partialLoss, int noReply, int errors,
            int cancelled, Double averageRttMs) {}
    public record Task(String taskId, String requestKey, String status, int total, Options options,
            Summary summary, List<Row> results, Instant createdAt, Instant finishedAt, String message) {}
    public record ExportRequest(String taskId, String format) {}
}
