package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.crypto.aes.AesModels.CryptoRequest;
import com.toolhelper.crypto.aes.AesModels.RandomRequest;
import com.toolhelper.crypto.aes.AesService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/aes")
public final class AesController {
    private final AesService service;
    public AesController(AesService service) { this.service = service; }
    @GetMapping("/capabilities") public Object capabilities(HttpServletRequest request) { return response(request, service.capabilities()); }
    @PostMapping("/encrypt") public Object encrypt(@RequestBody CryptoRequest body, HttpServletRequest request) { return response(request, service.encrypt(body)); }
    @PostMapping("/decrypt") public Object decrypt(@RequestBody CryptoRequest body, HttpServletRequest request) { return response(request, service.decrypt(body)); }
    @PostMapping("/random") public Object random(@RequestBody RandomRequest body, HttpServletRequest request) { return response(request, service.random(body)); }
    private static Object response(HttpServletRequest request, Object data) {
        return Map.of("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID), "data", data);
    }
}
