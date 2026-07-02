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
        assertEquals(DisplayMode.BOTH, cfg.chatMode, "聊天預設 原文+翻譯");
        assertEquals(DisplayMode.TRANSLATION, cfg.tooltipMode, "其他表面預設 只有翻譯");
        assertTrue(cfg.pretranslateItemsOnLoad);
    }

    @Test
    void roundTripsThroughJson() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.BOTH;
        cfg.scoreboardMode = DisplayMode.ORIGINAL_ONLY;
        cfg.targetLang = "zh-TW";
        cfg.blockSeparator = " | ";

        StringWriter out = new StringWriter();
        cfg.writeTo(out);

        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(out.toString()));
        assertEquals(DisplayMode.BOTH, loaded.chatMode);
        assertEquals(DisplayMode.ORIGINAL_ONLY, loaded.scoreboardMode);
        assertEquals("zh-TW", loaded.targetLang);
        assertEquals(" | ", loaded.blockSeparator);
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
}
