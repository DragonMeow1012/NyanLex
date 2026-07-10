package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslatorConfigTest {

    @Test
    void defaultsAreSensible() {
        TranslatorConfig cfg = new TranslatorConfig();
        assertEquals("zh-TW", cfg.targetLang);
        assertEquals("auto", cfg.sourceLang);
        assertEquals("gemini-3.1-flash-lite", cfg.aiModel);
        assertEquals(DisplayMode.BOTH, cfg.chatMode, "聊天預設 原文+翻譯");
        assertEquals(DisplayMode.TRANSLATION, cfg.tooltipMode, "其他表面預設 只有翻譯");
        assertFalse(cfg.debugTranslationOverlay);
        assertTrue(cfg.churnGuard, "特效字防護預設開啟");
        assertEquals(4, cfg.churnVariantThreshold);
        assertEquals(60, cfg.churnWindowSeconds);
        assertEquals(300, cfg.churnCooldownSeconds);
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
