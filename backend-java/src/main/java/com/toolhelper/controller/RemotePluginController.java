package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.service.RemotePluginService;
import com.toolhelper.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@RestController
@RequestMapping("/api/v1")
public class RemotePluginController {
    private final RemotePluginService service;
    private final LocalRequestFilter security;

    public RemotePluginController(RemotePluginService service, LocalRequestFilter security) {
        this.service = service; this.security = security;
    }

    @GetMapping("/session") public Map<String, Object> session(HttpServletRequest request) {
        return response(request, Map.of("token", security.sessionToken()));
    }
    @GetMapping("/plugins/electerm") public Map<String, Object> status(HttpServletRequest request) throws Exception {
        return response(request, service.status());
    }
    @GetMapping("/plugins/electerm/update-check") public Map<String, Object> check(HttpServletRequest request) throws Exception {
        return response(request, service.checkUpdate());
    }
    @GetMapping("/tasks/{id}") public Map<String, Object> task(@PathVariable String id, HttpServletRequest request) {
        return response(request, service.task(id));
    }
    @PostMapping("/plugins/electerm/{operation:install|update|uninstall}")
    public ResponseEntity<Map<String, Object>> mutate(@PathVariable String operation,
            @RequestBody MutationRequest body, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) throws Exception {
        String action = switch (operation) { case "install" -> "INSTALL"; case "update" -> "UPDATE"; default -> "UNINSTALL"; };
        Map<String, Object> result = service.mutate(action, body, key);
        return ResponseEntity.status(result.containsKey("taskId") ? 202 : 200).body(response(request, result));
    }
    @PostMapping("/plugins/electerm/connect")
    public Map<String, Object> connect(@RequestBody ConnectionRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws Exception {
        return response(request, service.localAction("CONNECT", body, key));
    }
    @PostMapping("/plugins/electerm/{operation:launch|open-directory}")
    public Map<String, Object> local(@PathVariable String operation, @RequestBody Map<String, Object> body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws Exception {
        if (!body.isEmpty()) throw new ApiException(400, "UNEXPECTED_PARAMETERS", "该操作不接受路径或参数");
        return response(request, service.localAction(operation.equals("launch") ? "LAUNCH" : "OPEN_DIRECTORY", null, key));
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        return Map.of("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID), "data", data);
    }
}
