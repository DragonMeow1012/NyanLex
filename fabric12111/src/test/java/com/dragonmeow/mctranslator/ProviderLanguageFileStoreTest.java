package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.FileStore;
import com.dragonmeow.mctranslator.cache.ProviderLanguageFileStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderLanguageFileStoreTest {
    @TempDir Path temp;

    @Test
    void constructorEagerlyOpensTheInitialProviderPartition() {
        FileStore legacy = new FileStore(temp.resolve("mctranslator-cache.json"), false);
        legacy.put("legacy", "value");
        AtomicReference<String> provider = new AtomicReference<>("google");

        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "mctranslator-cache", "zh-TW", provider::get);

        assertEquals(1, store.retainedStoreCount());
        assertTrue(Files.isRegularFile(temp.resolve("mctranslator-cache-zh-tw.json")),
                "initial migration/load must finish before the first get call");
    }

    @Test
    void providerSwitchKeepsIndependentFilesAndRestoresPriorRows() {
        AtomicReference<String> provider = new AtomicReference<>("google");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "mctranslator-cache", "zh-TW", provider::get);
        store.put("Sword", "Google 劍");

        provider.set("youdao");
        assertNull(store.get("Sword"));
        store.put("Sword", "Youdao 劍");

        provider.set("google");
        assertEquals("Google 劍", store.get("Sword"));
        provider.set("youdao");
        assertEquals("Youdao 劍", store.get("Sword"));
        assertTrue(Files.isRegularFile(temp.resolve("mctranslator-cache-zh-tw.json")));
        assertTrue(Files.isRegularFile(temp.resolve("mctranslator-cache-youdao-zh-tw.json")));
    }

    @Test
    void clearOnlyDeletesTheActiveProviderAndLanguage() {
        AtomicReference<String> provider = new AtomicReference<>("google");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "mctranslator-cache", "zh-TW", provider::get);
        store.put("A", "G");
        provider.set("deepl");
        store.put("A", "D");
        store.clear();
        assertNull(store.get("A"));
        provider.set("google");
        assertEquals("G", store.get("A"));
    }

    @Test
    void switchingManyProvidersRetainsOnlyTheActiveStoreInMemory() {
        String[] providers = {"google", "youdao", "deepl", "microsoft"};
        AtomicReference<String> provider = new AtomicReference<>(providers[0]);
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "mctranslator-cache", "zh-TW", provider::get);
        for (int i = 0; i < providers.length; i++) {
            provider.set(providers[i]);
            store.put("key", "value-" + i);
            assertEquals(1, store.retainedStoreCount());
        }
        for (int i = 0; i < 64; i++) {
            provider.set(providers[i % providers.length]);
            assertEquals("value-" + (i % providers.length), store.get("key"));
            assertEquals(1, store.retainedStoreCount());
        }

        provider.set(providers[0]);
        assertEquals("value-0", store.get("key"));
        assertEquals(1, store.retainedStoreCount());
    }

    @Test
    void recreatingProviderDoesNotClaimLegacyCacheForANewLanguage() {
        FileStore legacy = new FileStore(temp.resolve("mctranslator-cache.json"), false);
        legacy.put("legacy", "traditional");
        AtomicReference<String> provider = new AtomicReference<>("google");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "mctranslator-cache", "zh-TW", provider::get);
        assertEquals("traditional", store.get("legacy"));

        provider.set("deepl");
        assertNull(store.get("legacy"));
        store.setLanguage("zh-CN");
        provider.set("google");

        assertNull(store.get("legacy"),
                "a recreated provider must not copy the one-time legacy cache into a new language");
        assertTrue(Files.notExists(temp.resolve("mctranslator-cache-zh-cn.json")));
    }

    @Test
    void responseStartedBeforeProviderSwitchCannotEnterTheNewPartition() throws Exception {
        AtomicReference<String> provider = new AtomicReference<>("google");
        ProviderLanguageFileStore store = new ProviderLanguageFileStore(
                temp, "mctranslator-cache", "zh-TW", provider::get);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> reply = new AtomicReference<>("舊結果");
        AtomicReference<Boolean> block = new AtomicReference<>(true);
        Translator translator = (text, target) -> {
            if (block.get()) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new TranslationException("test timeout");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new TranslationException("interrupted", interrupted);
                }
            }
            return new TranslationResult(reply.get(), "en");
        };
        TranslationCache cache = new TranslationCache(
                translator, "zh-TW", Runnable::run, 100, 0L,
                System::currentTimeMillis, store);

        FutureTask<String> oldRequest = new FutureTask<>(() -> cache.translateBlocking("Hello"));
        Thread worker = new Thread(oldRequest, "provider-switch-test");
        worker.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        provider.set("deepl");
        cache.reloadProviderPartition();
        release.countDown();
        assertEquals("舊結果", oldRequest.get(2, TimeUnit.SECONDS));
        assertNull(cache.getCached("Hello"), "stale response must not be cached after reload");

        block.set(false);
        reply.set("新結果");
        assertEquals("新結果", cache.translateBlocking("Hello"));
        assertEquals("新結果", cache.getCached("Hello"));

        provider.set("google");
        cache.reloadProviderPartition();
        assertNull(cache.getCached("Hello"), "Google partition must not receive DeepL wording");
        provider.set("deepl");
        cache.reloadProviderPartition();
        assertEquals("新結果", cache.getCached("Hello"));
    }
}
