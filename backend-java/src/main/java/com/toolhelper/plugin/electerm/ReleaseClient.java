package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.electerm.PluginModels.*;

@Component
public class ReleaseClient {
    private static final URI LATEST = URI.create("https://api.github.com/repos/electerm/electerm/releases/latest");
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com");
    private static final Pattern VERSION = Pattern.compile("v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService transfers = Executors.newVirtualThreadPerTaskExecutor();
    private final ToolHelperProperties properties;
    private final JsonMapper json;
    private final Map<String, ReleaseSelection> selections = new HashMap<>();
    private Asset cached;
    private Instant cachedAt = Instant.EPOCH;

    public ReleaseClient(ToolHelperProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public synchronized ReleaseSelection latest() throws IOException, InterruptedException {
        Instant now = Instant.now();
        if (cached == null || cachedAt.plusSeconds(60).isBefore(now)) {
            try (InputStream body = open(LATEST, Duration.ofSeconds(30), true)) {
                Future<byte[]> read = transfers.submit(() -> body.readNBytes(2 * 1024 * 1024 + 1));
                byte[] bytes = await(read, 30);
                if (bytes.length > 2 * 1024 * 1024) throw new IOException("发布信息响应超限");
                cached = select(json.readTree(bytes));
                cachedAt = now;
            }
        }
        selections.values().removeIf(selection -> selection.expiresAt().isBefore(now));
        if (selections.size() >= 128) selections.clear();
        ReleaseSelection selection = new ReleaseSelection(UUID.randomUUID().toString(), cached, now.plusSeconds(900));
        selections.put(selection.id(), selection);
        return selection;
    }

    public synchronized Asset selected(String id) {
        ReleaseSelection selection = selections.get(id);
        if (selection == null || selection.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(409, "RELEASE_SELECTION_EXPIRED", "发行选择已过期，请重新检查版本");
        }
        Asset asset = selection.asset();
        if (!properties.supportedVersions().contains(version(asset.tag()))) {
            throw new ApiException(422, "PLUGIN_VERSION_UNVERIFIED", "该发行版尚未列入连接适配版本，保留当前安装");
        }
        return asset;
    }

    Asset select(JsonNode release) {
        if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean()) {
            throw new ApiException(422, "RELEASE_UNSUPPORTED", "仅支持正式发行版");
        }
        String tag = release.path("tag_name").asText("");
        String version = version(tag);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("");
            if (name.equals("electerm-" + version + "-win-x64-portable.tar.gz")
                    || name.equals("electerm-" + version + "-win-x64-portable.zip")) matches.add(asset);
        }
        if (matches.size() != 1) throw new ApiException(422,
                matches.isEmpty() ? "ASSET_NOT_FOUND" : "ASSET_AMBIGUOUS", "未找到唯一的 Windows x64 portable 资产");
        JsonNode asset = matches.getFirst();
        String digest = asset.path("digest").asText("");
        if (!digest.matches("sha256:[0-9a-fA-F]{64}")) {
            throw new ApiException(422, "CHECKSUM_UNAVAILABLE", "官方资产未提供可用 SHA-256，已停止安装");
        }
        long size = asset.path("size").asLong();
        if (size <= 0 || size > properties.maxDownloadBytes()) throw new ApiException(422, "ASSET_TOO_LARGE", "资产大小超出下载配额");
        String url = asset.path("browser_download_url").asText("");
        URI uri = URI.create(url);
        validateUrl(uri);
        if (!uri.getHost().equals("github.com") || !uri.getPath().startsWith("/electerm/electerm/releases/download/")) {
            throw new ApiException(422, "ASSET_URL_UNTRUSTED", "资产下载来源不符合官方仓库限制");
        }
        return new Asset(release.path("id").asLong(), tag, asset.path("id").asLong(),
                asset.path("name").asText(), size, digest.substring(7).toLowerCase(Locale.ROOT), url);
    }

    public void download(Asset asset, Path target, BiConsumer<Long, Long> progress) throws IOException, InterruptedException {
        SecurePaths.createDirectories(target.getParent());
        try (InputStream input = open(URI.create(asset.downloadUrl()), Duration.ofMinutes(30), false)) {
            Future<Long> transfer = transfers.submit(() -> {
                long downloaded = 0;
                long lastReported = System.nanoTime();
                try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[128 * 1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("下载已中断");
                        downloaded += count;
                        if (downloaded > asset.size() || downloaded > properties.maxDownloadBytes()) throw new IOException("下载内容超出声明长度/配额");
                        output.write(buffer, 0, count);
                        if (System.nanoTime() - lastReported >= 500_000_000) {
                            progress.accept(downloaded, asset.size());
                            lastReported = System.nanoTime();
                        }
                    }
                }
                return downloaded;
            });
            long total = await(transfer, 1800);
            progress.accept(total, asset.size());
            if (total != asset.size()) throw new ApiException(502, "DOWNLOAD_INCOMPLETE", "实际下载长度与官方资产不一致");
        }
        if (!SecurePaths.sha256(target).equals(asset.sha256())) {
            throw new ApiException(422, "CHECKSUM_MISMATCH", "下载文件 SHA-256 与官方发布信息不一致");
        }
    }

    InputStream open(URI start, Duration timeout, boolean metadata) throws IOException, InterruptedException {
        URI current = start;
        for (int redirect = 0; redirect <= 5; redirect++) {
            validateUrl(current);
            HttpRequest request = HttpRequest.newBuilder(current).timeout(timeout)
                    .header("User-Agent", "ToolHelper_Web/0.1")
                    .header("Accept", metadata ? "application/vnd.github+json" : "application/octet-stream").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                response.body().close();
                String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("重定向缺少 Location"));
                current = current.resolve(location);
                continue;
            }
            if (status != 200) {
                response.body().close();
                boolean limited = status == 429 || (status == 403
                        && response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0"));
                if (limited) throw new ApiException(503, "GITHUB_RATE_LIMITED", "GitHub 请求受限，请稍后重试，当前安装未改变", true);
                throw new ApiException(502, "UPSTREAM_FAILED", "官方下载服务返回 HTTP " + status, true);
            }
            return response.body();
        }
        throw new IOException("官方下载重定向次数超限");
    }

    static void validateUrl(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new ApiException(422, "ASSET_URL_UNTRUSTED", "拒绝非白名单 HTTPS 下载地址");
        }
    }

    private static <T> T await(Future<T> future, int seconds) throws IOException, InterruptedException {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException error) {
            future.cancel(true);
            throw new ApiException(502, "DOWNLOAD_TIMEOUT", "网络操作超过总时间限制", true);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("网络传输失败", error.getCause());
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    public static String version(String tag) {
        var match = VERSION.matcher(tag == null ? "" : tag);
        if (!match.matches()) throw new ApiException(422, "VERSION_UNSUPPORTED", "不支持该发行版版本格式");
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    public static int compare(String left, String right) {
        String[] a = version(left).split("\\.");
        String[] b = version(right).split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (result != 0) return result;
        }
        return 0;
    }

    @PreDestroy public void close() { transfers.shutdownNow(); http.close(); }
}
