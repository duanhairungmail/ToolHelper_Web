package com.toolhelper.cron;

import com.toolhelper.config.CronProperties;
import com.toolhelper.support.ApiException;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.*;

/** HTTP异步等待；取消不替换线程，单次next若不响应中断仍占原计算槽。 */
public final class CronCalculator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CronCalculator.class);
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final int timeoutMs;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    public CronCalculator(CronProperties properties) {
        timeoutMs = properties.timeoutMs();
        workers = new ThreadPoolExecutor(properties.threads(), properties.threads(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), Thread.ofPlatform().name("cron-preview-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("cron-deadline").factory());
        deadlines.setRemoveOnCancelPolicy(true);
    }
    public <T> CompletableFuture<T> submit(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!result.isDone()) {
                try { result.complete(action.call()); }
                catch (Exception failure) { result.completeExceptionally(failure); }
            }
            return null;
        });
        pending.add(result);
        try {
            workers.execute(task);
            ScheduledFuture<?> alarm = deadlines.schedule(() -> {
                if (result.completeExceptionally(new ApiException(503, "CRON_CALCULATION_TIMEOUT", "Cron 计算超时，请减少预览条数或简化表达式")))
                    LOG.warn("Cron预览计算超时 timeoutMs={}", timeoutMs);
            }, timeoutMs, TimeUnit.MILLISECONDS);
            result.whenComplete((value, failure) -> {
                alarm.cancel(false);
                if (failure != null || result.isCancelled()) { task.cancel(true); workers.remove(task); }
                pending.remove(result);
            });
        } catch (RejectedExecutionException failure) {
            task.cancel(true); workers.remove(task); pending.remove(result);
            result.completeExceptionally(new ApiException(503, "CRON_BUSY", "Cron 计算已达到并发上限或服务正在关闭，请稍后重试"));
        }
        return result;
    }
    @Override public void close() {
        workers.shutdownNow(); deadlines.shutdownNow();
        for (CompletableFuture<?> result : pending) result.completeExceptionally(new ApiException(503, "CRON_BUSY", "Cron 服务正在关闭"));
    }
}
