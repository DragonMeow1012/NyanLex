package com.dragonmeow.nyanslate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDataMigrationTest {

    @Test
    void copiesMainConfigFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator.json"), "{\"targetLang\":\"zh-tw\"}",
                StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(1, migrated);
        assertTrue(Files.exists(dir.resolve("nyanslate.json")));
        assertEquals("{\"targetLang\":\"zh-tw\"}",
                Files.readString(dir.resolve("nyanslate.json"), StandardCharsets.UTF_8));
        // Old file is kept, never deleted or moved.
        assertTrue(Files.exists(dir.resolve("mctranslator.json")));
    }

    @Test
    void copiesPerLanguageCacheAiCacheAndFailureFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator-cache-zh-tw.json"), "gt", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-ai-cache-zh-tw.json"), "ai", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-failures-zh-tw.json"), "fail", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-hub-cache-zh-tw.json"), "hub", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-hub-state.json"), "state", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(5, migrated);
        assertTrue(Files.exists(dir.resolve("nyanslate-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate-ai-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate-failures-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate-hub-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate-hub-state.json")));
    }

    @Test
    void handlesLegacyTreeConfigFileNames(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator-legacy.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-forge-legacy.json"), "{}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(2, migrated);
        assertTrue(Files.exists(dir.resolve("nyanslate-legacy.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate-forge-legacy.json")));
    }

    @Test
    void doesNotOverwriteExistingNewFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator.json"), "{\"old\":true}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanslate.json"), "{\"new\":true}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertEquals("{\"new\":true}",
                Files.readString(dir.resolve("nyanslate.json"), StandardCharsets.UTF_8));
    }

    @Test
    void skipsCodexHomeAndWorkspaceDirectories(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("mctranslator-codex-home"));
        Files.createDirectory(dir.resolve("mctranslator-codex-workspace"));
        Files.writeString(dir.resolve("mctranslator-codex-home").resolve("auth.json"), "secret",
                StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertFalse(Files.exists(dir.resolve("nyanslate-codex-home")));
        assertFalse(Files.exists(dir.resolve("nyanslate-codex-workspace")));
    }

    @Test
    void ignoresUnrelatedAndNonBoundaryPrefixedFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("other-mod.json"), "{}", StandardCharsets.UTF_8);
        // "mctranslatorx..." is not a "mctranslator"+("-"|".json") boundary match.
        Files.writeString(dir.resolve("mctranslatorx-cache.json"), "{}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertFalse(Files.exists(dir.resolve("nyanslate-x-cache.json")));
    }

    @Test
    void missingOrNonDirectoryConfigDirIsNoOp(@TempDir Path dir) {
        assertEquals(0, LegacyDataMigration.migrate(null, null));
        assertEquals(0, LegacyDataMigration.migrate(dir.resolve("does-not-exist"), null));
    }

    @Test
    void logsEachMigratedFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator.json"), "{}", StandardCharsets.UTF_8);
        List<String> logged = new ArrayList<>();

        int migrated = LegacyDataMigration.migrate(dir, logged::add);

        assertEquals(1, migrated);
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("mctranslator.json"));
        assertTrue(logged.get(0).contains("nyanslate.json"));
    }
}
