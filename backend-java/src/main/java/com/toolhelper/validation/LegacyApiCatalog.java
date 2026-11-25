package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.util.*;

public final class LegacyApiCatalog {
    private LegacyApiCatalog() {}
    public record Endpoint(String id, String name, String method, String path, boolean sideEffect) {
        public boolean needsDevice() { return path.contains("{deviceId}"); }
    }
    private static Endpoint get(String id, String name, String path) { return new Endpoint(id, name, "GET", path, false); }
    private static Endpoint post(String id, String name, String path) { return new Endpoint(id, name, "POST", path, true); }
    public static final List<Endpoint> ALL = List.of(
        get("devices", "设备列表", "/device/deviceinfo/list?deviceName=&Address=&pageSize=20&page=1"),
        get("summary", "设备状态统计", "/api/getIndexDeviceSummaryList"),
        get("io-status", "接点状态查询", "/index/getIOLastStatusData"),
        get("last-data", "即时数据", "/index/getDeviceLastData?device={deviceId}"),
        get("history", "历史数据", "/index/getDeviceHistoryDataByIdList?device={deviceId}&paramName=smokeline&begin=&end="),
        get("cable", "感温电缆终端", "/device/deviceinfo/getCableInfo?device={deviceId}"),
        get("meter-last", "电表即时数据", "/index/getMeterLastData"),
        get("meter-history-ac", "电表历史数据（交流）", "/index/getDeviceHistoryDataByIdList?deviceType=ac&pageSize=20&page=1&begin=&end=&sort=dataTime&direction=desc"),
        get("meter-history-dc", "电表历史数据（直流）", "/index/getDeviceHistoryDataByIdList?deviceType=dc&pageSize=20&page=1&begin=&end=&sort=dataTime&direction=desc"),
        get("alarms", "即时告警", "/index/getDeviceAlarmList"),
        get("device-alarms", "设备历史告警", "/index/getAlramHistoryList?device={deviceId}&begin=&end=&pageSize=20&page=1"),
        get("meter-alarms-ac", "电表历史告警（交流）", "/index/getMeterAlramHistoryList?deviceType=ac&pageSize=20&page=1&begin=&end=&sort=dataTime&direction=desc"),
        get("meter-alarms-dc", "电表历史告警（直流）", "/index/getMeterAlramHistoryList?deviceType=dc&pageSize=20&page=1&begin=&end=&sort=dataTime&direction=desc"),
        post("reset-alarm", "设备复位", "/index/resetAlarm"),
        post("write-device", "设备参数设置", "/index/writeDeviceData"),
        post("reset-meter", "电表复位", "/index/resetMeterAlarm")
    );
    public static List<Endpoint> select(List<String> ids, boolean automatic, boolean confirmed) {
        if (ids == null || ids.isEmpty() || ids.size() > ALL.size() || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> ALL.stream().noneMatch(item -> item.id().equals(id)))) {
            throw new ApiException(400, "INVALID_ENDPOINT_SELECTION", "请选择固定目录中的接口，不得重复或提交任意 URL");
        }
        List<Endpoint> selected = ALL.stream().filter(item -> ids.contains(item.id())).toList();
        if (selected.stream().anyMatch(Endpoint::sideEffect) && (automatic || !confirmed)) {
            throw new ApiException(400, "SIDE_EFFECT_CONFIRMATION_REQUIRED", automatic
                    ? "自动检测只允许只读 GET 接口" : "复位和参数写入可能影响设备，必须再次明确确认");
        }
        return selected;
    }
}
