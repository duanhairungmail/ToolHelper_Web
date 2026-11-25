package com.toolhelper.devicemac;

import com.toolhelper.config.DeviceMacProperties;
import com.toolhelper.plugin.common.LocalDataRoot;
import com.toolhelper.support.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.devicemac.DeviceMacModels.*;

@Component
public class DeviceMacStore {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceMacStore.class);
    private final Path file;
    private final JsonFiles files;
    private final JsonMapper json;
    private final DeviceMacProperties properties;
    public DeviceMacStore(LocalDataRoot root, JsonFiles files, JsonMapper json, DeviceMacProperties properties) {
        this.file = root.root().resolve("device-mac/records.json"); this.files = files; this.json = json; this.properties = properties;
    }
    public synchronized Ledger read() {
        try {
            SecurePaths.checkAncestors(file);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Ledger(1, "initial", null, List.of());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > properties.maxFileBytes()) throw new IOException("台账文件类型或大小异常");
            Ledger ledger = files.read(file, Ledger.class);
            if (ledger == null || ledger.schemaVersion() != 1 || ledger.revision() == null || ledger.revision().isBlank() || ledger.savedAt() == null)
                throw new IOException("台账版本或元数据异常");
            return new Ledger(1, ledger.revision(), ledger.savedAt(), validate(ledger.records()));
        } catch (IOException | RuntimeException error) {
            LOG.warn("MAC台账读取失败 type={}", error.getClass().getSimpleName());
            throw new ApiException(500, "LEDGER_READ_FAILED", "台账损坏、版本不支持或不可读取；不会覆盖原文件");
        }
    }
    public synchronized Ledger save(SaveRequest request) {
        if (request == null || request.expectedRevision() == null) throw new ApiException(400, "INVALID_REQUEST", "保存需要台账版本");
        List<DeviceMacModels.Record> records = validate(request.records());
        Ledger current = read();
        if (!current.revision().equals(request.expectedRevision())) throw new ApiException(409, "LEDGER_VERSION_CONFLICT", "台账已有更新，请处理草稿后重新加载");
        Ledger next = new Ledger(1, UUID.randomUUID().toString(), Instant.now(), records);
        if (json.writeValueAsBytes(next).length > properties.maxFileBytes()) throw new ApiException(413, "LEDGER_TOO_LARGE", "台账文件超出大小限制");
        try { files.write(file, next); }
        catch (IOException | RuntimeException error) {
            LOG.warn("MAC台账保存失败 type={}", error.getClass().getSimpleName());
            throw new ApiException(500, "LEDGER_WRITE_FAILED", "台账未保存，请保留当前草稿并检查数据目录");
        }
        LOG.info("MAC台账保存 records={} revision={}", records.size(), next.revision());
        return next;
    }
    /** 空白/非法 IP 可作为待修订台账保存，探测入口才严格校验，避免一行输入阻断全部草稿。 */
    public List<DeviceMacModels.Record> validate(List<DeviceMacModels.Record> records) {
        if (records == null || records.size() > properties.maxRows()) throw new ApiException(400, "INVALID_RECORDS", "台账行数不正确或超限");
        Set<String> ids = new HashSet<>(); List<DeviceMacModels.Record> clean = new ArrayList<>();
        for (var row : records) {
            if (row == null || !uuid(row.recordId()) || !ids.add(row.recordId())) throw new ApiException(400, "INVALID_RECORDS", "记录标识必须为不重复的 UUID");
            Observation observation = row.lastObservation();
            if (observation != null) {
                String ip = MacValues.ipv4(observation.ipAddress());
                if (!Set.of("MAPPING_FOUND", "CACHED_MAPPING").contains(text(observation.status()))
                        || !Set.of("LOCAL_INTERFACE", "WINDOWS_NEIGHBOR").contains(text(observation.source()))
                        || !Set.of("Local", "Reachable", "Stale", "Delay", "Probe", "Permanent").contains(text(observation.neighborState()))
                        || observation.interfaceIndex() <= 0 || observation.checkedAt() == null)
                    throw new ApiException(400, "INVALID_OBSERVATION", "MAC 观察值字段不正确");
                observation = new Observation(ip, MacValues.mac(observation.macAddress()), observation.status(), observation.source(),
                        observation.neighborState(), observation.interfaceIndex(), observation.checkedAt());
            }
            clean.add(new DeviceMacModels.Record(row.recordId(), text(row.stationName()), text(row.ipAddress()), text(row.deviceName()),
                    text(row.deviceModel()), text(row.serialNumber()), text(row.location()), observation));
        }
        return List.copyOf(clean);
    }
    private String text(String value) {
        if (value == null) return "";
        if (value.length() > properties.maxFieldCharacters() || value.codePoints().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13))
            throw new ApiException(400, "INVALID_FIELD", "台账字段过长或含不支持的控制字符");
        return value;
    }
    public static boolean uuid(String id) {
        if (id == null) return false;
        try { return UUID.fromString(id).toString().equals(id); } catch (IllegalArgumentException error) { return false; }
    }
}
