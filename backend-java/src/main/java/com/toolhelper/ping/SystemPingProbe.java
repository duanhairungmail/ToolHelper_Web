package com.toolhelper.ping;

import com.toolhelper.config.PingProperties;
import com.toolhelper.support.ApiException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import static com.toolhelper.ping.PingModels.*;

@Component
public class SystemPingProbe implements PingProbe {
    private static final Pattern WINDOWS_SUMMARY = Pattern.compile("=\\s*(\\d+)[^\\r\\n=]*=\\s*(\\d+)[^\\r\\n=]*=\\s*(\\d+)[^\\r\\n]*[（(]\\s*\\d+%");
    private static final Pattern WINDOWS_RTT = Pattern.compile("=\\s*(\\d+)\\s*(?:ms|毫秒)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIX_SUMMARY = Pattern.compile("(\\d+) packets transmitted,\\s*(\\d+) (?:packets )?received[^\\r\\n]*?(\\d+(?:\\.\\d+)?)% packet loss");
    private static final Pattern UNIX_RTT = Pattern.compile("(?:rtt|round-trip) min/avg/max/(?:mdev|stddev)\\s*=\\s*[\\d.]+/([\\d.]+)/[\\d.]+/[\\d.]+\\s*ms");
    private final PingProperties properties;
    private final String platform;
    private final Path executable;
    public SystemPingProbe(PingProperties properties) {
        this.properties = properties;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        platform = os.contains("win") ? "WINDOWS" : os.contains("mac") ? "MACOS" : os.contains("linux") ? "LINUX" : "UNSUPPORTED";
        List<Path> paths = switch (platform) {
            case "WINDOWS" -> List.of(Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "PING.EXE"));
            case "LINUX" -> List.of(Path.of("/usr/bin/ping"), Path.of("/bin/ping"));
            case "MACOS" -> List.of(Path.of("/sbin/ping"));
            default -> List.of();
        };
        executable = paths.stream().filter(path -> Files.isRegularFile(path) && Files.isExecutable(path)).findFirst().orElse(null);
    }
    @Override public String platform() { return platform; }
    @Override public boolean available() { return executable != null; }
    @Override public Row probe(String ip, Options options, BooleanSupplier cancelled) throws InterruptedException {
        PingTargets.ipv4(ip);
        if (!available()) return error(ip, "PING_COMMAND_UNAVAILABLE", "本机缺少可用的系统 Ping 程序，或平台不受支持");
        ProcessBuilder builder = new ProcessBuilder(command(platform, executable.toString(), ip, options));
        if (!platform.equals("WINDOWS")) { builder.environment().put("LC_ALL", "C"); builder.environment().put("LANG", "C"); }
        try {
            var result = PingProcess.run(builder, Duration.ofMillis((long) options.count() * (options.timeoutMs() + 1000L) + 3000),
                    properties.maxOutputBytes(), cancelled);
            return parse(platform, ip, options.count(), result.exitCode(), decode(result.bytes(), platform));
        } catch (ApiException failure) {
            if (failure.code().equals("PING_CANCELLED")) return cancelled(ip);
            return error(ip, failure.code(), failure.getMessage());
        }
    }
    static List<String> command(String platform, String executable, String ip, Options options) {
        PingTargets.ipv4(ip);
        return switch (platform) {
            case "WINDOWS" -> List.of(executable, "-4", "-n", "" + options.count(), "-w", "" + options.timeoutMs(), ip);
            // Linux -W 单位为秒，允许小数；macOS -W 单位为毫秒，不能共用一组参数。
            case "LINUX" -> List.of(executable, "-n", "-4", "-c", "" + options.count(), "-W", String.format(Locale.ROOT, "%.3f", options.timeoutMs() / 1000.0), ip);
            case "MACOS" -> List.of(executable, "-n", "-c", "" + options.count(), "-W", "" + options.timeoutMs(), ip);
            default -> throw new ApiException(503, "UNSUPPORTED_PLATFORM", "当前平台没有 Ping 适配器");
        };
    }
    static Row parse(String platform, String ip, int expected, int exitCode, String output) {
        boolean windows = platform.equals("WINDOWS");
        if ((!Set.of("WINDOWS", "LINUX", "MACOS").contains(platform)) || exitCode < 0 || exitCode > (platform.equals("MACOS") ? 2 : 1))
            return error(ip, "PING_COMMAND_FAILED", "系统 Ping 执行失败，无法判断");
        Matcher stats = (windows ? WINDOWS_SUMMARY : UNIX_SUMMARY).matcher(output);
        if (!stats.find()) return error(ip, "PING_UNRECOGNIZED_OUTPUT", "系统 Ping 输出无法识别，无法判断");
        int sent, received;
        Double average = null; boolean belowOne = false;
        try {
            sent = Integer.parseInt(stats.group(1)); received = Integer.parseInt(stats.group(2));
            if (sent != expected || received > sent) throw new IllegalArgumentException();
            if (windows) {
                if (Pattern.compile("general failure|一般故障|常规故障", Pattern.CASE_INSENSITIVE).matcher(output).find())
                    return error(ip, "PING_COMMAND_FAILED", "本机 Ping 发送失败，无法判断");
                // Windows 把目标不可达 ICMP 也计为已接收；仅 TTL 回复行才是 Echo 回复。
                List<String> replies = output.lines().filter(line -> Pattern.compile("\\bTTL=\\d+", Pattern.CASE_INSENSITIVE).matcher(line).find()).toList();
                if (replies.size() > received) throw new IllegalArgumentException();
                received = replies.size();
                if (received > 0) {
                    List<Double> times = new ArrayList<>(); Matcher latency = WINDOWS_RTT.matcher(output);
                    while (latency.find()) times.add(Double.parseDouble(latency.group(1)));
                    if (times.size() != 3) throw new IllegalArgumentException();
                    average = times.get(2);
                    belowOne = replies.stream().allMatch(line -> Pattern.compile("<\\s*1\\s*(?:ms|毫秒)", Pattern.CASE_INSENSITIVE).matcher(line).find());
                    if (belowOne) average = 1.0; // 上界值而非虚构精确 RTT；UI/导出均显示 <1 ms。
                }
            } else if (received > 0) {
                Matcher latency = UNIX_RTT.matcher(output);
                if (!latency.find()) throw new IllegalArgumentException();
                average = Double.parseDouble(latency.group(1));
            }
            if (average != null && (!Double.isFinite(average) || average < 0)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { return error(ip, "PING_UNRECOGNIZED_OUTPUT", "Ping 统计或延迟不完整，无法判断"); }
        String status = received == sent ? "RESPONSIVE" : received == 0 ? "NO_REPLY" : "PARTIAL_LOSS";
        return new Row(ip, status, sent, received, 100.0 * (sent - received) / sent, average, belowOne,
                Instant.now(), null, received == 0 ? "本次没有 ICMP Echo 回复，不能据此判定主机离线" : "系统 Ping 报告的 ICMP Echo 回复统计");
    }
    private static String decode(byte[] bytes, String platform) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) {
            // 中文 Windows Ping 使用本机代码页；优先 native.encoding，避免 Java UTF-8 默认编码破坏输出。
            Charset charset = Charset.forName(System.getProperty("native.encoding", platform.equals("WINDOWS") ? "GB18030" : "UTF-8"));
            return new String(bytes, charset);
        }
    }
    public static Row error(String ip, String code, String message) { return new Row(ip, "ERROR", null, null, null, null, false, Instant.now(), code, message); }
    public static Row cancelled(String ip) { return new Row(ip, "CANCELLED", null, null, null, null, false, null, null, "已停止，未完成该目标全部探测"); }
}
