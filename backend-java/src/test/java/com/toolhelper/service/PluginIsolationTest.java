package com.toolhelper.service;

import com.toolhelper.config.*;
import com.toolhelper.plugin.common.*;
import com.toolhelper.plugin.dbx.*;
import com.toolhelper.plugin.electerm.*;
import com.toolhelper.support.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class PluginIsolationTest {
    @TempDir Path root;
    private final JsonMapper mapper = new JsonMapper();

    @Test void twoStoresShareOneRootLockAndGlobalTaskButKeepPluginOwnership() throws Exception {
        var props = ToolHelperProperties.forTests(root);
        var json = new JsonFiles(mapper);
        try (var data = new LocalDataRoot(props)) {
            var remote = new InstallationStore(props, json, data);
            var dbx = new DbxInstallationStore(props, json, data);
            assertNotEquals(remote.pluginRoot(), dbx.pluginRoot());
            assertThrows(IOException.class, () -> new LocalDataRoot(props));
            var tasks = new TaskCoordinator(data, json, props);
            try {
                var first = tasks.claim("electerm", "INSTALL", "shared-request-key", "remote");
                assertNull(tasks.replay("dbx", "shared-request-key", "database"));
                assertEquals("PLUGIN_BUSY", assertThrows(ApiException.class, () -> tasks.claim("dbx", "INSTALL", "shared-request-key", "database")).code());
                tasks.execute(first.taskId(), Map::of);
                var second = tasks.claim("dbx", "INSTALL", "shared-request-key", "database");
                tasks.execute(second.taskId(), Map::of);
                assertEquals("electerm", tasks.replay("electerm", "shared-request-key", "remote").pluginId());
                assertEquals("dbx", tasks.replay("dbx", "shared-request-key", "database").pluginId());
            } finally { tasks.close(); }
        }
    }

    @Test void legacyTaskDefaultsToElectermAndSensitiveFingerprintNeverPersists() throws Exception {
        var props = ToolHelperProperties.forTests(root);
        var json = new JsonFiles(mapper);
        String sensitiveId;
        try (var data = new LocalDataRoot(props)) {
            var legacy = new TaskRecord(UUID.randomUUID().toString(), "LAUNCH", SecurePaths.hash("legacy-request-key"), "old", "SUCCEEDED", "DISPATCHING", 0, null, "完成", Instant.now(), Instant.now(), Map.of(), null);
            String oldJson = mapper.writeValueAsString(legacy).replace(",\"pluginId\":\"electerm\"", "");
            assertFalse(oldJson.contains("pluginId"));
            Files.writeString(data.tasksRoot().resolve(legacy.taskId() + ".json"), oldJson);
            var tasks = new TaskCoordinator(data, json, props);
            try {
                assertEquals("electerm", tasks.get(legacy.taskId()).pluginId());
                var task = tasks.claim("dbx", "CONNECT", "sensitive-request-key", "process-secret-hmac", true);
                sensitiveId = task.taskId();
                tasks.execute(task.taskId(), () -> Map.of("status", "CONNECTION_REQUESTED"));
                String persisted = Files.readString(data.tasksRoot().resolve(task.taskId() + ".json"));
                assertTrue(persisted.contains("EPHEMERAL")); assertFalse(persisted.contains("process-secret-hmac"));
                assertEquals(task.taskId(), tasks.replay("dbx", "sensitive-request-key", "process-secret-hmac").taskId());
                assertThrows(ApiException.class, () -> tasks.replay("dbx", "sensitive-request-key", "different"));
            } finally { tasks.close(); }
        }
        try (var data = new LocalDataRoot(props)) {
            var tasks = new TaskCoordinator(data, json, props);
            try {
                assertEquals("SUCCEEDED", tasks.get(sensitiveId).status());
                assertEquals("REQUEST_OUTCOME_UNKNOWN", assertThrows(ApiException.class, () -> tasks.replay("dbx", "sensitive-request-key", "process-secret-hmac")).code());
            } finally { tasks.close(); }
        }
    }

    @Test void repositoryCachesAndReleaseSelectionsAreIsolated() throws Exception {
        var props = ToolHelperProperties.forTests(root);
        List<String> requests = new ArrayList<>();
        try (var downloads = new OfficialDownloadClient(props) {
            @Override public byte[] metadata(URI uri) {
                requests.add(uri.toString());
                boolean db = uri.getPath().contains("t8y2");
                String version = db ? "0.6.72" : "5.12.6";
                String name = db ? "DBX_0.6.72_x64-portable.zip" : "electerm-5.12.6-win-x64-portable.zip";
                String repo = db ? "t8y2/dbx" : "electerm/electerm";
                return mapper.writeValueAsBytes(Map.of("id", 1, "tag_name", "v" + version, "assets", List.of(Map.of("id", 2, "name", name,
                        "size", 100, "digest", "sha256:" + "a".repeat(64), "browser_download_url", "https://github.com/" + repo + "/releases/download/v" + version + "/" + name))));
            }
        }) {
            var remote = new ReleaseClient(props, mapper, downloads);
            var dbx = new DbxReleaseClient(props, new DbxProperties(List.of("0.6.72")), mapper, downloads);
            var a = remote.latest(); var b = dbx.latest();
            remote.latest(); dbx.latest(); assertEquals(2, requests.size());
            assertEquals("RELEASE_SELECTION_EXPIRED", assertThrows(ApiException.class, () -> remote.selected(b.id())).code());
            assertEquals("RELEASE_SELECTION_EXPIRED", assertThrows(ApiException.class, () -> dbx.selected(a.id())).code());
        }
    }

    @Test void githubCooldownIsSharedAcrossRepositories() throws Exception {
        var props = ToolHelperProperties.forTests(root);
        var http = mock(HttpClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("60")), (a,b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any())).thenReturn(response);
        try (var downloads = new OfficialDownloadClient(props, http, Clock.systemUTC())) {
            var remote = new ReleaseClient(props, mapper, downloads);
            var dbx = new DbxReleaseClient(props, new DbxProperties(List.of("0.6.72")), mapper, downloads);
            assertEquals("GITHUB_RATE_LIMITED", assertThrows(ApiException.class, remote::latest).code());
            assertEquals("GITHUB_RATE_LIMITED", assertThrows(ApiException.class, dbx::latest).code());
            verify(http, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        }
    }
}
