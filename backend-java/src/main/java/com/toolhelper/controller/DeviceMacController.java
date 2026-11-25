package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.service.DeviceMacService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.toolhelper.devicemac.DeviceMacModels.*;

@RestController
@RequestMapping("/api/v1/device-mac")
public class DeviceMacController {
    private final DeviceMacService service;
    public DeviceMacController(DeviceMacService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) { return response(request, service.capabilities()); }
    @GetMapping("/ledger") public Object ledger(HttpServletRequest request) { return response(request, service.ledger()); }
    @PostMapping("/ledger/save") public Object save(@RequestBody SaveRequest body, HttpServletRequest request) { return response(request, service.save(body)); }
    @PostMapping("/queries") public Object submit(@RequestBody QueryRequest body, HttpServletRequest request) { return ResponseEntity.accepted().body(response(request, service.submit(body))); }
    @GetMapping("/queries/active") public Object active(HttpServletRequest request) { return response(request, service.active()); }
    @GetMapping("/queries/{id}") public Object task(@PathVariable String id, HttpServletRequest request) { return response(request, service.task(id)); }
    @DeleteMapping("/queries/{id}") public Object cancel(@PathVariable String id, HttpServletRequest request) { return response(request, service.cancel(id)); }
    @PostMapping("/export") public ResponseEntity<byte[]> export(@RequestBody ExportRequest body) {
        byte[] bytes = service.export(body);
        String type = body.format().equals("xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("Content-Disposition", "attachment; filename=toolhelper-device-mac." + body.format()).body(bytes);
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID)); result.put("data", data); return result;
    }
}
