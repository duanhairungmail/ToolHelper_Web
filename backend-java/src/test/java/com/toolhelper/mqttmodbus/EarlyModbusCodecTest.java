package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import com.toolhelper.support.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarlyModbusCodecTest {
    static Device device(String key,String id,int address,int smoke) {
        List<Integer> input=new ArrayList<>(Collections.nCopies(63,0));
        input.set(0,smoke); for(int i=4;i<9;i++) input.set(i,100);
        input.set(17,1); input.set(19,20); input.set(20,26); input.set(21,11);
        List<Integer> holding=Arrays.asList(20,26,11,28,10,24,0,180,10,160,10,140,10,120,10,100,10,180,10,160,10,140,10,120,10,100,10,8,0,20,0,address,7,2,1,1,80,0,2);
        return EarlyDeviceProfile.devices(key,List.of(new Device(id,key,"设备","FD",address,input,holding,List.of(true,true,false,false,true,false,false,false,false,true)))).getFirst();
    }
    static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ","")); }
    static byte[] query(int address,int function,int start,int count) { return EarlyModbusCodec.frame((byte)address,(byte)function,(byte)(start>>>8),(byte)start,(byte)(count>>>8),(byte)count); }
    @Test void fixedCrcVectorsAndCoilPacking() {
        assertArrayEquals(hex("01 01 00 01 00 0A ED CD"),query(1,1,1,10));
        var result=EarlyModbusCodec.respond(hex("01 01 00 01 00 0A ED CD"),List.of(device("g","d",1,120)));
        assertArrayEquals(EarlyModbusCodec.frame(hex("01 01 02 13 02")),result.response());
        // 第九位以后跨字节，末字节未使用位必须为零。
        assertEquals(2,result.response()[4]);
    }
    @Test void readsFullRegisterMapsAndSubrangesBigEndian() {
        Device d=device("g","d",1,880);
        assertArrayEquals(hex("01 04 02 03 70 B8 24"),EarlyModbusCodec.respond(query(1,4,1,1),List.of(d)).response());
        assertEquals(131,EarlyModbusCodec.respond(query(1,4,1,63),List.of(d)).response().length);
        assertEquals(83,EarlyModbusCodec.respond(query(1,3,1,39),List.of(d)).response().length);
        assertArrayEquals(EarlyModbusCodec.frame(hex("01 03 02 00 02")),EarlyModbusCodec.respond(query(1,3,39,1),List.of(d)).response());
    }
    @Test void malformedRequestsNeverProduceFabricatedSuccess() {
        var devices=List.of(device("g","d",1,120));
        assertEquals("UNKNOWN_SLAVE",EarlyModbusCodec.respond(query(2,4,1,1),devices).result());
        assertNull(EarlyModbusCodec.respond(hex("01 01 00 01 00 0A CD ED"),devices).response());
        assertEquals("EXCEPTION_1",EarlyModbusCodec.respond(query(1,4,0,1),devices).result());
        assertEquals("EXCEPTION_2",EarlyModbusCodec.respond(query(1,4,1,0),devices).result());
        assertEquals("EXCEPTION_3",EarlyModbusCodec.respond(query(1,4,63,2),devices).result());
        assertEquals("EXCEPTION_1",EarlyModbusCodec.respond(query(1,6,1,1),devices).result());
        assertNull(EarlyModbusCodec.respond(new byte[257],devices).response());
        assertNull(EarlyModbusCodec.respond(EarlyModbusCodec.frame(hex("01 04 00")),devices).response());
    }
    @Test void snapshotsValidateEnumsBindingAndAddressUniqueness() {
        Device d=device("g","d",1,120);
        assertThrows(ApiException.class,()->EarlyDeviceProfile.devices("other",List.of(d)));
        assertThrows(ApiException.class,()->EarlyDeviceProfile.devices("g",List.of(d,d)));
        var bad=new ArrayList<>(d.inputRegisters()); bad.set(2,6);
        assertThrows(ApiException.class,()->EarlyDeviceProfile.devices("g",List.of(new Device(d.id(),"g","a","b",1,bad,d.holdingRegisters(),d.coils()))));
        bad.set(2,0); bad.set(1,0x8000);
        var snapshot=EarlyDeviceProfile.devices("g",List.of(new Device(d.id(),"g","a","b",1,bad,d.holdingRegisters(),d.coils())));
        bad.set(0,999); assertEquals(120,snapshot.getFirst().inputRegisters().getFirst());
        assertThrows(UnsupportedOperationException.class,()->snapshot.getFirst().inputRegisters().set(0,100));
    }
    @Test void gatewayNormalizesTcpAndRejectsWildcardOrCredentialUrls() {
        Gateway g=new Gateway("g","网关","gateway-1","mqtt://LOCALHOST",null,0,null,null);
        var actual=EarlyDeviceProfile.gateway(g);
        assertEquals("tcp://localhost:1883",actual.brokerUrl()); assertEquals("firetopic/gateway-1/down",actual.downTopic());
        assertThrows(ApiException.class,()->EarlyDeviceProfile.gateway(new Gateway("g","n","id","mqtt://u:p@host",null,0,null,null)));
        assertThrows(ApiException.class,()->EarlyDeviceProfile.gateway(new Gateway("g","n","id","tcp://host",null,0,"a/#","b")));
    }
}
