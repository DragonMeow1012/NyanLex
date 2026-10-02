package com.dragonmeow.nyanlex.config;

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
        assertTrue(Files.exists(dir.resolve("nyanlex.json")));
        assertEquals("{\"targetLang\":\"zh-tw\"}",
                Files.readString(dir.resolve("nyanlex.json"), StandardCharsets.UTF_8));
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
        assertTrue(Files.exists(dir.resolve("nyanlex-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-ai-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-failures-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-hub-cache-zh-tw.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-hub-state.json")));
    }

    @Test
    void handlesLegacyTreeConfigFileNames(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator-legacy.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-forge-legacy.json"), "{}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(2, migrated);
        assertTrue(Files.exists(dir.resolve("nyanlex-legacy.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-forge-legacy.json")));
    }

    @Test
    void doesNotOverwriteExistingNewFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator.json"), "{\"old\":true}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanlex.json"), "{\"new\":true}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertEquals("{\"new\":true}",
                Files.readString(dir.resolve("nyanlex.json"), StandardCharsets.UTF_8));
    }

    @Test
    void skipsCodexHomeAndWorkspaceDirectories(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("mctranslator-codex-home"));
        Files.createDirectory(dir.resolve("mctranslator-codex-workspace"));
        Files.writeString(dir.resolve("mctranslator-codex-home").resolve("auth.json"), "secret",
                StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertFalse(Files.exists(dir.resolve("nyanlex-codex-home")));
        assertFalse(Files.exists(dir.resolve("nyanlex-codex-workspace")));
    }

    @Test
    void ignoresUnrelatedAndNonBoundaryPrefixedFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("other-mod.json"), "{}", StandardCharsets.UTF_8);
        // "mctranslatorx..." is not a "mctranslator"+("-"|".json") boundary match.
        Files.writeString(dir.resolve("mctranslatorx-cache.json"), "{}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertFalse(Files.exists(dir.resolve("nyanlex-x-cache.json")));
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
        assertTrue(logged.get(0).contains("nyanlex.json"));
    }

    // --- two earlier generations ("nyanslate" newer, "mctranslator" older) ---

    @Test
    void newerGenerationWinsWhenBothGenerationsExist(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator.json"), "{\"gen\":\"oldest\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanslate.json"), "{\"gen\":\"newer\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("mctranslator-cache-zh-tw.json"), "oldest-cache", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanslate-cache-zh-tw.json"), "newer-cache", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(2, migrated);
        assertEquals("{\"gen\":\"newer\"}",
                Files.readString(dir.resolve("nyanlex.json"), StandardCharsets.UTF_8));
        assertEquals("newer-cache",
                Files.readString(dir.resolve("nyanlex-cache-zh-tw.json"), StandardCharsets.UTF_8));
        // Neither earlier generation is touched.
        assertTrue(Files.exists(dir.resolve("mctranslator.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate.json")));
    }

    @Test
    void migratesWhenOnlyTheNewerGenerationExists(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("nyanslate.json"), "{\"gen\":\"newer\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanslate-hub-state.json"), "state", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(2, migrated);
        assertTrue(Files.exists(dir.resolve("nyanlex.json")));
        assertTrue(Files.exists(dir.resolve("nyanlex-hub-state.json")));
        assertTrue(Files.exists(dir.resolve("nyanslate.json")));
    }

    @Test
    void migratesWhenOnlyTheOlderGenerationExists(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator-ai-cache-zh-tw.json"), "ai", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(1, migrated);
        assertEquals("ai", Files.readString(dir.resolve("nyanlex-ai-cache-zh-tw.json"), StandardCharsets.UTF_8));
    }

    @Test
    void existingCurrentFileBlocksBothGenerations(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mctranslator.json"), "{\"gen\":\"oldest\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanslate.json"), "{\"gen\":\"newer\"}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("nyanlex.json"), "{\"gen\":\"current\"}", StandardCharsets.UTF_8);

        int migrated = LegacyDataMigration.migrate(dir, null);

        assertEquals(0, migrated);
        assertEquals("{\"gen\":\"current\"}",
                Files.readString(dir.resolve("nyanlex.json"), StandardCharsets.UTF_8));
    }

    @Test
    void skipsNewerGenerationCodexHomeDirectory(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("nyanslate-codex-home"));
        Files.writeString(dir.resolve("nyanslate-codex-home").resolve("auth.json"), "secret",
                StandardCharsets.UTF_8);

        assertEquals(0, LegacyDataMigration.migrate(dir, null));
        assertFalse(Files.exists(dir.resolve("nyanlex-codex-home")));
    }
}
