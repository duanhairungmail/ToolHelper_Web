package com.toolhelper.validation;

import java.util.List;

public final class ValidationModels {
    private ValidationModels() {}
    public record LoginRequest(String host, Integer port, String scheme, String username, String password,
                               String credentialMode, String loginFormat, Boolean allowInsecureHttp) {
        @Override public String toString() { return "ValidationLogin[REDACTED]"; }
    }
    public record RunRequest(String connectionId, List<String> endpointIds, Boolean confirmedSideEffects) {}
    public record ScheduleRequest(String connectionId, List<String> endpointIds, String cron) {}
    public record ConnectionRequest(String connectionId) {}
    public record Result(int index, String endpointId, String name, String method, String path,
                         Integer httpStatus, long elapsedMs, boolean reachable, String outcome, String message) {}
    public record Device(String name, String address, String id) {}
    public record Topic(String gateName, String downTopic, String upTopic) {}
}
