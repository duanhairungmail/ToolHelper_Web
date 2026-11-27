package com.toolhelper.modbus;

import com.toolhelper.modbus.ModbusModels.*;
import com.toolhelper.serial.SerialModels.Port;
import com.toolhelper.serial.SerialService;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Modbus操作状态机；端口资源由共享SerialService持有，只有本服务能通过内部入口发送固定协议帧。 */
@Service
public class ModbusService {
    private static final Logger LOG = LoggerFactory.getLogger(ModbusService.class);
    private static final int TIMEOUT_MS = 500, MAX_RETRIES = 2, POLL_INTERVAL_MS = 2000;
    private static final int MAX_EVENTS = 2000;
    private final SerialService serial;
    private final ExecutorService operations = Executors.newSingleThreadExecutor(r -> daemon(r, "toolhelper-modbus-operation"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "toolhelper-modbus-timer"));
    private final Deque<Event> events = new ArrayDeque<>();
    private Active active;
    private long cursor;

    public ModbusService(SerialService serial) {
        this.serial = serial;
        timer.scheduleWithFixedDelay(this::maintenance, 5, 5, TimeUnit.SECONDS);
    }

    public List<Port> ports() { return serial.ports(); }
    public synchronized Session session() { return active == null ? null : snapshot(); }

    public synchronized Session open(OpenRequest request) {
        if (active != null) throw new ApiException(409, "MODBUS_SESSION_ACTIVE", "Modbus会话已打开，请先关闭");
        if (request == null || request.port() == null || !request.port().matches("(?i)COM[1-9][0-9]{0,3}")
                || !Set.of(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).contains(request.baudRate())
                || request.protocol() == null || !Set.of("SHEN_HONG", "NAN_RUI_YI_HE").contains(request.protocol()))
            throw new ApiException(400, "INVALID_MODBUS_CONFIG", "串口、波特率或协议参数无效");
        com.toolhelper.serial.SerialModels.Session port = serial.openModbus(
                new com.toolhelper.serial.SerialModels.OpenRequest(request.port(), request.baudRate(), 8, "1", "NONE", "NONE"));
        // 清除旧会话事件并建立独立游标；该会话只接受严格序列化的RTU请求。
        events.clear(); cursor = 0;
        active = new Active(port.sessionId(), request.port(), request.baudRate(), request.protocol(), port.openedAt());
        LOG.info("Modbus会话已打开 sessionId={} port={} protocol={} baudRate={}", active.id, active.port, active.protocol, active.baudRate);
        return snapshot();
    }

    public synchronized void close(String id) {
        Active current = require(id); current.polling = false;
        if (current.cancel != null) current.cancel.set(true);
        serial.closeModbus(id);
        active = null; events.clear(); cursor = 0;
        LOG.info("Modbus会话已关闭 sessionId={} port={}", current.id, current.port);
    }

    public synchronized Operation startOperation(String id, OperationRequest request) {
        Active current = require(id);
        if (request == null || request.type() == null || !Set.of("SCAN", "READ", "RESET").contains(request.type()))
            throw new ApiException(400, "INVALID_MODBUS_OPERATION", "操作类型无效");
        if (current.polling || current.operationStatus.equals("QUEUED") || current.operationStatus.equals("RUNNING"))
            throw new ApiException(409, "MODBUS_OPERATION_BUSY", "当前操作尚未完成，请等待或停止轮询");
        if (request.type().equals("RESET") && (!current.protocol.equals("NAN_RUI_YI_HE") || !request.confirmed()
                || request.address() == null || request.address() < 1 || request.address() > 255))
            throw new ApiException(400, "MODBUS_RESET_CONFIRMATION_REQUIRED", "仅南瑞怡和协议支持复位；请确认并输入1至255的目标地址");
        if (request.type().equals("READ") && (request.address() == null || request.address() < 1 || request.address() > 255))
            throw new ApiException(400, "INVALID_DEVICE_ADDRESS", "设备地址必须为1至255");
        current.cancel = new AtomicBoolean(); current.operationId = UUID.randomUUID().toString();
        current.operationType = request.type(); current.operationStatus = "QUEUED"; current.operationMessage = "等待执行";
        current.operationStarted = Instant.now(); current.operationCompleted = null; current.operationAddress = request.address(); current.transmissions = 0;
        Operation result = operationSnapshot(current);
        operations.submit(() -> execute(current, request));
        return result;
    }

