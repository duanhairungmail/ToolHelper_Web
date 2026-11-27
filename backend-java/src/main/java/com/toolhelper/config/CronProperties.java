package com.toolhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record CronProperties(int threads, int queueCapacity, int timeoutMs) {
    public CronProperties(@Value("${toolhelper.cron.threads:2}") int threads,
            @Value("${toolhelper.cron.queue-capacity:8}") int queueCapacity,
            @Value("${toolhelper.cron.timeout-ms:2000}") int timeoutMs) {
        if (threads < 1 || threads > 2 || queueCapacity < 1 || queueCapacity > 8 || timeoutMs < 1 || timeoutMs > 2000)
            throw new IllegalArgumentException("Cron 计算配额配置不合法");
        this.threads = threads; this.queueCapacity = queueCapacity; this.timeoutMs = timeoutMs;
    }
}
