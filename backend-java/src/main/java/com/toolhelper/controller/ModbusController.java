package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.modbus.ModbusModels.*;
import com.toolhelper.modbus.ModbusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/modbus")
public class ModbusController {
    private final ModbusService service;
    public ModbusController(ModbusService service) { this.service = service; }

    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) {
        return response(request, Map.of("platform", "Windows 11 x64", "protocols", List.of("SHEN_HONG", "NAN_RUI_YI_HE"), "pollIntervalMs", 2000, "responseTimeoutMs", 500, "maxReadRetries", 2, "maxDevices", 255));
    }
    @GetMapping("/ports") public Object ports(HttpServletRequest request) {
        var active = service.session();
        return response(request, new SerialPorts(service.ports(), active == null ? "" : "MODBUS:" + active.port()));
    }
    @GetMapping("/session") public Object session(HttpServletRequest request) { return response(request, service.session()); }
    @PostMapping("/session") public Object open(@RequestBody OpenRequest body, HttpServletRequest request) { return response(request, service.open(body)); }
    @DeleteMapping("/session/{id}") public Object close(@PathVariable String id, HttpServletRequest request) { service.close(id); return response(request, Map.of("closed", true)); }
    @GetMapping("/session/{id}/state") public Object state(@PathVariable String id, HttpServletRequest request) {
        return response(request, requireSession(id));
    }
    @PostMapping("/session/{id}/operations") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object operation(@PathVariable String id, @RequestBody OperationRequest body, HttpServletRequest request) {
        return response(request, service.startOperation(id, body));
    }
    @DeleteMapping("/session/{id}/operations/{operationId}") public Object cancel(@PathVariable String id, @PathVariable String operationId, HttpServletRequest request) {
        service.cancelOperation(id, operationId); return response(request, requireSession(id));
    }
    @PostMapping("/session/{id}/polling") public Object startPolling(@PathVariable String id, HttpServletRequest request) { return response(request, service.startPolling(id)); }
    @DeleteMapping("/session/{id}/polling") public Object stopPolling(@PathVariable String id, HttpServletRequest request) { return response(request, service.stopPolling(id)); }
    @GetMapping("/session/{id}/events") public Object events(@PathVariable String id, @RequestParam(defaultValue = "0") long after, HttpServletRequest request) { return response(request, service.events(id, after)); }
    @PostMapping("/session/{id}/heartbeat") public Object heartbeat(@PathVariable String id, HttpServletRequest request) { service.heartbeat(id); return response(request, requireSession(id)); }

    private Session requireSession(String id) {
        Session value = service.session();
        if (value == null || !value.sessionId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Modbus会话已关闭");
        return value;
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID)); result.put("data", data); return result;
    }
}
