package com.toolhelper.druid;

import com.toolhelper.config.DruidScanProperties;
import com.toolhelper.support.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.druid.DruidModels.*;

public final class DruidScanner {
    /** 非record，不生成含密码的toString；运行后清除引用，不进入结果/任务公开模型。 */
    public static final class Secret {
        private String username, password;
        public Secret(String username, String password) { this.username = username; this.password = password; }
        public void clear() { username = null; password = null; }
        @Override public String toString() { return "[redacted]"; }
    }
    private final DruidScanProperties properties;
    private final DruidResponseAnalyzer analyzer;
    public DruidScanner(DruidScanProperties properties, JsonMapper json) {
        this.properties = properties; analyzer = new DruidResponseAnalyzer(json);
    }
    public TargetResult scan(String id, URI root, BooleanSupplier cancelled, Secret secret) {
        long start = System.nanoTime(), deadline = start + TimeUnit.SECONDS.toNanos(properties.targetSeconds());
        List<Evidence> evidence = new ArrayList<>();
        String execution = "COMPLETED", credentialStatus = secret == null ? "NOT_REQUESTED" : "NOT_EXECUTED";
        int requests = 0, completed = 0;
        try {
            if (cancelled.getAsBoolean()) throw new DruidHttpClient.Failure("CANCELLED");
            DruidTargetPolicy.checkAddresses(root);
            Map<String, List<Integer>> digests = new HashMap<>();
            try (var client = new DruidHttpClient(root, properties, cancelled, deadline, false)) {
                for (var rule : DruidRuleCatalog.ALL) {
                    try {
                        var reply = client.get(rule);
                        boolean fingerprint = evidence.stream().anyMatch(Evidence::identified);
                        Evidence item = analyzer.analyze(rule, reply, fingerprint);
                        // 同一通用JSON落到多个不同端点时保守处理，不把伪造通用路由当成多个暴露证据。
                        if (rule.json() && item.jsonAccessible() && !item.empty()) {
                            String digest = digest(reply.body());
                            var matches = digests.computeIfAbsent(digest, ignored -> new ArrayList<>());
                            if (!matches.isEmpty()) {
                                for (int previous : matches) evidence.set(previous, DruidResponseAnalyzer.ambiguous(evidence.get(previous)));
                                item = DruidResponseAnalyzer.ambiguous(item);
                            }
                            matches.add(evidence.size());
                        }
                        evidence.add(item); completed++;
                        if (reply.status() == 429) { execution = "PARTIAL"; break; }
                    } catch (DruidHttpClient.Failure failure) {
                        evidence.add(DruidResponseAnalyzer.failure(rule, failure.code));
                        execution = failure.code.equals("CANCELLED") ? "CANCELLED" : "PARTIAL";
                        // 超时等目标异常不额外自动重试；停止剩余规则，避免重复消耗请求。
                        break;
                    }
                }
                requests = client.requests();
            }
            if (secret != null && execution.equals("COMPLETED")) {
                var protectedRule = evidence.stream().filter(e -> e.status().equals("AUTH_REQUIRED"))
                        .map(e -> DruidRuleCatalog.ALL.stream().filter(r -> r.id().equals(e.ruleId()) && r.json()).findFirst().orElse(null))
                        .filter(Objects::nonNull).findFirst();
                if (protectedRule.isEmpty() || evidence.stream().noneMatch(Evidence::identified)) credentialStatus = "SKIPPED_NO_SUPPORTED_LOGIN";
                else {
                    try (var client = new DruidHttpClient(root, properties, cancelled, deadline, true)) {
                        client.used(requests);
                        try {
                            var reply = client.login(secret.username, secret.password);
                            String body = new String(reply.body(), StandardCharsets.UTF_8).trim();
                            if (reply.status() == 429) { credentialStatus = "RATE_LIMITED"; execution = "PARTIAL"; }
                            else if (reply.status() == 200 && body.equals("error")) credentialStatus = "REJECTED";
                            else if (reply.status() == 200 && body.equals("success") && client.hasCookie()) {
                                var verified = analyzer.analyze(protectedRule.get(), client.get(protectedRule.get()), true);
                                credentialStatus = verified.jsonAccessible() ? "VERIFIED" : "UNCONFIRMED";
                            } else credentialStatus = "UNSUPPORTED_RESPONSE";
                        } catch (DruidHttpClient.Failure failure) {
                            credentialStatus = failure.code; execution = failure.code.equals("CANCELLED") ? "CANCELLED" : "PARTIAL";
                        } finally { requests = client.requests(); }
                    }
                }
            }
        } catch (UnknownHostException failure) { execution = "DNS_ERROR"; }
        catch (ApiException failure) { execution = failure.code(); }
        catch (DruidHttpClient.Failure failure) { execution = failure.code; }
        finally { if (secret != null) secret.clear(); }
        if (cancelled.getAsBoolean()) execution = "CANCELLED";
        // 未执行项必须显式保留，不能以COMPLETED掩盖截止时间或取消后的缺失检查。
        Set<String> seen = new HashSet<>(); evidence.forEach(e -> seen.add(e.ruleId()));
        for (var rule : DruidRuleCatalog.ALL) if (!seen.contains(rule.id())) evidence.add(DruidResponseAnalyzer.failure(rule,
                execution.equals("CANCELLED") ? "CANCELLED" : "NOT_EXECUTED"));
        boolean identified = evidence.stream().anyMatch(Evidence::identified);
        String access = evidence.stream().anyMatch(Evidence::jsonAccessible) ? "ANONYMOUS_ACCESS"
                : evidence.stream().anyMatch(e -> e.status().equals("AUTH_REQUIRED")) ? "AUTH_REQUIRED"
                : evidence.stream().anyMatch(e -> e.status().equals("ACCESS_RESTRICTED")) ? "ACCESS_RESTRICTED"
                : evidence.stream().anyMatch(e -> e.status().equals("BUSINESS_AUTH_REQUIRED")) ? "BUSINESS_AUTH_REQUIRED"
                : identified ? "PAGE_ONLY" : "UNKNOWN";
        String severity = evidence.stream().anyMatch(e -> e.severity().equals("HIGH")) ? "HIGH"
                : evidence.stream().anyMatch(e -> e.severity().equals("MEDIUM")) ? "MEDIUM"
                : identified || access.equals("ACCESS_RESTRICTED") || access.equals("AUTH_REQUIRED") ? "INFO" : "UNRATED";
        return new TargetResult(id, root.toString(), execution, identified ? "IDENTIFIED" : "UNIDENTIFIED", access, severity,
                completed, DruidRuleCatalog.ALL.size(), requests, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Instant.now(),
                credentialStatus, "DIRECT · TLS校验开启 · 复测结论仅代表本来源、本时刻的匿名访问；拦截不等于整体安全或Druid自身鉴权配置已修复；未保存敏感原值",
                List.copyOf(evidence), DruidRetest.summarize(evidence));
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256不可用"); }
    }
}
