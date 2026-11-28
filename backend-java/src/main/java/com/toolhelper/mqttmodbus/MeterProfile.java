package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import java.math.BigDecimal;
import java.util.*;
import static com.toolhelper.mqttmodbus.EarlyDeviceProfile.*;

/** 旧监测程序兼容配置。双寄存器按 signed16(high)*256+low 解析，不代表厂家字序。 */
public final class MeterProfile {
    public static final String ENCODING="firesys-legacy-v1";
    public static final String DOWN="firetopic/__meterTopicKey/down", UP="firetopic/__meterTopicKey/up";
    private MeterProfile() {}
    private record Field(String name,int start,int scale,boolean pair,boolean signed) {}
    private static final List<Field> DC=List.of(new Field("voltage",0,1,false,false),
        new Field("current",1,3,true,false),new Field("power",3,4,true,false),
        new Field("totalEnergy",5,2,true,false),new Field("sharpEnergy",7,2,true,false),
        new Field("peakEnergy",9,2,true,false),new Field("flatEnergy",11,2,true,false),new Field("valleyEnergy",13,2,true,false));
    private static final List<Field> AC=List.of(new Field("totalEnergy",0,2,true,true),
        new Field("forwardEnergy",2,2,true,false),new Field("reverseEnergy",4,2,true,false),
        new Field("voltage",6,1,false,false),new Field("current",7,3,true,false),
        new Field("power",9,4,true,true),new Field("powerFactor",11,3,false,true),new Field("frequency",12,2,false,false));
    public record Snapshot(Meter meter,List<Integer> registers) {}
    public static List<Snapshot> meters(String key,List<Meter> input) {
        require(input!=null && !input.isEmpty() && input.size()<=2,"每网关需要 1–2 块电表");
        Set<String> ids=new HashSet<>(),types=new HashSet<>(); List<Snapshot> result=new ArrayList<>();
        for(Meter m:input) {
            require(m!=null,"电表不能为空"); text(m.id(),"电表 ID",128);
            require(ids.add(m.id()) && Set.of("dc","ac").contains(m.type()==null?"":m.type()) && types.add(m.type()),"电表 ID 或类型重复/无效");
            require(key.equals(m.gatewayKey()),"电表必须绑定当前网关");
            boolean dc=m.type().equals("dc");
            require(Objects.equals(m.address(),dc?1:2),"直流固定地址 1，交流固定地址 2");
            require(ENCODING.equals(m.encodingProfile()),"必须显式选择旧监测程序兼容编码");
            require(dc ? m.baudRateRaw()!=null && m.baudRateRaw()>=0 && m.baudRateRaw()<=65535 : m.baudRateRaw()==null,"直流需要 16 位波特率原始字；交流不接受该字段");
            List<Field> fields=dc?DC:AC;
            require(m.measurements()!=null && m.measurements().keySet().equals(new HashSet<>(fields.stream().map(Field::name).toList())),"测量字段缺失或不属于该电表");
            int[] registers=new int[dc?17:14]; Map<String,String> values=new LinkedHashMap<>();
            for(Field f:fields) {
                String value=m.measurements().get(f.name());
                require(value!=null && value.length()<=40 && value.matches("-?[0-9]+(?:\\.[0-9]+)?"),f.name()+" 必须是十进制字符串");
                int raw;
                try { raw=new BigDecimal(value).movePointRight(f.scale()).intValueExact(); }
                catch(ArithmeticException e) { throw new com.toolhelper.support.ApiException(400,"MQTT_MODBUS_INVALID",f.name()+" 精度或范围无效"); }
                int min=f.signed()?(f.pair()?-8388608:-1000):0, max=f.pair()?8388607:f.signed()?1000:32767;
                require(raw>=min && raw<=max,f.name()+" 超出兼容编码范围");
                if(f.pair()) {
                    registers[f.start()]=Math.floorDiv(raw,256)&65535;
                    registers[f.start()+1]=Math.floorMod(raw,256);
                } else registers[f.start()]=raw&65535;
                values.put(f.name(),new BigDecimal(value).toPlainString());
            }
            if(dc) { registers[15]=1; registers[16]=m.baudRateRaw(); }
            // 交流 0x000D 为固定查询所需兼容填充，非厂家已确认的测量寄存器。
            result.add(new Snapshot(new Meter(m.id(),key,m.type(),m.address(),ENCODING,m.baudRateRaw(),Map.copyOf(values)),Arrays.stream(registers).boxed().toList()));
        }
        return List.copyOf(result);
    }
}
