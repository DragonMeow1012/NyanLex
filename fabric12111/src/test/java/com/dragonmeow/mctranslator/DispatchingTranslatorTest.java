package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.DispatchingTranslator;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchingTranslatorTest {

    private static Translator tagging(String tag) {
        return new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult(tag + ":" + text, null);
            }
        };
    }

    private static Translator failing() {
        return new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) throws TranslationException {
                throw new TranslationException("boom");
            }
        };
    }

    @Test
    void usesPrimaryWhenEnabled() throws Exception {
        DispatchingTranslator d = new DispatchingTranslator(tagging("AI"), tagging("G"), () -> true);
        assertEquals("AI:Hi", d.translate("Hi", "zh-TW").translatedText());
    }

    @Test
    void usesFallbackWhenDisabled() throws Exception {
        DispatchingTranslator d = new DispatchingTranslator(tagging("AI"), tagging("G"), () -> false);
        assertEquals("G:Hi", d.translate("Hi", "zh-TW").translatedText());
    }

    @Test
    void fallsBackWhenPrimaryFails() throws Exception {
        DispatchingTranslator d = new DispatchingTranslator(failing(), tagging("G"), () -> true);
        assertEquals("G:Hi", d.translate("Hi", "zh-TW").translatedText());
    }

    @Test
    void batchHonoursToggleAndFallback() throws Exception {
        AtomicBoolean useAi = new AtomicBoolean(true);
        DispatchingTranslator d = new DispatchingTranslator(tagging("AI"), tagging("G"), useAi::get);
        assertEquals("AI:x", d.translateBatch(List.of("x"), "zh-TW").get(0).translatedText());
        useAi.set(false);
        assertEquals("G:x", d.translateBatch(List.of("x"), "zh-TW").get(0).translatedText());
    }

    @Test
    void fallbackProductsAreMarkedFromFallback() throws Exception {
        // A fallback-produced result carries fromFallback=true (the cache stores it as a
        // PROVISIONAL GT stand-in); a primary (AI) product never does.
        DispatchingTranslator broken = new DispatchingTranslator(failing(), tagging("G"), () -> true);
        assertTrue(broken.translate("Hi", "zh-TW").fromFallback(), "single fallback is marked");
        assertTrue(broken.translateBatch(List.of("x"), "zh-TW").get(0).fromFallback(),
                "batch fallback is marked");

        DispatchingTranslator healthy = new DispatchingTranslator(tagging("AI"), tagging("G"), () -> true);
        assertFalse(healthy.translate("Hi", "zh-TW").fromFallback(), "a primary product is FINAL");
    }
}
