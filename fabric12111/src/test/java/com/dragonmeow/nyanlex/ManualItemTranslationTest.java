package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.ChurnGuard;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-02 手動物品／介面翻譯: manual (cache-only, translate-key-driven) mode is no
 * longer a global startup flag -- it is judged live from each surface's CONFIGURED
 * ENGINE. Item-class surfaces (tooltip/held/container+HUD name pre-warm/custom-GUI
 * text pre-warm) are manual exactly when {@code config.aiTooltip == false} (the
 * machine-translation engine, also {@link TranslatorConfig}'s own default) -- a miss
 * shows the original and sends nothing -- while chat/boss bar/scoreboard/title/action
 * bar/book/name tags keep sending automatically exactly as before (every pre-existing
 * test in this module exercises that unaffected default). {@link
 * TranslationService#requestItemLines} and {@link TranslationService#retranslate} are
 * the explicit, key-triggered entry points that still send regardless of the mode. The
 * AI engine ({@code config.aiTooltip == true}) auto-translates instead, exactly like
 * every other automatic surface -- see {@link AiEngineAutoTranslatesItemsTest} for that
 * side. Every translator, cache and executor here is an inline fake; no file or network
 * is read.
 */
class ManualItemTranslationTest {

    private static final Executor DIRECT = Runnable::run;

    /** {@code cfg.aiTooltip}/{@code cfg.aiScreenText} default to {@code false} (the
     *  machine-translation engine), so a freshly constructed {@link TranslatorConfig} is
     *  already in manual item/screen mode -- no extra setter call needed. */
    private static TranslationService manualService(TranslatorConfig cfg, Translator translator) {
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        return new TranslationService(cfg, gt, ai);
    }

    private static Translator counting(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    /** Simulate two client ticks: the coalescer holds one tick after growth, then sends.
     *  Only the plain whole-line request path (lookup()'s own requestBatched(Passive))
     *  needs this; warmTooltipBatch/warmNamesBatch/requestItemLines send immediately
     *  through warmBatchAsync when no batching window is installed (the default here). */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static int tracked(TranslationCache cache, String field) {
        try {
            Field declared = TranslationCache.class.getDeclaredField(field);
            declared.setAccessible(true);
            Object value = declared.get(cache);
            if (value instanceof Map) return ((Map<?, ?>) value).size();
            if (value instanceof Collection) return ((Collection<?>) value).size();
            throw new AssertionError(field + " is not a map or collection");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertNoFailureOrBackoffState(TranslationCache cache) {
        for (String field : List.of("failedUntil", "contentFailures", "contentRetryAttempts",
                "retrySnapshots", "provisionalRetryAttempts")) {
            assertEquals(0, tracked(cache, field), "manual mode must not record " + field);
        }
    }

    // ---- hover tooltip / held item: 0 requests ----

    @Test
    void hoveringAnUncachedTooltipSendsNoRequests() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        // Several render frames of the same hover, mirroring visibleTooltip()'s
        // warmTooltipBatch() + per-line translateItemLine() calls.
        s.warmTooltipBatch(List.of("Diamond Sword", "A legendary blade"));
        TranslationDecision line1 = s.translateItemLine("Diamond Sword");
        TranslationDecision line2 = s.translateItemLine("A legendary blade");
        s.warmTooltipBatch(List.of("Diamond Sword", "A legendary blade"));
        s.translateItemLine("Diamond Sword");

        assertFalse(line1.changed());
        assertFalse(line2.changed());
        assertEquals(0, calls.get(), "hovering a never-before-seen tooltip must send nothing");
    }

    @Test
    void heldItemNameSendsNoRequest() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        TranslationDecision held = s.translateHeld("Netherite Pickaxe");

        assertFalse(held.changed());
        assertEquals(0, calls.get());
    }

    // ---- container slot / HUD hotbar name pre-warm: 0 requests ----

    @Test
    void containerSlotNamePrewarmSendsNoRequests() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        // warmOpenContainerItems() scans every visible slot through this entry point.
        s.warmNamesBatch(List.of("Ender Pearl", "Enchanted Book"));

        assertEquals(0, calls.get(), "container slot name pre-warm must send nothing");
    }

    @Test
    void hudHotbarNamePrewarmSendsNoRequests() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        // warmVisibleHudItems() scans the 9 hotbar slots plus the off-hand through the
        // very same entry point.
        s.warmNamesBatch(List.of("Golden Apple"));

        assertEquals(0, calls.get(), "HUD hotbar name pre-warm must send nothing");
    }

    // ---- custom GUI text (screenTextMode): 0 requests ----

    @Test
    void screenTextSendsNoRequests() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        TranslationService s = manualService(cfg, counting(calls));

        TranslationDecision d = s.translateScreenText("Open the SkyBlock menu");

        assertFalse(d.changed());
        assertEquals(0, calls.get());
    }

    // ---- already-cached rows keep displaying automatically ----

    @Test
    void cachedItemLineStillDisplaysWithoutResendingInManualMode() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        // requestItemLines (the key-triggered "send now" action) populates the cache
        // even while this surface is in manual (cache-only) mode.
        s.requestItemLines(List.of("Diamond Sword"));
        assertEquals(1, calls.get());
        TranslationDecision first = s.translateItemLine("Diamond Sword");
        assertTrue(first.changed());

        // An ordinary hover re-read afterwards must not resend.
        TranslationDecision second = s.translateItemLine("Diamond Sword");
        assertTrue(second.changed(), "an already-cached line keeps showing its translation");
        assertEquals(first.translated(), second.translated());
        assertEquals(1, calls.get(), "reading an already-cached line sends nothing new");
    }

    @Test
    void cachedScreenTextStillDisplaysWithoutResendingInManualMode() {
        AtomicInteger calls = new AtomicInteger();
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        TranslationService s = manualService(cfg, counting(calls));

        // retranslateScreen (the key-triggered "send now" action for screen text) sends
        // even in manual mode -- see TranslationService#retranslateScreen.
        s.retranslateScreen(List.of("Welcome to the server"));
        assertEquals(1, calls.get());
        TranslationDecision before = s.translateScreenText("Welcome to the server");
        assertTrue(before.changed());

        TranslationDecision after = s.translateScreenText("Welcome to the server");

        assertTrue(after.changed());
        assertEquals(before.translated(), after.translated());
        assertEquals(1, calls.get());
    }

    // ---- R key: requestItemLines sends only the missing lines ----

    @Test
    void requestItemLinesSendsOnlyTheMissingLines() {
        List<String> requested = new ArrayList<>();
        Translator translator = (text, target) -> {
            requested.add(text);
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = manualService(TestConfigs.translating(), translator);

        // One line already cached (e.g. the item's own name, cached from an earlier R).
        s.requestItemLines(List.of("Diamond Sword"));
        assertEquals(List.of("Diamond Sword"), requested);
        requested.clear();

        // R pressed again on a tooltip with one cached line and one new line.
        s.requestItemLines(List.of("Diamond Sword", "A legendary blade"));

        assertEquals(List.of("A legendary blade"), requested,
                "only the uncached line is requested; the cached one is left alone");
    }

    @Test
    void retranslateForcesAFreshRequestEvenWhenEverythingIsAlreadyCached() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        s.requestItemLines(List.of("Diamond Sword"));
        assertEquals(1, calls.get());
        assertTrue(s.isTooltipTranslationReady("Diamond Sword"),
                "the R-key handler decides 'everything already translated' from this");

        // Every line already translated: the R-key handler falls back to a forced
        // retranslate instead of requestItemLines (which would otherwise send nothing).
        s.retranslate(List.of("Diamond Sword"));

        assertEquals(2, calls.get(), "retranslate() re-buys an already-cached line");
    }

    // ---- enchant-list paragraphs: decomposition is request-free until R is pressed ----

    @Test
    void enchantListComponentsAreOnlyRequestedThroughRequestItemLines() {
        String row = "Soul Eater V, Chance IV";
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.targetLang = "zh-TW";
        List<String> requested = new ArrayList<>();
        Translator translator = (text, target) -> {
            requested.add(text);
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService s = manualService(cfg, translator);

        TranslationDecision hover = s.translateItemLine(row);
        assertFalse(hover.changed());
        assertTrue(requested.isEmpty(), "hovering must not request the enchant names either");

        s.requestItemLines(List.of(row));

        assertEquals(List.of("Soul Eater", "Chance"), requested,
                "R decomposes the paragraph into its two missing enchant names, never the whole row");
    }

    // ---- the tooltip hint line's "translating…" state ----

    @Test
    void isTooltipTranslationPendingReflectsRequestItemLinesInFlight() {
        Deque<Runnable> workers = new ArrayDeque<>();
        TranslatorConfig cfg = TestConfigs.translating();
        TranslationCache gt = new TranslationCache((text, target) -> new TranslationResult("T:" + text, "en"),
                cfg.targetLang, workers::add, 1000);
        TranslationCache ai = new TranslationCache((text, target) -> new TranslationResult("T:" + text, "en"),
                cfg.targetLang, DIRECT, 1000);
        TranslationService s = new TranslationService(cfg, gt, ai);

        assertFalse(s.isTooltipTranslationPending("Diamond Sword"));
        assertFalse(s.isTooltipTranslationReady("Diamond Sword"));

        s.requestItemLines(List.of("Diamond Sword"));

        assertTrue(s.isTooltipTranslationPending("Diamond Sword"), "a worker is queued but has not run yet");
        assertFalse(s.isTooltipTranslationReady("Diamond Sword"));
        assertEquals(1, workers.size());

        workers.poll().run();

        assertFalse(s.isTooltipTranslationPending("Diamond Sword"), "the worker finished and stored the result");
        assertTrue(s.isTooltipTranslationReady("Diamond Sword"));
    }

    // ---- chat / boss bar remain fully automatic in manual mode ----

    @Test
    void chatStillSendsAutomaticallyInManualMode() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        assertFalse(s.translateChat("Hello there").changed());
        pump(s);
        TranslationDecision d = s.translateChat("Hello there");

        assertTrue(d.changed());
        assertEquals(1, calls.get(), "chat is unaffected by manual item/screen mode");
    }

    @Test
    void bossBarStillSendsAutomaticallyInManualMode() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService s = manualService(TestConfigs.translating(), counting(calls));

        assertFalse(s.translateBossBar("Kuudra, the Mad").changed());
        pump(s);
        TranslationDecision d = s.translateBossBar("Kuudra, the Mad");

        assertTrue(d.changed());
        assertEquals(1, calls.get(), "boss bar is unaffected by manual item/screen mode");
    }

    // ---- manual mode writes no failure/backoff/churn state ----

    @Test
    void manualModeMissesRecordNoFailureOrBackoffOrChurnState() {
        TranslatorConfig cfg = TestConfigs.translating();
        Translator neverCalled = (text, target) -> {
            throw new AssertionError("must never be called in manual mode");
        };
        TranslationCache gt = new TranslationCache(neverCalled, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(neverCalled, cfg.targetLang, DIRECT, 1000);
        TranslationService s = new TranslationService(cfg, gt, ai);
        ChurnGuard guard = new ChurnGuard(2, 60_000L, 300_000L, System::currentTimeMillis);
        gt.setChurnGuard(guard);
        ai.setChurnGuard(guard);

        for (int frame = 0; frame < 5; frame++) {
            s.translateItemLine("Diamond Sword");
            s.translateHeld("Diamond Sword");
            s.warmTooltipBatch(List.of("Diamond Sword"));
            s.warmNamesBatch(List.of("Diamond Sword"));
            pump(s);
        }

        assertFalse(gt.hasFailureState("Diamond Sword"));
        assertFalse(ai.hasFailureState("Diamond Sword"));
        assertEquals(0, gt.pendingCount());
        assertEquals(0, ai.pendingCount());
        assertEquals(0, guard.signatureCount(), "ChurnGuard must never see a line that was never sent");
        assertNoFailureOrBackoffState(gt);
        assertNoFailureOrBackoffState(ai);
    }
}
