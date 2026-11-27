package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.cron.CronModels.*;
import com.toolhelper.service.CronService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cron")
public class CronController {
    private final CronService service;
    public CronController(CronService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) { return response(request, service.capabilities()); }
    @PostMapping("/analyze") public CompletableFuture<Object> analyze(@RequestBody AnalyzeRequest body, HttpServletRequest request) { return service.analyze(body).thenApply(data -> response(request, data)); }
    @PostMapping("/generate") public CompletableFuture<Object> generate(@RequestBody GenerateRequest body, HttpServletRequest request) { return service.generate(body).thenApply(data -> response(request, data)); }
    private static Object response(HttpServletRequest request, Object data) { return Map.of("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID), "data", data); }
}
