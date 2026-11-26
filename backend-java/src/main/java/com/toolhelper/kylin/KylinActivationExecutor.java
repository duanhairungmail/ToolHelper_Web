package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import java.time.Instant;
import java.util.regex.Pattern;
import static com.toolhelper.kylin.KylinModels.*;

/** 提示驱动的有限状态机：二维码出现后，只有人工提交才能继续原进程。 */
public final class KylinActivationExecutor {
    private KylinActivationExecutor() {}
    private static boolean enter(String value) { return value.contains("Press Enter to continue") || value.contains("请按回车键继续"); }
    private static boolean serial(String value) { return value.contains("Please enter the service serial number") || value.contains("请输入服务序列号"); }
    private static boolean code(String value) { return value.contains("Please enter the activation code") || value.contains("请输入激活码"); }
    /** legacy流程自动携带LICENSE序列号，不能把表单序列号误送到激活码提示。 */
    private static boolean legacySerialMatches(String value, String expected) {
        if (!value.contains("Above is the registration code and serial number") && !value.contains("以上是注册码和序列号")) return false;
        String clean = value.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "").replace("\r", "");
        var matcher = Pattern.compile("(?m)^[A-Za-z0-9-]{8,128}[ \\t]+([0-9]{8,128})[ \\t]*$").matcher(clean);
        String observed = null;
        while (matcher.find()) { if (observed != null) return false; observed = matcher.group(1); }
        return expected.equals(observed);
    }
    private static boolean rejected(String value) { return value.contains("无效的激活码") || value.contains("Invalid activation code") || value.contains("Please try again:") || value.contains("请重试") || value.contains("please re-enter") || value.contains("请重新输入") || Pattern.compile("ret=(?!0(?:\\D|$))[0-9]+").matcher(value).find(); }
    public static void execute(KylinActivation.Operation job, KylinSshClient ssh, boolean sudo,
            ActivationRequest request) {
        job.status = "RUNNING";
        if (job.cancelled) throw new ApiException(409, "CANCELLED", "已取消激活任务");
        job.scan = KylinProbe.scan(ssh, "activation", sudo, request.sudoPassword());
        String state = job.scan.findings().getFirst().status();
        if (state.equals("ACTIVATED")) throw new ApiException(409, "KYLIN_ALREADY_ACTIVATED", "系统已激活，不允许再次激活；取消激活或续期需手动操作");
        if (!state.equals("NOT_ACTIVATED")) throw new ApiException(409, "KYLIN_ACTIVATION_UNKNOWN", "无法明确当前激活状态，请先核对后再操作");
        String actual = job.scan.activation().serialNumber();
        if (actual != null && !actual.equals(request.serialNumber()) && !request.serialDifferenceConfirmed())
            throw new ApiException(409, "KYLIN_SERIAL_DIFFERENCE", "输入序列号与目标KEY不同，请核对差异并明确确认");
        job.step = "GETTING_QR"; job.message = "正在获取目标二维码";
        try (var terminal = ssh.activationChannel(sudo)) {
            int offset = 0, stage = 0;
            boolean passwordSent = false, serialSent = false, initialEnterSent = false;
            Instant deadline = Instant.now().plusSeconds(45);
            String submittedCode = null;
            while (true) {
                if (job.cancelled) throw new ApiException(409, "CANCELLED", "已停止后续步骤；如已提交激活码，请重新检测目标状态");
                String all = terminal.output();
                String tail = all.substring(Math.min(offset, all.length()));
                if (tail.contains("[TOOLHELPER_SUDO]")) {
                    if (passwordSent) throw new ApiException(403, "KYLIN_SUDO_AUTH_FAILED", "sudo密码验证失败，请重新输入");
                    if (request.sudoPassword() == null || request.sudoPassword().isEmpty()) throw new ApiException(403, "KYLIN_SUDO_PASSWORD_REQUIRED", "请输入当前SSH用户的sudo密码");
                    terminal.send(request.sudoPassword()); passwordSent = true; offset = all.length();
                } else if (tail.contains("not in the sudoers") || tail.contains("not allowed to execute")) {
                    throw new ApiException(403, "KYLIN_SUDO_FORBIDDEN", "当前用户没有所需sudo权限");
                } else if (rejected(tail)) {
                    throw new ApiException(409, "KYLIN_ACTIVATION_REJECTED", "目标拒绝序列号或激活码；未自动重试，请核对后重新检测");
                } else if (stage == 0 && (tail.contains("Enter \"kylin\"") || tail.contains("输入“kylin”") || tail.contains("输入\"kylin\""))) {
                    terminal.send("kylin"); offset = all.length();
                } else if (stage == 0 && enter(tail)) {
                    byte[] png = KylinActivation.qrPng(tail);
                    if (png != null) {
                        job.waiting(png); stage = 1; offset = all.length(); deadline = job.expiresAt;
                    } else if (!tail.contains("/* XPM */") && !tail.contains("Registration information TWO-DIMENSIONAL") && !tail.contains("注册信息二维码")) {
                        // 厂商脚本在生成二维码前有一次独立回车提示；只处理该已核实前置步骤。
                        if (initialEnterSent)
                            throw new ApiException(409, "KYLIN_QR_UNSUPPORTED", "未获得可扫描二维码，未继续激活");
                        terminal.send(""); initialEnterSent = true; offset = all.length();
                    } else throw new ApiException(409, "KYLIN_QR_UNSUPPORTED", "目标二维码不完整或格式不受支持，未继续激活");
                } else if (stage == 1 && job.submitted) {
                    submittedCode = job.takeCode(); terminal.send(""); stage = 2; offset = all.length(); deadline = Instant.now().plusSeconds(45);
                    job.step = "ACTIVATING";
                } else if (stage == 2 && serial(tail)) {
                    if (serialSent) throw new ApiException(409, "KYLIN_ACTIVATION_REJECTED", "目标未接受服务序列号");
                    terminal.send(request.serialNumber()); serialSent = true; offset = all.length();
                } else if (stage == 2 && code(tail)) {
                    if (!serialSent && !legacySerialMatches(tail, request.serialNumber()))
                        throw new ApiException(409, "KYLIN_ACTIVATION_UNSUPPORTED", "旧版命令未请求服务序列号，且内置序列号与填写值无法确认一致；未提交激活码，请手动核对");
                    terminal.send(submittedCode); submittedCode = null; stage = 3; offset = all.length();
                }
                if (terminal.closed()) {
                    String finalOutput = terminal.output();
                    String result = finalOutput.substring(Math.min(offset, finalOutput.length()));
                    if (stage != 3 || terminal.exitCode() == null || terminal.exitCode() != 0 || !result.contains("[system_real]11, ret=0") || rejected(result))
                        throw new ApiException(502, "KYLIN_ACTIVATION_RESULT_UNKNOWN", "目标未返回已核实的成功结果，请重新检测；未自动重试");
                    break;
                }
                if (!Instant.now().isBefore(deadline)) throw new ApiException(504, stage == 1 ? "KYLIN_SCAN_WAIT_TIMEOUT" : "SSH_COMMAND_TIMEOUT", stage == 1 ? "等待扫码超过10分钟，请重新开始" : "激活交互超时，请重新检测目标状态");
                terminal.pause();
            }
        }
        job.step = "VERIFYING"; job.message = "命令返回成功，正在重新检测激活状态";
        job.scan = KylinProbe.scan(ssh, "activation", sudo, request.sudoPassword());
        if (!job.scan.findings().getFirst().status().equals("ACTIVATED"))
            throw new ApiException(502, "KYLIN_ACTIVATION_NOT_VERIFIED", "命令报告成功，但检测未确认已激活，请核对目标状态");
        job.status = "SUCCEEDED"; job.step = "COMPLETED"; job.message = "激活命令成功，激活检测确认系统已激活";
    }
}
