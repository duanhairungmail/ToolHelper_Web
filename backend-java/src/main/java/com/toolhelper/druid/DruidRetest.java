package com.toolhelper.druid;

import java.util.List;
import static com.toolhelper.druid.DruidModels.*;

/** 历史未授权漏洞的统一复测口径；页面、API和报告使用同一份结论。 */
public final class DruidRetest {
    private DruidRetest() {}

    public static Retest endpoint(String status, boolean jsonRule) {
        return switch (status) {
            case "ANONYMOUS_JSON" -> new Retest("DATA_EXPOSED", "仍存在匿名监控接口暴露");
            case "PAGE_IDENTIFIED" -> jsonRule
                    ? new Retest("INCONCLUSIVE", "返回监控页面，尚不能判断该数据接口")
                    : new Retest("PAGE_EXPOSED", "仍存在监控页面匿名暴露，数据暴露需看JSON证据");
            case "AUTH_REQUIRED", "ACCESS_RESTRICTED", "BUSINESS_AUTH_REQUIRED" ->
                    new Retest("BLOCKED", "本次未复现：当前来源的匿名访问已拦截");
            case "PATH_NOT_FOUND" -> new Retest("PATH_NOT_FOUND", "本次路径未发现，不能直接确认已修复");
            default -> new Retest("INCONCLUSIVE", "无法判断，需核对未完成或未识别的响应");
        };
    }

    public static Retest summarize(List<Evidence> evidence) {
        // 暴露证据优先于其他端点的拦截或失败；仅全部规则明确拦截时才给出未复现。
        for (String status : List.of("DATA_EXPOSED", "PAGE_EXPOSED")) {
            if (evidence.stream().anyMatch(e -> e.retest().status().equals(status)))
                return new Retest(status, status.equals("DATA_EXPOSED")
                        ? "仍存在未授权暴露：匿名取得监控接口数据"
                        : "仍存在监控页面匿名暴露，尚未确认数据泄露");
        }
        if (evidence.size() == DruidRuleCatalog.ALL.size()
                && evidence.stream().allMatch(e -> e.retest().status().equals("BLOCKED")))
            return new Retest("BLOCKED", "本次未复现：全部检查端点的匿名访问已拦截");
        if (evidence.size() == DruidRuleCatalog.ALL.size()
                && evidence.stream().allMatch(e -> e.retest().status().equals("PATH_NOT_FOUND")))
            return new Retest("PATH_NOT_FOUND", "本次检查路径均未发现，不能直接确认已修复");
        return new Retest("INCONCLUSIVE", "无法完整判断，部分端点未确认访问控制状态");
    }
}
