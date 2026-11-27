package com.toolhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record SqlProperties(int threads, int queueCapacity, int timeoutMs) {
    public SqlProperties(@Value("${toolhelper.sql.threads:2}") int threads,
                         @Value("${toolhelper.sql.queue-capacity:4}") int queueCapacity,
                         @Value("${toolhelper.sql.timeout-ms:3000}") int timeoutMs) {
        if (threads < 1 || threads > 2 || queueCapacity < 1 || queueCapacity > 4 || timeoutMs < 1 || timeoutMs > 3000)
            throw new IllegalArgumentException("SQL计算资源配置不合法");
        this.threads = threads; this.queueCapacity = queueCapacity; this.timeoutMs = timeoutMs;
    }
}
