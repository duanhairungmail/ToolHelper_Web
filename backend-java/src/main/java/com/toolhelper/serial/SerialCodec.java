package com.toolhelper.serial;

import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** 校验发送输入并生成唯一、可预览的实际TX字节序列。 */
public final class SerialCodec {
    public static final int MAX_TX_BYTES = 4096;
    private SerialCodec() {}

    public static byte[] build(SerialModels.WriteRequest request) {
        if (request == null || request.mode() == null || request.data() == null
                || request.data().length() > MAX_TX_BYTES * 4) {
            throw invalid("发送内容为空或超出大小限制");
        }
        String mode = request.mode().toUpperCase(Locale.ROOT);
        byte[] payload;
        if (mode.equals("HEX")) {
            if (request.appendCrlf()) throw invalid("HEX模式不支持自动追加CRLF，请显式输入0D 0A");
            payload = parseHex(request.data());
        } else if (mode.equals("TEXT")) {
            payload = request.data().getBytes(StandardCharsets.UTF_8);
            if (request.appendCrlf()) {
                payload = Arrays.copyOf(payload, payload.length + 2);
                payload[payload.length - 2] = 0x0D;
                payload[payload.length - 1] = 0x0A;
            }
        } else {
            throw invalid("发送模式必须为TEXT或HEX");
        }
        if (payload.length == 0) throw invalid("发送内容不能为空");
        if (payload.length > MAX_TX_BYTES) throw new ApiException(413, "SERIAL_TX_TOO_LARGE", "单次发送不能超过4096字节");
        String checksum = request.checksum() == null ? "NONE" : request.checksum().toUpperCase(Locale.ROOT);
        byte[] suffix = checksum(payload, checksum);
        if (payload.length + suffix.length > MAX_TX_BYTES) throw new ApiException(413, "SERIAL_TX_TOO_LARGE", "含校验字节的发送内容超出大小限制");
        byte[] result = Arrays.copyOf(payload, payload.length + suffix.length);
        System.arraycopy(suffix, 0, result, payload.length, suffix.length);
        return result;
    }

    public static byte[] parseHex(String input) {
        String compact = input.replaceAll("[\\s-]", "");
        if (compact.isEmpty() || !compact.matches("(?i)[0-9a-f]+")) throw invalid("HEX仅允许十六进制字符、空白和短横线");
        if ((compact.length() & 1) != 0) throw invalid("HEX位数为奇数，请补齐完整字节后再发送");
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    public static byte[] checksum(byte[] input, String algorithm) {
        return switch (algorithm) {
            case "NONE" -> new byte[0];
            case "MODBUS_CRC16" -> littleEndian(crc16(input, 0xFFFF, 0xA001, true));
            case "CRC16_CCITT" -> bigEndian(crc16(input, 0xFFFF, 0x1021, false));
            case "CRC32" -> littleEndian32(crc32(input));
            case "LRC" -> new byte[]{(byte) lrc(input)};
            case "XOR" -> new byte[]{(byte) xor(input)};
            default -> throw invalid("不支持的校验算法");
        };
    }

    public static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            if (!output.isEmpty()) output.append(' ');
            output.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
        }
        return output.toString();
    }

    private static int crc16(byte[] bytes, int initial, int polynomial, boolean reflected) {
        int crc = initial;
        for (byte value : bytes) {
            crc ^= reflected ? value & 0xFF : (value & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++)
                crc = reflected ? ((crc & 1) != 0 ? (crc >>> 1) ^ polynomial : crc >>> 1)
                        : ((crc & 0x8000) != 0 ? (crc << 1) ^ polynomial : crc << 1);
        }
        return crc & 0xFFFF;
    }
    private static long crc32(byte[] bytes) {
        long crc = 0xFFFFFFFFL;
        for (byte value : bytes) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xEDB88320L : crc >>> 1;
        }
        return (crc ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }
    private static int lrc(byte[] bytes) { int sum = 0; for (byte value : bytes) sum = (sum + (value & 0xFF)) & 0xFF; return (-sum) & 0xFF; }
    private static int xor(byte[] bytes) { int value = 0; for (byte item : bytes) value ^= item & 0xFF; return value; }
    private static byte[] littleEndian(int value) { return new byte[]{(byte) value, (byte) (value >>> 8)}; }
    private static byte[] littleEndian32(long value) { return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)}; }
    private static byte[] bigEndian(int value) { return new byte[]{(byte) (value >>> 8), (byte) value}; }
    private static byte[] bigEndian(long value) { return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}; }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_SERIAL_DATA", message); }
}
