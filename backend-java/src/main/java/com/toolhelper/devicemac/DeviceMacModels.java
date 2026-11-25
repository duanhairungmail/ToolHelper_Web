package com.toolhelper.devicemac;

import java.time.Instant;
import java.util.List;

/** 台账、观察值与任务结果分离，错误文案不写入 MAC。 */
public final class DeviceMacModels {
    private DeviceMacModels() {}
    public record Observation(String ipAddress, String macAddress, String status, String source,
            String neighborState, int interfaceIndex, Instant checkedAt) {}
    public record Record(String recordId, String stationName, String ipAddress, String deviceName,
            String deviceModel, String serialNumber, String location, Observation lastObservation) {}
    public record Ledger(int schemaVersion, String revision, Instant savedAt, List<Record> records) {}
    public record SaveRequest(String expectedRevision, List<Record> records) {}
    public record Item(String recordId, String ipAddress) {}
    public record QueryRequest(String requestKey, List<Item> items) {}
    public record Outcome(String status, String message, Observation observation) {}
    public record RowResult(String recordId, String ipAddress, String status, String message, Observation observation) {}
    public record Task(String taskId, String status, int total, int done, List<RowResult> results,
            String errorCode, String message, Instant createdAt) {}
    public record ExportRequest(String format, List<String> columns, List<Record> records) {}
}
