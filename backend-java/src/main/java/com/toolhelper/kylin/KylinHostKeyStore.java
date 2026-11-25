package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.kylin.KylinModels.*;

/** 每个host:port单独记录；新主机需要当前探测结果确认，密钥变化不自动覆盖。 */
public final class KylinHostKeyStore {
    private final Path directory;
    private final JsonMapper json;
    private HostKey pending;
    private Instant probedAt;
    public KylinHostKeyStore(Path root, JsonMapper json) throws IOException {
        directory = KylinFiles.directory(root.resolve("known-hosts")); this.json = json;
    }
    private Path path(Host host) { return directory.resolve(KylinFiles.hash((host.host() + ":" + host.port()).getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json"); }
    public synchronized String trusted(Host host) {
        Path path = path(host);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            HostKey key = json.readValue(KylinFiles.read(path, 4096), HostKey.class);
            if (!key.host().equals(host.host()) || key.port() != host.port()) throw new IOException("Host mismatch");
            return key.fingerprint();
        } catch (IOException | RuntimeException invalid) { throw new ApiException(500, "HOST_KEY_STORE_INVALID", "项目主机信任记录损坏，未绕过校验"); }
    }
    public synchronized HostKey probe(Host host) {
        HostKey key = KylinSshClient.probe(host); String old = trusted(host);
        pending = key; probedAt = Instant.now();
        return new HostKey(key.host(), key.port(), key.algorithm(), key.fingerprint(), key.fingerprint().equals(old));
    }
    public synchronized HostKey trust(TrustRequest request) {
        Host host = KylinSshClient.validate(request.host(), request.port());
        if (!request.confirmed() || pending == null || Instant.now().isAfter(probedAt.plusSeconds(300))
                || !pending.host().equals(host.host()) || pending.port() != host.port() || !pending.fingerprint().equals(request.fingerprint()))
            throw new ApiException(409, "HOST_KEY_CONFIRMATION_REQUIRED", "需要先探测并确认当前主机指纹");
        String old = trusted(host);
        if (old != null && !old.equals(request.fingerprint())) throw new ApiException(409, "SSH_HOST_KEY_CHANGED", "已信任主机密钥发生变化；需先独立核对，不自动替换");
        HostKey current = KylinSshClient.probe(host);
        if (!current.fingerprint().equals(request.fingerprint())) throw new ApiException(409, "SSH_HOST_KEY_CHANGED", "确认期间主机密钥发生变化，未保存信任");
        HostKey result = new HostKey(host.host(), host.port(), current.algorithm(), current.fingerprint(), true);
        if (old == null) try { KylinFiles.writeNew(path(host), json.writeValueAsBytes(result)); }
        catch (IOException failure) { throw new ApiException(500, "HOST_KEY_STORE_FAILED", "主机信任记录保存失败"); }
        pending = null; return result;
    }
}
