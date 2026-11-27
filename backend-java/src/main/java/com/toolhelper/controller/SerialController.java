package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.serial.SerialModels.*;
import com.toolhelper.serial.SerialService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/serial")
public class SerialController {
    private final SerialService service;
    public SerialController(SerialService service) { this.service = service; }

    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) {
        return response(request, Map.of("platform", "Windows 11 x64", "maxTxBytes", 4096,
                "minAutoSendIntervalMs", 100, "maxAutoSendIntervalMs", 86_400_000,
                "eventPollIntervalMs", 200, "maxEventCount", 2000));
    }
    @GetMapping("/ports") public Object ports(HttpServletRequest request) { return response(request, service.ports()); }
    @GetMapping("/session") public Object session(HttpServletRequest request) { return response(request, service.session()); }
    @PostMapping("/session") public Object open(@RequestBody OpenRequest body, HttpServletRequest request) { return response(request, service.open(body)); }
    @DeleteMapping("/session/{id}") public Object close(@PathVariable String id, HttpServletRequest request) { service.close(id); return response(request, Map.of("closed", true)); }
    @PostMapping("/session/{id}/writes") public Object write(@PathVariable String id, @RequestBody WriteRequest body, HttpServletRequest request) { return response(request, service.write(id, body)); }
    @PostMapping("/session/{id}/auto-send") public Object auto(@PathVariable String id, @RequestBody AutoSendRequest body, HttpServletRequest request) { return response(request, service.startAutoSend(id, body)); }
    @DeleteMapping("/session/{id}/auto-send") public Object stopAuto(@PathVariable String id, HttpServletRequest request) { return response(request, service.stopAutoSend(id)); }
    @GetMapping("/session/{id}/events") public Object events(@PathVariable String id, @RequestParam(defaultValue = "0") long after, HttpServletRequest request) { return response(request, service.events(id, after)); }

    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID)); result.put("data", data); return result;
    }
}
