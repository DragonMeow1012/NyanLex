package com.dragonmeow.nyanslate.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubIndexTest {

    @Test
    void emptyIndexHasNoEntries() {
        HubIndex index = HubIndex.empty();
        assertNull(index.server("hypixel.net", "zh-TW"));
        assertTrue(index.modIds().isEmpty());
    }

    @Test
    void withEntriesAreImmutableCopies() {
        HubIndex empty = HubIndex.empty();
        HubIndex.LanguageStats stats = new HubIndex.LanguageStats(100, 2048, "abc123", "2026-10-01T00:00:00Z");
        HubIndex withServer = empty.withServerEntry("hypixel.net", "zh-TW", stats);

        assertNull(empty.server("hypixel.net", "zh-TW"), "original index must stay unchanged");
        assertEquals(stats, withServer.server("hypixel.net", "zh-TW"));
        // Language is normalized the same way everywhere (LanguageFileStore.languageTag).
        assertEquals(stats, withServer.server("hypixel.net", "zh_tw"));
    }

    @Test
    void modpackAndModEntriesAreIndependentTables() {
        HubIndex index = HubIndex.empty()
                .withModpackEntry("my-pack", "en-us", new HubIndex.LanguageStats(10, 100, "sha-a", null))
                .withModEntry("somemod", "en-us", new HubIndex.LanguageStats(20, 200, "sha-b", null));

        assertEquals(10, index.modpack("my-pack", "en-us").rows());
        assertEquals(20, index.mod("somemod", "en-us").rows());
        assertNull(index.modpack("somemod", "en-us"));
        assertTrue(index.modIds().contains("somemod"));
    }

    @Test
    void writeThenReadRoundTrips() throws IOException {
        HubIndex index = HubIndex.empty()
                .withServerEntry("hypixel.net", "zh-tw",
                        new HubIndex.LanguageStats(8421, 512344, "deadbeef", "2026-10-01T00:00:00Z"))
                .withModEntry("somemod", "zh-tw", new HubIndex.LanguageStats(5, 50, "sha-c", null));

        StringWriter writer = new StringWriter();
        index.write(writer);
        HubIndex roundTripped = HubIndex.read(new StringReader(writer.toString()));

        HubIndex.LanguageStats server = roundTripped.server("hypixel.net", "zh-tw");
        assertEquals(8421, server.rows());
        assertEquals(512344, server.bytes());
        assertEquals("deadbeef", server.sha256());
        assertEquals(5, roundTripped.mod("somemod", "zh-tw").rows());
    }

    @Test
    void malformedJsonFailsWithIOException() {
        org.junit.jupiter.api.function.Executable attempt =
                () -> HubIndex.read(new StringReader("not json"));
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, attempt);
    }
}
