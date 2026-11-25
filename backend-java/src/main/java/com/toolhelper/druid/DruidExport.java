package com.toolhelper.druid;

import com.toolhelper.support.*;
import java.util.*;
import static com.toolhelper.druid.DruidModels.*;

public final class DruidExport {
    private DruidExport() {}
    public static final List<String> KEYS = List.of("index", "target", "retest", "identification", "accessStatus", "severity", "executionStatus", "progress", "credentialStatus", "elapsedMs", "checkedAt", "ruleVersion");
    public static final List<String> TITLES = List.of("序号", "目标", "历史漏洞复测结论", "识别结果", "访问状态", "规则风险", "执行状态", "完成规则", "凭据验证", "耗时ms", "检测时间", "规则版本");
    public static byte[] generate(ExportRequest request, List<TargetResult> available) {
        if (request.columns() == null || request.columns().isEmpty() || !request.columns().contains("index")
                || !KEYS.containsAll(request.columns()) || request.columns().size() != new HashSet<>(request.columns()).size()
                || request.columns().size() > KEYS.size()) throw new ApiException(400, "INVALID_EXPORT_COLUMNS", "请选择有效列，序号必选");
        if (request.targetIds() == null || request.targetIds().isEmpty() || request.targetIds().size() > 100
                || request.targetIds().size() != new HashSet<>(request.targetIds()).size()) throw new ApiException(400, "INVALID_EXPORT_TARGETS", "请选择要导出的目标");
        Set<String> ids = new HashSet<>(request.targetIds());
        List<TargetResult> records = available.stream().filter(r -> ids.contains(r.targetId())).toList();
        if (records.size() != ids.size()) throw new ApiException(409, "DRUID_RESULT_CHANGED", "所选结果不存在或已过期，请刷新");
        List<List<String>> rows = new ArrayList<>(), details = new ArrayList<>();
        rows.add(request.columns().stream().map(k -> TITLES.get(KEYS.indexOf(k))).toList());
        if (Boolean.TRUE.equals(request.details())) details.add(List.of("目标", "规则", "路径", "HTTP", "复测结论", "响应分类", "风险", "字段类别", "样本条数", "证据说明"));
        int index = 0;
        for (var row : records) {
            List<String> values = List.of("" + ++index, row.target(), row.retest().conclusion(), label(row.identification()), label(row.accessStatus()), label(row.severity()),
                    label(row.executionStatus()), row.completedRules() + "/" + row.totalRules(), label(row.credentialStatus()),
                    "" + row.elapsedMs(), row.checkedAt().toString(), DruidRuleCatalog.VERSION);
            rows.add(request.columns().stream().map(k -> values.get(KEYS.indexOf(k))).toList());
            if (Boolean.TRUE.equals(request.details())) for (var e : row.evidence()) details.add(List.of(row.target(), e.ruleId(), e.path(),
                    "" + e.httpStatus(), e.retest().conclusion(), label(e.status()), label(e.severity()), String.join(", ", e.fields()), "" + e.sampleCount(), e.message()));
        }
        int[] widths = new int[request.columns().size()]; Arrays.fill(widths, 15398 / widths.length); widths[widths.length - 1] += 15398 % widths.length;
        return TableExport.report("Druid检测结果", request.format(), rows, widths, details, DruidRuleCatalog.REMEDIATION);
    }
    public static String label(String value) {
        return switch (value) {
            case "IDENTIFIED" -> "已识别"; case "UNIDENTIFIED" -> "未识别";
            case "ANONYMOUS_ACCESS", "ANONYMOUS_JSON" -> "匿名可访问"; case "AUTH_REQUIRED" -> "当前端点需登录";
            case "ACCESS_RESTRICTED" -> "当前来源受限"; case "PAGE_ONLY", "PAGE_IDENTIFIED" -> "仅识别页面";
            case "BUSINESS_AUTH_REQUIRED" -> "业务登录拦截，Druid鉴权未验证";
            case "HIGH" -> "高"; case "MEDIUM" -> "中"; case "INFO" -> "提示"; case "UNRATED" -> "未定级";
            case "COMPLETED" -> "处理结束"; case "PARTIAL" -> "部分完成"; case "CANCELLED" -> "已取消";
            case "VERIFIED" -> "指定凭据验证通过"; case "REJECTED" -> "指定凭据被拒绝";
            case "NOT_REQUESTED" -> "未请求"; case "NOT_EXECUTED" -> "未执行";
            case "UNKNOWN", "UNKNOWN_RESPONSE" -> "无法判断"; case "PATH_NOT_FOUND" -> "指定路径未发现";
            case "AMBIGUOUS_RESPONSE" -> "通用响应，无法判断";
            default -> value;
        };
    }
}
