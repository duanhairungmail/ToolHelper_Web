package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.toolhelper.kylin.KylinModels.*;
import static com.toolhelper.kylin.KylinSshClient.quote;

/** 固定key=value协议，状态与命令退出码分开，不能用active子串判断inactive。 */
public final class KylinProbe {
    private static final Logger LOG = LoggerFactory.getLogger(KylinProbe.class);
    private KylinProbe() {}
    public static final String SYSTEM = """
            set -eu
            export LC_ALL=C
            printf 'os='; awk -F= '$1=="ID" {gsub(/\"/,"",$2); print $2}' /etc/os-release
            printf 'version='; awk -F= '$1=="VERSION_ID" {gsub(/\"/,"",$2); print $2}' /etc/os-release
            printf 'arch='; uname -m
            printf 'uid='; id -u
            printf 'timezone='; date +%Z
            printf 'systemd='; if command -v systemctl >/dev/null; then echo yes; else echo no; fi
            printf 'dpkg='; if command -v dpkg-query >/dev/null; then echo yes; else echo no; fi
            printf 'display='; if test -f /etc/X11/default-display-manager; then head -c 256 /etc/X11/default-display-manager; else echo unknown; fi
            """;
    public static Map<String, String> labels(String output) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0 && line.length() <= 4096) result.put(line.substring(0, separator), line.substring(separator + 1).trim());
        }
        return Collections.unmodifiableMap(result);
    }
    public static Map<String, String> system(KylinSshClient ssh) {
        var reply = ssh.run(SYSTEM, false, null, 30, () -> false);
        if (reply.exitCode() != 0) throw new ApiException(502, "KYLIN_SYSTEM_PROBE_FAILED", "无法读取目标系统基本信息");
        Map<String, String> values = labels(reply.output());
        if (!values.keySet().containsAll(List.of("os", "arch", "uid", "systemd")))
            throw new ApiException(502, "KYLIN_SYSTEM_PROBE_INVALID", "系统探测响应不完整");
        return values;
    }
    public static String stateScript(List<String> paths, List<String> units) {
        StringBuilder script = new StringBuilder("set -eu\nexport LC_ALL=C\n");
        int i = 0;
        for (String path : paths) {
            script.append(KylinExecutor.safeDirectory(path.substring(0, path.lastIndexOf('/'))));
            script.append("p=").append(quote(path)).append("\nprintf 'file.").append(i++).append("='\n")
                    .append("if test -L \"$p\"; then echo LINK; elif test -f \"$p\"; then h=$(sha256sum -- \"$p\"); printf '%s|' \"${h%% *}\"; stat -c '%a|%u|%g' -- \"$p\"; elif test -e \"$p\"; then echo OTHER; else echo MISSING; fi\n");
        }
        i = 0;
        for (String unit : units) script.append("u=").append(quote(unit)).append("\nprintf 'unit.").append(i++).append("='\n")
                .append("if load=$(systemctl show -p LoadState --value \"$u\"); then :; elif test \"$load\" != not-found; then exit 1; fi\n")
                .append("if test \"$load\" = not-found; then echo MISSING; else a=$(systemctl is-active \"$u\" || :); e=$(systemctl is-enabled \"$u\" || :); printf '%s|%s|%s\\n' \"$load\" \"$a\" \"$e\"; fi\n");
        return script.toString();
    }
    public static Map<String, String> state(KylinSshClient ssh, List<String> paths, List<String> units,
            boolean sudo, String password, BooleanSupplier cancelled) {
        var result = ssh.run(stateScript(paths, units), sudo, password, 30, cancelled);
        if (result.exitCode() != 0) throw stateFailure(result);
        var values = labels(result.output());
        if (values.size() != paths.size() + units.size()) throw new ApiException(502, "KYLIN_STATE_INCOMPLETE", "目标状态不完整，不能用于变更预览");
        if (values.values().stream().anyMatch(v -> v.equals("LINK") || v.equals("OTHER")))
            throw new ApiException(409, "KYLIN_UNSAFE_FILE", "目标策略文件存在符号链接或非普通文件，未执行变更");
        validate(values, paths.size(), units.size());
        return values;
    }
    static ApiException stateFailure(KylinSshClient.Reply reply) {
        String error = reply.error().toLowerCase(Locale.ROOT);
        String code = "KYLIN_STATE_PROBE_FAILED", message = "目标状态读取失败，退出码：" + reply.exitCode() + "；请核对目标工具、路径及服务环境";
        if (error.contains("error while loading shared libraries") || error.contains("version `") && error.contains("not found")) {
            code = "KYLIN_PROBE_LIBRARY_ERROR"; message = "目标命令加载动态库失败，请核对目标机运行环境与库版本";
        } else if (error.contains("command not found") || error.contains("no such file or directory")) {
            code = "KYLIN_PROBE_TOOL_MISSING"; message = "状态读取所需命令或文件不可用，请核对目标机bash、sha256sum、stat和systemctl";
        } else if (error.contains("failed to connect to bus") || error.contains("system has not been booted with systemd")) {
            code = "KYLIN_PROBE_SYSTEMD_ERROR"; message = "无法查询目标systemd服务状态，请核对系统服务管理环境";
        } else if (error.contains("permission denied")) {
            code = "KYLIN_PROBE_PERMISSION_DENIED"; message = "目标拒绝读取策略文件或服务状态，请核对当前执行权限";
        }
        // 原始stderr可能含目标配置或秘密，只记录白名单类别与退出码。
        LOG.warn("Kylin状态读取失败 code={} exitCode={}", code, reply.exitCode());
        return new ApiException(502, code, message);
    }
    public static void validate(Map<String, String> values, int paths, int units) {
        for (int i = 0; i < paths; i++) if (!values.getOrDefault("file." + i, "").matches("MISSING|[a-f0-9]{64}\\|[0-7]{3,4}\\|[0-9]{1,10}\\|[0-9]{1,10}"))
            throw new ApiException(502, "KYLIN_STATE_INVALID", "文件状态格式不正确，不用于变更命令");
        for (int i = 0; i < units; i++) if (!values.getOrDefault("unit." + i, "").matches("MISSING|[a-z-]+\\|[a-z-]+\\|[a-z-]+"))
            throw new ApiException(502, "KYLIN_STATE_INVALID", "服务状态格式不正确，不用于变更命令");
    }
    public static String revision(Map<String, String> state) { return KylinFiles.hash(state.toString().getBytes(StandardCharsets.UTF_8)); }
    /** 与清理脚本相同的固定范围；仅汇总大小，不把目标文件名拼回shell。 */
    public static String logCandidates(KylinSshClient ssh, Params params, boolean sudo, String password) {
        KylinTemplates.logs(params, false); // 统一保留期边界校验。
        int days = params.fileDays() == null ? 365 : params.fileDays();
        String script = "set -euo pipefail\nexport LC_ALL=C\n"
                + "find -P /var/log -xdev -type f \\( -name '*.gz' -o -name '*.log.[0-9]*' \\) -mtime +" + days
                + " -printf '%s\\n' | awk '{n++; b+=$1} END {printf \"archives=%d|%.0f\\n\", n,b}'\n"
                + "find -P /tmp -xdev -type f -mtime +" + days
                + " -printf '%s\\n' | awk '{n++; b+=$1} END {printf \"temporary=%d|%.0f\\n\", n,b}'\n"
                + "printf 'journal='; journalctl --disk-usage\ndf -Pk /var/log /tmp\n";
        var reply = ssh.run(script, sudo, password, 30, () -> false);
        if (reply.exitCode() != 0) throw new ApiException(409, "LOG_PREVIEW_FAILED", "无法完整统计清理范围，未生成计划");
        var values = labels(reply.output());
        if (!values.getOrDefault("archives", "").matches("[0-9]+\\|[0-9]+") || !values.getOrDefault("temporary", "").matches("[0-9]+\\|[0-9]+"))
            throw new ApiException(502, "LOG_PREVIEW_INVALID", "清理范围统计响应不完整");
        return "当前候选（数量|字节）：/var/log归档 " + values.get("archives") + "；/tmp旧普通文件 " + values.get("temporary")
                + "；journal占用（含不可清理的活跃文件） " + safeDetail(values.getOrDefault("journal", "UNKNOWN"));
    }
    private static String safeDetail(String value) {
        return value.length() <= 256 && value.codePoints().noneMatch(Character::isISOControl) ? value : "输出格式超出预期";
    }
    public static Scan scan(KylinSshClient ssh, String feature, boolean sudo, String password) {
        List<Finding> findings = new ArrayList<>(); Map<String, String> state;
        if (feature.equals("activation")) {
            var reply = ssh.run("kylin_activation_check", sudo, password, 30, () -> false);
            String out = reply.output(); boolean no = out.contains("系统未被激活") || out.contains("系统未激活");
            boolean yes = out.lines().map(String::trim).anyMatch(s -> s.equals("系统激活") || s.contains("系统已激活"));
            String status = reply.exitCode() == 0 && no != yes ? no ? "NOT_ACTIVATED" : "ACTIVATED" : "UNKNOWN";
            findings.add(new Finding("activation", status, "根据激活命令退出码及明确状态判断；不根据服务状态推断授权，不保存原始输出"));
            state = Map.of("activation", status);
        } else if (feature.equals("patch")) {
            return KylinPatch.scan(ssh);
        } else {
            var selected = feature.equals("optimization") ? KylinPolicyCatalog.OPTIMIZATIONS : List.<KylinPolicyCatalog.Optimization>of();
            var paths = KylinPolicyCatalog.paths(feature, selected); var units = KylinPolicyCatalog.units(feature, selected);
            state = state(ssh, paths, units, sudo, password, () -> false);
            for (int i = 0; i < paths.size(); i++) {
                String value = state.get("file." + i);
                findings.add(new Finding(paths.get(i), value.equals("MISSING") ? "MISSING" : "PRESENT", value));
            }
            for (int i = 0; i < units.size(); i++) {
                String value = state.get("unit." + i);
                findings.add(new Finding(units.get(i), value.equals("MISSING") ? "NOT_APPLICABLE" : "OBSERVED", value));
            }
            if (feature.equals("optimization")) for (var item : selected) {
                boolean present = false, compliant = true;
                for (String path : item.files()) {
                    String value = state.get("file." + paths.indexOf(path));
                    if (!value.equals("MISSING")) { present = true; compliant &= (Integer.parseInt(value.split("\\|")[1], 8) & 0111) == 0; }
                }
                for (String unit : item.units()) {
                    String value = state.get("unit." + units.indexOf(unit));
                    if (!value.equals("MISSING")) { present = true; compliant &= value.endsWith("|inactive|masked") || value.endsWith("|inactive|masked-runtime"); }
                }
                findings.add(new Finding(item.id(), item.readOnly() ? "READ_ONLY" : !present ? "NOT_APPLICABLE" : compliant ? "COMPLIANT" : "NOT_COMPLIANT", item.name() + "：" + item.impact()));
            }
        }
        return new Scan(feature, revision(state), List.copyOf(findings), Instant.now());
    }
}