    public synchronized void cancelOperation(String id, String operationId) {
        Active current = require(id);
        if (!Objects.equals(current.operationId, operationId) || current.cancel == null)
            throw new ApiException(404, "MODBUS_OPERATION_NOT_FOUND", "操作不存在或已结束");
        current.cancel.set(true);
        if (current.polling) current.polling = false;
    }

    public synchronized Session startPolling(String id) {
        Active current = require(id);
        if (current.devices.isEmpty()) throw new ApiException(409, "MODBUS_SCAN_REQUIRED", "请先扫描下辖设备");
        if (current.operationStatus.equals("RUNNING") || current.operationStatus.equals("QUEUED")) throw new ApiException(409, "MODBUS_OPERATION_BUSY", "当前操作尚未结束");
        if (current.polling) return snapshot();
        current.polling = true; current.pollIndex = 0;
        current.poll = timer.scheduleWithFixedDelay(() -> pollNext(current), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        addEvent(current, "STATE", null, "POLLING_STARTED", "开始逐设备轮询"); return snapshot();
    }

    public synchronized Session stopPolling(String id) {
        Active current = require(id); current.polling = false;
        if (current.poll != null) current.poll.cancel(false);
        // 停止只阻止下一台设备的请求；当前读取按原超时策略收尾，避免留下半次轮询结果。
        addEvent(current, "STATE", null, "POLLING_STOPPED", "停止后续轮询请求；当前设备读取继续完成"); return snapshot();
    }

    public synchronized void heartbeat(String id) { Active current = require(id); serial.heartbeatModbus(id); current.leaseUntil = Instant.now().plusSeconds(60); }

    public synchronized Events events(String id, long after) {
        Active current = require(id);
        if (after < 0) throw new ApiException(400, "INVALID_EVENT_CURSOR", "事件游标不能为负数");
        long first = events.isEmpty() ? cursor + 1 : events.getFirst().cursor();
        return new Events(id, cursor, after < first - 1, events.stream().filter(e -> e.cursor() > after).toList(), snapshot());
    }

    private void execute(Active current, OperationRequest request) {
        synchronized (this) {
            if (active != current) return;
            current.operationStatus = "RUNNING"; current.operationMessage = "操作进行中";
            if (request.type().equals("SCAN")) current.devices.values().forEach(device -> {
                device.readStatus = "历史"; device.readMessage = "本次扫描尚未完成，保留上次结果";
            });
        }
        try {
            switch (request.type()) {
                case "SCAN" -> scan(current);
                case "READ" -> readDevice(current, request.address());
                case "RESET" -> reset(current, request.address());
                default -> throw new IllegalArgumentException("未知操作");
            }
            complete(current, current.cancel.get() ? "CANCELLED" : "SUCCEEDED", current.cancel.get() ? "已停止后续步骤" : "操作完成");
        } catch (PartialFailure failure) { complete(current, "PARTIAL", failure.getMessage()); }
        catch (RuntimeException failure) {
            if (request.type().equals("READ") && request.address() != null) markReadFailed(current, request.address(), safeMessage(failure));
            String status = failure instanceof UnknownOutcome ? "UNKNOWN" : current.cancel.get() ? "CANCELLED" : "FAILED";
            LOG.warn("Modbus操作结束 sessionId={} operationId={} type={} status={} errorType={}", current.id, current.operationId, request.type(), status, failure.getClass().getSimpleName());
            complete(current, status, safeMessage(failure));
        }
    }

    private void scan(Active current) {
        byte[] listFrame = exchange(current, 0, 3, 0, 16, "SCAN", 16);
        verifyRead(listFrame, 0, 3, 16);
        int[] addresses = ModbusCodec.parseDevicePositions(listFrame);
        int[] states = new int[16]; Arrays.fill(states, -1);
        if (current.protocol.equals("NAN_RUI_YI_HE")) {
            byte[] stateFrame = exchange(current, 0, 3, 0xEE, 16, "SCAN", 16);
            verifyRead(stateFrame, 0, 3, 16); states = ModbusCodec.parseCommunicationPositions(stateFrame);
        }
        synchronized (this) {
            if (active != current) return;
            current.positions.clear(); current.devices.clear(); Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 16; i++) {
                int state = states[i]; int address = addresses[i]; boolean duplicate = address != 0 && !seen.add(address);
                current.positions.add(new Slot(i + 1, address, state < 0 ? null : state, duplicate));
                if (duplicate) { addEvent(current, "STATE", null, "DUPLICATE_DEVICE_ADDRESS", "设备地址" + address + "重复出现；仅保留首个位置，通讯状态按首个位置关联"); continue; }
                if (address == 0) continue;
                DeviceState device = new DeviceState(address); device.communicationState = state; device.readStatus = "未读取";
                if (address > 247) { device.readStatus = "地址待核对"; device.readMessage = "地址超出常用Modbus从站范围，未加入轮询"; }
                current.devices.put(address, device);
            }
            addEvent(current, "STATE", null, "SCAN_COMPLETE", "扫描完成，发现" + current.devices.size() + "个唯一地址");
        }
    }

