package com.toolhelper.devicemac;

import java.util.Map;
import java.util.function.BooleanSupplier;
import static com.toolhelper.devicemac.DeviceMacModels.*;

public interface MacResolver {
    Map<String, Object> capabilities();
    Outcome resolve(String ip, BooleanSupplier cancelled) throws InterruptedException;
}
