package com.toolhelper.devicemac;

import com.toolhelper.support.ApiException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 同时消费两条输出流；取消和截止时间控制实际进程，而不是仅停止等待。 */
public final class BoundedProcess {
    private BoundedProcess() {}
    public static String run(ProcessBuilder builder, Duration timeout, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new ApiException(409, "QUERY_CANCELLED", "查询已取消");
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process;
        try { process = builder.start(); }
        catch (IOException error) { throw new ApiException(503, "COMMAND_UNAVAILABLE", "无法启动 Windows 查询工具"); }
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> out = readers.submit(() -> limited(process.getInputStream()));
            Future<byte[]> err = readers.submit(() -> limited(process.getErrorStream()));
            try {
                while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) throw new ApiException(409, "QUERY_CANCELLED", "查询已取消");
                    if (System.nanoTime() >= deadline) throw new ApiException(408, "QUERY_TIMEOUT", "查询超过总截止时间");
                    if (out.isDone()) check(out);
                    if (err.isDone()) check(err);
                }
                if (cancelled.getAsBoolean()) throw new ApiException(409, "QUERY_CANCELLED", "查询已取消");
                if (System.nanoTime() >= deadline) throw new ApiException(408, "QUERY_TIMEOUT", "查询超过总截止时间");
                byte[] stdout = out.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                byte[] stderr = err.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (process.exitValue() != 0 || stderr.length != 0) throw new ApiException(502, "COMMAND_FAILED", "Windows 查询工具执行失败");
                return new String(stdout, StandardCharsets.UTF_8).strip().replaceFirst("^\\uFEFF", "");
            } catch (ExecutionException error) { throw new ApiException(502, "COMMAND_FAILED", "查询输出异常或超出限制"); }
            catch (TimeoutException error) { throw new ApiException(408, "QUERY_TIMEOUT", "查询输出超过总截止时间"); }
            finally {
                terminate(process);
                try { process.getInputStream().close(); process.getErrorStream().close(); } catch (IOException ignored) { /* 进程已终止，流清理不改变查询结果。 */ }
                out.cancel(true); err.cancel(true);
            }
        }
    }
    private static void check(Future<byte[]> stream) throws ExecutionException, InterruptedException { stream.get(); }
    private static byte[] limited(InputStream stream) throws IOException {
        byte[] data = stream.readNBytes(65537);
        if (data.length > 65536) throw new IOException("output limit");
        return data;
    }
    private static void terminate(Process process) {
        // 先捕获后代，再终止根进程，避免根退出后无法识别仍存活的子进程。
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            while (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                try { Thread.sleep(20); } catch (InterruptedException error) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
