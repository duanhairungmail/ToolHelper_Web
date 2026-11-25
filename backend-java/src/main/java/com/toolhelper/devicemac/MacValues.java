package com.toolhelper.devicemac;

import com.toolhelper.support.ApiException;
import java.util.Locale;

public final class MacValues {
    private MacValues() {}
    public static String ipv4(String value) {
        String ip = value == null ? "" : value.trim();
        if (!ip.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) throw invalid();
        String[] parts = ip.split("\\.");
        for (String part : parts) if (Integer.parseInt(part) > 255) throw invalid();
        int first = Integer.parseInt(parts[0]);
        if (first == 0 || first == 127 || first >= 224 || ip.equals("255.255.255.255")) throw invalid();
        return ip;
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_IPV4", "请输入完整单播 IPv4 地址，不支持环回、广播、网段或主机名"); }
    public static String mac(String value) {
        String mac = value == null ? "" : value.replace('-', ':').toUpperCase(Locale.ROOT);
        if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}") || mac.equals("00:00:00:00:00:00")
                || (Integer.parseInt(mac.substring(0, 2), 16) & 1) != 0)
            throw new ApiException(400, "INVALID_MAC", "未取得有效的六字节单播 MAC 地址");
        return mac;
    }
    public static long number(String ip) {
        long result = 0; for (String part : ip.split("\\.")) result = (result << 8) | Integer.parseInt(part); return result;
    }
    public static boolean broadcast(String ip, String prefix) {
        String[] parts = prefix.split("/"); int length = Integer.parseInt(parts[1]);
        if (length >= 31) return false;
        long mask = (0xffffffffL << (32 - length)) & 0xffffffffL;
        return number(ip) == ((number(parts[0]) & mask) | (~mask & 0xffffffffL));
    }
}
