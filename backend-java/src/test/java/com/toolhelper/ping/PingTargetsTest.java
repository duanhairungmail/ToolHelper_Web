package com.toolhelper.ping;

import com.toolhelper.config.PingProperties;
import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.ping.PingModels.*;

class PingTargetsTest {
    private final PingProperties properties = new PingProperties(4096, 200, 2 * 1024 * 1024, 4 * 1024 * 1024, 65536, 20, 30);
    @Test void expandsIpv4CidrAndRangeDeduplicatesInInputOrderWithoutProbing() {
        ParsedTargets result = PingTargets.parse(new ParseRequest("# 地址\n192.168.1.0/30\n192.168.1.2\n192.168.1.8-10", null), properties);
        assertEquals(java.util.List.of("192.168.1.1", "192.168.1.2", "192.168.1.8", "192.168.1.9", "192.168.1.10"), result.targets());
        assertEquals(5, result.total());
    }
    @Test void rejectsInvalidRowsCidrsAndTargetOverflowAsWholeRequest() {
        ApiException line = assertThrows(ApiException.class, () -> PingTargets.parse(new ParseRequest("127.0.0.1\nhost.local", null), properties));
        assertEquals("INVALID_PING_TARGETS", line.code());
        assertTrue(line.getMessage().contains("第 2 行"));
        assertThrows(ApiException.class, () -> PingTargets.parse(new ParseRequest("192.168.0.0/19", null), properties));
        assertThrows(ApiException.class, () -> PingTargets.parse(new ParseRequest("192.168.0.8-7", null), properties));
        assertThrows(ApiException.class, () -> PingTargets.ipv4("192.168.001.1"));
        assertThrows(ApiException.class, () -> PingTargets.ipv4("example.test"));
    }
    @Test void enforcesCidrNetworkBroadcastFileSizeAndUtf8Rules() {
        ParsedTargets allHosts = PingTargets.parse(new ParseRequest("192.168.2.0/24", null), properties);
        assertEquals(254, allHosts.total());
        assertEquals("192.168.2.1", allHosts.targets().getFirst());
        assertEquals("192.168.2.254", allHosts.targets().getLast());
        String utf8 = Base64.getEncoder().encodeToString("127.0.0.1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(java.util.List.of("127.0.0.1"), PingTargets.parse(new ParseRequest(null, utf8), properties).targets());
        String invalidUtf8 = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff});
        assertThrows(ApiException.class, () -> PingTargets.parse(new ParseRequest(null, invalidUtf8), properties));
    }
}
