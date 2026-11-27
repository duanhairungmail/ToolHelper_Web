package com.toolhelper.modbus;

import static org.junit.jupiter.api.Assertions.*;
import com.toolhelper.modbus.ModbusModels.*;
import com.toolhelper.serial.SerialPortAdapter;
import com.toolhelper.serial.SerialService;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

class ModbusServiceTest {
    private static final String PORT = "COM3";

    @Test void scansAndReadsThroughTheSharedPortWithoutExposingBasicWrites() throws Exception {
        FakePortAdapter adapter = new FakePortAdapter(); SerialService serial = new SerialService(adapter); ModbusService service = new ModbusService(serial);
        try {
            Session opened = service.open(new OpenRequest(PORT, 9600, "SHEN_HONG"));
            assertNull(serial.session()); assertEquals(opened.sessionId(), serial.modbusSession().sessionId());
            Operation scan = service.startOperation(opened.sessionId(), new OperationRequest("SCAN", null, false));
            awaitStatus(service, opened.sessionId(), scan.operationId(), "SUCCEEDED");
            assertEquals(List.of(1, 2), service.session().devices().stream().map(Device::address).toList());
            Operation read = service.startOperation(opened.sessionId(), new OperationRequest("READ", 1, false));
            awaitStatus(service, opened.sessionId(), read.operationId(), "SUCCEEDED");
            Device device = service.session().devices().getFirst();
            assertEquals("成功", device.readStatus()); assertEquals("正常", device.alarmName());
            assertEquals(10, device.thresholds().size());
            assertTrue(adapter.writeCount(3, 1) >= 1);
            assertThrows(RuntimeException.class, () -> serial.close(opened.sessionId()));
            service.close(opened.sessionId()); assertNull(serial.modbusSession());
        } finally { service.shutdown(); serial.close(); }
    }

    @Test void readsRetryTwiceButResetIsNeverReplayed() throws Exception {
        FakePortAdapter adapter = new FakePortAdapter(); adapter.suppressDeviceReplies = true;
        SerialService serial = new SerialService(adapter); ModbusService service = new ModbusService(serial);
        try {
            Session sh = service.open(new OpenRequest(PORT, 9600, "SHEN_HONG"));
            Operation read = service.startOperation(sh.sessionId(), new OperationRequest("READ", 1, false));
            awaitStatus(service, sh.sessionId(), read.operationId(), "FAILED");
            assertEquals(3, adapter.writeCount(3, 1));
            service.close(sh.sessionId());

            adapter.suppressDeviceReplies = true;
            Session nr = service.open(new OpenRequest(PORT, 9600, "NAN_RUI_YI_HE"));
            Operation reset = service.startOperation(nr.sessionId(), new OperationRequest("RESET", 1, true));
            awaitStatus(service, nr.sessionId(), reset.operationId(), "UNKNOWN");
            assertEquals(1, adapter.writeCount(5, 1));
        } finally { service.shutdown(); serial.close(); }
    }

    @Test void stoppingPollingLetsCurrentDeviceReadFinishButDoesNotStartAnother() throws Exception {
        FakePortAdapter adapter = new FakePortAdapter();
        SerialService serial = new SerialService(adapter); ModbusService service = new ModbusService(serial);
        try {
            Session opened = service.open(new OpenRequest(PORT, 9600, "SHEN_HONG"));
            Operation scan = service.startOperation(opened.sessionId(), new OperationRequest("SCAN", null, false));
            awaitStatus(service, opened.sessionId(), scan.operationId(), "SUCCEEDED");
            service.startPolling(opened.sessionId());
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < deadline && !service.session().operationStatus().equals("RUNNING")) Thread.sleep(5);
            assertEquals("RUNNING", service.session().operationStatus());
            service.stopPolling(opened.sessionId());
            long writesAtStop = adapter.device.writes.size();
            Thread.sleep(2200);
            Session finished = service.session();
            assertFalse(finished.polling());
            assertEquals("SUCCEEDED", finished.operationStatus());
            assertEquals(writesAtStop, adapter.device.writes.size());
        } finally { service.shutdown(); serial.close(); }
    }

    private static void awaitStatus(ModbusService service, String sessionId, String operationId, String expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Session state = service.session();
            if (state == null || !state.sessionId().equals(sessionId)) fail("会话提前关闭");
            if (state.operationId().equals(operationId) && Set.of("SUCCEEDED", "PARTIAL", "FAILED", "UNKNOWN", "CANCELLED").contains(state.operationStatus())) {
                assertEquals(expected, state.operationStatus(), state.operationMessage()); return;
            }
            Thread.sleep(10);
        }
        fail("操作未在时限内完成");
    }

    private static final class FakePortAdapter implements SerialPortAdapter {
        private final FakeDevice device = new FakeDevice(); boolean suppressDeviceReplies;
        @Override public List<PortInfo> listPorts() { return List.of(new PortInfo(PORT, "Simulated Modbus test port")); }
        @Override public Device open(com.toolhelper.serial.SerialModels.OpenRequest request) { return device; }
        int writeCount(int function, int address) { return (int) device.writes.stream().filter(frame -> (frame[1] & 0xFF) == function && (frame[0] & 0xFF) == address).count(); }

        private final class FakeDevice implements Device {
            final List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
            final ConcurrentLinkedQueue<Byte> rx = new ConcurrentLinkedQueue<>();
            @Override public int read(byte[] buffer) {
                int count = 0; Byte value;
                while (count < buffer.length && (value = rx.poll()) != null) buffer[count++] = value;
                return count;
            }
            @Override public int write(byte[] bytes) {
                writes.add(bytes.clone()); if (bytes.length < 8 || !ModbusCodec.validCrc(bytes)) return bytes.length;
                int address = bytes[0] & 0xFF, function = bytes[1] & 0xFF, start = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
                if (function == 5) return bytes.length;
                if (suppressDeviceReplies && address != 0) return bytes.length;
                int quantity = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
                byte[] data = new byte[address == 0 ? quantity : quantity * 2];
                if (address == 0 && start == 0) { data[0] = 1; data[1] = 2; }
                if (function == 3 && start == 0x10) { data[31] = 1; }
                byte[] body = new byte[data.length + 3]; body[0] = bytes[0]; body[1] = bytes[1]; body[2] = (byte) data.length;
                System.arraycopy(data, 0, body, 3, data.length); byte[] response = ModbusCodec.frame(body);
                // Split the RTU reply to cover incremental reads in the shared serial poller.
                int split = Math.min(7, response.length); enqueue(Arrays.copyOfRange(response, 0, split)); enqueue(Arrays.copyOfRange(response, split, response.length));
                return bytes.length;
            }
            private void enqueue(byte[] bytes) { for (byte value : bytes) rx.add(value); }
            @Override public void close() { rx.clear(); }
        }
    }
}
