package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.validation.ValidationModels.*;

/** 仅调用固定业务目录；异常不带 URL/query/响应体，防止凭据进入全局异常日志。 */
@Component
public class TargetApiClient implements AutoCloseable {
    public static final class AuthException extends ApiException {
        public final int upstreamStatus;
        AuthException(int status) { super(401, "TARGET_AUTH_REQUIRED", "目标登录已失效或无访问权限，请手动重新登录"); upstreamStatus = status; }
    }
    public record Reply(int status, JsonNode body, boolean json) {}
    public record Page(List<JsonNode> rows, Long total) {}
    private final JsonMapper json;
    private final int timeoutSeconds;
    private final int maxBytes;
    private final HttpClient http;
    public TargetApiClient(JsonMapper json,
            @Value("${toolhelper.api-validation.timeout-seconds:15}") int timeoutSeconds,
            @Value("${toolhelper.api-validation.max-response-bytes:2097152}") int maxBytes) {
        if (timeoutSeconds < 1 || maxBytes < 128) throw new IllegalArgumentException("接口验证配额必须为正数");
        this.json = json; this.timeoutSeconds = timeoutSeconds; this.maxBytes = maxBytes;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build();
    }
    public String login(URI origin, LoginRequest request) throws InterruptedException {
        String user = field(request.username(), 256, true), password = field(request.password(), 2048, true);
        if (!Set.of("PLAIN", "AES_CBC").contains(String.valueOf(request.credentialMode()))
                || !Set.of("JSON", "LEGACY_QUERY").contains(String.valueOf(request.loginFormat()))) {
            throw new ApiException(400, "INVALID_LOGIN_MODE", "请选择明文/AES-CBC及JSON/旧版查询参数格式");
        }
        if (request.credentialMode().equals("AES_CBC")) { user = encrypt(user); password = encrypt(password); }
        String path = "/sys/login";
        String body = json.writeValueAsString(Map.of("userName", user, "password", password));
        if (request.loginFormat().equals("LEGACY_QUERY")) {
            path += "?userName=" + TargetAddressPolicy.encode(user) + "&password=" + TargetAddressPolicy.encode(password);
            body = "";
        }
        Reply reply = exchange(origin, path, "POST", body, null);
        if (reply.status() < 200 || reply.status() >= 300 || !reply.json() || businessFailure(reply.body())) {
            throw new ApiException(422, "TARGET_LOGIN_REJECTED", "目标登录失败（HTTP " + reply.status() + "），请核对账号、凭据模式及登录格式");
        }
        JsonNode token = reply.body().path("data").path("token");
        if (!token.isString()) token = reply.body().path("token");
        if (!token.isString() && reply.body().path("data").isString()) token = reply.body().path("data");
        if (!token.isString()) throw new ApiException(422, "TARGET_TOKEN_MISSING", "目标响应未包含有效 Token");
        String acquired = token.asText();
        if (acquired.isEmpty() || acquired.length() > 8192 || acquired.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new ApiException(422, "TARGET_TOKEN_INVALID", "目标响应包含不可用的 Token 格式");
        }
        return acquired;
    }
    public Reply call(URI origin, String token, String method, String path) throws InterruptedException {
        // path 只由服务端固定目录及编码后的设备 ID 构造，浏览器不能传入。
        Reply reply = exchange(origin, path, method, method.equals("POST") ? "{}" : null, token);
        if (reply.status() == 401 || reply.status() == 403 || authCode(reply.body())) {
            throw new AuthException(reply.status());
        }
        return reply;
    }
    private Reply exchange(URI origin, String path, String method, String body, String token) throws InterruptedException {
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\r") || path.contains("\n")) throw new IllegalArgumentException("非法内部 endpoint");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin.toString() + path)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token).header("token", token);
        if (method.equals("POST")) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else request.GET();
        var pending = http.sendAsync(request.build(), response -> new LimitedBody(maxBytes));
        try {
            HttpResponse<byte[]> response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            JsonNode parsed;
            boolean validJson = true;
            try { parsed = json.readTree(response.body()); if (parsed == null) throw new IllegalArgumentException(); }
            catch (RuntimeException invalid) { parsed = json.createObjectNode(); validJson = false; }
            return new Reply(response.statusCode(), parsed, validJson);
        } catch (InterruptedException cancelled) { pending.cancel(true); throw cancelled; }
        catch (TimeoutException timeout) { pending.cancel(true); throw new ApiException(504, "TARGET_TIMEOUT", "目标响应超时，未自动重试"); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpTimeoutException) throw new ApiException(504, "TARGET_TIMEOUT", "目标响应超时，未自动重试");
            if (cause instanceof BodyLimitException || cause != null && cause.getCause() instanceof BodyLimitException) throw new ApiException(422, "TARGET_RESPONSE_TOO_LARGE", "目标响应超出大小上限，已停止读取");
            throw new ApiException(502, "TARGET_NETWORK_ERROR", "无法访问目标，请核对协议、端口、网络及证书；不会绕过TLS校验");
        }
    }
    public Page page(URI origin, String token, int page, int size) throws InterruptedException {
        Reply reply = call(origin, token, "GET", "/device/deviceinfo/list?deviceName=&Address=&pageSize=" + size + "&page=" + page);
        if (reply.status() < 200 || reply.status() >= 300 || !reply.json() || businessFailure(reply.body())) {
            throw new ApiException(422, "DEVICE_QUERY_FAILED", "设备列表请求失败或业务响应异常（HTTP " + reply.status() + "）");
        }
        JsonNode data = reply.body().path("data"), rows = data;
        if (!rows.isArray()) for (String key : List.of("list", "records", "rows")) if (data.path(key).isArray()) { rows = data.path(key); break; }
        if (!rows.isArray()) throw new ApiException(422, "DEVICE_RESPONSE_INVALID", "设备列表不是已适配的数组/list/records/rows结构");
        List<JsonNode> items = new ArrayList<>(); rows.forEach(items::add);
        if (items.stream().anyMatch(item -> !item.isObject())) throw new ApiException(422, "DEVICE_RESPONSE_INVALID", "设备列表包含非对象记录");
        Long total = null;
        for (JsonNode parent : List.of(reply.body(), data)) for (String key : List.of("totalCount", "total", "count")) {
            JsonNode node = parent.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                try {
                    long number = Long.parseLong(node.asText());
                    if (number < 0 || total != null && total != number) throw new NumberFormatException();
                    total = number;
                }
                catch (RuntimeException invalid) { throw new ApiException(422, "PAGINATION_INVALID", "设备分页总数格式不正确"); }
            }
        }
        return new Page(List.copyOf(items), total);
    }
    public static String value(JsonNode row, String... keys) {
        for (String key : keys) {
            JsonNode node = row.path(key);
            if ((node.isString() || node.isNumber()) && !node.asText().isBlank()) return node.asText();
        }
        return "";
    }
    public static boolean businessFailure(JsonNode body) {
        if (body.path("success").isBoolean() && !body.path("success").asBoolean()) return true;
        JsonNode code = body.path("code");
        return !code.isMissingNode() && !code.isNull() && !Set.of("0", "200").contains(code.asText());
    }
    public static boolean businessSuccess(JsonNode body) {
        return Set.of("0", "200").contains(body.path("code").asText())
                || body.path("success").isBoolean() && body.path("success").asBoolean();
    }
    public static boolean authCode(JsonNode body) {
        if (Set.of("401", "403").contains(body.path("code").asText())) return true;
        // 已授权目标的实际旧协议：402 + 此消息表示未登录；普通 Payment Required 不据此清除登录。
        return body.path("code").asText().equals("402") && body.path("success").isBoolean()
                && !body.path("success").asBoolean() && body.path("msg").asText().equals("请登录系统再操作！");
    }
    public static String encrypt(String value) {
        try {
            // 上游旧协议的公开固定参数，仅为兼容，不能保护HTTP链路。
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("32DGoR8HdfIiw1judwJHY&^%1_aFSSJw".getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec("32DGoR8HdfIiw1ju".getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("本机AES不可用"); }
    }
    private static String field(String value, int limit, boolean required) {
        if (value == null || value.length() > limit || (required && value.isBlank())
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF)) {
            throw new ApiException(400, "INVALID_CREDENTIAL_FIELD", "凭据字段为空、过长或包含非法字符");
        }
        return value;
    }
    @Override public void close() { http.shutdownNow(); }

    private static class BodyLimitException extends IOException {}
    /** 在读取过程中限额，避免先下载完整响应再判断大小；整体Future超时也覆盖慢响应体。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) { subscription.cancel(); result.completeExceptionally(new BodyLimitException()); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
