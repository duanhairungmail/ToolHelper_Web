package com.toolhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record DruidScanProperties(int maxTargets, int concurrency, int requestSeconds, int targetSeconds,
        int maxRequests, int maxResponseBytes, int maxRequestBytes) {
    public DruidScanProperties(@Value("${toolhelper.druid.max-targets:100}") int maxTargets,
            @Value("${toolhelper.druid.concurrency:4}") int concurrency,
            @Value("${toolhelper.druid.request-seconds:5}") int requestSeconds,
            @Value("${toolhelper.druid.target-seconds:30}") int targetSeconds,
            @Value("${toolhelper.druid.max-requests:16}") int maxRequests,
            @Value("${toolhelper.druid.max-response-bytes:1048576}") int maxResponseBytes,
            @Value("${toolhelper.druid.max-request-bytes:262144}") int maxRequestBytes) {
        if (maxTargets < 1 || maxTargets > 100 || concurrency < 1 || concurrency > 4 || requestSeconds < 1
                || requestSeconds > 30 || targetSeconds < requestSeconds || targetSeconds > 120
                || maxRequests < 13 || maxRequests > 32 || maxResponseBytes < 1024 || maxResponseBytes > 1048576
                || maxRequestBytes < 1024 || maxRequestBytes > 262144) throw new IllegalArgumentException("Druid检测配额不合法");
        this.maxTargets = maxTargets; this.concurrency = concurrency; this.requestSeconds = requestSeconds;
        this.targetSeconds = targetSeconds; this.maxRequests = maxRequests;
        this.maxResponseBytes = maxResponseBytes; this.maxRequestBytes = maxRequestBytes;
    }
}
