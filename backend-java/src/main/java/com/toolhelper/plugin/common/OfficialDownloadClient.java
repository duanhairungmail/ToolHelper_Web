package com.toolhelper.plugin.common;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import jakarta.annotation.PreDestroy;
import com.toolhelper.plugin.electerm.DownloadProxySelector;
import java.io.*;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import static com.toolhelper.plugin.electerm.PluginModels.*;

/** 两个受管插件共用的官方传输、系统代理和域名限流冷却。 */
@Component
public class OfficialDownloadClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(OfficialDownloadClient.class);
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    private final HttpClient http;
    private final Clock clock;
    private final ToolHelperProperties properties;
    private final Map<String, Instant> rateLimitedUntil = new ConcurrentHashMap<>();
    private final ExecutorService transfers = Executors.newVirtualThreadPerTaskExecutor();
    @Autowired
    public OfficialDownloadClient(ToolHelperProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .proxy(new DownloadProxySelector(ProxySelector.getDefault())).followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }
    public OfficialDownloadClient(ToolHelperProperties properties, HttpClient http, Clock clock) {
        this.properties = properties; this.http = http; this.clock = clock;
    }
    public byte[] metadata(URI uri) throws IOException, InterruptedException {
        try (InputStream body = open(uri, Duration.ofSeconds(30), true)) {
            byte[] bytes = await(transfers.submit(() -> body.readNBytes(2 * 1024 * 1024 + 1)), 30);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("发布信息响应超限");
            return bytes;
        }
    }
    /**
     * 单路流式下载到新文件，依次检查大小上限、最终长度和 SHA-256。
     * progress 接收已下载字节数与总字节数；本方法不执行解压或安装。
     */
    public void download(Asset asset, Path target, BiConsumer<Long, Long> progress) throws IOException, InterruptedException {
        SecurePaths.createDirectories(target.getParent());
        try (InputStream input = open(URI.create(asset.downloadUrl()), Duration.ofMinutes(30), false)) {
            Future<Long> transfer = transfers.submit(() -> {
                long downloaded = 0;
                long lastReported = System.nanoTime();
                // CREATE_NEW 防止覆盖已有文件；分块读写，避免将整个安装包加载到内存。
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[128 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("下载已中断");
                        downloaded += count;
                        // 在写入前检查累计大小，避免服务端发送超过声明长度或本地配额的数据。
                        if (downloaded > asset.size() || downloaded > properties.maxDownloadBytes()) throw new IOException("下载内容超出声明长度/配额");
                        output.write(buffer, 0, count);
                        // 约每 0.5 秒回报进度，限制的是通知频率，不是网络下载速度。
                        if (System.nanoTime() - lastReported >= 500_000_000) {
                            progress.accept(downloaded, asset.size());
                            lastReported = System.nanoTime();
                        }
                    }
                }
                return downloaded;
            });
            // 响应体为流式读取，额外限制传输任务最多等待 30 分钟，并补报最终进度。
            long total = await(transfer, 1800);
            progress.accept(total, asset.size());
            if (total != asset.size()) throw new ApiException(502, "DOWNLOAD_INCOMPLETE", "实际下载长度与官方资产不一致");
        }
        // 长度相同不代表内容正确；关闭文件后再计算摘要，与官方发行信息核对。
        if (!SecurePaths.sha256(target).equals(asset.sha256())) {
            throw new ApiException(422, "CHECKSUM_MISMATCH", "下载文件 SHA-256 与官方发布信息不一致");
        }
    }

    /**
     * 打开官方 HTTP 响应流，调用方负责关闭。
     * metadata 区分 GitHub API 的 JSON 信息和安装包二进制内容。
     */
    public InputStream open(URI start, Duration timeout, boolean metadata) throws IOException, InterruptedException {
        URI current = start;
        // 初次请求加最多 5 次重定向请求；每次发送前重新校验，防止跳向非白名单地址。
        for (int redirect = 0; redirect <= 5; redirect++) {
            validateUrl(current);
            requireRateLimitReady(current);
            HttpRequest request = HttpRequest.newBuilder(current).timeout(timeout)
                    .header("User-Agent", "ToolHelper_Web/0.1")
                    .header("Accept", metadata ? "application/vnd.github+json" : "application/octet-stream").GET().build();
            // 获取响应流后再由调用方读取；大文件不会在这里一次性进入内存。
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException failure) {
                // HttpClient 会包装代理选择器的异常，保留可展示给页面的配置错误。
                for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof ApiException error) throw error;
                }
                LOG.warn("官方下载连接失败 host={}", current.getHost(), failure);
                ApiException error = new ApiException(502, "UPSTREAM_CONNECTION_FAILED",
                        "无法连接官方下载服务，请检查本机网络及系统代理配置", true);
                error.initCause(failure);
                throw error;
            }
            int status = response.statusCode();
            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                // 不使用当前响应体，先释放连接资源；resolve 同时支持绝对和相对 Location。
                response.body().close();
                String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("重定向缺少 Location"));
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                response.body().close();
                // 403 也可能是其他权限问题，只有配额耗尽或明确给出等待时间时才按限流处理。
                boolean limited = status == 429 || (status == 403
                        && (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                            || response.headers().firstValue("Retry-After").isPresent()));
                if (limited) {
                    Instant retryAt = rateLimitedUntil.merge(current.getHost(), retryAt(response.headers()),
                            (previous, next) -> previous.isAfter(next) ? previous : next);
                    LOG.warn("GitHub 请求限流 host={} status={} limit={} remaining={} retryAt={}", current.getHost(), status,
                            response.headers().firstValue("X-RateLimit-Limit").orElse("unknown"),
                            response.headers().firstValue("X-RateLimit-Remaining").orElse("unknown"), retryAt);
                    throw rateLimitError(retryAt);
                }
                throw new ApiException(502, "UPSTREAM_FAILED", "官方下载服务返回 HTTP " + status, true);
            }
            return response.body();
        }
        throw new IOException("官方下载重定向次数超限");
    }

    /** 冷却按域名隔离；API 配额耗尽不阻断已经取得合法选择的资产下载。 */
    private void requireRateLimitReady(URI uri) {
        Instant retryAt = rateLimitedUntil.get(uri.getHost());
        if (retryAt != null) {
            if (clock.instant().isBefore(retryAt)) throw rateLimitError(retryAt);
            rateLimitedUntil.remove(uri.getHost(), retryAt);
        }
    }

    /** 优先遵守上游的等待时间；主配额耗尽时还要等待配额重置，无法解析时冷却一分钟。 */
    private Instant retryAt(HttpHeaders headers) {
        Instant now = clock.instant();
        Instant result = now;
        String retryAfter = headers.firstValue("Retry-After").orElse("").trim();
        if (!retryAfter.isEmpty()) {
            try {
                result = retryAfter.matches("[0-9]+") ? now.plusSeconds(Long.parseLong(retryAfter))
                        : ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeException | ArithmeticException | NumberFormatException invalid) {
                LOG.warn("GitHub Retry-After 响应头无效，将按配额重置时间或一分钟冷却处理");
            }
        }
        if (headers.firstValue("X-RateLimit-Remaining").orElse("").equals("0")) {
            try {
                Instant reset = Instant.ofEpochSecond(Long.parseLong(headers.firstValue("X-RateLimit-Reset").orElse("")));
                if (reset.isAfter(result)) result = reset;
            } catch (DateTimeException | NumberFormatException invalid) {
                LOG.warn("GitHub 配额重置时间无效，将按 Retry-After 或一分钟冷却处理");
            }
        }
        return result.isAfter(now) ? result : now.plusSeconds(60);
    }

    private static ApiException rateLimitError(Instant retryAt) {
        String localTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
                .withZone(ZoneId.systemDefault()).format(retryAt);
        return new ApiException(503, "GITHUB_RATE_LIMITED",
                "GitHub 请求受限，预计可重试时间：" + localTime + "；请勿连续点击，当前安装未改变", true);
    }

    /** 仅允许白名单域名上的标准 HTTPS 地址，拒绝 URL 内嵌凭据及非标准端口。 */
    public static void validateUrl(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new ApiException(422, "ASSET_URL_UNTRUSTED", "拒绝非白名单 HTTPS 下载地址");
        }
    }

    /** 统一处理流读取任务的超时、执行异常和中断，并向上层保留可识别的错误。 */
    private static <T> T await(Future<T> future, int seconds) throws IOException, InterruptedException {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException error) {
            future.cancel(true);
            throw new ApiException(502, "DOWNLOAD_TIMEOUT", "网络操作超过总时间限制", true);
        } catch (ExecutionException error) {
            // 保留业务运行时异常的类型；其他执行异常包装为网络 I/O 错误。
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("网络传输失败", error.getCause());
        } catch (InterruptedException error) {
            future.cancel(true);
            // InterruptedException 会清除中断标志，重新设置后让上层仍能感知取消请求。
            Thread.currentThread().interrupt();
            throw error;
        }
    }


    @PreDestroy public void close() { transfers.shutdownNow(); http.close(); }
}
