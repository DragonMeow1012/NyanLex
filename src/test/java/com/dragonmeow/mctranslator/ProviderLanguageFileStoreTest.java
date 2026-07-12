package com.dragonmeow.mctranslator;

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
