package com.toolhelper.ping;

import java.util.function.BooleanSupplier;
import static com.toolhelper.ping.PingModels.*;

/** 边界便于用无网络夹具验证调度；生产实现只运行固定系统命令。 */
public interface PingProbe {
    String platform();
    boolean available();
    Row probe(String ip, Options options, BooleanSupplier cancelled) throws InterruptedException;
}
