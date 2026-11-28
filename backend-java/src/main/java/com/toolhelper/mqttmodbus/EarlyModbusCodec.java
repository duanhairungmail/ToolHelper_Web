package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.Device;
import java.util.*;

/** 每个 MQTT payload 必须恰好是一帧 RTU；CRC 低字节先发，不添加 MBAP。 */
public final class EarlyModbusCodec {
    private EarlyModbusCodec() {}
    public record Result(byte[] response, String deviceId, String result) {}
    public static int crc(byte[] bytes, int length) {
        int crc = 0xffff;
        for (int i=0;i<length;i++) {
            crc ^= bytes[i] & 255;
            for (int bit=0;bit<8;bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
        }
        return crc;
    }
    public static byte[] frame(byte... body) {
        byte[] result = Arrays.copyOf(body, body.length+2);
        int crc = crc(body, body.length);
        result[body.length] = (byte) crc; result[body.length+1] = (byte) (crc >>> 8);
        return result;
    }
    public static Result respond(byte[] request, List<Device> devices) {
        if (request.length < 4 || request.length > 256) return drop("INVALID_LENGTH");
        int crc = crc(request, request.length-2);
        if ((request[request.length-2]&255) != (crc&255) || (request[request.length-1]&255) != (crc>>>8)) return drop("INVALID_CRC");
        int address = request[0]&255, function = request[1]&255;
        Device device = devices.stream().filter(d -> d.address() == address).findFirst().orElse(null);
        if (device == null) return drop("UNKNOWN_SLAVE");
        if (function >= 128 || function == 0) return drop("INVALID_FUNCTION");
        if (function != 1 && function != 3 && function != 4) return exception(address,function,1,device.id());
        if (request.length != 8) return drop("INVALID_LENGTH");
        int start = (request[2]&255)*256+(request[3]&255), count = (request[4]&255)*256+(request[5]&255);
        int size = function == 1 ? 10 : function == 3 ? 39 : 63;
        // V1.5 的字段异常码定义：01 地址、02 数量、03 地址与数量总长度。
        if (start < 1 || start > size) return exception(address,function,1,device.id());
        if (count < 1 || count > (function == 1 ? 2000 : 125)) return exception(address,function,2,device.id());
        if (start + count - 1 > size) return exception(address,function,3,device.id());
        int byteCount = function == 1 ? (count+7)/8 : count*2;
        byte[] body = new byte[3+byteCount]; body[0]=(byte)address; body[1]=(byte)function; body[2]=(byte)byteCount;
        for (int i=0;i<count;i++) {
            if (function == 1) { if (device.coils().get(start-1+i)) body[3+i/8] |= (byte)(1 << (i%8)); }
            else {
                int value = (function == 3 ? device.holdingRegisters() : device.inputRegisters()).get(start-1+i);
                body[3+i*2]=(byte)(value>>>8); body[4+i*2]=(byte)value;
            }
        }
        return new Result(frame(body),device.id(),"OK");
    }
    private static Result drop(String reason) { return new Result(null,"",reason); }
    private static Result exception(int address,int function,int code,String id) {
        return new Result(frame((byte)address,(byte)(function|128),(byte)code),id,"EXCEPTION_"+code);
    }
}
