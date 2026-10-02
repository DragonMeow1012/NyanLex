package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two 1.0.7 regressions found by replaying the player's real cache (impl-R-realdata F1/F3),
 * reproduced here with synthetic inline data only: a synthetic player name, invented rows,
 * inline translators and a direct executor.
 */
class RealDataRegressionTest {

    private static final Executor DIRECT = Runnable::run;

    private static Translator counting(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    private static void pump(TranslationService service) {
        for (int i = 0; i < 3; i++) service.flushBatches();
    }

    // ---- R1: colour chat with a cached exact projection AND a differently worded plain row ----

    @Test
    void cachedExactColourRowIsTheOnlyFinalChatDeliveryWhetherSwitchedOnOrOff() {
        String marked = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧brave world⟦/CS1⟧";
        String exact = "⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧勇敢的世界⟦/CS1⟧";
        for (boolean switchOn : new boolean[] {false, true}) {
            TranslatorConfig cfg = new TranslatorConfig();
            cfg.chatMode = DisplayMode.TRANSLATION;
            cfg.aiChat = true;
            cfg.churnGuard = false;
            cfg.translationRequestsEnabled = switchOn;
            AtomicInteger calls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
            ai.importTranslations(Map.of(
                    "Hello brave world", "哈囉，勇敢的世界",   // plain semantic row, other wording
                    marked, exact));                           // exact colour projection
            TranslationService s = new TranslationService(cfg, gt, ai);
            List<String> texts = new ArrayList<>();
            List<Boolean> finals = new ArrayList<>();

            s.translateChatAsyncDetailed(marked, result -> {
                texts.add(result.text());
                finals.add(result.finalResult());
            });
            pump(s);

            assertEquals(List.of(exact), texts,
                    "the cached exact projection is shown, never the approximate fallback (on=" + switchOn + ")");
            assertEquals(List.of(true), finals, "delivered once, as final (on=" + switchOn + ")");
            assertEquals(0, calls.get(), "a cache hit sends nothing (on=" + switchOn + ")");
        }
    }

    // ---- R2: a protected name between short words must not turn the line into a "machine code" ----

    @Test
    void dungeonClassLabelAroundAPlayerNameStillShowsItsCachedTranslation() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.churnGuard = false;
        AtomicInteger calls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        ai.importTranslations(Map.of(
                "[B] ⟦0⟧ [Lv27]", "[狂戰士] ⟦0⟧ [等級27]",
                "⟦MT0⟧ ⟦0⟧: at p5!", "⟦MT0⟧ ⟦0⟧：在 p5！"));
        TranslationService s = new TranslationService(cfg, gt, ai);
        s.setProtectedNames(() -> Set.of("Qzaa"));

        assertEquals("[狂戰士] Qzaa [等級27]", s.translateChat("[B] Qzaa [Lv27]").translated(),
                "1.0.6 showed this cached row; the name must not glue B + Lv27 into a code");
        assertEquals("[MVP+] Qzaa：在 p5！", s.translateChat("[MVP+] Qzaa: at p5!").translated(),
                "nor \"at\" + \"p5\" into \"atp5\"");
        assertEquals("[狂戰士] Qzaa [等級27]", TextFilter.stripStyleFallback(s.translateChat(
                "⟦CS0⟧[B]⟦/CS0⟧ ⟦CS1⟧Qzaa⟦/CS1⟧ ⟦CS2⟧[Lv27]⟦/CS2⟧").translated()),
                "the colour-marked variant reads the same semantic row");
        assertEquals(0, calls.get());
    }

    @Test
    void doNotTranslateTermPlusAShortWordIsStillTranslatedButNotAMachineCode() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.churnGuard = false;
        cfg.doNotTranslateTerms = new ArrayList<>(List.of("SkyBlock"));
        AtomicInteger calls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        ai.importTranslations(Map.of("⟦0⟧ XP", "⟦0⟧ 經驗", "⟦0⟧ n6400", "⟦0⟧ 伺服器"));
        TranslationService s = new TranslationService(cfg, gt, ai);

        assertEquals("SkyBlock 經驗", s.translateChat("SkyBlock XP").translated(),
                "the 1.0.7 fix stays: \"⟦0⟧ XP\" is not the machine code \"0XP\"");
        assertFalse(s.translateChat("SkyBlock n6400").changed(),
                "a protected term next to one server code is still not prose");
        assertFalse(s.translateChat("SkyBlock").changed());
        assertEquals(0, calls.get());
    }

    @Test
    void protectedNameSplitsShortMachineCodeDetectionOnlyWhereItSits() {
        // Placeholder lines: words on both sides of the name are prose, as in 1.0.6.
        assertTrue(TextFilter.shouldTranslate("[B] ⟦0⟧ [Lv27]", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("[H] ⟦0⟧ D5,000✦", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("[MVP+] ⟦0⟧: at p5!", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("⟦0⟧ XP", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("⟦0⟧ XP: 1,250", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("Hi ⟦0⟧", "zh-TW"));
        // Only a protected term, or a protected term plus ONE short code, stays untranslated.
        assertFalse(TextFilter.shouldTranslate("⟦0⟧", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("⟦0⟧ n6400", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("⟦0⟧ ⟦PB0⟧ ⟦1⟧", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("[MVP+] ⟦0⟧: 33", "zh-TW"));
        // Lines without a protected name keep the unchanged machine-code rule.
        assertFalse(TextFilter.shouldTranslate("[Lv27]", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("B Lv27", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("HP 100 ⟦PB0⟧ 50", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("B ⟦PB0⟧ Lv27", "zh-TW"),
                "a paragraph break is not a protected name");
    }

    @Test
    void coordinateRowAroundAProtectedNameStaysAMachineRow() {
        // 1.0.6 never sent these; the R2 exception covers only the glued-code rule, so a row of
        // single letters among several numbers keeps the slot rule and is never requested.
        assertFalse(TextFilter.shouldTranslate("⟦0⟧: x: 8, y: 9, z: 10", "zh-TW"));
        assertFalse(TextFilter.shouldTranslate("⟦0⟧ x 8 y 9", "zh-TW"));
        // Without several generated numbers the words around the names stay prose.
        assertTrue(TextFilter.shouldTranslate("⟦0⟧ ⟦1⟧ a b", "zh-TW"));
        assertTrue(TextFilter.shouldTranslate("⟦0⟧: hi o/", "zh-TW"));

        TranslatorConfig cfg = new TranslatorConfig();
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiChat = true;
        cfg.churnGuard = false;
        AtomicInteger calls = new AtomicInteger();
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, gt, ai);
        s.setProtectedNames(() -> Set.of("Qzaa"));

        List<String> shown = new ArrayList<>();
        s.translateChatAsync("Qzaa: x: 8, y: 9, z: 10", shown::add);
        pump(s);
        assertFalse(s.translateChat("Qzaa: x: 8, y: 9, z: 10").changed());
        assertEquals(0, calls.get(), "no request is bought for a coordinate share");
    }
}
