package com.toolhelper.plugin.electerm;

import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@Component
public class ProcessAdapter {
    private final JsonMapper json;
    private final Map<Long, Instant> known = new ConcurrentHashMap<>();

    public ProcessAdapter(JsonMapper json) {
        this.json = json;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            // JDK 21 默认的 Windows legacy 模式不转义参数内的 JSON 引号。
            // 仅固定当前服务 JVM 的 EXE 参数编码，使用 JDK 自带的 WIN32_SAFE 规则；不修改系统环境。
            System.setProperty("jdk.lang.Process.allowAmbiguousCommands", "false");
        }
    }

    public boolean platformSupported() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")
                && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch").toLowerCase(Locale.ROOT));
    }

    public void requireDesktop() {
        if (!platformSupported()) throw new ApiException(422, "PLATFORM_UNSUPPORTED", "本阶段只支持 Windows x64 本机桌面");
        if ("Services".equalsIgnoreCase(System.getenv("SESSIONNAME"))) {
            throw new ApiException(503, "DESKTOP_UNAVAILABLE", "请在已登录用户的 Windows 桌面中运行 Java 服务");
        }
    }

    public ProcessObservation observe(Path managedExecutable) {
        List<Long> pids = new ArrayList<>();
        boolean[] foreign = {false};
        boolean[] uncertain = {false};
        try (var processes = ProcessHandle.allProcesses()) {
            processes.forEach(process -> {
                ProcessHandle.Info info = process.info();
                Optional<String> command = info.command();
                if (command.isPresent()) {
                    Path path;
                    try { path = Path.of(command.get()).toAbsolutePath().normalize(); }
                    catch (InvalidPathException ignored) { return; }
                    if (path.getFileName().toString().equalsIgnoreCase("electerm.exe")) {
                        pids.add(process.pid());
                        if (managedExecutable == null || !path.equals(managedExecutable.toAbsolutePath().normalize())) foreign[0] = true;
                    }
                } else if (known.containsKey(process.pid()) && process.isAlive()) {
                    Instant expectedStart = known.get(process.pid());
                    if (info.startInstant().isEmpty() || info.startInstant().get().equals(expectedStart)) uncertain[0] = true;
                }
            });
        } catch (SecurityException error) { uncertain[0] = true; }
        known.keySet().removeIf(pid -> ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true));
        return new ProcessObservation(uncertain[0] ? "UNKNOWN" : pids.isEmpty() ? "NOT_OBSERVED" : "RUNNING", List.copyOf(pids), foreign[0]);
    }

    public void requireStopped(Path executable) {
        ProcessObservation state = observe(executable);
        if (state.state().equals("UNKNOWN")) throw new ApiException(409, "PROCESS_STATE_UNKNOWN", "无法确认相关进程状态，请关闭 electerm 后重试");
        if (!state.pids().isEmpty()) throw new ApiException(409, "PLUGIN_IN_USE", "检测到 electerm 运行中，请先关闭所有 electerm 窗口", true);
    }

    public Map<String, Object> launch(Path executable, Path dataPath) throws IOException, InterruptedException {
        requireDesktop();
        ProcessObservation observation = observe(executable);
        requireOwnedInstance(observation);
        if (!observation.pids().isEmpty()) return Map.of("status", "ALREADY_RUNNING", "message", "electerm 已运行，请查看已有窗口");
        long pid = start(List.of(executable.toString()), executable.getParent(), dataPath);
        return Map.of("status", "LAUNCH_REQUESTED", "pid", pid, "message", "已请求启动 electerm");
    }

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

    private static void requireOwnedInstance(ProcessObservation observation) {
        if (observation.foreignInstance()) throw new ApiException(409, "PLUGIN_INSTANCE_CONFLICT", "检测到其他安装的 electerm，请先关闭，避免连接转交给错误实例");
        if (observation.state().equals("UNKNOWN")) throw new ApiException(409, "PROCESS_STATE_UNKNOWN", "相关实例状态不明，请关闭 electerm 后重试");
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

    public Map<String, Object> openDirectory(Path directory) throws IOException {
        requireDesktop();
        SecurePaths.createDirectories(directory);
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) throw new ApiException(503, "DESKTOP_UNAVAILABLE", "无法定位 Windows 资源管理器");
        Path explorer = Path.of(systemRoot, "explorer.exe");
        if (!Files.isRegularFile(explorer)) throw new IOException("Windows 资源管理器不存在");
        new ProcessBuilder(explorer.toString(), directory.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        return Map.of("status", "OPEN_REQUESTED", "directory", directory.toString(), "message", "已请求打开插件目录");
    }

    private long start(List<String> command, Path workDirectory, Path dataPath) throws IOException, InterruptedException {
        SecurePaths.checkAncestors(Path.of(command.getFirst()));
        SecurePaths.createDirectories(dataPath);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        // 固定本安装的数据位置，避免继承外部 DATA_PATH 后使更新漏掉真实配置。
        builder.environment().put("DATA_PATH", dataPath.toString());
        builder.environment().remove("ELECTRON_RUN_AS_NODE");
        builder.environment().remove("NODE_OPTIONS");
        Process process = builder.start();
        known.put(process.pid(), process.info().startInstant().orElse(Instant.now()));
        if (process.waitFor(350, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
            throw new ApiException(500, "CONNECTION_DISPATCH_FAILED", "electerm 在请求发起后异常退出，请检查插件是否完整");
        }
        return process.pid();
    }
}
