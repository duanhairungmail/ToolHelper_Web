package com.toolhelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 配额只能在方案硬上限以内调低；无效配置在启动时失败。 */
@Component
public record PingProperties(int maxTargets, int maxConcurrency, int maxFileBytes, int maxRequestBytes,
        int maxOutputBytes, int maxTasks, int retentionMinutes) {
    public PingProperties(@Value("${toolhelper.ping.max-targets:4096}") int maxTargets,
            @Value("${toolhelper.ping.max-concurrency:200}") int maxConcurrency,
            @Value("${toolhelper.ping.max-file-bytes:2097152}") int maxFileBytes,
            @Value("${toolhelper.ping.max-request-bytes:4194304}") int maxRequestBytes,
            @Value("${toolhelper.ping.max-output-bytes:65536}") int maxOutputBytes,
            @Value("${toolhelper.ping.max-tasks:20}") int maxTasks,
            @Value("${toolhelper.ping.retention-minutes:30}") int retentionMinutes) {
        if (maxTargets < 1 || maxTargets > 4096 || maxConcurrency < 1 || maxConcurrency > 200
                || maxFileBytes < 1024 || maxFileBytes > 2097152 || maxRequestBytes < 1024 || maxRequestBytes > 4194304
                || maxOutputBytes < 1024 || maxOutputBytes > 65536 || maxTasks < 1 || maxTasks > 20
                || retentionMinutes < 1 || retentionMinutes > 30) throw new IllegalArgumentException("群 Ping 配额配置不合法");
        this.maxTargets = maxTargets; this.maxConcurrency = maxConcurrency; this.maxFileBytes = maxFileBytes;
        this.maxRequestBytes = maxRequestBytes; this.maxOutputBytes = maxOutputBytes;
        this.maxTasks = maxTasks; this.retentionMinutes = retentionMinutes;
    }
}
