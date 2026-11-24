package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.plugin.dbx.DbxModels.ConnectionRequest;
import com.toolhelper.plugin.electerm.PluginModels.MutationRequest;
import com.toolhelper.service.DatabasePluginService;
import com.toolhelper.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/plugins/dbx")
public class DatabasePluginController {
    private final DatabasePluginService service;
    public DatabasePluginController(DatabasePluginService service) { this.service = service; }

    @GetMapping public Map<String, Object> status(HttpServletRequest request) throws Exception { return response(request, service.status()); }
    @GetMapping("/update-check") public Map<String, Object> check(HttpServletRequest request) throws Exception { return response(request, service.checkUpdate()); }
    @PostMapping("/{operation:install|update|uninstall}")
    public ResponseEntity<Map<String, Object>> mutate(@PathVariable String operation, @RequestBody MutationRequest body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws Exception {
        String action = switch (operation) { case "install" -> "INSTALL"; case "update" -> "UPDATE"; default -> "UNINSTALL"; };
        var result = service.mutate(action, body, key);
        return ResponseEntity.status(result.containsKey("taskId") ? 202 : 200).body(response(request, result));
    }
    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody ConnectionRequest body, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) throws Exception { return response(request, service.connect(body, key)); }
    @PostMapping("/{operation:launch|open-directory}")
    public Map<String, Object> local(@PathVariable String operation, @RequestBody Map<String, Object> body,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws Exception {
        if (!body.isEmpty()) throw new ApiException(400, "UNEXPECTED_PARAMETERS", "该操作不接受路径或额外参数");
        return response(request, service.localAction(operation.equals("launch") ? "LAUNCH" : "OPEN_DIRECTORY", null, key));
    }
    private static Map<String, Object> response(HttpServletRequest request, Object data) {
        return Map.of("requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID), "data", data);
    }
}
