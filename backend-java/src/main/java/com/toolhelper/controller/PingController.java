package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.ping.PingModels.*;
import com.toolhelper.service.PingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ping")
public class PingController {
    private final PingService service;
    public PingController(PingService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) { return response(request, service.capabilities()); }
    @PostMapping("/targets/parse") public Object parse(@RequestBody ParseRequest body, HttpServletRequest request) { return response(request, service.parse(body)); }
    @PostMapping("/tasks") public ResponseEntity<?> submit(@RequestBody TaskRequest body, HttpServletRequest request) {
        return ResponseEntity.accepted().body(response(request, service.submit(body)));
    }
    @GetMapping("/tasks/active") public Object active(HttpServletRequest request) { return response(request, service.active()); }
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id, HttpServletRequest request) { return response(request, service.task(id)); }
    @DeleteMapping("/tasks/{id}") public Object cancel(@PathVariable String id, HttpServletRequest request) { return response(request, service.cancel(id)); }
    @PostMapping("/export") public ResponseEntity<byte[]> export(@RequestBody ExportRequest body) {
        byte[] bytes = service.export(body);
        boolean xlsx = body.format().equals("xlsx");
        MediaType type = xlsx ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") : MediaType.parseMediaType("text/csv;charset=UTF-8");
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("ToolHelper-ping." + body.format()).build().toString()).body(bytes);
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID)); result.put("data", data); return result;
    }
}
