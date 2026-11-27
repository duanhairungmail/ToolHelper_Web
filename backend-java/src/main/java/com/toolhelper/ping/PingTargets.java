package com.toolhelper.ping;

import com.toolhelper.config.PingProperties;
import com.toolhelper.support.ApiException;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static com.toolhelper.ping.PingModels.*;

/** 纯解析，不做 DNS 或网络探测；先计算 CIDR 容量，再分配目标集合。 */
public final class PingTargets {
    private PingTargets() {}
    public static ParsedTargets parse(ParseRequest request, PingProperties properties) {
        if (request == null || (request.text() == null) == (request.fileBase64() == null))
            throw invalid("只允许提供文本或一个 UTF-8 TXT 文件");
        String text = request.text();
        if (text == null) {
            if (request.fileBase64().length() > ((properties.maxFileBytes() + 2L) / 3) * 4) throw invalid("导入文件超过大小上限");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(request.fileBase64()); }
            catch (IllegalArgumentException error) { throw invalid("文件编码不正确"); }
            if (bytes.length > properties.maxFileBytes()) throw invalid("导入文件超过大小上限");
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException error) { throw invalid("TXT 文件必须使用有效 UTF-8 编码"); }
        }
        if (text.length() > properties.maxFileBytes() || text.getBytes(StandardCharsets.UTF_8).length > properties.maxFileBytes())
            throw invalid("目标文本超过大小上限");
        text = text.replaceFirst("^\uFEFF", "");
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try { expand(line, targets, properties.maxTargets()); }
            catch (ApiException error) { throw new ApiException(400, error.code(), "第 " + (i + 1) + " 行：" + error.getMessage()); }
        }
        if (targets.isEmpty()) throw invalid("没有可用目标，请每行填写一个 IPv4、CIDR 或末段范围");
        return new ParsedTargets(List.copyOf(targets), targets.size());
    }
    private static void expand(String line, LinkedHashSet<String> targets, int limit) {
        if (line.contains("/")) {
            String[] parts = line.split("/", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}")) throw invalid("CIDR 格式不正确");
            long value = ipv4(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (prefix > 32) throw invalid("CIDR 前缀必须在 0–32 之间");
            long size = 1L << (32 - prefix);
            // 按本阶段排除网络和广播地址的规则，/31、/32 不产生主机地址。
            if (size <= 2) throw invalid("此 CIDR 没有可展开的主机地址，请使用单个 IPv4 地址");
            if (size - 2 > limit) throw quota(limit);
            long network = value & (0xffffffffL << (32 - prefix));
            for (long ip = network + 1; ip < network + size - 1; ip++) add(targets, ip, limit);
        } else if (line.contains("-")) {
            String[] parts = line.split("-", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) throw invalid("只支持如 192.168.1.1-254 的末段范围");
            long first = ipv4(parts[0]); int start = (int) (first & 255), end = Integer.parseInt(parts[1]);
            if (start < 1 || end > 254 || end < start) throw invalid("末段范围须递增且位于 1–254");
            for (long ip = first; ip <= first + end - start; ip++) add(targets, ip, limit);
        } else add(targets, ipv4(line), limit);
    }
    private static void add(Set<String> targets, long value, int limit) {
        targets.add(format(value));
        if (targets.size() > limit) throw quota(limit);
    }
    public static long ipv4(String value) {
        if (value == null || value.length() > 15) throw invalid("需要 IPv4 字面地址，不支持主机名、IPv6 或命令参数");
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) throw invalid("IPv4 地址格式不正确");
        long result = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}")) throw invalid("IPv4 地址各段须为十进制数字且不带前导零");
            int number = Integer.parseInt(octet);
            if (number > 255) throw invalid("IPv4 地址各段须位于 0–255");
            result = result * 256 + number;
        }
        return result;
    }
    public static String format(long value) { return (value >> 24 & 255) + "." + (value >> 16 & 255) + "." + (value >> 8 & 255) + "." + (value & 255); }
    private static ApiException quota(int limit) { return new ApiException(400, "PING_TARGET_LIMIT", "目标超过 " + limit + " 个，已整体拒绝，没有截断或执行探测"); }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_PING_TARGETS", message); }
}
