package com.toolhelper.serial;

import static org.junit.jupiter.api.Assertions.*;
import com.toolhelper.serial.SerialModels.WriteRequest;
import com.toolhelper.support.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SerialCodecTest {
    @Test void rejectsOddHexAndImplicitCrlf() {
        assertThrows(ApiException.class, () -> SerialCodec.build(new WriteRequest("HEX", "A", false, "NONE")));
        assertThrows(ApiException.class, () -> SerialCodec.build(new WriteRequest("HEX", "01", true, "NONE")));
    }

    @Test void crcVectorsMatchLegacyAlgorithmParametersAndByteOrder() {
        byte[] input = "123456789".getBytes(StandardCharsets.US_ASCII);
        assertEquals("37 4B", SerialCodec.hex(SerialCodec.checksum(input, "MODBUS_CRC16")));
        assertEquals("29 B1", SerialCodec.hex(SerialCodec.checksum(input, "CRC16_CCITT")));
        assertEquals("26 39 F4 CB", SerialCodec.hex(SerialCodec.checksum(input, "CRC32")));
    }

    @Test void textCrlfIsIncludedBeforeChecksum() {
        byte[] actual = SerialCodec.build(new WriteRequest("TEXT", "A", true, "XOR"));
        assertEquals("41 0D 0A 46", SerialCodec.hex(actual));
    }

    @Test void rejectsOversizedAndUnknownAlgorithmBeforeWriting() {
        assertThrows(ApiException.class, () -> SerialCodec.build(new WriteRequest("HEX", "01", false, "MD5")));
        assertThrows(ApiException.class, () -> SerialCodec.build(new WriteRequest("TEXT", "x".repeat(SerialCodec.MAX_TX_BYTES + 1), false, "NONE")));
    }
}
