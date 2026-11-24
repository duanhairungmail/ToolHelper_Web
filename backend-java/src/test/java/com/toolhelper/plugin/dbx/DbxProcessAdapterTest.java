package com.toolhelper.plugin.dbx;

import com.toolhelper.support.ApiException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.dbx.DbxModels.*;

class DbxProcessAdapterTest {
    @TempDir Path root;
    private final DbxProcessAdapter adapter = new DbxProcessAdapter();
    private ConnectionRequest request(String type, String host, String password) {
        return new ConnectionRequest(type, host, 15432, "域\\u@a&b", "库&名=one", "测试 #1😀", true, password);
    }
    @Test void fixedSingleDeepLinkKeepsEncodedValuesAndDoesNotUseShell() {
        var command = adapter.command(root.resolve("DBX.exe"), request("POSTGRESQL", "2001:db8::1", "a&b=中+%\""));
        assertEquals(2, command.size());
        var uri = URI.create(command.get(1));
        assertEquals("dbx", uri.getScheme()); assertEquals("connection", uri.getHost()); assertEquals("/new", uri.getPath());
        Map<String, String> query = new LinkedHashMap<>();
        for (String field : uri.getRawQuery().split("&")) {
            String[] pair = field.split("=", 2);
            assertNull(query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        }
        assertEquals("postgres", query.get("type")); assertEquals("true", query.get("one_time"));
        assertEquals("2001:db8::1", query.get("host")); assertEquals("15432", query.get("port"));
        assertEquals("域\\u@a&b", query.get("user")); assertEquals("库&名=one", query.get("database"));
        assertEquals("测试 #1😀", query.get("name")); assertEquals("a&b=中+%\"", query.get("password"));
        assertEquals("true", query.get("ssl"));
        assertFalse(request("MYSQL", "localhost", "secret").toString().contains("secret"));
    }
    @Test void missingAndEmptyPasswordsHaveDifferentMeaningAndMysqlMapping() {
        String absent = adapter.command(root.resolve("DBX.exe"), request("MYSQL", "db.example.test", null)).getLast();
        assertTrue(absent.contains("type=mysql")); assertFalse(absent.contains("password="));
        assertTrue(adapter.command(root.resolve("DBX.exe"), request("MYSQL", "localhost", "")).getLast().endsWith("password="));
    }
    @Test void nativeProcessReceivesOneCompleteEncodedArgument() throws Exception {
        String uri = adapter.command(root.resolve("DBX.exe"), request("POSTGRESQL", "::1", "pass&中+%#\"")).getLast();
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ArgumentsEcho.class.getName(), uri).start();
        try {
            assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertEquals(uri, new String(Base64.getDecoder().decode(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()), StandardCharsets.UTF_8));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    public static class ArgumentsEcho {
        public static void main(String[] args) {
            if (args.length != 1) throw new IllegalArgumentException("应当只有一个 URI 参数");
            System.out.println(Base64.getEncoder().encodeToString(args[0].getBytes(StandardCharsets.UTF_8)));
        }
    }
    @Test void rejectsControlCharactersUnknownTypesAndHostInjection() {
        for (String host : List.of("https://localhost", "--help", "u@host", "host/path", "256.1.2.3", "01.2.3.4", "localhost\r\n", "bad..host", "[::1]")) {
            assertThrows(ApiException.class, () -> adapter.command(root.resolve("DBX.exe"), request("MYSQL", host, null)), host);
        }
        assertThrows(ApiException.class, () -> adapter.command(root.resolve("DBX.exe"), request("REDIS", "localhost", null)));
        assertThrows(ApiException.class, () -> adapter.command(root.resolve("DBX.exe"), request("MYSQL", "localhost", "a\nb")));
        assertThrows(ApiException.class, () -> adapter.command(root.resolve("DBX.exe"), new ConnectionRequest("MYSQL", "localhost", 0, "u", "", "", false, null)));
    }
    @Test void rejectsImplicitImportAndKeepsChildEnvironmentInsideProject() throws Exception {
        Path data = root.resolve("app/data"); Files.createDirectories(data);
        Path existing = root.resolve("external/dbx.db"); Files.createDirectories(existing.getParent()); Files.writeString(existing, "external");
        assertEquals("DBX_EXISTING_DATA_CONFLICT", assertThrows(ApiException.class, () -> DbxProcessAdapter.requireNoImplicitImport(data, existing)).code());
        assertFalse(Files.exists(data.resolve("dbx.db")));
        var builder = new ProcessBuilder(root.resolve("DBX.exe").toString());
        builder.environment().put("DBX_DATA_DIR", "C:\\unmanaged");
        adapter.configureEnvironment(builder, data);
        assertFalse(builder.environment().containsKey("DBX_DATA_DIR"));
        assertEquals(data.resolve("webview2").toString(), builder.environment().get("WEBVIEW2_USER_DATA_FOLDER"));
        assertEquals(data.resolve("logs").toString(), builder.environment().get("DBX_STARTUP_LOG_DIR"));
        assertEquals(data.resolve("tmp").toString(), builder.environment().get("TEMP"));
        assertEquals("external", Files.readString(existing));
    }
}
