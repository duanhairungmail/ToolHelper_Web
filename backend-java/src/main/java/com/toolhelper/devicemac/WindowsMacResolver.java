package com.toolhelper.devicemac;

import com.toolhelper.config.DeviceMacProperties;
import com.toolhelper.support.ApiException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.devicemac.DeviceMacModels.*;

@Component
public class WindowsMacResolver implements MacResolver {
    private final DeviceMacProperties properties;
    private final JsonMapper json;
    private final String script;
    private final Path executable;
    public WindowsMacResolver(DeviceMacProperties properties, JsonMapper json) throws IOException {
        this.properties = properties; this.json = json;
        String windows = System.getenv("SystemRoot");
        executable = Path.of(windows == null ? "C:\\Windows" : windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        try (InputStream input = getClass().getResourceAsStream("/device-mac-query.ps1")) {
            if (input == null) throw new IOException("缺少 MAC 查询脚本");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Override public Map<String, Object> capabilities() {
        boolean supported = System.getProperty("os.name").startsWith("Windows") && Files.isRegularFile(executable);
        return Map.of("supported", supported, "platform", "Windows IPv4", "maxRows", properties.maxRows(),
                "concurrency", properties.concurrency(), "timeoutSeconds", properties.timeoutSeconds(),
                "message", supported ? "查询本机观察到的 IP/MAC 映射；系统命令及权限在执行时检查" : "仅支持具备 Windows PowerShell 的 Windows 本机");
    }
    @Override public Outcome resolve(String target, BooleanSupplier cancelled) throws InterruptedException {
        String ip = MacValues.ipv4(target);
        if (!Boolean.TRUE.equals(capabilities().get("supported"))) return failure("UNSUPPORTED_PLATFORM");
        if (cancelled.getAsBoolean()) return failure("QUERY_CANCELLED");
        try {
            List<NetworkInterface> local = new ArrayList<>();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isUp() && Collections.list(nic.getInetAddresses()).stream().anyMatch(a -> a.getHostAddress().equals(ip))) local.add(nic);
            }
            if (!local.isEmpty()) {
                if (local.size() != 1) return failure("UNSUPPORTED_INTERFACE");
                byte[] mac = local.getFirst().getHardwareAddress();
                if (mac == null || mac.length != 6) return failure("UNSUPPORTED_INTERFACE");
                return new Outcome("MAPPING_FOUND", "本机接口地址", new Observation(ip, MacValues.mac(HexFormat.ofDelimiter(":").formatHex(mac)),
                        "MAPPING_FOUND", "LOCAL_INTERFACE", "Local", local.getFirst().getIndex(), Instant.now()));
            }
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
            builder.environment().put("TOOLHELPER_MAC_TARGET", ip);
            return parse(ip, json.readTree(BoundedProcess.run(builder, Duration.ofSeconds(properties.timeoutSeconds()), cancelled)));
        } catch (ApiException error) { return failure(error.code()); }
        catch (SocketException error) { return failure("COMMAND_FAILED"); }
        catch (RuntimeException error) { return failure("COMMAND_FAILED"); }
    }
    public static Outcome parse(String ip, JsonNode data) {
        if (data == null || !data.isObject()) return failure("COMMAND_FAILED");
        String code = data.path("code").asText();
        if (!code.equals("OK")) return failure(code);
        if (!ip.equals(data.path("ipAddress").asText()) || data.path("interfaceIndex").asInt() <= 0) return failure("COMMAND_FAILED");
        try {
            if (MacValues.broadcast(ip, data.path("prefix").asText())) return failure("INVALID_IPV4");
            String state = data.path("neighborState").asText();
            if (!Set.of("Reachable", "Stale", "Delay", "Probe", "Permanent").contains(state)) return failure("NO_NEIGHBOR");
            String status = state.equals("Reachable") ? "MAPPING_FOUND" : "CACHED_MAPPING";
            return new Outcome(status, status.equals("MAPPING_FOUND") ? "已读取邻居映射，不代表设备身份验证" : "缓存/静态映射，不能证明设备本次在线",
                    new Observation(ip, MacValues.mac(data.path("macAddress").asText()), status, "WINDOWS_NEIGHBOR", state, data.path("interfaceIndex").asInt(), Instant.now()));
        } catch (RuntimeException error) { return failure("NO_NEIGHBOR"); }
    }
    public static Outcome failure(String code) {
        String message = switch (code) {
            case "INVALID_IPV4" -> "目标为无效 IPv4 或子网广播地址";
            case "NO_ROUTE" -> "无可用路由或唯一出站接口";
            case "NOT_ON_LINK" -> "目标经网关转发，无法用本机 ARP 获取远端 MAC";
            case "UNSUPPORTED_INTERFACE" -> "接口不支持本阶段 MAC 查询或存在多个匹配";
            case "NETWORK_CHANGED" -> "查询期间网络发生变化，请重试";
            case "NO_NEIGHBOR" -> "未取得有效邻居映射，不能据此判定设备离线";
            case "QUERY_TIMEOUT" -> "查询超时，子进程已清理";
            case "QUERY_CANCELLED" -> "查询已取消";
            case "COMMAND_UNAVAILABLE" -> "Windows 网络查询工具不可用";
            case "ACCESS_DENIED" -> "读取网络信息的权限不足";
            case "UNSUPPORTED_PLATFORM" -> "仅支持 Windows 本机 IPv4";
            default -> "Windows 网络查询失败，请检查系统工具和权限";
        };
        String safe = Set.of("INVALID_IPV4", "NO_ROUTE", "NOT_ON_LINK", "UNSUPPORTED_INTERFACE", "NETWORK_CHANGED", "NO_NEIGHBOR", "QUERY_TIMEOUT", "QUERY_CANCELLED", "COMMAND_UNAVAILABLE", "ACCESS_DENIED", "UNSUPPORTED_PLATFORM").contains(code) ? code : "COMMAND_FAILED";
        return new Outcome(safe, message, null);
    }
}
