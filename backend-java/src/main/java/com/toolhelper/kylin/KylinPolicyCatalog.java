package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.util.*;
import static com.toolhelper.kylin.KylinModels.*;

/** 仅允许目录内策略；请求不能提供shell命令或任意远程目标路径。 */
public final class KylinPolicyCatalog {
    private KylinPolicyCatalog() {}
    public static final String VERSION = "toolhelper-kylin-1";
    public static final String DATABASE = "/data/usershare/firestation/db/opengauss/data/single_node";
    public static final String BACKUPS = "/var/lib/toolhelper/operations/";
    public static final List<Feature> FEATURES = List.of(
        new Feature("activation", "系统激活", "检测激活状态；微信扫描目标二维码取得激活码后提交密钥激活", List.of()),
        new Feature("reboot", "定时重启（自动登录）", "按目标时区执行；默认带LightDM临时自动登录，重启后清理", List.of("DEPLOY", "UNINSTALL", "RESTORE")),
        new Feature("logs", "日志优化", "先预览保留规则；已删除的日志不能恢复", List.of("DEPLOY", "CLEAN_NOW", "UNINSTALL", "RESTORE")),
        new Feature("vnc", "VNC Server", "默认回环监听；LAN监听需显式选择，口令不出现在命令行", List.of("DEPLOY", "START", "STOP", "UNINSTALL", "RESTORE")),
        new Feature("opengauss", "openGauss连接配置", "使用用户提供的模板，限制明确IPv4来源；reload与重启分开", List.of("DEPLOY", "RELOAD", "UNINSTALL", "RESTORE")),
        new Feature("patch", "漏洞检测", "检测补丁版本，确认后上传并安装用户提供的kylin-offline-upgrade补丁，再核对包及服务状态", List.of("REPAIR")),
        new Feature("optimization", "系统优化", "逐项扫描及选择，按变更前状态恢复；审计/更新/激活/存储默认只读", List.of("APPLY", "UNINSTALL", "RESTORE"))
    );
    public record Optimization(String id, String name, boolean readOnly, List<String> units, List<String> files, String impact) {}
    public static final List<Optimization> OPTIMIZATIONS = List.of(
        opt("peripherals", "蓝牙、打印与生物识别服务", false, List.of("bluetooth.service", "cups.service", "cups.socket", "cups.path", "cups-browsed.service", "biometric-authentication.service", "ukui-bluetooth.service"), List.of(), "这些外设及对应桌面功能将不可用"),
        opt("manager-process", "麒麟管家后台程序", false, List.of(), List.of("/usr/bin/kylin-os-manager-daemon", "/usr/share/kylin-os-manager/kylin-core-dump-monitor/kylin-core-dump-monitor.sh", "/usr/lib/kylin-os-manager/bin/kylin-os-manager-session-service"), "移除可执行位，现有进程不自动结束，重新登录后验证"),
        opt("manager-service", "麒麟管家系统服务", false, List.of("kylin-core-dump-monitor.service", "kylin-process-manager-daemon.service", "com.kylin.kysdk.SyncConfig.service", "com.kylin-os-manager.service"), List.of(), "管家监控和系统管理功能将停止"),
        opt("activation", "激活校验服务", true, List.of("kylin-activation-check.service"), List.of(), "仅检查，保持授权校验"),
        opt("updates", "自动更新服务", true, List.of("kylin-source-update.service", "kylin-source-update-timer.timer", "kylin-system-updater.service", "kylin-offline-upgrade.service", "kylin-unattended-upgrades.service"), List.of(), "仅检查，保留安全更新"),
        opt("audit", "安全审计服务", true, List.of("auditd.service"), List.of(), "仅检查，保留审计追踪"),
        opt("samba", "Samba服务", false, List.of("smbd.service", "nmbd.service"), List.of(), "Windows文件共享将停止"),
        opt("ppp", "PPP DNS服务", false, List.of("pppd-dns.service"), List.of(), "PPP拨号DNS更新将停止"),
        opt("discovery", "局域网自动发现", false, List.of("avahi-daemon.service", "avahi-daemon.socket"), List.of(), "mDNS服务发现将停止"),
        opt("weather", "天气组件", false, List.of(), List.of("/usr/bin/kylin-weather"), "移除可执行位；现有天气进程需退出后验证"),
        opt("storage", "LVM存储监控", true, List.of("lvm2-monitor.service", "lvm2-lvmpolld.service", "lvm2-lvmpolld.socket"), List.of(), "仅检查，不能根据服务名判断存储业务可停"),
        opt("accounts", "多账户管理", false, List.of("accounts-daemon.service"), List.of(), "可能影响桌面用户管理，需要核对业务"),
        opt("search", "全局搜索组件", false, List.of(), List.of("/usr/bin/ukui-search"), "移除可执行位；不自动终止现有桌面进程"),
        opt("session", "会话自启动残留", true, List.of(), List.of("/etc/xdg/autostart/ukui-bluetooth.desktop", "/etc/xdg/autostart/kylin-activation-autostart.desktop", "/etc/xdg/autostart/ukui-search.desktop", "/usr/share/dbus-1/system-services/com.kylin.UpgradeStrategies.service"), "仅检查混合自启动项，不整组关闭激活/更新或杀进程")
    );
    private static Optimization opt(String id, String name, boolean readOnly, List<String> units, List<String> files, String impact) {
        return new Optimization(id, name, readOnly, units, files, impact);
    }
    public static Feature feature(String id) { return FEATURES.stream().filter(f -> f.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(400, "INVALID_KYLIN_FEATURE", "请选择固定运维功能")); }
    public static List<Optimization> selected(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 14 || new HashSet<>(ids).size() != ids.size())
            throw new ApiException(400, "INVALID_OPTIMIZATIONS", "请明确选择可执行优化项");
        var result = ids.stream().map(id -> OPTIMIZATIONS.stream().filter(o -> o.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(400, "INVALID_OPTIMIZATIONS", "优化项不在允许目录内"))).toList();
        if (result.stream().anyMatch(Optimization::readOnly)) throw new ApiException(400, "READ_ONLY_POLICY", "审计、更新、激活、存储与混合自启动策略只读");
        return result;
    }
    public static List<String> paths(String feature, List<Optimization> selected) {
        return switch (feature) {
            case "reboot" -> List.of("/usr/local/bin/scheduled-reboot.sh", "/usr/local/bin/clear-autologin.sh", "/etc/cron.d/auto-reboot", "/etc/systemd/system/toolhelper-autologin-cleanup.service",
                    "/etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf", "/var/lib/toolhelper/reboot/pending");
            case "logs" -> List.of("/usr/local/bin/clean-logs.sh", "/etc/cron.d/clean-logs");
            case "vnc" -> List.of("/usr/local/bin/x11vnc", "/etc/x11vnc.passwd", "/etc/systemd/system/x11vnc.service");
            case "opengauss" -> List.of(DATABASE + "/pg_hba.conf", DATABASE + "/postgresql.conf");
            case "optimization" -> selected.stream().flatMap(o -> o.files().stream()).distinct().toList();
            default -> List.of();
        };
    }
    public static List<String> units(String feature, List<Optimization> selected) {
        return switch (feature) {
            case "reboot" -> List.of("cron.service", "toolhelper-autologin-cleanup.service");
            case "logs" -> List.of("cron.service");
            case "vnc" -> List.of("x11vnc.service");
            case "optimization" -> selected.stream().flatMap(o -> o.units().stream()).distinct().toList();
            default -> List.of();
        };
    }
}
