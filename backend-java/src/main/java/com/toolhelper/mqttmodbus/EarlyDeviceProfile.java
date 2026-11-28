package com.toolhelper.mqttmodbus;

import com.toolhelper.support.ApiException;
import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import java.net.URI;
import java.util.*;

/** 服务端独立验证并复制集合，运行中只读取完整不可变快照。 */
public final class EarlyDeviceProfile {
    private EarlyDeviceProfile() {}
    static void require(boolean condition, String message) {
        if (!condition) throw new ApiException(400, "MQTT_MODBUS_INVALID", message);
    }
    static String text(String value, String field, int max) {
        require(value != null && !value.isBlank() && value.length() <= max && value.chars().noneMatch(Character::isISOControl), field + " 无效");
        return value;
    }
    public static Gateway gateway(Gateway g) {
        require(g != null, "缺少网关");
        text(g.key(), "gateway key", 128); text(g.name(), "网关名称", 128);
        require(g.gatewayId() != null && g.gatewayId().matches("[A-Za-z0-9_.-]{1,128}"), "网关 ID 仅允许字母、数字、点、下划线和短横线");
        String broker;
        try {
            URI uri = URI.create(g.brokerUrl());
            require(Set.of("tcp", "mqtt").contains(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getPort() != 0 && uri.getPort() <= 65535, "仅支持 mqtt:// 或 tcp://主机:端口，认证请使用独立字段");
            broker = new URI("tcp", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort() < 0 ? 1883 : uri.getPort(), null, null, null).toString();
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(400, "MQTT_MODBUS_INVALID", "Broker 地址无效"); }
        require(g.qos() != null && g.qos() >= 0 && g.qos() <= 2, "QoS 必须为 0、1 或 2");
        String client = g.clientId() == null || g.clientId().isBlank() ? "th-" + UUID.randomUUID().toString().substring(0, 20) : text(g.clientId(), "Client ID", 128);
        String down = topic(g.downTopic(), "firetopic/" + g.gatewayId() + "/down");
        String up = topic(g.upTopic(), "firetopic/" + g.gatewayId() + "/up");
        require(!down.equals(up), "上下行主题不能相同");
        return new Gateway(g.key(), g.name(), g.gatewayId(), broker, client, g.qos(), down, up);
    }
    private static String topic(String input, String fallback) {
        String value = input == null || input.isBlank() ? fallback : input;
        text(value, "主题", 512);
        require(!value.contains("+") && !value.contains("#"), "首版主题必须精确匹配，不能包含通配符");
        return value;
    }
    public static List<Device> devices(String key, List<Device> input) {
        require(input != null && input.size() <= 247, "每网关设备数不能超过 247");
        Set<String> ids = new HashSet<>(); Set<Integer> addresses = new HashSet<>();
        List<Device> result = new ArrayList<>();
        for (Device d : input) {
            require(d != null, "设备不能为空");
            text(d.id(), "设备 ID", 128); text(d.name(), "设备名称", 128); text(d.modelId(), "设备标识", 128);
            require(key.equals(d.gatewayKey()), "设备必须绑定当前网关");
            require(d.address() != null && d.address() >= 1 && d.address() <= 247 && addresses.add(d.address()), "同网关从站地址需唯一且在 1–247 范围内");
            require(ids.add(d.id()), "设备 ID 重复");
            registers(d.inputRegisters(), 63); registers(d.holdingRegisters(), 39);
            require(d.coils() != null && d.coils().size() == 10 && d.coils().stream().noneMatch(Objects::isNull), "需要 10 个布尔线圈");
            var r = d.inputRegisters();
            range(r,1,0,1000); range(r,3,0,5); range(r,4,0,1);
            for (int a=5;a<=9;a++) range(r,a,1,255);
            for (int a=10;a<=14;a++) range(r,a,0,1);
            for (int a=15;a<=17;a++) range(r,a,0,9999);
            range(r,18,1,8); range(r,19,0,0); range(r,20,19,22); range(r,21,0,99); range(r,22,1,12);
            for (int a=23;a<=63;a++) range(r,a,0,127);
            r = d.holdingRegisters();
            range(r,1,20,20); range(r,2,0,99); range(r,3,1,12); range(r,4,1,31); range(r,5,0,23); range(r,6,0,59); range(r,7,0,59);
            for (int a=8;a<=27;a++) range(r,a,a%2==0?1:0,a%2==0?255:60);
            range(r,28,0,23); range(r,29,0,59); range(r,30,0,23); range(r,31,0,59); range(r,32,1,255);
            require(Set.of(0,7,8,9,10).contains(r.get(32)), "通讯波特率枚举无效");
            range(r,34,1,3); range(r,35,0,1); range(r,36,0,1); range(r,37,20,100); range(r,38,0,6); range(r,39,1,4);
            result.add(new Device(d.id(), key, d.name(), d.modelId(), d.address(), List.copyOf(d.inputRegisters()), List.copyOf(r), List.copyOf(d.coils())));
        }
        return List.copyOf(result);
    }
    private static void registers(List<Integer> r, int size) {
        require(r != null && r.size() == size && r.stream().allMatch(v -> v != null && v >= 0 && v <= 65535), "寄存器数量或 16 位整型值无效");
    }
    private static void range(List<Integer> r, int address, int min, int max) {
        require(r.get(address-1) >= min && r.get(address-1) <= max, "寄存器 0x" + Integer.toHexString(address) + " 超出范围 " + min + "–" + max);
    }
}
