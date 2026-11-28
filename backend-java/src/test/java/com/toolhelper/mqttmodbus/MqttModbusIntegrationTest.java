package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import com.toolhelper.support.ApiException;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static com.toolhelper.mqttmodbus.EarlyModbusCodecTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式指定隔离 Broker：mvn -Dmqtt.test.broker=tcp://127.0.0.1:21883 ... test。 */
@EnabledIfSystemProperty(named="mqtt.test.broker",matches=".+")
class MqttModbusIntegrationTest {
    record Packet(String topic,byte[] payload,boolean retained) {}
    private final String broker=System.getProperty("mqtt.test.broker");
    private final String prefix="toolhelper-test/"+UUID.randomUUID();
    private OpenRequest request(String key,String client,List<Device> devices) {
        return new OpenRequest(new Gateway(key,key,key,broker,client,1,prefix+"/"+key+"/down",prefix+"/"+key+"/up"),devices,null,null);
    }
    @Test void realBrokerRoutesMultipleGatewaysAndAtomicUpdatesAndStopsIndependently() throws Exception {
        var packets=new LinkedBlockingQueue<Packet>();
        try (var observer=new MqttClient(broker,"observer-"+UUID.randomUUID(),new MemoryPersistence())) {
            observer.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) {}
                public void deliveryComplete(IMqttDeliveryToken token) {}
                public void messageArrived(String topic,MqttMessage m) { packets.offer(new Packet(topic,m.getPayload(),m.isRetained())); }
            });
            observer.connect(); observer.subscribe(prefix+"/+/up",1);
            var service=new MqttModbusService();
            try {
                var a=service.open(request("a","sim-a-"+UUID.randomUUID(),List.of(device("a","a1",1,120),device("a","a2",2,500))));
                var b=service.open(request("b","sim-b-"+UUID.randomUUID(),List.of(device("b","b1",1,880))));
                assertEquals(2,service.list().size());
                assertThrows(ApiException.class,()->service.open(request("a","other",List.of())));
                observer.publish(a.gateway().downTopic(),query(2,4,1,1),1,false);
                Packet p=packets.poll(5,TimeUnit.SECONDS); assertNotNull(p); assertEquals(a.gateway().upTopic(),p.topic());
                assertArrayEquals(EarlyModbusCodec.frame(hex("02 04 02 01 F4")),p.payload()); assertFalse(p.retained());
                observer.publish(b.gateway().downTopic(),query(1,4,1,1),1,false);
                p=packets.poll(5,TimeUnit.SECONDS); assertNotNull(p); assertEquals(b.gateway().upTopic(),p.topic());
                assertArrayEquals(hex("01 04 02 03 70 B8 24"),p.payload());
                var updated=service.profile(a.sessionId(),new ProfileRequest(1L,List.of(device("a","a1",1,777),device("a","a2",2,500))));
                assertEquals(2,updated.version());
                assertThrows(ApiException.class,()->service.profile(a.sessionId(),new ProfileRequest(1L,updated.devices())));
                assertThrows(ApiException.class,()->service.profile(a.sessionId(),new ProfileRequest(2L,List.of(device("a","a1",3,888),device("a","a2",2,500)))));
                observer.publish(a.gateway().downTopic(),query(1,4,1,1),1,false);
                p=packets.poll(5,TimeUnit.SECONDS); assertNotNull(p); assertArrayEquals(EarlyModbusCodec.frame(hex("01 04 02 03 09")),p.payload());
                observer.publish(a.gateway().downTopic(),hex("01 04 00 01 00 01 00 00"),1,false);
                assertNull(packets.poll(200,TimeUnit.MILLISECONDS));
                var page=service.events(a.sessionId(),0);
                assertTrue(page.events().stream().anyMatch(e->e.result().equals("INVALID_CRC")));
                service.close(a.sessionId()); assertEquals(1,service.list().size());
                observer.publish(a.gateway().downTopic(),query(1,4,1,1),1,false);
                assertNull(packets.poll(200,TimeUnit.MILLISECONDS));
                observer.publish(b.gateway().downTopic(),query(1,3,1,39),1,false);
                assertNotNull(packets.poll(5,TimeUnit.SECONDS));
                assertEquals("RUNNING",service.state(b.sessionId()).state());
                assertTrue(service.heartbeat(b.sessionId()).leaseExpiresAt()>System.currentTimeMillis());
            } finally { service.shutdown(); observer.disconnect(); }
        }
    }
    @Test void retainedReplayIsDroppedAndLeaseExpiryReleasesGateway() throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var service=new MqttModbusService(clock::get);
        String topic=prefix+"/retained/down";
        try(var publisher=new MqttClient(broker,"retain-"+UUID.randomUUID(),new MemoryPersistence())) {
            publisher.connect();
            // MQTT 3.1.1 仅在订阅重放时携带 RETAIN=1；实时转发的 retain 标记为 0。
            publisher.publish(topic,query(1,4,1,1),1,true);
            var s=service.open(request("retained","sim-retain-"+UUID.randomUUID(),List.of(device("retained","r1",1,120))));
            org.awaitility.Awaitility.await().atMost(3,TimeUnit.SECONDS).until(()->service.state(s.sessionId()).dropped()>0);
            assertEquals(0,service.state(s.sessionId()).published());
            assertTrue(service.events(s.sessionId(),0).events().stream().anyMatch(e->e.result().equals("RETAINED_QUERY")));
            clock.addAndGet(50000); service.heartbeat(s.sessionId()); clock.addAndGet(20000); service.expire();
            assertEquals(1,service.list().size());
            clock.addAndGet(41000); service.expire(); assertTrue(service.list().isEmpty());
            publisher.publish(topic,new byte[0],1,true);
            publisher.disconnect();
        } finally { service.shutdown(); }
    }
    @Test void conflictingSubscriptionsCredentialsAndFailedConnectionsLeaveNoClient() {
        var service=new MqttModbusService();
        try {
            var a=service.open(request("a","sim-collision",List.of(device("a","a1",1,100))));
            var collision=new Gateway("b","b","b",broker,a.gateway().clientId(),1,"other/down","other/up");
            assertThrows(ApiException.class,()->service.open(new OpenRequest(collision,List.of(),null,null)));
            Gateway finalCollision=new Gateway("b","b","b",broker,"unique",1,a.gateway().downTopic(),"other/up");
            assertThrows(ApiException.class,()->service.open(new OpenRequest(finalCollision,List.of(),null,null)));
            var bad=new Gateway("bad","bad","bad","tcp://127.0.0.1:1","failure",0,null,null);
            assertThrows(ApiException.class,()->service.open(new OpenRequest(bad,List.of(),"test-user","do-not-leak")));
            assertEquals(1,service.list().size());
            assertFalse(service.list().toString().contains("do-not-leak"));
        } finally { service.shutdown(); }
    }
    @Test void disconnectIsVisibleAndEventsHaveBoundedCursorWindow() throws Exception {
        var service=new MqttModbusService();
        try(var publisher=new MqttClient(broker,"flood-"+UUID.randomUUID(),new MemoryPersistence())) {
            var s=service.open(request("events","sim-events-"+UUID.randomUUID(),List.of(device("events","e1",1,120))));
            publisher.connect();
            for(int i=0;i<250;i++) publisher.publish(s.gateway().downTopic(),hex("01 04 00 01 00 01 00 00"),0,false);
            org.awaitility.Awaitility.await().atMost(5,TimeUnit.SECONDS).until(()->service.state(s.sessionId()).received()==250 && service.state(s.sessionId()).dropped()==250);
            var page=service.events(s.sessionId(),0); assertTrue(page.gap()); assertEquals(200,page.events().size());
            assertTrue(service.events(s.sessionId(),page.cursor()).events().isEmpty());
            assertTrue(service.events(s.sessionId(),Long.MAX_VALUE).gap());
            try(var takeover=new MqttClient(broker,s.gateway().clientId(),new MemoryPersistence())) {
                takeover.connect();
                org.awaitility.Awaitility.await().atMost(5,TimeUnit.SECONDS).until(()->service.state(s.sessionId()).state().equals("FAILED"));
                assertEquals("MQTT_CONNECTION_LOST",service.state(s.sessionId()).error());
                takeover.disconnect();
            }
            publisher.disconnect();
        } finally { service.shutdown(); }
    }
    @Test void stoppingWhileBrokerHandshakeIsPendingDoesNotLeaveClientBehind() throws Exception {
        try(var listener=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            var service=new MqttModbusService();
            var request=new OpenRequest(new Gateway("pending","pending","pending","tcp://127.0.0.1:"+listener.getLocalPort(),"pending-test",0,null,null),List.of(),null,null);
            var open=CompletableFuture.runAsync(()->assertThrows(ApiException.class,()->service.open(request)));
            try(var socket=listener.accept()) {
                org.awaitility.Awaitility.await().atMost(2,TimeUnit.SECONDS).until(()->!service.list().isEmpty());
                service.close(service.list().getFirst().sessionId());
                open.get(10,TimeUnit.SECONDS);
                assertTrue(service.list().isEmpty());
                socket.setSoTimeout(2000);
                // 丢弃已发送的 CONNECT 数据，最终必须观察到 EOF。
                while(socket.getInputStream().read()!=-1) { }
            } finally { service.shutdown(); }
        }
    }
}
