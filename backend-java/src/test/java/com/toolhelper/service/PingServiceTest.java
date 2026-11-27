package com.toolhelper.service;

import com.toolhelper.config.PingProperties;
import com.toolhelper.ping.*;
import com.toolhelper.support.ApiException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.ping.PingModels.*;

class PingServiceTest {
    private final PingProperties properties = new PingProperties(4096, 8, 2 * 1024 * 1024, 4 * 1024 * 1024, 65536, 20, 30);
    private static String uuid(int suffix) { return "00000000-0000-0000-0000-" + String.format("%012d", suffix); }
    private static Task await(PingService service, String id, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        Task task;
        do { task = service.task(id); if (task.status().equals(status)) return task; Thread.sleep(10); }
        while (System.nanoTime() < deadline);
        return task;
    }
    @Test void limitsOneActiveTaskSupportsIdempotentGetAndPreservesInputOrder() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PingProbe fake = new PingProbe() {
            public String platform() { return "TEST"; }
            public boolean available() { return true; }
            public Row probe(String ip, Options options, java.util.function.BooleanSupplier cancelled) {
                calls.incrementAndGet();
                return new Row(ip, ip.endsWith(".2") ? "NO_REPLY" : "RESPONSIVE", options.count(), ip.endsWith(".2") ? 0 : options.count(),
                        ip.endsWith(".2") ? 100.0 : 0.0, ip.endsWith(".2") ? null : 2.0, false, java.time.Instant.now(), null, ip.endsWith(".2") ? "=1+1" : "夹具");
            }
        };
        try (var service = new AutoCloseableService(new PingService(properties, fake))) {
            PingService ping = service.value;
            Task accepted = ping.submit(new TaskRequest(uuid(1), List.of("127.0.0.3", "127.0.0.2", "127.0.0.1"), 2, 2, 100));
            Task duplicate = ping.submit(new TaskRequest(uuid(1), List.of("127.0.0.3", "127.0.0.2", "127.0.0.1"), 2, 2, 100));
            assertEquals(accepted.taskId(), duplicate.taskId());
            Task completed = await(ping, accepted.taskId(), "SUCCEEDED");
            assertEquals(List.of("127.0.0.3", "127.0.0.2", "127.0.0.1"), completed.results().stream().map(Row::ip).toList());
            assertEquals(1, completed.summary().noReply()); assertEquals(2, completed.summary().responsive());
            assertEquals(2.0, completed.summary().averageRttMs()); assertEquals(3, calls.get());
            String csv = new String(ping.export(new ExportRequest(accepted.taskId(), "csv")), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(csv.contains("'=1+1"));
            ApiException conflict = assertThrows(ApiException.class, () -> ping.submit(new TaskRequest(uuid(1), List.of("127.0.0.1"), 1, 1, 100)));
            assertEquals("PING_IDEMPOTENCY_CONFLICT", conflict.code());
        }
    }
    @Test void cancellationStopsSchedulingAndCompletedRowsCanBeExportedWithoutNetwork() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PingProbe fake = new PingProbe() {
            public String platform() { return "TEST"; }
            public boolean available() { return true; }
            public Row probe(String ip, Options options, java.util.function.BooleanSupplier cancelled) throws InterruptedException {
                if (calls.incrementAndGet() == 1) return new Row(ip, "RESPONSIVE", 1, 1, 0.0, 1.0, false, java.time.Instant.now(), null, "夹具");
                secondStarted.countDown();
                while (!cancelled.getAsBoolean()) Thread.sleep(5);
                return SystemPingProbe.cancelled(ip);
            }
        };
        try (var holder = new AutoCloseableService(new PingService(properties, fake))) {
            PingService ping = holder.value;
            Task task = ping.submit(new TaskRequest(uuid(3), List.of("127.0.0.1", "127.0.0.2"), 1, 1, 100));
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            ping.cancel(task.taskId());
            Task stopped = await(ping, task.taskId(), "CANCELLED");
            assertEquals(1, stopped.summary().done());
            assertEquals(1, stopped.summary().cancelled());
            assertEquals(List.of("127.0.0.1"), stopped.results().stream().map(Row::ip).toList());
            assertTrue(new String(ping.export(new ExportRequest(task.taskId(), "csv")), java.nio.charset.StandardCharsets.UTF_8).contains("目标IP"));
            assertThrows(ApiException.class, () -> ping.export(new ExportRequest(task.taskId(), "docx")));
        }
    }
    private static final class AutoCloseableService implements AutoCloseable {
        final PingService value;
        AutoCloseableService(PingService value) { this.value = value; }
        @Override public void close() { value.close(); }
    }
}
