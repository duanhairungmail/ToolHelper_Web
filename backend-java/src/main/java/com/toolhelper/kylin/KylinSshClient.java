package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.impl.AbstractSftpClient;
import org.apache.sshd.sftp.SftpModuleProperties;
import static com.toolhelper.kylin.KylinModels.*;

/** 不加载全局密钥，不接受未固定主机密钥；密码仅经认证或stdin传递。 */
public final class KylinSshClient implements AutoCloseable {
    public static final int OUTPUT_LIMIT = 262144;
    private final SshClient client;
    private final ClientSession session;
    private volatile boolean outputOverflow;
    /** stderr仅供内存中的错误分类，不允许将远程原始输出写入日志或API。 */
    public record Reply(int exitCode, String output, String error) {
        @Override public String toString() { return "Reply[exitCode=" + exitCode + "]"; }
    }
    private KylinSshClient(SshClient client, ClientSession session) { this.client = client; this.session = session; }

    public static Host validate(String host, int port) {
        if (host == null || host.length() > 253 || port < 1 || port > 65535
                || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?"))
            throw new ApiException(400, "INVALID_SSH_TARGET", "请输入单个IPv4或DNS主机及有效SSH端口");
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.contains("..") || Arrays.stream(normalized.split("\\.")).anyMatch(s -> s.startsWith("-") || s.endsWith("-")))
            throw new ApiException(400, "INVALID_SSH_TARGET", "主机格式不正确");
        if (normalized.matches("[0-9.]+") && !normalized.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))
            throw new ApiException(400, "INVALID_SSH_TARGET", "IPv4需填写四段十进制地址");
        try {
            for (var address : InetAddress.getAllByName(normalized))
                if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
                        || address.getHostAddress().equals("100.100.100.200"))
                    throw new ApiException(400, "SSH_TARGET_REJECTED", "不允许未指定、多播、链路本地或metadata地址");
        } catch (UnknownHostException invalid) { throw new ApiException(400, "SSH_DNS_ERROR", "SSH主机无法解析"); }
        return new Host(normalized, port);
    }
    private static SshClient base() {
        var client = SshClient.setUpDefaultClient();
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE));
        CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, Duration.ofSeconds(15));
        CoreModuleProperties.AUTH_TIMEOUT.set(client, Duration.ofSeconds(20));
        CoreModuleProperties.IDLE_TIMEOUT.set(client, Duration.ofMinutes(16));
        CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, Duration.ofMinutes(16));
        CoreModuleProperties.WINDOW_TIMEOUT.set(client, Duration.ofSeconds(30));
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client, Duration.ofSeconds(15));
        AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT.set(client, Duration.ofSeconds(30));
        client.start(); return client;
    }
    public static HostKey probe(Host host) {
        AtomicReference<HostKey> observed = new AtomicReference<>();
        try (var client = base()) {
            // 本探测明确拒绝认证，唯一用途是读取握手中服务端发送的公开密钥。
            client.setServerKeyVerifier((session, address, key) -> {
                observed.set(new HostKey(host.host(), host.port(), key.getAlgorithm(), KeyUtils.getFingerPrint(BuiltinDigests.sha256, key), false));
                return false;
            });
            try (var ignored = client.connect("toolhelper-key-probe", host.host(), host.port()).verify(Duration.ofSeconds(15)).getSession()) {
                ignored.waitFor(EnumSet.of(ClientSession.ClientSessionEvent.CLOSED), Duration.ofSeconds(15));
            } catch (IOException expected) { if (observed.get() == null) throw expected; }
            if (observed.get() == null) throw new IOException("No host key");
            return observed.get();
        } catch (IOException failure) { throw new ApiException(502, "SSH_PROBE_FAILED", "SSH主机密钥探测失败，请检查端口和服务"); }
    }
    public static KylinSshClient connect(Host host, String username, String password, String fingerprint) {
        if (username == null || !username.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}") || password == null
                || password.isEmpty() || password.length() > 2048 || password.codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(400, "INVALID_SSH_CREDENTIAL", "SSH用户名或密码格式不正确");
        var client = base(); ClientSession session = null; AtomicBoolean mismatch = new AtomicBoolean();
        try {
            client.setServerKeyVerifier((s, address, key) -> {
                boolean matches = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key).equals(fingerprint);
                if (!matches) mismatch.set(true); return matches;
            });
            session = client.connect(username, host.host(), host.port()).verify(Duration.ofSeconds(15)).getSession();
            session.addPasswordIdentity(password);
            try { session.auth().verify(Duration.ofSeconds(15)); }
            finally { session.removePasswordIdentity(password); }
            return new KylinSshClient(client, session);
        } catch (IOException failure) {
            if (session != null) session.close(true).awaitUninterruptibly(5000);
            client.close(true).awaitUninterruptibly(5000);
            throw new ApiException(502, mismatch.get() ? "SSH_HOST_KEY_CHANGED" : "SSH_AUTH_OR_CONNECTION_FAILED",
                    mismatch.get() ? "主机密钥与已信任记录不一致，已中止连接" : "SSH连接或密码认证失败；未自动重试");
        }
    }
    public boolean connected() { return session.isOpen() && session.isAuthenticated(); }
    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    public Reply run(String script, boolean root, String sudoPassword, int seconds, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new ApiException(409, "CANCELLED", "命令派发前已取消");
        if (sudoPassword != null && (sudoPassword.length() > 2048 || sudoPassword.codePoints().anyMatch(Character::isISOControl)))
            throw new ApiException(400, "INVALID_SUDO_PASSWORD", "sudo密码格式不正确");
        if (!connected()) throw new ApiException(409, "SSH_DISCONNECTED", "SSH已断开，请重新连接");
        // 固定sudo诊断语言便于按白名单分类；不改目标机环境配置，也不复用sudo缓存。
        String command = (root ? "LC_ALL=C sudo -k -S -p '' -- /bin/bash -c " : "/bin/bash -c ") + quote(script);
        byte[] input = ((root ? Objects.requireNonNullElse(sudoPassword, "") : "") + "\n").getBytes(StandardCharsets.UTF_8);
        var stdout = new BoundedOutput(); var stderr = new BoundedOutput(); outputOverflow = false;
        try (var channel = session.createExecChannel(command)) {
            channel.setIn(new ByteArrayInputStream(input)); channel.setOut(stdout); channel.setErr(stderr);
            channel.open().verify(Duration.ofSeconds(15));
            long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (!channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 100).contains(ClientChannelEvent.CLOSED)) {
                if (outputOverflow) throw new ApiException(502, "SSH_OUTPUT_LIMIT", "远程输出超过限制，需核对远程状态");
                if (cancelled.getAsBoolean()) throw new ApiException(409, "CANCELLED", "已请求停止；已发出步骤可能已经生效");
                if (System.nanoTime() >= end) throw new ApiException(504, "SSH_COMMAND_TIMEOUT", "远程命令超时，未自动重试");
            }
            if (outputOverflow) throw new ApiException(502, "SSH_OUTPUT_LIMIT", "远程输出超过限制");
            if (channel.getExitStatus() == null) throw new ApiException(502, "SSH_EXIT_UNKNOWN", "连接未返回退出状态，需重新核对目标");
            var reply = new Reply(channel.getExitStatus(), stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
            if (root && reply.exitCode() != 0) checkSudoFailure(reply.error(), sudoPassword != null && !sudoPassword.isEmpty());
            return reply;
        } catch (IOException failure) { throw new ApiException(502, "SSH_IO_ERROR", "SSH通道异常；远程执行状态需重新核对"); }
        finally { Arrays.fill(input, (byte) 0); }
    }
    /** 仅返回固定描述；不能向客户端或日志回显可能含秘密的远程stderr。 */
    static void checkSudoFailure(String error, boolean suppliedPassword) {
        String message = error.toLowerCase(Locale.ROOT);
        if (!suppliedPassword && message.contains("sudo:") && (message.contains("no password was provided") || message.contains("a password is required") || message.contains("incorrect password")))
            throw new ApiException(403, "KYLIN_SUDO_PASSWORD_REQUIRED", "目标sudo需要密码，请填写当前SSH登录用户的sudo密码后重试（通常为该用户的登录密码）");
        if (message.contains("sudo:") && (message.contains("incorrect password") || message.contains("sorry, try again") || message.contains("no password was provided") || message.contains("a password is required")))
            throw new ApiException(403, "KYLIN_SUDO_AUTH_FAILED", "sudo密码验证失败，请填写当前SSH登录用户的密码；默认不是root密码");
        if (message.contains("sudo:") && (message.contains("must have a tty") || message.contains("terminal is required")))
            throw new ApiException(403, "KYLIN_SUDO_TTY_REQUIRED", "目标sudo策略要求交互终端，当前非交互SSH执行被拒绝，请由管理员核对策略");
        if (message.contains("not in the sudoers") || message.contains("is not allowed to execute") || message.contains("may not run sudo"))
            throw new ApiException(403, "KYLIN_SUDO_FORBIDDEN", "当前SSH用户未获准通过sudo执行所需命令，请由管理员核对授权");
    }
    public void upload(String path, byte[] bytes) { upload(path, bytes, () -> false); }
    public void upload(String path, byte[] bytes, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new ApiException(409, "CANCELLED", "上传派发前取消");
        try (var sftp = SftpClientFactory.instance().createSftpClient(session);
                var handle = sftp.open(path, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Exclusive)) {
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            for (int offset = 0; offset < bytes.length; offset += 32768) {
                if (cancelled.getAsBoolean()) throw new ApiException(409, "CANCELLED", "已停止后续上传");
                if (System.nanoTime() > end) throw new ApiException(504, "SFTP_UPLOAD_TIMEOUT", "上传达到时限，未应用策略");
                sftp.write(handle, offset, bytes, offset, Math.min(32768, bytes.length - offset));
            }
        }
        catch (IOException failure) { throw new ApiException(502, "SFTP_UPLOAD_FAILED", "SFTP上传失败；需核对任务临时目录"); }
    }
    private final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            int remaining = OUTPUT_LIMIT - count;
            if (length > remaining) outputOverflow = true;
            super.write(bytes, offset, Math.min(length, remaining));
        }
        @Override public synchronized void write(int value) {
            if (count >= OUTPUT_LIMIT) outputOverflow = true; else super.write(value);
        }
    }
    @Override public void close() {
        session.close(false).awaitUninterruptibly(5000);
        client.close(true).awaitUninterruptibly(5000);
    }
}
