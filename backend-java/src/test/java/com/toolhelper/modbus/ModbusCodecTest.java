package com.toolhelper.modbus;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModbusCodecTest {
    @Test void buildsVendorRequestCrcVectorsAndReset() {
        assertEquals("00 03 00 00 00 10 45 D7", ModbusCodec.hex(ModbusCodec.request(0, 3, 0, 16)));
        assertEquals("00 03 00 EE 00 10 25 E2", ModbusCodec.hex(ModbusCodec.request(0, 3, 0xEE, 16)));
        assertEquals("01 03 00 10 00 16 C5 C1", ModbusCodec.hex(ModbusCodec.request(1, 3, 0x10, 22)));
        assertEquals("01 04 00 01 00 1C A0 03", ModbusCodec.hex(ModbusCodec.request(1, 4, 1, 28)));
        assertEquals("01 03 00 08 00 19 05 C2", ModbusCodec.hex(ModbusCodec.request(1, 3, 8, 25)));
        assertEquals("01 05 00 07 FF 00 3D FB", ModbusCodec.hex(ModbusCodec.resetRequest(1)));
        assertEquals("37 4B", ModbusCodec.hex(ModbusCodec.crc("123456789".getBytes())));
    }

    @Test void extractsFramesAcrossFragmentsAndConcatenation() {
        byte[] one = ModbusCodec.request(1, 3, 0x10, 22);
        byte[] two = ModbusCodec.request(2, 3, 0x10, 22);
        byte[] responseOne = readResponse(1, 3, new byte[44]);
        byte[] responseTwo = readResponse(2, 3, new byte[44]);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertTrue(ModbusCodec.extract(buffer, java.util.Arrays.copyOf(responseOne, 9)).isEmpty());
        byte[] tail = java.util.Arrays.copyOfRange(responseOne, 9, responseOne.length);
        byte[] combined = new byte[tail.length + responseTwo.length];
        System.arraycopy(tail, 0, combined, 0, tail.length); System.arraycopy(responseTwo, 0, combined, tail.length, responseTwo.length);
        List<byte[]> frames = ModbusCodec.extract(buffer, combined);
        assertEquals(2, frames.size()); assertArrayEquals(responseOne, frames.get(0)); assertArrayEquals(responseTwo, frames.get(1));
        assertEquals(0, buffer.size());
    }

    @Test void parsesVendorRegisterMapsAndKeepsRawIdBytes() {
        byte[] data = new byte[44];
        put(data, 0, 5); put(data, 2, 0xFFFF); put(data, 4, 1); put(data, 6, 0); put(data, 8, 123);
        data[32] = 0x55; byte[] id = "00000000001".getBytes(); System.arraycopy(id, 0, data, 33, 11);
        var parsed = ModbusCodec.parseShenHong(readResponse(1, 3, data), 1);
        assertEquals(5, parsed.get("alarmLevel")); assertEquals("30 30 30 30 30 30 30 30 30 30 31", parsed.get("serialHex"));
        assertEquals("00000000001", parsed.get("serialText")); assertEquals(16, ModbusCodec.faults(parsed.get("faultWord")).size());

        byte[] live = new byte[56]; put(live, 0, 321); put(live, 2, 1); put(live, 4, 2); put(live, 6, 1);
        byte[] serial = "NR-00000001".getBytes();
        for (int i = 0; i < serial.length; i++) live[(0x11 - 1 + i) * 2] = serial[i];
        var nr = ModbusCodec.parseNanRuiRealtime(readResponse(1, 4, live), 1);
        assertEquals("4E 52 2D 30 30 30 30 30 30 30 31", nr.get("serialHex"));
        assertEquals("NR-00000001", nr.get("serialText"));

        byte[] holding = new byte[50]; put(holding, 0, 10); put(holding, 48, 1);
        var thresholds = ModbusCodec.parseNanRuiHolding(readResponse(1, 3, holding), 1);
        assertEquals(10, ((List<?>) thresholds.get("thresholds")).size()); assertEquals(1, thresholds.get("deviceAddress"));
    }

    @Test void enforcesStationSingleByteLayoutAndExactResetEcho() {
        byte[] positions = new byte[16]; positions[0] = 1; positions[1] = 2;
        int[] parsed = ModbusCodec.parseDevicePositions(readResponse(0, 3, positions));
        assertArrayEquals(new int[]{1, 2}, java.util.Arrays.copyOf(parsed, 2));
        assertThrows(IllegalArgumentException.class, () -> ModbusCodec.parseDevicePositions(readResponse(0, 3, new byte[32])));
        byte[] reset = ModbusCodec.resetRequest(1); ModbusCodec.verifyEcho(reset, reset);
        byte[] wrong = ModbusCodec.resetRequest(2); assertThrows(IllegalArgumentException.class, () -> ModbusCodec.verifyEcho(wrong, reset));
    }

    @Test void badCrcAndWrongAddressAreRejected() {
        byte[] frame = readResponse(1, 3, new byte[44]); frame[frame.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ModbusCodec.parseShenHong(frame, 1));
        assertThrows(IllegalArgumentException.class, () -> ModbusCodec.parseShenHong(readResponse(2, 3, new byte[44]), 1));
    }

    private static byte[] readResponse(int address, int function, byte[] data) {
        byte[] body = new byte[data.length + 3]; body[0] = (byte) address; body[1] = (byte) function; body[2] = (byte) data.length;
        System.arraycopy(data, 0, body, 3, data.length); return ModbusCodec.frame(body);
    }
    private static void put(byte[] data, int offset, int value) { data[offset] = (byte) (value >>> 8); data[offset + 1] = (byte) value; }
}
