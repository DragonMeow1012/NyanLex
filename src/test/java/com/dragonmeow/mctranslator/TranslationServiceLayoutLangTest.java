package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Service-level checks for whitespace preservation and runtime language switching. */
class TranslationServiceLayoutLangTest {

    private static final Executor DIRECT = Runnable::run;

    /** Inline translator: strips surrounding whitespace (like Google) and varies by target. */
    private static Translator inline() {
        return (text, target) -> {
            String t = text.strip();
            boolean simplified = target != null && target.toLowerCase().startsWith("zh-cn");
            String out = switch (t) {
                case "Diamond Sword" -> simplified ? "钻石剑" : "鑽石劍";
                case "WEBSITE" -> simplified ? "网址" : "網址";
                default -> "[" + t + "]";
            };
            return new TranslationResult(out, "en");
        };
    }

    private static TranslationService service(TranslatorConfig cfg) {
        TranslationCache cache = new TranslationCache(inline(), cfg.targetLang, DIRECT, 100);
        TranslationCache aiCache = new TranslationCache(inline(), cfg.targetLang, DIRECT, 100);
        return new TranslationService(cfg, cache, aiCache);
    }

    /** Simulate two client ticks: the coalescer holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    @Test
    void translationKeepsOriginalIndentation() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg);

        String indented = "    WEBSITE";
        s.translateItemLine(indented);             // queue the miss
        pump(s);                                   // batch goes out (DIRECT executor)
        TranslationDecision d = s.translateItemLine(indented);
        assertEquals("    網址", d.translated(), "leading spaces of the original must be kept");
    }

    @Test
    void setTargetLangRetranslatesIntoTheNewLanguage() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        TranslationService s = service(cfg);

        s.translateItemLine("Diamond Sword");      // queue zh-TW
        pump(s);
        assertEquals("鑽石劍", s.translateItemLine("Diamond Sword").translated());

        s.setTargetLang("zh-CN");                   // switch without deleting another language
        s.translateItemLine("Diamond Sword");       // queue zh-CN
        pump(s);
        assertEquals("钻石剑", s.translateItemLine("Diamond Sword").translated());
        assertEquals("zh-CN", s.targetLang());
    }
}
