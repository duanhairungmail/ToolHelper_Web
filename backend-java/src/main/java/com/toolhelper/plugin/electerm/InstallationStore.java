package com.toolhelper.plugin.electerm;
import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.common.*;
import com.toolhelper.support.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@Component
public class InstallationStore extends ManagedInstallationStore {
    @Autowired
    public InstallationStore(ToolHelperProperties properties, JsonFiles json, LocalDataRoot root) throws IOException { super(properties, json, root, false, "electerm", "portable-v5"); }
    public InstallationStore(ToolHelperProperties properties, JsonFiles json) throws IOException { super(properties, json, new LocalDataRoot(properties), true, "electerm", "portable-v5"); }
    @Override protected void validateLayout(Path installation, InstallManifest manifest, Set<String> inventory) throws IOException {
        Path executable = relative(installation, manifest.executable());
        Path data = relative(installation, manifest.userData());
        if (!executable.getFileName().toString().equals("electerm.exe")
                || !executable.startsWith(installation.resolve("app"))
                || !inventory.contains(slash(installation.resolve("app").relativize(executable)).toLowerCase(Locale.ROOT))
                || !data.equals(executable.getParent().resolve("electerm"))
                || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IOException("安装 EXE 或便携数据布局不符合预期");
    }
    public InstallManifest makeCandidate(Path candidate, Asset asset, List<String> packageFiles) throws IOException {
        Path app = candidate.resolve("app");
        List<Path> executables;
        try (var paths = Files.walk(app)) {
            executables = paths.filter(path -> path.getFileName().toString().equals("electerm.exe"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        if (executables.size() != 1) throw new ApiException(422, "PLUGIN_LAYOUT_UNSUPPORTED", "压缩包中没有唯一准确的 electerm.exe");
        Path exe = executables.getFirst();
        String executable = slash(candidate.relativize(exe));
        String userData = slash(candidate.relativize(exe.getParent().resolve("electerm")));
        InstallManifest manifest = new InstallManifest("electerm", asset.releaseId(), asset.tag(),
                asset.assetId(), asset.name(), asset.sha256(), executable, userData, "portable-v5",
                UUID.randomUUID().toString(), Instant.now(), List.copyOf(packageFiles));
        json.write(candidate.resolve("install.json"), manifest);
        readManifest(candidate);
        return manifest;
    }

}
