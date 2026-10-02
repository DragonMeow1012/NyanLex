package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.0.7 F5: a CS-marked line whose final colour projection outlived its semantic row (the
 * semantic row is written first, so write-order eviction drops it first; or its plain copy
 * never passed validation) used to stay original on every render surface while the request
 * side counted it as cached, so it was never re-requested either. The projection is now
 * displayed at once, and the semantic row is rebuilt from it on a worker with the line's
 * own live values. Zero requests, zero tokens, no failure records, request switch or not.
 * Every collaborator is an inline fake.
 */
class ProjectionSemanticRebuildTest {

    private static final Executor DIRECT = Runnable::run;

    /** What the glue requests for a two-colour item title (and the held-item label). */
    private static final String TITLE = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";
    /** Its CS key (stars become one live slot) and the surviving final projection row. */
    private static final String TITLE_KEY = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String TITLE_PROJECTION = "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String TITLE_TRANSLATED = "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";
    /** The container / hotbar key of the same item and its semantic row. */
    private static final String PLAIN_NAME = "Aspect of the End ✪✪✪";
    private static final String SEMANTIC_KEY = "Aspect of the End ⟦MT0⟧";

    /** A colour run separates the sign from its number: the styled key slots "10" after a
     *  literal "+", while the plain key slots "+10" (the dungeon-class row of the report). */
    private static final String BERSERK = "⟦CS0⟧Berserk: +⟦/CS0⟧⟦CS1⟧10❁ Strength⟦/CS1⟧";
    private static final String BERSERK_KEY = "⟦CS0⟧Berserk: +⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦MT1⟧ Strength⟦/CS1⟧";
    private static final String BERSERK_PROJECTION = "⟦CS0⟧狂戰士：+⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦MT1⟧ 力量⟦/CS1⟧";

    /** A projection whose plain copy the validator rejects: stripping the colour markers
     *  leaves a genuine half-transliterated residue ("傑ger" for "Jaeger") glued onto CJK.
     *  (P1.1: a trailing-punctuation-only difference, like the old "Scarf,"/"Scarf" pair
     *  this fixture used before, is no longer flagged — see
     *  TextFilterTest#possessiveSuffixIsStrippedBeforeHalfTransliterationJudgement — so this
     *  fixture uses an unrelated, punctuation-independent half-transliteration instead.) */
    private static final String SCARF = "⟦CS0⟧Forged by⟦/CS0⟧ ⟦CS1⟧Jaeger⟦/CS1⟧ ⟦CS2⟧the master.⟦/CS2⟧";
    private static final String SCARF_PROJECTION = "⟦CS0⟧由⟦/CS0⟧ ⟦CS1⟧傑ger⟦/CS1⟧⟦CS2⟧大師打造。⟦/CS2⟧";

    /** A level badge: its colour-marked key is translatable, but its plain form "Lv27" is a
     *  machine code that can never get a semantic row of its own. */
    private static final String LEVEL = "⟦CS0⟧Lv⟦/CS0⟧⟦CS1⟧27⟦/CS1⟧";
    private static final String LEVEL_KEY = "⟦CS0⟧Lv⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String LEVEL_PROJECTION = "⟦CS0⟧等級⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String LEVEL_TRANSLATED = "⟦CS0⟧等級⟦/CS0⟧⟦CS1⟧27⟦/CS1⟧";

    /** The same item title in another colour topology (same plain key as {@link #TITLE}). */
    private static final String TITLE_OTHER_COLOURS = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ✪✪✪⟦/CS1⟧";

    /** Inline worker pool: keeps submitted tasks until the test runs them, and rejects
     *  beyond its capacity like the bounded production pool. */
    private static final class HeldPool implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        final int capacity;
        int submitted;
        int rejected;

