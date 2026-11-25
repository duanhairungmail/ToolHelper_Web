package com.toolhelper.devicemac;

import com.toolhelper.support.ApiException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BoundedProcessTest {
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("utf8")) { System.out.write("{\"text\":\"中文\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); return; }
            if (args[0].equals("overflow")) { System.out.print("x".repeat(100000)); return; }
            if (args[0].equals("error")) { System.err.print("failed"); System.exit(2); }
            if (args[0].equals("tree")) {
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), "wait").start();
            }
            Thread.sleep(30000);
        }
    }
    ProcessBuilder command(String mode) {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), mode);
    }
    @Test void readsUtf8AndRejectsExitErrorsAndOversizedOutput() throws Exception {
        assertTrue(BoundedProcess.run(command("utf8"), Duration.ofSeconds(5), () -> false).contains("中文"));
        for (String mode : List.of("error", "overflow")) assertEquals("COMMAND_FAILED", assertThrows(ApiException.class, () -> BoundedProcess.run(command(mode), Duration.ofSeconds(5), () -> false)).code());
    }
    @Test void deadlineAndCancelTerminateActualChildBeforeReturning() {
        Set<Long> before = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        assertEquals("QUERY_TIMEOUT", assertThrows(ApiException.class, () -> BoundedProcess.run(command("wait"), Duration.ofMillis(300), () -> false)).code());
        assertEquals("QUERY_TIMEOUT", assertThrows(ApiException.class, () -> BoundedProcess.run(command("tree"), Duration.ofMillis(800), () -> false)).code());
        long start = System.nanoTime();
        assertEquals("QUERY_CANCELLED", assertThrows(ApiException.class, () -> BoundedProcess.run(command("wait"), Duration.ofSeconds(10), () -> System.nanoTime() - start > 300_000_000)).code());
        assertTrue(ProcessHandle.current().descendants().noneMatch(p -> p.isAlive() && !before.contains(p.pid())));
    }
}
