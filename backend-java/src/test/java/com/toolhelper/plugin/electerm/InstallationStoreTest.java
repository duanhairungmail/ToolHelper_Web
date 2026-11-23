package com.toolhelper.plugin.electerm;

import com.toolhelper.config.ToolHelperProperties;
import com.toolhelper.support.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static com.toolhelper.plugin.electerm.PluginModels.*;

class InstallationStoreTest {
    @TempDir Path root;
    private InstallationStore store;
    @BeforeEach void setUp() throws Exception { store = new InstallationStore(ToolHelperProperties.forTests(root), new JsonFiles(new JsonMapper())); }
    @AfterEach void tearDown() throws Exception { store.close(); }
    private InstallManifest candidate(Path candidate, String tag) throws Exception {
        Files.createDirectories(candidate.resolve("app/package"));
        Files.writeString(candidate.resolve("app/package/electerm.exe"), "fixture-exe");
        return store.makeCandidate(candidate, new Asset(1, tag, 2, "package.zip", 1, "a".repeat(64), "https://github.com/file"), List.of("package/electerm.exe"));
    }
    @Test void preventsTwoServicesManagingOneRoot() {
        assertThrows(IOException.class, () -> new InstallationStore(ToolHelperProperties.forTests(root), new JsonFiles(new JsonMapper())));
    }
    @Test void startupRestoresOldVersionAfterOldDirectoryMoved() throws Exception {
        String task = UUID.randomUUID().toString();
        InstallManifest old = candidate(store.current(), "v5.12.5");
        store.writeJournal(new Journal("UPDATE", task, old.revision(), UUID.randomUUID().toString(), "PREPARED", false));
        store.move(store.current(), store.backup(task));
        store.recover();
        assertEquals(old.revision(), store.requireInstalled().revision());
        assertFalse(Files.exists(store.journalPath()));
    }
    @Test void explicitFailureRollsBackEvenWhenNewInstallationIsValid() throws Exception {
        String task = UUID.randomUUID().toString();
        InstallManifest old = candidate(store.current(), "v5.12.5");
        Path nextPath = store.stage(task).resolve("candidate");
        InstallManifest next = candidate(nextPath, "v5.12.6");
        Journal transaction = new Journal("UPDATE", task, old.revision(), next.revision(), "NEW_MOVED", false);
        store.writeJournal(transaction);
        store.move(store.current(), store.backup(task));
        store.move(nextPath, store.current());
        store.rollback(transaction);
        assertEquals(old.revision(), store.requireInstalled().revision());
        assertTrue(Files.isDirectory(store.stage(task).resolve("failed-current")));
    }
    @Test void invalidJournalStopsChangesAndKeepsEvidence() throws Exception {
        Files.writeString(store.journalPath(), "invalid json");
        store.recover();
        assertEquals("RECOVERY_REQUIRED", store.inspect().state());
        assertTrue(Files.exists(store.journalPath()));
    }
    @Test void copiesVerifiedUserDataAndRejectsUnclassifiedFiles() throws Exception {
        InstallManifest old = candidate(store.current(), "v5.12.6");
        Path data = store.relative(store.current(), old.userData());
        Files.createDirectories(data);
        Files.writeString(data.resolve("db.json"), "user settings");
        store.verifyOwnedFiles(old);
        Path preserved = store.preserved(UUID.randomUUID().toString());
        assertTrue(store.copyData(old, preserved));
        assertEquals("user settings", Files.readString(preserved.resolve("db.json")));
        Files.writeString(store.current().resolve("notes.txt"), "must not delete");
        assertEquals("USER_DATA_LAYOUT_UNKNOWN", assertThrows(ApiException.class, () -> store.verifyOwnedFiles(old)).code());
        assertEquals("must not delete", Files.readString(store.current().resolve("notes.txt")));
    }
}
