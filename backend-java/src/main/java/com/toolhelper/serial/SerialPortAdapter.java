package com.toolhelper.serial;

import java.util.List;

public interface SerialPortAdapter {
    record PortInfo(String name, String description) {}
    List<PortInfo> listPorts();
    Device open(SerialModels.OpenRequest request);

    interface Device extends AutoCloseable {
        int read(byte[] buffer);
        int write(byte[] bytes);
        @Override void close();
    }
}
