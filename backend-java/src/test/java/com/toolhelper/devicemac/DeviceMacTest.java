package com.toolhelper.devicemac;

import com.toolhelper.config.*;
import com.toolhelper.plugin.common.LocalDataRoot;
import com.toolhelper.service.DeviceMacService;
import com.toolhelper.support.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.devicemac.DeviceMacModels.*;

class DeviceMacTest {
    @TempDir Path root;
    final JsonMapper json = new JsonMapper();
    final DeviceMacProperties properties = new DeviceMacProperties(1000, 4, 10, 256, 2097152, 4194304);
    static String uuid() { return UUID.randomUUID().toString(); }
    static DeviceMacModels.Record record(String id, String ip, Observation observation) {
        return new DeviceMacModels.Record(id, "站<&>", ip, "=SUM(A1)", "", "90071992547409931234", "", observation);
    }
    static Outcome found(String ip) {
        return new Outcome("MAPPING_FOUND", "observed", new Observation(ip, "02:11:22:33:44:55", "MAPPING_FOUND", "WINDOWS_NEIGHBOR", "Reachable", 18, Instant.now()));
    }
    DeviceMacStore store(LocalDataRoot data) { return new DeviceMacStore(data, new JsonFiles(json), json, properties); }
    @Test void validatesLiteralIpAndMacWithoutSubstringMatches() {
        assertEquals("192.168.1.10", MacValues.ipv4(" 192.168.1.10 "));
        for (String ip : List.of("127.0.0.1", "0.0.0.0", "224.1.1.1", "255.255.255.255", "1.2.3.256", "192.168.01.1", "localhost", "1.2.3.4; calc", "192.168.1.0/24"))
            assertThrows(ApiException.class, () -> MacValues.ipv4(ip), ip);
        assertEquals("02:11:22:33:44:55", MacValues.mac("02-11-22-33-44-55"));
        for (String mac : List.of("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:11:22:33:44:55", "abc")) assertThrows(ApiException.class, () -> MacValues.mac(mac));
        assertTrue(MacValues.broadcast("192.168.31.255", "192.168.31.119/24"));
        assertFalse(MacValues.broadcast("192.168.31.126", "192.168.31.119/24"));
    }
    @Test void interpretsNeighborStateAndChecksExactIpInterfaceAndMac() {
        for (String state : List.of("Reachable", "Stale", "Delay", "Probe", "Permanent", "Incomplete", "Unreachable")) {
            var data = json.readTree("{\"code\":\"OK\",\"ipAddress\":\"192.168.1.1\",\"interfaceIndex\":18,\"prefix\":\"192.168.1.119/24\",\"macAddress\":\"04-67-61-C6-82-F4\",\"neighborState\":\"" + state + "\"}");
            var result = WindowsMacResolver.parse("192.168.1.1", data);
            assertEquals(state.equals("Reachable") ? "MAPPING_FOUND" : Set.of("Incomplete", "Unreachable").contains(state) ? "NO_NEIGHBOR" : "CACHED_MAPPING", result.status());
            assertEquals("COMMAND_FAILED", WindowsMacResolver.parse("192.168.1.10", data).status());
        }
        assertEquals("COMMAND_FAILED", WindowsMacResolver.parse("192.168.1.1", json.readTree("[]")).status());
        assertEquals("NOT_ON_LINK", WindowsMacResolver.parse("192.168.1.1", json.readTree("{\"code\":\"NOT_ON_LINK\"}")).status());
    }
    @Test void ledgerRoundTripConflictAndCorruptionNeverReplacesOriginal() throws Exception {
        try (var data = new LocalDataRoot(ToolHelperProperties.forTests(root))) {
            var store = store(data); assertEquals("initial", store.read().revision());
            var rows = List.of(record(uuid(), "192.168.1.1", found("192.168.1.1").observation()));
            var saved = store.save(new SaveRequest("initial", rows));
            assertEquals(saved, store.read());
            assertEquals("LEDGER_VERSION_CONFLICT", assertThrows(ApiException.class, () -> store.save(new SaveRequest("initial", rows))).code());
            assertEquals(saved, store.read());
            var fresh = new DeviceMacStore(data, new JsonFiles(json), json, properties); assertEquals(saved, fresh.read());
            Path file = root.resolve("device-mac/records.json"); Files.writeString(file, "corrupted-original");
            assertEquals("LEDGER_READ_FAILED", assertThrows(ApiException.class, () -> store.save(new SaveRequest(saved.revision(), rows))).code());
            assertEquals("corrupted-original", Files.readString(file));
        }
    }
    @Test void ledgerRejectsDuplicateIdsAndFailedAtomicWriteKeepsFile() throws Exception {
        try (var data = new LocalDataRoot(ToolHelperProperties.forTests(root))) {
            var store = store(data); String id = uuid(); var row = record(id, "", null);
            assertThrows(ApiException.class, () -> store.save(new SaveRequest("initial", List.of(row, row))));
            var saved = store.save(new SaveRequest("initial", List.of(row)));
            // 用目录占据临时文件名，模拟不可恢复的写入失败。
            Files.createDirectory(root.resolve("device-mac/records.json.tmp"));
            assertEquals("LEDGER_WRITE_FAILED", assertThrows(ApiException.class, () -> store.save(new SaveRequest(saved.revision(), List.of()))).code());
            assertEquals(saved, store.read());
        }
    }
    @Test void exportsSelectedColumnsAsTextAndEscapesXml() throws Exception {
        try (var data = new LocalDataRoot(ToolHelperProperties.forTests(root))) {
            var store = store(data); var row = record(uuid(), "192.168.1.1", found("192.168.1.1").observation());
            for (String format : List.of("xlsx", "docx")) {
                byte[] bytes = DeviceMacExport.generate(new ExportRequest(format, List.of("index", "stationName", "deviceName", "serialNumber", "macAddress"), List.of(row)), store);
                String xml = entry(bytes, format.equals("xlsx") ? "xl/worksheets/sheet1.xml" : "word/document.xml");
                assertTrue(xml.contains("站&lt;&amp;&gt;")); assertTrue(xml.contains("=SUM(A1)")); assertTrue(xml.contains("90071992547409931234")); assertTrue(xml.contains("02:11:22:33:44:55"));
                assertFalse(xml.contains("<f>"));
            }
            assertThrows(ApiException.class, () -> DeviceMacExport.generate(new ExportRequest("xlsx", List.of("macAddress"), List.of(row)), store));
        }
    }
    static String entry(byte[] bytes, String name) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals(name)) return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IOException("missing XML");
    }
    @Test void batchesDeduplicateKeepPartialErrorsAndEnforceConcurrencyAndReplay() throws Exception {
        AtomicInteger calls = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
        MacResolver resolver = new MacResolver() {
            public Map<String, Object> capabilities() { return Map.of("supported", true); }
            public Outcome resolve(String ip, BooleanSupplier cancelled) throws InterruptedException {
                calls.incrementAndGet(); int n = active.incrementAndGet(); peak.accumulateAndGet(n, Math::max);
                try { Thread.sleep(30); return ip.endsWith(".9") ? WindowsMacResolver.failure("NO_NEIGHBOR") : found(ip); }
                finally { active.decrementAndGet(); }
            }
        };
        try (var data = new LocalDataRoot(ToolHelperProperties.forTests(root))) {
            var store = store(data); var service = new DeviceMacService(resolver, store, properties);
            try {
                List<Item> items = new ArrayList<>();
                for (int i = 1; i <= 9; i++) items.add(new Item(uuid(), "192.168.1." + i));
                items.add(new Item(uuid(), "192.168.1.1")); items.add(new Item(uuid(), "")); items.add(new Item(uuid(), "bad"));
                var request = new QueryRequest(uuid(), items); Task submitted = service.submit(request);
                assertEquals(submitted.taskId(), service.submit(request).taskId());
                assertEquals("MAC_QUERY_BUSY", assertThrows(ApiException.class, () -> service.save(new SaveRequest("initial", List.of()))).code());
                Task result = terminal(service, submitted.taskId());
                assertEquals("COMPLETED", result.status()); assertEquals(12, result.done()); assertEquals(9, calls.get()); assertTrue(peak.get() <= 4);
                assertTrue(result.results().stream().anyMatch(r -> r.status().equals("INVALID_IPV4")));
                assertTrue(result.results().stream().anyMatch(r -> r.status().equals("NO_NEIGHBOR")));
                assertEquals(submitted.taskId(), service.submit(request).taskId());
                assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class, () -> service.submit(new QueryRequest(request.requestKey(), List.of(new Item(uuid(), "1.2.3.4"))))).code());
            } finally { service.close(); }
        }
    }
    @Test void cancellationWaitsForWorkersAndStopsUnscheduledTargets() throws Exception {
        AtomicInteger active = new AtomicInteger(), calls = new AtomicInteger(); CountDownLatch started = new CountDownLatch(1);
        MacResolver resolver = new MacResolver() {
            public Map<String, Object> capabilities() { return Map.of(); }
            public Outcome resolve(String ip, BooleanSupplier cancelled) throws InterruptedException {
                calls.incrementAndGet(); active.incrementAndGet(); started.countDown();
                try { while (!cancelled.getAsBoolean()) Thread.sleep(10); Thread.sleep(50); return WindowsMacResolver.failure("QUERY_CANCELLED"); }
                finally { active.decrementAndGet(); }
            }
        };
        try (var data = new LocalDataRoot(ToolHelperProperties.forTests(root))) {
            var service = new DeviceMacService(resolver, store(data), properties);
            try {
                var items = new ArrayList<Item>(); for (int i = 1; i <= 20; i++) items.add(new Item(uuid(), "192.168.1." + i));
                var task = service.submit(new QueryRequest(uuid(), items)); assertTrue(started.await(2, TimeUnit.SECONDS));
                assertEquals("CANCELLING", service.cancel(task.taskId()).status());
                var result = terminal(service, task.taskId()); assertEquals("CANCELLED", result.status()); assertEquals(20, result.done()); assertEquals(0, active.get()); assertTrue(calls.get() <= 4);
                assertEquals("CANCELLED", service.cancel(task.taskId()).status());
            } finally { service.close(); }
        }
    }
    @Test void fatalEnvironmentFailureStopsBatchAndReleasesOnlyAfterCleanup() throws Exception {
        AtomicInteger active = new AtomicInteger();
        MacResolver resolver = new MacResolver() {
            public Map<String, Object> capabilities() { return Map.of(); }
            public Outcome resolve(String ip, BooleanSupplier cancelled) throws InterruptedException {
                active.incrementAndGet();
                try {
                    if (ip.endsWith(".1")) return WindowsMacResolver.failure("ACCESS_DENIED");
                    while (!cancelled.getAsBoolean()) Thread.sleep(10);
                    Thread.sleep(30); return WindowsMacResolver.failure("QUERY_CANCELLED");
                } finally { active.decrementAndGet(); }
            }
        };
        try (var data = new LocalDataRoot(ToolHelperProperties.forTests(root))) {
            var service = new DeviceMacService(resolver, store(data), properties);
            try {
                var items = new ArrayList<Item>(); for (int i = 1; i <= 8; i++) items.add(new Item(uuid(), "192.168.1." + i));
                var result = terminal(service, service.submit(new QueryRequest(uuid(), items)).taskId());
                assertEquals("FAILED", result.status()); assertEquals("ACCESS_DENIED", result.errorCode()); assertEquals(8, result.done()); assertEquals(0, active.get()); assertNull(service.active());
            } finally { service.close(); }
        }
    }
    static Task terminal(DeviceMacService service, String id) throws InterruptedException {
        for (int i = 0; i < 300; i++) { Task task = service.task(id); if (Set.of("COMPLETED", "CANCELLED", "FAILED").contains(task.status())) return task; Thread.sleep(10); }
        throw new AssertionError("task did not finish");
    }
}
