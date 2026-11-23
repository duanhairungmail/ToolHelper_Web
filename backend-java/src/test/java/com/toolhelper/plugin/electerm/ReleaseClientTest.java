package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class ReleaseClientTest {
    @TempDir Path root;
    private final JsonMapper json = new JsonMapper();

    private Map<String, Object> asset() {
        return new LinkedHashMap<>(Map.of("id", 2, "name", "electerm-5.12.6-win-x64-portable.tar.gz",
                "size", 10, "digest", "sha256:" + "a".repeat(64),
                "browser_download_url", "https://github.com/electerm/electerm/releases/download/v5.12.6/package.tar.gz"));
    }
    private Asset select(ReleaseClient client, List<Map<String, Object>> assets) {
        return client.select(json.valueToTree(Map.of("id", 1, "tag_name", "v5.12.6", "draft", false,
                "prerelease", false, "assets", assets)));
    }

    @Test void selectsExactUniqueAssetWithDigest() {
        try (Client client = new Client()) {
            Asset selected = select(client, List.of(asset()));
            assertEquals("v5.12.6", selected.tag());
            assertEquals("a".repeat(64), selected.sha256());
            assertEquals("ASSET_AMBIGUOUS", assertThrows(ApiException.class, () -> select(client, List.of(asset(), asset()))).code());
            assertEquals("ASSET_NOT_FOUND", assertThrows(ApiException.class, () -> select(client, List.of())).code());
        }
    }
    @Test void rejectsMissingHashAndUnofficialUrl() {
        try (Client client = new Client()) {
            Map<String, Object> asset = asset();
            asset.put("digest", "");
            assertEquals("CHECKSUM_UNAVAILABLE", assertThrows(ApiException.class, () -> select(client, List.of(asset))).code());
            asset.put("digest", "sha256:" + "a".repeat(64));
            asset.put("browser_download_url", "https://github.com/other/repo/releases/download/v1/x");
            assertEquals("ASSET_URL_UNTRUSTED", assertThrows(ApiException.class, () -> select(client, List.of(asset))).code());
        }
    }
    @Test void redirectsRequireExactHttpsHosts() {
        ReleaseClient.validateUrl(URI.create("https://release-assets.githubusercontent.com/release/file?sig=value"));
        for (String url : List.of("http://github.com/file", "https://github.com.evil.test/file", "https://user@github.com/file", "https://github.com:444/file")) {
            assertThrows(ApiException.class, () -> ReleaseClient.validateUrl(URI.create(url)));
        }
    }
    @Test void comparesNumericVersionsAndRejectsUnknownFormats() {
        assertTrue(ReleaseClient.compare("v5.10.0", "5.9.99") > 0);
        assertEquals(0, ReleaseClient.compare("5.12.6", "v5.12.6"));
        for (String version : List.of("5.12.6-beta", "05.12.6", "v5.12", "")) assertThrows(ApiException.class, () -> ReleaseClient.version(version));
    }
    @Test void streamedDownloadChecksLengthHashAndProgress() throws Exception {
        try (Client client = new Client()) {
            client.bytes = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Asset asset = new Asset(1, "v5.12.6", 2, "file.zip", 5, SecurePaths.hash("hello"), "https://github.com/file");
            List<Long> progress = new ArrayList<>();
            client.download(asset, root.resolve("good.part"), (done, total) -> progress.add(done));
            assertEquals("hello", Files.readString(root.resolve("good.part")));
            assertEquals(5L, progress.getLast());
            Asset badHash = new Asset(1, asset.tag(), 2, asset.name(), 5, "0".repeat(64), asset.downloadUrl());
            assertEquals("CHECKSUM_MISMATCH", assertThrows(ApiException.class, () -> client.download(badHash, root.resolve("bad.part"), (a, b) -> {})).code());
            client.bytes = new byte[]{1};
            assertEquals("DOWNLOAD_INCOMPLETE", assertThrows(ApiException.class, () -> client.download(asset, root.resolve("short.part"), (a, b) -> {})).code());
            client.bytes = new byte[6];
            assertThrows(IOException.class, () -> client.download(asset, root.resolve("long.part"), (a, b) -> {}));
        }
    }
    private class Client extends ReleaseClient implements AutoCloseable {
        byte[] bytes;
        Client() { super(ToolHelperProperties.forTests(root), json); }
        @Override InputStream open(URI start, Duration timeout, boolean metadata) { return new ByteArrayInputStream(bytes); }
    }
}
