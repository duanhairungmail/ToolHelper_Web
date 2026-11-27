package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.sql.SqlModels.*;
import com.toolhelper.sql.SqlService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sql")
public class SqlController {
    private final SqlService service;
    public SqlController(SqlService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) { return response(request, service.capabilities()); }
    @PostMapping("/generate") public CompletableFuture<Object> generate(@RequestBody GenerateRequest body, HttpServletRequest request) {
        return service.generate(body).thenApply(data -> response(request, data));
    }
    @PostMapping("/format") public CompletableFuture<Object> format(@RequestBody FormatRequest body, HttpServletRequest request) {
        return service.format(body).thenApply(data -> response(request, data));
    }
    private static Object response(HttpServletRequest request, Object data) {
        return Map.of("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID), "data", data);
    }
}
