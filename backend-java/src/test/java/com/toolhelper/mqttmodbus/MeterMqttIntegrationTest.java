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
import static com.toolhelper.mqttmodbus.MeterModbusCodecTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅对显式隔离 Broker 执行：测试使用电表固定主题，不能指向生产 Broker。 */
@EnabledIfSystemProperty(named="mqtt.test.broker",matches=".+")
class MeterMqttIntegrationTest {
    private final String broker=System.getProperty("mqtt.test.broker");
    static OpenRequest request(String broker,String key,List<Meter> meters) {
        return new OpenRequest(new Gateway(key,key,key,broker,null,0,MeterProfile.DOWN,MeterProfile.UP),List.of(),null,null,"meter",meters);
    }
    private MqttClient observer(BlockingQueue<byte[]> packets) throws Exception {
        var client=new MqttClient(broker,"meter-probe-"+UUID.randomUUID(),new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {}
            public void deliveryComplete(IMqttDeliveryToken token) {}
            public void messageArrived(String topic,MqttMessage m) { packets.offer(m.getPayload()); }
        });
        client.connect(); client.subscribe(MeterProfile.UP,0); return client;
    }
    @Test void sharedTopicSeparateGatewaysRouteOnceAndApplyAtomically() throws Exception {
        var packets=new LinkedBlockingQueue<byte[]>(); var service=new MqttModbusService();
        try(var client=observer(packets)) {
            var dc=service.open(request(broker,"dc",List.of(meter("dc","dc"))));
            var ac=service.open(request(broker,"ac",List.of(meter("ac","ac"))));
            client.publish(MeterProfile.DOWN,hex("01 03 00 00 00 11 85 C6"),0,false);
            assertArrayEquals(hex("01 03 22 0D AC 00 27 00 10 00 88 00 B8 01 E8 00 48 00 27 00 10 00 88 00 B8 00 C3 00 50 00 75 00 30 00 01 00 00 F7 AC"),packets.poll(5,TimeUnit.SECONDS));
            assertNull(packets.poll(200,TimeUnit.MILLISECONDS));
            org.awaitility.Awaitility.await().atMost(3,TimeUnit.SECONDS).until(()->service.state(ac.sessionId()).filtered()==1);
            assertEquals(0,service.state(ac.sessionId()).published());
            assertThrows(ApiException.class,()->service.open(request(broker,"duplicate",List.of(meter("duplicate","dc")))));
            var collision=new Gateway("early","early","early",broker,null,0,MeterProfile.DOWN,MeterProfile.UP);
            assertThrows(ApiException.class,()->service.open(new OpenRequest(collision,List.of(),null,null)));
            var changed=change(ac.meters().getFirst(),"power","-1.0450");
            var updated=service.profile(ac.sessionId(),new ProfileRequest(1L,null,List.of(changed)));
            assertEquals(2,updated.version());
            assertThrows(ApiException.class,()->service.profile(ac.sessionId(),new ProfileRequest(1L,null,List.of(changed))));
            assertThrows(ApiException.class,()->service.profile(ac.sessionId(),new ProfileRequest(2L,null,List.of(change(changed,"voltage","1.11")))));
            assertEquals(2,service.state(ac.sessionId()).version());
            client.publish(MeterProfile.DOWN,query(2,3,9,2),0,false);
            assertArrayEquals(EarlyModbusCodec.frame(hex("02 03 04 FF D7 00 2E")),packets.poll(5,TimeUnit.SECONDS));
            assertNull(packets.poll(200,TimeUnit.MILLISECONDS));
            assertTrue(service.events(ac.sessionId(),0).events().stream().anyMatch(e->e.kind().equals("发布") && e.version()==2));
            service.close(dc.sessionId());
            client.publish(MeterProfile.DOWN,query(1,3,0,17),0,false);
            assertNull(packets.poll(200,TimeUnit.MILLISECONDS));
            client.publish(MeterProfile.DOWN,query(2,3,0,14),0,false);
            assertEquals(33,Objects.requireNonNull(packets.poll(5,TimeUnit.SECONDS)).length);
            client.disconnect();
        } finally { service.shutdown(); }
    }
    @Test void singleGatewayBothMetersRejectsTopologyChangesAndExpires() throws Exception {
        var clock=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        var service=new MqttModbusService(clock::get); var packets=new LinkedBlockingQueue<byte[]>();
        try(var client=observer(packets)) {
            var s=service.open(request(broker,"both",List.of(meter("both","dc"),meter("both","ac"))));
            assertEquals(2,s.meters().size());
            client.publish(MeterProfile.DOWN,query(1,3,0,17),0,false);
            assertEquals(39,Objects.requireNonNull(packets.poll(5,TimeUnit.SECONDS)).length);
            client.publish(MeterProfile.DOWN,query(2,3,0,14),0,false);
            assertEquals(33,Objects.requireNonNull(packets.poll(5,TimeUnit.SECONDS)).length);
            assertThrows(ApiException.class,()->service.profile(s.sessionId(),new ProfileRequest(1L,null,List.of(meter("both","dc")))));
            clock.addAndGet(61000); service.expire(); assertTrue(service.list().isEmpty());
            client.publish(MeterProfile.DOWN,query(1,3,0,17),0,false);
            assertNull(packets.poll(200,TimeUnit.MILLISECONDS));
            client.disconnect();
        } finally { service.shutdown(); }
    }
    @Test void concurrentSameAddressCreationHasOneWinner() throws Exception {
        var service=new MqttModbusService();
        try {
            var a=CompletableFuture.supplyAsync(()->openOrReject(service,"race-a"));
            var b=CompletableFuture.supplyAsync(()->openOrReject(service,"race-b"));
            assertEquals(1,(a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0));
            assertEquals(1,service.list().size());
        } finally { service.shutdown(); }
    }
    private boolean openOrReject(MqttModbusService service,String key) {
        try { service.open(request(broker,key,List.of(meter(key,"dc")))); return true; }
        catch(ApiException e) { assertEquals("MQTT_SESSION_CONFLICT",e.code()); return false; }
    }
}
