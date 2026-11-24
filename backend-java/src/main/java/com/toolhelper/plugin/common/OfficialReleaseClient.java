package com.toolhelper.plugin.common;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.ApiException;
import com.toolhelper.support.SecurePaths;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.toolhelper.plugin.electerm.PluginModels.*;

/** 仓库级发行缓存、资产选择和兼容白名单；不接受前端提供下载地址。 */
public class OfficialReleaseClient implements AutoCloseable {
    private final String repository;
    private final java.util.function.Predicate<String> assetName;
    private final List<String> supportedVersions;
    private final ToolHelperProperties properties;
    private final JsonMapper json;
    private final OfficialDownloadClient downloads;
    private final Clock clock;
    private final boolean ownsDownloads;
    private static final Pattern VERSION = Pattern.compile("v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)");
    private final Map<String, ReleaseSelection> selections = new HashMap<>();
    private Asset cached;
    private Instant cachedAt = Instant.EPOCH;
    protected OfficialReleaseClient(ToolHelperProperties properties, JsonMapper json, OfficialDownloadClient downloads,
            Clock clock, boolean ownsDownloads, String repository, List<String> versions, java.util.function.Predicate<String> assetName) {
        this.properties = properties; this.json = json; this.downloads = downloads; this.clock = clock;
        this.ownsDownloads = ownsDownloads; this.repository = repository; this.supportedVersions = List.copyOf(versions); this.assetName = assetName;
    }
    /**
     * 查询最新正式发行版，并为本次选择生成一个有效期为 15 分钟的 ID。
     * synchronized 保护缓存和选择记录，避免并发请求重复刷新或修改同一个 HashMap。
     */
    public synchronized ReleaseSelection latest() throws IOException, InterruptedException {
        Instant now = clock.instant();
        // 发行信息缓存 60 秒，减少反复点击检查版本时对 GitHub API 的请求。
        if (cached == null || cachedAt.plusSeconds(60).isBefore(now)) {
            cached = select(json.readTree(downloads.metadata(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))));
            cachedAt = now;
        }
        // 清理过期记录并限制数量；安装时必须使用服务端保存的选择，不能自行拼装资产信息。
        selections.values().removeIf(selection -> selection.expiresAt().isBefore(now));
        if (selections.size() >= 128) selections.clear();
        ReleaseSelection selection = new ReleaseSelection(UUID.randomUUID().toString(), cached, now.plusSeconds(900));
        selections.put(selection.id(), selection);
        return selection;
    }

    /** 根据选择 ID 取回安装包，并确认该版本已列入连接适配白名单。 */
    public synchronized Asset selected(String id) {
        ReleaseSelection selection = selections.get(id);
        if (selection == null || selection.expiresAt().isBefore(clock.instant())) {
            throw new ApiException(409, "RELEASE_SELECTION_EXPIRED", "发行选择已过期，请重新检查版本");
        }
        Asset asset = selection.asset();
        if (!supportedVersions.contains(version(asset.tag()))) {
            throw new ApiException(422, "PLUGIN_VERSION_UNVERIFIED", "该发行版尚未列入连接适配版本，保留当前安装");
        }
        return asset;
    }

    /** 从官方发行信息中选出唯一的 Windows x64 便携包，同时验证摘要、大小和来源。 */
    public Asset select(JsonNode release) {
        if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean()) {
            throw new ApiException(422, "RELEASE_UNSUPPORTED", "仅支持正式发行版");
        }
        String tag = release.path("tag_name").asText("");
        String version = version(tag);
        // 精确匹配版本与包名；既不选择安装器，也不在多个候选之间任意挑选。
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode asset : release.path("assets")) {
            String name = asset.path("name").asText("");
            if (assetName.test(version + ":" + name)) matches.add(asset);
        }
        if (matches.size() != 1) throw new ApiException(422,
                matches.isEmpty() ? "ASSET_NOT_FOUND" : "ASSET_AMBIGUOUS", "未找到唯一的 Windows x64 portable 资产");
        JsonNode asset = matches.getFirst();
        // 下载完成后要与官方 SHA-256 对比；缺少摘要时直接拒绝，不跳过校验。
        String digest = asset.path("digest").asText("");
        if (!digest.matches("sha256:[0-9a-fA-F]{64}")) {
            throw new ApiException(422, "CHECKSUM_UNAVAILABLE", "官方资产未提供可用 SHA-256，已停止安装");
        }
        long size = asset.path("size").asLong();
        if (size <= 0 || size > properties.maxDownloadBytes()) throw new ApiException(422, "ASSET_TOO_LARGE", "资产大小超出下载配额");
        String url = asset.path("browser_download_url").asText("");
        URI uri = URI.create(url);
        OfficialDownloadClient.validateUrl(uri);
        // 初始资产地址还必须属于 electerm 官方仓库；后续跳转则由 open() 检查域名。
        if (!uri.getHost().equals("github.com") || !uri.getPath().startsWith("/" + repository + "/releases/download/")) {
            throw new ApiException(422, "ASSET_URL_UNTRUSTED", "资产下载来源不符合官方仓库限制");
        }
        return new Asset(release.path("id").asLong(), tag, asset.path("id").asLong(),
                asset.path("name").asText(), size, digest.substring(7).toLowerCase(Locale.ROOT), url);
    }


    public void download(Asset asset, Path target, BiConsumer<Long, Long> progress) throws IOException, InterruptedException {
        downloads.download(asset, target, progress);
    }
    public InputStream open(URI uri, Duration timeout, boolean metadata) throws IOException, InterruptedException { return downloads.open(uri, timeout, metadata); }
    public static void validateUrl(URI uri) { OfficialDownloadClient.validateUrl(uri); }
    /** 只接受三段数字版本，可带 v 前缀；返回去掉前缀后的版本号。 */
    public static String version(String tag) {
        var match = VERSION.matcher(tag == null ? "" : tag);
        if (!match.matches()) throw new ApiException(422, "VERSION_UNSUPPORTED", "不支持该发行版版本格式");
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /** 按主版本、次版本、修订号的数值比较，避免字符串比较把 5.10 排在 5.9 前面。 */
    public static int compare(String left, String right) {
        String[] a = version(left).split("\\.");
        String[] b = version(right).split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (result != 0) return result;
        }
        return 0;
    }


    @PreDestroy public void close() { if (ownsDownloads) downloads.close(); }
}
