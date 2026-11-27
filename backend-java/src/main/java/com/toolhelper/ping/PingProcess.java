package com.toolhelper.ping;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 实际子进程有截止时间和输出配额；取消会终止进程并等待释放，不能只取消 Future。 */
final class PingProcess {
    record Output(int exitCode, byte[] bytes) {}
    private PingProcess() {}
    static Output run(ProcessBuilder builder, Duration timeout, int maxBytes, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw failure("PING_CANCELLED", "探测已停止");
        Process process;
        try { process = builder.redirectErrorStream(true).start(); }
        catch (IOException error) { throw failure("PING_COMMAND_UNAVAILABLE", "无法启动系统 Ping 程序"); }
        long deadline = System.nanoTime() + timeout.toNanos();
        ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> output = reader.submit(() -> {
            byte[] bytes = process.getInputStream().readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("Ping output exceeds limit");
            return bytes;
        });
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw failure("PING_CANCELLED", "探测已停止");
                if (System.nanoTime() >= deadline) throw failure("PING_PROCESS_TIMEOUT", "系统 Ping 进程超过截止时间，无法判断");
                if (output.isDone()) output.get();
            }
            if (cancelled.getAsBoolean()) throw failure("PING_CANCELLED", "探测已停止");
            return new Output(process.exitValue(), output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (ExecutionException error) { throw failure("PING_OUTPUT_LIMIT", "Ping 输出读取失败或超过大小限制"); }
        catch (TimeoutException error) { throw failure("PING_PROCESS_TIMEOUT", "Ping 输出超过截止时间，无法判断"); }
        finally {
            process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            try {
                // 固定命令不使用 shell，因此没有需要继续追踪的 shell 子进程。
                while (process.isAlive()) {
                    try { process.waitFor(50, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException error) { interrupted = true; }
                }
                try { process.getInputStream().close(); } catch (IOException ignored) { /* 已终止进程，流清理不覆盖原结果。 */ }
                output.cancel(true); reader.shutdownNow();
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private static ApiException failure(String code, String message) { return new ApiException(502, code, message); }
}
