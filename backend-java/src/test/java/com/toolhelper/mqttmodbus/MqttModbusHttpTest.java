package com.toolhelper.mqttmodbus;

import com.toolhelper.ToolHelperApplication;
import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.mqttmodbus.EarlyModbusCodecTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MqttModbusHttpTest {
    @TempDir Path root;
    @Test void protectedApiValidatesJsonBeforeNetworkAndUsesStandardEnvelope() throws Exception {
        var app=new SpringApplication(ToolHelperApplication.class);
        try(var context=app.run("--server.port=0","--toolhelper.data-root="+root,"--spring.main.banner-mode=off");var client=HttpClient.newHttpClient()) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort();
            var json=new JsonMapper();
            assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/mqtt-modbus/sessions")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var bootstrap=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/session")).header("X-ToolHelper-Client","web").GET().build(),HttpResponse.BodyHandlers.ofString());
            String token=json.readTree(bootstrap.body()).path("data").path("token").asText();
            var caps=send(client,base,token,"/capabilities","GET",null);
            assertEquals(200,caps.statusCode()); assertEquals(8,json.readTree(caps.body()).path("data").path("maxSessions").asInt());
            assertFalse(json.readTree(caps.body()).path("requestId").asText().isBlank());
            var gateway=new Gateway("g","g","g","tcp://127.0.0.1:1",null,0,null,null);
            String body=json.writeValueAsString(new OpenRequest(gateway,List.of(device("g","d",1,120)),null,null));
            assertEquals(400,send(client,base,token,"/sessions","POST",body.replace("\"address\":1","\"address\":1.5")).statusCode());
            assertEquals(400,send(client,base,token,"/sessions","POST",body.replace("\"address\":1","\"address\":null")).statusCode());
            assertEquals(400,send(client,base,token,"/sessions","POST",body.replaceFirst("\\{","{\"unknown\":true,")).statusCode());
            assertEquals(415,client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/mqtt-modbus/sessions")).header("X-ToolHelper-Session",token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(413,send(client,base,token,"/sessions","POST"," ".repeat(512*1024+1)).statusCode());
            assertEquals(404,send(client,base,token,"/sessions/missing","GET",null).statusCode());
            assertEquals(200,send(client,base,token,"/sessions/missing","DELETE",null).statusCode());
            // 可选隔离 Broker 使同一契约测试覆盖 HTTP -> MQTT 会话 -> 原子配置 -> 停止。
            String broker=System.getProperty("mqtt.test.broker");
            if(broker!=null) {
                body=body.replace("tcp://127.0.0.1:1",broker);
                var opened=send(client,base,token,"/sessions","POST",body); assertEquals(200,opened.statusCode(),opened.body());
                String id=json.readTree(opened.body()).path("data").path("sessionId").asText();
                var changed=new ProfileRequest(1L,List.of(device("g","d",1,777)));
                assertEquals(200,send(client,base,token,"/sessions/"+id+"/profile","POST",json.writeValueAsString(changed)).statusCode());
                assertEquals(409,send(client,base,token,"/sessions/"+id+"/profile","POST",json.writeValueAsString(changed)).statusCode());
                assertEquals(200,send(client,base,token,"/sessions/"+id+"/heartbeat","POST","{}").statusCode());
                assertEquals(200,send(client,base,token,"/sessions/"+id+"/events?after=0","GET",null).statusCode());
                assertEquals(200,send(client,base,token,"/sessions/"+id,"DELETE",null).statusCode());
            }
        } finally {
            // Spring 的全局日志 appender 比 Context 寿命长；先释放测试目录文件句柄。
            Logger logger=(Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            var appenders=logger.iteratorForAppenders();
            while(appenders.hasNext()) {
                var appender=appenders.next();
                if(appender instanceof FileAppender<?> file && file.getFile()!=null && Path.of(file.getFile()).startsWith(root)) {
                    appender.stop(); logger.detachAppender(appender);
                }
            }
        }
    }
    private static HttpResponse<String> send(HttpClient client,String base,String token,String path,String method,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/mqtt-modbus"+path)).header("X-ToolHelper-Session",token);
        if(body!=null) request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
