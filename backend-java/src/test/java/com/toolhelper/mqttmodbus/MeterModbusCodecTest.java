package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import com.toolhelper.support.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.toolhelper.mqttmodbus.EarlyModbusCodecTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MeterModbusCodecTest {
    static Meter meter(String key,String type) {
        Map<String,String> values=new LinkedHashMap<>();
        if(type.equals("dc")) {
            values.putAll(Map.of("voltage","350.0","current","10.000","power","3.5000","totalEnergy","1250.00",
                "sharpEnergy","100.00","peakEnergy","350.00","flatEnergy","500.00","valleyEnergy","300.00"));
        } else values.putAll(Map.of("totalEnergy","1250.00","forwardEnergy","1200.00","reverseEnergy","50.00",
            "voltage","220.0","current","5.000","power","1.0450","powerFactor","0.950","frequency","50.00"));
        return new Meter("meter-"+type,key,type,type.equals("dc")?1:2,MeterProfile.ENCODING,type.equals("dc")?0:null,values);
    }
    static Meter change(Meter m,String field,String value) {
        Map<String,String> values=new HashMap<>(m.measurements()); values.put(field,value);
        return new Meter(m.id(),m.gatewayKey(),m.type(),m.address(),m.encodingProfile(),m.baudRateRaw(),values);
    }
    @Test void fixedFramesMatchIndependentLegacyVectors() {
        var dc=MeterProfile.meters("g",List.of(meter("g","dc")));
        assertArrayEquals(hex("01 03 22 0D AC 00 27 00 10 00 88 00 B8 01 E8 00 48 00 27 00 10 00 88 00 B8 00 C3 00 50 00 75 00 30 00 01 00 00 F7 AC"),MeterModbusCodec.respond(hex("01 03 00 00 00 11 85 C6"),dc).response());
        var ac=MeterProfile.meters("g",List.of(meter("g","ac")));
        assertArrayEquals(hex("02 03 1C 01 E8 00 48 01 D4 00 C0 00 13 00 88 08 98 00 13 00 88 00 28 00 D2 03 B6 13 88 00 00 3E 3B"),MeterModbusCodec.respond(hex("02 03 00 00 00 0E C4 3D"),ac).response());
    }
    @Test void validatesDecimalPrecisionAndSignedLegacyLimitsWithoutRounding() {
        Meter ac=meter("g","ac"),dc=meter("g","dc");
        for(String value:List.of("-838.8608","-1.0450","0.0255","0.0256","3.2767","3.2768","838.8607")) {
            var r=MeterProfile.meters("g",List.of(change(ac,"power",value))).getFirst().registers();
            assertEquals(new java.math.BigDecimal(value).movePointRight(4).intValueExact(),(short)(int)r.get(9)*256+(short)(int)r.get(10));
        }
        for(String value:List.of("838.8608","-838.8609","1.00001","NaN","1e2",""))
            assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(change(ac,"power",value))));
        assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(change(dc,"power","-0.0001"))));
        assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(change(ac,"voltage","3276.8"))));
        assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(change(ac,"frequency","327.68"))));
        assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(change(ac,"powerFactor","1.001"))));
        assertEquals(64586,MeterProfile.meters("g",List.of(change(ac,"powerFactor","-0.950"))).getFirst().registers().get(11));
        assertDoesNotThrow(()->MeterProfile.meters("g",List.of(change(ac,"current","1.2300"))));
        assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(change(ac,"unknown","0"))));
        assertThrows(ApiException.class,()->MeterProfile.meters("g",List.of(ac,ac)));
        assertThrows(ApiException.class,()->MeterProfile.meters("other",List.of(dc)));
        assertThrows(UnsupportedOperationException.class,()->MeterProfile.meters("g",List.of(dc)).getFirst().meter().measurements().put("power","1"));
    }
    @Test void validatesReadBoundariesAndDropsUnknownOrMalformedQueries() {
        var meters=MeterProfile.meters("g",List.of(meter("g","dc"),meter("g","ac")));
        assertArrayEquals(EarlyModbusCodec.frame(hex("01 03 02 00 10")),MeterModbusCodec.respond(query(1,3,2,1),meters).response());
        assertEquals("EXCEPTION_2",MeterModbusCodec.respond(query(2,3,13,2),meters).result());
        assertEquals("EXCEPTION_2",MeterModbusCodec.respond(query(2,3,0x1fb,1),meters).result());
        assertEquals("EXCEPTION_3",MeterModbusCodec.respond(query(1,3,0,0),meters).result());
        assertEquals("EXCEPTION_3",MeterModbusCodec.respond(query(1,3,0,126),meters).result());
        assertEquals("EXCEPTION_1",MeterModbusCodec.respond(query(1,6,0,1),meters).result());
        assertEquals("FILTERED_SLAVE",MeterModbusCodec.respond(query(0,3,0,1),meters).result());
        assertEquals("FILTERED_SLAVE",MeterModbusCodec.respond(query(3,3,0,1),meters).result());
        assertEquals("INVALID_CRC",MeterModbusCodec.respond(hex("01 03 00 00 00 11 00 00"),meters).result());
        assertEquals("INVALID_LENGTH",MeterModbusCodec.respond(EarlyModbusCodec.frame(hex("01 03 00")),meters).result());
        assertEquals("INVALID_LENGTH",MeterModbusCodec.respond(new byte[257],meters).result());
    }
}
