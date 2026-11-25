package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.kylin.KylinModels.*;

public final class KylinBackupStore {
    /** 首次状态可能分散在多次操作中；每个文件保留其真实备份来源。 */
    public record RestorePoint(Backup backup, Map<String, String> sources, Map<String, String> expected) {}
    private final Path directory;
    private final JsonMapper json;
    public KylinBackupStore(Path root, JsonMapper json) throws IOException {
        directory = KylinFiles.directory(root.resolve("operations")); this.json = json;
    }
    private Path path(String id, String suffix) {
        try { UUID.fromString(id); } catch (RuntimeException invalid) { throw new ApiException(400, "INVALID_BACKUP_ID", "备份ID无效"); }
        return directory.resolve(id + suffix + ".json");
    }
    public synchronized void save(Backup backup) {
        try { KylinFiles.writeNew(path(backup.backupId(), ""), json.writeValueAsBytes(backup)); }
        catch (IOException failure) { throw new ApiException(500, "BACKUP_RECORD_FAILED", "本地备份记录写入失败，未执行变更"); }
    }
    public synchronized void receipt(String id, Map<String, String> state) {
        try { KylinFiles.writeNew(path(id, ".applied"), json.writeValueAsBytes(state)); }
        catch (IOException failure) { throw new ApiException(500, "BACKUP_RECEIPT_FAILED", "变更后状态记录失败，需人工核对远程备份"); }
    }
    public synchronized void unchanged(String id) {
        try { KylinFiles.writeNew(path(id, ".unchanged"), new byte[]{1}); }
        catch (IOException failure) { throw new ApiException(500, "BACKUP_RECORD_FAILED", "未变更记录保存失败，需人工核对备份"); }
    }
    /** 软件包升级只能记录审计状态，不纳入可恢复文件备份，避免产生一键回滚承诺。 */
    public synchronized void patchRecord(String id, String phase, Connection connection, Map<String, String> state) {
        if (!Set.of("before", "after", "failed").contains(phase)) throw new IllegalArgumentException("Unknown patch record phase");
        try { KylinFiles.writeNew(path(id, ".patch-" + phase), json.writeValueAsBytes(Map.of(
                "host", connection.host(), "port", connection.port(), "fingerprint", connection.fingerprint(),
                "feature", "patch", "sha256", KylinPatch.SHA256, "state", state, "time", java.time.Instant.now()))); }
        catch (IOException failure) { throw new ApiException(500, "PATCH_RECORD_FAILED", "补丁安装记录保存失败，请核对任务与远程安装状态"); }
    }
    public synchronized Backup get(String id) {
        try {
            Backup backup = json.readValue(KylinFiles.read(path(id, ""), 65536), Backup.class);
            if (!backup.backupId().equals(id)) throw new IOException("Backup identity mismatch");
            var allowedPaths = KylinPolicyCatalog.paths(backup.feature(), KylinPolicyCatalog.OPTIMIZATIONS);
            var allowedUnits = KylinPolicyCatalog.units(backup.feature(), KylinPolicyCatalog.OPTIMIZATIONS);
            if (!allowedPaths.containsAll(backup.paths()) || !allowedUnits.containsAll(backup.units())) throw new IOException("Unknown policy path");
            KylinProbe.validate(backup.state(), backup.paths().size(), backup.units().size());
            return backup;
        }
        catch (IOException invalid) { throw new ApiException(409, "BACKUP_MISSING", "备份记录不存在或损坏"); }
    }
    public synchronized Map<String, String> expected(String id) {
        try {
            var node = json.readTree(KylinFiles.read(path(id, ".applied"), 65536));
            Map<String, String> values = new LinkedHashMap<>();
            node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
            return values;
        } catch (IOException invalid) { throw new ApiException(409, "BACKUP_STATE_UNKNOWN", "缺少变更后状态记录，需先人工核对远程状态"); }
    }
    public synchronized List<Backup> list(String host, int port, String fingerprint) {
        return all(host, port, fingerprint).stream().limit(100).toList();
    }
    private List<Backup> all(String host, int port, String fingerprint) {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                    .map(p -> get(p.getFileName().toString().substring(0, 36)))
                    .filter(b -> b.host().equals(host) && b.port() == port && b.fingerprint().equals(fingerprint))
                    .sorted(Comparator.comparing(Backup::createdAt).reversed().thenComparing(Backup::backupId)).toList();
        } catch (IOException failure) { throw new ApiException(500, "BACKUP_LIST_FAILED", "备份索引读取失败"); }
    }
    public synchronized RestorePoint restorePoint(String id) {
        Backup backup = get(id);
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < backup.paths().size(); i++)
            sources.put(backup.paths().get(i), KylinPolicyCatalog.BACKUPS + id + "/file-" + i);
        return new RestorePoint(backup, Map.copyOf(sources), expected(id));
    }
    public synchronized RestorePoint initial(String host, int port, String fingerprint, String feature) {
        var history = new ArrayList<>(all(host, port, fingerprint).stream().filter(b -> b.feature().equals(feature)).toList());
        if (history.isEmpty()) throw new ApiException(409, "KYLIN_NOT_MANAGED", "没有本工具的变更记录，不能卸载或猜测原始配置");
        Collections.reverse(history);
        Map<String, String> originalFiles = new LinkedHashMap<>(), originalUnits = new LinkedHashMap<>();
        Map<String, String> expectedFiles = new LinkedHashMap<>(), expectedUnits = new LinkedHashMap<>(), sources = new LinkedHashMap<>();
        // 按路径聚合，不能依赖不同操作中的file.N索引相同；缺少任何回执时要求人工核对。
        for (Backup backup : history) {
            if (Files.exists(path(backup.backupId(), ".unchanged"), LinkOption.NOFOLLOW_LINKS)) continue;
            Map<String, String> applied = expected(backup.backupId());
            KylinProbe.validate(applied, backup.paths().size(), backup.units().size());
            for (int i = 0; i < backup.paths().size(); i++) {
                String path = backup.paths().get(i), key = "file." + i;
                originalFiles.putIfAbsent(path, backup.state().get(key));
                sources.putIfAbsent(path, KylinPolicyCatalog.BACKUPS + backup.backupId() + "/file-" + i);
                expectedFiles.put(path, applied.get(key));
            }
            for (int i = 0; i < backup.units().size(); i++) {
                String unit = backup.units().get(i), key = "unit." + i;
                originalUnits.putIfAbsent(unit, backup.state().get(key));
                expectedUnits.put(unit, applied.get(key));
            }
        }
        if (originalFiles.isEmpty() && originalUnits.isEmpty()) throw new ApiException(409, "KYLIN_NOT_MANAGED", "本功能尚无已派发的变更，无需卸载");
        Map<String, String> original = new LinkedHashMap<>(), latest = new LinkedHashMap<>();
        var paths = List.copyOf(originalFiles.keySet()); var units = List.copyOf(originalUnits.keySet());
        for (int i = 0; i < paths.size(); i++) { original.put("file." + i, originalFiles.get(paths.get(i))); latest.put("file." + i, expectedFiles.get(paths.get(i))); }
        for (int i = 0; i < units.size(); i++) { original.put("unit." + i, originalUnits.get(units.get(i))); latest.put("unit." + i, expectedUnits.get(units.get(i))); }
        Backup first = history.getFirst();
        var baseline = new Backup(first.backupId(), host, port, fingerprint, feature, paths, units, Map.copyOf(original), first.createdAt());
        return new RestorePoint(baseline, Map.copyOf(sources), Map.copyOf(latest));
    }
}
