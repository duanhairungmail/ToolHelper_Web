package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MqttModbusService {
    public static final int MAX_SESSIONS=8, MAX_EVENTS=200, QUEUE_SIZE=64;
    public static final long LEASE_MS=60000;
    private final Map<String, Context> sessions = new LinkedHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"mqtt-modbus-lease"); t.setDaemon(true); return t; });
    private boolean destroyed;
    private final LongSupplier clock;
    public MqttModbusService() { this(System::currentTimeMillis); }
    MqttModbusService(LongSupplier clock) { this.clock=clock; reaper.scheduleWithFixedDelay(this::expire,5,5,TimeUnit.SECONDS); }

    private static final class Context {
        final String id=UUID.randomUUID().toString(); final Gateway gateway;
        final ThreadPoolExecutor worker;
        final AtomicLong received=new AtomicLong(),published=new AtomicLong(),dropped=new AtomicLong();
        final Deque<Event> events=new ArrayDeque<>();
        volatile List<Device> devices; long version=1, cursor; volatile long expires;
        final String kind;
        volatile List<MeterProfile.Snapshot> meters;
        final AtomicLong filtered=new AtomicLong();
        volatile String state="CONNECTING", error=""; volatile MqttClientAdapter client;
        Context(Gateway gateway,List<Device> devices,String kind,List<MeterProfile.Snapshot> meters,long now) {
            this.gateway=gateway; this.devices=devices; this.kind=kind; this.meters=meters; this.expires=now+LEASE_MS;
            worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(QUEUE_SIZE),r -> { var t=new Thread(r,"mqtt-modbus-"+id); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
        }
        synchronized Session snapshot() { return new Session(id,gateway,state,version,devices,received.get(),published.get(),dropped.get(),expires,error,kind,meters.stream().map(MeterProfile.Snapshot::meter).toList(),filtered.get()); }
        synchronized void event(String kind,String topic,String device,byte[] bytes,String result,long configVersion) {
            String crcStatus=bytes==null ? "NOT_CHECKED" : result.equals("INVALID_CRC") ? "INVALID" : result.equals("INVALID_LENGTH") ? "NOT_CHECKED" : "VALID";
            events.addLast(new Event(++cursor,System.currentTimeMillis(),gateway.key(),kind,topic,device,
                bytes==null?0:bytes.length, bytes!=null && bytes.length>0?bytes[0]&255:null, bytes!=null && bytes.length>1?bytes[1]&255:null,crcStatus,
                bytes == null ? "" : HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes),result,configVersion));
            while(events.size()>MAX_EVENTS) events.removeFirst();
        }
    }
    public Session open(OpenRequest request) {
        EarlyDeviceProfile.require(request != null,"缺少请求");
        Gateway g=EarlyDeviceProfile.gateway(request.gateway());
        String kind=request.kind()==null?"early":request.kind();
        EarlyDeviceProfile.require(Set.of("early","meter").contains(kind),"会话类型无效");
        boolean meter=kind.equals("meter");
        EarlyDeviceProfile.require(meter ? request.devices()==null || request.devices().isEmpty() : request.meters()==null || request.meters().isEmpty(),"电表与极早期设备不能混用");
        List<Device> devices=meter?List.of():EarlyDeviceProfile.devices(g.key(),request.devices());
        List<MeterProfile.Snapshot> meters=meter?MeterProfile.meters(g.key(),request.meters()):List.of();
        EarlyDeviceProfile.require(!meter || (MeterProfile.DOWN.equals(g.downTopic()) && MeterProfile.UP.equals(g.upTopic())),"电表必须使用固定主题");
        EarlyDeviceProfile.require(request.username()==null || request.username().length()<=256,"用户名过长");
        EarlyDeviceProfile.require(request.password()==null || request.password().length()<=1024,"密码过长");
        EarlyDeviceProfile.require(request.password()==null || request.password().isEmpty() || (request.username()!=null && !request.username().isBlank()),"使用密码时需填写用户名");
        Context c=new Context(g,devices,kind,meters,clock.getAsLong());
        synchronized(this) {
            if(destroyed) throw new ApiException(503,"MQTT_SERVICE_STOPPED","服务正在关闭");
            if(sessions.size()>=MAX_SESSIONS) throw new ApiException(409,"MQTT_SESSION_LIMIT","最多同时运行 8 个网关");
            for(Context old:sessions.values()) {
                if(old.gateway.key().equals(g.key())) conflict("该网关已有会话，请先停止");
                if(old.gateway.brokerUrl().equals(g.brokerUrl())) {
                    if(old.gateway.clientId().equals(g.clientId())) conflict("同 Broker 的 Client ID 重复");
                    boolean sharedMeters=meter && old.kind.equals("meter") && old.gateway.downTopic().equals(g.downTopic()) && old.gateway.upTopic().equals(g.upTopic());
                    if(sharedMeters) {
                        if(meters.stream().anyMatch(m->old.meters.stream().anyMatch(o->o.meter().address().equals(m.meter().address())))) conflict("同 Broker 电表从站地址重复");
                    } else if(old.gateway.downTopic().equals(g.downTopic()) || old.gateway.upTopic().equals(g.upTopic()) || old.gateway.upTopic().equals(g.downTopic()) || old.gateway.downTopic().equals(g.upTopic())) conflict("同 Broker 收发主题存在冲突");
                }
                Set<String> ids=new HashSet<>(old.devices.stream().map(Device::id).toList());
                if(devices.stream().anyMatch(d->ids.contains(d.id()))) conflict("设备已在其他网关会话运行");
                Set<String> allIds=new HashSet<>(ids); old.meters.forEach(m->allIds.add(m.meter().id()));
                if(meters.stream().anyMatch(m->allIds.contains(m.meter().id())) || devices.stream().anyMatch(d->old.meters.stream().anyMatch(m->m.meter().id().equals(d.id())))) conflict("电表或设备 ID 已在其他会话运行");
            }
            sessions.put(c.id,c);
        }
        LoggerFactory.getLogger(getClass()).info("MQTT Modbus open session={}, devices={}, qos={}",c.id,devices.size(),g.qos());
        try {
            synchronized(c) { if(!c.state.equals("CONNECTING")) throw new IllegalStateException(); c.client=new MqttClientAdapter(g,(bytes,retained)->receive(c,bytes,retained),cause->fail(c,"MQTT_CONNECTION_LOST")); }
            c.client.connect(g,request.username(),request.password());
            synchronized(c) { if(!c.state.equals("CONNECTING")) throw new IllegalStateException(); c.state="RUNNING"; c.event("连接",g.downTopic(),"",null,"SUBSCRIBED",c.version); }
            return c.snapshot();
        } catch(Exception e) {
            close(c.id);
            LoggerFactory.getLogger(getClass()).warn("MQTT connect/subscribe failed session={}, type={}",c.id,e.getClass().getSimpleName());
            throw new ApiException(502,"MQTT_CONNECT_FAILED","MQTT 连接或订阅失败，请检查 Broker、认证、主题权限及网络");
        }
    }
    private static void conflict(String message) { throw new ApiException(409,"MQTT_SESSION_CONFLICT",message); }
    private void receive(Context c,byte[] bytes,boolean retained) {
        c.received.incrementAndGet();
        if(bytes.length>256 || retained) {
            c.dropped.incrementAndGet(); c.event("丢弃",c.gateway.downTopic(),"",null,retained?"RETAINED_QUERY":"PAYLOAD_TOO_LARGE",c.snapshot().version()); return;
        }
        byte[] copy=bytes.clone();
        try { c.worker.execute(()->process(c,copy)); }
        catch(RejectedExecutionException e) { c.dropped.incrementAndGet(); c.event("丢弃",c.gateway.downTopic(),"",null,"QUEUE_FULL_OR_STOPPED",c.snapshot().version()); }
    }
    private void process(Context c,byte[] bytes) {
        List<Device> devices; List<MeterProfile.Snapshot> meters; long version;
        synchronized(c) {
            if(!Set.of("RUNNING","CONNECTING").contains(c.state)) return;
            devices=c.devices; meters=c.meters; version=c.version;
        }
        var result=c.kind.equals("meter")?MeterModbusCodec.respond(bytes,meters):EarlyModbusCodec.respond(bytes,devices);
        c.event("接收",c.gateway.downTopic(),result.deviceId(),bytes,result.result(),version);
        if(result.response()==null) { if(result.result().equals("FILTERED_SLAVE")) c.filtered.incrementAndGet(); else c.dropped.incrementAndGet(); return; }
        try {
            // 每个查询只发布一次；不在结果不明时重试，停止会话会关闭连接并打断队列。
            if(!Set.of("RUNNING","CONNECTING").contains(c.state)) return;
            c.client.publish(c.gateway,result.response()); c.published.incrementAndGet();
            c.event("发布",c.gateway.upTopic(),result.deviceId(),result.response(),result.result(),version);
        } catch(Exception e) { fail(c,"MQTT_PUBLISH_FAILED"); }
    }
    private void fail(Context c,String reason) {
        synchronized(c) {
            if(Set.of("STOPPED","FAILED").contains(c.state)) return;
            c.state="FAILED"; c.error=reason; c.event("异常","","",null,reason,c.version);
        }
        LoggerFactory.getLogger(getClass()).warn("MQTT session failed session={}, reason={}",c.id,reason);
        c.worker.shutdownNow();
        // 不能在 Paho 自身的回调线程 close（会等待自己退出）。
        Thread.ofVirtual().start(()->{ if(c.client!=null) c.client.close(); });
    }
    public synchronized List<Session> list() { return sessions.values().stream().map(Context::snapshot).toList(); }
    private synchronized Context require(String id) {
        Context c=sessions.get(id);
        if(c==null) throw new ApiException(404,"MQTT_SESSION_NOT_FOUND","网关会话不存在或已停止");
        return c;
    }
    public Session state(String id) { return require(id).snapshot(); }
    public Session profile(String id,ProfileRequest body) {
        Context c=require(id);
        EarlyDeviceProfile.require(body!=null && body.expectedVersion()!=null,"缺少配置版本");
        boolean meter=c.kind.equals("meter");
        EarlyDeviceProfile.require(meter ? body.devices()==null || body.devices().isEmpty() : body.meters()==null || body.meters().isEmpty(),"不能更改会话类型");
        List<Device> next=meter?List.of():EarlyDeviceProfile.devices(c.gateway.key(),body.devices());
        List<MeterProfile.Snapshot> nextMeters=meter?MeterProfile.meters(c.gateway.key(),body.meters()):List.of();
        synchronized(c) {
            if(!c.state.equals("RUNNING")) conflict("当前会话未运行");
            if(body.expectedVersion()!=c.version) conflict("配置版本已变化，请刷新状态后重试");
            Map<String,Integer> topology=new HashMap<>(); c.devices.forEach(d->topology.put(d.id(),d.address()));
            if(next.size()!=c.devices.size() || next.stream().anyMatch(d->!Objects.equals(topology.get(d.id()),d.address()))) conflict("运行中不能修改设备绑定或地址，请先停止网关");
            Set<String> meterTopology=new HashSet<>(); c.meters.forEach(m->meterTopology.add(m.meter().id()+"/"+m.meter().type()+"/"+m.meter().address()));
            if(nextMeters.size()!=c.meters.size() || nextMeters.stream().anyMatch(m->!meterTopology.contains(m.meter().id()+"/"+m.meter().type()+"/"+m.meter().address()))) conflict("运行中不能修改电表绑定或类型");
            c.devices=next; c.meters=nextMeters; c.version++; c.event("配置","","",null,"PROFILE_UPDATED",c.version);
            return c.snapshot();
        }
    }
    public Session heartbeat(String id) {
        Context c=require(id);
        synchronized(c) { if(c.state.equals("STOPPED")) conflict("网关已停止"); c.expires=clock.getAsLong()+LEASE_MS; return c.snapshot(); }
    }
    public Events events(String id,long after) {
        if(after<0) throw new ApiException(400,"MQTT_INVALID_CURSOR","游标不能为负数");
        Context c=require(id);
        synchronized(c) {
            boolean gap=after>c.cursor || (!c.events.isEmpty() && after<c.events.getFirst().cursor()-1);
            return new Events(c.events.stream().filter(e->gap || e.cursor()>after).toList(),c.cursor,gap);
        }
    }
    public void close(String id) {
        Context c;
        synchronized(this) { c=sessions.get(id); if(c==null) return; }
        synchronized(c) { c.state="STOPPED"; }
        c.worker.shutdownNow();
        if(c.client!=null) c.client.close();
        synchronized(this) { sessions.remove(id,c); }
        LoggerFactory.getLogger(getClass()).info("MQTT session stopped session={}",id);
    }
    void expire() {
        List<Context> candidates;
        synchronized(this) { candidates=List.copyOf(sessions.values()); }
        for(Context c:candidates) {
            synchronized(c) {
                // 再次检查，避免扫描后刚好收到心跳却仍关闭该连接。
                if(c.expires>=clock.getAsLong()) continue;
                c.state="STOPPED";
            }
            close(c.id);
        }
    }
    @PreDestroy public void shutdown() {
        List<String> ids; synchronized(this) { destroyed=true; ids=List.copyOf(sessions.keySet()); }
        reaper.shutdownNow(); ids.forEach(this::close);
    }
}