        HeldPool(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public void execute(Runnable task) {
            if (tasks.size() >= capacity) {
                rejected++;
                throw new RejectedExecutionException("pool full");
            }
            submitted++;
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }

    private static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
            @Override public Map<String, String> entries() { return new LinkedHashMap<>(backing); }
        };
    }

    /** Inline backend: counts calls and renders a fixed phrase table. */
    private static Translator counting(AtomicInteger calls) {
        return (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text
                    .replace("Aspect of the End", "終界之刃")
                    .replace("Hello", "你好").replace("World", "世界"), "en");
        };
    }

    private static TranslatorConfig aiConfig() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        cfg.aiChat = true;
        cfg.disableGoogleFallbackForAi = false; // these tests exercise the AI-tier-reads-through-to-GT path
        cfg.churnGuard = false;
        return cfg;
    }

    /** An AI service whose AI cache persists into {@code disk} and records failures into
     *  {@code failures}; the machine cache must never be needed. */
    private static final class Env {
        final AtomicInteger aiCalls = new AtomicInteger();
        final AtomicInteger gtCalls = new AtomicInteger();
        final TranslationCache ai;
        final TranslationService service;

        Env(TranslatorConfig cfg, Map<String, String> disk, Map<String, String> failures) {
            TranslationCache gt = new TranslationCache(counting(gtCalls), "zh-TW", DIRECT, 100);
            ai = new TranslationCache(counting(aiCalls), "zh-TW", DIRECT, 100,
                    10_000L, () -> 0L, inlineStore(disk));
            ai.setFailureStore(inlineStore(failures));
            service = new TranslationService(cfg, gt, ai);
        }
    }

    private static String shown(TranslationDecision decision) {
        return decision.changed() ? TextFilter.stripStyleFallback(decision.translated()) : null;
    }

    /** Size of a private session table (map or collection) inside the cache. */
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

    private static void assertNothingRecorded(Env env, Map<String, String> failures, String label) {
        for (String field : List.of("failedUntil", "contentFailures", "contentRetryAttempts",
                "retrySnapshots", "flights")) {
            assertEquals(0, tracked(env.ai, field), label + ": " + field);
        }
        assertTrue(failures.isEmpty(), label + ": failure ledger " + failures);
        assertEquals(0, env.ai.pendingCount(), label + ": nothing queued or in flight");
    }

    @Test
    void projectionOnlyRowIsDisplayedWithoutRequest() {
        Map<String, String> disk = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Env e = new Env(aiConfig(), disk, failures);

        assertEquals(TITLE_TRANSLATED, shown(e.service.translateItemLine(TITLE)), "first frame");
        assertEquals(TITLE_TRANSLATED, shown(e.service.translateHeld(TITLE)));
        assertTrue(e.service.isTooltipTranslationReady(TITLE), "a BOTH tooltip is ready too");
        List<String> chat = new ArrayList<>();
        e.service.translateChatAsync(TITLE, chat::add);
        assertEquals(List.of(TITLE_TRANSLATED), chat, "chat and tooltip agree");

        assertEquals(0, e.aiCalls.get());
        assertEquals(0, e.gtCalls.get());
        assertNothingRecorded(e, failures, "render, request and flush sides agree it is cached");
    }

    @Test
    void projectionOnlyRowRebuildsSemanticRowOnTick() {
        Map<String, String> disk = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Env e = new Env(aiConfig(), disk, failures);

        e.service.translateItemLine(TITLE);  // render frame: notes the missing semantic row
        assertNull(e.ai.getCached(PLAIN_NAME), "not rebuilt by the render lookup itself");
        e.service.flushBatches();            // one client tick
        assertEquals("終界之刃 ✪✪✪", e.ai.getCached(PLAIN_NAME), "semantic row rebuilt from the projection");

        assertEquals("終界之刃 ✪✪✪", shown(e.service.translateHeld(PLAIN_NAME)),
                "the container / hotbar key reads the same wording");
        assertEquals("終界之刃 ✪✪✪✪✪", shown(e.service.translateHeld("Aspect of the End ✪✪✪✪✪")),
                "every star count shares the rebuilt template");
        assertEquals(TITLE_TRANSLATED, shown(e.service.translateItemLine(TITLE)));
        for (int tick = 0; tick < 5; tick++) {
            e.service.translateItemLine(TITLE);
            e.service.flushBatches();
        }
        assertEquals(0, e.aiCalls.get(), "the rebuild never buys a request");
        assertEquals(Map.of(TITLE_KEY, TITLE_PROJECTION), disk,
                "session memory only: the store is never written, so no row is ever evicted for it");
        assertNothingRecorded(e, failures, "after the rebuild");
    }

    @Test
    void rebuildUsesSnapshotValuesNotProjectionIndices() {
        Map<String, String> disk = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        disk.put(BERSERK_KEY, BERSERK_PROJECTION);
        Env e = new Env(aiConfig(), disk, failures);

        assertEquals("⟦CS0⟧狂戰士：+⟦/CS0⟧⟦CS1⟧10❁ 力量⟦/CS1⟧", shown(e.service.translateItemLine(BERSERK)));
        e.service.flushBatches();

        // The plain key slots "+10", so the literal "+" of the styled template must go.
        assertEquals("狂戰士：+10❁ 力量", shown(e.service.translateItemLine("Berserk: +10❁ Strength")),
                "never \"++10\"");
        assertEquals("狂戰士：+25❁ 力量", shown(e.service.translateItemLine("Berserk: +25❁ Strength")),
                "the rebuilt template carries the sign inside the live slot");
        assertEquals("⟦CS0⟧狂戰士：+⟦/CS0⟧⟦CS1⟧10❁ 力量⟦/CS1⟧", shown(e.service.translateItemLine(BERSERK)),
                "projection and rebuilt row agree, so the exact colours stay");
        assertEquals(0, e.aiCalls.get());
        assertEquals(Map.of(BERSERK_KEY, BERSERK_PROJECTION), disk, "nothing persisted");
    }

    @Test
    void validatorRejectedPlainCopyNeverLoops() {
        Map<String, String> disk = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        disk.put(SCARF, SCARF_PROJECTION);
        Env e = new Env(aiConfig(), disk, failures);
        Map<String, String> before = new LinkedHashMap<>(disk);

        for (int tick = 0; tick < 10; tick++) {
            assertEquals(SCARF_PROJECTION, shown(e.service.translateItemLine(SCARF)),
                    "the projection keeps being displayed");
            e.service.flushBatches();
        }

        assertEquals(0, e.aiCalls.get(), "no request, ever");
        assertEquals(before, disk, "nothing could be rebuilt, so nothing was written");
        assertEquals(1, tracked(e.ai, "unrebuildableProjections"), "rebuild tried once, then remembered");
        assertEquals(0, tracked(e.ai, "pendingSemanticRebuilds"), "not noted again every frame");
        assertNothingRecorded(e, failures, "an unrebuildable projection is not a failure");

        e.ai.invalidate(SCARF); // an explicit retranslate starts the line over
        assertEquals(0, tracked(e.ai, "unrebuildableProjections"), "the memo goes with the rows");
    }

    @Test
    void masterSwitchOffStillDisplaysProjection() {
        TranslatorConfig cfg = aiConfig();
        cfg.translationRequestsEnabled = false;
        Map<String, String> disk = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        disk.put(SCARF, SCARF_PROJECTION);
        Env e = new Env(cfg, disk, failures);

        assertEquals(TITLE_TRANSLATED, shown(e.service.translateItemLine(TITLE)));
        assertEquals(SCARF_PROJECTION, shown(e.service.translateItemLine(SCARF)));
        e.service.flushBatches();
        assertEquals("終界之刃 ✪✪✪", e.ai.getCached(PLAIN_NAME),
                "the local rebuild is cache maintenance and runs while requests are off");
        for (int tick = 0; tick < 5; tick++) {
            e.service.translateItemLine(TITLE);
            e.service.translateItemLine(SCARF);
            e.service.flushBatches();
        }
        assertEquals(0, e.aiCalls.get());
        assertEquals(0, tracked(e.ai, "sessionRetryDemand"));
        assertEquals(Map.of(TITLE_KEY, TITLE_PROJECTION, SCARF, SCARF_PROJECTION), disk,
                "switched off, nothing is written either");
        assertNothingRecorded(e, failures, "switched off");

        cfg.translationRequestsEnabled = true; // back on: both rows are simply cached
        for (int tick = 0; tick < 5; tick++) {
            assertEquals(TITLE_TRANSLATED, shown(e.service.translateItemLine(TITLE)));
            assertEquals(SCARF_PROJECTION, shown(e.service.translateItemLine(SCARF)));
            e.service.flushBatches();
        }
        assertEquals(0, e.aiCalls.get());
    }

    @Test
    void rebuildRunsOnlyOnTheWorkerAndNeverWritesTheStore() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        List<String> writes = new ArrayList<>();
        PersistentStore tracking = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) {
                writes.add("put " + key);
                disk.put(key, value);
            }
            @Override public void clear() { writes.add("clear"); disk.clear(); }
            @Override public void remove(String key) {
                writes.add("remove " + key);
                disk.remove(key);
            }
        };
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", worker::add, 100,
                10_000L, () -> 0L, tracking);

        for (int frame = 0; frame < 3; frame++) {
            assertEquals(TITLE_TRANSLATED, cache.getCached(TITLE), "render frame " + frame);
        }
        assertNull(cache.getCached(PLAIN_NAME), "render lookups do not rebuild");
        cache.flushBatch();                                  // client tick
        assertEquals(1, worker.size(), "the tick only hands one rebuild task to the worker");
        assertNull(cache.getCached(PLAIN_NAME), "nor does the tick itself");

        worker.poll().run();

        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "the worker rebuilt the row");
        assertEquals(TITLE_TRANSLATED, cache.getCached(TITLE));
        assertEquals(List.of(), writes, "no journal write anywhere, so nothing is ever evicted for it");
        assertEquals(0, calls.get());
    }

    @Test
    void pendingSemanticRequestIsNotOverwrittenByAnOlderProjection() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        Translator fresh = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Aspect of the End", "末影之眼"), "en");
        };
        TranslationCache cache = new TranslationCache(fresh, "zh-TW", worker::add, 100,
                10_000L, () -> 0L, inlineStore(disk));

        cache.warmBatchAsync(List.of(PLAIN_NAME));  // e.g. an explicit retranslate re-buys the name
        assertEquals(TITLE_TRANSLATED, cache.getCached(TITLE)); // render frame notes a rebuild
        cache.flushBatch();                          // tick: rebuild task queued behind the request
        assertEquals(2, worker.size());
        Runnable request = worker.pollFirst();
        worker.pollFirst().run();                    // the rebuild runs first: it must step aside
        assertNull(cache.getCached(PLAIN_NAME), "the older projection did not take the row");
        assertEquals(0, tracked(cache, "unrebuildableProjections"), "deferring is not giving up");

        request.run();
        assertEquals(1, calls.get());
        assertEquals("末影之眼 ⟦MT0⟧", disk.get(SEMANTIC_KEY), "the fresh answer owns the semantic row");
        assertEquals("末影之眼 ✪✪✪", cache.getCached(PLAIN_NAME));
    }

    @Test
    void provisionalProjectionIsNeverPromotedToAFinalSemanticRow() {
        Map<String, String> disk = new LinkedHashMap<>();
        java.util.Set<String> provisionalKeys = new java.util.HashSet<>();
        PersistentStore store = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) { put(key, value, false); }
            @Override public void put(String key, String value, boolean provisional) {
                disk.put(key, value);
                if (provisional) provisionalKeys.add(key);
                else provisionalKeys.remove(key);
            }
            @Override public boolean isProvisional(String key) { return provisionalKeys.contains(key); }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, store);
        cache.setBatchWindowMs(() -> 0);

        cache.requestBatched(TITLE);                  // collected as a genuine miss
        store.put(TITLE_KEY, TITLE_PROJECTION, true); // a GT stand-in projection lands meanwhile
        cache.flushBatch();                           // counts as cached; a rebuild is noted
        cache.flushBatch();                           // worker: a stand-in is not final AI wording
        assertNull(cache.getCached(PLAIN_NAME));
        assertNull(disk.get(SEMANTIC_KEY));
        assertEquals(0, calls.get());
    }

    @Test
    void queuedLineWhoseOnlyRowIsItsProjectionIsRebuiltNotBought() {
        // Flush side: the line was a genuine miss when collected, only its projection landed.
        Map<String, String> disk = new LinkedHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        cache.setBatchWindowMs(() -> 0);
        cache.requestBatched(TITLE);
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        cache.flushBatch();                          // cached, as for the render lookup; noted
        assertEquals(0, calls.get());
        assertNull(cache.getCached(PLAIN_NAME));
        cache.flushBatch();                          // next tick: the worker rebuilds the row
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME));

        // Batch side: a warm-up dispatched as a miss finds only the projection on the worker.
        Map<String, String> disk2 = new LinkedHashMap<>();
        Deque<Runnable> worker = new ArrayDeque<>();
        TranslationCache direct = new TranslationCache(counting(calls), "zh-TW", worker::add, 100,
                10_000L, () -> 0L, inlineStore(disk2));
        direct.warmBatchAsync(List.of(TITLE));
        assertEquals(1, worker.size());
        disk2.put(TITLE_KEY, TITLE_PROJECTION);
        worker.poll().run();
        assertEquals("終界之刃 ✪✪✪", direct.getCached(PLAIN_NAME), "rebuilt inline on the worker");
        assertEquals(0, calls.get(), "and never bought");
        assertNull(disk.get(SEMANTIC_KEY));
        assertNull(disk2.get(SEMANTIC_KEY));
    }

    @Test
    void sectionCodeKeyProjectionCountsAsCachedOnTheRequestSide() {
        String styled = "⟦CS0⟧§aHello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        String projectionKey = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧"; // shared, §-free
        Map<String, String> disk = new LinkedHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        cache.setBatchWindowMs(() -> 0);

        cache.requestBatched(styled);                // a miss when collected
        disk.put(projectionKey, "⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧");
        assertEquals("⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", cache.getCached(styled), "render shows it");
        cache.flushBatch();
        cache.flushBatch();
        assertEquals(0, calls.get(), "so the request side must not buy it either");
        assertEquals("你好 世界", cache.getCached("Hello World"), "and its semantic row is rebuilt");
    }

    @Test
    void sectionCodeKeyStandInProjectionIsNotCountedAsCached() {
        String styled = "⟦CS0⟧§aHello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        String projectionKey = "⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧";
        Map<String, String> disk = new LinkedHashMap<>();
        java.util.Set<String> provisionalKeys = new java.util.HashSet<>();
        PersistentStore store = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) { put(key, value, false); }
            @Override public void put(String key, String value, boolean provisional) {
                disk.put(key, value);
                if (provisional) provisionalKeys.add(key);
                else provisionalKeys.remove(key);
            }
            @Override public boolean isProvisional(String key) { return provisionalKeys.contains(key); }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, store);
        cache.setBatchWindowMs(() -> 0);

        cache.requestBatched(styled);                                             // a genuine miss
        store.put(projectionKey, "⟦CS0⟧你好⟦/CS0⟧ ⟦CS1⟧世界⟦/CS1⟧", true);         // only a GT stand-in lands
        cache.flushBatch();
        assertEquals(1, calls.get(), "a stand-in is not final AI wording, exactly as for the render lookup");
    }

    // ---- review K-1: bounded work in the shared, bounded translation pool ----

    @Test
    void untranslatablePlainFormIsTriedOnceThenRemembered() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(LEVEL_KEY, LEVEL_PROJECTION);
        HeldPool pool = new HeldPool(Integer.MAX_VALUE);
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", pool, 100,
                10_000L, () -> 0L, inlineStore(disk));

        for (int tick = 0; tick < 20; tick++) {
            assertEquals(LEVEL_TRANSLATED, cache.getCached(LEVEL), "scoreboard frame " + tick);
            cache.flushBatch();
            pool.runAll();
        }

        assertEquals(1, pool.submitted, "one local try, not one worker task every tick for ever");
        assertEquals(1, tracked(cache, "unrebuildableProjections"));
        assertEquals(0, tracked(cache, "pendingSemanticRebuilds"));
        assertEquals(0, calls.get());
        assertEquals(Map.of(LEVEL_KEY, LEVEL_PROJECTION), disk);
    }

    @Test
    void stalledPoolHoldsOneRebuildTaskAndStillTakesTranslations() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        disk.put(SCARF, SCARF_PROJECTION);
        disk.put(LEVEL_KEY, LEVEL_PROJECTION);
        HeldPool pool = new HeldPool(8); // production: one pool of 512 shared by both caches
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", pool, 100,
                10_000L, () -> 0L, inlineStore(disk));
        cache.setBatchWindowMs(() -> 0);

        for (int tick = 0; tick < 100; tick++) { // workers stalled (AI pacing): nothing runs
            cache.getCached(TITLE);
            cache.getCached(SCARF);
            cache.getCached(LEVEL);
            cache.flushBatch();
        }
        assertEquals(1, pool.tasks.size(), "one outstanding rebuild task, not one per tick");
        assertEquals(0, pool.rejected);

        List<String> chat = new ArrayList<>();
        cache.requestCoalesced("Hello World", chat::add, true); // a genuine miss meanwhile
        cache.flushBatch();
        assertEquals(0, pool.rejected, "the translation batch still finds room in the pool");
        pool.runAll();
        assertEquals(List.of("你好 世界"), chat);
        assertEquals(1, calls.get());
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "and the rebuild still happened");
    }

    @Test
    void rebuildsAtMostSixtyFourLinesPerTick() {
        Map<String, String> disk = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String line = "⟦CS0⟧Sword " + (char) ('A' + i / 26) + (char) ('a' + i % 26) + "⟦/CS0⟧";
            lines.add(line);
            disk.put(line, "⟦CS0⟧劍" + (char) (0x4E00 + i) + "⟦/CS0⟧");
        }
        HeldPool pool = new HeldPool(Integer.MAX_VALUE);
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", pool,
                1_000, 10_000L, () -> 0L, inlineStore(disk));

        for (String line : lines) cache.getCached(line);
        cache.flushBatch();
        pool.runAll();
        assertEquals(64, tracked(cache, "derivedSemanticRows"), "one tick's share");
        cache.flushBatch();
        pool.runAll();
        assertEquals(100, tracked(cache, "derivedSemanticRows"), "the rest on the next tick");
        assertEquals("劍丁", cache.getCached("Sword Ab"));
    }

    // ---- review K-5 / K-8: a pending or newer answer outranks the local copy ----

    @Test
    void collectedRequestForTheRowDefersTheRebuildThenAWaiterIsServed() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        AtomicBoolean requestsOn = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        cache.setBatchWindowMs(() -> 5_000); // collected, not sent before the window closes
        cache.setRequestGate(requestsOn::get);
        List<String> finals = new ArrayList<>();

        cache.requestCoalescedFinal(PLAIN_NAME, finals::add); // e.g. a hotbar label awaiting a final
        assertEquals(TITLE_TRANSLATED, cache.getCached(TITLE));
        cache.flushBatch();
        assertNull(cache.getCached(PLAIN_NAME), "a collected request for the row is not pre-empted");
        assertEquals(0, tracked(cache, "unrebuildableProjections"), "deferring is not giving up");
        assertEquals(List.of(), finals);

        requestsOn.set(false); // switched off before it was sent: the collected request is dropped
        for (int tick = 0; tick < 2; tick++) {
            cache.getCached(TITLE);
            cache.flushBatch();
        }
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "then the row is rebuilt locally");
        assertEquals(List.of("終界之刃 ✪✪✪"), finals, "and the waiting consumer is served by it");
        assertEquals(0, calls.get());
    }

    @Test
    void lineRetranslatedBeforeItsRebuildRunsIsRebuiltFromItsNewProjection() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", worker::add, 100,
                10_000L, () -> 0L, inlineStore(disk));

        cache.getCached(TITLE);
        cache.flushBatch();       // a rebuild task is handed out ...
        cache.invalidate(TITLE);  // ... but the line is retranslated before it runs
        worker.poll().run();
        assertNull(cache.getCached(PLAIN_NAME));
        assertEquals(0, tracked(cache, "unrebuildableProjections"), "the stale task memoises nothing");

        disk.put(TITLE_KEY, "⟦CS0⟧末影之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧"); // the retranslation lands
        cache.getCached(TITLE);
        cache.flushBatch();
        worker.poll().run();
        assertEquals("末影之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "rebuilt from the new projection");
        assertEquals(0, calls.get());
    }

    @Test
    void changeLandingWhileTheCopyIsDerivedOutranksIt() {
        for (String race : List.of("retranslate", "language", "genuine")) {
            Map<String, String> disk = new LinkedHashMap<>();
            disk.put(TITLE_KEY, TITLE_PROJECTION);
            AtomicBoolean armed = new AtomicBoolean();
            TranslationCache[] self = new TranslationCache[1];
            PersistentStore racing = new PersistentStore() {
                @Override public String get(String key) { return disk.get(key); }
                @Override public boolean isProvisional(String key) {
                    // The worker has read the projection and is deriving the copy; at that
                    // moment the client thread retranslates the plain name, switches the
                    // language, or a genuine semantic row lands (here: an import).
                    if (TITLE_KEY.equals(key) && armed.getAndSet(false)) {
                        if (race.equals("retranslate")) self[0].invalidate(PLAIN_NAME);
                        else if (race.equals("language")) self[0].setTargetLang("ja");
                        else self[0].importTranslations(Map.of(SEMANTIC_KEY, "末影之眼 ⟦MT0⟧"));
                    }
                    return false;
                }
                @Override public void put(String key, String value) { disk.put(key, value); }
                @Override public void clear() { disk.clear(); }
                @Override public void remove(String key) { disk.remove(key); }
            };
            Deque<Runnable> worker = new ArrayDeque<>();
            AtomicInteger calls = new AtomicInteger();
            TranslationCache cache = new TranslationCache(counting(calls), "zh-TW", worker::add, 100,
                    10_000L, () -> 0L, racing);
            self[0] = cache;

            cache.getCached(TITLE);
            cache.flushBatch();
            armed.set(true);
            worker.poll().run();
            assertFalse(armed.get(), race + ": the race point was reached");

            if (race.equals("genuine")) {
                assertEquals("末影之眼 ✪✪✪", cache.getCached(PLAIN_NAME), "the genuine row is kept");
                assertEquals("末影之眼 ⟦MT0⟧", disk.get(SEMANTIC_KEY));
            } else {
                assertNull(cache.getCached(PLAIN_NAME), race + ": the outranked copy is not written");
            }
            assertEquals(0, tracked(cache, "derivedSemanticRows"), race);
            assertEquals(0, tracked(cache, "unrebuildableProjections"), race + ": deferred, not given up");
            assertEquals(0, calls.get(), race);
        }
    }

    // ---- review-K2 N-1 / N-2 / N-3 / N-4 ----

    @Test
    void explicitRequestDrainedWhileTheCopyAppearsIsStillSent() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicBoolean armed = new AtomicBoolean();
        PersistentStore racing = new PersistentStore() {
            @Override public String get(String key) {
                // The tick has drained the collected plain request but not yet made it a
                // flight; right then the worker runs the pending rebuild task.
                if (SEMANTIC_KEY.equals(key) && armed.getAndSet(false)) worker.poll().run();
                return disk.get(key);
            }
            @Override public void put(String key, String value) { disk.put(key, value); }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        AtomicInteger calls = new AtomicInteger();
        Translator fresh = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Aspect of the End", "末影之眼"), "en");
        };
        TranslationCache cache = new TranslationCache(fresh, "zh-TW", worker::add, 100,
                10_000L, () -> 0L, racing);
        cache.setBatchWindowMs(() -> 0);

        cache.requestBatched(PLAIN_NAME);   // e.g. a retranslate of the name, collected as a miss
        cache.getCached(TITLE);             // its projection-only line is on screen as well
        armed.set(true);
        cache.flushBatch();                 // the rebuild lands inside the drain window
        assertFalse(armed.get(), "the race point was reached");
        while (!worker.isEmpty()) worker.poll().run();

        assertEquals(1, calls.get(), "the explicit request is still sent");
        assertEquals("末影之眼 ⟦MT0⟧", disk.get(SEMANTIC_KEY), "and its answer replaces the copy, persisted");
        assertEquals("末影之眼 ✪✪✪", cache.getCached(PLAIN_NAME));
    }

    @Test
    void standInAnswerNeverReplacesTheDerivedAiWording() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        AtomicInteger calls = new AtomicInteger();
        Translator fallingBack = (text, target) -> { // the AI engine answered through its GT fallback
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Aspect of", "GT之").replace("the End", "刃"), "en", true);
        };
        TranslationCache cache = new TranslationCache(fallingBack, "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        cache.setBatchWindowMs(() -> 0);
        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "session copy rebuilt");

        cache.requestCoalescedExactStyle(TITLE_OTHER_COLOURS, ignored -> { }, true);
        cache.flushBatch();
        assertEquals(1, calls.get());
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "a GT stand-in never replaces the AI copy");
        assertEquals(TITLE_TRANSLATED, cache.getCached(TITLE), "so the line keeps its AI projection");
        assertNull(disk.get(SEMANTIC_KEY));
    }

    @Test
    void lineRetranslatedWhileItsRebuildRunsIsNotMemoised() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicBoolean armed = new AtomicBoolean();
        TranslationCache[] self = new TranslationCache[1];
        PersistentStore racing = new PersistentStore() {
            @Override public String get(String key) {
                // The task already passed its start check; the line is retranslated now, so
                // its projection is gone by the time the rebuild reads it.
                if (SEMANTIC_KEY.equals(key) && armed.getAndSet(false)) self[0].invalidate(TITLE);
                return disk.get(key);
            }
            @Override public void put(String key, String value) { disk.put(key, value); }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", worker::add,
                100, 10_000L, () -> 0L, racing);
        self[0] = cache;

        cache.getCached(TITLE);
        cache.flushBatch();
        armed.set(true);
        worker.poll().run();
        assertFalse(armed.get(), "the race point was reached");
        assertEquals(0, tracked(cache, "unrebuildableProjections"), "failed only because it was deleted");

        disk.put(TITLE_KEY, "⟦CS0⟧末影之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧"); // the retranslation lands
        cache.getCached(TITLE);
        cache.flushBatch();
        worker.poll().run();
        assertEquals("末影之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "the new projection is rebuilt");
    }

    @Test
    void failingExecutorNeverStopsLaterRebuilds() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicInteger failures = new AtomicInteger(1);
        Executor flaky = task -> {
            if (failures.getAndDecrement() > 0) throw new IllegalStateException("executor unavailable");
            worker.add(task);
        };
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", flaky, 100,
                10_000L, () -> 0L, inlineStore(disk));

        cache.getCached(TITLE);
        assertThrows(IllegalStateException.class, cache::flushBatch);
        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals(1, worker.size(), "the next tick hands the rebuild out again");
        worker.poll().run();
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME));
    }

    @Test
    void rejectedRebuildTaskIsHandedOutAgainOnALaterTick() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        HeldPool pool = new HeldPool(1);
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", pool, 100,
                10_000L, () -> 0L, inlineStore(disk));
        pool.execute(() -> { }); // the only slot is taken by other work

        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals(1, pool.rejected, "the pool was full: this rebuild task was dropped");
        pool.runAll();           // the other work finishes
        cache.getCached(TITLE);
        cache.flushBatch();
        pool.runAll();
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "a dropped task never blocks later rebuilds");
    }

    // ---- review K-2: the session copy never blocks, replaces or exports a genuine row ----

    @Test
    void rebuiltCopyNeverBlocksAGenuineRowAndIsNeverExported() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        AtomicInteger calls = new AtomicInteger();
        Translator otherWording = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("Aspect of", "末影").replace("the End", "之眼"), "en");
        };
        TranslationCache cache = new TranslationCache(otherWording, "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        cache.setBatchWindowMs(() -> 0);
        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals("終界之刃 ✪✪✪", cache.getCached(PLAIN_NAME), "session copy rebuilt");
        assertTrue(cache.hasProjectionOnlyWording(PLAIN_NAME));
        assertFalse(cache.exportTranslations().containsKey(SEMANTIC_KEY),
                "a session copy is not shared; its projection is");

        // Another colour topology of the same item is bought: its semantic copy is genuine.
        List<String> exact = new ArrayList<>();
        cache.requestCoalescedExactStyle(TITLE_OTHER_COLOURS, exact::add, true);
        cache.flushBatch();
        assertEquals(1, calls.get());
        assertEquals("末影 之眼 ⟦MT0⟧", disk.get(SEMANTIC_KEY),
                "the genuine row replaces the session copy and is persisted");
        assertEquals("末影 之眼 ✪✪✪", cache.getCached(PLAIN_NAME));
        assertFalse(cache.hasProjectionOnlyWording(PLAIN_NAME), "no longer a session copy");
        assertTrue(cache.exportTranslations().containsKey(SEMANTIC_KEY));

        // After a restart the container name is simply cached: nothing is bought again.
        AtomicInteger restartCalls = new AtomicInteger();
        TranslationCache restarted = new TranslationCache(counting(restartCalls), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        restarted.setBatchWindowMs(() -> 0);
        restarted.requestBatched(PLAIN_NAME);
        restarted.flushBatch();
        assertEquals("末影 之眼 ✪✪✪", restarted.getCached(PLAIN_NAME));
        assertEquals(0, restartCalls.get());

        // A shared row being imported is taken, not skipped as "already final".
        Map<String, String> disk2 = new LinkedHashMap<>();
        disk2.put(TITLE_KEY, TITLE_PROJECTION);
        TranslationCache importing = new TranslationCache(counting(new AtomicInteger()), "zh-TW", DIRECT,
                100, 10_000L, () -> 0L, inlineStore(disk2));
        importing.getCached(TITLE);
        importing.flushBatch();
        assertEquals("終界之刃 ✪✪✪", importing.getCached(PLAIN_NAME));
        assertEquals(1, importing.importTranslations(Map.of(SEMANTIC_KEY, "終界之刃 ⟦MT0⟧")));
        assertEquals("終界之刃 ⟦MT0⟧", disk2.get(SEMANTIC_KEY), "and persisted");
        assertFalse(importing.hasProjectionOnlyWording(PLAIN_NAME));
    }

    @Test
    void derivedMarkLeavesTogetherWithItsRow() {
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", DIRECT, 3,
                10_000L, () -> 0L, inlineStore(disk));

        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals(1, tracked(cache, "derivedSemanticRows"));
        cache.invalidate(PLAIN_NAME);                      // retranslate of the plain name
        assertEquals(0, tracked(cache, "derivedSemanticRows"), "invalidate");
        assertFalse(cache.hasProjectionOnlyWording(PLAIN_NAME));

        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals(1, tracked(cache, "derivedSemanticRows"));
        cache.setTargetLang("ja");                          // language switch
        assertEquals(0, tracked(cache, "derivedSemanticRows"), "reset");

        cache.setTargetLang("zh-TW");
        cache.getCached(TITLE);
        cache.flushBatch();
        assertEquals(1, tracked(cache, "derivedSemanticRows"));
        for (int i = 0; i < 5; i++) cache.importTranslations(Map.of("Filler " + (char) ('a' + i), "填充"));
        assertEquals(0, tracked(cache, "derivedSemanticRows"), "evicted from the 3-row memory tier");
    }

    // ---- review K-6: a projection that lost a protected name is left exactly as before ----

    @Test
    void mangledProjectionOnlyLineIsLeftAsBeforeWithoutARequest() {
        String line = "⟦CS0⟧Qzaa's⟦/CS0⟧ ⟦CS1⟧Sword⟦/CS1⟧";
        String maskedKey = "⟦CS0⟧⟦0⟧'s⟦/CS0⟧ ⟦CS1⟧Sword⟦/CS1⟧";
        Map<String, String> disk = new LinkedHashMap<>();
        disk.put(maskedKey, "⟦CS0⟧某人的⟦/CS0⟧ ⟦CS1⟧劍⟦/CS1⟧"); // an old projection that lost the name
        AtomicInteger aiCalls = new AtomicInteger();
        Translator ai = (text, target) -> {
            aiCalls.incrementAndGet();
            return new TranslationResult(text.replace("Sword", "劍"), "en");
        };
        TranslationCache gt = new TranslationCache(counting(new AtomicInteger()), "zh-TW", DIRECT, 100);
        TranslationCache aiCache = new TranslationCache(ai, "zh-TW", DIRECT, 100, 10_000L, () -> 0L,
                inlineStore(disk));
        TranslationService service = new TranslationService(aiConfig(), gt, aiCache);
        service.setProtectedNames(() -> java.util.Set.of("Qzaa"));

        for (int tick = 0; tick < 5; tick++) {
            assertFalse(service.translateItemLine(line).changed(), "a lost name is never displayed");
            service.flushBatches();
        }
        assertEquals(0, aiCalls.get(), "and, as before 1.0.7, the line is neither deleted nor re-bought");
        assertTrue(disk.containsKey(maskedKey));

        // No copy of the damaged wording covers the plain form: it is requested exactly as
        // before 1.0.7 (its row was missing) and a good answer displays.
        service.translateItemLine("Qzaa's Sword");
        service.flushBatches();
        service.flushBatches();
        assertEquals(1, aiCalls.get());
        assertEquals("Qzaa's 劍", service.translateItemLine("Qzaa's Sword").translated());
    }

    @Test
    void mangledGtProjectionIsLeftButAMangledGtSemanticRowStillSelfHeals() {
        String line = "⟦CS0⟧Qzaa's⟦/CS0⟧ ⟦CS1⟧Sword⟦/CS1⟧";
        String maskedKey = "⟦CS0⟧⟦0⟧'s⟦/CS0⟧ ⟦CS1⟧Sword⟦/CS1⟧";
        String plainKey = "⟦0⟧'s Sword";
        for (boolean projectionRow : new boolean[] {true, false}) {
            TranslatorConfig cfg = aiConfig();
            cfg.translationRequestsEnabled = false; // cache-only: the AI tier reads through to GT
            Map<String, String> gtDisk = new LinkedHashMap<>();
            if (projectionRow) gtDisk.put(maskedKey, "⟦CS0⟧某人的⟦/CS0⟧ ⟦CS1⟧劍⟦/CS1⟧"); // lost the name
            else gtDisk.put(plainKey, "某人的 劍");                                           // lost the name
            AtomicInteger calls = new AtomicInteger();
            TranslationCache gt = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100, 10_000L,
                    () -> 0L, inlineStore(gtDisk));
            TranslationCache ai = new TranslationCache(counting(calls), "zh-TW", DIRECT, 100, 10_000L,
                    () -> 0L, inlineStore(new LinkedHashMap<>()));
            TranslationService service = new TranslationService(cfg, gt, ai);
            service.setProtectedNames(() -> java.util.Set.of("Qzaa"));

            for (int tick = 0; tick < 3; tick++) {
                assertFalse(service.translateItemLine(line).changed(), "a lost name is never displayed");
                service.flushBatches();
            }
            if (projectionRow) {
                assertTrue(gtDisk.containsKey(maskedKey),
                        "a GT projection displayable only since 1.0.7 is left exactly as before");
            } else {
                assertFalse(gtDisk.containsKey(plainKey),
                        "a damaged GT semantic row still self-heals: deleted so it is bought again");
            }
            assertEquals(0, calls.get(), "requests are switched off");
        }
    }

    @Test
    void projectionOnlyWordingNeedsTheLinesOwnProjection() {
        Map<String, String> disk = new LinkedHashMap<>();
        TranslationCache cache = new TranslationCache(counting(new AtomicInteger()), "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, inlineStore(disk));
        assertFalse(cache.hasProjectionOnlyWording(TITLE),
                "no own wording at all (a lower-tier read-through, say) is not projection-only");
        disk.put(TITLE_KEY, TITLE_PROJECTION);
        assertTrue(cache.hasProjectionOnlyWording(TITLE), "its own final projection and no semantic row");
        disk.put(SEMANTIC_KEY, "終界之刃 ⟦MT0⟧");
        assertFalse(cache.hasProjectionOnlyWording(TITLE), "a genuine semantic row");
        assertFalse(cache.hasProjectionOnlyWording(PLAIN_NAME));
    }

    // ---- review K-3: item-name reconciliation buys nothing for projection-only wording ----

    @Test
    void itemNameReconciliationIgnoresWordingKnownOnlyFromAProjection() {
        String name = "Aspect of the End";
        String nameMarked = "⟦CS0⟧Aspect of the End⟦/CS0⟧";
        String titleMarked = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧Sharp⟦/CS1⟧";
        String titlePlain = "Aspect of the End Sharp";
        // titleDerived: the title's semantic row was evicted (rebuilt from its projection);
        // titleMarkedBeforeTick: the same, compared through the colour-marked title before
        // any tick; nameDerived: the isolated name exists only as a coloured label's
        // projection; control: every row genuine, as before 1.0.7.
        for (String kase : List.of("titleDerived", "titleMarkedBeforeTick", "nameDerived", "control")) {
            Map<String, String> disk = new LinkedHashMap<>();
            disk.put(titleMarked, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧鋒利⟦/CS1⟧");
            if (kase.equals("nameDerived")) disk.put(nameMarked, "⟦CS0⟧末影之視⟦/CS0⟧");
            else disk.put(name, "末影之視");
            if (kase.equals("nameDerived") || kase.equals("control")) disk.put(titlePlain, "終界之刃 鋒利");
            Env e = new Env(aiConfig(), disk, new LinkedHashMap<>());

            e.service.translateItemLine(titleMarked);                       // tooltip title shown
            if (kase.equals("nameDerived")) e.service.translateHeld(nameMarked); // coloured label shown
            boolean marked = kase.equals("titleMarkedBeforeTick");
            if (!marked) e.service.flushBatches();                          // missing rows rebuilt
            List<String> tooltip = List.of(marked ? titleMarked : titlePlain, "Ability: Instant Transmission");
            for (int frame = 0; frame < 3; frame++) {
                e.service.reconcileItemNameWithTooltip(name, tooltip);
                e.service.flushBatches();
            }

            if (kase.equals("control")) {
                assertEquals(1, e.aiCalls.get(), "control: genuine rows are reconciled (suffix bought)");
            } else {
                assertEquals(0, e.aiCalls.get(), kase + ": projection-only wording buys no request");
                assertEquals(kase.equals("nameDerived") ? null : "末影之視", disk.get(name),
                        kase + ": and rewrites no name row");
            }
        }
    }
}
