package com.toolhelper.service;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.plugin.electerm.*;
import com.toolhelper.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class TaskCoordinatorTest {
    @TempDir Path root;
    @Test void unfinishedRequestsAreInterruptedOnRestartAndNeverReplayed() throws Exception {
        var properties = ToolHelperProperties.forTests(root);
        var json = new JsonFiles(new JsonMapper());
        String id;
        try (var store = new InstallationStore(properties, json)) {
            var tasks = new TaskCoordinator(store, json, properties);
            try { id = tasks.claim("CONNECT", "crash-request-key", "body").taskId(); }
            finally { tasks.close(); }
        }
        try (var store = new InstallationStore(properties, json)) {
            var tasks = new TaskCoordinator(store, json, properties);
            try {
                assertEquals("INTERRUPTED", tasks.get(id).status());
                assertEquals(id, tasks.replay("crash-request-key", "body").taskId());
                assertNull(tasks.active());
            } finally { tasks.close(); }
        }
    }
    @Test void oneActiveTaskAndKeyBodyConflictsAreEnforced() throws Exception {
        var properties = ToolHelperProperties.forTests(root);
        try (var store = new InstallationStore(properties, new JsonFiles(new JsonMapper()))) {
            var tasks = new TaskCoordinator(store, new JsonFiles(new JsonMapper()), properties);
            try {
                var first = tasks.claim("INSTALL", "first-request-key", "one");
                assertEquals("PLUGIN_BUSY", assertThrows(ApiException.class, () -> tasks.claim("LAUNCH", "second-request-key", "two")).code());
                assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class, () -> tasks.replay("first-request-key", "other")).code());
                var failed = tasks.execute(first.taskId(), () -> { throw new ApiException(422, "VALIDATION_FAILED", "fixture"); });
                assertEquals(422, failed.error().status());
                assertNull(tasks.active());
                assertEquals("SUCCEEDED", tasks.execute(tasks.claim("LAUNCH", "second-request-key", "two").taskId(), () -> Map.of("status", "LAUNCH_REQUESTED")).status());
            } finally { tasks.close(); }
        }
    }
}
