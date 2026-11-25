package com.toolhelper.devicemac;

import com.toolhelper.support.*;
import java.util.*;
import static com.toolhelper.devicemac.DeviceMacModels.*;

public final class DeviceMacExport {
    private DeviceMacExport() {}
    private static final List<String> KEYS = List.of("index", "stationName", "ipAddress", "deviceName", "deviceModel", "serialNumber", "macAddress", "location", "status", "checkedAt");
    private static final List<String> TITLES = List.of("序号", "站名", "IP地址", "设备名", "设备型号", "设备序列号", "设备MAC地址", "安装位置", "观察状态", "查询时间");
    public static byte[] generate(ExportRequest request, DeviceMacStore store) {
        if (request == null || request.columns() == null || request.columns().isEmpty() || request.columns().size() > KEYS.size()
                || !request.columns().contains("index") || !KEYS.containsAll(request.columns()) || new HashSet<>(request.columns()).size() != request.columns().size())
            throw new ApiException(400, "INVALID_EXPORT_COLUMNS", "请选择有效导出列，序号必选");
        var records = store.validate(request.records());
        List<List<String>> table = new ArrayList<>();
        table.add(request.columns().stream().map(k -> TITLES.get(KEYS.indexOf(k))).toList());
        int index = 0;
        for (var row : records) {
            Observation o = row.lastObservation();
            boolean current = o != null && o.ipAddress().equals(row.ipAddress().trim());
            List<String> values = List.of("" + ++index, row.stationName(), row.ipAddress(), row.deviceName(), row.deviceModel(), row.serialNumber(),
                    current ? o.macAddress() : "", row.location(), current ? (o.status().equals("CACHED_MAPPING") ? "缓存映射" : "已观察映射") : "未查询",
                    current ? o.checkedAt().toString() : "");
            table.add(request.columns().stream().map(k -> values.get(KEYS.indexOf(k))).toList());
        }
        int[] widths = new int[request.columns().size()];
        Arrays.fill(widths, 15398 / widths.length); widths[widths.length - 1] += 15398 % widths.length;
        return TableExport.generate("设备MAC台账", request.format(), table, widths);
    }
}
