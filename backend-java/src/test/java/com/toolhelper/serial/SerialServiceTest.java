package com.toolhelper.serial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.toolhelper.serial.SerialModels.*;
import com.toolhelper.support.ApiException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SerialServiceTest {
    private static final OpenRequest CONFIG = new OpenRequest("COM3", 9600, 8, "1", "NONE", "NONE");

    @Test void enforcesSingleSessionAndSerializesWriteEvents() {
        SerialPortAdapter adapter = mock(SerialPortAdapter.class);
        SerialPortAdapter.Device device = mock(SerialPortAdapter.Device.class);
        when(adapter.open(CONFIG)).thenReturn(device);
        when(device.write(any())).thenAnswer(call -> ((byte[]) call.getArgument(0)).length);
        SerialService service = new SerialService(adapter);
        try {
            Session opened = service.open(CONFIG);
            assertThrows(ApiException.class, () -> service.open(CONFIG));
            Event written = service.write(opened.sessionId(), new WriteRequest("HEX", "01 02", false, "NONE"));
            assertEquals("01 02", written.hex());
            assertEquals("TX", service.events(opened.sessionId(), 0).events().getFirst().direction());
            assertEquals(2, service.session().txBytes());
            service.close(opened.sessionId());
            verify(device).close();
            assertNull(service.session());
        } finally { service.close(); }
    }

    @Test void timerStopsAndCountsSuccessfulWrites() throws Exception {
        SerialPortAdapter adapter = mock(SerialPortAdapter.class);
        SerialPortAdapter.Device device = mock(SerialPortAdapter.Device.class);
        AtomicInteger writes = new AtomicInteger();
        when(adapter.open(CONFIG)).thenReturn(device);
        when(device.write(any())).thenAnswer(call -> { writes.incrementAndGet(); return ((byte[]) call.getArgument(0)).length; });
        SerialService service = new SerialService(adapter);
        try {
            Session opened = service.open(CONFIG);
            service.startAutoSend(opened.sessionId(), new AutoSendRequest(new WriteRequest("HEX", "01", false, "NONE"), 100));
            Thread.sleep(280);
            Session running = service.stopAutoSend(opened.sessionId());
            int stoppedAt = writes.get();
            Thread.sleep(180);
            assertTrue(stoppedAt >= 2);
            assertEquals(stoppedAt, writes.get());
            assertFalse(running.autoSending());
            assertEquals(stoppedAt, running.transmissions());
        } finally { service.close(); }
    }

    @Test void basicAndModbusSessionsShareOwnershipAndRejectCrossToolWrites() {
        SerialPortAdapter adapter = mock(SerialPortAdapter.class);
        SerialPortAdapter.Device device = mock(SerialPortAdapter.Device.class);
        when(adapter.open(CONFIG)).thenReturn(device);
        when(device.write(any())).thenAnswer(call -> ((byte[]) call.getArgument(0)).length);
        SerialService service = new SerialService(adapter);
        try {
            Session modbus = service.openModbus(CONFIG);
            assertNull(service.session());
            assertEquals(modbus.sessionId(), service.modbusSession().sessionId());
            assertThrows(ApiException.class, () -> service.write(modbus.sessionId(), new WriteRequest("HEX", "01 02", false, "NONE")));
            assertThrows(ApiException.class, () -> service.open(CONFIG));
            assertThrows(ApiException.class, () -> service.close("wrong-session"));
            service.heartbeatModbus(modbus.sessionId());
            service.writeModbus(modbus.sessionId(), new byte[]{1, 3, 0, 0, 0, 1, 0, 0});
            service.closeModbus(modbus.sessionId());
            verify(device).close(); assertNull(service.modbusSession());
        } finally { service.close(); }
    }
}
