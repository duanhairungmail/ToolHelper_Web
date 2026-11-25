package com.toolhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record DeviceMacProperties(int maxRows, int concurrency, int timeoutSeconds, int maxFieldCharacters,
        int maxFileBytes, int maxRequestBytes) {
    public DeviceMacProperties(@Value("${toolhelper.device-mac.max-rows:1000}") int maxRows,
            @Value("${toolhelper.device-mac.concurrency:4}") int concurrency,
            @Value("${toolhelper.device-mac.timeout-seconds:10}") int timeoutSeconds,
            @Value("${toolhelper.device-mac.max-field-characters:256}") int maxFieldCharacters,
            @Value("${toolhelper.device-mac.max-file-bytes:2097152}") int maxFileBytes,
            @Value("${toolhelper.device-mac.max-request-bytes:4194304}") int maxRequestBytes) {
        if (maxRows < 1 || maxRows > 10000 || concurrency < 1 || concurrency > 16 || timeoutSeconds < 1
                || timeoutSeconds > 120 || maxFieldCharacters < 1 || maxFieldCharacters > 4096
                || maxFileBytes < 1024 || maxFileBytes > 16 * 1024 * 1024 || maxRequestBytes < 1024
                || maxRequestBytes > 16 * 1024 * 1024) throw new IllegalArgumentException("MAC 查询配额配置不合法");
        this.maxRows = maxRows; this.concurrency = concurrency; this.timeoutSeconds = timeoutSeconds;
        this.maxFieldCharacters = maxFieldCharacters; this.maxFileBytes = maxFileBytes; this.maxRequestBytes = maxRequestBytes;
    }
}
