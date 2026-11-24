package com.toolhelper.plugin.common;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.toolhelper.plugin.electerm.PluginModels.*;

public abstract class ManagedInstallationStore implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedInstallationStore.class);
    protected final ToolHelperProperties properties;
    protected final JsonFiles json;
    private final LocalDataRoot dataRoot;
    private final boolean ownsRoot;
    private final String pluginId;
    private final String layout;
    private volatile String recoveryProblem;
    protected ManagedInstallationStore(ToolHelperProperties properties, JsonFiles json, LocalDataRoot dataRoot, boolean ownsRoot, String pluginId, String layout) throws IOException {
        this.properties = properties; this.json = json; this.dataRoot = dataRoot; this.ownsRoot = ownsRoot; this.pluginId = pluginId; this.layout = layout;
        for (Path directory : List.of(pluginRoot(), stagingRoot(), backupRoot(), preservedRoot())) SecurePaths.createDirectories(directory);
    }
    public LocalDataRoot dataRoot() { return dataRoot; }
    public Path root() { return dataRoot.root(); }
    public Path pluginRoot() { return root().resolve("plugins").resolve(pluginId); }
    public Path current() { return pluginRoot().resolve("current"); }
    public Path stagingRoot() { return pluginRoot().resolve("staging"); }
    public Path backupRoot() { return pluginRoot().resolve("backup"); }
    public Path preservedRoot() { return pluginRoot().resolve("preserved"); }
    public Path tasksRoot() { return root().resolve("tasks"); }
    public Path journalPath() { return pluginRoot().resolve("transaction.json"); }
    public Path stage(String task) { return stagingRoot().resolve(uuid(task)); }
    public Path backup(String task) { return backupRoot().resolve(uuid(task)); }
    public Path preserved(String task) { return preservedRoot().resolve(uuid(task)); }

    private static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("非法任务标识");
        return value;
    }

    public void recoveryRequired(String message) { recoveryProblem = message; }

    public Inspection inspect() {
        if (recoveryProblem != null || dataRoot.recoveryProblem() != null) return new Inspection("RECOVERY_REQUIRED", null, recoveryProblem != null ? recoveryProblem : dataRoot.recoveryProblem());
        try {
            SecurePaths.checkAncestors(current());
            if (!Files.exists(current(), LinkOption.NOFOLLOW_LINKS)) return new Inspection("NOT_INSTALLED", null, "插件未安装");
            InstallManifest manifest = readManifest(current());
            return new Inspection("INSTALLED", manifest, "插件已安装");
        } catch (Exception error) {
            LOG.warn("安装检查失败：{}", error.toString());
            return new Inspection("BROKEN", null, "安装文件或元数据无效，请查看本机日志；不会自动覆盖");
        }
    }

    public InstallManifest requireInstalled() throws IOException {
        Inspection state = inspect();
        if (!state.state().equals("INSTALLED")) throw new ApiException(409,
                "NOT_INSTALLED".equals(state.state()) ? "PLUGIN_NOT_INSTALLED" : "PLUGIN_BROKEN", state.message());
        return state.manifest();
    }

    public InstallManifest readManifest(Path installation) throws IOException {
        SecurePaths.checkAncestors(installation);
        InstallManifest manifest = json.read(installation.resolve("install.json"), InstallManifest.class);
        if (!pluginId.equals(manifest.pluginId()) || !layout.equals(manifest.layout())
                || manifest.revision() == null || manifest.packageFiles() == null
                || manifest.packageFiles().size() > properties.maxArchiveEntries()) throw new IOException("安装元数据格式无效");
        UUID.fromString(manifest.revision());
        OfficialReleaseClient.version(manifest.tag());
        relative(installation, manifest.executable());
        relative(installation, manifest.userData());
        Set<String> inventory = new HashSet<>();
        for (String file : manifest.packageFiles()) {
            relative(installation.resolve("app"), file);
            if (!inventory.add(file.toLowerCase(Locale.ROOT))) throw new IOException("安装清单包含重复文件");
        }
        validateLayout(installation, manifest, inventory);
        return manifest;
    }

    public Path executable(InstallManifest manifest) throws IOException {
        Path executable = relative(current(), manifest.executable());
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IOException("插件可执行文件不存在");
        return executable;
    }

    public Path relative(Path base, String value) throws IOException {
        Path result = SecurePaths.archivePath(base, value, properties.maxPathDepth() + 2);
        SecurePaths.checkAncestors(result);
        return result;
    }

    protected abstract void validateLayout(Path installation, InstallManifest manifest, Set<String> inventory) throws IOException;
    public abstract InstallManifest makeCandidate(Path candidate, Asset asset, List<String> packageFiles) throws IOException;

    /** 拒绝未归入程序包或已核实数据目录的新增文件，避免更新/卸载时丢弃用户自己放入的文件。 */
    public void verifyOwnedFiles(InstallManifest manifest) throws IOException {
        Set<String> owned = new HashSet<>(manifest.packageFiles());
        Path app = current().resolve("app");
        Path userData = relative(current(), manifest.userData());
        Files.walkFileTree(current(), new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                SecurePaths.checkNode(dir);
                if (dir.equals(userData)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                SecurePaths.checkNode(file);
                if (!file.equals(current().resolve("install.json"))
                        && (!file.startsWith(app) || !owned.contains(slash(app.relativize(file))))) {
                    throw new ApiException(409, "USER_DATA_LAYOUT_UNKNOWN", "安装目录存在未识别文件，已停止操作以保留数据");
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public boolean copyData(InstallManifest old, Path destination) throws IOException {
        Path source = relative(current(), old.userData());
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return false;
        copyTree(source, destination);
        return true;
    }

    private void copyTree(Path source, Path destination) throws IOException {
        SecurePaths.createDirectories(destination);
        long[] bytes = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                SecurePaths.checkNode(dir);
                SecurePaths.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                SecurePaths.checkNode(file);
                bytes[0] += attrs.size();
                if (bytes[0] > properties.maxUserDataBytes()) throw new ApiException(409, "USER_DATA_TOO_LARGE", "用户数据超过保留配额，未删除原数据");
                Path target = destination.resolve(source.relativize(file));
                SecurePaths.checkAncestors(target);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("候选目录与用户数据冲突");
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                if (!SecurePaths.sha256(file).equals(SecurePaths.sha256(target))) throw new IOException("用户数据复制校验失败");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void move(Path from, Path to) throws IOException {
        SecurePaths.requireInside(pluginRoot(), from);
        SecurePaths.requireInside(pluginRoot(), to);
        SecurePaths.checkAncestors(from);
        SecurePaths.createDirectories(to.getParent());
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("目录切换目标已存在");
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    public void writeJournal(Journal journal) throws IOException { json.write(journalPath(), journal); }
    public void clearJournal() throws IOException {
        SecurePaths.checkAncestors(journalPath());
        Files.deleteIfExists(journalPath());
    }

    /** 执行过程中失败必须恢复旧版；与重启后的提交判定不同，不能把新版本误当作成功。 */
    public void rollback(Journal journal) throws IOException {
        try {
            Path backup = backup(journal.taskId());
            boolean hasCurrent = Files.exists(current(), LinkOption.NOFOLLOW_LINKS);
            boolean hasBackup = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
            if (hasBackup && !readManifest(backup).revision().equals(journal.oldRevision())) {
                throw new IOException("回滚备份修订不符");
            }
            if (hasCurrent) {
                // 即使新 EXE 损坏，也可根据本次事务写入的修订号隔离失败候选，保留诊断现场。
                String revision = json.read(current().resolve("install.json"), InstallManifest.class).revision();
                if (revision.equals(journal.oldRevision()) && !hasBackup) {
                    readManifest(current());
                    clearJournal();
                    return;
                }
                if (!revision.equals(journal.newRevision()) || (journal.oldRevision() != null && !hasBackup)) {
                    throw new IOException("回滚时发现安装修订冲突");
                }
                move(current(), stage(journal.taskId()).resolve("failed-current"));
            }
            if (journal.oldRevision() != null) {
                if (!hasBackup) throw new IOException("回滚备份不存在");
                move(backup, current());
                readManifest(current());
            }
            clearJournal();
        } catch (IOException | RuntimeException error) {
            recoveryRequired("回滚未能安全完成，请核对管理目录中的原安装、备份及恢复记录");
            throw error;
        }
    }

    /** 只恢复可由两个修订号确定的布局；部分卸载清理无法逆转时保留现场并停止变更。 */
    public void recover() {
        if (!Files.exists(journalPath(), LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Journal journal = json.read(journalPath(), Journal.class);
            String task = uuid(journal.taskId());
            boolean currentExists = Files.exists(current(), LinkOption.NOFOLLOW_LINKS);
            if (journal.operation().equals("UNINSTALL")) {
                Path removed = stage(task).resolve("uninstall");
                if (currentExists && readManifest(current()).revision().equals(journal.oldRevision())) {
                    clearJournal();
                } else if (!currentExists && !Files.exists(removed, LinkOption.NOFOLLOW_LINKS)) {
                    if (journal.userDataPresent() && !Files.isDirectory(preserved(task))) throw new IOException("保留数据丢失");
                    clearJournal();
                } else if (!currentExists && !journal.phase().equals("CLEANING")
                        && readManifest(removed).revision().equals(journal.oldRevision())) {
                    move(removed, current());
                    clearJournal();
                } else throw new IOException("卸载清理部分完成，请人工核对保留数据和残留文件");
            } else if (Set.of("INSTALL", "UPDATE").contains(journal.operation())) {
                if (currentExists) {
                    String revision = readManifest(current()).revision();
                    if (!revision.equals(journal.newRevision()) && !revision.equals(journal.oldRevision())) throw new IOException("安装修订冲突");
                    clearJournal();
                } else if (journal.oldRevision() != null && Files.exists(backup(task), LinkOption.NOFOLLOW_LINKS)) {
                    if (!readManifest(backup(task)).revision().equals(journal.oldRevision())) throw new IOException("备份修订不符");
                    move(backup(task), current());
                    clearJournal();
                } else if (journal.oldRevision() == null) clearJournal();
                else throw new IOException("无法确定可恢复旧版");
            } else throw new IOException("未知操作记录");
            LOG.warn("已检查并恢复中断操作 {}，请重新查询插件状态", task);
        } catch (Exception error) {
            recoveryRequired("发现未完成切换，已保留现场并停止变更，请检查日志和管理目录");
            LOG.error("自动恢复无法安全完成", error);
        }
    }

    public void ensureDiskSpace(long required) throws IOException {
        if (Files.getFileStore(pluginRoot()).getUsableSpace() < required) {
            throw new ApiException(409, "INSUFFICIENT_DISK_SPACE", "磁盘可用空间不足，原安装未改变");
        }
    }

    protected static String slash(Path path) { return path.toString().replace('\\', '/'); }

    @Override public void close() throws IOException { if (ownsRoot) dataRoot.close(); }
}
