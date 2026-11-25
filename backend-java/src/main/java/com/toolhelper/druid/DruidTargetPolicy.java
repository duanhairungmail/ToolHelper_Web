package com.toolhelper.druid;

import com.toolhelper.support.ApiException;
import java.net.*;
import java.util.*;

public final class DruidTargetPolicy {
    private DruidTargetPolicy() {}
    public static URI normalize(String input) {
        if (input == null || input.isBlank() || input.length() > 2048 || !input.equals(input.trim())
                || input.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) invalid();
        try {
            URI uri = new URI(input);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (!Set.of("http", "https").contains(String.valueOf(scheme)) || host == null || host.length() > 253
                    || host.contains(":") || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) invalid();
            host = host.toLowerCase(Locale.ROOT);
            if (host.matches("[0-9.]+")) {
                String[] parts = host.split("\\.", -1);
                if (parts.length != 4 || Arrays.stream(parts).anyMatch(p -> !p.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(p) > 255)) invalid();
            } else if (!Arrays.stream(host.split("\\.", -1)).allMatch(label -> label.length() <= 63 && label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))) invalid();
            // 根路径采用明确的ASCII路径段；拒绝编码斜线、点段和分号等含糊映射。
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || path.equals("/")) path = "/druid/";
            if (!path.matches("/[A-Za-z0-9_./-]*") || path.contains("//")
                    || Arrays.stream(path.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))) invalid();
            if (path.endsWith("/index.html")) path = path.substring(0, path.length() - "index.html".length());
            if (path.substring(path.lastIndexOf('/') + 1).contains(".")) invalid();
            if (!path.endsWith("/")) path += "/";
            int port = uri.getPort(); if (port == (scheme.equals("https") ? 443 : 80)) port = -1;
            return new URI(scheme, null, host, port, path, null, null);
        } catch (URISyntaxException | NumberFormatException failure) { invalid(); return null; }
    }
    public static String origin(URI root) { return root.getScheme() + "://" + root.getRawAuthority(); }
    /** 前置DNS检查也占用目标工作槽，取消不能提前释放仍在解析的线程。 */
    public static void checkAddresses(URI root) throws UnknownHostException {
        String host = root.getHost();
        if (host.equals("metadata.google.internal") || host.equals("instance-data")) denied();
        for (InetAddress address : InetAddress.getAllByName(host)) {
            byte[] b = address.getAddress();
            if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()
                    || b.length == 4 && ((b[0] & 255) == 0 || (b[0] & 255) >= 224
                    || (b[0] & 255) == 100 && (b[1] & 255) == 100 && (b[2] & 255) == 100 && (b[3] & 255) == 200)) denied();
        }
    }
    public static URI redirect(URI root, URI from, String location) {
        try {
            if (location == null || location.length() > 2048) return null;
            URI next = from.resolve(location);
            String path = next.getRawPath();
            if (!origin(root).equals(origin(next)) || next.getRawUserInfo() != null || next.getRawQuery() != null || next.getRawFragment() != null
                    || !Set.of(root.getRawPath(), root.getRawPath() + "index.html", root.getRawPath() + "login.html").contains(path)) return null;
            return next;
        } catch (IllegalArgumentException failure) { return null; }
    }
    private static void denied() { throw new ApiException(400, "DRUID_TARGET_DENIED", "目标解析到不允许的地址类别"); }
    private static void invalid() { throw new ApiException(400, "INVALID_DRUID_TARGET", "请填写http/https、IPv4或DNS主机名及明确的监控根/首页URL；不接受凭据、查询串、IPv6或编码路径"); }
}
