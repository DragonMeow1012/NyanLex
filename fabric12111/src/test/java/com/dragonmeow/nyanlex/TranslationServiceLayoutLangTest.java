package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
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
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
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

    @Test
    void uiPrewriteOfSharedConfigStillSwitchesRuntimeCachesAndJapaneseStore(@TempDir Path dir) {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("[" + target + "] " + text, "en");
        };
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT,
                100, 10_000L, System::currentTimeMillis,
                new LanguageFileStore(dir, "nyanlex-cache", cfg.targetLang));
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT,
                100, 10_000L, System::currentTimeMillis,
                new LanguageFileStore(dir, "nyanlex-ai-cache", cfg.targetLang));
        TranslationService service = new TranslationService(cfg, google, ai);

        service.translateItemLine("Diamond Sword");
        pump(service);
        assertEquals("[zh-TW] Diamond Sword",
                service.translateItemLine("Diamond Sword").translated());

        // This is the exact order used by the broken language picker: because config is
        // shared with the service, comparing only against config.targetLang used to turn
        // the following setter call into a silent no-op.
        cfg.targetLang = "ja-JP";
        service.setTargetLang(cfg.targetLang);
        service.translateItemLine("Diamond Sword");
        pump(service);

        assertEquals("[ja-JP] Diamond Sword",
                service.translateItemLine("Diamond Sword").translated());
        assertEquals("ja-JP", service.targetLang());
        assertEquals(2, calls.get(), "the old Chinese memory entry must not survive the switch");
        assertTrue(Files.exists(dir.resolve("nyanlex-ai-cache-ja-jp.json")),
                "the first successful Japanese AI result creates the ja-JP partition");
    }
}