    private void readDevice(Active current, int address) {
        synchronized (this) { current.devices.computeIfAbsent(address, DeviceState::new).readStatus = "读取中"; }
        if (current.protocol.equals("SHEN_HONG")) {
            byte[] frame = exchange(current, address, 3, 0x10, 22, "READ", 44);
            Map<String, Object> data = ModbusCodec.parseShenHong(frame, address); update(current, address, data, "申弘点表");
            checkDeviceAddress(current, address, data);
        } else {
            byte[] realtime = exchange(current, address, 4, 1, 28, "READ", 56);
            Map<String, Object> live = ModbusCodec.parseNanRuiRealtime(realtime, address); updatePartial(current, address, live);
            byte[] holding;
            try { holding = exchange(current, address, 3, 8, 25, "READ", 50); }
            catch (RuntimeException failure) {
                synchronized (this) {
                    DeviceState d = current.devices.get(address); d.readStatus = "部分成功";
                    d.readMessage = "实时寄存器已更新，阈值读取失败；" + safeMessage(failure);
                    d.updatedAt = Instant.now(); d.dataSource = "南瑞旧项目映射0x11-0x1B；实时数据已更新，阈值沿用历史值";
                }
                throw new PartialFailure("实时数据已读取；阈值读取失败，已保留历史阈值");
            }
            Map<String, Object> config = ModbusCodec.parseNanRuiHolding(holding, address); updatePartial(current, address, config);
            checkDeviceAddress(current, address, config); finishRead(current, address, "南瑞旧项目映射0x11-0x1B（每寄存器高字节）");
        }
    }

    private void reset(Active current, int address) {
        if (!current.protocol.equals("NAN_RUI_YI_HE") || current.polling) throw new IllegalStateException("复位仅允许在南瑞协议且停止轮询时执行");
        byte[] request = ModbusCodec.resetRequest(address); long after = serial.modbusEvents(current.id, 0).cursor();
        synchronized (this) { addEvent(current, "TX", request, "RESET", "发送一次；不会自动重试"); current.transmissions++; }
        try {
            if (serial.writeModbus(current.id, request).status().equals("FAILED")) throw new UnknownOutcome("复位报文写入结果不完整，设备状态未知");
        } catch (ApiException failure) { throw new UnknownOutcome("复位报文写入结果不完整，设备状态未知；请勿自动重试"); }
        byte[] echo;
        try { echo = awaitFrame(current, address, 5, 8, TIMEOUT_MS, after); }
        catch (RuntimeException failure) { throw new UnknownOutcome("复位命令已发送但未收到确认回显，执行结果未知；请先读取设备状态，不要直接重试"); }
        try { ModbusCodec.verifyEcho(echo, request); }
        catch (IllegalArgumentException failure) { throw new UnknownOutcome("复位回显不匹配，执行结果未知；请先读取设备状态"); }
        synchronized (this) { addEvent(current, "STATE", echo, "RESET_CONFIRMED", "收到匹配回显；尚未验证复位后状态"); }
    }

