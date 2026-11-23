package com.toolhelper.plugin.electerm;

import com.toolhelper.support.ApiException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class ProcessAdapterTest {
    private final JsonMapper json = new JsonMapper();
    private final ProcessAdapter adapter = new ProcessAdapter(json);
    @Test void passesAllFourProtocolsWithoutReparsingAddressesOrUsernames() {
        for (String protocol : List.of("SSH", "SFTP", "RDP", "VNC")) {
            String username = "DOMAIN\\test@name \"quoted\" & calc.exe";
            List<String> command = adapter.command(Path.of("C:/plugin folder/electerm.exe"), new ConnectionRequest(protocol, "2001:db8::1", 4433, username));
            assertEquals("--tp", command.get(1));
            assertEquals(protocol.equals("SFTP") ? "ssh" : protocol.toLowerCase(Locale.ROOT), command.get(2));
            assertEquals("--opts", command.get(3));
            var options = json.readTree(command.get(4));
            assertEquals("2001:db8::1", options.path("host").asText());
            assertEquals(4433, options.path("port").asInt());
            assertEquals(username, options.path("username").asText());
            assertEquals(protocol.equals("SFTP"), command.contains("--sftp-only"));
            assertFalse(options.has("password"));
        }
    }
    @Test void validatesIpPortsAndRequiredUsers() {
        assertEquals("192.168.1.2", ProcessAdapter.validate(new ConnectionRequest("SSH", " 192.168.1.2 ", 22, " user ")).host());
        assertEquals("", ProcessAdapter.validate(new ConnectionRequest("VNC", "::1", 5900, null)).username());
        for (String host : List.of("localhost", "1.2.3.999", "01.2.3.4", "1.2.3.4 & cmd", "fe80::1%1", "::bad::", "")) {
            assertThrows(ApiException.class, () -> ProcessAdapter.validate(new ConnectionRequest("SSH", host, 22, "root")));
        }
        for (int port : List.of(0, -1, 65536)) assertThrows(ApiException.class, () -> ProcessAdapter.validate(new ConnectionRequest("RDP", "127.0.0.1", port, "u")));
        for (String username : List.of("", "u\nname", "x".repeat(257))) assertThrows(ApiException.class, () -> ProcessAdapter.validate(new ConnectionRequest("SFTP", "127.0.0.1", 22, username)));
        assertEquals("PROTOCOL_NOT_SUPPORTED", assertThrows(ApiException.class, () -> ProcessAdapter.validate(new ConnectionRequest("FTP", "127.0.0.1", 21, "u"))).code());
    }

    @Test void nativeProcessKeepsJsonQuotesAndDomainUsernameIntact() throws Exception {
        var command = adapter.command(Path.of("C:/plugin folder/electerm.exe"),
                new ConnectionRequest("RDP", "2001:db8::1", 3390, "域\\user@host \"quoted\" & harmless"));
        List<String> child = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ArgumentsEcho.class.getName()));
        child.addAll(command.subList(1, command.size()));
        Process process = new ProcessBuilder(child).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            List<String> actual = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(value -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).toList();
            assertEquals(command.subList(1, command.size()), actual);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static class ArgumentsEcho {
        public static void main(String[] args) {
            for (String arg : args) System.out.println(Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
