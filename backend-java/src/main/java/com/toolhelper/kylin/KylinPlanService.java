package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.toolhelper.kylin.KylinModels.*;
import static com.toolhelper.kylin.KylinSshClient.quote;

/** 计划只保留固定策略与非敏感文件，绑定变更前状态，五分钟后失效。 */
public final class KylinPlanService {
    public record Prepared(Plan view, List<KylinTemplates.File> files, List<String> paths, List<String> units,
            Map<String, String> before, List<KylinPolicyCatalog.Optimization> optimizations, KylinBackupStore.RestorePoint restore, Params params) {}
    private final KylinBackupStore backups;
    public KylinPlanService(KylinBackupStore backups) { this.backups = backups; }
    public Prepared prepare(Connection connection, KylinSshClient ssh, boolean sudo, PlanRequest request) {
        var feature = KylinPolicyCatalog.feature(request.feature()); String action = request.action();
        if (action == null || !feature.actions().contains(action)) throw new ApiException(400, "KYLIN_ACTION_NOT_SUPPORTED", "该功能不支持此操作或当前仅允许只读检测");
        if (!connection.system().getOrDefault("os", "").toLowerCase(Locale.ROOT).contains("kylin")
                || !connection.system().getOrDefault("systemd", "").equals("yes"))
            throw new ApiException(409, "KYLIN_NOT_APPLICABLE", "当前写策略仅适配Kylin及systemd环境");
        if (request.feature().equals("patch")) return KylinPatch.prepare(connection, ssh, sudo, request);
        Params params = request.params(); if (params == null) params = new Params(null, null, null, null, null, null, null, null, null);
        List<KylinPolicyCatalog.Optimization> selected = request.feature().equals("optimization") && action.equals("APPLY")
                ? KylinPolicyCatalog.selected(params.optimizationIds()) : List.of();
        var restorePoint = action.equals("UNINSTALL") ? backups.initial(connection.host(), connection.port(), connection.fingerprint(), request.feature())
                : action.equals("RESTORE") ? backups.restorePoint(params.backupId()) : null;
        Backup restore = restorePoint == null ? null : restorePoint.backup();
        if (restore != null && (!restore.host().equals(connection.host()) || restore.port() != connection.port()
                || !restore.fingerprint().equals(connection.fingerprint()) || !restore.feature().equals(request.feature())))
            throw new ApiException(409, "BACKUP_TARGET_MISMATCH", "备份不属于当前主机、密钥或功能");
        List<String> paths = restore == null ? KylinPolicyCatalog.paths(request.feature(), selected) : restore.paths();
        List<String> units = restore == null ? KylinPolicyCatalog.units(request.feature(), selected) : restore.units();
        if (restore == null && request.feature().equals("logs") && action.equals("CLEAN_NOW")) {
            paths = List.of("/usr/local/bin/clean-logs.sh"); units = List.of();
        }
        var before = KylinProbe.state(ssh, paths, units, sudo, request.sudoPassword(), () -> false);
        if (restore != null && !before.equals(restorePoint.expected()))
            throw new ApiException(409, "KYLIN_RESTORE_CONFLICT", "目标已在原变更后发生其他变化，未生成覆盖恢复计划");
        List<KylinTemplates.File> files = List.of();
        var impacts = new ArrayList<String>(); var actions = new ArrayList<String>();
        impacts.add("仅作用于此主机；执行后断线或超时需核对远程状态，不自动重放。");
        if (restore != null) {
            if (action.equals("UNINSTALL")) impacts.add("卸载恢复到本工具首次变更前的文件和服务状态；首次不存在的文件将移除，原有文件保留并恢复。");
            impacts.add("恢复该备份覆盖的文件及原服务状态；日志内容、已发生重启和进程内存不可恢复。");
            actions.add("恢复原文件属性与服务运行/自启状态");
            for (int i = 0; i < units.size(); i++) actions.add(units.get(i) + "：" + before.get("unit." + i) + " → " + restore.state().get("unit." + i));
        } else switch (request.feature()) {
            case "reboot" -> {
                var check = ssh.run("set -eu\ngetent passwd " + quote(KylinTemplates.user(params.desktopUser()))
                        + " >/dev/null\ntest -d /etc/lightdm/lightdm.conf.d\ntest -f /etc/X11/default-display-manager\ngrep -q 'lightdm' /etc/X11/default-display-manager\n"
                        + "test ! -e /var/lib/toolhelper/reboot/pending\ntest ! -e /etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf\n"
                        + "test ! -e /etc/sudoers.d/auto-reboot\ntest ! -e /etc/xdg/autostart/clear-autologin.desktop\n", sudo, request.sudoPassword(), 30, () -> false);
                if (check.exitCode() != 0) throw new ApiException(409, "AUTOLOGIN_PRECHECK_FAILED", "需要真实桌面用户及LightDM配置目录；旧sudoers/桌面脚本或待清理自动登录需先处理");
                files = KylinTemplates.reboot(params);
                actions.addAll(List.of("systemctl daemon-reload", "启用cron与自动登录清理服务；此时不重启"));
                impacts.add("按目标时区 " + connection.system().getOrDefault("timezone", "UNKNOWN") + " 在 " + params.schedule() + " 重启并自动登录 " + params.desktopUser());
                impacts.add("新启动后等待指定用户X11会话，最多60秒即移除临时配置；清理服务失败可能残留配置，需人工处理。慢启动或显示管理器差异可能导致自动登录未成功。");
            }
            case "logs" -> {
                files = KylinTemplates.logs(params, !action.equals("CLEAN_NOW"));
                impacts.add(KylinProbe.logCandidates(ssh, params, sudo, request.sudoPassword()));
                if (action.equals("CLEAN_NOW")) {
                    actions.add("替换清理脚本并立即按所预览保留期清理journal、旧日志归档和/tmp旧普通文件；不安装cron");
                    impacts.add("如果已有定时任务引用/usr/local/bin/clean-logs.sh，其后续清理也会使用本次新规则，请核对脚本替换影响。");
                }
                else actions.add("启用cron并安装清理计划；此时不清日志");
                impacts.add("清理删除不可恢复，不截断当前日志；RESTORE仅恢复脚本与计划。预览与执行之间文件会变化，数量/容量仅为当前快照；执行仍按固定规则匹配。");
            }
            case "vnc" -> {
                if (action.equals("DEPLOY")) {
                    if (!connection.system().getOrDefault("arch", "").equals("x86_64") || !connection.system().getOrDefault("display", "").contains("lightdm"))
                        throw new ApiException(409, "VNC_NOT_APPLICABLE", "当前x11vnc资产仅适配经验证的x86_64 LightDM/X11桌面");
                    files = KylinTemplates.vnc(params, KylinTemplates.asset("x11vnc_x86_64"));
                    var desktop = ssh.run("test -S /tmp/.X11-unix/X0\n", false, null, 30, () -> false);
                    if (desktop.exitCode() != 0) throw new ApiException(409, "VNC_X11_UNAVAILABLE", "未发现X11显示:0套接字，不能推断Wayland或其他显示号兼容");
                    impacts.add("VNC监听 " + params.listenAddress() + ":" + params.port() + "；传统VNC不提供传输加密，跨机访问应结合可信网络/SSH隧道。");
                    actions.add("安装并启动本模块x11vnc服务；仅影响本模块服务实例");
                } else {
                    if (before.get("unit.0").equals("MISSING")) throw new ApiException(409, "VNC_NOT_DEPLOYED", "VNC服务不存在，不能启停");
                    actions.add("systemctl " + action.toLowerCase(Locale.ROOT) + " x11vnc.service");
                }
                if (!before.get("file.2").equals("MISSING")) {
                    var owned = ssh.run("grep -Fxq 'Description=ToolHelper x11vnc on X11 display :0' /etc/systemd/system/x11vnc.service", sudo, request.sudoPassword(), 30, () -> false);
                    if (owned.exitCode() != 0) throw new ApiException(409, "VNC_SERVICE_CONFLICT", "已有x11vnc服务不属于本模块，未接管或启停");
                } else if (!before.get("unit.0").equals("MISSING")) {
                    throw new ApiException(409, "VNC_SERVICE_CONFLICT", "已有其他位置的x11vnc服务，未接管");
                }
            }
            case "opengauss" -> {
                if (before.values().stream().anyMatch(v -> v.equals("MISSING"))) throw new ApiException(409, "OPENGAUSS_CONFIG_MISSING", "固定数据目录的配置文件缺失，未猜测其他数据库目录");
                String databaseCheck = "set -eu\nowner=$(stat -c '%U' -- " + quote(KylinPolicyCatalog.DATABASE)
                        + ")\nrunuser -l \"$owner\" -c 'gsql --version'\n";
                var product = ssh.run(databaseCheck, sudo, request.sudoPassword(), 30, () -> false);
                if (product.exitCode() != 0 || !product.output().toLowerCase(Locale.ROOT).contains("opengauss")
                        || !product.output().matches("(?s).*\\b6\\.0\\.1\\b.*"))
                    throw new ApiException(409, "OPENGAUSS_VERSION_UNVERIFIED", "未确认配置属主环境中的openGauss 6.0.1，不能套用用户提供的模板");
                if (action.equals("DEPLOY")) {
                    files = KylinTemplates.database(params, KylinTemplates.asset("pg_hba.conf"), KylinTemplates.asset("postgresql.conf"));
                    impacts.add("替换为用户提供模板的限制来源版本；模板中的其他参数也会替换，请逐项核对预览。密码认证改为sha256需要数据库用户具备相应口令摘要。");
                    actions.add("只替换配置，不自动reload或restart；listen_addresses需维护窗口重启才能生效");
                } else {
                    actions.add("以配置属主身份执行gs_ctl reload；失败不回退restart");
                    impacts.add("reload不能应用listen_addresses等启动参数；连接验证需另行核对，不自动重启数据库。");
                }
            }
            case "optimization" -> {
                for (var opt : selected) { impacts.add(opt.name() + "：" + opt.impact()); }
                actions.add("只停用并mask已存在选中服务，或移除选中文件执行位；不杀进程");
            }
            default -> throw new ApiException(400, "READ_ONLY_POLICY", "当前功能只读");
        }
        var changes = new ArrayList<Change>();
        if (restore != null) for (int i = 0; i < paths.size(); i++) {
            String original = restore.state().get("file." + i);
            String[] attrs = original.split("\\|");
            changes.add(new Change(paths.get(i), before.get("file." + i), original,
                    original.equals("MISSING") ? "本工具变更前不存在，确认后移除此文件" : "从原始备份恢复内容、属主与权限；SHA256：" + attrs[0],
                    original.equals("MISSING") ? "—" : attrs[1]));
        }
        for (var file : files) {
            int i = paths.indexOf(file.path());
            if (i < 0) throw new IllegalStateException("Template path outside policy");
            String content = file.secret() ? "[执行时单独输入，口令不保存]" : file.bytes().length > 65536 || file.path().endsWith("x11vnc")
                    ? "[二进制资产；核对SHA256]" : new String(file.bytes(), StandardCharsets.UTF_8);
            changes.add(new Change(file.path(), before.get("file." + i), file.secret() ? "SECRET" : KylinFiles.hash(file.bytes()), content, file.mode()));
        }
        String backupId = UUID.randomUUID().toString();
        Plan plan = new Plan(UUID.randomUUID().toString(), connection.connectionId(), connection.host() + ":" + connection.port(), request.feature(), action,
                KylinProbe.revision(before), List.copyOf(changes), List.copyOf(actions), List.copyOf(impacts), backupId, Instant.now().plusSeconds(300));
        return new Prepared(plan, files, paths, units, before, selected, restorePoint, params);
    }
}
