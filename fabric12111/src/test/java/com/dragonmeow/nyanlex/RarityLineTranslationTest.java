package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.7 fixed rarity/type term table, exercised through the real
 * {@link TranslationService}/{@link TranslationCache} stack. Every translator, cache
 * and executor is an inline fake; no file or network is read.
 */
class RarityLineTranslationTest {

    private static final Executor DIRECT = Runnable::run;

    private static TranslationService service(TranslatorConfig cfg, Translator translator) {
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        return new TranslationService(cfg, gt, ai);
    }

    /** Two client ticks: the coalescer holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static TranslatorConfig zhTwConfig() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "zh-TW";
        return cfg;
    }

    // ---- everything in the shipped table: 0 translator calls ----

    @Test
    void fullyKnownRarityLineComposesLocallyWithZeroTranslatorCalls() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("SHOULD-NOT-BE-CALLED:" + text, "en");
        };
        TranslationService s = service(zhTwConfig(), translator);

        TranslationDecision d = s.translateItemLine("EPIC DUNGEON GLOVES");
        assertTrue(d.changed());
        assertEquals("史詩地城手套", d.translated());
        assertEquals(0, calls.get());

        // The common case — an item stays hovered for many render frames — must stay free.
        s.translateItemLine("EPIC DUNGEON GLOVES");
        s.translateItemLine("LEGENDARY"); // bare-rarity shape too
        assertEquals(0, calls.get());
    }

    @Test
    void bareRarityWithNoTypeWordAlsoComposesLocally() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(zhTwConfig(), (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("x", "en");
        });

        assertEquals("傳奇", s.translateItemLine("LEGENDARY").translated());
        assertEquals(0, calls.get());
    }

    // ---- out-of-table type word: one small unit, then local composition ----

    @Test
    void outOfTableTypeWordTriggersOneSmallRequestThenComposesLocally() {
        AtomicInteger calls = new AtomicInteger();
        List<String> requested = new ArrayList<>();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            requested.add(text);
            return new TranslationResult("未來裝甲", "en");
        };
        TranslationService s = service(zhTwConfig(), translator);

        // Not learned yet: falls through to the pre-P1.7 behaviour for this line (a
        // miss queues the whole line, same as before P1.7 existed).
        TranslationDecision beforeLearning = s.translateItemLine("EPIC FUTURE ARMOR");
        assertFalse(beforeLearning.changed());
        pump(s);

        assertTrue(requested.contains("FUTURE ARMOR"), "the out-of-table word was sent as its own small unit");
        assertEquals(2, calls.get(),
                "exactly the pre-existing whole-line fallback request plus ONE extra small unit");

        TranslationDecision learned = s.translateItemLine("EPIC FUTURE ARMOR");
        assertTrue(learned.changed());
        assertEquals("史詩未來裝甲", learned.translated(), "now composed locally from the learned word");

        // Learning must not keep re-requesting on every later render frame.
        s.translateItemLine("EPIC FUTURE ARMOR");
        pump(s);
        assertEquals(2, calls.get(), "no further requests once the word is learned");
    }

    // ---- user override wins over the shipped default ----

    @Test
    void userOverrideTakesPrecedenceOverShippedDefault() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.termOverrides.put("EPIC", "史詩級"); // user-chosen spelling
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("x", "en");
        });

        TranslationDecision d = s.translateItemLine("EPIC DUNGEON GLOVES");
        assertTrue(d.changed());
        assertEquals("史詩級地城手套", d.translated());
        assertEquals(0, calls.get());
    }

    @Test
    void lowerCaseOverrideKeyStillMatchesTheUpperCaseRarityWord() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.termOverrides.put("gloves", "戰鬥手套"); // normalized() upper-cases this to GLOVES
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("x", "en");
        });

        assertEquals("史詩地城戰鬥手套", s.translateItemLine("EPIC DUNGEON GLOVES").translated());
        assertEquals(0, calls.get());
    }

    // ---- scope: zh_TW / zh_HK only ----

    @Test
    void nonChineseTargetFallsThroughToTheOrdinaryTranslator() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "fr-FR";
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("[" + text + "]", "en");
        };
        TranslationService s = service(cfg, translator);

        s.translateItemLine("EPIC DUNGEON GLOVES");
        pump(s);
        TranslationDecision d = s.translateItemLine("EPIC DUNGEON GLOVES");
        assertTrue(d.changed());
        assertEquals("[EPIC DUNGEON GLOVES]", d.translated(),
                "whole line went through the ordinary translator, not P1.7's term table");
        assertEquals(1, calls.get());
    }

    @Test
    void simplifiedChineseTargetFallsThroughToTheOrdinaryTranslator() {
        // P1.7 is scoped to zh_TW/zh_HK; zh_CN keeps going through the ordinary path.
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "zh-CN";
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("[" + text + "]", "en");
        };
        TranslationService s = service(cfg, translator);

        s.translateItemLine("EPIC DUNGEON GLOVES");
        pump(s);
        assertEquals("[EPIC DUNGEON GLOVES]", s.translateItemLine("EPIC DUNGEON GLOVES").translated());
        assertEquals(1, calls.get());
    }

    // ---- multi-line paragraphs are untouched ----

    @Test
    void multiLineParagraphRarityLineComposesLocallyWhileTheRestIsIndependentlyRequested() {
        // Pre-2026-10-01, RarityLineComposer.match(original) only accepts the WHOLE text as
        // a pure rarity line, so one unrelated prose row glued next to it made P1.7 decline
        // entirely -- the whole blob (rarity wording included) went to the ordinary
        // whole-line translator. TooltipSegmentPlanner's partial planning fix now classifies
        // "EPIC DUNGEON GLOVES" as its own RARITY segment (composed for free from the local
        // term table, zero requests -- see resolveRaritySegment) and "Sells for 100 coins"
        // (recognised by no composer) as its own independent PROSE segment/cache key, so
        // only the genuinely new content is ever sent.
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            seen.add(text);
            return new TranslationResult(text, "en"); // echo: content is not the point here
        };
        TranslationService s = service(zhTwConfig(), translator);
        String joined = ParagraphModel.join(List.of("EPIC DUNGEON GLOVES", "Sells for 100 coins"));

        s.translateItemLine(joined);
        pump(s);

        assertEquals(1, calls.get(), "only the PROSE unit is a real request; RARITY composes free");
        assertEquals("Sells for ⟦MT0⟧ coins", seen.get(0));
        assertFalse(seen.get(0).contains("EPIC DUNGEON GLOVES"),
                "the rarity line's own wording never travels inside another unit's request");
    }

    // ---- CS structure and the recombobulated confusion letter are preserved ----

    @Test
    void recombobulatedConfusionLetterAndCsStructureArePreservedThroughTheService() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(zhTwConfig(), (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("SHOULD-NOT-BE-CALLED", "en");
        });
        String line = "⟦CS0⟧a⟦/CS0⟧ ⟦CS1⟧MYTHIC DUNGEON SWORD"
                + "⟦/CS1⟧ ⟦CS2⟧a⟦/CS2⟧";

        TranslationDecision d = s.translateItemLine(line);
        assertTrue(d.changed());
        assertEquals("⟦CS0⟧a⟦/CS0⟧ ⟦CS1⟧神話地城劍"
                + "⟦/CS1⟧ ⟦CS2⟧a⟦/CS2⟧", d.translated());
        assertEquals(0, calls.get());
    }

    // ---- master switch off: not even the one-off small unit is requested ----

    @Test
    void masterSwitchOffSendsNoRequestAtAll() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.translationRequestsEnabled = false;
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("SHOULD-NOT-HAPPEN", "en");
        };
        TranslationService s = service(cfg, translator);

        TranslationDecision d = s.translateItemLine("EPIC FUTURE ARMOR"); // FUTURE ARMOR unknown
        assertFalse(d.changed());
        pump(s);
        assertEquals(0, calls.get(), "master switch off: no request, not even the one-off small unit");
    }

    // ---- a do-not-translate term must never be overridden by local composition ----

    @Test
    void doNotTranslateTermInsideARarityLineStaysUntranslatedButRestStillComposesLocally() {
        // 1.0.7 P1 regression: composeRarityLine ran entirely BEFORE mask()/
        // doNotTranslateTerms, so the user's own "Garden" do-not-translate entry was
        // silently ignored and translated anyway via the "GARDEN CHIP" -> "花園晶片"
        // whole-phrase table entry.
        TranslatorConfig cfg = zhTwConfig();
        cfg.doNotTranslateTerms.add("Garden");
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = service(cfg, (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("SHOULD-NOT-BE-CALLED:" + text, "en");
        });

        TranslationDecision d = s.translateItemLine("EPIC GARDEN CHIP");

        assertTrue(d.changed());
        assertEquals("史詩GARDEN CHIP", d.translated(),
                "EPIC still composes locally from the table; GARDEN stays untranslated");
        assertEquals(0, calls.get(), "still a pure local composition, no translator request");
    }

    // ---- an ordinary all-caps line must not be mistaken for a rarity line ----

    @Test
    void ordinaryUppercaseTooltipLineIsNotTreatedAsARarityLine() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("右鍵點擊使用", "en");
        };
        TranslationService s = service(zhTwConfig(), translator);

        s.translateItemLine("RIGHT-CLICK TO USE");
        pump(s);
        TranslationDecision d = s.translateItemLine("RIGHT-CLICK TO USE");
        assertTrue(d.changed());
        assertEquals("右鍵點擊使用", d.translated(),
                "went through the ordinary translator exactly once, not P1.7's term table");
        assertEquals(1, calls.get());
    }
}
