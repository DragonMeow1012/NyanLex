package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.MachineTranslationProvider;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
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
        assertFalse(cfg.aiUseCodex);
        assertEquals("gpt-5.6-terra", cfg.codexModel);
        assertEquals("low", cfg.codexReasoningEffort);
        assertEquals(DisplayMode.BOTH, cfg.chatMode, "聊天預設 原文+翻譯");
        assertEquals(DisplayMode.TRANSLATION, cfg.tooltipMode, "其他表面預設 只有翻譯");
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
        Path path = directory.resolve("mctranslator.json");
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
        cfg.scoreboardMode = DisplayMode.ORIGINAL_ONLY;
        cfg.targetLang = "zh-TW";

        StringWriter out = new StringWriter();
        cfg.writeTo(out);

        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(out.toString()));
        assertEquals(DisplayMode.BOTH, loaded.chatMode);
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
    void emptyJsonYieldsDefaults() {
        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader("{}"));
        assertEquals("zh-TW", cfg.targetLang);
        assertEquals(DisplayMode.BOTH, cfg.chatMode);
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
