package com.toolhelper.service;

import com.toolhelper.config.*;
import com.toolhelper.plugin.common.*;
import com.toolhelper.plugin.dbx.*;
import com.toolhelper.plugin.electerm.ArchiveExtractor;
import com.toolhelper.support.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.dbx.DbxModels.*;

@Service
public class DatabasePluginService extends ManagedPluginService {
    private final DbxProcessAdapter dbxProcesses;
    private final byte[] fingerprintKey = new byte[32];

    public DatabasePluginService(DbxInstallationStore store, DbxReleaseClient releases, ArchiveExtractor archives,
            DbxProcessAdapter processes, TaskCoordinator tasks, ToolHelperProperties properties, DbxProperties dbx, JsonMapper json) {
        super("dbx", dbx.supportedVersions(), store, releases, archives, processes, tasks, properties, json);
        dbxProcesses = processes;
        new SecureRandom().nextBytes(fingerprintKey);
    }

    @Override public synchronized Map<String, Object> status() throws java.io.IOException {
        Map<String, Object> result = super.status();
        result.put("packageVersion", result.get("version") == null ? null : DbxInstallationStore.packageVersion((String) result.get("version")));
        result.put("supportedTypes", List.of("MYSQL", "POSTGRESQL"));
        result.put("userDataDirectory", store.current().resolve("app/data").toString());
        return result;
    }

    @Override public Map<String, Object> checkUpdate() throws java.io.IOException, InterruptedException {
        Map<String, Object> result = new LinkedHashMap<>(super.checkUpdate());
        result.put("packageVersion", DbxInstallationStore.packageVersion((String) result.get("latestVersion")));
        return result;
    }

    public synchronized Map<String, Object> connect(ConnectionRequest request, String key) throws Exception {
        ConnectionRequest connection = DbxProcessAdapter.validate(request);
        // 密码请求只比较进程随机密钥的 HMAC；任务 JSON 不含密码、URI 或可离线猜测的密码摘要。
        boolean sensitive = connection.password() != null;
        String fingerprint = sensitive ? sensitiveFingerprint(connection) : SecurePaths.hash("CONNECT:" + json.writeValueAsString(connection));
        var replay = tasks.replay(pluginId, key, fingerprint);
        if (replay != null) return requireResult(replay, false);
        ensureReady();
        dbxProcesses.requireDesktop();
        var installed = store.requireInstalled();
        if (!supportedVersions.contains(OfficialReleaseClient.version(installed.tag()))) {
            throw new ApiException(422, "PLUGIN_VERSION_UNVERIFIED", "该 DBX 版本尚未适配带参连接");
        }
        Path executable = store.executable(installed);
        Path data = store.relative(store.current(), installed.userData());
        dbxProcesses.command(executable, connection); // 在占用任务前验证编码后的总长度。
        var task = tasks.claim(pluginId, "CONNECT", key, fingerprint, sensitive);
        var result = tasks.execute(task.taskId(), () -> {
            tasks.progress(task.taskId(), "DISPATCHING", 0, null, "正在向 DBX 提交连接请求");
            return dbxProcesses.connect(executable, data, connection);
        });
        return requireResult(result, false);
    }

    private String sensitiveFingerprint(ConnectionRequest connection) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsString(connection).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("本机 HMAC 不可用"); }
    }
}
