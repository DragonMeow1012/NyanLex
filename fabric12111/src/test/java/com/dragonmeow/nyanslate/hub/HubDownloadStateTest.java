package com.dragonmeow.nyanslate.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the review finding A5: "清除倉庫翻譯" must be able to make a source
 *  re-downloadable even when the upstream content (and therefore its sha256) never
 *  changed, by dropping the throttling ledger entries for that language. */
class HubDownloadStateTest {

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanslate-hub-download-state-test");
    }

    @Test
    void forgetLanguageDropsEveryRecordedShaForThatLanguageOnly() throws IOException {
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));
        HubSource server = HubSource.server("hypixel.net");
        HubSource mod = HubSource.mod("somemod");
        state.record(server, "zh-TW", "sha-server-zh");
        state.record(mod, "zh-TW", "sha-mod-zh");
        state.record(server, "en-US", "sha-server-en");

        state.forgetLanguage("zh-TW");

        assertNull(state.sha256(server, "zh-TW"), "zh-TW server entry must be dropped");
        assertNull(state.sha256(mod, "zh-TW"), "zh-TW mod entry must be dropped");
        assertEquals("sha-server-en", state.sha256(server, "en-US"),
                "a different language's ledger must be untouched");
    }

    @Test
    void forgetLanguagePersistsAcrossReload() throws IOException {
        Path file = tempDir().resolve("state.json");
        HubSource server = HubSource.server("hypixel.net");
        HubDownloadState first = new HubDownloadState(file);
        first.record(server, "zh-TW", "sha-1");
        first.forgetLanguage("zh-TW");

        HubDownloadState reloaded = new HubDownloadState(file);
        assertNull(reloaded.sha256(server, "zh-TW"));
    }

    @Test
    void forgetLanguageIsANoOpWhenNothingRecorded() throws IOException {
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));
        // Must not throw and must not create garbage entries.
        state.forgetLanguage("zh-TW");
        assertNull(state.sha256(HubSource.server("x"), "zh-TW"));
    }

    @Test
    void forgetLanguageNormalizesLanguageTagLikeRecord() throws IOException {
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));
        HubSource server = HubSource.server("hypixel.net");
        state.record(server, "zh_tw", "sha-1");

        state.forgetLanguage("zh-TW");

        assertNull(state.sha256(server, "zh_tw"), "differently-cased/formatted language tags must hit the same ledger entry");
    }
}
