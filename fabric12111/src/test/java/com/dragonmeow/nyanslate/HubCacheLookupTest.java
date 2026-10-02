package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.service.TranslationDecision;
import com.dragonmeow.nyanslate.service.TranslationService;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.dragonmeow.nyanslate.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TranslationService#setHubLookup} wires the GitHub AI translation hub's local
 * read-through cache into the AI engine's cache-miss path (see the design note on
 * {@code TranslationService.lookup}): a hub hit is consulted BEFORE the "send a
 * request" decision, so it displays immediately — with zero outgoing requests — in
 * every mode, including {@link TranslationService#isManualItemTranslation()}'s
 * cache-only item surfaces (2026-10-02: manual mode is {@code config.aiTooltip ==
 * false}, no longer a separate flag). Every translator/cache here is an inline fake; no
 * file or network is touched.
 */
class HubCacheLookupTest {

    private static final Executor DIRECT = Runnable::run;

    private static Translator counting(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    // 2026-10-02: the hub only ever read-throughs the AI engine's cache miss (see
    // hubLookupIsNotConsultedForTheGoogleTranslateEngine below), and manual item mode is
    // now DEFINED as "this surface's engine is NOT AI" (config.aiTooltip == false) -- so
    // "manual item mode" and "the AI engine" are no longer independently reachable
    // together the way the pre-2026-10-02 global manualItemTranslation flag allowed. This
    // scenario is kept as an explicit regression guard for that AI-engine-only hub
    // behaviour; it is now equivalent to automaticModeHubHitDisplaysWithoutSendingARequestEither.
    @Test
    void manualItemModeHubHitDisplaysWithZeroRequests() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true; // the hub only read-throughs the AI engine's cache miss
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationService service = new TranslationService(cfg, gt, ai);
        Map<String, String> hub = Map.of("Diamond Sword", "鑽石劍");
        service.setHubLookup(hub::get);

        TranslationDecision decision = service.translateItemLine("Diamond Sword");

        assertTrue(decision.changed());
        assertEquals("鑽石劍", decision.translated());
        assertEquals(0, calls.get(), "a hub hit must never send a translator request");
    }

    @Test
    void automaticModeHubHitDisplaysWithoutSendingARequestEither() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true;
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationService service = new TranslationService(cfg, gt, ai);
        Map<String, String> hub = Map.of("Diamond Sword", "鑽石劍");
        service.setHubLookup(hub::get);

        TranslationDecision decision = service.translateItemLine("Diamond Sword");

        assertTrue(decision.changed());
        assertEquals("鑽石劍", decision.translated());
        assertEquals(0, calls.get(), "a cache tier hit never also buys a fresh request");
    }

    @Test
    void hubLookupIsNotConsultedForTheGoogleTranslateEngine() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = false; // tooltip surface routed to the GT engine, not AI (also manual mode)
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationService service = new TranslationService(cfg, gt, ai);
        Map<String, String> hub = Map.of("Diamond Sword", "鑽石劍");
        service.setHubLookup(hub::get);

        TranslationDecision decision = service.translateItemLine("Diamond Sword");

        assertFalse(decision.changed(), "the hub only ever read-throughs the AI engine's miss, never GT's");
        assertEquals(0, calls.get());
    }

    @Test
    void hubMissFallsThroughToTheOrdinaryCacheMissBehavior() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiTooltip = true;
        TranslationCache gt = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(counting(calls), cfg.targetLang, DIRECT, 1000);
        TranslationService service = new TranslationService(cfg, gt, ai);
        service.setHubLookup(key -> null); // wired in, but never has this key

        TranslationDecision decision = service.translateItemLine("Diamond Sword");
        service.flushBatches();
        service.flushBatches();
        TranslationDecision after = service.translateItemLine("Diamond Sword");

        assertFalse(decision.changed());
        assertTrue(after.changed(), "a hub miss must not block the ordinary automatic request");
        assertEquals(1, calls.get());
    }
}
