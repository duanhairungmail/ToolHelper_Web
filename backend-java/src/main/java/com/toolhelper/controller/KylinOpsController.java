package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.service.KylinOpsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.toolhelper.kylin.KylinModels.*;

@RestController
@RequestMapping("/api/v1/kylin")
public class KylinOpsController {
    private final KylinOpsService service;
    public KylinOpsController(KylinOpsService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest r) { return response(r, service.capabilities()); }
    @PostMapping("/host-key-probes") public Object probe(@RequestBody Host body, HttpServletRequest r) { return response(r, service.probe(body)); }
    @PostMapping("/host-key-trust") public Object trust(@RequestBody TrustRequest body, HttpServletRequest r) { return response(r, service.trust(body)); }
    @PostMapping("/connections") public Object connect(@RequestBody ConnectRequest body, HttpServletRequest r) { return response(r, service.connect(body)); }
    @GetMapping("/connections/current") public Object connection(HttpServletRequest r) { return response(r, service.connection()); }
    @DeleteMapping("/connections/{id}") public Object disconnect(@PathVariable String id, HttpServletRequest r) { service.disconnect(id); return response(r, true); }
    @GetMapping("/connections/{id}/backups") public Object backups(@PathVariable String id, HttpServletRequest r) { return response(r, service.backups(id)); }
    @PostMapping("/scans") public Object scan(@RequestBody ScanRequest body, HttpServletRequest r) { return response(r, service.scan(body)); }
    @PostMapping("/plans") public Object plan(@RequestBody PlanRequest body, HttpServletRequest r) { return response(r, service.plan(body)); }
    @PostMapping("/executions") public Object execute(@RequestBody ExecuteRequest body, HttpServletRequest r) { return ResponseEntity.accepted().body(response(r, service.execute(body))); }
    @GetMapping("/tasks/active") public Object active(HttpServletRequest r) { return response(r, service.active()); }
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id, HttpServletRequest r) { return response(r, service.task(id)); }
    @DeleteMapping("/tasks/{id}") public Object cancel(@PathVariable String id, HttpServletRequest r) { return response(r, service.cancel(id)); }
    private static Map<String, Object> response(HttpServletRequest r, Object data) {
        var value = new LinkedHashMap<String, Object>(); value.put("requestId", r.getAttribute(LocalRequestFilter.REQUEST_ID)); value.put("data", data); return value;
    }
}
