package com.dragonmeow.nyanslate.service;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanslate.translate.NameMasker;
import com.dragonmeow.nyanslate.translate.ParagraphModel;
import com.dragonmeow.nyanslate.translate.TranslationException;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.dragonmeow.nyanslate.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for "重新翻譯快捷鍵按了沒有清掉舊資料" (R/P does not clear stale
 * data): every cache layer an explicit retranslate (R — {@link
 * TranslationService#retranslate}, triggered from the keybind glue's {@code
 * retranslateItem}) or rescan (P — {@link TranslationService#retranslateScreen}, triggered
 * from {@code scanAndTranslateScreen}) touches must actually discard the old value before a
 * fresh answer is allowed to land, exercised end to end through the real
 * TranslationService/TranslationCache/TooltipSegmentPlanner/EnchantListComposer stack. Every
 * translator/cache/executor below is an inline fake, each test builds its own mutable
 * dictionary (never a shared static one, so one test's "what does the backend say now"
 * change can never leak into another) — no file or network is read.
 *
 * <p>Two concrete gaps this file locks in:
 * <ol>
 *   <li>{@link TranslationService#retranslateScreen} did not clear {@code
 *   enchantComposeMemo}/{@code structuredComposeMemo}/{@code legacyConvertAttempted} the way
 *   {@link TranslationService#retranslate} already did — an enchant/scroll-list-shaped
 *   widget paragraph (P2, applies to screen text too, see {@code translateScreenText}) kept
 *   returning its OLD fully-composed string forever, because that memo is consulted BEFORE
 *   any per-name cache lookup; every per-name key underneath it was being correctly
 *   invalidated and refreshed by {@code invalidateSources}, but nothing ever read it again.
 *   See {@code retranslateScreenClearsTheComposedEnchantListMemoSoTheFreshAnswerIsShown}.</li>
 *   <li>The R-key glue ({@code NyanslateFabric#retranslateItem}) used to branch on whether
 *   ANY line of the tooltip was still unresolved and, if so, call {@code requestItemLines}
 *   (no invalidation at all) for the WHOLE tooltip instead of {@code retranslate}. Since
 *   {@link com.dragonmeow.nyanslate.translate.TooltipSegmentPlanner} decomposes one
 *   paragraph into several independently ready/not-ready segments, a real tooltip is almost
 *   always a MIX — so that branch fired far more often than the "everything is already
 *   cached" one, and every other already-cached-but-stale segment on the SAME tooltip never
 *   got cleared no matter how many times R was pressed. This file cannot exercise the glue
 *   class itself (it needs a live {@code ItemStack}/{@code Minecraft} instance unavailable in
 *   this plain-JUnit module — see PACKAGING.md), so
 *   {@code retranslateResendsEverySegmentOfAStructuredTooltipAndDisplaysTheFreshWording"}
 *   instead proves the service-level method the fixed glue now always calls
 *   unconditionally ({@link TranslationService#retranslate}) really does refresh EVERY
 *   segment of a mixed tooltip in one call, which is the property the glue fix depends on.
 *   </li>
 *   <li><b>Superseded by the final rule below:</b> always calling {@code retranslate}
 *   unconditionally (point 2 above) fixed the "stale segment never clears" bug but
 *   over-corrected — it also throws away every already-CORRECT segment the instant any
 *   sibling line on the same tooltip is still missing, including a segment SHARED with
 *   other items (a common "Buy it now"/"Seller" row), burning tokens for no reason. The
 *   final rule restores the two-branch shape but keys it off {@link
 *   TranslationService#isTooltipFullyDisplayedTranslated}, the ACTUAL composed display
 *   result of every line (reusing {@link TranslationService#translateItemLine}, the exact
 *   path the renderer itself calls) rather than a separate structural readiness predicate
 *   that could disagree with what is actually on screen: any line still showing original
 *   text → {@code requestItemLines} only (point 2's over-invalidation never happens); every
 *   line already showing SOME translated wording (AI cache, hub, legacy lazy conversion, or
 *   a segment-composed value alike) → {@code retranslate} (point 2's original fix still
 *   applies in full). See the tests below this comment block.</li>
 * </ol>
 */
class RetranslateClearsStaleDataTest {

    private static final Executor DIRECT = Runnable::run;

    /** Counts one HTTP-level request per batch call; answers come from a MUTABLE dictionary
     *  so a test can change "what the backend would say now" mid-test to simulate a
     *  corrected answer landing after an explicit retranslate/rescan. */
    private static final class DictionaryTranslator implements Translator {
        final AtomicInteger requests = new AtomicInteger();
        final Map<String, String> dictionary;

        DictionaryTranslator(Map<String, String> dictionary) {
            this.dictionary = dictionary;
        }

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requests.incrementAndGet();
            return new TranslationResult(resolve(text), "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                       List<String> surfaceContext)
                throws TranslationException {
            requests.incrementAndGet();
            List<TranslationResult> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(new TranslationResult(resolve(t), "en"));
            return out;
        }

        @Override
        public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                                   List<List<String>> itemContexts)
                throws TranslationException {
            requests.incrementAndGet();
            List<TranslationResult> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(new TranslationResult(resolve(t), "en"));
            return out;
        }

        private String resolve(String text) {
            String v = dictionary.get(text);
            return v != null ? v : "[" + text + "]";
        }
    }

    private static TranslatorConfig aiTooltipConfig() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        return cfg;
    }

    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static String hyperionTooltip(String scroll1, String scroll2, String scroll3,
                                          String seller, String priceCoins) {
        return ParagraphModel.join(List.of(
                "LEGENDARY",
                "● " + scroll1, "● " + scroll2, "● " + scroll3,
                "Seller: " + seller, "Buy it now: " + priceCoins + " coins"));
    }

    // -------------------------------------------------------------------------
    // Scenario 1 (R key): Hyperion, every segment kind at once.
    // -------------------------------------------------------------------------

    @Test
    void retranslateResendsEverySegmentOfAStructuredTooltipAndDisplaysTheFreshWording() {
        Map<String, String> dictionary = new HashMap<>(Map.of(
                "Implosion", "內爆",
                "Wither Shield", "凋零盾",
                "Shadow Warp", "暗影躍動",
                "Seller: ⟦0⟧", "賣家：⟦0⟧",
                "Buy it now: ⟦MT0⟧ coins", "一口價：⟦MT0⟧ 金幣"));
        DictionaryTranslator translator = new DictionaryTranslator(dictionary);
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationService s = new TranslationService(aiTooltipConfig(), google, ai);
        s.setBatchWindowMs(() -> 0);

        String hyperion = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(hyperion));
        pump(s);
        TranslationDecision firstView = s.translateItemLine(hyperion);
        assertTrue(firstView.changed());
        assertTrue(firstView.translated().contains("內爆"), "Implosion -> 內爆 (first view)");
        assertTrue(firstView.translated().contains("賣家："), "Seller row translated (first view)");
        assertEquals(1, translator.requests.get(), "5 missing units batched into one request");

        // The user presses R because the wording is wrong: every segment this item renders
        // from would now be answered differently by the backend (a corrected AI run, a
        // different provider, a term-table edit -- the exact cause is out of scope; what
        // matters is EVERY cached segment gets a chance at a new value, not just whichever
        // ones happened to still be missing).
        dictionary.put("Implosion", "內爆(新)");
        dictionary.put("Wither Shield", "凋零盾(新)");
        dictionary.put("Shadow Warp", "暗影躍動(新)");
        dictionary.put("Seller: ⟦0⟧", "賣家(新)：⟦0⟧");
        dictionary.put("Buy it now: ⟦MT0⟧ coins", "一口價(新)：⟦MT0⟧ 金幣");

        s.retranslate(List.of(hyperion));
        pump(s);

        assertEquals(2, translator.requests.get(),
                "retranslate re-buys every segment in one fresh batched request, not a "
                        + "once-per-segment trickle");
        TranslationDecision afterR = s.translateItemLine(hyperion);
        assertTrue(afterR.changed());
        String out = afterR.translated();
        assertTrue(out.contains("內爆(新)"), "scroll name 1 refreshed");
        assertTrue(out.contains("凋零盾(新)"), "scroll name 2 refreshed");
        assertTrue(out.contains("暗影躍動(新)"), "scroll name 3 refreshed");
        assertTrue(out.contains("賣家(新)："), "TRADE Seller row refreshed");
        assertTrue(out.contains("一口價(新)："), "TRADE Buy-it-now row refreshed");
        assertTrue(out.contains("DragonMeow"), "the real seller name is still restored");
        assertTrue(out.contains("1,000,000"), "the real price is still restored");
    }

    // -------------------------------------------------------------------------
    // Scenario 2 (R key): a pre-segment-cache legacy whole-paragraph row, lazily converted
    // into a segment-level key, must not revive after R.
    // -------------------------------------------------------------------------

    private static NameMasker.Masked maskPlainLike(String text) {
        return NameMasker.mask(text, List.of(), DoNotTranslateMatcher.EMPTY);
    }

    private static String tradeTooltip(String scroll1, String scroll2, String seller) {
        return ParagraphModel.join(List.of(
                "LEGENDARY", "● " + scroll1, "● " + scroll2, "Seller: " + seller));
    }

    @Test
    void retranslateDoesNotReviveTheOldLegacyWholeParagraphOrItsLazilyConvertedTradeRow() {
        Map<String, String> dictionary = new HashMap<>();
        DictionaryTranslator translator = new DictionaryTranslator(dictionary);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 100);

        String original = tradeTooltip("Implosion", "Wither Shield", "DragonMeow");
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        List<String> rows = ParagraphModel.split(wholeMasked.text());
        assertEquals(4, rows.size());
        String sellerKey = rows.get(3); // masked "Seller: ⟦0⟧"
        String legacyWhole = ParagraphModel.join(List.of(
                "傳奇", // LEGENDARY -> 傳奇
                rows.get(1).replace("Implosion", "內爆"),
                rows.get(2).replace("Wither Shield", "凋零盾"),
                sellerKey.replace("Seller:", "賣家：")));
        assertEquals(1, ai.importTranslations(Map.of(wholeMasked.text(), legacyWhole)),
                "the legacy row must actually import for this test to be meaningful");

        TranslatorConfig cfg = aiTooltipConfig();
        TranslationService s = new TranslationService(cfg, google, ai);
        // Requests disabled (master switch) for the FIRST render only:
        // composeStructuredTooltip's legacy fallback + lazy conversion is a pure cache
        // splice, independent of allowRequest -- disabling requests here just keeps the
        // two still-uncached scroll names (SCROLL segments are deliberately never lazily
        // converted, see TranslationService#convertLegacyWholeCache) from buying a request
        // of their own and confounding what this test is checking.
        cfg.translationRequestsEnabled = false;
        TranslationDecision beforeR = s.translateItemLine(original);
        assertTrue(beforeR.changed(), "the legacy whole row displays and lazily converts its Seller row");
        assertTrue(beforeR.translated().contains("賣家："), "the OLD legacy wording for Seller");
        assertEquals(sellerKey.replace("Seller:", "賣家："), ai.getCached(sellerKey),
                "the Seller row must have actually been lazily converted into its own segment "
                        + "key for this test to be meaningful");
        cfg.translationRequestsEnabled = true;
        s.setBatchWindowMs(() -> 0);

        // The user presses R: a corrected answer for the Seller row, plus the two scroll
        // names legacy conversion never seeds.
        dictionary.put(sellerKey, sellerKey.replace("Seller:", "賣家(新)："));
        dictionary.put("Implosion", "內爆");
        dictionary.put("Wither Shield", "凋零盾");
        s.retranslate(List.of(original));

        assertNull(ai.getCached(wholeMasked.text()),
                "retranslate must discard the legacy whole-paragraph row too -- otherwise "
                        + "composeStructuredTooltip's own legacy-peek fallback keeps re-displaying "
                        + "the old wording while the fresh Seller-row request is still in flight");
        pump(s);

        TranslationDecision afterR = s.translateItemLine(original);
        assertTrue(afterR.changed());
        String out = afterR.translated();
        assertTrue(out.contains("賣家(新)："),
                "R must show the FRESH Seller-row wording, not the legacy-converted one");
        assertTrue(out.contains("內爆"), "Implosion resolves fresh too");
        assertTrue(out.contains("凋零盾"), "Wither Shield resolves fresh too");
    }

    // -------------------------------------------------------------------------
    // Scenario 3 (R key): a hub-repository row must not immediately re-cover the key an
    // explicit retranslate just invalidated.
    // -------------------------------------------------------------------------

    @Test
    void retranslateDisplaysTheFreshAiAnswerInsteadOfTheStaleHubRow() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.aiTooltip = true;
        Map<String, String> hub = new HashMap<>(Map.of("Diamond Sword", "舊的鑑石劍"));
        AtomicInteger requests = new AtomicInteger();
        Translator translator = (text, target) -> {
            requests.incrementAndGet();
            return new TranslationResult("新的鑑石劍", "en");
        };
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 1000);
        TranslationService s = new TranslationService(cfg, google, ai);
        s.setHubLookup(hub::get);

        TranslationDecision first = s.translateItemLine("Diamond Sword");
        assertTrue(first.changed());
        assertEquals("舊的鑑石劍", first.translated(), "hub serves the stale wording before any R press");
        assertEquals(0, requests.get(), "a hub hit costs no request");

        s.retranslate(List.of("Diamond Sword"));
        // Immediately after invalidation, before the fresh request lands: the hub must NOT
        // instantly re-cover the row with the SAME old wording, or R visibly does nothing
        // for a moment and (if the user never re-checks) forever if hub lookups kept winning.
        TranslationDecision duringFlight = s.translateItemLine("Diamond Sword");
        assertFalse("舊的鑑石劍".equals(duringFlight.translated()),
                "the hub must not re-serve the stale wording while the fresh request is in flight");

        pump(s);

        TranslationDecision afterR = s.translateItemLine("Diamond Sword");
        assertTrue(afterR.changed());
        assertEquals("新的鑑石劍", afterR.translated(), "R must show the fresh AI answer, not the old hub row");
        assertEquals(1, requests.get(), "exactly one fresh request sent by the explicit retranslate");
    }

    // -------------------------------------------------------------------------
    // Scenario 4 (P key): retranslateScreen must clear enchantComposeMemo, the actual bug
    // fixed in this patch.
    // -------------------------------------------------------------------------

    private static final String ENCHANT_ROW0 = "Soul Eater V, Toxophilite IV, Chance IV";
    private static final String ENCHANT_ROW1 = "Cubism V, Power VI, Snipe III";
    private static final String ENCHANT_PARAGRAPH =
            ParagraphModel.join(List.of(ENCHANT_ROW0, ENCHANT_ROW1));

    @Test
    void retranslateScreenClearsTheComposedEnchantListMemoSoTheFreshAnswerIsShown() {
        Map<String, String> dictionary = new HashMap<>(Map.of(
                "Soul Eater", "噬魂者", "Toxophilite", "弓箭精通",
                "Chance", "機會", "Cubism", "立方", "Power", "力量",
                "Snipe", "狙擊"));
        DictionaryTranslator translator = new DictionaryTranslator(dictionary);
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiScreenText = true;
        cfg.aiScreenScan = true;
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 1000);
        TranslationService s = new TranslationService(cfg, google, ai);

        TranslationDecision first = s.translateScreenText(ENCHANT_PARAGRAPH);
        assertFalse(first.changed(), "not ready yet: nothing cached on the first frame");
        pump(s);
        TranslationDecision ready = s.translateScreenText(ENCHANT_PARAGRAPH);
        assertTrue(ready.changed());
        assertTrue(ready.translated().contains("噬魂者"),
                "old wording for Soul Eater, now composed and memoised by composeEnchantList");

        // The user presses P because the translation is wrong: a corrected answer for Soul
        // Eater. The other 5 names deliberately keep their old (still-correct) wording, to
        // prove this is not just "everything happens to be re-requested identically".
        dictionary.put("Soul Eater", "食魂者");
        s.retranslateScreen(List.of(ENCHANT_PARAGRAPH));
        pump(s);

        TranslationDecision afterP = s.translateScreenText(ENCHANT_PARAGRAPH);
        assertTrue(afterP.changed());
        String out = afterP.translated();
        assertTrue(out.contains("食魂者"),
                "P must show the fresh wording for Soul Eater, not the memoised old composed "
                        + "string -- this is exactly the enchantComposeMemo leak retranslateScreen() "
                        + "must clear (it already clears the equivalent structured-tooltip memos "
                        + "for the tooltip surface's retranslate())");
        assertTrue(out.contains("弓箭精通"), "the other 5 names are still correctly reused");
    }

    // -------------------------------------------------------------------------
    // New R-key rule (2026-10-01, supersedes the "R always retranslates" behaviour that
    // Scenario 1 above locked in): {@link TranslationService#isTooltipFullyDisplayedTranslated}
    // decides, from the ACTUAL composed display result of every line, whether R should only
    // buy what is missing (no invalidation at all) or invalidate and resend everything. These
    // tests drive the method directly and then perform the exact two-branch decision the glue
    // makes, proving both halves of the contract end to end through the real service/cache
    // stack (the glue class itself still cannot be unit tested here -- see the class javadoc).
    // -------------------------------------------------------------------------

    @Test
    void isTooltipFullyDisplayedTranslatedIsFalseForNullEmptyOrEntirelyUntranslatableInput() {
        Map<String, String> dictionary = new HashMap<>();
        DictionaryTranslator translator = new DictionaryTranslator(dictionary);
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationService s = new TranslationService(aiTooltipConfig(), google, ai);

        assertFalse(s.isTooltipFullyDisplayedTranslated(null));
        assertFalse(s.isTooltipFullyDisplayedTranslated(List.of()));
        assertFalse(s.isTooltipFullyDisplayedTranslated(List.of("1,000,000", "   ", "✦✦✦")),
                "nothing translatable anywhere must never report \"fully translated\" -- it "
                        + "keeps the caller in the safe, non-invalidating branch");
        assertEquals(0, translator.requests.get(), "nothing translatable means nothing to request");
    }

    @Test
    void partiallyDisplayedTooltipIsNotFullyTranslatedAndTheMissingOnlyBranchNeverTouchesAnAlreadyCorrectSegmentSharedWithAnotherItem() {
        Map<String, String> dictionary = new HashMap<>(Map.of(
                "Implosion", "內爆",
                "Wither Shield", "凋零盾",
                "Seller: ⟦0⟧", "賣家：⟦0⟧",
                "Buy it now: ⟦MT0⟧ coins", "一口價：⟦MT0⟧ 金幣"));
        DictionaryTranslator translator = new DictionaryTranslator(dictionary);
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationService s = new TranslationService(aiTooltipConfig(), google, ai);
        s.setBatchWindowMs(() -> 0);

        // Item A: every segment the dictionary already knows about -- this also seeds the
        // TWO Trade-row cache keys ("Seller: ⟦0⟧" / "Buy it now: ⟦MT0⟧ coins") that item B
        // below SHARES verbatim: the seller name/price are masked OUT of the cache key, so
        // any two items with a Seller/Buy-it-now row hit the exact same keys.
        String hyperionA = hyperionTooltip("Implosion", "Wither Shield", "Wither Shield",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(hyperionA));
        pump(s);
        assertTrue(s.isTooltipFullyDisplayedTranslated(List.of(hyperionA)),
                "item A: every segment is already resolved and displayed");
        String sellerRowBefore = ai.getCached("Seller: ⟦0⟧");
        String buyItNowRowBefore = ai.getCached("Buy it now: ⟦MT0⟧ coins");
        assertTrue(sellerRowBefore != null && buyItNowRowBefore != null,
                "both shared Trade rows must actually be cached for this test to be meaningful");

        // Item B: a DIFFERENT item with the same two Trade rows (already cached via item A
        // above) but ONE brand new scroll name ("Shadow Warp") that has never been requested.
        String hyperionB = hyperionTooltip("Implosion", "Shadow Warp", "Wither Shield",
                "Steve", "2,000,000");
        assertFalse(s.isTooltipFullyDisplayedTranslated(List.of(hyperionB)),
                "item B still shows the ORIGINAL English for its one missing scroll name");

        // The R-key glue's "false" branch: buy only what is missing, invalidate nothing.
        s.requestItemLines(List.of(hyperionB));

        // The shared Trade rows item A already resolved must be completely untouched --
        // never invalidated just because a SIBLING segment on item B's tooltip was missing.
        assertEquals(sellerRowBefore, ai.getCached("Seller: ⟦0⟧"),
                "the shared Seller row must not be invalidated by the missing-only branch");
        assertEquals(buyItNowRowBefore, ai.getCached("Buy it now: ⟦MT0⟧ coins"),
                "the shared Buy-it-now row must not be invalidated by the missing-only branch");

        // Once the missing name resolves, the whole tooltip (including the untouched shared
        // rows) displays correctly and the method now reports "fully displayed".
        dictionary.put("Shadow Warp", "暗影躍動");
        pump(s);
        assertTrue(s.isTooltipFullyDisplayedTranslated(List.of(hyperionB)));
        TranslationDecision afterFetch = s.translateItemLine(hyperionB);
        assertTrue(afterFetch.changed());
        String out = afterFetch.translated();
        assertTrue(out.contains("內爆"), "Implosion resolved");
        assertTrue(out.contains("暗影躍動"), "the previously missing Shadow Warp now resolved");
        assertTrue(out.contains("賣家："), "the shared Seller row, never touched, still displays");
        assertTrue(out.contains("一口價："), "the shared Buy-it-now row, never touched, still displays");
        assertTrue(out.contains("Steve"), "item B's OWN seller name restored");
        assertTrue(out.contains("2,000,000"), "item B's OWN price restored");
    }

    @Test
    void fullyDisplayedTooltipSourcedFromTheHubRepositoryIsRetranslatedNotLeftAlone() {
        TranslatorConfig cfg = aiTooltipConfig();
        Map<String, String> hub = new HashMap<>(Map.of("Diamond Sword", "舊的鑽石劍"));
        AtomicInteger requests = new AtomicInteger();
        Translator translator = (text, target) -> {
            requests.incrementAndGet();
            return new TranslationResult("新的鑽石劍", "en");
        };
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 1000);
        TranslationService s = new TranslationService(cfg, google, ai);
        s.setHubLookup(hub::get);

        // The hub alone already makes this line display translated wording -- 0 AI requests.
        assertTrue(s.isTooltipFullyDisplayedTranslated(List.of("Diamond Sword")),
                "a hub-served row is ALREADY showing translated wording on screen, the new "
                        + "rule must not treat it as \"still missing\" just because its source "
                        + "is the repository, not the AI cache");
        assertEquals(0, requests.get(), "a hub hit costs no request");

        // The R-key glue's "true" branch.
        s.retranslate(List.of("Diamond Sword"));
        TranslationDecision duringFlight = s.translateItemLine("Diamond Sword");
        assertFalse("舊的鑽石劍".equals(duringFlight.translated()),
                "the stale hub wording must not keep covering the key the new rule chose to "
                        + "invalidate");

        s.flushBatches();
        s.flushBatches();
        TranslationDecision afterR = s.translateItemLine("Diamond Sword");
        assertEquals("新的鑽石劍", afterR.translated(), "the fresh AI answer now displays");
        assertEquals(1, requests.get(), "exactly one fresh request sent by the explicit retranslate");
    }

    @Test
    void fullyDisplayedTooltipSourcedFromLegacyLazyConversionIsRetranslatedNotLeftAlone() {
        Map<String, String> dictionary = new HashMap<>();
        DictionaryTranslator translator = new DictionaryTranslator(dictionary);
        TranslationCache ai = new TranslationCache(translator, "zh-TW", DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, "zh-TW", DIRECT, 100);

        String original = tradeTooltip("Implosion", "Wither Shield", "DragonMeow");
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        List<String> rows = ParagraphModel.split(wholeMasked.text());
        String sellerKey = rows.get(3);
        String legacyWhole = ParagraphModel.join(List.of(
                "傳奇",
                rows.get(1).replace("Implosion", "內爆"),
                rows.get(2).replace("Wither Shield", "凋零盾"),
                sellerKey.replace("Seller:", "賣家：")));
        assertEquals(1, ai.importTranslations(Map.of(wholeMasked.text(), legacyWhole)),
                "the legacy row must actually import for this test to be meaningful");

        TranslatorConfig cfg = aiTooltipConfig();
        TranslationService s = new TranslationService(cfg, google, ai);
        // Requests disabled (master switch) for the priming render only, exactly like the
        // legacy-conversion scenario above: a pure cache splice, independent of allowRequest.
        cfg.translationRequestsEnabled = false;
        TranslationDecision primed = s.translateItemLine(original);
        assertTrue(primed.changed(), "the legacy whole row already displays translated wording");
        cfg.translationRequestsEnabled = true;
        s.setBatchWindowMs(() -> 0);

        // Every line of this tooltip is ALREADY showing translated wording -- entirely via
        // legacy whole-paragraph lazy conversion, with no segment ever bought on its own.
        assertTrue(s.isTooltipFullyDisplayedTranslated(List.of(original)),
                "legacy-lazily-converted wording still counts as \"fully displayed translated\", "
                        + "the new rule must not special-case it as \"still missing\"");

        dictionary.put(sellerKey, sellerKey.replace("Seller:", "賣家(新)："));
        dictionary.put("Implosion", "內爆(新)");
        dictionary.put("Wither Shield", "凋零盾(新)");

        // The R-key glue's "true" branch.
        s.retranslate(List.of(original));
        assertNull(ai.getCached(wholeMasked.text()),
                "retranslate must discard the legacy whole-paragraph row the new rule chose to "
                        + "invalidate, otherwise composeStructuredTooltip's legacy-peek fallback "
                        + "keeps re-displaying the old wording");
        pump(s);

        TranslationDecision afterR = s.translateItemLine(original);
        assertTrue(afterR.changed());
        String out = afterR.translated();
        assertTrue(out.contains("賣家(新)："), "fresh Seller-row wording, not the legacy-converted one");
        assertTrue(out.contains("內爆(新)"), "Implosion resolves fresh too");
        assertTrue(out.contains("凋零盾(新)"), "Wither Shield resolves fresh too");
    }
}
