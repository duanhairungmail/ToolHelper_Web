package com.toolhelper.plugin.electerm;

import com.toolhelper.config.SystemProxySupport;
import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.ApiException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class SystemProxySupportTest {
    @TempDir Path root;

    @Test void freshJvmEnablesSystemProxyBeforeFirstNetworkUse() throws Exception {
        assertTrue(probe(List.of(), "property").contains("SYSTEM_PROXY=true"));
    }

    @Test void preservesExplicitJvmPreference() throws Exception {
        assertTrue(probe(List.of("-Djava.net.useSystemProxies=false"), "property").contains("SYSTEM_PROXY=false"));
    }

    @Test void realReleaseRequestUsesHttpConnectAndReportsProxyFailure() throws Exception {
        // 本机代理夹具只接收 CONNECT 后拒绝隧道，不访问 GitHub，也不修改系统代理设置。
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            proxy.setSoTimeout(10_000);
            Future<String> request = executor.submit(() -> {
                try (Socket socket = proxy.accept()) {
                    socket.setSoTimeout(5_000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    String firstLine = input.readLine();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    return firstLine;
                }
            });
            String output = probe(List.of("-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=" + proxy.getLocalPort(),
                    "-Dhttp.nonProxyHosts="), "request");
            assertEquals("CONNECT api.github.com:443 HTTP/1.1", request.get(2, TimeUnit.SECONDS));
            assertTrue(output.contains("UPSTREAM_CONNECTION_FAILED"), output);
            assertTrue(output.contains("mode=HTTP_PROXY"), output);
        }
    }

    @Test void socksSelectionSurvivesHttpClientExceptionWrapping() throws Exception {
        assertTrue(probe(List.of(), "socks").contains("PROXY_TYPE_UNSUPPORTED"));
    }

    private String probe(List<String> options, String action) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(options);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName(), action, root.toString()));
        Path output = root.resolve(UUID.randomUUID() + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "代理测试子进程超时");
            String result = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), result);
            return result;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            SystemProxySupport.initialize();
            if (args[0].equals("property")) {
                System.out.println("SYSTEM_PROXY=" + System.getProperty("java.net.useSystemProxies"));
                // 开关设置后才触发 JDK 的默认选择器初始化。
                Objects.requireNonNull(ProxySelector.getDefault());
                return;
            }
            if (args[0].equals("socks")) {
                ProxySelector.setDefault(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) {
                        return List.of(new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 18766)));
                    }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                });
            }
            ReleaseClient client = new ReleaseClient(ToolHelperProperties.forTests(Path.of(args[1])), new JsonMapper());
            try (InputStream body = client.open(URI.create("https://api.github.com/repos/electerm/electerm/releases/latest"), Duration.ofSeconds(5), true)) {
                throw new AssertionError("代理夹具不应返回成功响应");
            } catch (ApiException expected) {
                System.out.println("ERROR_CODE=" + expected.code());
            } finally { client.close(); }
        }
    }
}
