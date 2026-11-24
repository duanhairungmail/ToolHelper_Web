package com.toolhelper.plugin.dbx;

import com.toolhelper.plugin.common.DesktopProcessAdapter;
import com.toolhelper.support.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.toolhelper.plugin.dbx.DbxModels.*;

@Component
public class DbxProcessAdapter extends DesktopProcessAdapter {
    public DbxProcessAdapter() { super("DBX"); }

    public static ConnectionRequest validate(ConnectionRequest request) {
        if (request == null || !Set.of("MYSQL", "POSTGRESQL").contains(Objects.toString(request.type(), ""))) {
            throw new ApiException(422, "DATABASE_TYPE_NOT_SUPPORTED", "只支持 MySQL 和 PostgreSQL");
        }
        String host = text(request.host(), 253, true).trim();
        boolean valid;
        if (host.contains(":")) {
            try { valid = host.matches("[a-fA-F0-9:.]+") && InetAddress.getByName(host) instanceof Inet6Address; }
            catch (UnknownHostException invalid) { valid = false; }
        } else if (host.matches("[0-9.]+")) {
            valid = host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                    && Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255 && (part.length() == 1 || !part.startsWith("0")));
        } else {
            valid = Arrays.stream(host.split("\\.", -1)).allMatch(label -> label.length() <= 63 && label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"));
        }
        if (!valid || request.port() == null || request.port() < 1 || request.port() > 65535) invalid();
        String username = text(request.username(), 256, true);
        String database = text(request.database(), 256, false);
        String name = text(request.connectionName(), 256, false);
        if (request.password() != null) text(request.password(), 2048, false);
        return new ConnectionRequest(request.type(), host, request.port(), username, database, name,
                Boolean.TRUE.equals(request.ssl()), request.password());
    }

    private static String text(String value, int max, boolean required) {
        String result = value == null ? "" : value;
        if (result.length() > max || result.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)) || (required && result.isBlank())) invalid();
        return result;
    }
    private static void invalid() { throw new ApiException(400, "INVALID_CONNECTION_PARAMETERS", "请输入合法 IP/DNS、1～65535 端口及用户名；字段不得包含控制字符或超过长度限制"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    /** 单个固定协议参数，所有值逐项编码；用户字段不能变成额外 CLI 参数。 */
    public List<String> command(Path executable, ConnectionRequest request) {
        ConnectionRequest value = validate(request);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("v", "1"); fields.put("type", value.type().equals("MYSQL") ? "mysql" : "postgres");
        fields.put("host", value.host()); fields.put("port", value.port().toString()); fields.put("user", value.username());
        fields.put("database", value.database()); fields.put("name", value.connectionName()); fields.put("ssl", value.ssl().toString());
        fields.put("one_time", "true");
        if (value.password() != null) fields.put("password", value.password());
        StringJoiner query = new StringJoiner("&", "dbx://connection/new?", "");
        fields.forEach((key, entry) -> query.add(key + "=" + encode(entry)));
        String uri = query.toString();
        if (uri.length() + executable.toString().length() + 8 > 28000) {
            throw new ApiException(400, "CONNECTION_PARAMETERS_TOO_LONG", "编码后的连接参数超过 Windows 命令行安全长度，请缩短字段");
        }
        return List.of(executable.toString(), uri);
    }

    public Map<String, Object> connect(Path executable, Path data, ConnectionRequest request) throws IOException, InterruptedException {
        requireDesktop();
        requireOwnedInstance(observe(executable));
        long pid = start(command(executable, request), executable.getParent(), data);
        return Map.of("status", "CONNECTION_REQUESTED", "pid", pid, "message", "已向 DBX 提交连接请求，请在 DBX 查看实际连接或认证结果");
    }

    @Override protected void configureEnvironment(ProcessBuilder builder, Path data) throws IOException {
        String appData = System.getenv("APPDATA");
        requireNoImplicitImport(data, appData == null ? null : Path.of(appData, "com.dbx.app", "dbx.db"));
        builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("DBX_DATA_DIR"));
        for (var entry : Map.of("WEBVIEW2_USER_DATA_FOLDER", "webview2", "DBX_STARTUP_LOG_DIR", "logs", "TEMP", "tmp", "TMP", "tmp").entrySet()) {
            Path directory = data.resolve(entry.getValue());
            SecurePaths.createDirectories(directory);
            builder.environment().put(entry.getKey(), directory.toString());
        }
    }

    public static void requireNoImplicitImport(Path data, Path defaultDatabase) throws IOException {
        SecurePaths.checkAncestors(data);
        if (!Files.exists(data.resolve("dbx.db"), LinkOption.NOFOLLOW_LINKS) && defaultDatabase != null
                && Files.exists(defaultDatabase, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(409, "DBX_EXISTING_DATA_CONFLICT", "检测到用户默认 DBX 数据，已阻止首次启动的自动导入；请先确认隔离或迁移方式");
        }
    }
}
