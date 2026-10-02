package com.dragonmeow.nyanslate.service;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segment cache integration: a Hypixel SkyBlock Auction House tooltip glues a rarity
 * line, a scroll/ability name list and Seller:/Buy it now: trade fields together with no
 * blank row between them (see {@code design-segment-cache.md}). This exercises the whole
 * pipeline — {@link TooltipSegmentPlanner} (indirectly, via package access) through {@link
 * TranslationService#translateItemLine}/{@link TranslationService#warmTooltipBatch} — with
 * an inline fake {@link Translator} that counts HTTP-level requests (one {@link
 * Translator#translateBatch}/{@code translateBatchWithContexts} call = one request,
 * regardless of how many distinct cache keys it carries) and characters sent, so the
 * Hyperion "three scrolls" scenario can be quantified exactly like a real auction browse.
 * No file or network is read.
 */
class TranslationServiceStructuredTooltipTest {

    private static final Executor DIRECT = Runnable::run;

    /** Counts one HTTP-level request per {@code translateBatch}/{@code
     *  translateBatchWithContexts} call (never per individual cache key inside it), and
     *  the total characters of every text/context string actually sent — the two numbers
     *  the "不得增加 prompt token 或請求數" acceptance criterion is quantified against. */
    private static class CountingTranslator implements Translator {
        final AtomicInteger requests = new AtomicInteger();
        /** Characters of the actual TRANSLATABLE UNITS sent (never the shared surface
         *  context) — this is the number that must stay small on a partial re-buy: the
         *  missing piece's own text, not a re-send of the whole combo. */
        final AtomicInteger textCharsSent = new AtomicInteger();
        /** Characters of the surface CONTEXT sent alongside (every request's units share
         *  one list in {@link #translateBatch(List, String, List)}, or carry their own in
         *  {@link #translateBatchWithContexts}). Context dedup at the {@code
         *  OpenAiTranslator} level — so an already-known neighbour segment used only as
         *  context is not re-billed — is OUT OF SCOPE for this change (see the report);
         *  this counter exists so that gap is visible rather than silently assumed away. */
        final AtomicInteger contextCharsSent = new AtomicInteger();
        final Map<String, String> dictionary;

        CountingTranslator(Map<String, String> dictionary) {
            this.dictionary = dictionary;
        }

        @Override
        public TranslationResult translate(String text, String targetLang) {
            requests.incrementAndGet();
            textCharsSent.addAndGet(text.length());
            return new TranslationResult(resolve(text), "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                       List<String> surfaceContext)
                throws TranslationException {
            requests.incrementAndGet();
            for (String t : texts) textCharsSent.addAndGet(t.length());
            if (surfaceContext != null) for (String c : surfaceContext) contextCharsSent.addAndGet(c.length());
            List<TranslationResult> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(new TranslationResult(resolve(t), "en"));
            return out;
        }

        @Override
        public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                                   List<List<String>> itemContexts)
                throws TranslationException {
            requests.incrementAndGet();
            for (String t : texts) textCharsSent.addAndGet(t.length());
            if (itemContexts != null) {
                for (List<String> ctx : itemContexts) {
                    if (ctx != null) for (String c : ctx) contextCharsSent.addAndGet(c.length());
                }
            }
            List<TranslationResult> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(new TranslationResult(resolve(t), "en"));
            return out;
        }

        private String resolve(String text) {
            String v = dictionary.get(text);
            return v != null ? v : "[" + text + "]";
        }
    }

    private static final Map<String, String> DICTIONARY = new HashMap<>() {{
        put("Seller: ⟦0⟧", "賣家：⟦0⟧");                 // "Seller: ⟦0⟧" -> "賣家：⟦0⟧"
        put("Buy it now: ⟦MT0⟧ coins", "一口價：⟦MT0⟧ 金幣"); // -> "一口價：⟦MT0⟧ 金幣"
        put("Implosion", "內爆");           // 內爆
        put("Wither Shield", "凋零盾"); // 凋零盾
        put("Shadow Warp", "暗影躍動"); // 暗影躍動
        put("Wither Impact", "凋零衝擊"); // 凋零衝擊
    }};

    private static String hyperionTooltip(String scroll1, String scroll2, String scroll3,
                                          String seller, String priceCoins) {
        return ParagraphModel.join(List.of(
                "LEGENDARY",
                "● " + scroll1, "● " + scroll2, "● " + scroll3,
                "Seller: " + seller, "Buy it now: " + priceCoins + " coins"));
    }

    private static TranslationService newService(TranslatorConfig cfg, Translator translator) {
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationService service = new TranslationService(cfg, google, ai);
        service.setBatchWindowMs(() -> 0); // enable the shared windowed collector (coalesces a tick's warms into 1 request)
        return service;
    }

    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static TranslatorConfig aiTooltipConfig() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        return cfg;
    }

    @Test
    void firstViewSendsEverythingMissingAsOneRequest() {
        CountingTranslator translator = new CountingTranslator(DICTIONARY);
        TranslationService s = newService(aiTooltipConfig(), translator);

        String hyperion = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(hyperion));
        pump(s);

        assertEquals(1, translator.requests.get(),
                "3 scroll names + 2 trade rows missing on first sight, batched into ONE request "
                        + "(the rarity word resolves locally from the term table, 0 requests)");

        TranslationDecision d = s.translateItemLine(hyperion);
        assertTrue(d.changed(), "every segment is now cached: the tooltip renders translated");
        String out = d.translated();
        assertTrue(out.contains("內爆"), "Implosion -> 內爆");
        assertTrue(out.contains("凋零盾"), "Wither Shield -> 凋零盾");
        assertTrue(out.contains("暗影躍動"), "Shadow Warp -> 暗影躍動");
        assertTrue(out.contains("DragonMeow"), "the seller's real name is restored, not the placeholder");
        assertTrue(out.contains("1,000,000"), "the real price is restored, not the placeholder");
        // TranslationService returns the composed string with its ⟦PBn⟧/⟦CSn⟧ GLUE-level
        // protocol tokens still in place (FabricTextStyle strips/renders those later, same
        // as composeEnchantList's own output) -- what must never remain is an UNRESTORED
        // name/number placeholder (a bare ⟦0⟧ or ⟦MT0⟧).
        assertFalse(out.contains("⟦0⟧"), "no unrestored NameMasker placeholder (⟦0⟧) reaches the screen");
        assertFalse(out.contains("⟦MT0⟧"), "no unrestored TemplateText placeholder (⟦MT0⟧) reaches the screen");
    }

    @Test
    void swappingOneScrollOnlySendsTheMissingName() {
        CountingTranslator translator = new CountingTranslator(DICTIONARY);
        TranslationService s = newService(aiTooltipConfig(), translator);

        String first = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(first));
        pump(s);
        assertEquals(1, translator.requests.get());
        int textCharsAfterFirst = translator.textCharsSent.get();

        // Same item re-equipped with Wither Impact instead of Wither Shield; same seller,
        // same price.
        String swapped = hyperionTooltip("Implosion", "Wither Impact", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(swapped));
        pump(s);

        assertEquals(2, translator.requests.get(),
                "only the ONE new scroll name is missing: one more small request, not a re-buy "
                        + "of the whole combo");
        // The second request's TRANSLATABLE-UNIT payload is just the one new name, never a
        // re-send of the whole combo as one blob (the "送出字元數" quantification). Context
        // dedup at the OpenAiTranslator level (so an already-cached neighbour segment used
        // only as shared context is not re-billed) is a separate, NOT-yet-implemented piece
        // -- see the report -- so contextCharsSent is deliberately not asserted here.
        int secondRequestTextChars = translator.textCharsSent.get() - textCharsAfterFirst;
        assertEquals("Wither Impact".length(), secondRequestTextChars,
                "the second request's own translatable text is exactly the one missing name");

        TranslationDecision d = s.translateItemLine(swapped);
        assertTrue(d.changed());
        String out = d.translated();
        assertTrue(out.contains("內爆"), "Implosion is still reused from the first view");
        assertTrue(out.contains("暗影躍動"), "Shadow Warp is still reused from the first view");
        assertTrue(out.contains("凋零衝擊"), "Wither Impact -> 凋零衝擊 (newly bought)");
        assertFalse(out.contains("凋零盾"), "the replaced Wither Shield wording is gone");
    }

    @Test
    void sameComboDifferentSellerAndPriceCostsZeroRequests() {
        CountingTranslator translator = new CountingTranslator(DICTIONARY);
        TranslationService s = newService(aiTooltipConfig(), translator);

        String first = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(first));
        pump(s);
        assertEquals(1, translator.requests.get());

        String relisted = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "Steve", "500,000");
        s.warmTooltipBatch(List.of(relisted));
        pump(s);

        assertEquals(1, translator.requests.get(),
                "same scroll combo, different seller/price: every segment is already cached "
                        + "(Seller:/Buy it now: key on the MASKED/TEMPLATED row, not the real name/price)");

        TranslationDecision d = s.translateItemLine(relisted);
        assertTrue(d.changed());
        String out = d.translated();
        assertTrue(out.contains("Steve"), "the new seller's real name is restored");
        assertTrue(out.contains("500,000"), "the new price is restored");
        assertFalse(out.contains("DragonMeow"), "the old seller must not leak into this listing");
    }

    @Test
    void manualItemModeSendsNothingUntilTheExplicitRequestKey() {
        TranslatorConfig cfg = aiTooltipConfig();
        // 2026-10-02: manual item mode is now DEFINED as config.aiTooltip == false (the
        // machine-translation engine) -- see TranslationService#isManualItemTranslation().
        // Both caches share the same underlying translator/dictionary here, so which cache
        // backs the lookup does not change this test's outcome.
        cfg.aiTooltip = false;
        CountingTranslator translator = new CountingTranslator(DICTIONARY);
        TranslationService s = newService(cfg, translator);

        String hyperion = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(hyperion)); // hover alone must buy nothing in manual mode
        pump(s);
        assertEquals(0, translator.requests.get(), "manual item mode: hovering never sends");
        assertFalse(s.translateItemLine(hyperion).changed(), "nothing cached yet: shows the original");

        s.requestItemLines(List.of(hyperion)); // R-key: the explicit "send now" action
        pump(s);
        assertEquals(1, translator.requests.get(),
                "the explicit key still buys everything missing, batched into one request");
        assertTrue(s.translateItemLine(hyperion).changed());
    }

    @Test
    void hubLookupServesASegmentWithZeroRequests() {
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of(
                "Seller: ⟦0⟧", "賣家：⟦0⟧",
                "Buy it now: ⟦MT0⟧ coins", "一口價：⟦MT0⟧ 金幣",
                "Wither Shield", "凋零盾",
                "Shadow Warp", "暗影躍動"));
        TranslationService s = newService(cfg, translator);
        // The hub already knows "Implosion" -> 內爆 at the SEGMENT level: a repository
        // read-through hit must count as a cache hit (0 requests), exactly like any other
        // already-cached unit, confirming hub lookups work at segment granularity too.
        s.setHubLookup(masked -> "Implosion".equals(masked) ? "內爆" : null);

        String hyperion = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(hyperion));
        pump(s);

        assertEquals(1, translator.requests.get(),
                "Implosion is served from the hub (0 requests); the other 2 scroll names + 2 "
                        + "trade rows still batch into ONE request");
        TranslationDecision d = s.translateItemLine(hyperion);
        assertTrue(d.changed());
        assertTrue(d.translated().contains("內爆"), "the hub-served name appears in the composed tooltip");
    }

    @Test
    void oneUnresolvableSegmentNeverProducesAPartiallyTranslatedOrCorruptedResult() {
        // "整件視為失敗，不得寫錯段" adapted to this design's independent-per-segment
        // architecture (see the report): there is no single joined mega-request whose PB
        // count can mismatch, so the equivalent safety property is that ONE permanently
        // unresolved segment must never leave the group showing a half-English/half-
        // Chinese corrupted mix, and must never block or corrupt the OTHER segments, which
        // stay correctly resolvable and cacheable entirely on their own.
        Map<String, String> dictionary = new HashMap<>(DICTIONARY);
        CountingTranslator translator = new CountingTranslator(dictionary) {
            @Override
            public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                                       List<List<String>> itemContexts)
                    throws TranslationException {
                requests.incrementAndGet();
                List<TranslationResult> out = new ArrayList<>(texts.size());
                for (String t : texts) {
                    textCharsSent.addAndGet(t.length());
                    String known = dictionary.get(t);
                    // "Wither Shield" simulates a provider that can never translate this
                    // one unit: it echoes the source back verbatim, which the cache's own
                    // failure ledger treats as a permanent "keep original" for THIS key
                    // only (see TranslationCache's FAILURE_ECHO semantics) -- every other
                    // key in the SAME batch still resolves normally.
                    out.add(new TranslationResult("Wither Shield".equals(t) ? t
                            : known != null ? known : "[" + t + "]", "en"));
                }
                return out;
            }
        };
        TranslationService s = newService(aiTooltipConfig(), translator);

        String hyperion = hyperionTooltip("Implosion", "Wither Shield", "Shadow Warp",
                "DragonMeow", "1,000,000");
        s.warmTooltipBatch(List.of(hyperion));
        pump(s);

        TranslationDecision d = s.translateItemLine(hyperion);
        assertFalse(d.changed(),
                "one segment permanently unresolved: the WHOLE group stays the original English, "
                        + "never a half-translated/corrupted mix");

        // The other segments the first attempt legitimately resolved remain correct and
        // independently displayable on their own combo -- the one failure never corrupted
        // or blocked them.
        String otherCombo = ParagraphModel.join(List.of(
                "LEGENDARY", "● Implosion", "● Shadow Warp", "Seller: DragonMeow"));
        s.warmTooltipBatch(List.of(otherCombo));
        pump(s);
        TranslationDecision d2 = s.translateItemLine(otherCombo);
        assertTrue(d2.changed());
        assertTrue(d2.translated().contains("內爆"));
        assertTrue(d2.translated().contains("暗影躍動"));
        assertTrue(d2.translated().contains("賣家"));
    }

    @Test
    void styleColourMarkersSurviveComposition() {
        CountingTranslator translator = new CountingTranslator(DICTIONARY);
        TranslationService s = newService(aiTooltipConfig(), translator);

        // The rarity line and a scroll name carry their own colour run, as Hypixel's real
        // wire format does.
        String text = ParagraphModel.join(List.of(
                "⟦CS0⟧LEGENDARY⟦/CS0⟧",
                "● ⟦CS1⟧Implosion⟦/CS1⟧",
                "● Wither Shield",
                "Seller: DragonMeow"));
        s.warmTooltipBatch(List.of(text));
        pump(s);
        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed());
        String out = d.translated();
        assertTrue(out.contains("⟦CS0⟧"), "the rarity line's own colour run survives composition");
        assertTrue(out.contains("⟦CS1⟧"), "the scroll name's own colour run survives composition");
        assertTrue(out.contains("內爆"), "Implosion -> 內爆, still inside its own CS run");
        assertTrue(out.contains("凋零盾"), "Wither Shield -> 凋零盾");
        assertTrue(out.contains("DragonMeow"));
    }

    @Test
    void structuredComposeMemoStaysBounded() {
        TranslatorConfig cfg = aiTooltipConfig();
        Map<String, String> dictionary = new HashMap<>(DICTIONARY);
        CountingTranslator translator = new CountingTranslator(dictionary);
        TranslationService s = newService(cfg, translator);

        for (int i = 0; i < 300; i++) {
            // A plain letters-only suffix (ScrollNameListComposer's NAME shape excludes
            // digits, same WORD definition as EnchantListComposer's).
            String scroll = "Implosion" + suffixLetters(i);
            dictionary.put(scroll, "內爆" + i);
            String text = hyperionTooltip(scroll, "Wither Shield", "Shadow Warp",
                    "DragonMeow", "1,000,000");
            s.warmTooltipBatch(List.of(text));
            pump(s);
            TranslationDecision d = s.translateItemLine(text); // populates structuredComposeMemo
            assertTrue(d.changed());
        }
        assertTrue(s.structuredComposeMemoSizeForTest() <= 256,
                "bounded LRU, same discipline as enchantComposeMemo");
    }

    // -------------------------------------------------------------------------
    // 2026-10-01 real-cache fix: PROSE/ABILITY partial planning. Pre-fix, a single
    // unrecognised row (ordinary lore prose, or an Ability:…Cooldown: block) anywhere in
    // the paragraph declined the WHOLE plan — real 97,691-row account data showed this is
    // why only 2 of 49,797 multi-row candidate rows were ever splittable. These tests
    // exercise the fix through the real TranslationService/TooltipSegmentPlanner/
    // TranslationCache stack, the same way the Hyperion tests above exercise TRADE/SCROLL.
    // -------------------------------------------------------------------------

    @Test
    void reusableProseFootnoteGluedToTheAuctionFieldsIsItsOwnSharedSegment() {
        CountingTranslator translator = new CountingTranslator(new HashMap<>(DICTIONARY) {{
            put("Works while in Accessory Bag!", "在飾品袋中也能使用！");
        }});
        TranslationService s = newService(aiTooltipConfig(), translator);

        // A footnote row no composer recognises, glued (no blank row) right after the
        // rarity line and before the trade fields — real shape confirmed on account data.
        String itemA = ParagraphModel.join(List.of(
                "LEGENDARY", "Works while in Accessory Bag!", "Seller: DragonMeow",
                "Buy it now: 1,000,000 coins"));
        s.warmTooltipBatch(List.of(itemA));
        pump(s);
        assertEquals(1, translator.requests.get(),
                "the PROSE footnote + 2 trade rows all missing on first sight, one windowed request "
                        + "(rarity resolves free from the term table)");

        TranslationDecision d = s.translateItemLine(itemA);
        assertTrue(d.changed());
        assertTrue(d.translated().contains("在飾品袋中也能使用！"), "the prose footnote is translated");
        assertTrue(d.translated().contains("賣家"));

        // A DIFFERENT item (different rarity, different seller/price) carrying the EXACT
        // same footnote text must reuse it at zero additional cost -- the whole point of
        // giving PROSE its own independent, item-agnostic cache key. The rarity word
        // resolves free either way, and Seller:/Buy it now: key on their MASKED/TEMPLATED
        // form (seller-name/price-agnostic, see the Hyperion tests above), already cached
        // from itemA -- so itemB costs ZERO new requests end to end.
        String itemB = ParagraphModel.join(List.of(
                "COMMON", "Works while in Accessory Bag!", "Seller: Steve",
                "Buy it now: 500,000 coins"));
        s.warmTooltipBatch(List.of(itemB));
        pump(s);
        assertEquals(1, translator.requests.get(),
                "every segment of itemB is already cached (masked keys match itemA's): 0 new requests");
        TranslationDecision d2 = s.translateItemLine(itemB);
        assertTrue(d2.changed());
        assertTrue(d2.translated().contains("在飾品袋中也能使用！"), "reused, not re-requested");
    }

    @Test
    void abilityBlockGluedToARarityLineIsCarvedOutAsOneSegmentAndReusedVerbatimAcrossItems() {
        // NOTE on the dictionary key's ⟦PB0⟧ (2026-10-02): a multi-row ABILITY/PROSE
        // segment's INTERNAL ⟦PBn⟧ break tokens are locally renumbered from 0 like every
        // other token kind (LocalTokenRenumberer), so the SAME ability text shares one key
        // regardless of how many rows precede it in a given item's tooltip.
        CountingTranslator translator = new CountingTranslator(new HashMap<>(DICTIONARY) {{
            put("Ability: Flame Breath" + ParagraphModel.breakToken(0) + "Deals damage.",
                    "能力：噴火" + ParagraphModel.breakToken(0) + "造成傷害。");
        }});
        TranslationService s = newService(aiTooltipConfig(), translator);

        // Pre-fix: the mere PRESENCE of "Ability:" anywhere in the text declined the WHOLE
        // plan, so even the trivial RARITY row never composed locally. Today the ability
        // block is carved out as its own single ABILITY segment (never further split), and
        // the rarity row still resolves for free.
        String itemA = ParagraphModel.join(List.of(
                "RARE WAND", "Ability: Flame Breath", "Deals damage."));
        s.warmTooltipBatch(List.of(itemA));
        pump(s);
        assertEquals(1, translator.requests.get());
        TranslationDecision d = s.translateItemLine(itemA);
        assertTrue(d.changed());
        assertTrue(d.translated().contains("能力：噴火"));
        assertTrue(d.translated().contains("造成傷害。"));

        // A different item (different rarity) sharing the IDENTICAL ability block text
        // reuses it at zero additional requests.
        String itemB = ParagraphModel.join(List.of(
                "EPIC WAND", "Ability: Flame Breath", "Deals damage."));
        s.warmTooltipBatch(List.of(itemB));
        pump(s);
        assertEquals(1, translator.requests.get(), "the ability block is reused verbatim, 0 new requests");
        TranslationDecision d2 = s.translateItemLine(itemB);
        assertTrue(d2.changed());
        assertTrue(d2.translated().contains("能力：噴火"));
    }

    @Test
    void proseAndAbilitySegmentsReportReadyAndPendingCorrectly() {
        CountingTranslator translator = new CountingTranslator(new HashMap<>(DICTIONARY) {{
            put("Works while in Accessory Bag!", "在飾品袋中也能使用！");
        }});
        TranslationService s = newService(aiTooltipConfig(), translator);
        String item = ParagraphModel.join(List.of(
                "LEGENDARY", "Works while in Accessory Bag!", "Seller: DragonMeow",
                "Buy it now: 1,000,000 coins"));

        assertFalse(s.isTooltipTranslationReady(item), "nothing requested yet");
        s.warmTooltipBatch(List.of(item));
        assertTrue(s.isTooltipTranslationPending(item), "the PROSE/TRADE units are now queued/in flight");
        pump(s);
        assertFalse(s.isTooltipTranslationPending(item), "nothing left in flight once the batch drains");
        assertTrue(s.isTooltipTranslationReady(item), "every segment, including PROSE, now has a final value");
    }

    /** Turns {@code i} into an upper/lower letters-only suffix so the generated name still
     *  matches ScrollNameListComposer's WORD shape ({@code [A-Z][A-Za-z]*}). */
    private static String suffixLetters(int i) {
        StringBuilder out = new StringBuilder();
        int n = i;
        do {
            out.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return out.toString();
    }
}
