package com.toolhelper.mqttmodbus;

import java.util.List;

public final class MqttModbusModels {
    private MqttModbusModels() {}
    public record Gateway(String key, String name, String gatewayId, String brokerUrl,
                          String clientId, Integer qos, String downTopic, String upTopic) {}
    public record Device(String id, String gatewayKey, String name, String modelId, Integer address,
                         List<Integer> inputRegisters, List<Integer> holdingRegisters, List<Boolean> coils) {}
    public record OpenRequest(Gateway gateway, List<Device> devices, String username, String password) {
        @Override public String toString() { return "OpenRequest[credentials redacted]"; }
    }
    public record ProfileRequest(Long expectedVersion, List<Device> devices) {}
    public record Session(String sessionId, Gateway gateway, String state, long version, List<Device> devices,
                          long received, long published, long dropped, long leaseExpiresAt, String error) {}
    public record Event(long cursor, long time, String gatewayKey, String kind, String topic, String deviceId,
                        int length, Integer address, Integer function, String crcStatus,
                        String hex, String result, long version) {}
    public record Events(List<Event> events, long cursor, boolean gap) {}
}
