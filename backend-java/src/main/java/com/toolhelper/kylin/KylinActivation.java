package com.toolhelper.kylin;

import com.toolhelper.support.ApiException;
import com.toolhelper.support.TableExport;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import static com.toolhelper.kylin.KylinModels.*;

/** 激活元信息、严格XPM解码和一次性人工继续信号；不保存原始终端输出。 */
public final class KylinActivation {
    private KylinActivation() {}
    public static final int WAIT_SECONDS = 600;
    public static final String SERIAL_SCRIPT = """
            if test ! -f /etc/.kyinfo; then echo serial.status=MISSING
            elif test ! -r /etc/.kyinfo; then echo serial.status=UNREADABLE
            else
              awk 'BEGIN { n=0 } /^[[:space:]]*[Kk][Ee][Yy][[:space:]]*=/ {
                n++; sub(/^[^=]*=/, ""); gsub(/^[[:space:]]+|[[:space:]]+$/, ""); value=$0
              } END { if(n==1 && length(value)>0 && length(value)<=128 && value ~ /^[A-Za-z0-9_-]+$/) {
                print "serial.status=AVAILABLE"; print "serial.value=" value
              } else print "serial.status=INVALID" }' /etc/.kyinfo
            fi
            """;
    public static ActivationInfo parseSerial(String output) {
        var values = KylinProbe.labels(output);
        String status = values.getOrDefault("serial.status", "INVALID"), value = values.get("serial.value");
        if (status.equals("AVAILABLE") && value != null && value.matches("[A-Za-z0-9_-]{1,128}")) return new ActivationInfo(value, status);
        return new ActivationInfo(null, Set.of("MISSING", "UNREADABLE", "INVALID").contains(status) ? status : "INVALID");
    }
    public static String text(String value, String label, int limit, boolean required) {
        if (value == null) value = "";
        if ((required && value.isBlank()) || value.length() > limit || value.codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(400, "INVALID_ACTIVATION_FIELD", label + "为空、过长或含控制字符");
        return value;
    }
    public static byte[] export(ActivationExport request) {
        String station = text(request.stationName(), "变电站名称", 128, true);
        String serial = text(request.serialNumber(), "服务序列号", 128, true);
        String key = text(request.activationKey(), "激活密钥", 512, false);
        String code = text(request.activationCode(), "激活码", 512, false);
        return TableExport.generate("KylinOS激活信息", "xlsx", List.of(
                List.of("变电站名称", "服务序列号", "激活密钥", "激活码", "主机SN码"),
                List.of(station, serial, key.isBlank() ? "无" : key, code.isBlank() ? "无" : code, "由目标系统自动绑定")), new int[]{3000, 3000, 3200, 3200, 2998});
    }
    /** 仅接受qrencode两色、单字符XPM，像素逐一复制；不解析URL、不运行脚本、不重编码二维码载荷。 */
    public static byte[] qrPng(String terminal) {
        int begin = terminal.indexOf("/* XPM */");
        if (begin < 0) return null;
        var quoted = Pattern.compile("\"([^\"\\r\\n]*)\"").matcher(terminal.substring(begin));
        var strings = new ArrayList<String>();
        while (quoted.find()) strings.add(quoted.group(1));
        if (strings.isEmpty()) return null;
        var header = strings.getFirst().split(" ");
        if (header.length != 4 || !header[0].matches("[0-9]{1,3}") || !header[1].equals(header[0]) || !header[2].equals("2") || !header[3].equals("1"))
            throw unsupported();
        int size = Integer.parseInt(header[0]);
        if (size < 21 || size > 768) throw unsupported();
        if (strings.size() < size + 3) return null;
        Map<Character, Integer> colors = new HashMap<>();
        for (String color : strings.subList(1, 3)) {
            if (!color.matches(". c #(?:000000|[fF]{6})")) throw unsupported();
            colors.put(color.charAt(0), color.endsWith("#000000") ? 0 : 0xffffff);
        }
        if (colors.size() != 2 || !colors.containsValue(0) || !colors.containsValue(0xffffff)) throw unsupported();
        // 额外白边保证二维码安静区，使用整数放大，浏览器不必重新解释终端字符。
        int padding = 12, scale = 3;
        var image = new BufferedImage((size + 2 * padding) * scale, (size + 2 * padding) * scale, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (int y = 0; y < size; y++) {
                String row = strings.get(y + 3);
                if (row.length() != size) throw unsupported();
                for (int x = 0; x < size; x++) {
                    Integer rgb = colors.get(row.charAt(x)); if (rgb == null) throw unsupported();
                    if (rgb == 0) { graphics.setColor(java.awt.Color.BLACK); graphics.fillRect((x + padding) * scale, (y + padding) * scale, scale, scale); }
                }
            }
        } finally { graphics.dispose(); }
        try (var bytes = new ByteArrayOutputStream()) { ImageIO.write(image, "png", bytes); return bytes.toByteArray(); }
        catch (IOException failure) { throw new IllegalStateException("二维码图片生成失败"); }
    }
    private static ApiException unsupported() { return new ApiException(409, "KYLIN_QR_UNSUPPORTED", "当前目标二维码格式不受支持，未继续激活"); }

    public static final class Operation {
        public final String id = UUID.randomUUID().toString(), connectionId;
        public final Instant created = Instant.now();
        public volatile String status = "QUEUED", step = "PRECHECK", errorCode, message = "等待获取二维码";
        public volatile Instant expiresAt, finished;
        public volatile boolean cancelled, submitted;
        public volatile Scan scan;
        private byte[] qr;
        private String activationCode;
        public Operation(String connectionId) { this.connectionId = connectionId; }
        public synchronized void waiting(byte[] png) {
            if (cancelled) throw new ApiException(409, "CANCELLED", "已取消获取二维码");
            qr = png; expiresAt = Instant.now().plusSeconds(WAIT_SECONDS); step = "WAITING_CODE";
            message = "请微信扫码，输入密钥获取激活码，再回到此窗口提交";
        }
        public synchronized void submit(String code) {
            if (!step.equals("WAITING_CODE") || finished != null || submitted || cancelled || expiresAt == null || !Instant.now().isBefore(expiresAt))
                throw new ApiException(409, "KYLIN_ACTIVATION_NOT_WAITING", "激活任务已提交、失效或不在等待阶段，请查询状态");
            activationCode = text(code, "激活码", 512, true); submitted = true;
            step = "SUBMITTING_CODE"; message = "正在继续原终端激活流程"; notifyAll();
        }
        public synchronized String takeCode() {
            String value = activationCode; activationCode = null; return value;
        }
        public synchronized byte[] qr() {
            if (qr == null || finished != null || cancelled || expiresAt == null || !Instant.now().isBefore(expiresAt))
                throw new ApiException(409, "KYLIN_QR_EXPIRED", "二维码尚未就绪或已经失效");
            return qr.clone();
        }
        public synchronized void clear() { qr = null; activationCode = null; }
        public synchronized ActivationTask snapshot() {
            return new ActivationTask(id, connectionId, status, step, errorCode, message,
                    qr != null && finished == null && !cancelled && expiresAt != null && Instant.now().isBefore(expiresAt), expiresAt, scan);
        }
        public Task task() { return new Task(id, connectionId, "activation", "ACTIVATE", status, step, errorCode, message, null, created, finished); }
        @Override public String toString() { return "ActivationOperation[redacted]"; }
    }
}
