package com.toolhelper.modbus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Modbus RTU组帧、流式提取和两家站端点表解析。 */
public final class ModbusCodec {
    private static final String[] FAULTS = {"气流堵塞", "气流漏气", "风扇故障", "显示屏通信中断", "系统故障1", "系统故障2", "系统故障3", "系统故障4", "系统故障5", "系统故障6", "系统故障7", "云室通信中断", "系统故障9", "系统故障10", "温湿度传感器故障", "电压故障"};
    private static final String[] ALARMS = {"正常", "示警", "预警", "巡警", "火警1", "火警2"};
    private static final String[] THRESHOLDS = {"白天火警2阈值", "白天火警1阈值", "白天巡警阈值", "白天预警阈值", "白天示警阈值", "晚上火警2阈值", "晚上火警1阈值", "晚上巡警阈值", "晚上预警阈值", "晚上示警阈值"};

    private ModbusCodec() {}

    public static byte[] request(int address, int function, int start, int count) {
        return frame(new byte[]{(byte) address, (byte) function, (byte) (start >>> 8), (byte) start, (byte) (count >>> 8), (byte) count});
    }
    public static byte[] resetRequest(int address) { return frame(new byte[]{(byte) address, 0x05, 0, 7, (byte) 0xFF, 0}); }
    public static byte[] frame(byte[] body) {
        byte[] result = Arrays.copyOf(body, body.length + 2); byte[] crc = crc(body);
        result[result.length - 2] = crc[0]; result[result.length - 1] = crc[1]; return result;
    }
    public static byte[] crc(byte[] bytes) {
        int value = 0xFFFF;
        for (byte b : bytes) { value ^= b & 0xFF; for (int i = 0; i < 8; i++) value = (value & 1) != 0 ? (value >>> 1) ^ 0xA001 : value >>> 1; }
        return new byte[]{(byte) value, (byte) (value >>> 8)};
    }
    public static boolean validCrc(byte[] frame) {
        if (frame == null || frame.length < 5) return false;
        byte[] expected = crc(Arrays.copyOf(frame, frame.length - 2));
        return expected[0] == frame[frame.length - 2] && expected[1] == frame[frame.length - 1];
    }
    public static String hex(byte[] bytes) { return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes); }
    public static byte[] unhex(String hex) { return HexFormat.of().parseHex(hex.replace(" ", "")); }

    /** 从任意分片中提取完整候选帧；损坏或未知前缀逐字节丢弃，缓冲始终有界。 */
    public static List<byte[]> extract(ByteArrayOutputStream buffer, byte[] incoming) {
        byte[] old = buffer.toByteArray();
        int total = Math.min(4096, old.length + incoming.length);
        byte[] joined = new byte[total];
        int keepIncoming = Math.min(incoming.length, total);
        int keepOld = total - keepIncoming;
        if (keepOld > 0) System.arraycopy(old, old.length - keepOld, joined, 0, keepOld);
        if (keepIncoming > 0) System.arraycopy(incoming, incoming.length - keepIncoming, joined, keepOld, keepIncoming);
        buffer.reset(); int offset = 0; List<byte[]> frames = new ArrayList<>();
        while (joined.length - offset >= 5) {
            int function = joined[offset + 1] & 0xFF; int length;
            if ((function & 0x80) != 0) length = 5;
            else if (function == 3 || function == 4) { int byteCount = joined[offset + 2] & 0xFF; if (byteCount > 250) { offset++; continue; } length = byteCount + 5; }
            else if (function == 5) length = 8;
            else { offset++; continue; }
            if (joined.length - offset < length) break;
            byte[] frame = Arrays.copyOfRange(joined, offset, offset + length);
            if (validCrc(frame)) { frames.add(frame); offset += length; } else offset++;
        }
        if (offset < joined.length) buffer.write(joined, offset, joined.length - offset);
        if (buffer.size() > 260) { byte[] tail = buffer.toByteArray(); buffer.reset(); buffer.write(tail, tail.length - 260, 260); }
        return frames;
    }

    public static int[] parseDevicePositions(byte[] response) {
        requireRead(response, 0, 3, 16);
        if (response[2] != 16 || response.length != 21) throw new IllegalArgumentException("站端清单应返回16字节地址数据");
        int[] result = new int[16]; for (int i = 0; i < 16; i++) result[i] = response[3 + i] & 0xFF; return result;
    }
    public static int[] parseCommunicationPositions(byte[] response) {
        requireRead(response, 0, 3, 16);
        if (response[2] != 16 || response.length != 21) throw new IllegalArgumentException("站端通讯状态应返回16字节数据");
        int[] result = new int[16]; for (int i = 0; i < 16; i++) result[i] = response[3 + i] & 0xFF; return result;
    }

    public static Map<String, Object> parseShenHong(byte[] response, int address) {
        requireRead(response, address, 3, 44); byte[] d = Arrays.copyOfRange(response, 3, response.length - 2);
        Map<String, Object> out = common(); out.put("alarmLevel", u16(d, 0)); out.put("faultWord", u16(d, 2));
        out.put("workStatus", u16(d, 4)); out.put("commStatus", u16(d, 6)); out.put("ionValue", u16(d, 8));
        int[] thresholds = new int[10]; for (int i = 0; i < 10; i++) thresholds[i] = u16(d, 10 + i * 2);
        out.put("thresholds", thresholdItems(thresholds)); out.put("deviceAddress", u16(d, 30));
        byte[] id = Arrays.copyOfRange(d, 33, 44); out.put("serialHex", hex(id)); out.put("serialText", printableAscii(id));
        out.put("serialMapping", "0x20高字节丢弃，后续11字节原值"); return out;
    }

    /** 采用用户确认的旧项目映射：0x11-0x1B，每个寄存器取高字节。 */
    public static Map<String, Object> parseNanRuiRealtime(byte[] response, int address) {
        requireRead(response, address, 4, 56); byte[] d = Arrays.copyOfRange(response, 3, response.length - 2);
        Map<String, Object> out = common(); out.put("ionValue", u16(d, 0)); out.put("faultWord", u16(d, 2));
        out.put("alarmLevel", u16(d, 4)); out.put("workStatus", u16(d, 6));
        byte[] id = new byte[11]; for (int i = 0; i < id.length; i++) id[i] = d[(0x11 - 1 + i) * 2];
        out.put("serialHex", hex(id)); out.put("serialText", printableAscii(id)); out.put("serialMapping", "旧项目映射 0x11-0x1B，每寄存器取高字节"); return out;
    }

    public static Map<String, Object> parseNanRuiHolding(byte[] response, int address) {
        requireRead(response, address, 3, 50); byte[] d = Arrays.copyOfRange(response, 3, response.length - 2);
        int[] thresholds = new int[10]; int[] addresses = {8,10,12,14,16,18,20,22,24,26};
        for (int i = 0; i < addresses.length; i++) thresholds[i] = u16(d, (addresses[i] - 8) * 2);
        Map<String, Object> out = new LinkedHashMap<>(); out.put("thresholds", thresholdItems(thresholds)); out.put("deviceAddress", u16(d, (0x20 - 8) * 2)); return out;
    }

    public static void verifyEcho(byte[] response, byte[] request) {
        if (!validCrc(response) || response.length != 8 || request.length != 8
                || !Arrays.equals(response, request)) throw new IllegalArgumentException("复位回显与本次命令不匹配");
    }
    public static void verifyFrame(byte[] response, int address, int function, int dataLength) {
        requireRead(response, address, function, dataLength);
    }
    public static String alarmName(Object raw) {
        if (!(raw instanceof Number number)) return "未读取";
        int value = number.intValue(); return value >= 0 && value < ALARMS.length ? ALARMS[value] : "未知（" + value + "）";
    }
    public static List<String> faults(Object raw) {
        if (!(raw instanceof Number number)) return List.of(); int word = number.intValue(); List<String> result = new ArrayList<>();
        for (int i = 0; i < 16; i++) if ((word & (1 << i)) != 0) result.add(FAULTS[i]); return result;
    }
    private static Map<String, Object> common() { Map<String, Object> out = new LinkedHashMap<>(); out.put("thresholds", List.of()); return out; }
    private static List<Map<String, Object>> thresholdItems(int[] values) {
        List<Map<String, Object>> result = new ArrayList<>(); for (int i = 0; i < values.length; i++) result.add(Map.of("name", THRESHOLDS[i], "value", values[i])); return result;
    }
    private static int u16(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
    private static String printableAscii(byte[] bytes) {
        for (byte b : bytes) if ((b & 0xFF) < 0x20 || (b & 0xFF) > 0x7E) return "";
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static void requireRead(byte[] response, int address, int function, int dataLength) {
        if (!validCrc(response) || response.length < 5 || (response[0] & 0xFF) != address || (response[1] & 0xFF) != function
                || (response[2] & 0xFF) != dataLength || response.length != dataLength + 5)
            throw new IllegalArgumentException("响应地址、功能码、字节数或CRC与请求不匹配");
    }
}
