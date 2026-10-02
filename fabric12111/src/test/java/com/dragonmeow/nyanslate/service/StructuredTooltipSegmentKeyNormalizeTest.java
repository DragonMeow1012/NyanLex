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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces and fixes the 2026-10-02 real-Hypixel bug: {@link
 * com.dragonmeow.nyanslate.translate.FabricTextStyle#markChatContent} (not exercised
 * directly here — this test works one layer down, straight from the already-marked
 * {@code ⟦CSn⟧}-wrapped tooltip paragraph string) numbers every {@code ⟦CSn⟧} colour-run
 * marker/{@code ⟦n⟧} bare slot SEQUENTIALLY ACROSS THE WHOLE joined tooltip paragraph, so
 * the exact same semantic TRADE/STATS row ({@code "Crit Chance: 50%"}, {@code "Seller:
 * [MVP+] <name>"}, …) carries a DIFFERENT embedded marker offset depending on how many
 * OTHER style runs happened to precede it in THAT item's own paragraph (a longer enchant
 * list, an extra trade field, a different rank prefix, …). Before this fix, {@code
 * TranslationService#resolveEnchantName}/{@code #requestEnchantName} used that raw,
 * position-dependent span DIRECTLY as the cache/request key, so the same row effectively
 * never got reused across items, and a sibling item needing its OWN fresh request could
 * mismatch between warm time and render time whenever anything upstream (re)computed a
 * different marker offset for what looks like "the same" tooltip. {@link
 * com.dragonmeow.nyanslate.translate.LocalTokenRenumberer#localize}/{@code restore} now sit
 * in that one shared chokepoint, so the key is computed from the row's OWN local token
 * numbering, completely independent of its position.
 */
class StructuredTooltipSegmentKeyNormalizeTest {

    private static final Executor DIRECT = Runnable::run;

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

    /** Two scroll names (ScrollNameListComposer needs >= 2 bulleted rows to register at
     *  all) plus one STATS row carrying LITERAL {@code ⟦CSn⟧} markers at whatever global
     *  offset the caller hands in — simulating exactly what a real joined, markChatContent
     *  -numbered tooltip paragraph looks like once it reaches TranslationService. */
    private static String tooltip(String scroll1, String scroll2, String statsRow) {
        return ParagraphModel.join(List.of("● " + scroll1, "● " + scroll2, statsRow));
    }

    @Test
    void sameStatsRowAtDifferentGlobalCsOffsetsSharesOneCacheEntryAcrossSiblingItems() {
        TranslatorConfig cfg = aiTooltipConfig();
        // The dictionary is keyed on the LOCALIZED, THEN MT-templated form every request/
        // lookup now actually computes (CS indices renumbered 0,1,... from first
        // appearance, and TranslationTemplate folds the literal "50%" into its own ⟦MT0⟧
        // value slot before the cache/backend ever sees it, same as any other stat value)
        // -- NOT on either item's own raw, globally-numbered span. A real OpenAI-style
        // backend would of course echo back whatever indices/slots it was actually sent;
        // this fake just mirrors that.
        CountingTranslator translator = new CountingTranslator(Map.of(
                "⟦CS0⟧Crit Chance:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧", "⟦CS0⟧暴擊率：⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧",
                "Implosion", "內爆",
                "Wither Shield", "凋零盾"));
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, google, ai);
        s.setBatchWindowMs(() -> 0);

        // Item A: 16 OTHER style runs precede this STATS row in ITS OWN tooltip paragraph
        // (a longer enchant list, extra trade fields, ...), so markChatContent would have
        // numbered it CS16/CS17.
        String itemA = tooltip("Implosion", "Wither Shield",
                "⟦CS16⟧Crit Chance:⟦/CS16⟧ ⟦CS17⟧50%⟦/CS17⟧");
        // Item B: the IDENTICAL semantic row, but only 3 other style runs precede it in
        // ITS OWN (shorter) paragraph -- CS3/CS4.
        String itemB = tooltip("Implosion", "Wither Shield",
                "⟦CS3⟧Crit Chance:⟦/CS3⟧ ⟦CS4⟧50%⟦/CS4⟧");

        s.warmTooltipBatch(List.of(itemA));
        pump(s);
        TranslationDecision a = s.translateItemLine(itemA);
        assertTrue(a.changed(), "item A's own stats row must display once warmed/resolved");
        assertTrue(a.translated().contains("⟦CS16⟧暴擊率：⟦/CS16⟧ ⟦CS17⟧50%⟦/CS17⟧"),
                "composed back with item A's OWN global CS indices restored, not the local "
                        + "0/1 form the cache key used internally");

        int requestsAfterItemA = translator.requests.get();
        assertTrue(requestsAfterItemA > 0, "sanity: item A actually needed some request(s)");

        // Render item B WITHOUT ever warming it first: if the STATS row's cache key still
        // depended on its raw, position-dependent span, this would look like a brand new,
        // never-seen unit (0 cache hit) and either show English or need a fresh request.
        TranslationDecision b = s.translateItemLine(itemB);
        assertEquals(requestsAfterItemA, translator.requests.get(),
                "the STATS row resolves straight from item A's ALREADY-cached entry -- zero "
                        + "new requests -- despite carrying a completely different global CS offset");
        assertTrue(b.changed(), "item B's stats row must display immediately too");
        assertTrue(b.translated().contains("⟦CS3⟧暴擊率：⟦/CS3⟧ ⟦CS4⟧50%⟦/CS4⟧"),
                "composed back with item B's OWN global CS indices (3/4), proving the "
                        + "restore step re-anchors per ITEM, not to item A's or the local form");
    }

    @Test
    void warmAndRenderComputeTheIdenticalKeyForTheSameItem() {
        // Narrower, more direct statement of the render/warm divergence bullet: warming an
        // item then rendering it must need EXACTLY ONE request total for its STATS row --
        // if warm and render ever computed two DIFFERENT keys for the very same row of the
        // very same item, warm's write would simply never be found by render, and render's
        // own first-touch query would have to re-request under ITS OWN (different) key.
        TranslatorConfig cfg = aiTooltipConfig();
        CountingTranslator translator = new CountingTranslator(Map.of(
                "⟦CS0⟧Crit Chance:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧", "⟦CS0⟧暴擊率：⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧",
                "Implosion", "內爆",
                "Wither Shield", "凋零盾"));
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(translator, cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, google, ai);
        s.setBatchWindowMs(() -> 0);

        String item = tooltip("Implosion", "Wither Shield",
                "⟦CS7⟧Crit Chance:⟦/CS7⟧ ⟦CS8⟧50%⟦/CS8⟧");

        s.warmTooltipBatch(List.of(item));
        pump(s);
        int requestsAfterWarm = translator.requests.get();
        assertTrue(requestsAfterWarm > 0, "sanity: warm actually sent something");

        TranslationDecision rendered = s.translateItemLine(item);
        assertEquals(requestsAfterWarm, translator.requests.get(),
                "render must hit EXACTLY what warm already wrote for this same item -- no "
                        + "extra request from a key mismatch between the two code paths");
        assertTrue(rendered.changed());
        assertTrue(rendered.translated().contains("⟦CS7⟧暴擊率：⟦/CS7⟧ ⟦CS8⟧50%⟦/CS8⟧"));

        // One more render frame (as a hovered tooltip repeatedly re-renders): still zero
        // new requests, and still the exact same restored global indices.
        TranslationDecision renderedAgain = s.translateItemLine(item);
        assertEquals(requestsAfterWarm, translator.requests.get());
        assertEquals(rendered.translated(), renderedAgain.translated());
    }
}
