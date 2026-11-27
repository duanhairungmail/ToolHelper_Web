package com.toolhelper.ping;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.ping.PingModels.*;

class SystemPingProbeTest {
    @Test void buildsFixedPlatformSpecificArgumentsAndValidatesIpBeforeCommand() {
        Options options = new Options(10, 4, 1500);
        assertEquals(java.util.List.of("ping.exe", "-4", "-n", "4", "-w", "1500", "127.0.0.1"), SystemPingProbe.command("WINDOWS", "ping.exe", "127.0.0.1", options));
        assertEquals(java.util.List.of("/usr/bin/ping", "-n", "-4", "-c", "4", "-W", "1.500", "127.0.0.1"), SystemPingProbe.command("LINUX", "/usr/bin/ping", "127.0.0.1", options));
        assertEquals(java.util.List.of("/sbin/ping", "-n", "-c", "4", "-W", "1500", "127.0.0.1"), SystemPingProbe.command("MACOS", "/sbin/ping", "127.0.0.1", options));
        assertThrows(RuntimeException.class, () -> SystemPingProbe.command("WINDOWS", "ping.exe", "127.0.0.1 & whoami", options));
    }
    @Test void parsesWindowsEnglishAndChineseRepliesAndDoesNotCallUnreachableOffline() {
        String english = "Reply from 127.0.0.1: bytes=32 time<1ms TTL=128\r\nReply from 127.0.0.1: bytes=32 time<1ms TTL=128\r\n" +
                "Packets: Sent = 2, Received = 2, Lost = 0 (0% loss)\r\nMinimum = 0ms, Maximum = 0ms, Average = 0ms";
        Row en = SystemPingProbe.parse("WINDOWS", "127.0.0.1", 2, 0, english);
        assertEquals("RESPONSIVE", en.status(), en.message()); assertTrue(en.rttBelowOneMs()); assertEquals(1.0, en.averageRttMs());
        String chinese = "来自 127.0.0.1 的回复: 字节=32 时间<1ms TTL=128\r\n" +
                "数据包: 已发送 = 1，已接收 = 1，丢失 = 0 (0% 丢失)\r\n最短 = 0ms，最长 = 0ms，平均 = 0ms";
        assertEquals("RESPONSIVE", SystemPingProbe.parse("WINDOWS", "127.0.0.1", 1, 0, chinese).status());
        String unreachable = "Destination host unreachable.\r\nPackets: Sent = 1, Received = 1, Lost = 0 (0% loss)";
        Row noEcho = SystemPingProbe.parse("WINDOWS", "127.0.0.1", 1, 1, unreachable);
        assertEquals("NO_REPLY", noEcho.status());
        assertEquals("本次没有 ICMP Echo 回复，不能据此判定主机离线", noEcho.message());
    }
    @Test void parsesUnixStatsAndSeparatesUnknownOutputFromNoReply() {
        String linux = "4 packets transmitted, 3 received, 25% packet loss, time 3036ms\nrtt min/avg/max/mdev = 0.040/1.250/2.500/0.300 ms";
        Row partial = SystemPingProbe.parse("LINUX", "127.0.0.1", 4, 0, linux);
        assertEquals("PARTIAL_LOSS", partial.status()); assertEquals(25.0, partial.lossPercent()); assertEquals(1.25, partial.averageRttMs());
        String mac = "4 packets transmitted, 4 packets received, 0.0% packet loss\nround-trip min/avg/max/stddev = 0.040/0.050/0.070/0.015 ms";
        assertEquals("RESPONSIVE", SystemPingProbe.parse("MACOS", "127.0.0.1", 4, 0, mac).status());
        assertEquals("NO_REPLY", SystemPingProbe.parse("LINUX", "127.0.0.1", 4, 1, "4 packets transmitted, 0 received, 100% packet loss").status());
        assertEquals("ERROR", SystemPingProbe.parse("LINUX", "127.0.0.1", 4, 1, "unexpected output").status());
    }
    @Test void currentSystemPingCanReachLoopbackWithoutTouchingExternalHosts() throws Exception {
        var properties = new com.toolhelper.config.PingProperties(4096, 200, 2097152, 4194304, 65536, 20, 30);
        var probe = new SystemPingProbe(properties);
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.available(), "测试系统未安装平台 Ping 程序");
        Row result = probe.probe("127.0.0.1", new Options(1, 2, 1000), () -> false);
        assertEquals("RESPONSIVE", result.status(), result.errorCode() + " · " + result.message());
        assertEquals(2, result.received());
    }
}
