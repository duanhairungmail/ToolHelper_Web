package com.toolhelper.controller;

import com.toolhelper.config.LocalRequestFilter;
import com.toolhelper.support.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> known(ApiException error, HttpServletRequest request) {
        LOG.warn("请求拒绝 requestId={} code={}", request.getAttribute(LocalRequestFilter.REQUEST_ID), error.code());
        return error(request, error.status(), error.code(), error.getMessage(), error.retryable());
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<?> invalid(Exception failure, HttpServletRequest request) {
        return error(request, 400, "INVALID_REQUEST", "请求体、字段类型或必需请求头不正确", false);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception failure, HttpServletRequest request) {
        LOG.error("本机请求失败 requestId={}", request.getAttribute(LocalRequestFilter.REQUEST_ID), failure);
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return error(request, 500, "INTERNAL_ERROR", "本机操作失败，请使用请求标识检查服务日志", false);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> missing(Exception failure, HttpServletRequest request) {
        return error(request, 404, "RESOURCE_NOT_FOUND", "接口或资源不存在", false);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(Exception failure, HttpServletRequest request) {
        return error(request, 405, "METHOD_NOT_ALLOWED", "不支持该请求方法", false);
    }
    private static ResponseEntity<?> error(HttpServletRequest request, int status, String code, String message, boolean retryable) {
        Object id = request.getAttribute(LocalRequestFilter.REQUEST_ID);
        return ResponseEntity.status(status).body(Map.of("requestId", id == null ? "unavailable" : id,
                "error", Map.of("code", code, "message", message, "retryable", retryable)));
    }
}
