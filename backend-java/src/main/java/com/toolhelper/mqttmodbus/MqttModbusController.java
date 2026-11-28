package com.toolhelper.mqttmodbus;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.mqttmodbus.MqttModbusModels.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mqtt-modbus")
public class MqttModbusController {
    private final MqttModbusService service;
    public MqttModbusController(MqttModbusService service) { this.service=service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest r) { return response(r,Map.of("protocol","MQTT 3.1.1 / RTU binary", "transports",List.of("tcp","mqtt"),"functions",List.of(1,3,4),"maxSessions",8,"maxDevicesPerGateway",247,"leaseMs",60000,"maxEvents",200,"maxFrameBytes",256,
        "meter",Map.of("types",List.of("dc","ac"),"encodingProfile",MeterProfile.ENCODING,"downTopic",MeterProfile.DOWN,"upTopic",MeterProfile.UP,"functions",List.of(3),"addresses",Map.of("dc",1,"ac",2),"readRanges",Map.of("dc",List.of(0,16),"ac",List.of(0,13)),"maxPerGateway",2))); }
    @GetMapping("/sessions") public Object list(HttpServletRequest r) { return response(r,service.list()); }
    @PostMapping("/sessions") public Object open(@RequestBody OpenRequest body,HttpServletRequest r) { return response(r,service.open(body)); }
    @GetMapping("/sessions/{id}") public Object state(@PathVariable String id,HttpServletRequest r) { return response(r,service.state(id)); }
    @GetMapping("/sessions/{id}/events") public Object events(@PathVariable String id,@RequestParam(defaultValue="0") long after,HttpServletRequest r) { return response(r,service.events(id,after)); }
    @PostMapping("/sessions/{id}/profile") public Object profile(@PathVariable String id,@RequestBody ProfileRequest body,HttpServletRequest r) { return response(r,service.profile(id,body)); }
    @PostMapping("/sessions/{id}/heartbeat") public Object heartbeat(@PathVariable String id,HttpServletRequest r) { return response(r,service.heartbeat(id)); }
    @DeleteMapping("/sessions/{id}") public Object close(@PathVariable String id,HttpServletRequest r) { service.close(id); return response(r,Map.of("closed",true)); }
    private static Object response(HttpServletRequest r,Object data) { return Map.of("requestId",r.getAttribute(LocalRequestFilter.REQUEST_ID),"data",data); }
}
