package com.toolhelper.mqttmodbus;

import java.util.List;

public final class MqttModbusModels {
    private MqttModbusModels() {}
    public record Gateway(String key, String name, String gatewayId, String brokerUrl,
                          String clientId, Integer qos, String downTopic, String upTopic) {}
    public record Device(String id, String gatewayKey, String name, String modelId, Integer address,
                         List<Integer> inputRegisters, List<Integer> holdingRegisters, List<Boolean> coils) {}
    public record Meter(String id, String gatewayKey, String type, Integer address, String encodingProfile,
                        Integer baudRateRaw,
                        @tools.jackson.databind.annotation.JsonDeserialize(contentUsing=DecimalStringDeserializer.class)
                        java.util.Map<String,String> measurements) {}
    public record OpenRequest(Gateway gateway, List<Device> devices, String username, String password,
                              String kind, List<Meter> meters) {
        public OpenRequest(Gateway gateway,List<Device> devices,String username,String password) {
            this(gateway,devices,username,password,null,null);
        }
        @Override public String toString() { return "OpenRequest[credentials redacted]"; }
    }
    public record ProfileRequest(Long expectedVersion, List<Device> devices, List<Meter> meters) {
        public ProfileRequest(Long expectedVersion,List<Device> devices) { this(expectedVersion,devices,null); }
    }
    public record Session(String sessionId, Gateway gateway, String state, long version, List<Device> devices,
                          long received, long published, long dropped, long leaseExpiresAt, String error,
                          String kind, List<Meter> meters, long filtered) {}
    public record Event(long cursor, long time, String gatewayKey, String kind, String topic, String deviceId,
                        int length, Integer address, Integer function, String crcStatus,
                        String hex, String result, long version) {}
    public record Events(List<Event> events, long cursor, boolean gap) {}
}
