package com.toolhelper.plugin.dbx;

public final class DbxModels {
    private DbxModels() {}
    public record ConnectionRequest(String type, String host, Integer port, String username,
            String database, String connectionName, Boolean ssl, String password) {
        // 即使误用结构化日志，也不能由 record 的默认 toString 输出凭据。
        @Override public String toString() { return "DbxConnectionRequest[REDACTED]"; }
    }
}