    private byte[] exchange(Active current, int address, int function, int start, int count, String kind, int expectedBytes) {
        if (current.cancel.get()) throw new CancellationException("操作已取消");
        byte[] request = ModbusCodec.request(address, function, start, count);
        int expectedResponseAddress = address;
        long after = serial.modbusEvents(current.id, 0).cursor();
        synchronized (this) { addEvent(current, "TX", request, kind, "请求开始地址0x" + Integer.toHexString(start).toUpperCase(Locale.ROOT)); }
        com.toolhelper.serial.SerialModels.Event written = serial.writeModbus(current.id, request);
        synchronized (this) { current.transmissions++; }
        if (written.status().equals("FAILED")) throw new IllegalStateException("串口写入不完整；没有自动补写");
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            if (attempt > 1) {
                synchronized (this) { addEvent(current, "TX", request, kind, "超时重试" + (attempt - 1)); current.transmissions++; }
                com.toolhelper.serial.SerialModels.Event retryWrite = serial.writeModbus(current.id, request);
                if (retryWrite.status().equals("FAILED")) throw new IllegalStateException("重试报文写入不完整；没有自动补写");
            }
            try {
                byte[] frame = awaitFrame(current, expectedResponseAddress, function, expectedBytes + 5, TIMEOUT_MS, after);
                if (frame.length == 5 && (frame[1] & 0x80) != 0) throw new IllegalStateException("设备返回Modbus异常码0x" + String.format("%02X", frame[2]));
                verifyRead(frame, expectedResponseAddress, function, expectedBytes);
                synchronized (this) { addEvent(current, "RX", frame, kind, "CRC、地址、功能码和数据长度验证通过；尝试" + attempt); }
                return frame;
            } catch (TimeoutException | FrameMismatch failure) { last = new IllegalStateException(failure.getMessage()); }
        }
        throw new IllegalStateException(last == null ? "设备响应超时" : "设备响应超时或不匹配，已重试2次");
    }

    private byte[] awaitFrame(Active current, int address, int function, int expectedLength, int timeoutMs, long after) {
        long start = System.nanoTime(); ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long serialCursor = after;
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < timeoutMs) {
            if (active != current) throw new CancellationException("串口会话已关闭");
            if (current.cancel.get() && !current.operationType.equals("RESET")) throw new CancellationException("操作已取消");
            com.toolhelper.serial.SerialModels.Events incoming = serial.modbusEvents(current.id, serialCursor); serialCursor = incoming.cursor();
            for (com.toolhelper.serial.SerialModels.Event event : incoming.events()) if (event.direction().equals("RX")) {
                synchronized (this) { addEvent(current, "RX", ModbusCodec.unhex(event.hex()), "RAW_RECEIVED", "收到" + event.byteCount() + "字节原始数据"); }
                List<byte[]> frames;
                try { frames = ModbusCodec.extract(buffer, ModbusCodec.unhex(event.hex())); }
                catch (RuntimeException ignored) { continue; }
                for (byte[] frame : frames) {
                    if (frame.length == 5 && (frame[1] & 0x80) != 0 && (frame[0] & 0xFF) == address && (frame[1] & 0x7F) == function) return frame;
                    if (frame.length == expectedLength && (frame[0] & 0xFF) == address && (frame[1] & 0xFF) == function) return frame;
                    synchronized (this) { addEvent(current, "RX", frame, current.operationType, "忽略与当前请求不匹配的帧"); }
                }
            }
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("操作线程已中断"); }
        }
        throw new TimeoutException("等待设备响应超时");
    }

    private void verifyRead(byte[] frame, int address, int function, int bytes) {
        if (frame.length == 5 && (frame[1] & 0x80) != 0) throw new IllegalStateException("设备返回Modbus异常码0x" + String.format("%02X", frame[2]));
        try { ModbusCodec.verifyFrame(frame, address, function, bytes); }
        catch (IllegalArgumentException e) { throw new FrameMismatch(e.getMessage()); }
    }

    private void update(Active current, int address, Map<String, Object> data, String source) {
        synchronized (this) {
            DeviceState d = current.devices.computeIfAbsent(address, DeviceState::new); apply(d, data); d.readStatus = "成功"; d.readMessage = "本次读取完成"; d.updatedAt = Instant.now(); d.dataSource = source;
            addEvent(current, "STATE", null, "DEVICE_UPDATED", "设备" + address + "读取成功");
        }
    }
    private void updatePartial(Active current, int address, Map<String, Object> data) { synchronized (this) { DeviceState d = current.devices.computeIfAbsent(address, DeviceState::new); apply(d, data); } }
    private void apply(DeviceState d, Map<String, Object> data) {
        if (data.containsKey("alarmLevel")) { d.alarmLevel = (Integer) data.get("alarmLevel"); d.alarmName = ModbusCodec.alarmName(d.alarmLevel); }
        if (data.containsKey("faultWord")) { d.faultWord = (Integer) data.get("faultWord"); d.faults = ModbusCodec.faults(d.faultWord); }
        if (data.containsKey("ionValue")) d.ionValue = (Integer) data.get("ionValue");
        if (data.containsKey("workStatus")) d.workStatus = (Integer) data.get("workStatus");
        if (data.containsKey("commStatus")) d.commStatus = (Integer) data.get("commStatus");
        if (data.containsKey("deviceAddress")) d.deviceAddress = (Integer) data.get("deviceAddress");
        if (data.containsKey("serialHex")) { d.serialHex = (String) data.get("serialHex"); d.serialText = (String) data.get("serialText"); d.serialMapping = (String) data.get("serialMapping"); }
        if (data.containsKey("thresholds")) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> values = (List<Map<String, Object>>) data.get("thresholds");
            for (Map<String, Object> item : values) d.thresholds.put((String) item.get("name"), (Integer) item.get("value"));
        }
    }
    private void checkDeviceAddress(Active current, int address, Map<String, Object> data) {
        Object raw = data.get("deviceAddress"); if (raw instanceof Integer value && value != address) synchronized (this) {
            DeviceState d = current.devices.get(address); d.readStatus = "警告"; d.readMessage = "寄存器通讯地址" + value + "与请求地址" + address + "不一致";
        }
    }
    private void finishRead(Active current, int address, String source) { synchronized (this) {
        DeviceState d = current.devices.get(address);
        if (d.deviceAddress != null && d.deviceAddress != address) { d.readStatus = "警告"; d.readMessage = "通讯地址" + d.deviceAddress + "与请求地址" + address + "不一致"; }
        else { d.readStatus = "成功"; d.readMessage = "本次两步读取完成"; }
        d.updatedAt = Instant.now(); d.dataSource = source;
        addEvent(current, "STATE", null, "DEVICE_UPDATED", "设备" + address + "两步读取成功");
    } }
    private void markReadFailed(Active current, int address, String message) { synchronized (this) {
        DeviceState d = current.devices.computeIfAbsent(address, DeviceState::new); d.readStatus = "失败"; d.readMessage = message;
        addEvent(current, "STATE", null, "DEVICE_READ_FAILED", "设备" + address + "：" + message);
    } }

    private void pollNext(Active current) {
        synchronized (this) {
            if (active != current || !current.polling || current.operationStatus.equals("RUNNING") || current.devices.isEmpty()) return;
            List<Integer> addresses = current.devices.keySet().stream().sorted().toList();
            int address = addresses.get(current.pollIndex++ % addresses.size());
            current.cancel = new AtomicBoolean(); current.operationId = UUID.randomUUID().toString(); current.operationType = "POLL_READ";
            current.operationStatus = "RUNNING"; current.operationStarted = Instant.now(); current.operationAddress = address; current.transmissions = 0;
            operations.submit(() -> {
                try { readDevice(current, address); complete(current, "SUCCEEDED", "设备" + address + "轮询读取完成"); }
                catch (PartialFailure e) { complete(current, "PARTIAL", e.getMessage()); }
                catch (RuntimeException e) {
                    markReadFailed(current, address, safeMessage(e));
                    complete(current, current.cancel.get() ? "CANCELLED" : "FAILED", safeMessage(e));
                }
            });
        }
    }

    private synchronized void complete(Active current, String status, String message) {
        if (active != current) return;
        current.operationStatus = status; current.operationMessage = message; current.operationCompleted = Instant.now();
        addEvent(current, "STATE", null, status, message);
    }
    private synchronized Session snapshot() {
        Active c = active;
        List<Device> devices = c.devices.values().stream().sorted(Comparator.comparingInt(d -> d.address)).map(d -> d.snapshot(c.protocol.equals("SHEN_HONG"))).toList();
        return new Session(c.id, c.port, c.baudRate, c.protocol, c.openedAt, c.operationStatus, c.operationType,
                c.operationId, c.operationMessage, c.polling, POLL_INTERVAL_MS, c.leaseUntil, cursor, devices, List.copyOf(c.positions));
    }
    private Operation operationSnapshot(Active c) { return new Operation(c.operationId, c.operationStatus, c.operationType, c.operationMessage, c.operationStarted, c.operationCompleted, c.operationAddress, c.transmissions); }
    private Active require(String id) {
        if (active == null || id == null || !active.id.equals(id)) throw new ApiException(404, "MODBUS_SESSION_NOT_FOUND", "Modbus会话已关闭或已失效，请刷新状态"); return active;
    }
    private void addEvent(Active c, String direction, byte[] frame, String verification, String note) {
        String hex = frame == null ? "" : ModbusCodec.hex(frame);
        events.addLast(new Event(++cursor, direction, hex, frame == null ? 0 : frame.length, Instant.now(), c.operationType, c.transmissions, verification, note));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    private void maintenance() {
        Active current; synchronized (this) { current = active; if (current == null) return; }
        try {
            if (!Instant.now().isBefore(current.leaseUntil)) {
                synchronized (this) { if (active == current) { current.polling = false; if (current.cancel != null) current.cancel.set(true); } }
                serial.closeModbus(current.id); synchronized (this) { if (active == current) active = null; }
                LOG.info("Modbus会话因页面租约到期而关闭 sessionId={}", current.id);
            }
        } catch (RuntimeException failure) { LOG.warn("Modbus租约清理失败 sessionId={} errorType={}", current.id, failure.getClass().getSimpleName()); }
    }

    private static String safeMessage(RuntimeException e) { return e.getMessage() == null ? "操作失败，请检查串口和设备状态" : e.getMessage(); }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    private static final class TimeoutException extends RuntimeException { TimeoutException(String m) { super(m); } }
    private static final class FrameMismatch extends RuntimeException { FrameMismatch(String m) { super(m); } }
    private static final class PartialFailure extends RuntimeException { PartialFailure(String m) { super(m); } }
    private static final class UnknownOutcome extends RuntimeException { UnknownOutcome(String m) { super(m); } }
    private static final class Active {
        final String id, port, protocol; final int baudRate; final Instant openedAt; Instant leaseUntil = Instant.now().plusSeconds(60);
        final LinkedHashMap<Integer, DeviceState> devices = new LinkedHashMap<>(); final List<Slot> positions = new ArrayList<>();
        String operationStatus = "IDLE", operationType = "", operationId = "", operationMessage = "";
        Instant operationStarted, operationCompleted; Integer operationAddress; int transmissions, pollIndex;
        boolean polling; ScheduledFuture<?> poll; AtomicBoolean cancel = new AtomicBoolean();
        Active(String id, String port, int baudRate, String protocol, Instant openedAt) { this.id = id; this.port = port; this.baudRate = baudRate; this.protocol = protocol; this.openedAt = openedAt; }
    }
    private static final class DeviceState {
        final int address; String serialHex = "", serialText = "", serialMapping = "", alarmName = "未读取", faultHex = "", readStatus = "未读取", readMessage = "", dataSource = "";
        Integer alarmLevel, faultWord, ionValue, workStatus, commStatus, communicationState, deviceAddress; Instant updatedAt;
        List<String> faults = List.of(); final LinkedHashMap<String, Integer> thresholds = new LinkedHashMap<>();
        DeviceState(int address) { this.address = address; }
        Device snapshot(boolean shenHong) {
            if (faultWord != null) faultHex = String.format("0x%04X", faultWord);
            Integer visibleCommStatus = shenHong ? commStatus : communicationState;
            String visibleCommText = visibleCommStatus == null ? "未获取"
                    : shenHong ? "原始值 " + visibleCommStatus + "（含义待核对）"
                    : visibleCommStatus == 1 ? "站端报告正常" : visibleCommStatus == 0 ? "站端报告异常" : "站端原始值 " + visibleCommStatus;
            return new Device(address, serialHex, serialText, serialMapping, alarmName, alarmLevel, List.copyOf(faults), faultHex,
                    ionValue, workStatus, workStatus == null ? "未读取" : "原始值 " + workStatus,
                    visibleCommStatus, visibleCommText,
                    deviceAddress, thresholds.entrySet().stream().map(e -> new Threshold(e.getKey(), e.getValue())).toList(),
                    readStatus, readMessage, updatedAt, dataSource);
        }
    }

    @PreDestroy public synchronized void shutdown() {
        if (active != null) { Active c = active; active = null; c.polling = false; if (c.cancel != null) c.cancel.set(true); try { serial.closeModbus(c.id); } catch (RuntimeException e) { LOG.warn("服务退出时Modbus端口释放失败 sessionId={} errorType={}", c.id, e.getClass().getSimpleName()); } }
        operations.shutdownNow(); timer.shutdownNow(); events.clear();
    }
}
