package com.toolhelper.mqttmodbus;

import com.toolhelper.mqttmodbus.MqttModbusModels.Gateway;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Paho 回调只把帧交给会话队列，等待发布完成在会话工作线程进行。 */
public class MqttClientAdapter implements AutoCloseable {
    private final MqttAsyncClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    public MqttClientAdapter(Gateway gateway, BiConsumer<byte[], Boolean> receive, Consumer<Throwable> lost) throws MqttException {
        client = new MqttAsyncClient(gateway.brokerUrl(), gateway.clientId(), new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) { lost.accept(cause); }
            public void deliveryComplete(IMqttDeliveryToken token) {}
            public void messageArrived(String topic, MqttMessage message) {
                receive.accept(message.getPayload(), message.isRetained());
            }
        });
    }
    public synchronized void connect(Gateway g, String username, String password) throws MqttException {
        if (closed.get()) throw new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true); options.setAutomaticReconnect(false);
        options.setConnectionTimeout(5); options.setKeepAliveInterval(20); options.setMaxInflight(10);
        char[] secret = password == null ? null : password.toCharArray();
        try {
            if (username != null && !username.isBlank()) options.setUserName(username);
            if (secret != null && secret.length > 0) options.setPassword(secret);
            client.connect(options).waitForCompletion(6000);
            IMqttToken subscription = client.subscribe(g.downTopic(),g.qos());
            subscription.waitForCompletion(5000);
            if (subscription.getGrantedQos().length != 1 || subscription.getGrantedQos()[0] == 128)
                throw new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED);
        } finally {
            // Paho 1.2.5 的 setter 不接受 null，清空其内部副本后替换为空数组。
            if (options.getPassword() != null) Arrays.fill(options.getPassword(),'\0');
            options.setPassword(new char[0]);
            if (secret != null) Arrays.fill(secret,'\0');
        }
    }
    public void publish(Gateway g, byte[] bytes) throws MqttException {
        client.publish(g.upTopic(),bytes,g.qos(),false).waitForCompletion(5000);
    }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false,true)) return;
        // connect 的有限等待结束后再关闭；即使令牌超时仍可能处于 CONNECTING，
        // 强制断开覆盖这一状态，不能仅凭 isConnected 决定是否清理网络资源。
        try { client.disconnectForcibly(0,1000,false); }
        catch (MqttException e) { org.slf4j.LoggerFactory.getLogger(MqttClientAdapter.class).warn("MQTT disconnect failed, reason={}",e.getReasonCode()); }
        try { client.close(true); }
        catch (MqttException e) { org.slf4j.LoggerFactory.getLogger(MqttClientAdapter.class).warn("MQTT client close failed, reason={}",e.getReasonCode()); }
    }
}
