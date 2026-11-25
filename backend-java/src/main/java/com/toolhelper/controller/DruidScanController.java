package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.service.DruidScanService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.toolhelper.druid.DruidModels.*;

@RestController
@RequestMapping("/api/v1/druid")
public class DruidScanController {
    private final DruidScanService service;
    public DruidScanController(DruidScanService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) { return response(request, service.capabilities()); }
    @GetMapping("/scans") public Object history(HttpServletRequest request) { return response(request, service.history()); }
    @GetMapping("/scans/active") public Object active(HttpServletRequest request) { return response(request, service.active()); }
    @PostMapping("/scans") public Object submit(@RequestBody ScanRequest body, HttpServletRequest request) { return ResponseEntity.accepted().body(response(request, service.submit(body))); }
    @PostMapping("/credential-checks") public Object credentials(@RequestBody CredentialRequest body, HttpServletRequest request) { return ResponseEntity.accepted().body(response(request, service.credentials(body))); }
    @GetMapping("/scans/{id}") public Object task(@PathVariable String id, HttpServletRequest request) { return response(request, service.task(id)); }
    @GetMapping("/scans/{id}/results") public Object results(@PathVariable String id, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="50") int size, HttpServletRequest request) { return response(request, service.results(id, page, size)); }
    @GetMapping("/scans/{id}/results/{targetId}") public Object result(@PathVariable String id, @PathVariable String targetId, HttpServletRequest request) { return response(request, service.result(id, targetId)); }
    @DeleteMapping("/scans/{id}") public Object cancel(@PathVariable String id, HttpServletRequest request) { return response(request, service.cancel(id)); }
    @DeleteMapping("/results") public Object clear(HttpServletRequest request) { return response(request, service.clear()); }
    @PostMapping("/export") public ResponseEntity<byte[]> export(@RequestBody ExportRequest body) {
        byte[] bytes = service.export(body);
        String type = body.format().equals("xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header("Content-Disposition", "attachment; filename=ToolHelper-druid." + body.format()).body(bytes);
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        var result = new java.util.LinkedHashMap<String, Object>(); result.put("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID)); result.put("data", data); return result;
    }
}
