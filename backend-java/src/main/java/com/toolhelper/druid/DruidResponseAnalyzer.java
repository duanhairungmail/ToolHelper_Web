package com.toolhelper.druid;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.druid.DruidModels.*;

/** 只保留规则白名单字段名和类别，原始HTML/SQL/Session/连接串不进入结果模型。 */
public final class DruidResponseAnalyzer {
    private final JsonMapper json;
    public DruidResponseAnalyzer(JsonMapper json) { this.json = json; }
    public Evidence analyze(DruidRuleCatalog.Rule rule, DruidHttpClient.Reply reply, boolean fingerprint) {
        String text = new String(reply.body(), StandardCharsets.UTF_8), lower = text.toLowerCase(Locale.ROOT);
        if (reply.status() == 429) return evidence(rule, reply, "RATE_LIMITED", "UNRATED", false, false, false, 0, List.of(), "目标限制请求，已停止该目标");
        if (reply.status() == 401) return evidence(rule, reply, "AUTH_REQUIRED", "INFO", false, false, false, 0, List.of(), "当前端点需要认证");
        if (reply.status() == 403) return evidence(rule, reply, "ACCESS_RESTRICTED", "INFO", false, false, false, 0, List.of(), "当前来源被拒绝，不代表其他来源也受限");
        if (reply.status() == 404) return evidence(rule, reply, "PATH_NOT_FOUND", "UNRATED", false, false, false, 0, List.of(), "指定路径未发现，不能据此确认组件未启用");
        if (businessLoginRequired(reply.status(), text)) return evidence(rule, reply, "BUSINESS_AUTH_REQUIRED", "UNRATED",
                false, false, false, 0, List.of(), "业务平台要求登录；当前来源未取得匿名数据，Druid自身鉴权尚未验证");
        boolean html = lower.stripLeading().startsWith("<");
        boolean login = html && (lower.contains("druid stat login") || lower.contains("loginusername") && lower.contains("loginpassword") && lower.contains("druid"));
        if (login && (reply.status() == 200 || Set.of(301, 302, 303, 307, 308).contains(reply.status())))
            return evidence(rule, reply, "AUTH_REQUIRED", "INFO", true, false, false, 0, List.of(), "识别到Druid登录页或其同源跳转，未将页面视为数据访问成功");
        if (reply.status() != 200) return evidence(rule, reply, "HTTP_ERROR", "UNRATED", false, false, false, 0, List.of(), "端点HTTP响应异常");
        if (html && lower.contains("druid") && (lower.contains("not permit") || lower.contains("not permitted") || lower.contains("没有权限")))
            return evidence(rule, reply, "ACCESS_RESTRICTED", "INFO", true, false, false, 0, List.of(), "识别到Druid来源限制页面");
        if (!rule.json() || html) {
            boolean druid = html && (lower.contains("druid stat") || lower.contains("druid monitor"));
            return evidence(rule, reply, druid ? "PAGE_IDENTIFIED" : "UNKNOWN_RESPONSE", druid ? "INFO" : "UNRATED",
                    druid, false, false, 0, List.of(), druid ? "仅识别页面外壳；数据暴露需独立JSON证据" : "未识别为已适配的Druid页面或JSON");
        }
        try {
            if (!boundedJson(text)) return unknown(rule, reply);
            JsonNode body = json.readTree(text);
            if (body == null || !body.isObject() || !body.path("ResultCode").isIntegralNumber() || body.path("ResultCode").asInt() != 1 || !body.has("Content")) return unknown(rule, reply);
            JsonNode content = body.path("Content");
            if (!content.isNull() && !content.isArray() && !content.isObject()) return unknown(rule, reply);
            List<JsonNode> samples = new ArrayList<>();
            if (content.isObject()) samples.add(content);
            else if (content.isArray()) { for (JsonNode row : content) { if (samples.size() >= 10) break; if (!row.isObject()) return unknown(rule, reply); samples.add(row); } }
            boolean empty = samples.isEmpty() || samples.stream().allMatch(JsonNode::isEmpty);
            List<String> fields = rule.fields().stream().filter(key -> samples.stream().anyMatch(row -> row.has(key))).toList();
            boolean signature = fields.size() >= 2;
            if (!signature && !(empty && fingerprint)) return unknown(rule, reply);
            boolean sensitive = signature && rule.sensitive().stream().anyMatch(key -> samples.stream().anyMatch(row -> row.has(key) && !row.path(key).isNull() && !row.path(key).asText().isBlank()));
            return evidence(rule, reply, "ANONYMOUS_JSON", sensitive ? "HIGH" : "MEDIUM", signature || fingerprint,
                    true, empty, samples.size(), fields, empty ? "匿名可访问；本轮未观察到记录，不代表端点受保护"
                    : sensitive ? "匿名样本包含敏感字段类别，原值未保存；样本数不是总泄露量" : "匿名取得已适配监控结构；不据此推断存在数据库密码");
        } catch (RuntimeException failure) { return unknown(rule, reply); }
    }
    private Evidence unknown(DruidRuleCatalog.Rule rule, DruidHttpClient.Reply reply) {
        return evidence(rule, reply, "UNKNOWN_RESPONSE", "UNRATED", false, false, false, 0, List.of(), "不是已适配的Druid响应结构，不能判为安全或暴露");
    }
    /** 仅识别阶段3已实测的完整拦截签名；普通402或其他业务错误不能推断为登录保护。 */
    private boolean businessLoginRequired(int status, String text) {
        if (status != 402 || !boundedJson(text)) return false;
        try {
            JsonNode body = json.readTree(text);
            return body != null && body.isObject() && body.path("code").isIntegralNumber()
                    && body.path("code").asInt() == 402 && body.path("success").isBoolean()
                    && !body.path("success").asBoolean() && body.path("msg").asText().equals("请登录系统再操作！");
        } catch (RuntimeException invalid) { return false; }
    }
    private static Evidence evidence(DruidRuleCatalog.Rule rule, DruidHttpClient.Reply reply, String status, String severity,
            boolean identified, boolean accessible, boolean empty, int samples, List<String> fields, String message) {
        return new Evidence(rule.id(), rule.path(), "GET", reply.status(), status, severity, identified, accessible, empty,
                samples, fields, message, reply.elapsedMs(), Instant.now(), DruidRetest.endpoint(status, rule.json()));
    }
    public static Evidence failure(DruidRuleCatalog.Rule rule, String code) {
        return new Evidence(rule.id(), rule.path(), "GET", 0, code, "UNRATED", false, false, false, 0,
                List.of(), "检查未完成：" + code, 0, Instant.now(), DruidRetest.endpoint(code, rule.json()));
    }
    public static Evidence ambiguous(Evidence e) {
        return new Evidence(e.ruleId(), e.path(), e.method(), e.httpStatus(), "AMBIGUOUS_RESPONSE", "UNRATED", false,
                false, false, 0, List.of(), "不同JSON规则返回相同非空正文，无法排除通用路由响应", e.elapsedMs(), e.checkedAt(), DruidRetest.endpoint("AMBIGUOUS_RESPONSE", true));
    }
    /** 先用有界扫描约束深度/长字符串，避免解析失败把原始正文写入日志。 */
    private static boolean boundedJson(String text) {
        int depth = 0, length = 0; boolean string = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (++length > 65536) return false;
                if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false;
            } else if (c == '"') { string = true; length = 0; }
            else if (c == '{' || c == '[') { if (++depth > 64) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        }
        return !string && depth == 0;
    }
}
