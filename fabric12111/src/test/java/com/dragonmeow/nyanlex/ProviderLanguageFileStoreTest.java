package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.FileStore;
import com.dragonmeow.nyanlex.cache.ProviderLanguageFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderLanguageFileStoreTest {
    @TempDir Path temp;

    @Test
    void constructorEagerlyOpensTheGooglePartition() {
        FileStore legacy = new FileStore(temp.resolve("nyanlex-cache.json"), false);
        legacy.put("legacy", "value");
        AtomicReference<String> provider = new AtomicReference<>("google");

        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "nyanlex-cache", "zh-TW", provider::get);

        assertEquals(1, store.retainedStoreCount());
        assertTrue(Files.isRegularFile(temp.resolve("nyanlex-cache-zh-tw.json")),
                "initial migration/load must finish before the first get call");
    }

    @Test
    void removedSourceIdsKeepReadingTheGoogleFile() {
        AtomicReference<String> provider = new AtomicReference<>("google");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "nyanlex-cache", "zh-TW", provider::get);
        store.put("Sword", "Google 劍");

        for (String old : new String[] {"deepl_api", "microsoft_api", "youdao", ""}) {
            provider.set(old);
            assertEquals("Google 劍", store.get("Sword"), old);
            assertEquals(1, store.retainedStoreCount());
        }
        assertTrue(Files.isRegularFile(temp.resolve("nyanlex-cache-zh-tw.json")));
        assertTrue(Files.notExists(temp.resolve("nyanlex-cache-deepl_api-zh-tw.json")));
    }

    @Test
    void clearDeletesOnlyTheActiveLanguage() {
        AtomicReference<String> provider = new AtomicReference<>("google");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "nyanlex-cache", "zh-TW", provider::get);
        store.put("A", "G");
        store.clear();
        assertNull(store.get("A"));
    }

    @Test
    void languageSwitchDoesNotClaimTheLegacyCacheTwice() {
        FileStore legacy = new FileStore(temp.resolve("nyanlex-cache.json"), false);
        legacy.put("legacy", "traditional");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "nyanlex-cache", "zh-TW", () -> "google");
        assertEquals("traditional", store.get("legacy"));

        store.setLanguage("zh-CN");
        assertNull(store.get("legacy"),
                "the one-time legacy cache must not be copied into a new language");
    }
}
