package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import static com.toolhelper.kylin.KylinModels.*;
import static com.toolhelper.kylin.KylinSshClient.quote;

/** 可审查的固定模板；用户参数先验证再代入，不接收任意脚本。 */
public final class KylinTemplates {
    private KylinTemplates() {}
    public record File(String path, byte[] bytes, String mode, boolean secret) {}
    private static final Map<String, String> ASSET_HASHES = Map.of(
            KylinPatch.FILE, KylinPatch.SHA256,
            "x11vnc_x86_64", "0c085b6e9b33e2939ce34b2a160973966d171786bba7f4f75941998e45c7c797",
            "pg_hba.conf", "8c68ba82d0f3d2a0f759de3257d1fde5781d20650ee5e69bbee004cfd9e22bca",
            "postgresql.conf", "d045a2fd8893d85c1f74ccf71b4908cffe9e2f5a1fe96f2d08aa1f47a6d5d992");
    public static List<Map<String, String>> assets() {
        return ASSET_HASHES.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
            String status;
            try { asset(entry.getKey()); status = entry.getKey().endsWith(".deb") ? "USER_PROVIDED_PATCH" : entry.getKey().startsWith("x11vnc") ? "TARGET_CHECK_REQUIRED" : "USER_TEMPLATE"; }
            catch (ApiException unavailable) { status = unavailable.code(); }
            return Map.of("name", entry.getKey(), "sha256", entry.getValue(), "status", status);
        }).toList();
    }
    public static byte[] asset(String name) {
        try (var stream = KylinTemplates.class.getResourceAsStream("/kylin/assets/" + name)) {
            if (stream == null) throw new ApiException(409, "KYLIN_ASSET_MISSING", "缺少经过核对的运维资产：" + name);
            byte[] bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IOException("Asset too large");
            if (!KylinFiles.hash(bytes).equals(ASSET_HASHES.get(name))) throw new IOException("Asset digest mismatch");
            return bytes;
        } catch (IOException invalid) { throw new ApiException(500, "KYLIN_ASSET_INVALID", "运维资产读取失败"); }
    }
    private static File file(String path, String content, String mode) {
        return new File(path, content.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8), mode, false);
    }
    public static String cron(String value) {
        if (value == null) throw invalid("请填写五段数字cron表达式");
        String[] fields = value.trim().split("\\s+"); int[] maxima = {59, 23, 31, 12, 7};
        if (fields.length != 5) throw invalid("定时计划需为五段cron，不包含命令");
        for (int i = 0; i < fields.length; i++) {
            if (!fields[i].equals("*") && (!fields[i].matches("0|[1-9][0-9]?")
                    || Integer.parseInt(fields[i]) > maxima[i] || i >= 2 && i <= 3 && fields[i].equals("0")))
                throw invalid("cron每段仅支持*或范围内数字，请勿输入命令");
        }
        return String.join(" ", fields);
    }
    public static String user(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_.-]{0,31}")) throw invalid("桌面用户名格式不正确");
        return value;
    }
    public static String ipv4(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw invalid("请填写明确IPv4地址");
        for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) throw invalid("IPv4地址段超出范围");
        int first = Integer.parseInt(value.substring(0, value.indexOf('.')));
        if (first == 0 || first >= 224 || value.startsWith("169.254.")) throw invalid("不允许未指定、多播或链路本地地址");
        return value;
    }
    public static String cidr(String value) {
        if (value == null || !value.matches("[0-9.]+/(?:[1-9]|[12][0-9]|3[0-2])")) throw invalid("请输入明确IPv4来源CIDR，不允许0.0.0.0/0");
        String[] parts = value.split("/"); ipv4(parts[0]);
        int prefix = Integer.parseInt(parts[1]); long address = 0;
        for (String part : parts[0].split("\\.")) address = (address << 8) | Integer.parseInt(part);
        if ((address & ((1L << (32 - prefix)) - 1)) != 0) throw invalid("CIDR地址需按网络边界填写，例如192.168.31.0/24");
        return value;
    }
    public static List<File> reboot(Params params) {
        String username = user(params.desktopUser()), schedule = cron(params.schedule());
        // 仅增加本模块独立seat配置。清理由root启动服务执行，不依赖桌面用户提权。
        String script = """
                #!/bin/bash
                set -euo pipefail
                umask 077
                test -d /etc/lightdm/lightdm.conf.d
                test ! -L /etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf
                test ! -L /var/lib/toolhelper/reboot
                if test -e /var/lib/toolhelper/reboot; then
                    test -d /var/lib/toolhelper/reboot
                    test "$(stat -c '%%u' /var/lib/toolhelper/reboot)" = 0
                    test $(( 8#$(stat -c '%%a' /var/lib/toolhelper/reboot) & 022 )) = 0
                fi
                install -d -o root -g root -m 700 /var/lib/toolhelper/reboot
                test ! -L /var/lib/toolhelper/reboot/seat.tmp
                test ! -e /var/lib/toolhelper/reboot/pending
                test ! -e /etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf
                printf '[Seat:*]\\nautologin-user=%%s\\nautologin-user-timeout=0\\n' %s > /var/lib/toolhelper/reboot/seat.tmp
                install -o root -g root -m 600 /var/lib/toolhelper/reboot/seat.tmp /etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf
                cat /proc/sys/kernel/random/boot_id > /var/lib/toolhelper/reboot/pending
                sync
                if ! /sbin/reboot; then
                    rm -f -- /etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf /var/lib/toolhelper/reboot/pending
                    exit 1
                fi
                """.formatted(quote(username));
        String cleanup = """
                #!/bin/bash
                set -euo pipefail
                test ! -L /var/lib/toolhelper/reboot
                test -f /var/lib/toolhelper/reboot/pending || exit 0
                test ! -L /var/lib/toolhelper/reboot/pending
                # 只有进入新一次启动才清理，部署时不干扰现有桌面。
                test "$(cat /proc/sys/kernel/random/boot_id)" != "$(cat /var/lib/toolhelper/reboot/pending)" || exit 0
                # 等待指定用户的X11会话；最多60秒后始终移除临时配置，登录慢时不承诺成功。
                for attempt in {1..30}; do
                    logged_in=no
                    while read -r session _; do
                        test -n "$session" || continue
                        if test "$(loginctl show-session "$session" -p Name --value)" = %s && test "$(loginctl show-session "$session" -p Type --value)" = x11; then logged_in=yes; break; fi
                    done < <(loginctl list-sessions --no-legend)
                    test "$logged_in" = no || break
                    sleep 2
                done
                rm -f -- /etc/lightdm/lightdm.conf.d/99-toolhelper-autologin.conf /var/lib/toolhelper/reboot/pending
                """.formatted(quote(username));
        String service = """
                [Unit]
                Description=ToolHelper remove one-boot LightDM auto-login configuration
                After=lightdm.service
                ConditionPathExists=/var/lib/toolhelper/reboot/pending
                [Service]
                Type=oneshot
                ExecStart=/usr/local/bin/clear-autologin.sh
                TimeoutStartSec=90
                [Install]
                WantedBy=graphical.target
                """;
        return List.of(file("/usr/local/bin/scheduled-reboot.sh", script, "700"),
                file("/usr/local/bin/clear-autologin.sh", cleanup, "700"),
                file("/etc/cron.d/auto-reboot", "# ToolHelper scheduled reboot with temporary auto-login\n" + schedule + " root /usr/local/bin/scheduled-reboot.sh\n", "644"),
                file("/etc/systemd/system/toolhelper-autologin-cleanup.service", service, "644"));
    }
    public static List<File> logs(Params params) { return logs(params, true); }
    public static List<File> logs(Params params, boolean scheduled) {
        int journal = params.journalDays() == null ? 30 : params.journalDays();
        int days = params.fileDays() == null ? 365 : params.fileDays();
        if (journal < 7 || journal > 3650 || days < 30 || days > 3650) throw invalid("journal保留7～3650天，文件保留30～3650天");
        String script = """
                #!/bin/bash
                set -euo pipefail
                journalctl --vacuum-time=%dd
                # 只清理固定目录中的旧归档，不截断正在写入的日志，不跟随符号链接。
                find -P /var/log -xdev -type f \\( -name '*.gz' -o -name '*.log.[0-9]*' \\) -mtime +%d -delete
                find -P /tmp -xdev -type f -mtime +%d -delete
                """.formatted(journal, days, days);
        File cleanup = file("/usr/local/bin/clean-logs.sh", script, "700");
        return scheduled ? List.of(cleanup,
                file("/etc/cron.d/clean-logs", "# ToolHelper retention policy\n" + cron(params.schedule()) + " root /usr/local/bin/clean-logs.sh\n", "644")) : List.of(cleanup);
    }
    public static List<File> database(Params params, byte[] hbaTemplate, byte[] configTemplate) {
        String source = cidr(params.allowedCidr()), address = ipv4(params.listenAddress());
        StringBuilder hba = new StringBuilder("# ToolHelper derived from user supplied template; remote source restricted\n");
        for (String line : new String(hbaTemplate, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n")) {
            if (line.stripLeading().startsWith("host")) continue;
            // 本地trust也显式收紧，避免同机任意用户绕过数据库认证。
            if (line.stripLeading().startsWith("local")) hba.append("local all all sha256\n");
            else hba.append(line).append('\n');
        }
        hba.append("host all all 127.0.0.1/32 sha256\nhost all all ::1/128 sha256\nhost all all ").append(source).append(" sha256\n");
        String config = new String(configTemplate, StandardCharsets.UTF_8).replace("\r\n", "\n");
        config = Pattern.compile("(?m)^\\s*listen_addresses\\s*=.*$").matcher(config).replaceAll("listen_addresses = '" + (address.equals("127.0.0.1") ? address : address + ",127.0.0.1") + "'");
        if (!config.contains("listen_addresses = '")) throw invalid("用户配置模板缺少listen_addresses，未猜测配置格式");
        return List.of(file(KylinPolicyCatalog.DATABASE + "/pg_hba.conf", hba.toString(), "600"),
                file(KylinPolicyCatalog.DATABASE + "/postgresql.conf", config, "600"));
    }
    public static List<File> vnc(Params params, byte[] binary) {
        String address = ipv4(params.listenAddress()); int port = params.port() == null ? 5901 : params.port();
        if (port < 1024 || port > 65535) throw invalid("VNC端口范围为1024～65535");
        String service = """
                [Unit]
                Description=ToolHelper x11vnc on X11 display :0
                After=lightdm.service
                Requires=lightdm.service
                [Service]
                Type=simple
                ExecStart=/usr/local/bin/x11vnc -display :0 -auth guess -rfbauth /etc/x11vnc.passwd -forever -shared -listen %s -rfbport %d -noxdamage
                Restart=on-failure
                RestartSec=5
                [Install]
                WantedBy=graphical.target
                """.formatted(address, port);
        return List.of(new File("/usr/local/bin/x11vnc", binary, "755", false),
                new File("/etc/x11vnc.passwd", new byte[0], "600", true),
                file("/etc/systemd/system/x11vnc.service", service, "644"));
    }
    /** LibVNC兼容的固定密钥DES口令文件；该格式可还原，仅作为传统VNC兼容格式。 */
    public static byte[] vncPassword(String value) {
        if (value == null || !value.matches("[\\x21-\\x7E]{8}")) throw invalid("传统VNC口令需恰好8个可打印ASCII字符，不允许静默截断");
        byte[] password = value.getBytes(StandardCharsets.US_ASCII), key = {23, 82, 107, 6, 35, 78, 88, 7};
        try {
            for (int i = 0; i < key.length; i++) key[i] = (byte) (Integer.reverse(key[i] & 255) >>> 24);
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            return cipher.doFinal(password);
        } catch (java.security.GeneralSecurityException invalid) { throw new IllegalStateException("VNC password encoding unavailable", invalid); }
        finally { Arrays.fill(password, (byte) 0); Arrays.fill(key, (byte) 0); }
    }
    private static ApiException invalid(String message) { return new ApiException(400, "INVALID_KYLIN_PARAMETERS", message); }
}
