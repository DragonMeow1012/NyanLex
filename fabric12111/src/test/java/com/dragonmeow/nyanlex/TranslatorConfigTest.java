package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorConfigTest {

    @Test
    void defaultsAreSensible() {
        TranslatorConfig cfg = new TranslatorConfig();
        assertEquals("zh-TW", cfg.targetLang);
        assertEquals("auto", cfg.sourceLang);
        assertEquals(MachineTranslationProvider.GOOGLE.id(), cfg.machineTranslationProvider);
        assertEquals("gemini-3.1-flash-lite", cfg.aiModel);
        assertEquals(DisplayMode.BOTH, cfg.chatMode, "聊天預設 原文+翻譯");
        assertTrue(cfg.deliverChatTranslationsInOrder);
        assertEquals(DisplayMode.TRANSLATION, cfg.tooltipMode, "其他表面預設 只有翻譯");
        assertEquals(DisplayMode.ORIGINAL_ONLY, cfg.screenTextMode, "介面文字預設不翻譯");
        assertFalse(cfg.translationRequestsEnabled, "a fresh install sends nothing until the player turns it on");
        assertFalse(cfg.debugTranslationOverlay);
        assertTrue(cfg.churnGuard, "特效字防護預設開啟");
        assertEquals(4, cfg.churnVariantThreshold);
        assertEquals(60, cfg.churnWindowSeconds);
        assertEquals(300, cfg.churnCooldownSeconds);
        assertEquals(5000, cfg.batchWindowMs);
        assertEquals(10000, cfg.requestCooldownMs, "事前冷卻安全預設 10000ms");
        assertEquals(TranslatorConfig.PACING_DEFAULTS_VERSION, cfg.pacingDefaultsVersion);
    }

    @Test
    void requestCooldownNormalizesNegativeButKeepsZero() {
        // Negative is invalid → back to the 10000ms safe default; 0 is a VALID value (pacing off).
        TranslatorConfig negative = TranslatorConfig.fromReader(
                new StringReader("{ \"requestCooldownMs\": -1 }"));
        assertEquals(10000, negative.requestCooldownMs);

        TranslatorConfig off = TranslatorConfig.fromReader(
                new StringReader("{ \"requestCooldownMs\": 0 }"));
        assertEquals(0, off.requestCooldownMs, "0 = 關閉節流，不得被回填");
    }

    @Test
    void oldUntouchedPacingDefaultMigratesExactlyOnce() {
        TranslatorConfig migrated = TranslatorConfig.fromReader(
                new StringReader("{ \"requestCooldownMs\": 6000 }"));
        assertEquals(10000, migrated.requestCooldownMs);
        assertEquals(TranslatorConfig.PACING_DEFAULTS_VERSION, migrated.pacingDefaultsVersion);

        TranslatorConfig userSelectedSixSeconds = TranslatorConfig.fromReader(new StringReader(
                "{ \"requestCooldownMs\": 6000, \"pacingDefaultsVersion\": "
                        + TranslatorConfig.PACING_DEFAULTS_VERSION + " }"));
        assertEquals(6000, userSelectedSixSeconds.requestCooldownMs,
                "6000 selected after migration must remain a user choice");
    }

    @Test
    void loadPersistsPacingMigrationBeforeLaterUserChoice(@TempDir Path directory)
            throws IOException {
        Path path = directory.resolve("nyanlex.json");
        Files.writeString(path, "{ \"requestCooldownMs\": 6000 }", StandardCharsets.UTF_8);

        TranslatorConfig migrated = TranslatorConfig.load(path);
        assertEquals(10000, migrated.requestCooldownMs);
        assertEquals(TranslatorConfig.PACING_DEFAULTS_VERSION, migrated.pacingDefaultsVersion);
        String migratedJson = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(migratedJson.contains("\"requestCooldownMs\": 10000"));
        assertTrue(migratedJson.contains("\"pacingDefaultsVersion\": 1"),
                "the one-time marker must be durable before load returns");

        migrated.requestCooldownMs = 6000;
        migrated.save(path);
        TranslatorConfig reloaded = TranslatorConfig.load(path);
        assertEquals(6000, reloaded.requestCooldownMs,
                "marker=1 makes a later 6000ms setting an explicit user choice");
        assertEquals(TranslatorConfig.PACING_DEFAULTS_VERSION, reloaded.pacingDefaultsVersion);
        String userJson = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(userJson.contains("\"requestCooldownMs\": 6000"));
        assertTrue(userJson.contains("\"pacingDefaultsVersion\": 1"));
    }

    @Test
    void pacingMigrationPreservesEveryNonLegacyChoice() {
        for (int selected : new int[]{0, 1000, 2000, 4000, 8000, 10000}) {
            TranslatorConfig loaded = TranslatorConfig.fromReader(
                    new StringReader("{ \"requestCooldownMs\": " + selected + " }"));
            assertEquals(selected, loaded.requestCooldownMs);
            assertEquals(TranslatorConfig.PACING_DEFAULTS_VERSION, loaded.pacingDefaultsVersion);
        }
    }

    @Test
    void batchWindowNormalizesNegativeButKeepsZero() {
        TranslatorConfig negative = TranslatorConfig.fromReader(
                new StringReader("{ \"batchWindowMs\": -1 }"));
        assertEquals(5000, negative.batchWindowMs);

        TranslatorConfig off = TranslatorConfig.fromReader(
                new StringReader("{ \"batchWindowMs\": 0 }"));
        assertEquals(0, off.batchWindowMs);
    }

    @Test
    void workerThreadsHasASafeUpperBound() {
        TranslatorConfig invalid = TranslatorConfig.fromReader(
                new StringReader("{ \"workerThreads\": 0 }"));
        assertEquals(2, invalid.workerThreads);

        TranslatorConfig excessive = TranslatorConfig.fromReader(
                new StringReader("{ \"workerThreads\": 1000000 }"));
        assertEquals(TranslatorConfig.MAX_WORKER_THREADS, excessive.workerThreads);
    }

    @Test
    void persistentCacheCapDefaultsAndNormalizesToOneHundredThousand() {
        assertEquals(TranslatorConfig.DEFAULT_PERSISTENT_CACHE_ENTRIES,
                new TranslatorConfig().persistentCacheMaxEntries);

        TranslatorConfig invalid = TranslatorConfig.fromReader(
                new StringReader("{ \"persistentCacheMaxEntries\": 0 }"));
        assertEquals(TranslatorConfig.DEFAULT_PERSISTENT_CACHE_ENTRIES,
                invalid.persistentCacheMaxEntries);

        TranslatorConfig excessive = TranslatorConfig.fromReader(
                new StringReader("{ \"persistentCacheMaxEntries\": 10000000 }"));
        assertEquals(TranslatorConfig.MAX_PERSISTENT_CACHE_ENTRIES,
                excessive.persistentCacheMaxEntries);
    }

    @Test
    void machineProviderNormalizesUnknownValuesToGoogle() {
        TranslatorConfig valid = TranslatorConfig.fromReader(
                new StringReader("{ \"machineTranslationProvider\": \"deepl\" }"));
        assertEquals(MachineTranslationProvider.DEEPL.id(), valid.machineTranslationProvider);

        TranslatorConfig invalid = TranslatorConfig.fromReader(
                new StringReader("{ \"machineTranslationProvider\": \"baidu\" }"));
        assertEquals(MachineTranslationProvider.GOOGLE.id(), invalid.machineTranslationProvider);
    }

    @Test
    void churnFieldsNormalizeInvalidValues() {
        String json = "{ \"churnVariantThreshold\": 1, \"churnWindowSeconds\": 0, \"churnCooldownSeconds\": -3 }";
        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(json));
        assertEquals(4, cfg.churnVariantThreshold);
        assertEquals(60, cfg.churnWindowSeconds);
        assertEquals(300, cfg.churnCooldownSeconds);
    }

    @Test
    void roundTripsThroughJson() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.BOTH;
        cfg.deliverChatTranslationsInOrder = false;
        cfg.scoreboardMode = DisplayMode.ORIGINAL_ONLY;
        cfg.targetLang = "zh-TW";

        StringWriter out = new StringWriter();
        cfg.writeTo(out);

        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(out.toString()));
        assertEquals(DisplayMode.BOTH, loaded.chatMode);
        assertFalse(loaded.deliverChatTranslationsInOrder);
        assertEquals(DisplayMode.ORIGINAL_ONLY, loaded.scoreboardMode);
        assertEquals("zh-TW", loaded.targetLang);
    }

    @Test
    void normalizesMissingAndInvalidFields() {
        String json = "{ \"targetLang\": \"\", \"httpTimeoutMs\": -5, \"cacheMaxSize\": 0 }";
        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(json));

        assertEquals("zh-TW", cfg.targetLang);
        assertEquals("auto", cfg.sourceLang);
        assertEquals(DisplayMode.BOTH, cfg.chatMode);
        assertTrue(cfg.httpTimeoutMs > 0);
        assertTrue(cfg.cacheMaxSize > 0);
    }

    @Test
    void clampsMemoryCacheToSafeMaximum() {
        TranslatorConfig cfg = TranslatorConfig.fromReader(
                new StringReader("{ \"cacheMaxSize\": 2147483647 }"));

        assertEquals(TranslatorConfig.MAX_MEMORY_CACHE_ENTRIES, cfg.cacheMaxSize);
    }

    @Test
    void emptyJsonYieldsDefaults() {
        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader("{}"));
        assertEquals("zh-TW", cfg.targetLang);
        assertEquals(DisplayMode.BOTH, cfg.chatMode);
        assertTrue(cfg.deliverChatTranslationsInOrder);
    }

    @Test
    void requestSwitchIsOffOnAFreshInstallButAnExistingConfigKeepsItsBehaviour() {
        assertFalse(new TranslatorConfig().translationRequestsEnabled, "fresh install: 送出翻譯請求 預設關閉");
        assertTrue(TranslatorConfig.fromReader(new StringReader("{ \"targetLang\": \"zh-TW\" }"))
                        .translationRequestsEnabled,
                "an old config file without the key was already translating: it keeps sending requests");
        assertFalse(TranslatorConfig.fromReader(new StringReader("{ \"translationRequestsEnabled\": false }"))
                .translationRequestsEnabled, "a stored false stays false");
        assertTrue(TranslatorConfig.fromReader(new StringReader("{ \"translationRequestsEnabled\": true }"))
                .translationRequestsEnabled, "a stored true stays true");
        // an existing config keeps its stored display modes too
        TranslatorConfig stored = TranslatorConfig.fromReader(new StringReader(
                "{ \"chatMode\": \"TRANSLATION\", \"tooltipMode\": \"ORIGINAL_ONLY\","
                        + " \"screenTextMode\": \"BOTH\", \"translationRequestsEnabled\": false }"));
        assertEquals(DisplayMode.TRANSLATION, stored.chatMode);
        assertEquals(DisplayMode.ORIGINAL_ONLY, stored.tooltipMode);
        assertEquals(DisplayMode.BOTH, stored.screenTextMode);

        TranslatorConfig cfg = new TranslatorConfig();
        cfg.translationRequestsEnabled = false;
        StringWriter out = new StringWriter();
        cfg.writeTo(out);
        assertTrue(out.toString().contains("\"translationRequestsEnabled\": false"), out.toString());
        assertFalse(TranslatorConfig.fromReader(new StringReader(out.toString()))
                .translationRequestsEnabled);
    }

    @Test
    void doNotTranslateTermsNormalizeAndRoundTrip() {
        TranslatorConfig empty = TranslatorConfig.fromReader(new StringReader("{}"));
        assertEquals(java.util.List.of(), empty.doNotTranslateTerms);
        TranslatorConfig nullList = TranslatorConfig.fromReader(
                new StringReader("{ \"doNotTranslateTerms\": null }"));
        assertEquals(java.util.List.of(), nullList.doNotTranslateTerms, "null becomes an empty list");

        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(
                "{ \"doNotTranslateTerms\": [\"  SkyBlock \", \"\", null, \"skyblock\","
                        + " \"Hypixel\", \"SKYBLOCK\", \"  \", \"Dungeon Hub\"] }"));
        assertEquals(java.util.List.of("SkyBlock", "Hypixel", "Dungeon Hub"), cfg.doNotTranslateTerms,
                "trimmed, blanks dropped, case-insensitive duplicates keep the first spelling");
        cfg.doNotTranslateTerms.add("Bazaar"); // the normalized list stays editable

        StringWriter out = new StringWriter();
        cfg.writeTo(out);
        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(out.toString()));
        assertEquals(java.util.List.of("SkyBlock", "Hypixel", "Dungeon Hub", "Bazaar"),
                loaded.doNotTranslateTerms);
        assertEquals(java.util.List.of("a"),
                TranslatorConfig.normalizedTerms(java.util.Arrays.asList("a", "A", " a ")));
        assertEquals(java.util.List.of(), TranslatorConfig.normalizedTerms(null));
    }

    @Test
    void termOverridesNormalizeAndRoundTrip() {
        TranslatorConfig empty = TranslatorConfig.fromReader(new StringReader("{}"));
        assertEquals(java.util.Map.of(), empty.termOverrides, "default is an empty map");

        TranslatorConfig nullMap = TranslatorConfig.fromReader(
                new StringReader("{ \"termOverrides\": null }"));
        assertEquals(java.util.Map.of(), nullMap.termOverrides, "null becomes an empty map");

        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(
                "{ \"termOverrides\": { \"  epic \": \" 史詩級 \", \"\": \"x\","
                        + " \"DUNGEON\": \"\", \"pet item\": \"寵物道具\" } }"));
        assertEquals(java.util.Map.of("EPIC", "史詩級", "PET ITEM", "寵物道具"), cfg.termOverrides,
                "keys trimmed+upper-cased, blank key/value entries dropped");
        cfg.termOverrides.put("BOOTS", "戰鬥靴"); // the normalized map stays editable

        StringWriter out = new StringWriter();
        cfg.writeTo(out);
        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(out.toString()));
        assertEquals(java.util.Map.of("EPIC", "史詩級", "PET ITEM", "寵物道具", "BOOTS", "戰鬥靴"),
                loaded.termOverrides);

        assertEquals(java.util.Map.of("A", "b"),
                TranslatorConfig.normalizedTermOverrides(java.util.Map.of(" a ", " b ")));
        assertEquals(java.util.Map.of(), TranslatorConfig.normalizedTermOverrides(null));
    }

    @Test
    void legacyRemovedFieldsInOldJsonAreIgnored() {
        // Old configs carry heldMode/aiHeld (now merged into tooltipMode/aiTooltip) and
        // blockSeparator (dead code, removed). Loading must neither crash nor leak them.
        String json = "{ \"heldMode\": \"ORIGINAL_ONLY\", \"aiHeld\": true,"
                + " \"blockSeparator\": \" | \", \"tooltipMode\": \"BOTH\" }";
        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(json));

        assertEquals(DisplayMode.BOTH, cfg.tooltipMode, "the surviving merged field must load");
        assertFalse(cfg.aiTooltip, "the legacy aiHeld flag must not bleed into aiTooltip");
        assertEquals("zh-TW", cfg.targetLang, "the rest of the config normalizes as usual");
    }
}
