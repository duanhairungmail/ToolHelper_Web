package com.toolhelper.plugin.electerm;
import com.toolhelper.plugin.common.DesktopProcessAdapter;
import com.toolhelper.support.*;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@Component
public class ProcessAdapter extends DesktopProcessAdapter {
    private final JsonMapper json;
    public ProcessAdapter(JsonMapper json) { super("electerm"); this.json = json; }
    public Map<String, Object> connect(Path executable, Path dataPath, ConnectionRequest request) throws IOException, InterruptedException {
        requireDesktop();
        ConnectionRequest normalized = validate(request);
        ProcessObservation observation = observe(executable);
        requireOwnedInstance(observation);
        // 该版单实例在窗口创建前无法保证转交；不在初次启动的短窗口内盲目提交第二次连接。
        for (Long pid : observation.pids()) {
            Optional<Instant> started = ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant());
            if (started.isPresent() && started.get().plusSeconds(10).isAfter(Instant.now())) {
                throw new ApiException(409, "PLUGIN_STARTING", "electerm 正在启动，请等窗口就绪后再发起新的连接", true);
            }
        }
        long pid = start(command(executable, normalized), executable.getParent(), dataPath);
        return Map.of("status", "CONNECTION_REQUESTED", "pid", pid,
                "protocol", normalized.protocol(), "host", normalized.host(), "port", normalized.port(),
                "username", normalized.username(), "message", "已向 electerm 提交连接请求，请在外挂中查看连接或认证结果");
    }

    /** 固定 --tp + JSON 选项避免 IPv6/@/域用户名被快捷连接字符串重新拆解。 */
    public List<String> command(Path executable, ConnectionRequest request) {
        ConnectionRequest normalized = validate(request);
        String type = normalized.protocol().equals("SFTP") ? "ssh" : normalized.protocol().toLowerCase(Locale.ROOT);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("host", normalized.host());
        options.put("port", normalized.port());
        options.put("username", normalized.username());
        if (Set.of("SSH", "SFTP").contains(normalized.protocol())) options.put("enableSftp", true);
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--tp", type,
                "--opts", json.writeValueAsString(options)));
        if (normalized.protocol().equals("SFTP")) command.add("--sftp-only");
        return List.copyOf(command);
    }

    public static ConnectionRequest validate(ConnectionRequest request) {
        if (request == null || request.protocol() == null || !Set.of("SSH", "SFTP", "RDP", "VNC").contains(request.protocol())) {
            throw new ApiException(422, "PROTOCOL_NOT_SUPPORTED", "只支持 SSH、SFTP、RDP、VNC");
        }
        String host = request.host() == null ? "" : request.host().trim();
        boolean valid = false;
        if (host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            valid = Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255 && (part.length() == 1 || !part.startsWith("0")));
        } else if (host.length() <= 45 && host.contains(":") && host.matches("[0-9a-fA-F:.]+")) {
            try { valid = InetAddress.getByName(host) instanceof java.net.Inet6Address; }
            catch (java.net.UnknownHostException ignored) { valid = false; }
        }
        if (!valid || request.port() == null || request.port() < 1 || request.port() > 65535) {
            throw new ApiException(400, "INVALID_CONNECTION_PARAMETERS", "请输入有效 IPv4/IPv6 地址及 1～65535 的端口");
        }
        String username = request.username() == null ? "" : request.username().trim();
        if (username.length() > 256 || username.chars().anyMatch(Character::isISOControl)
                || (!request.protocol().equals("VNC") && username.isBlank())) {
            throw new ApiException(400, "INVALID_CONNECTION_PARAMETERS", "SSH/SFTP/RDP 需要用户名；用户名不能超过 256 字符或包含控制字符");
        }
        return new ConnectionRequest(request.protocol(), host, request.port(), username);
    }

    @Override protected void configureEnvironment(ProcessBuilder builder, Path dataPath) {
        builder.environment().put("DATA_PATH", dataPath.toString());
        builder.environment().remove("ELECTRON_RUN_AS_NODE");
        builder.environment().remove("NODE_OPTIONS");
    }
}
