package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.service.ApiValidationService;
import com.toolhelper.validation.LegacyApiCatalog;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.toolhelper.validation.ValidationModels.*;

@RestController
@RequestMapping("/api/v1/api-validation")
public class ApiValidationController {
    private final ApiValidationService service;
    public ApiValidationController(ApiValidationService service) { this.service = service; }
    @GetMapping("/session") public Object state(HttpServletRequest request) { return response(request, service.state()); }
    @GetMapping("/endpoints") public Object endpoints(HttpServletRequest request) { return response(request, LegacyApiCatalog.ALL); }
    @PostMapping("/login") public Object login(@RequestBody LoginRequest body, HttpServletRequest request) throws InterruptedException { return response(request, service.login(body)); }
    @PostMapping("/logout") public Object logout(@RequestBody ConnectionRequest body, HttpServletRequest request) { return response(request, service.logout(body.connectionId())); }
    @PostMapping("/verify") public Object verify(@RequestBody RunRequest body, HttpServletRequest request) { return ResponseEntity.accepted().body(response(request, service.verify(body))); }
    @PostMapping("/device-ids") public Object devices(@RequestBody ConnectionRequest body, HttpServletRequest request) { return ResponseEntity.accepted().body(response(request, service.query(body.connectionId(), false))); }
    @PostMapping("/mqtt-topics") public Object topics(@RequestBody ConnectionRequest body, HttpServletRequest request) { return ResponseEntity.accepted().body(response(request, service.query(body.connectionId(), true))); }
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id, HttpServletRequest request) { return response(request, service.task(id)); }
    @DeleteMapping("/tasks/{id}") public Object cancel(@PathVariable String id, @RequestParam String connectionId, HttpServletRequest request) { return response(request, service.cancel(id, connectionId)); }
    @PostMapping("/auto-detection") public Object start(@RequestBody ScheduleRequest body, HttpServletRequest request) { return response(request, service.startSchedule(body)); }
    @GetMapping("/auto-detection") public Object schedule(HttpServletRequest request) { return response(request, service.autoState()); }
    @DeleteMapping("/auto-detection") public Object stop(@RequestParam String connectionId, HttpServletRequest request) { return response(request, service.stopAuto(connectionId)); }
    @GetMapping("/cron-preview") public Object preview(@RequestParam String cron, HttpServletRequest request) { return response(request, service.preview(cron)); }
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam String connectionId, @RequestParam String view, @RequestParam String format) {
        byte[] bytes = service.export(connectionId, view, format);
        String type = format.equals("xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("Content-Disposition", "attachment; filename=toolhelper-" + view + "." + format).body(bytes);
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) { return Map.of("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID), "data", data); }
}
