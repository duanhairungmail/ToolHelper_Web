package com.toolhelper.mqttmodbus;

import java.util.List;
import static com.toolhelper.mqttmodbus.EarlyModbusCodec.*;

/** 电表 FC03 仅按已验证快照切片，不沿用极早期设备的特殊异常码。 */
public final class MeterModbusCodec {
    private MeterModbusCodec() {}
    public static Result respond(byte[] request,List<MeterProfile.Snapshot> meters) {
        if(request.length<4 || request.length>256) return new Result(null,"","INVALID_LENGTH");
        int checksum=crc(request,request.length-2);
        if((request[request.length-2]&255)!=(checksum&255) || (request[request.length-1]&255)!=(checksum>>>8)) return new Result(null,"","INVALID_CRC");
        int address=request[0]&255, function=request[1]&255;
        var target=meters.stream().filter(m->m.meter().address()==address).findFirst().orElse(null);
        if(target==null) return new Result(null,"","FILTERED_SLAVE");
        if(function==0 || function>=128) return new Result(null,target.meter().id(),"INVALID_FUNCTION");
        if(function!=3) return exception(address,function,1,target.meter().id());
        if(request.length!=8) return new Result(null,target.meter().id(),"INVALID_LENGTH");
        int start=(request[2]&255)*256+(request[3]&255), count=(request[4]&255)*256+(request[5]&255);
        if(count<1 || count>125) return exception(address,function,3,target.meter().id());
        if(start>=target.registers().size() || start+count>target.registers().size()) return exception(address,function,2,target.meter().id());
        byte[] body=new byte[3+count*2]; body[0]=(byte)address; body[1]=3; body[2]=(byte)(count*2);
        for(int i=0;i<count;i++) { int raw=target.registers().get(start+i); body[3+i*2]=(byte)(raw>>>8); body[4+i*2]=(byte)raw; }
        return new Result(frame(body),target.meter().id(),"REPLIED");
    }
    private static Result exception(int address,int function,int code,String id) {
        return new Result(frame((byte)address,(byte)(function|128),(byte)code),id,"EXCEPTION_"+code);
    }
}
