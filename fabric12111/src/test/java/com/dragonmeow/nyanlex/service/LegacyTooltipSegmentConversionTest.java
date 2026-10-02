package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanlex.translate.NameMasker;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;
import com.dragonmeow.nyanlex.translate.Translator;
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
 * Lazy conversion of a pre-segment-cache whole-paragraph tooltip translation into
 * independent, row-level segment cache entries (see {@code
 * TranslationService#convertLegacyWholeCache}/{@code peekLegacyWholeCacheForWarm} and
 * design-segment-cache.md §3). A paragraph an older build (or an imported translations
 * file) already cached as ONE opaque multi-row unit must both (a) keep displaying correctly
 * today — never regress to the English original just because {@link TooltipSegmentPlanner}
 * now exists — and (b) opportunistically seed the NEW segment-level keys it safely can, so a
 * later render of this or any OTHER item sharing one of those rows needs zero new requests.
 * Every translator/cache/executor is an inline fake; no file or network is read.
 */
class LegacyTooltipSegmentConversionTest {

    private static final Executor DIRECT = Runnable::run;

    /** Counts one HTTP-level request per {@code translateBatch}/{@code
     *  translateBatchWithContexts} call. */
    private static class CountingTranslator implements Translator {
        final AtomicInteger requests = new AtomicInteger();
        final Map<String, String> dictionary;

        CountingTranslator(Map<String, String> dictionary) {
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

    /** The exact masked (P-slot only, no entity layer) form of {@code text} that {@code
     *  TranslationService#maskPlain} would itself produce with the default config this test
     *  uses (no TAB-list names configured, no do-not-translate terms) — used to seed a
     *  pre-existing "legacy whole paragraph" cache row the same way an older build (or an
     *  imported translations file) would have written one. */
    private static NameMasker.Masked maskPlainLike(String text) {
        return NameMasker.mask(text, List.of(), DoNotTranslateMatcher.EMPTY);
    }

    /** A RARITY + two-name SCROLL run + one Seller: TRADE row paragraph (the Hyperion
     *  shape, minus the price field) — deliberately free of any literal number, so no
     *  {@code ⟦MTn⟧} slot is involved anywhere in this test (that narrower "does a
     *  mismatched slot index get safely rejected" property is covered at the {@code
     *  TranslationCache} level, see {@code
     *  importTranslationsAsyncStillAppliesTheSameUsableForBulkTransferGate} in {@code
     *  TranslationCacheTest}). {@link ScrollNameListComposer#matchRuns} requires a run of
     *  AT LEAST 2 icon-bulleted rows (same "nothing to gain from a single item" rule as
     *  {@link EnchantListComposer}) — a single scroll row does not register as a SCROLL
     *  segment at all, which silently defeats {@link TooltipSegmentPlanner#plan} (one
     *  unclassified prose row makes it decline the WHOLE paragraph), so every test here
     *  uses two scroll names. */
    private static String tooltip(String scroll1, String scroll2, String seller) {
        return ParagraphModel.join(List.of(
                "LEGENDARY", "● " + scroll1, "● " + scroll2, "Seller: " + seller));
    }

    @Test
    void legacyWholeParagraphCacheHitDisplaysImmediatelyInsteadOfRegressingToEnglish() {
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of());
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);

        String original = tooltip("Implosion", "Wither Shield", "DragonMeow");
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        String legacyWhole = legacyTranslation(wholeMasked.text());
        assertEquals(1, ai.importTranslations(Map.of(wholeMasked.text(), legacyWhole)),
                "the legacy row must actually import for this test to be meaningful");

        TranslationService s = new TranslationService(cfg, google, ai);
        // Manual item mode (allowRequest=false for the hover/render path): the point of
        // THIS test is purely "does the pre-existing legacy row still display correctly",
        // not request accounting -- any side-effect request the two still-uncached scroll
        // names would otherwise queue is irrelevant noise here (and, left on, would let a
        // FRESH request's own fake answer confuse what is actually being asserted).
        cfg.translationRequestsEnabled = false;
        TranslationDecision d = s.translateItemLine(original);

        assertTrue(d.changed(),
                "a pre-existing whole-paragraph translation must display immediately, not "
                        + "regress to the English original while segment-level keys are unearned");
        // decide() restores the masked ⟦n⟧ placeholder back to the real seller name before
        // handing the final string to the render surface -- the raw legacyWhole value (which
        // still carries the bare placeholder) is never shown as-is.
        java.util.regex.Matcher placeholder =
                java.util.regex.Pattern.compile("⟦\\d+⟧").matcher(wholeMasked.text());
        assertTrue(placeholder.find(), "the Seller row must have masked a placeholder");
        String expectedDisplay = legacyWhole.replace(placeholder.group(), "DragonMeow");
        assertEquals(expectedDisplay, d.translated());
    }

    @Test
    void tradeRowSegmentIsConvertedAndReusedByASiblingItemWithZeroRequests() {
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of());
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);

        String original = tooltip("Implosion", "Wither Shield", "DragonMeow");
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        String legacyWhole = legacyTranslation(wholeMasked.text());
        ai.importTranslations(Map.of(wholeMasked.text(), legacyWhole));

        TranslationService s = new TranslationService(cfg, google, ai);
        s.setBatchWindowMs(() -> 0); // enable the shared windowed collector for the sibling check below
        // Manual mode ONLY for this first render, so the still-missing scroll names cannot
        // send/cache a FRESH (fake) answer of their own and confound "was the Seller row
        // populated by legacy conversion specifically" -- composeStructuredTooltip's
        // legacy-peek-and-convert runs regardless of allowRequest (it is a cache splice,
        // never a network request).
        cfg.translationRequestsEnabled = false;
        s.translateItemLine(original);
        cfg.translationRequestsEnabled = true;

        // A SIBLING item: both scroll names are brand new (never cached before, and not
        // part of the legacy row's own combo), but the EXACT same masked "Seller: <name>"
        // row. If the TRADE segment was really carved out and written as its own
        // segment-level key, resolving this sibling needs requests only for the two new
        // scroll names -- never an extra one for the Seller row.
        String sibling = tooltip("Shadow Warp", "Wither Impact", "DragonMeow");
        s.warmTooltipBatch(List.of(sibling));
        pump(s);

        assertEquals(1, translator.requests.get(),
                "only the two brand-new scroll names are missing, batched into one request -- "
                        + "the Seller: row was already seeded from the legacy paragraph, so it buys "
                        + "nothing of its own");
    }

    @Test
    void scrollSegmentIsNeverConvertedFromTheLegacyRow() {
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of());
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);

        String original = tooltip("Implosion", "Wither Shield", "DragonMeow");
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        String legacyWhole = legacyTranslation(wholeMasked.text());
        ai.importTranslations(Map.of(wholeMasked.text(), legacyWhole));

        TranslationService s = new TranslationService(cfg, google, ai);
        // Manual mode: allowRequest=false means NOTHING is ever queued/sent as a side
        // effect of rendering, so checking the cache state right after is unambiguous --
        // whatever IS there came only from convertLegacyWholeCache, never a fresh request.
        cfg.translationRequestsEnabled = false;
        s.translateItemLine(original);

        assertNull(ai.getCached("Implosion"),
                "the ENCHANT/SCROLL per-name key must never be opportunistically seeded from "
                        + "the legacy row -- no reliable per-name boundary once already translated "
                        + "as free-form prose");
        assertNull(ai.getCached("Wither Shield"), "same for the run's second name");
    }

    @Test
    void repeatedRendersOfTheSameUnresolvedParagraphWriteTheConvertedRowAtMostOnce() {
        // Manual mode: nothing is ever requested, so composed stays null forever and
        // composeStructuredTooltip's legacy-fallback branch — hence
        // convertLegacyWholeCache — runs on EVERY call, exactly like a tooltip held on
        // screen for many render frames. The per-text "already attempted" guard must still
        // keep the actual persistent write (never mind the whole row scan) to ONE, not
        // once-per-frame forever.
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of());
        Map<String, String> disk = new HashMap<>();
        AtomicInteger diskWrites = new AtomicInteger();
        PersistentStore countingStore = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) {
                diskWrites.incrementAndGet();
                disk.put(key, value);
            }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100,
                10_000L, System::currentTimeMillis, countingStore);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);

        String original = tooltip("Implosion", "Wither Shield", "DragonMeow");
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        String legacyWhole = legacyTranslation(wholeMasked.text());
        ai.importTranslations(Map.of(wholeMasked.text(), legacyWhole));

        TranslationService s = new TranslationService(cfg, google, ai);
        cfg.translationRequestsEnabled = false;
        // Frame 1 does the real (one-time) work: the conversion write itself, plus whatever
        // decide()'s own usual one-time derived-row bookkeeping for a freshly-displayed
        // translation adds (not this feature's concern; same for an ordinary cache hit).
        TranslationDecision first = s.translateItemLine(original);
        assertTrue(first.changed(), "frame 1 already shows the legacy whole translation");
        int writesAfterFirstFrame = diskWrites.get();
        assertTrue(writesAfterFirstFrame > 0, "frame 1 must have written SOMETHING for this test to be meaningful");

        for (int frame = 0; frame < 19; frame++) {
            TranslationDecision d = s.translateItemLine(original);
            assertTrue(d.changed(), "every later frame still shows the legacy whole translation");
        }

        assertEquals(writesAfterFirstFrame, diskWrites.get(),
                "no further writes across 19 MORE renders of the same still-unresolved "
                        + "paragraph -- the per-text guard stops convertLegacyWholeCache from "
                        + "repeating its row scan/import dispatch once per frame");
    }

    @Test
    void aSecondNumberBearingTradeRowConvertsCorrectlyViaLocalRenumbering() {
        // Two TRADE rows, each carrying its own literal number (Starting bid / Buy it
        // now): inside the WHOLE paragraph's own prepared key, these two numbers get
        // GLOBAL, sequential ⟦MTn⟧ indices (0 then 1) -- but a FRESH, standalone request
        // for "Buy it now: 500,000 coins" ALONE would number its own (only) number ⟦MT0⟧,
        // not ⟦MT1⟧. Without LocalTokenRenumberer this second row could never convert (its
        // naive global-index value would fail TranslationCache's own MT-shape validation
        // against the freshly recomputed LOCAL key) -- this proves it now does.
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of());
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);

        String original = ParagraphModel.join(List.of(
                "LEGENDARY", "● Implosion", "● Wither Shield",
                "Starting bid: 100,000 coins", "Buy it now: 500,000 coins"));
        NameMasker.Masked wholeMasked = maskPlainLike(original);
        String preparedWhole = new TranslationTemplate().prepare(wholeMasked.text()).key();
        List<String> keyRows = ParagraphModel.split(preparedWhole);
        assertEquals(5, keyRows.size());
        // Both scroll rows' leading bullet icon ("●") are themselves MT-templated
        // (TemplateText treats a decorative icon as a deterministic value slot), so the
        // two prices land on MT2/MT3 here rather than MT0/MT1 -- either way, what matters
        // for this test is that the price is NOT the row's own locally-first MT slot.
        assertFalse(keyRows.get(4).contains("⟦MT0⟧"),
                "the second TRADE row must NOT carry local index 0 inside the whole "
                        + "paragraph's key for this test to actually exercise the renumbering fix");

        List<String> translatedRows = List.of(
                "傳奇",
                keyRows.get(1).replace("Implosion", "內爆"),
                keyRows.get(2).replace("Wither Shield", "凋零盾"),
                keyRows.get(3).replace("Starting bid:", "起標價："),
                keyRows.get(4).replace("Buy it now:", "一口價："));
        String legacyWhole = ParagraphModel.join(translatedRows);
        assertEquals(1, ai.importTranslations(Map.of(preparedWhole, legacyWhole)),
                "the legacy row must actually import for this test to be meaningful");

        TranslationService s = new TranslationService(cfg, google, ai);
        cfg.translationRequestsEnabled = false;
        TranslationDecision d = s.translateItemLine(original);
        assertTrue(d.changed(), "legacy whole paragraph displays");
        cfg.translationRequestsEnabled = true;
        s.setBatchWindowMs(() -> 0);

        // Same two prices (so both TRADE rows apply), two brand-new scroll names.
        String sibling = ParagraphModel.join(List.of(
                "LEGENDARY", "● Shadow Warp", "● Wither Impact",
                "Starting bid: 100,000 coins", "Buy it now: 500,000 coins"));
        s.warmTooltipBatch(List.of(sibling));
        pump(s);

        assertEquals(1, translator.requests.get(),
                "both TRADE rows -- including the SECOND one, whose global MT index "
                        + "needed renumbering -- were already converted; only the two "
                        + "brand-new scroll names are missing, batched into one request");
    }

    /** Hand-builds the "AI already translated this whole paragraph, long before the segment
     *  planner existed" value: rarity/scroll rows via a small fixed dictionary, the Seller
     *  row's own masked placeholder copied through untranslated (exactly like a real
     *  provider response would leave a protected ⟦n⟧ token alone), same PB count as the
     *  input (4 rows -> 3 breaks). */
    private static String legacyTranslation(String maskedOriginal) {
        List<String> rows = ParagraphModel.split(maskedOriginal);
        assertEquals(4, rows.size());
        String rarity = "傳奇"; // LEGENDARY -> 傳奇
        String scroll1 = rows.get(1).replace("Implosion", "內爆"); // -> 內爆
        String scroll2 = rows.get(2).replace("Wither Shield", "凋零盾"); // -> 凋零盾
        String tradeRow = rows.get(3).replace("Seller:", "賣家："); // Seller: -> 賣家：
        return ParagraphModel.join(List.of(rarity, scroll1, scroll2, tradeRow));
    }
}
