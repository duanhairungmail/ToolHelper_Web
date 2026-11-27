package com.toolhelper.serial;

import com.toolhelper.serial.SerialModels.*;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 单活动会话服务；所有TX经同一把锁串行写入，RX与TX事件均使用有界内存队列。 */
@Service
public class SerialService {
    private static final Logger LOG = LoggerFactory.getLogger(SerialService.class);
    private static final int MAX_EVENTS = 2_000;
    private static final int MAX_EVENT_HEX_CHARS = 1_000_000;
    private static final int MAX_INTERVAL_MS = 86_400_000;
    private final SerialPortAdapter adapter;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "toolhelper-serial"); thread.setDaemon(true); return thread;
    });
    private final Deque<Event> events = new ArrayDeque<>();
    private final AtomicInteger polling = new AtomicInteger();
    private Active active;
    private long cursor;
    private int retainedHexChars;

    public SerialService(SerialPortAdapter adapter) { this.adapter = adapter; }

    public List<Port> ports() {
        return adapter.listPorts().stream().map(item -> new Port(item.name(), item.description())).toList();
    }

    public synchronized Session session() { return active != null && active.owner.equals("BASIC") ? snapshot() : null; }

    public synchronized Session open(OpenRequest request) {
        return open(request, "BASIC");
    }

    /** Modbus 与基础串口共用同一native句柄和所有权检查。 */
    public synchronized Session openModbus(OpenRequest request) {
        return open(request, "MODBUS");
    }

    private Session open(OpenRequest request, String owner) {
        if (active != null) throw new ApiException(409, "SERIAL_SESSION_ACTIVE", "已有活动串口，请先关闭当前会话");
        validate(request);
        events.clear(); retainedHexChars = 0; cursor = 0;
        SerialPortAdapter.Device device = adapter.open(request);
        active = new Active(UUID.randomUUID().toString(), request, device, Instant.now(), owner);
        active.poll = executor.scheduleWithFixedDelay(this::poll, 0, 20, TimeUnit.MILLISECONDS);
        LOG.info("串口会话已打开 sessionId={} owner={} port={} baudRate={} dataBits={} stopBits={} parity={} flowControl={}",
                active.id, owner, request.port(), request.baudRate(), request.dataBits(), request.stopBits(), request.parity(), request.flowControl());
        return snapshot();
    }

    public synchronized void close(String sessionId) {
        Active current = require(sessionId, "BASIC");
        stopAuto(current);
        current.poll.cancel(false);
        try { current.device.close(); }
        catch (RuntimeException failure) { throw new ApiException(500, "SERIAL_CLOSE_FAILED", "串口关闭失败，请检查设备状态"); }
        active = null;
        events.clear(); retainedHexChars = 0; cursor = 0;
        LOG.info("串口会话已关闭 sessionId={} port={}", current.id, current.request.port());
    }

    public synchronized Event write(String sessionId, WriteRequest request) {
        Active current = require(sessionId, "BASIC");
        byte[] bytes = SerialCodec.build(request);
        return write(current, bytes);
    }

    public synchronized Session startAutoSend(String sessionId, AutoSendRequest request) {
        Active current = require(sessionId, "BASIC");
        if (request == null || request.write() == null || request.intervalMs() < 100 || request.intervalMs() > MAX_INTERVAL_MS)
            throw new ApiException(400, "INVALID_AUTO_SEND", "定时发送周期必须为100ms至24小时");
        byte[] bytes = SerialCodec.build(request.write());
        stopAuto(current);
        current.autoBytes = bytes;
        current.interval = request.intervalMs();
        current.auto = executor.scheduleWithFixedDelay(() -> autoWrite(current), 0,
                request.intervalMs(), TimeUnit.MILLISECONDS);
        LOG.info("串口定时发送已启动 sessionId={} intervalMs={} payloadBytes={}", current.id, request.intervalMs(), bytes.length);
        return snapshot();
    }

    public synchronized Session stopAutoSend(String sessionId) {
        stopAuto(require(sessionId, "BASIC"));
        return snapshot();
    }

    public synchronized Events events(String sessionId, long after) {
        Active current = require(sessionId, "BASIC");
        if (after < 0) throw new ApiException(400, "INVALID_EVENT_CURSOR", "事件游标不能为负数");
        long first = events.isEmpty() ? cursor + 1 : events.getFirst().cursor();
        boolean gap = after < first - 1;
        List<Event> result = events.stream().filter(event -> event.cursor() > after).toList();
        return new Events(current.id, cursor, gap, result, snapshot());
    }

    public synchronized Session modbusSession() {
        return active != null && active.owner.equals("MODBUS") ? snapshot() : null;
    }

    public synchronized void heartbeatModbus(String sessionId) {
        Active current = require(sessionId, "MODBUS");
        current.leaseUntil = Instant.now().plusSeconds(60);
    }

    public synchronized void closeModbus(String sessionId) {
        closeOwned(sessionId, "MODBUS");
    }

    public synchronized Event writeModbus(String sessionId, byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes.length > 256)
            throw new ApiException(400, "INVALID_MODBUS_FRAME", "Modbus报文长度无效");
        return write(require(sessionId, "MODBUS"), bytes.clone());
    }

    public synchronized Events modbusEvents(String sessionId, long after) {
        Active current = require(sessionId, "MODBUS");
        if (after < 0) throw new ApiException(400, "INVALID_EVENT_CURSOR", "事件游标不能为负数");
        long first = events.isEmpty() ? cursor + 1 : events.getFirst().cursor();
        boolean gap = after < first - 1;
        List<Event> result = events.stream().filter(event -> event.cursor() > after).toList();
        return new Events(current.id, cursor, gap, result, snapshot());
    }

    public synchronized boolean modbusLeaseExpired(String sessionId) {
        Active current = require(sessionId, "MODBUS");
        return !Instant.now().isBefore(current.leaseUntil);
    }

    private void closeOwned(String sessionId, String owner) {
        Active current = require(sessionId, owner);
        stopAuto(current);
        current.poll.cancel(false);
        try { current.device.close(); }
        catch (RuntimeException failure) { throw new ApiException(500, "SERIAL_CLOSE_FAILED", "串口关闭失败，请检查设备状态"); }
        active = null;
        events.clear(); retainedHexChars = 0; cursor = 0;
        LOG.info("串口会话已关闭 sessionId={} owner={} port={}", current.id, owner, current.request.port());
    }

    private synchronized void poll() {
        Active current = active;
        if (current == null || !polling.compareAndSet(0, 1)) return;
        try {
            byte[] buffer = new byte[4096];
            int count = current.device.read(buffer);
            if (count < 0) { failSession(current, null); return; }
            if (count > 0) {
                byte[] received = Arrays.copyOf(buffer, count);
                current.rxBytes += count; current.lastRxAt = Instant.now();
                addEvent(current, "RX", received, "RECEIVED");
            }
        } catch (RuntimeException failure) { failSession(current, failure); }
        finally { polling.set(0); }
    }

    private synchronized void autoWrite(Active current) {
        if (active != current || current.auto == null || current.auto.isCancelled()) return;
        try { write(current, current.autoBytes); }
        catch (RuntimeException failure) { stopAuto(current); }
    }

    private Event write(Active current, byte[] bytes) {
        try {
            int written = current.device.write(bytes);
            if (written != bytes.length) {
                LOG.warn("串口未完整写入 sessionId={} requestedBytes={} writtenBytes={}", current.id, bytes.length, written);
                stopAuto(current);
                addEvent(current, "TX", bytes, "FAILED");
                throw new ApiException(502, "SERIAL_WRITE_FAILED", "串口未能完整写入本次数据；未自动重发");
            }
            current.txBytes += written; current.transmissions++; current.lastTxAt = Instant.now();
            return addEvent(current, "TX", bytes, "SENT");
        } catch (ApiException error) { throw error; }
        catch (RuntimeException failure) {
            LOG.warn("串口写入失败 sessionId={} payloadBytes={} errorType={}", current.id, bytes.length, failure.getClass().getSimpleName());
            stopAuto(current); addEvent(current, "TX", bytes, "FAILED");
            throw new ApiException(502, "SERIAL_WRITE_FAILED", "串口写入失败；未自动重发");
        }
    }

    private Event addEvent(Active current, String direction, byte[] bytes, String status) {
        String hex = SerialCodec.hex(bytes);
        Event event = new Event(++cursor, current.id, direction, hex, bytes.length, Instant.now(), status);
        events.addLast(event); retainedHexChars += hex.length();
        while (events.size() > MAX_EVENTS || retainedHexChars > MAX_EVENT_HEX_CHARS) retainedHexChars -= events.removeFirst().hex().length();
        return event;
    }

    private void failSession(Active current, RuntimeException failure) {
        if (active != current) return;
        stopAuto(current);
        current.poll.cancel(false);
        try { current.device.close(); }
        catch (RuntimeException closeFailure) {
            LOG.warn("失效串口释放失败 sessionId={} errorType={}", current.id, closeFailure.getClass().getSimpleName());
        }
        active = null;
        LOG.warn("串口会话失效 sessionId={} port={} errorType={}", current.id, current.request.port(),
                failure == null ? "DEVICE_DISCONNECTED" : failure.getClass().getSimpleName());
    }

    private Session snapshot() {
        if (active == null) return null;
        Active item = active;
        return new Session(item.id, item.request.port(), item.request.baudRate(), item.request.dataBits(),
                item.request.stopBits(), item.request.parity(), item.request.flowControl(), item.openedAt,
                item.txBytes, item.rxBytes, item.transmissions, item.lastTxAt, item.lastRxAt,
                item.auto != null && !item.auto.isCancelled(), item.interval == 0 ? null : item.interval);
    }

    private Active require(String id) {
        if (active == null || id == null || !active.id.equals(id)) throw new ApiException(404, "SERIAL_SESSION_NOT_FOUND", "串口会话已关闭或已失效，请刷新状态");
        return active;
    }

    private Active require(String id, String owner) {
        Active current = require(id);
        if (!current.owner.equals(owner)) throw new ApiException(409, "SERIAL_SESSION_OWNED_BY_OTHER_TOOL", "串口正由另一工具占用");
        return current;
    }
    private void stopAuto(Active item) {
        if (item.auto != null) { item.auto.cancel(false); item.auto = null; }
        item.autoBytes = null; item.interval = 0;
    }
    private static void validate(OpenRequest request) {
        if (request == null || request.port() == null || !request.port().matches("(?i)COM[1-9][0-9]{0,3}")
                || !Set.of(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).contains(request.baudRate())
                || !Set.of(5, 6, 7, 8).contains(request.dataBits())
                || request.stopBits() == null || !Set.of("1", "1.5", "2").contains(request.stopBits())
                || request.parity() == null || !Set.of("NONE", "ODD", "EVEN", "MARK", "SPACE").contains(request.parity())
                || request.flowControl() == null || !Set.of("NONE", "XON_XOFF", "RTS_CTS").contains(request.flowControl()))
            throw new ApiException(400, "INVALID_SERIAL_CONFIG", "串口或连接参数无效");
    }

    @PreDestroy public synchronized void close() {
        if (active != null) {
            Active current = active; active = null; stopAuto(current);
            if (current.poll != null) current.poll.cancel(false);
            try { current.device.close(); }
            catch (RuntimeException closeFailure) {
                LOG.warn("服务退出时串口释放失败 sessionId={} errorType={}", current.id, closeFailure.getClass().getSimpleName());
            }
        }
        executor.shutdownNow(); events.clear();
    }

    private static final class Active {
        final String id; final OpenRequest request; final SerialPortAdapter.Device device; final Instant openedAt; final String owner;
        Instant leaseUntil = Instant.now().plusSeconds(60);
        long txBytes, rxBytes, transmissions; Instant lastTxAt, lastRxAt;
        ScheduledFuture<?> poll, auto; byte[] autoBytes; int interval;
        Active(String id, OpenRequest request, SerialPortAdapter.Device device, Instant openedAt, String owner) {
            this.id = id; this.request = request; this.device = device; this.openedAt = openedAt; this.owner = owner;
        }
    }
}
