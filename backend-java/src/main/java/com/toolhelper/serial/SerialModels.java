package com.toolhelper.serial;

import java.time.Instant;
import java.util.List;

public final class SerialModels {
    private SerialModels() {}

    public record Port(String name, String description) {}
    public record OpenRequest(String port, int baudRate, int dataBits, String stopBits,
            String parity, String flowControl) {}
    public record Session(String sessionId, String port, int baudRate, int dataBits,
            String stopBits, String parity, String flowControl, Instant openedAt,
            long txBytes, long rxBytes, long transmissions, Instant lastTxAt, Instant lastRxAt,
            boolean autoSending, Integer autoSendIntervalMs) {}
    public record WriteRequest(String mode, String data, boolean appendCrlf, String checksum) {}
    public record AutoSendRequest(WriteRequest write, int intervalMs) {}
    public record Event(long cursor, String sessionId, String direction, String hex,
            int byteCount, Instant at, String status) {}
    public record Events(String sessionId, long cursor, boolean gap, List<Event> events,
            Session session) {}
}
