package com.toolhelper.plugin.dbx;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.common.*;
import com.toolhelper.support.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.toolhelper.plugin.electerm.PluginModels.*;

/** DBX v0.6.72 根目录平铺便携包；完整 data 由公共事务整体保留。 */
@Component
public class DbxInstallationStore extends ManagedInstallationStore {
    private static final Set<String> PACKAGE_FILES = Set.of("DBX.exe", "portable.dbx", "portable-update.json", "LICENSE", "README.md");
    // 官方 v0.6.72 实包的声明版本与 tag 不同，精确记录实测对应关系，不能宽松忽略后缀。
    private static final Map<String, String> PACKAGE_VERSIONS = Map.of("0.6.72", "0.6.72-beta.20261119.2");
    public static String packageVersion(String tag) { return PACKAGE_VERSIONS.get(OfficialReleaseClient.version(tag)); }

    public DbxInstallationStore(ToolHelperProperties properties, JsonFiles json, LocalDataRoot root) throws IOException {
        super(properties, json, root, false, "dbx", "dbx-portable-v1");
    }

    @Override protected void validateLayout(Path installation, InstallManifest manifest, Set<String> inventory) throws IOException {
        if (!"app/DBX.exe".equals(manifest.executable()) || !"app/data".equals(manifest.userData())
                || !new HashSet<>(manifest.packageFiles()).equals(PACKAGE_FILES)
                || manifest.packageHashes() == null || !manifest.packageHashes().keySet().equals(PACKAGE_FILES)) {
            throw new IOException("DBX 便携布局或文件摘要清单不正确");
        }
        for (String file : PACKAGE_FILES) {
            if (!Files.isRegularFile(relative(installation.resolve("app"), file), LinkOption.NOFOLLOW_LINKS)
                    || !manifest.packageHashes().getOrDefault(file, "").matches("[0-9a-f]{64}")) throw new IOException("DBX 包文件缺失或摘要无效");
        }
        if (Files.exists(installation.resolve("app/uninstall.exe"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("DBX 出现非便携安装标记");
    }

    @Override public InstallManifest makeCandidate(Path candidate, Asset asset, List<String> files) throws IOException {
        if (!new HashSet<>(files).equals(PACKAGE_FILES) || files.size() != PACKAGE_FILES.size()) {
            throw new ApiException(422, "PLUGIN_LAYOUT_UNSUPPORTED", "DBX 包不符合已适配的根目录平铺便携布局");
        }
        Path app = candidate.resolve("app");
        var metadata = json.read(app.resolve("portable-update.json"), tools.jackson.databind.JsonNode.class);
        if (metadata.path("schema_version").asInt() != 1 || !"x64".equals(metadata.path("arch").asText())
                || !metadata.path("version").asText().equals(packageVersion(asset.tag()))
                || !"DBX.exe".equals(metadata.path("executable").asText())
                || !SecurePaths.sha256(app.resolve("DBX.exe")).equals(metadata.path("executable_sha256").asText())) {
            throw new ApiException(422, "PLUGIN_LAYOUT_UNSUPPORTED", "DBX 包内版本、架构或 EXE 摘要不符");
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String file : files) hashes.put(file, SecurePaths.sha256(relative(app, file)));
        InstallManifest manifest = new InstallManifest("dbx", asset.releaseId(), asset.tag(), asset.assetId(),
                asset.name(), asset.sha256(), "app/DBX.exe", "app/data", "dbx-portable-v1",
                UUID.randomUUID().toString(), Instant.now(), List.copyOf(files), Map.copyOf(hashes));
        json.write(candidate.resolve("install.json"), manifest);
        readManifest(candidate);
        return manifest;
    }

    @Override public void verifyOwnedFiles(InstallManifest manifest) throws IOException {
        super.verifyOwnedFiles(manifest);
        verifyHashes(manifest);
    }

    @Override public Path executable(InstallManifest manifest) throws IOException {
        verifyHashes(manifest);
        return super.executable(manifest);
    }

    private void verifyHashes(InstallManifest manifest) throws IOException {
        for (String file : PACKAGE_FILES) {
            if (!SecurePaths.sha256(relative(current().resolve("app"), file)).equals(manifest.packageHashes().get(file))) {
                throw new ApiException(409, "PLUGIN_FILES_CHANGED", "DBX 程序文件已被外部修改或自更新，请核对后再操作；用户数据已保留");
            }
        }
    }
}
