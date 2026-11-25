package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import static com.toolhelper.kylin.KylinModels.*;
import static com.toolhelper.kylin.KylinSshClient.quote;

/** 固定路径变更、原属性备份及冲突保护；不自动回滚已发生的远程副作用。 */
public final class KylinExecutor {
    private final KylinBackupStore backups;
    public KylinExecutor(KylinBackupStore backups) { this.backups = backups; }
    public void execute(Connection connection, KylinSshClient ssh, boolean sudo, KylinPlanService.Prepared prepared,
            String sudoPassword, String vncPassword, BooleanSupplier cancelled, Consumer<String> step, Runnable mutationStarted) {
        if (prepared.view().feature().equals("patch")) {
            KylinPatch.execute(connection, ssh, sudo, prepared, sudoPassword, cancelled, step, mutationStarted, backups);
            return;
        }
        var plan = prepared.view(); String directory = KylinPolicyCatalog.BACKUPS + plan.backupId();
        String staging = "/tmp/toolhelper-" + plan.backupId();
        List<KylinTemplates.File> files = new ArrayList<>();
        step.accept("PRECHECK");
        var current = KylinProbe.state(ssh, prepared.paths(), prepared.units(), sudo, sudoPassword, cancelled);
        if (!current.equals(prepared.before())) throw new ApiException(409, "KYLIN_PLAN_STALE", "预览后目标状态发生变化，请重新生成计划");
        validateServiceStates(current);
        var original = new Backup(plan.backupId(), connection.host(), connection.port(), connection.fingerprint(), plan.feature(),
                prepared.paths(), prepared.units(), current, Instant.now());
        backups.save(original);
        boolean changed = false;
        try {
            for (var file : prepared.files()) files.add(file.secret()
                    ? new KylinTemplates.File(file.path(), KylinTemplates.vncPassword(vncPassword), file.mode(), true) : file);
            step.accept("BACKUP");
            StringBuilder backup = new StringBuilder("set -eu\numask 077\n");
            for (String parent : List.of("/var/lib/toolhelper", "/var/lib/toolhelper/operations"))
                backup.append(safeDirectory(parent)).append("if test -e ").append(quote(parent))
                        .append("; then test -d ").append(quote(parent)).append("; test \"$(stat -c %u -- ").append(quote(parent))
                        .append(")\" = 0; test $(( 8#$(stat -c %a -- ").append(quote(parent)).append(") & 022 )) = 0; fi\n")
                        .append("install -d -m 700 -o root -g root -- ").append(quote(parent)).append('\n');
            backup.append("test ! -e ").append(quote(directory)).append("\nmkdir -m 700 -- ").append(quote(directory)).append('\n');
            for (int i = 0; i < prepared.paths().size(); i++) {
                String value = current.get("file." + i);
                if (!value.equals("MISSING")) backup.append("cp -p -- ").append(quote(prepared.paths().get(i))).append(' ').append(quote(directory + "/file-" + i))
                        .append("\nchown root:root -- ").append(quote(directory + "/file-" + i))
                        .append("\nchmod 600 -- ").append(quote(directory + "/file-" + i)).append('\n');
            }
            require(ssh.run(backup.toString(), sudo, sudoPassword, 30, cancelled), "KYLIN_BACKUP_FAILED", "远程备份未完成，未应用策略");
            require(ssh.run("umask 077\ntest ! -e " + quote(staging) + " && mkdir -m 700 -- " + quote(staging), false, null, 30, cancelled),
                    "KYLIN_STAGING_FAILED", "无法建立私有上传目录");
            for (int i = 0; i < files.size(); i++) ssh.upload(staging + "/file-" + i, files.get(i).bytes(), cancelled);
            step.accept("VALIDATING");
            StringBuilder check = new StringBuilder("set -eu\n");
            for (int i = 0; i < files.size(); i++) {
                var file = files.get(i); String temp = staging + "/file-" + i;
                check.append("printf '%s  %s\\n' ").append(quote(KylinFiles.hash(file.bytes()))).append(' ').append(quote(temp)).append(" | sha256sum -c - >/dev/null\n");
                if (file.path().endsWith(".sh")) check.append("bash -n -- ").append(quote(temp)).append('\n');
            }
            // verify也检查ExecStart可执行性。首次部署时只在校验副本中指向暂存程序，原始unit保持不变。
            for (int i = 0; i < files.size(); i++) if (files.get(i).path().endsWith(".service")) {
                String executable = prepared.view().feature().equals("vnc") ? "/usr/local/bin/x11vnc" : "/usr/local/bin/clear-autologin.sh";
                int executableIndex = -1;
                for (int j = 0; j < files.size(); j++) if (files.get(j).path().equals(executable)) executableIndex = j;
                if (executableIndex < 0) throw new IllegalStateException("Unit executable missing from fixed assets");
                String stagedExecutable = staging + "/file-" + executableIndex;
                check.append("chmod 700 -- ").append(quote(stagedExecutable)).append('\n')
                        .append("sed ").append(quote("s@^ExecStart=" + executable + "@ExecStart=" + stagedExecutable + "@"))
                        .append(' ').append(quote(staging + "/file-" + i)).append(" > ").append(quote(staging + "/check.service"))
                        .append("\nsystemd-analyze verify ").append(quote(staging + "/check.service")).append('\n');
            }
            require(ssh.run(check.toString(), sudo, sudoPassword, 30, cancelled), "KYLIN_SYNTAX_FAILED", "脚本或服务语法检查失败，未应用策略");
            if (prepared.view().feature().equals("vnc") && prepared.view().action().equals("DEPLOY")) {
                // 摘要校验通过后才执行旧资产，且不以root执行兼容性探测。
                require(ssh.run(quote(staging + "/file-0") + " -version >/dev/null 2>&1\n",
                        false, null, 30, cancelled), "VNC_BINARY_INCOMPATIBLE", "x11vnc目标依赖或执行检查失败，未安装");
            }
            // 上传与校验可能耗时；临近应用再次核对，避免覆盖期间外部已经变更的配置。
            if (!KylinProbe.state(ssh, prepared.paths(), prepared.units(), sudo, sudoPassword, cancelled).equals(prepared.before()))
                throw new ApiException(409, "KYLIN_PLAN_STALE", "上传期间目标状态发生变化，未应用策略");
            if (cancelled.getAsBoolean()) throw new ApiException(409, "CANCELLED", "应用前取消");
            step.accept("APPLYING"); mutationStarted.run(); changed = true;
            String script = prepared.restore() == null ? apply(prepared, files, staging) : restore(prepared.restore().backup(), prepared.restore().sources());
            require(ssh.run(script, sudo, sudoPassword, plan.action().equals("CLEAN_NOW") ? 300 : 60, cancelled),
                    "KYLIN_APPLY_FAILED", "策略应用未通过，可能部分生效，请查看备份并核对目标");
            step.accept("VERIFYING");
            var after = KylinProbe.state(ssh, prepared.paths(), prepared.units(), sudo, sudoPassword, cancelled);
            verify(prepared, files, after);
            backups.receipt(plan.backupId(), after);
        } catch (RuntimeException failure) {
            if (changed) {
                // 记录实际部分状态，供用户在核对后恢复；失败不覆盖原始异常，也不伪报已恢复。
                try { backups.receipt(plan.backupId(), KylinProbe.state(ssh, prepared.paths(), prepared.units(), sudo, sudoPassword, () -> false)); }
                catch (RuntimeException stateFailure) { step.accept("REMOTE_STATE_UNKNOWN"); }
            } else {
                try { backups.unchanged(plan.backupId()); }
                catch (RuntimeException recordFailure) { step.accept("BACKUP_RECORD_FAILED"); }
            }
            throw failure;
        } finally {
            for (var file : files) if (file.secret()) Arrays.fill(file.bytes(), (byte) 0);
            // 只删除本任务随机目录内固定文件，不使用递归删除；失败由状态反馈，不扩大清理范围。
            if (ssh.connected()) {
                StringBuilder cleanup = new StringBuilder("set -eu\n");
                for (int i = 0; i < files.size(); i++) cleanup.append("rm -f -- ").append(quote(staging + "/file-" + i)).append('\n');
                cleanup.append("rm -f -- ").append(quote(staging + "/check.service")).append("\nif test -d ").append(quote(staging)).append("; then rmdir -- ").append(quote(staging)).append("; fi\n");
                try { if (ssh.run(cleanup.toString(), false, null, 30, () -> false).exitCode() != 0) step.accept("TEMP_CLEANUP_FAILED"); }
                catch (RuntimeException failure) { step.accept("TEMP_CLEANUP_FAILED"); }
            }
        }
    }
    public static String safeDirectory(String directory) {
        StringBuilder result = new StringBuilder(); String prefix = "";
        for (String part : directory.split("/")) if (!part.isEmpty()) {
            prefix += "/" + part;
            result.append("test ! -L ").append(quote(prefix)).append("\n");
        }
        return result.toString();
    }
    static String apply(KylinPlanService.Prepared prepared, List<KylinTemplates.File> files, String staging) {
        StringBuilder script = new StringBuilder("set -eu\n");
        for (int i = 0; i < files.size(); i++) {
            var file = files.get(i); int index = prepared.paths().indexOf(file.path());
            String before = prepared.before().get("file." + index);
            String parent = file.path().substring(0, file.path().lastIndexOf('/'));
            script.append(safeDirectory(parent));
            if (file.path().startsWith("/usr/local/")) script.append("install -d -m 755 -o root -g root -- ").append(quote(parent)).append('\n');
            script.append("test ! -L ").append(quote(file.path())).append('\n');
            String[] attrs = before.split("\\|");
            String owner = prepared.view().feature().equals("opengauss") ? attrs[2] : "0";
            String group = prepared.view().feature().equals("opengauss") ? attrs[3] : "0";
            script.append("install -o ").append(owner).append(" -g ").append(group).append(" -m ").append(file.mode()).append(" -- ")
                    .append(quote(staging + "/file-" + i)).append(' ').append(quote(file.path())).append('\n');
        }
        for (var file : files) if (file.path().endsWith(".service"))
            script.append("systemd-analyze verify ").append(quote(file.path())).append('\n');
        switch (prepared.view().feature()) {
            case "reboot" -> script.append("systemctl daemon-reload\nsystemctl enable --now cron.service\nsystemctl enable toolhelper-autologin-cleanup.service\n");
            case "logs" -> {
                if (prepared.view().action().equals("CLEAN_NOW")) script.append("/usr/local/bin/clean-logs.sh\n");
                else script.append("systemctl enable --now cron.service\n");
            }
            case "vnc" -> {
                script.append("systemctl daemon-reload\n");
                if (prepared.view().action().equals("DEPLOY")) script.append("systemctl enable --now x11vnc.service\n");
                else script.append("systemctl ").append(prepared.view().action().toLowerCase(Locale.ROOT)).append(" x11vnc.service\n");
            }
            case "opengauss" -> {
                if (prepared.view().action().equals("RELOAD")) script.append("owner=$(stat -c '%U' -- ").append(quote(KylinPolicyCatalog.DATABASE)).append(")\n")
                        .append("runuser -l \"$owner\" -c ").append(quote("gs_ctl reload -D " + quote(KylinPolicyCatalog.DATABASE))).append('\n');
            }
            case "optimization" -> {
                for (var opt : prepared.optimizations()) {
                    for (String unit : opt.units()) {
                        int index = prepared.units().indexOf(unit);
                        if (!prepared.before().get("unit." + index).equals("MISSING")) script.append("systemctl disable --now ").append(quote(unit))
                                .append("\nsystemctl mask ").append(quote(unit)).append('\n');
                    }
                    for (String path : opt.files()) if (!prepared.before().get("file." + prepared.paths().indexOf(path)).equals("MISSING"))
                        script.append("chmod a-x -- ").append(quote(path)).append('\n');
                }
            }
            default -> throw new IllegalStateException("Unexpected feature");
        }
        return script.toString();
    }
    static String restore(Backup backup) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < backup.paths().size(); i++) sources.put(backup.paths().get(i), KylinPolicyCatalog.BACKUPS + backup.backupId() + "/file-" + i);
        return restore(backup, sources);
    }
    static String restore(Backup backup, Map<String, String> sources) {
        StringBuilder script = new StringBuilder("set -eu\n");
        for (int i = 0; i < backup.units().size(); i++) {
            if (backup.state().get("unit." + i).equals("MISSING"))
                script.append("if load=$(systemctl show -p LoadState --value ").append(quote(backup.units().get(i)))
                        .append("); then :; elif test \"$load\" != not-found; then exit 1; fi\n")
                        .append("if test \"$load\" != not-found; then systemctl disable --now ").append(quote(backup.units().get(i))).append("; fi\n");
        }
        for (int i = 0; i < backup.paths().size(); i++) {
            String path = backup.paths().get(i), original = backup.state().get("file." + i);
            script.append(safeDirectory(path.substring(0, path.lastIndexOf('/')))).append("test ! -L ").append(quote(path)).append('\n');
            if (original.equals("MISSING")) script.append("rm -f -- ").append(quote(path)).append('\n');
            else {
                String source = Objects.requireNonNull(sources.get(path));
                String[] attrs = original.split("\\|");
                script.append(safeDirectory(source.substring(0, source.lastIndexOf('/')))).append("test ! -L ").append(quote(source)).append("\nprintf '%s  %s\\n' ").append(quote(attrs[0])).append(' ').append(quote(source))
                        .append(" | sha256sum -c - >/dev/null\ninstall -o ").append(attrs[2]).append(" -g ").append(attrs[3]).append(" -m ").append(attrs[1])
                        .append(" -- ").append(quote(source)).append(' ').append(quote(path)).append('\n');
            }
        }
        script.append("systemctl daemon-reload\n");
        for (int i = 0; i < backup.units().size(); i++) {
            String value = backup.state().get("unit." + i), unit = quote(backup.units().get(i));
            if (value.equals("MISSING")) continue;
            String[] states = value.split("\\|"); String active = states[1], enabled = states[2];
            script.append("systemctl unmask ").append(unit).append('\n');
            if (enabled.equals("enabled") || enabled.equals("enabled-runtime")) script.append("systemctl enable ").append(enabled.endsWith("runtime") ? "--runtime " : "").append(unit).append('\n');
            else if (enabled.equals("disabled") || enabled.startsWith("masked")) script.append("systemctl disable ").append(unit).append('\n');
            script.append("systemctl ").append(active.equals("active") ? "start " : "stop ").append(unit).append('\n');
            if (enabled.startsWith("masked")) script.append("systemctl mask ").append(enabled.endsWith("runtime") ? "--runtime " : "").append(unit).append('\n');
        }
        return script.toString();
    }
    private static void validateServiceStates(Map<String, String> values) {
        for (var entry : values.entrySet()) if (entry.getKey().startsWith("unit.") && !entry.getValue().equals("MISSING")) {
            String[] parts = entry.getValue().split("\\|");
            if (parts.length != 3 || !Set.of("active", "inactive").contains(parts[1])
                    || !Set.of("enabled", "enabled-runtime", "disabled", "masked", "masked-runtime", "static", "indirect").contains(parts[2]))
                throw new ApiException(409, "KYLIN_UNRESTORABLE_STATE", "服务处于无法可靠恢复的状态，请先核对目标");
        }
    }
    private static void verify(KylinPlanService.Prepared prepared, List<KylinTemplates.File> files, Map<String, String> after) {
        if (prepared.restore() != null) {
            if (!prepared.restore().backup().state().equals(after)) throw new ApiException(409, "KYLIN_RESTORE_VERIFY_FAILED", "恢复后状态与原始记录不一致");
            return;
        }
        for (var file : files) {
            String value = after.get("file." + prepared.paths().indexOf(file.path()));
            if (!value.startsWith(KylinFiles.hash(file.bytes()) + "|") || !value.split("\\|")[1].equals(file.mode()))
                throw new ApiException(409, "KYLIN_FILE_VERIFY_FAILED", "文件内容或权限验证失败");
        }
        if (prepared.view().feature().equals("reboot") || prepared.view().feature().equals("logs") && prepared.view().action().equals("DEPLOY")) {
            if (!after.get("unit.0").endsWith("|active|enabled"))
                throw new ApiException(409, "KYLIN_SERVICE_VERIFY_FAILED", "cron服务未达到运行及自启状态");
            if (prepared.view().feature().equals("reboot") && !after.get("unit.1").endsWith("|enabled"))
                throw new ApiException(409, "KYLIN_SERVICE_VERIFY_FAILED", "自动登录清理服务未启用");
        }
        if (prepared.view().feature().equals("vnc")) {
            String state = after.get("unit.0");
            String expected = prepared.view().action().equals("STOP") ? "inactive" : "active";
            if (!state.contains("|" + expected + "|")) throw new ApiException(409, "KYLIN_SERVICE_VERIFY_FAILED", "VNC服务运行状态未达到预期");
        }
        if (prepared.view().feature().equals("optimization")) {
            for (int i = 0; i < prepared.paths().size(); i++) if (!prepared.before().get("file." + i).equals("MISSING")) {
                String value = after.get("file." + i);
                if (value.equals("MISSING") || (Integer.parseInt(value.split("\\|")[1], 8) & 0111) != 0)
                    throw new ApiException(409, "KYLIN_OPTIMIZATION_VERIFY_FAILED", "选中文件未移除执行位");
            }
            for (int i = 0; i < prepared.units().size(); i++) if (!prepared.before().get("unit." + i).equals("MISSING"))
                if (!after.get("unit." + i).equals("masked|inactive|masked") && !after.get("unit." + i).endsWith("|inactive|masked"))
                    throw new ApiException(409, "KYLIN_OPTIMIZATION_VERIFY_FAILED", "选中服务未完成停止及mask");
        }
    }
    private static void require(KylinSshClient.Reply result, String code, String message) {
        if (result.exitCode() != 0) throw new ApiException(409, code, message + "（退出码" + result.exitCode() + "）");
    }
}
