package com.dragonmeow.nyanlex.cache;

import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warm-up lane of the cache and the request budget it shares with screen translation (inline fakes). */
class WarmLaneTest {

    private static final Executor DIRECT = Runnable::run;

    private static final class Recording implements Translator {
        final List<List<String>> batches = new ArrayList<>();
        final List<List<List<String>>> contexts = new ArrayList<>();
        final AtomicBoolean unpacedOnLane = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public TranslationResult translate(String text, String targetLang) {
            return translateBatch(List.of(text), targetLang).get(0);
        }

        @Override
        public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                                  List<List<String>> itemContexts) {
            calls.incrementAndGet();
            unpacedOnLane.set(RequestPacer.isUnpacedThread());
            batches.add(new ArrayList<>(texts));
            contexts.add(itemContexts == null ? null : new ArrayList<>(itemContexts));
            List<TranslationResult> out = new ArrayList<>();
            for (String text : texts) out.add(new TranslationResult("T:" + text, "en"));
            return out;
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang) {
            return translateBatchWithContexts(texts, targetLang, null);
        }
    }

    private static String text(String label, int chars) {
        StringBuilder sb = new StringBuilder(label);
        while (sb.length() < chars) sb.append("sharp blade of the dawn ");
        return sb.substring(0, chars).stripTrailing() + ".";
    }

    private static TranslationCache cache(Translator translator, long[] now) {
        TranslationCache cache = new TranslationCache(translator, "zh-TW", DIRECT, 1_000, 10_000L, () -> now[0]);
        cache.setBatchWindowMs(() -> 5_000);
        cache.setWindowedBatching(true);
        return cache;
    }

    // ------------------------------------------------------------ shared budget

    @Test
    void theWarmupAndTheScreenCollectorShareOneBudgetConstantAndOnePackingRule() {
        assertEquals(BatchBudget.WINDOWED_CHARS, TranslationCache.MAX_WINDOWED_BATCH_CHARS,
                "the collector (screen capture, chat, tooltips) takes its budget from BatchBudget");
        assertEquals(4_000, BatchBudget.WINDOWED_CHARS);

        BatchBudget budget = BatchBudget.windowed();
        assertTrue(budget.fits(10_000), "the first unit always fits, however large");
        budget.add(10_000);
        assertTrue(budget.full());
        assertFalse(budget.fits(1), "nothing joins a request that is already over budget");

        BatchBudget second = BatchBudget.windowed();
        second.add(3_000);
        assertTrue(second.fits(1_000), "exactly the budget still fits");
        assertFalse(second.fits(1_001), "one point over stays for the next request");
    }

    @Test
    void theScreenCollectorPacksExactlyLikeTheBudgetRule() {
        Recording engine = new Recording();
        long[] now = {0L};
        TranslationCache cache = cache(engine, now);
        // 3 units of 1,000 characters: 1,016 points each, so 3 fit (3,048) and a 4th (4,064) does not
        List<String> screen = new ArrayList<>();
        for (int i = 0; i < 4; i++) screen.add(text("Screen unit " + (char) ('a' + i) + " ", 1_000));
        cache.warmBatchAsync(screen, screen);
        now[0] = 5_000L;
        cache.flushBatch();
        assertEquals(1, engine.batches.size());
        assertEquals(3, engine.batches.get(0).size(), "the 4th unit waits for the next request");
        now[0] = 10_000L;
        cache.flushBatch();
        assertEquals(2, engine.batches.size());
        assertEquals(1, engine.batches.get(1).size());
    }

    // ------------------------------------------------------------ lane

    @Test
    void laneSendsEverythingItCollectedAsOneRequestWithEachUnitsOwnContextAndNoCooldown() {
        Recording engine = new Recording();
        long[] now = {0L};
        TranslationCache cache = cache(engine, now);
        List<String> itemA = List.of("Sword of Dawn", "A blade forged at sunrise");
        List<String> itemB = List.of("Shield of Dusk", "A shield forged at sunset");

        int requests = TranslationCache.collectWarmLane(() -> {
            cache.warmBatchAsync(itemA, itemA);
            cache.warmBatchAsync(itemB, itemB);
        });

        assertEquals(1, requests);
        assertEquals(1, engine.calls.get(), "one HTTP-level request for both items, no collector window");
        assertEquals(List.of("Sword of Dawn", "A blade forged at sunrise",
                "Shield of Dusk", "A shield forged at sunset"), engine.batches.get(0));
        assertEquals(List.of(itemA, itemA, itemB, itemB), engine.contexts.get(0),
                "every unit travels with its own item as context");
        assertTrue(engine.unpacedOnLane.get(), "the request skips the interactive cooldown");
        assertFalse(RequestPacer.isUnpacedThread(), "and the mark does not leak to the caller");
        assertEquals("T:Sword of Dawn", cache.getCached("Sword of Dawn"));
        assertEquals(0, cache.pendingCount());
    }

    @Test
    void laneReappliesTheSharedBudgetAndAnOversizedUnitTravelsAlone() {
        Recording engine = new Recording();
        long[] now = {0L};
        TranslationCache cache = cache(engine, now);
        List<String> units = new ArrayList<>();
        for (int i = 0; i < 5; i++) units.add(text("Lane unit " + (char) ('a' + i) + " ", 1_000));
        units.add(text("Lane giant ", 9_000));
        units.add(text("Lane tail ", 100));

        int requests = TranslationCache.collectWarmLane(() -> cache.warmBatchAsync(units, units));

        assertEquals(engine.batches.size(), requests);
        int total = 0;
        for (List<String> batch : engine.batches) {
            total += batch.size();
            int points = 0;
            for (String unit : batch) points += BatchBudget.unitChars(unit);
            assertTrue(batch.size() == 1 || points <= BatchBudget.WINDOWED_CHARS,
                    "a request above the budget must be a single oversized unit: " + points);
        }
        assertEquals(7, total, "nothing is dropped");
        boolean giantAlone = engine.batches.stream()
                .anyMatch(b -> b.size() == 1 && b.get(0).startsWith("Lane giant"));
        assertTrue(giantAlone, engine.batches.toString());
    }

    @Test
    void laneWorkIsNotInteractiveAndAnInteractiveEntryIs() {
        AtomicBoolean interactiveDuringLane = new AtomicBoolean(true);
        long[] now = {0L};
        TranslationCache[] holder = new TranslationCache[1];
        Translator watcher = new Translator() {
            @Override
            public TranslationResult translate(String text, String targetLang) {
                interactiveDuringLane.set(holder[0].hasInteractiveWork());
                return new TranslationResult("T:" + text, "en");
            }
        };
        TranslationCache cache = cache(watcher, now);
        holder[0] = cache;

        TranslationCache.collectWarmLane(() -> cache.warmBatchAsync(List.of("Lane axe"), null));
        assertFalse(interactiveDuringLane.get(), "a warm-up request in flight does not make the cache busy");
        assertFalse(cache.hasInteractiveWork());

        cache.requestBatched("Hello from chat");
        assertTrue(cache.hasInteractiveWork(), "a collected chat line is interactive work");
        now[0] = 5_000L;
        cache.flushBatch();
        assertFalse(cache.hasInteractiveWork());
    }

    @Test
    void laneSendsNothingWhileOnlineTranslationIsOff() {
        Recording engine = new Recording();
        long[] now = {0L};
        TranslationCache cache = cache(engine, now);
        cache.setRequestGate(() -> false);

        int requests = TranslationCache.collectWarmLane(
                () -> cache.warmBatchAsync(List.of("Iron pickaxe", "Digs fast"), null));

        assertEquals(0, requests);
        assertEquals(0, engine.calls.get());
        assertEquals(0, cache.pendingCount());
    }

    @Test
    void laneSkipsWhatIsAlreadyInFlightOrCached() {
        Recording engine = new Recording();
        long[] now = {0L};
        TranslationCache cache = cache(engine, now);
        TranslationCache.collectWarmLane(() -> cache.warmBatchAsync(List.of("Copper ingot"), null));
        assertEquals(1, engine.calls.get());

        int again = TranslationCache.collectWarmLane(() -> cache.warmBatchAsync(List.of("Copper ingot"), null));
        assertEquals(0, again, "already stored: nothing is sent twice");
        assertEquals(1, engine.calls.get());
    }

    // ------------------------------------------------------------ pacer bypass

    @Test
    void theLaneThreadSkipsTheInteractiveCooldownEverybodyElseKeepsIt() {
        AtomicLong now = new AtomicLong(1_000);
        List<Long> sleeps = new ArrayList<>();
        RequestPacer pacer = new RequestPacer(() -> 10_000L, now::get, sleeps::add);

        pacer.acquireForAi(); // an interactive request reserves a slot
        pacer.acquireForAi();
        assertEquals(List.of(10_000L), sleeps, "interactive requests are spaced by the 10 s cooldown");

        Boolean previous = RequestPacer.bindUnpaced();
        try {
            pacer.acquireForAi();
            pacer.acquireForAi();
            pacer.acquireForAi();
        } finally {
            RequestPacer.restoreUnpaced(previous);
        }
        assertEquals(List.of(10_000L), sleeps, "the warm-up lane never waits and reserves nothing");

        pacer.acquireForAi();
        assertEquals(List.of(10_000L, 20_000L), sleeps,
                "back on an ordinary thread the interactive slots are exactly where they were");
    }
}
