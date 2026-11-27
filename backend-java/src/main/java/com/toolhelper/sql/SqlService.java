package com.toolhelper.sql;

import com.toolhelper.config.SqlProperties;
import com.toolhelper.support.ApiException;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import static com.toolhelper.sql.SqlModels.*;

@Service
public final class SqlService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SqlService.class);
    private final SqlGenerator generator = new SqlGenerator();
    private final SqlFormatter formatter = new SqlFormatter();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final int timeoutMs;
    public SqlService(SqlProperties properties) {
        timeoutMs = properties.timeoutMs();
        workers = new ThreadPoolExecutor(properties.threads(), properties.threads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name("sql-work-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("sql-deadline").factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }
    public Map<String, Object> capabilities() {
        return Map.ofEntries(Map.entry("dialects", List.of(Map.of("id", "MYSQL", "label", "MySQL", "escapeModes", List.of("BACKSLASH", "NO_BACKSLASH_ESCAPES")),
                        Map.of("id", "POSTGRESQL", "label", "PostgreSQL", "escapeModes", List.of("STANDARD")))),
                Map.entry("statementTypes", List.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE_TABLE")),
                Map.entry("format", Map.of("keywordCase", List.of("UPPER", "LOWER"), "indentSize", List.of(2, 4))),
                Map.entry("maxSqlBytes", 256 * 1024), Map.entry("maxRequestBytes", 2 * 1024 * 1024), Map.entry("maxStatements", 50),
                Map.entry("maxTokens", 50_000), Map.entry("maxOutputBytes", 1024 * 1024), Map.entry("timeoutMs", timeoutMs),
                Map.entry("unsupported", List.of("存储过程/触发器", "DELIMITER/psql命令", "hint/可执行注释", "参数占位符/模板", "MySQL双引号", "会改变token结构的AST规范化")));
    }
    public CompletableFuture<Result> generate(GenerateRequest request) { return submit(() -> generator.generate(request), "GENERATE"); }
    public CompletableFuture<Result> format(FormatRequest request) { return submit(() -> formatter.format(request), "FORMAT"); }
    CompletableFuture<Result> submit(Callable<Result> action, String operation) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!result.isDone()) try {
                Result value = action.call();
                if (value.sql().getBytes(StandardCharsets.UTF_8).length > SqlTokenGuard.OUTPUT_BYTES)
                    throw new ApiException(413, "SQL_TOO_LARGE", "SQL结果超过1 MiB");
                result.complete(value);
            } catch (Exception failure) { result.completeExceptionally(failure); }
            return null;
        });
        pending.add(result);
        try {
            workers.execute(task);
            ScheduledFuture<?> alarm = deadlines.schedule(() -> {
                if (result.completeExceptionally(new ApiException(503, "SQL_TIMEOUT", "SQL处理超时，请缩小输入或稍后重试"))) {
                    task.cancel(true); LOG.warn("SQL操作超时 operation={} timeoutMs={}", operation, timeoutMs);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            result.whenComplete((value, failure) -> {
                alarm.cancel(false);
                if (failure != null || result.isCancelled()) { task.cancel(true); workers.remove(task); }
                pending.remove(result);
            });
        } catch (RejectedExecutionException failure) {
            task.cancel(true); workers.remove(task); pending.remove(result);
            result.completeExceptionally(new ApiException(503, "SQL_BUSY", "SQL处理已达到并发上限，请稍后重试", true));
        }
        return result;
    }
    @PreDestroy @Override public void close() {
        workers.shutdownNow(); deadlines.shutdownNow();
        for (CompletableFuture<?> result : pending) result.completeExceptionally(new ApiException(503, "SQL_BUSY", "SQL服务正在关闭"));
    }
}
