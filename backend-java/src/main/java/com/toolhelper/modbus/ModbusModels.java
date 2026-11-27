package com.toolhelper.modbus;

import com.toolhelper.serial.SerialModels.Port;
import java.time.Instant;
import java.util.List;

public final class ModbusModels {
    private ModbusModels() {}
    public record OpenRequest(String port, int baudRate, String protocol) {}
    public record OperationRequest(String type, Integer address, boolean confirmed) {}
    public record Session(String sessionId, String port, int baudRate, String protocol, Instant openedAt,
            String operationStatus, String operationType, String operationId, String operationMessage,
            boolean polling, int pollIntervalMs, Instant leaseUntil, long cursor,
            List<Device> devices, List<Slot> positions) {}
    public record Device(int address, String serialHex, String serialText, String serialMapping,
            String alarmName, Integer alarmLevel, List<String> faults, String faultHex,
            Integer ionValue, Integer workStatus, String workStatusText,
            Integer commStatus, String commStatusText, Integer deviceAddress,
            List<Threshold> thresholds, String readStatus, String readMessage, Instant updatedAt,
            String dataSource) {}
    public record Threshold(String name, int value) {}
    public record Slot(int index, int address, Integer communicationState, boolean duplicate) {}
    public record Event(long cursor, String direction, String hex, int byteCount, Instant at,
            String operationType, int attempt, String verification, String note) {}
    public record Events(String sessionId, long cursor, boolean gap, List<Event> events, Session session) {}
    public record Operation(String operationId, String status, String type, String message,
            Instant startedAt, Instant completedAt, Integer address, int transmissions) {}
    public record SerialPorts(List<Port> ports, String occupiedBy) {}
}
