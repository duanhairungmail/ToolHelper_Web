package com.toolhelper.plugin.common;

import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import static com.toolhelper.plugin.electerm.PluginModels.*;

public class DesktopProcessAdapter {
    private final String clientName;
    protected final Map<Long, Instant> known = new ConcurrentHashMap<>();

    protected DesktopProcessAdapter(String clientName) {
        this.clientName = clientName;
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
                    if (path.getFileName().toString().equalsIgnoreCase(clientName + ".exe")) {
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
        if (state.state().equals("UNKNOWN")) throw new ApiException(409, "PROCESS_STATE_UNKNOWN", "无法确认相关进程状态，请关闭 " + clientName + " 后重试");
        if (!state.pids().isEmpty()) throw new ApiException(409, "PLUGIN_IN_USE", "检测到 " + clientName + " 运行中，请先关闭所有窗口", true);
    }

    public Map<String, Object> launch(Path executable, Path dataPath) throws IOException, InterruptedException {
        requireDesktop();
        ProcessObservation observation = observe(executable);
        requireOwnedInstance(observation);
        if (!observation.pids().isEmpty()) return Map.of("status", "ALREADY_RUNNING", "message", clientName + " 已运行，请查看已有窗口");
        long pid = start(List.of(executable.toString()), executable.getParent(), dataPath);
        return Map.of("status", "LAUNCH_REQUESTED", "pid", pid, "message", "已请求启动 " + clientName);
    }

    protected void requireOwnedInstance(ProcessObservation observation) {
        if (observation.foreignInstance()) throw new ApiException(409, "PLUGIN_INSTANCE_CONFLICT", "检测到其他安装的 " + clientName + "，请先关闭，避免连接转交给错误实例");
        if (observation.state().equals("UNKNOWN")) throw new ApiException(409, "PROCESS_STATE_UNKNOWN", "相关实例状态不明，请关闭 " + clientName + " 后重试");
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

    protected long start(List<String> command, Path workDirectory, Path dataPath) throws IOException, InterruptedException {
        SecurePaths.checkAncestors(Path.of(command.getFirst()));
        SecurePaths.createDirectories(dataPath);
        ProcessBuilder builder = new ProcessBuilder(command).directory(workDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        configureEnvironment(builder, dataPath);
        Process process;
        try { process = builder.start(); }
        catch (IOException failure) { throw new ApiException(500, "CONNECTION_DISPATCH_FAILED", clientName + " 启动请求失败，请检查程序和桌面环境"); }
        known.put(process.pid(), process.info().startInstant().orElse(Instant.now()));
        if (process.waitFor(350, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
            throw new ApiException(500, "CONNECTION_DISPATCH_FAILED", "" + clientName + " 在请求发起后异常退出，请检查插件是否完整");
        }
        return process.pid();
    }
    protected void configureEnvironment(ProcessBuilder builder, Path dataPath) throws IOException {}
}
