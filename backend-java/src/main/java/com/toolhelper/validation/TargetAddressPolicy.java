package com.toolhelper.validation;

import com.toolhelper.support.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.Arrays;
import static com.toolhelper.validation.ValidationModels.*;

public final class TargetAddressPolicy {
    private TargetAddressPolicy() {}
    public static URI origin(LoginRequest request) {
        if (request == null || !Set.of("http", "https").contains(String.valueOf(request.scheme()))) invalid();
        String host = request.host() == null ? "" : request.host().trim();
        boolean valid = !host.isEmpty() && host.length() <= 253;
        if (host.contains(":")) {
            try { valid &= host.matches("[0-9A-Fa-f:.]+") && InetAddress.getByName(host) instanceof Inet6Address; }
            catch (UnknownHostException failure) { valid = false; }
        } else if (host.matches("[0-9.]+")) {
            valid &= host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") && Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255 && (part.length() == 1 || !part.startsWith("0")));
        } else valid &= Arrays.stream(host.split("\\.", -1)).allMatch(label -> label.length() <= 63 && label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"));
        if (!valid || request.port() == null || request.port() < 1 || request.port() > 65535) invalid();
        if (request.scheme().equals("http") && !Boolean.TRUE.equals(request.allowInsecureHttp())) {
            throw new ApiException(400, "INSECURE_HTTP_CONFIRMATION_REQUIRED", "HTTP 不保护凭据和 Token，请确认仅在可信网络使用");
        }
        try { return new URI(request.scheme(), null, host, request.port(), null, null, null); }
        catch (URISyntaxException invalid) { throw new ApiException(400, "INVALID_TARGET", "目标地址格式不正确"); }
    }
    public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    public static void invalid() { throw new ApiException(400, "INVALID_TARGET", "请填写 http/https、合法主机和整数端口"); }
}
