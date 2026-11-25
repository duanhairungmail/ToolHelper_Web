package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import static com.toolhelper.kylin.KylinModels.*;
import static com.toolhelper.kylin.KylinSshClient.quote;

/** 用户指定的固定补丁：只读比对、一次性确认、私有上传及安装后核验，不做软件包回滚。 */
public final class KylinPatch {
    private KylinPatch() {}
    public static final String PACKAGE = "kylin-offline-upgrade";
    public static final String VERSION = "1.0.0.33-0k1.11";
    public static final String FILE = PACKAGE + "_" + VERSION + "_amd64.deb";
    public static final String SHA256 = "92fbdd5421a9a0783c123a30005f87f007feff0f3a5b45424ebcb32823e7fdc8";
    private static final String UNIT = PACKAGE + ".service";
    private static final List<String> KEYS = List.of("os", "version", "arch", "package.status", "package.version", "package.arch", "comparison", "audit", "unit.0");
    public static Map<String, String> metadata() {
        return Map.of("package", PACKAGE, "version", VERSION, "architecture", "amd64", "file", FILE, "sha256", SHA256,
                "basis", "USER_PROVIDED_PATCH", "serviceImpact", "安装脚本会启用并重启kylin-offline-upgrade.service");
    }
    /** dpkg负责版本排序，不能用字符串排序或把查询失败当成未安装。 */
    static String stateScript() {
        return """
                set -eu
                export LC_ALL=C
                for tool in dpkg dpkg-query sha256sum stat systemctl; do command -v "$tool" >/dev/null; done
                printf 'os='; awk -F= '$1=="ID" {gsub(/"/,"",$2); print $2}' /etc/os-release
                printf 'version='; awk -F= '$1=="VERSION_ID" {gsub(/"/,"",$2); print $2}' /etc/os-release
                printf 'arch='; dpkg --print-architecture
                if row=$(dpkg-query -W -f='${Status}|${Version}|${Architecture}' kylin-offline-upgrade 2>&1); then
                    IFS='|' read -r status version architecture <<EOF
                $row
                EOF
                    printf 'package.status=%s\npackage.version=%s\npackage.arch=%s\n' "$status" "$version" "$architecture"
                    if dpkg --compare-versions "$version" lt '1.0.0.33-0k1.11'; then echo comparison=OLDER
                    elif dpkg --compare-versions "$version" eq '1.0.0.33-0k1.11'; then echo comparison=EQUAL
                    elif dpkg --compare-versions "$version" gt '1.0.0.33-0k1.11'; then echo comparison=NEWER
                    else exit 1; fi
                else
                    case "$row" in
                        *'no packages found matching kylin-offline-upgrade'*) printf 'package.status=MISSING\npackage.version=NONE\npackage.arch=NONE\ncomparison=NOT_INSTALLED\n' ;;
                        *) exit 1 ;;
                    esac
                fi
                if audit=$(dpkg --audit kylin-offline-upgrade); then
                    if test -z "$audit"; then echo audit=CLEAN; else echo audit=DIRTY; fi
                else exit 1; fi
                """ + KylinProbe.stateScript(List.of(), List.of(UNIT));
    }
    static Map<String, String> state(KylinSshClient ssh, boolean sudo, String password, BooleanSupplier cancelled) {
        var reply = ssh.run(stateScript(), sudo, password, 30, cancelled);
        if (reply.exitCode() != 0) throw new ApiException(502, "KYLIN_PATCH_QUERY_FAILED", "补丁状态查询失败，请核对目标dpkg、组件和systemd环境；不能据此判断未安装");
        return parseState(reply.output());
    }
    static Map<String, String> parseState(String output) {
        var state = KylinProbe.labels(output);
        if (state.size() != KEYS.size() || !state.keySet().containsAll(KEYS)
                || !state.get("os").matches("[a-zA-Z0-9_.-]{1,64}") || !state.get("version").matches("[a-zA-Z0-9_.-]{1,64}")
                || !state.get("arch").matches("[a-z0-9-]{1,32}")
                || !state.get("package.version").matches("NONE|[0-9][a-zA-Z0-9.+:~\\-]{0,127}")
                || !state.get("package.arch").matches("NONE|[a-z0-9-]{1,32}")
                || !state.get("package.status").matches("MISSING|(?:unknown|install|hold|deinstall|purge) (?:ok|reinstreq) (?:not-installed|config-files|half-installed|unpacked|half-configured|triggers-awaited|triggers-pending|installed)")
                || !Set.of("OLDER", "EQUAL", "NEWER", "NOT_INSTALLED").contains(state.get("comparison"))
                || !Set.of("CLEAN", "DIRTY").contains(state.get("audit")))
            throw new ApiException(502, "KYLIN_PATCH_STATE_INVALID", "补丁状态响应不完整或格式异常，未生成安装结论");
        KylinProbe.validate(Map.of("unit.0", state.get("unit.0")), 0, 1);
        boolean missing = state.get("package.status").equals("MISSING");
        if (missing != state.get("package.version").equals("NONE") || missing != state.get("package.arch").equals("NONE")
                || missing != state.get("comparison").equals("NOT_INSTALLED"))
            throw new ApiException(502, "KYLIN_PATCH_STATE_INVALID", "补丁包状态与版本响应矛盾");
        return state;
    }
    static boolean applicable(Map<String, String> state) {
        return state.get("os").toLowerCase(Locale.ROOT).contains("kylin")
                && state.get("version").equalsIgnoreCase("v10") && state.get("arch").equals("amd64");
    }
    static boolean installed(Map<String, String> state) {
        return Set.of("install ok installed", "hold ok installed").contains(state.get("package.status"))
                && state.get("package.arch").equals("amd64") && state.get("audit").equals("CLEAN");
    }
    static void requireUpgrade(Map<String, String> state) {
        if (!applicable(state)) throw new ApiException(409, "KYLIN_PATCH_NOT_APPLICABLE", "该补丁仅适配Kylin V10 amd64，目标系统或架构不匹配");
        if (state.get("package.status").equals("MISSING") || state.get("package.status").endsWith("config-files") || state.get("package.status").endsWith("not-installed"))
            throw new ApiException(409, "KYLIN_PATCH_NOT_INSTALLED", "目标组件未安装，不执行补丁全新安装");
        if (!installed(state)) throw new ApiException(409, "KYLIN_PATCH_PACKAGE_UNHEALTHY", "目标组件未完整配置、存在审计异常或包架构不匹配，请先处理原安装状态");
        if (state.get("package.status").startsWith("hold ")) throw new ApiException(409, "KYLIN_PATCH_PACKAGE_HELD", "目标组件已被管理员标记hold，未绕过锁定安装补丁");
        if (!state.get("comparison").equals("OLDER")) throw new ApiException(409, "KYLIN_PATCH_ALREADY_CURRENT", "目标已达到或高于提供的补丁版本，无需重复安装；不会降级");
    }
    public static Scan scan(KylinSshClient ssh) {
        var state = state(ssh, false, null, () -> false);
        String status = !applicable(state) ? "NOT_APPLICABLE" : state.get("package.status").equals("MISSING")
                || state.get("package.status").endsWith("config-files") || state.get("package.status").endsWith("not-installed") ? "NOT_INSTALLED"
                : !installed(state) ? "PACKAGE_UNHEALTHY" : state.get("comparison").equals("OLDER") ? "PATCH_REQUIRED" : "PATCH_VERSION_REACHED";
        var findings = List.of(
                new Finding(PACKAGE, status, "包状态：" + state.get("package.status") + "；当前版本：" + state.get("package.version") + "；架构：" + state.get("package.arch") + "；提供的补丁版本：" + VERSION),
                new Finding(UNIT, "OBSERVED", state.get("unit.0")),
                new Finding("patch-verification", status.equals("PATCH_VERSION_REACHED") && state.get("unit.0").equals("loaded|active|enabled") ? "PATCH_VERIFIED" : "PATCH_NOT_VERIFIED",
                        "验证要求：达到提供的补丁版本、包完整配置且审计正常、升级服务运行并自启；服务状态：" + state.get("unit.0")),
                new Finding("patch-basis", "USER_PROVIDED_PATCH", "按用户提供的固定补丁比较版本，不使用漏洞利用探测；尚无独立厂商公告/CVE映射，不代表其他漏洞均已修复。SHA256：" + SHA256));
        return new Scan("patch", KylinProbe.revision(state), findings, Instant.now());
    }
    static KylinPlanService.Prepared prepare(Connection connection, KylinSshClient ssh, boolean sudo, PlanRequest request) {
        KylinTemplates.asset(FILE);
        var before = state(ssh, sudo, request.sudoPassword(), () -> false); requireUpgrade(before);
        var changes = List.of(new Change(FILE, before.get("package.version"), VERSION,
                "用户提供的deb安装包，上传后核对SHA256及包内Package/Version/Architecture；SHA256：" + SHA256, "600"));
        var actions = List.of("SFTP上传固定补丁，校验摘要及包元数据；先执行dpkg --no-act --install预检", "dpkg --force-confold --install 固定补丁路径；最多5分钟；保留现有conffile", "重新检查包版本、已配置状态、dpkg审计以及升级服务运行/自启状态");
        var impacts = List.of("补丁：" + FILE + "；当前 " + before.get("package.version") + " → " + VERSION,
                "安装包维护脚本会启用并重启kylin-offline-upgrade.service，并更新桌面/MIME关联；可能中断正在执行的离线升级，请安排维护窗口。",
                "保留已有dpkg conffile；包维护脚本仍会按包自身逻辑修改其他文件。不会自动安装依赖、执行apt修复或重启系统。",
                "软件包升级不能通过文件备份恢复；本功能记录安装前后状态，不提供一键卸载/降级。安装失败、断线或超时可能部分生效，不自动重试。",
                "检测及验证基于用户提供的补丁版本；尚无独立厂商公告/CVE映射，不声称全部漏洞已修复。");
        var plan = new Plan(UUID.randomUUID().toString(), connection.connectionId(), connection.host() + ":" + connection.port(), "patch", "REPAIR",
                KylinProbe.revision(before), changes, actions, impacts, UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
        return new KylinPlanService.Prepared(plan, List.of(), List.of(), List.of(), before, List.of(), null, request.params());
    }
    /** root私有副本用于校验和安装，避免普通上传账户在校验后替换安装包。 */
    static String validateScript(String staging, String directory) {
        String source = staging + "/patch.deb", target = directory + "/patch.deb";
        return "set -eu\numask 077\nexport LC_ALL=C\n"
                + "for tool in dpkg dpkg-deb sha256sum stat install; do command -v \"$tool\" >/dev/null; done\n"
                + "test ! -L " + quote(source) + "\ntest -f " + quote(source) + "\n"
                + KylinExecutor.safeDirectory(directory)
                + "install -o root -g root -m 600 -- " + quote(source) + " " + quote(target) + "\n"
                + "printf '%s  %s\\n' " + quote(SHA256) + " " + quote(target) + " | sha256sum -c - >/dev/null\n"
                + "test \"$(dpkg-deb -f " + quote(target) + " Package)\" = " + quote(PACKAGE) + "\n"
                + "test \"$(dpkg-deb -f " + quote(target) + " Version)\" = " + quote(VERSION) + "\n"
                + "test \"$(dpkg-deb -f " + quote(target) + " Architecture)\" = amd64\n"
                + "DEBIAN_FRONTEND=noninteractive dpkg --no-act --install " + quote(target) + "\n";
    }
    static String installScript(String directory) {
        String target = directory + "/patch.deb";
        return "set -eu\nexport LC_ALL=C\n" + KylinExecutor.safeDirectory(directory)
                + "test ! -L " + quote(target) + "\nprintf '%s  %s\\n' " + quote(SHA256) + " " + quote(target) + " | sha256sum -c - >/dev/null\n"
                + "DEBIAN_FRONTEND=noninteractive dpkg --force-confold --install " + quote(target) + "\n";
    }
    static void verify(Map<String, String> after) {
        if (!applicable(after) || !installed(after) || !Set.of("EQUAL", "NEWER").contains(after.get("comparison")))
            throw new ApiException(409, "KYLIN_PATCH_VERIFY_FAILED", "安装后包状态或版本未达到提供的补丁要求，请核对dpkg状态；未自动重试");
        if (!after.get("unit.0").equals("loaded|active|enabled"))
            throw new ApiException(409, "KYLIN_PATCH_SERVICE_VERIFY_FAILED", "包版本已达到补丁要求，但升级服务未达到运行且自启状态，请核对维护脚本执行结果");
    }
    static void execute(Connection connection, KylinSshClient ssh, boolean sudo, KylinPlanService.Prepared prepared,
            String password, BooleanSupplier cancelled, Consumer<String> step, Runnable mutationStarted, KylinBackupStore records) {
        String id = prepared.view().backupId(), staging = "/tmp/toolhelper-" + id, directory = KylinPolicyCatalog.BACKUPS + id;
        boolean directoryCreated = false, stagingCreated = false, installStarted = false, installReturned = false;
        try {
            step.accept("PRECHECK");
            var current = state(ssh, sudo, password, cancelled); requireUpgrade(current);
            if (!current.equals(prepared.before())) throw new ApiException(409, "KYLIN_PLAN_STALE", "预览后补丁包或服务状态发生变化，请重新生成计划");
            records.patchRecord(id, "before", connection, current);
            step.accept("UPLOADING");
            require(ssh.run("set -eu\numask 077\ntest ! -e " + quote(staging) + "\ntest ! -L " + quote(staging)
                    + "\nmkdir -m 700 -- " + quote(staging), false, null, 30, cancelled), "KYLIN_PATCH_UPLOAD_FAILED", "无法建立补丁私有上传目录");
            stagingCreated = true;
            ssh.upload(staging + "/patch.deb", KylinTemplates.asset(FILE), cancelled);
            // 与策略文件备份相同的root目录约束，绝不信任已存在的宽权限目录。
            StringBuilder folders = new StringBuilder("set -eu\numask 077\n");
            for (String parent : List.of("/var/lib/toolhelper", "/var/lib/toolhelper/operations"))
                folders.append(KylinExecutor.safeDirectory(parent)).append("if test -e ").append(quote(parent)).append("; then test -d ")
                        .append(quote(parent)).append("; test \"$(stat -c %u -- ").append(quote(parent)).append(")\" = 0; test $(( 8#$(stat -c %a -- ")
                        .append(quote(parent)).append(") & 022 )) = 0; fi\ninstall -d -m 700 -o root -g root -- ").append(quote(parent)).append('\n');
            folders.append("test ! -e ").append(quote(directory)).append("\ntest ! -L ").append(quote(directory)).append("\nmkdir -m 700 -- ").append(quote(directory)).append('\n');
            require(ssh.run(folders.toString(), sudo, password, 30, cancelled), "KYLIN_PATCH_STAGING_FAILED", "无法建立root补丁校验目录");
            directoryCreated = true; step.accept("VALIDATING");
            require(ssh.run(validateScript(staging, directory), sudo, password, 60, cancelled), "KYLIN_PATCH_VALIDATION_FAILED", "补丁摘要、包元数据或dpkg安装预检失败，未安装");
            current = state(ssh, sudo, password, cancelled); requireUpgrade(current);
            if (!current.equals(prepared.before())) throw new ApiException(409, "KYLIN_PLAN_STALE", "上传期间补丁状态发生变化，未安装");
            if (cancelled.getAsBoolean()) throw new ApiException(409, "CANCELLED", "安装前取消");
            step.accept("INSTALLING"); mutationStarted.run(); installStarted = true;
            var installed = ssh.run(installScript(directory), sudo, password, 300, cancelled);
            installReturned = true;
            require(installed, "KYLIN_PATCH_INSTALL_FAILED", "补丁安装未完成，可能已部分解包或配置；请核对dpkg状态，未自动重试");
            step.accept("VERIFYING");
            var after = state(ssh, sudo, password, cancelled);
            records.patchRecord(id, "after", connection, after); verify(after);
        } catch (RuntimeException failure) {
            try { records.patchRecord(id, "failed", connection, state(ssh, sudo, password, () -> false)); }
            catch (RuntimeException diagnosticFailure) { step.accept("REMOTE_STATE_UNKNOWN"); }
            throw failure;
        } finally {
            // 只清理本次随机目录中的固定补丁；不递归删除，也不将安装失败包装成已回滚。
            if (ssh.connected()) {
                try {
                    // 安装超时/断线/取消时dpkg可能仍在读取副本，保留root副本供人工核对。
                    if (directoryCreated && (!installStarted || installReturned)) require(ssh.run("set -eu\nrm -f -- " + quote(directory + "/patch.deb") + "\nrmdir -- " + quote(directory), sudo, password, 30, () -> false), "KYLIN_PATCH_CLEANUP_FAILED", "补丁临时副本清理失败");
                    if (stagingCreated) require(ssh.run("set -eu\nif test -d " + quote(staging) + "; then rm -f -- " + quote(staging + "/patch.deb") + "; rmdir -- " + quote(staging) + "; fi\n", false, null, 30, () -> false), "KYLIN_PATCH_CLEANUP_FAILED", "补丁上传副本清理失败");
                } catch (RuntimeException cleanupFailure) { step.accept("TEMP_CLEANUP_FAILED"); }
            }
        }
    }
    private static void require(KylinSshClient.Reply reply, String code, String message) {
        if (reply.exitCode() != 0) throw new ApiException(409, code, message + "（退出码" + reply.exitCode() + "）");
    }
}
