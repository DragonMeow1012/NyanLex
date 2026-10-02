package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLocationsTest {
    private static final Path DIR = Path.of("C:/Users/Me/AppData/Roaming/.minecraft/config");

    private static String name(List<FileLocations.Entry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow()
                .path().getFileName().toString();
    }

    @Test
    void namesFollowTheStoresActualFileNames() {
        List<FileLocations.Entry> e = FileLocations.entries(DIR, "zh-TW");
        assertEquals("nyanlex.json", name(e, "config"));
        assertEquals("nyanlex-ai-cache-zh-tw.json", name(e, "ai_cache"));
        assertEquals("nyanlex-cache-zh-tw.json", name(e, "gt_google"));
        assertEquals("nyanlex-failures-zh-tw.json", name(e, "failures"));
        assertEquals("nyanlex-hub-cache-zh-tw.json", name(e, "hub_cache"));
        assertEquals("nyanlex-hub-state.json", name(e, "hub_state"));
        assertEquals("nyanlex-debug-log.jsonl", name(e, "debug_log"));
        assertTrue(e.stream().noneMatch(x -> x.id().equals("debug_dir") || x.id().equals("lang_probe")));
    }

    @Test
    void everyIdHasAnEntryInOrderAndTheLanguageTagFollowsTheTarget() {
        List<FileLocations.Entry> e = FileLocations.entries(DIR, "ja_JP");
        assertEquals(FileLocations.IDS, e.stream().map(FileLocations.Entry::id).toList());
        assertTrue(name(e, "ai_cache").endsWith("ja-jp.json"));
    }
}
