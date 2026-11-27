package com.toolhelper.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.toolhelper.support.ApiException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JSerialCommAdapter implements SerialPortAdapter {
    @Override public List<PortInfo> listPorts() {
        return Arrays.stream(SerialPort.getCommPorts())
                .map(port -> new PortInfo(port.getSystemPortName(), port.getDescriptivePortName()))
                .filter(port -> port.name() != null && port.name().matches("(?i)COM[1-9][0-9]{0,3}"))
                .toList();
    }

    @Override public Device open(SerialModels.OpenRequest request) {
        PortInfo selected = listPorts().stream().filter(port -> port.name().equalsIgnoreCase(request.port())).findFirst()
                .orElseThrow(() -> new ApiException(400, "SERIAL_PORT_NOT_AVAILABLE", "所选串口已不存在，请刷新端口列表"));
        SerialPort port = SerialPort.getCommPort(selected.name());
        int stopBits = switch (request.stopBits()) {
            case "1" -> SerialPort.ONE_STOP_BIT;
            case "1.5" -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case "2" -> SerialPort.TWO_STOP_BITS;
            default -> throw new ApiException(400, "INVALID_SERIAL_CONFIG", "停止位参数无效");
        };
        int parity = switch (request.parity()) {
            case "NONE" -> SerialPort.NO_PARITY;
            case "ODD" -> SerialPort.ODD_PARITY;
            case "EVEN" -> SerialPort.EVEN_PARITY;
            case "MARK" -> SerialPort.MARK_PARITY;
            case "SPACE" -> SerialPort.SPACE_PARITY;
            default -> throw new ApiException(400, "INVALID_SERIAL_CONFIG", "校验位参数无效");
        };
        int flow = switch (request.flowControl()) {
            case "NONE" -> SerialPort.FLOW_CONTROL_DISABLED;
            case "XON_XOFF" -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            case "RTS_CTS" -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            default -> throw new ApiException(400, "INVALID_SERIAL_CONFIG", "流控参数无效");
        };
        if (!port.setComPortParameters(request.baudRate(), request.dataBits(), stopBits, parity)
                || !port.setFlowControl(flow))
            throw new ApiException(400, "SERIAL_CONFIG_UNSUPPORTED", "串口驱动不支持所选参数组合");
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        if (!port.openPort()) throw new ApiException(409, "SERIAL_PORT_OPEN_FAILED", "无法打开串口；设备可能被占用、已拔出或驱动不支持所选参数");
        return new Device() {
            @Override public int read(byte[] buffer) {
                int available = Math.min(buffer.length, Math.max(0, port.bytesAvailable()));
                return available == 0 ? 0 : port.readBytes(buffer, available);
            }
            @Override public int write(byte[] bytes) { return (int) port.writeBytes(bytes, bytes.length); }
            @Override public void close() {
                if (port.isOpen() && !port.closePort())
                    throw new ApiException(500, "SERIAL_CLOSE_FAILED", "操作系统未确认串口已释放");
            }
        };
    }
}
