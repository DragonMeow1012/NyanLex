package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.service.TranslationDecision;
import com.dragonmeow.nyanslate.service.TranslationService;
import com.dragonmeow.nyanslate.translate.ParagraphModel;
import com.dragonmeow.nyanslate.translate.TextFilter;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.dragonmeow.nyanslate.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 enchant-list tooltip decomposition (see {@link
 * com.dragonmeow.nyanslate.translate.EnchantListComposer}), exercised through the real
 * {@link TranslationService}/{@link TranslationCache} stack. Every translator, cache and
 * executor is an inline fake; no file or network is read.
 */
class EnchantListTranslationTest {

    private static final Executor DIRECT = Runnable::run;

    private static TranslationService service(TranslatorConfig cfg, Translator translator) {
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        return new TranslationService(cfg, gt, ai);
    }

    private static TranslatorConfig zhTwConfig() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "zh-TW";
        return cfg;
    }

    private static final String ROW0 = "Soul Eater V, Toxophilite IV, Chance IV";
    private static final String ROW1 = "Cubism V, Power VI, Snipe III";
    private static final String PARAGRAPH = ParagraphModel.join(List.of(ROW0, ROW1));

    private static final Map<String, String> DICTIONARY = new HashMap<>();

    static {
        DICTIONARY.put("Soul Eater", "噬魂者");
        DICTIONARY.put("Toxophilite", "弓箭精通");
        DICTIONARY.put("Chance", "機會");
        DICTIONARY.put("Cubism", "立方");
        DICTIONARY.put("Power", "力量");
        DICTIONARY.put("Snipe", "狙擊");
    }

    /** Inline translator: answers each request from the fixed dictionary above (falling
     *  back to a tagged echo for anything else), recording every request it ever
     *  receives and its surface context, 1:1 aligned. */
    private static final class DictionaryTranslator implements Translator {
        final List<String> requested = new ArrayList<>();
        final List<List<String>> contexts = new ArrayList<>();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requested.add(text);
            contexts.add(null);
            return new TranslationResult(answer(text), "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                       List<String> surfaceContext) {
            requested.addAll(texts);
            List<String> context = surfaceContext == null ? null : new ArrayList<>(surfaceContext);
            for (int i = 0; i < texts.size(); i++) contexts.add(context);
            List<TranslationResult> out = new ArrayList<>();
            for (String text : texts) out.add(new TranslationResult(answer(text), "en"));
            return out;
        }

        private String answer(String text) {
            String value = DICTIONARY.get(text);
            return value != null ? value : "T:" + text;
        }
    }

    // ---- two-row paragraph decomposes into 6 components, then composes locally ----

    @Test
    void twoRowParagraphDecomposesIntoSixRequestsThenComposesLocally() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);

        TranslationDecision first = s.translateItemLine(PARAGRAPH);
        assertFalse(first.changed(), "not ready on the very first frame: nothing cached yet");
        assertEquals(6, translator.requested.size(), "all 6 enchant names were requested");
        assertEquals(List.of("Soul Eater", "Toxophilite", "Chance", "Cubism", "Power", "Snipe"),
                translator.requested);

        TranslationDecision ready = s.translateItemLine(PARAGRAPH);
        assertTrue(ready.changed());
        String expectedRow0 = "噬魂者 V, 弓箭精通 IV, 機會 IV";
        String expectedRow1 = "立方 V, 力量 VI, 狙擊 III";
        assertEquals(ParagraphModel.join(List.of(expectedRow0, expectedRow1)), ready.translated());

        // Hovering the SAME item again must cost nothing further.
        s.translateItemLine(PARAGRAPH);
        assertEquals(6, translator.requested.size(), "fully composed: no further requests");
    }

    @Test
    void sameEnchantNameAcrossTwoItemsIsRequestedOnlyOnce() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);
        String itemA = ParagraphModel.join(List.of("Soul Eater V, Chance IV", "Cubism V, Power VI"));
        String itemB = ParagraphModel.join(List.of("Chance V, Snipe III", "Power I, Cubism II"));

        s.translateItemLine(itemA);
        s.translateItemLine(itemB);

        // Distinct names across BOTH items: Soul Eater, Chance, Cubism, Power, Snipe = 5.
        assertEquals(5, translator.requested.size());
        assertEquals(1, Collections.frequency(translator.requested, "Chance"));
        assertEquals(1, Collections.frequency(translator.requested, "Cubism"));
        assertEquals(1, Collections.frequency(translator.requested, "Power"));
    }

    @Test
    void everyNameAlreadyCachedSendsZeroRequestsAndComposesImmediately() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);
        s.translateItemLine(PARAGRAPH); // buys all 6 names
        int boughtSoFar = translator.requested.size();
        assertEquals(6, boughtSoFar);

        // A brand-new item reusing the SAME 6 enchant names in a different order/combo --
        // exactly the reordering scenario the reported bug was about.
        String differentCombo = ParagraphModel.join(
                List.of("Chance IV, Cubism V, Snipe III", "Power VI, Soul Eater V, Toxophilite IV"));
        TranslationDecision d = s.translateItemLine(differentCombo);

        assertTrue(d.changed(), "every name was already learned: composes on the FIRST frame");
        assertEquals(boughtSoFar, translator.requested.size(), "0 new requests: everything was cached");
    }

    // ---- surface context: a new enchant name's request carries the list row ----

    @Test
    void requestForANewEnchantNameCarriesTheEnchantListRowAsContext() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);

        s.translateItemLine(PARAGRAPH);

        int chanceIndex = translator.requested.indexOf("Chance");
        assertTrue(chanceIndex >= 0);
        List<String> context = translator.contexts.get(chanceIndex);
        assertNotNull(context, "a brand-new enchant name's request carries surface context");
        assertEquals(1, context.size());
        assertTrue(context.get(0).contains("Soul Eater") && context.get(0).contains("Chance")
                        && context.get(0).contains("Toxophilite"),
                "the context is the original enchant-list row(s), not just the bare name");
    }

    @Test
    void onceLearnedAnEnchantNameIsNeverRequestedOrGivenContextAgain() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);
        s.translateItemLine(PARAGRAPH); // learns all 6, Chance included, WITH context once

        // A different item reusing "Chance": already cached, so no new request at all --
        // the design's own exception ("每個新附魔名的一次性小單位除外，之後永遠 0").
        String otherItem = ParagraphModel.join(List.of("Chance V, Power I", "Cubism II, Snipe III"));
        s.translateItemLine(otherItem);

        assertEquals(1, Collections.frequency(translator.requested, "Chance"),
                "Chance is requested only once, ever");
    }

    // ---- do-not-translate terms stay verbatim and are never requested ----

    @Test
    void doNotTranslateTermInsideAnEnchantNameStaysUntranslatedAndIsNeverRequested() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.doNotTranslateTerms.add("Chance");
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(cfg, translator);

        TranslationDecision first = s.translateItemLine(PARAGRAPH);
        assertFalse(first.changed());
        assertFalse(translator.requested.contains("Chance"), "a do-not-translate name is never requested");
        assertEquals(5, translator.requested.size(), "the other 5 names were still requested normally");

        TranslationDecision d = s.translateItemLine(PARAGRAPH);
        assertTrue(d.changed());
        String expectedRow0 = "噬魂者 V, 弓箭精通 IV, Chance IV";
        String expectedRow1 = "立方 V, 力量 VI, 狙擊 III";
        assertEquals(ParagraphModel.join(List.of(expectedRow0, expectedRow1)), d.translated());
    }

    // ---- master switch off: not one component is requested ----

    @Test
    void masterSwitchOffSendsNoRequestForAnyComponent() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.translationRequestsEnabled = false;
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(cfg, translator);

        TranslationDecision d = s.translateItemLine(PARAGRAPH);
        assertFalse(d.changed());
        assertEquals(0, translator.requested.size(), "master switch off: no request for any component");
    }

    // ---- isolated single-item lines / mixed content are untouched by P2 ----

    @Test
    void isolatedSingleEnchantLineGoesThroughTheOrdinaryWholeLineTranslator() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = service(zhTwConfig(), translator);

        TranslationDecision first = s.translateItemLine("Sharpness VII");
        assertFalse(first.changed());
        s.flushBatches();
        s.flushBatches();
        TranslationDecision d = s.translateItemLine("Sharpness VII");
        assertTrue(d.changed());
        assertEquals("T:Sharpness VII", d.translated(),
                "an isolated single-enchant line is translated as a whole line, unaffected by P2");
        assertEquals(1, calls.get());
    }

    @Test
    void paragraphMixedWithOtherContentIsDecomposedByTheSegmentPlannerInsteadOfOneOpaqueBlob() {
        // Pre-2026-10-01 P1.7/P2 ran whole-text-only matchers (RarityLineComposer.match/
        // EnchantListComposer.match both decline the moment ANY other row is mixed in), so
        // this fell all the way through to the ordinary whole-line translator — the exact
        // real-cache bug TooltipSegmentPlanner's "partial planning" fix addresses (97,691-row
        // account data: 93.3% of abandoned multi-row paragraphs were declined purely because
        // an otherwise-classifiable row sat next to ordinary, unrelated prose). Today this
        // paragraph is planned: the ENCHANT row decomposes into its 3 independent names
        // exactly as it would in isolation, and "Sells for 100 coins" — recognised by
        // NEITHER composer — becomes its own independent PROSE segment/cache key, instead of
        // gluing everything into one near-combinatorially-unique blob.
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            seen.add(text);
            return new TranslationResult(text, "en"); // echo: content is not the point here
        };
        TranslationService s = service(zhTwConfig(), translator);
        String mixed = ParagraphModel.join(List.of(ROW0, "Sells for 100 coins"));

        s.translateItemLine(mixed);
        s.flushBatches();
        s.flushBatches();

        assertEquals(4, calls.get(), "3 independent enchant names + 1 independent PROSE unit");
        assertTrue(seen.contains("Soul Eater"));
        assertTrue(seen.contains("Toxophilite"));
        assertTrue(seen.contains("Chance"));
        // The prose unit is masked/templated like any other standalone request -- the
        // literal "100" becomes a stable ⟦MTn⟧ slot, same as every other translation unit.
        assertTrue(seen.contains("Sells for ⟦MT0⟧ coins"));
        assertFalse(seen.contains(mixed), "the whole raw paragraph itself is never sent as one unit");
    }

    @Test
    void twoRowsEachWithOnlyOneItemGoesThroughTheOrdinaryWholeLineTranslator() {
        AtomicInteger calls = new AtomicInteger();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text, "en");
        };
        TranslationService s = service(zhTwConfig(), translator);
        String twoIsolatedRows = ParagraphModel.join(List.of("Sharpness VII", "Unbreaking III"));

        s.translateItemLine(twoIsolatedRows);
        s.flushBatches();
        s.flushBatches();

        assertEquals(1, calls.get(),
                "neither row has 2+ items -- no reordering risk, P2 leaves it alone");
    }

    // ---- components not yet complete: show old cache, or the original ----

    @Test
    void incompleteComponentsShowTheOriginalWhenNoOldCacheExists() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);

        TranslationDecision d = s.translateItemLine(PARAGRAPH);
        assertFalse(d.changed());
        assertEquals(PARAGRAPH, d.original());
    }

    @Test
    void incompleteComponentsShowAnOldWholeLineCacheHitWithoutEverRequestingTheWholeLine() {
        TranslatorConfig cfg = zhTwConfig();
        // The legacy row below is imported into the GT (google) cache specifically, so
        // this surface must read through the GT engine (also manual -- nothing is ever
        // requested, which is the whole point of this test) rather than zhTwConfig()'s
        // default AI engine.
        cfg.aiTooltip = false;
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        // A pre-existing whole-paragraph translation (an older build, or an imported
        // translations file): P2 may show it while components are incomplete, but must
        // never buy it itself -- its combinatorial cardinality would defeat caching.
        String legacyWhole = ParagraphModel.join(List.of(
                "舊版噬魂者 V, 舊版弓箭 IV, 舊版機會 IV", "舊版立方 V, 舊版力量 VI, 舊版狙擊 III"));
        int imported = gt.importTranslations(Map.of(PARAGRAPH, legacyWhole));
        assertEquals(1, imported, "the legacy row must actually import for this test to be meaningful");
        TranslationService s = new TranslationService(cfg, gt, ai);

        TranslationDecision d = s.translateItemLine(PARAGRAPH);
        assertTrue(d.changed());
        assertEquals(legacyWhole, d.translated());
        assertFalse(translator.requested.contains(PARAGRAPH),
                "the whole paragraph itself is peeked from cache, never requested");
    }

    // ---- BOTH mode tooltip readiness is all-or-nothing ----

    @Test
    void bothModeTooltipReadinessIsAllOrNothing() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.tooltipMode = DisplayMode.BOTH;
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(cfg, translator);

        assertFalse(s.isTooltipTranslationReady(PARAGRAPH), "nothing cached yet: not ready");
        s.translateItemLine(PARAGRAPH); // buys all 6 names (DIRECT executor: synchronous)
        assertTrue(s.isTooltipTranslationReady(PARAGRAPH), "every distinct name now has a final translation");
    }

    // ---- validator compatibility: composed Roman numerals must not be misjudged ----

    @Test
    void composedResultWithRomanNumeralsPassesTheGeneralValidators() {
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(zhTwConfig(), translator);
        s.translateItemLine(PARAGRAPH);
        TranslationDecision d = s.translateItemLine(PARAGRAPH);
        assertTrue(d.changed());

        assertFalse(TextFilter.hasUntranslatedAnchoredField(PARAGRAPH, d.translated()),
                "Roman-numeral levels next to newly-Chinese names must not be flagged as an "
                        + "untranslated anchored field");
        assertFalse(TextFilter.isPartialTransliteration(PARAGRAPH, d.translated()),
                "a Roman numeral separated from the translated name by a space is not a "
                        + "glued half-transliteration residue");
    }

    // ---- other surfaces: interface text (screen) decomposes like tooltip; chat does not ----

    @Test
    void screenTextSurfaceDecomposesTheSameEnchantListWithTheSameRequestCount() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.screenTextMode = DisplayMode.TRANSLATION; // 介面文字 defaults to ORIGINAL_ONLY (off)
        cfg.aiScreenText = true; // AI engine: automatic screen text (independent of aiTooltip)
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(cfg, translator);

        TranslationDecision first = s.translateScreenText(PARAGRAPH);
        assertFalse(first.changed(), "not ready on the very first frame: nothing cached yet");
        assertEquals(6, translator.requested.size(),
                "interface text decomposes the same enchant-list paragraph into per-name "
                        + "requests, exactly like the tooltip surface");
        assertEquals(List.of("Soul Eater", "Toxophilite", "Chance", "Cubism", "Power", "Snipe"),
                translator.requested);

        TranslationDecision ready = s.translateScreenText(PARAGRAPH);
        assertTrue(ready.changed());
        String expectedRow0 = "噬魂者 V, 弓箭精通 IV, 機會 IV";
        String expectedRow1 = "立方 V, 力量 VI, 狙擊 III";
        assertEquals(ParagraphModel.join(List.of(expectedRow0, expectedRow1)), ready.translated());
    }

    @Test
    void screenTextSurfaceReusesNamesAlreadyLearnedByTheTooltipSurfaceAtZeroCost() {
        TranslatorConfig cfg = zhTwConfig();
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiScreenText = true; // AI engine: automatic screen text (independent of aiTooltip)
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(cfg, translator);

        s.translateItemLine(PARAGRAPH); // learns all 6 names via the tooltip surface
        int boughtByTooltip = translator.requested.size();
        assertEquals(6, boughtByTooltip);

        TranslationDecision viaScreen = s.translateScreenText(PARAGRAPH);
        assertTrue(viaScreen.changed(), "every name was already learned by the tooltip surface");
        assertEquals(boughtByTooltip, translator.requested.size(),
                "screen text reuses the tooltip surface's per-name cache: 0 new requests, "
                        + "0 new prompt tokens");
    }

    @Test
    void chatSurfaceDoesNotDecomposeTheSameEnchantListString() {
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        Translator translator = (text, target) -> {
            calls.incrementAndGet();
            seen.add(text);
            return new TranslationResult(text, "en"); // echo: content is not the point here
        };
        TranslationService s = service(zhTwConfig(), translator);

        s.translateChat(PARAGRAPH);
        s.flushBatches();
        s.flushBatches();

        assertEquals(1, calls.get(), "chat sends the whole paragraph as one request, unmodified by P2");
        assertTrue(seen.get(0).contains("Soul Eater"),
                "the paragraph's wording travelled whole, never decomposed per enchant name");
    }

    // ---- applies to every target language, not just zh-TW/zh-HK (unlike P1.7) ----

    @Test
    void appliesToNonChineseTargetLanguagesToo() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.targetLang = "fr-FR";
        DictionaryTranslator translator = new DictionaryTranslator();
        TranslationService s = service(cfg, translator);

        s.translateItemLine(PARAGRAPH);
        assertEquals(6, translator.requested.size(), "P2 decomposes regardless of target language");
        TranslationDecision d = s.translateItemLine(PARAGRAPH);
        assertTrue(d.changed());
    }
}
