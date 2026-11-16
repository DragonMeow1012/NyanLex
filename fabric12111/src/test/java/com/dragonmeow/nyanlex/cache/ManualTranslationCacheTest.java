package com.dragonmeow.nyanlex.cache;

import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A manual translation outranks every automatic source and no automatic path may send a request
 * for it, overwrite it or hide it. The proof is the call count of a fake backend. Everything is an
 * inline fake; the language-file test uses a temporary directory.
 */
class ManualTranslationCacheTest {

    private static final Executor DIRECT = Runnable::run;

    private static final String SHARP = "Sharpness";
    private static final String MANUAL_SHARP = "手動鋒利";

    /** A coloured line (two colour runs, the stars are one live value) and its keys. */
    private static final String TITLE = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";
    private static final String TITLE_PLAIN = "Aspect of the End ✪✪✪";
    private static final String TITLE_SEMANTIC = "Aspect of the End ⟦MT0⟧";
    private static final String TITLE_COLOUR_KEY = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    /** The same words coloured another way. */
    private static final String TITLE_OTHER = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ✪✪✪⟦/CS1⟧";
    private static final String TITLE_OTHER_COLOUR_KEY = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ⟦MT0⟧⟦/CS1⟧";
    private static final String MANUAL_TITLE = "我的終界之刃 ⟦MT0⟧";
    private static final String MANUAL_TITLE_COLOUR = "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String MANUAL_TITLE_OTHER_COLOUR = "⟦CS0⟧我的終界⟦/CS0⟧⟦CS1⟧之刃 ⟦MT0⟧⟦/CS1⟧";

    /** Inline store that also records the provisional flag. */
    private static final class MapStore implements PersistentStore {
        final Map<String, String> rows = new LinkedHashMap<>();
        final Set<String> provisional = new HashSet<>();
        final AtomicInteger gets = new AtomicInteger();

        @Override public String get(String key) {
            gets.incrementAndGet();
            return rows.get(key);
        }
        @Override public void put(String key, String value) { put(key, value, false); }
        @Override public void put(String key, String value, boolean isProvisional) {
            rows.put(key, value);
            if (isProvisional) provisional.add(key);
            else provisional.remove(key);
        }
        @Override public boolean isProvisional(String key) { return provisional.contains(key); }
        @Override public void clear() { rows.clear(); provisional.clear(); }
        @Override public void remove(String key) { rows.remove(key); provisional.remove(key); }
        @Override public Map<String, String> entries() { return new LinkedHashMap<>(rows); }
        @Override public int size() { return rows.size(); }
    }

    /** Counting backend: renders "<prefix><text>", can fail, can run a hook inside the call. */
    private static final class Backend implements Translator {
        final String prefix;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> seen = new ArrayList<>();
        boolean fail;
        boolean fallbackProduct;
        Runnable duringCall = () -> { };
        /** When set, renders the answer instead of the prefix. */
        java.util.function.UnaryOperator<String> render;

        Backend(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public TranslationResult translate(String text, String targetLang) throws TranslationException {
            calls.incrementAndGet();
            seen.add(text);
            duringCall.run();
            if (fail) throw new TranslationException("backend down");
            return new TranslationResult(render != null ? render.apply(text) : prefix + text,
                    "en", fallbackProduct);
        }
    }

    private static String shown(String lookup) {
        return TextFilter.stripStyleFallback(lookup);
    }

    private static TranslationCache cache(Backend backend, MapStore store, ManualTranslations manual) {
        TranslationCache cache = new TranslationCache(backend, "zh-TW", DIRECT, 100,
                10_000L, () -> 0L, store);
        cache.setManualTranslations(manual);
        return cache;
    }

    private static ManualTranslations manual() {
        return new ManualTranslations(new MapStore());
    }

    private static void ticks(TranslationCache cache) {
        for (int i = 0; i < 8; i++) cache.flushBatch();
    }

    // ------------------------------------------------------------------ priority

    @Test
    void manualWinsOverAiAndMachineRowsOnEveryRead() {
        ManualTranslations manual = manual();
        Backend aiBackend = new Backend("AI:");
        Backend gtBackend = new Backend("GT:");
        MapStore aiRows = new MapStore();
        TranslationCache ai = cache(aiBackend, aiRows, manual);
        TranslationCache gt = cache(gtBackend, new MapStore(), manual);
        ai.setFallback(gt, true);
        ai.requestAsync(SHARP);
        gt.requestAsync(SHARP);
        assertEquals("AI:Sharpness", ai.getCached(SHARP));
        assertEquals("GT:Sharpness", gt.getCached(SHARP));

        manual.put(SHARP, MANUAL_SHARP);

        for (TranslationCache cache : List.of(ai, gt)) {
            for (String source : List.of(SHARP, "  Sharpness ", "§aSharpness", "§a§lSharpness")) {
                assertEquals(MANUAL_SHARP, cache.getCached(source), source);
                assertEquals(MANUAL_SHARP, cache.getCachedFinal(source), source);
                assertEquals(MANUAL_SHARP, cache.peekFinal(source, false), source);
                assertEquals(MANUAL_SHARP, cache.peekFinal(source, true), source);
            }
        }
        assertEquals("AI:Sharpness", aiRows.rows.get(SHARP), "the automatic row is not touched");

        manual.remove(SHARP);
        assertEquals("AI:Sharpness", ai.getCached(SHARP), "reverting shows the automatic wording again");
        assertEquals("GT:Sharpness", gt.getCached(SHARP));
    }

    @Test
    void manualBeatsALearnedKeepOriginalVerdict() {
        ManualTranslations manual = manual();
        MapStore rows = new MapStore();
        rows.rows.put(SHARP, "\u0000MT_KEEP_ORIGINAL2"); // the cache learned "needs no translation"
        TranslationCache cache = cache(new Backend("AI:"), rows, manual);
        assertEquals(SHARP, cache.getCached(SHARP), "keep-original shows the original");

        manual.put(SHARP, MANUAL_SHARP);
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
        assertEquals(MANUAL_SHARP, cache.getCachedFinal(SHARP));
        assertEquals(MANUAL_SHARP, cache.peekFinal(SHARP, false));
    }

    @Test
    void numberVariantsShareOneManualTemplate() {
        ManualTranslations manual = manual();
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual);
        assertEquals("You got ⟦MT0⟧ coins", cache.semanticKey("You got 5 coins"));
        assertEquals(cache.semanticKey("You got 5 coins"), cache.semanticKey("§e You got §699 coins"));

        manual.put(cache.semanticKey("You got 5 coins"), "你拿到 ⟦MT0⟧ 個金幣");

        assertEquals("你拿到5個金幣", cache.getCached("You got 5 coins"));
        assertEquals("你拿到99個金幣", cache.getCached("You got 99 coins"));
        assertEquals("你拿到1,250個金幣", cache.getCachedFinal("You got 1,250 coins"));
        assertEquals("你拿到7個金幣", cache.getCached("§aYou got §67 coins"));
    }

    @Test
    void theSemanticKeyIsAFixedPointForTheKeysTheCachesStore() {
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual());
        for (String source : List.of(SHARP, "You got 5 coins", "⟦0⟧ joined the game", "Kills: 12 / 30",
                "Aspect of the End ✪✪✪", "Cost: 1,000 coins  Stock: 5", "Line one⟦PB0⟧Line two ⟦MT0⟧",
                TITLE, "§eGold §6Coins: §f123")) {
            String key = cache.semanticKey(source);
            assertEquals(key, cache.semanticKey(key), "storing under the key must find it again: " + source);
        }
    }

    // ------------------------------------------------------------------ zero requests

    private static void callEveryEntryPoint(TranslationCache cache, List<String> got) {
        cache.requestBatched(SHARP);
        cache.requestBatchedPassive(SHARP);
        cache.requestAsync(SHARP);
        cache.requestAsync(SHARP, got::add);
        cache.translateAsyncAlways(SHARP, got::add);
        got.add(cache.translateBlocking(SHARP));
        cache.requestCoalesced(SHARP, got::add, true);
        cache.requestCoalescedHigh(SHARP, got::add, true);
        cache.requestCoalescedExactStyle(SHARP, got::add, true);
        cache.requestCoalescedFinal(SHARP, got::add);
        cache.requestCoalescedExactStyleFinal(SHARP, got::add);
        cache.warmBatch(List.of(SHARP, "§aSharpness"));
        cache.warmBatch(List.of(SHARP), List.of(SHARP, "", "Other"));
        cache.warmBatchAsync(List.of(SHARP));
        cache.warmBatchAsync(List.of(SHARP), List.of(SHARP));
        cache.warmBatchAsyncHigh(List.of(SHARP), List.of(SHARP));
        cache.translateAllAsync(List.of(SHARP), results -> got.addAll(results));
        TranslationCache.collectWarmLane(() -> cache.warmBatchAsync(List.of(SHARP)));
        ticks(cache);
    }

    @Test
    void noEntryPointSendsARequestForAManualSentence() {
        for (boolean windowed : new boolean[] {false, true}) {
            for (String engine : List.of("ai", "gt")) {
                ManualTranslations manual = manual();
                manual.put(SHARP, MANUAL_SHARP);
                Backend backend = new Backend("T:");
                TranslationCache cache = cache(backend, new MapStore(), manual);
                if (windowed) {
                    cache.setBatchWindowMs(() -> 0);
                    cache.setWindowedBatching(engine.equals("ai"));
                }
                List<String> got = new ArrayList<>();

                callEveryEntryPoint(cache, got);

                String label = engine + (windowed ? " windowed" : "");
                assertEquals(0, backend.calls.get(), label + ": no request, whichever way it is asked for");
                assertFalse(got.isEmpty(), label);
                for (String value : got) assertEquals(MANUAL_SHARP, value, label);
                assertEquals(0, cache.pendingCount(), label + ": nothing queued or in flight");
            }
        }
    }

    @Test
    void aSentenceQueuedBeforeTheEditIsDroppedWithoutARequest() {
        for (boolean edited : new boolean[] {false, true}) {
            ManualTranslations manual = manual();
            Backend backend = new Backend("AI:");
            TranslationCache cache = cache(backend, new MapStore(), manual);
            List<String> got = new ArrayList<>();
            cache.requestCoalesced(SHARP, got::add, true);   // a miss: queued, the caller waits
            assertEquals(1, cache.pendingCount());
            if (edited) manual.put(SHARP, MANUAL_SHARP);

            ticks(cache);

            if (edited) {
                assertEquals(0, backend.calls.get(), "the queued entry is dropped before it is sent");
                assertEquals(List.of(MANUAL_SHARP), got, "and its caller gets the player's wording");
            } else {
                assertEquals(1, backend.calls.get(), "control: without the edit the request goes out");
                assertEquals(List.of("AI:Sharpness"), got);
            }
            assertEquals(0, cache.pendingCount());
        }
    }

    @Test
    void aResponseAlreadyOnItsWayNeverChangesWhatIsShown() {
        ManualTranslations manual = manual();
        MapStore rows = new MapStore();
        Backend backend = new Backend("AI:");
        // The player saves while the request is in the air.
        backend.duringCall = () -> manual.put(SHARP, MANUAL_SHARP);
        TranslationCache cache = cache(backend, rows, manual);
        List<String> got = new ArrayList<>();
        cache.requestCoalesced(SHARP, got::add, true);

        ticks(cache);

        assertEquals(1, backend.calls.get(), "it was already sent");
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
        assertEquals(List.of(MANUAL_SHARP), got, "the waiting caller is answered with the manual wording");
        assertEquals("AI:Sharpness", rows.rows.get(SHARP), "the answer is kept as the automatic row");

        manual.remove(SHARP);
        assertEquals("AI:Sharpness", cache.getCached(SHARP), "and shows again after a revert");
        assertEquals(1, backend.calls.get());
    }

    @Test
    void failureBackoffAndDueRetriesNeverResendAManualSentence() {
        for (boolean edited : new boolean[] {false, true}) {
            long[] now = {0L};
            ManualTranslations manual = manual();
            Backend backend = new Backend("AI:");
            backend.fail = true;
            TranslationCache cache = new TranslationCache(backend, "zh-TW", DIRECT, 100,
                    10_000L, () -> now[0], new MapStore());
            cache.setManualTranslations(manual);
            cache.requestCoalesced(SHARP, ignored -> { }, true);
            ticks(cache);
            assertEquals(1, backend.calls.get());
            assertTrue(cache.hasFailureState(SHARP), "the request failed and is in backoff");

            if (edited) {
                manual.put(SHARP, MANUAL_SHARP);
                assertFalse(cache.hasFailureState(SHARP), "at once: the old failure no longer counts");
                assertFalse(cache.mayUseFallback(SHARP));
            }
            now[0] += 3_600_000L;
            ticks(cache);
            ticks(cache);

            if (edited) {
                assertEquals(1, backend.calls.get(), "no retry for a sentence the player wrote");
                assertFalse(cache.hasFailureState(SHARP));
                assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
            } else {
                assertTrue(backend.calls.get() >= 2, "control: the retry does run without the edit");
            }
        }
    }

    @Test
    void aProvisionalStandInIsNotUpgradedWhileTheSentenceIsManual() {
        ManualTranslations manual = manual();
        Backend backend = new Backend("GT:");
        backend.fallbackProduct = true; // the AI engine fell back: the answer is a stand-in
        MapStore rows = new MapStore();
        TranslationCache cache = cache(backend, rows, manual);
        cache.setProvisionalRetryGate(() -> true);
        cache.requestAsync(SHARP);
        assertEquals(1, backend.calls.get());
        assertTrue(rows.provisional.contains(SHARP));

        manual.put(SHARP, MANUAL_SHARP);
        assertFalse(cache.hasFailureState(SHARP), "a stand-in was a pending upgrade; not any more");
        assertFalse(cache.mayUseFallback(SHARP), "and no stand-in engine may be started for it");
        for (int i = 0; i < 6; i++) {
            assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
            assertEquals(MANUAL_SHARP, cache.getCachedFinal(SHARP), "manual is final, not a stand-in");
            cache.flushBatch();
        }
        assertEquals(1, backend.calls.get(), "no AI supplement for a manual sentence");

        manual.remove(SHARP);
        assertEquals("GT:Sharpness", cache.getCached(SHARP));
        assertEquals(2, backend.calls.get(), "after the revert the stand-in is upgraded again, as before");
    }

    @Test
    void aFallbackEngineIsNeverStartedForAManualSentence() {
        for (boolean edited : new boolean[] {false, true}) {
            ManualTranslations manual = manual();
            Backend aiBackend = new Backend("AI:");
            aiBackend.fail = true;
            Backend gtBackend = new Backend("GT:");
            TranslationCache ai = cache(aiBackend, new MapStore(), manual);
            TranslationCache gt = cache(gtBackend, new MapStore(), manual);
            ai.setFallback(gt, true);
            ai.setProvisionalRetryGate(() -> true);
            // The request fails while the player is editing the sentence: the failure path must stay quiet.
            if (edited) aiBackend.duringCall = () -> manual.put(SHARP, MANUAL_SHARP);
            ai.requestCoalesced(SHARP, ignored -> { }, true);
            ticks(ai);
            ticks(gt);

            assertEquals(1, aiBackend.calls.get());
            if (edited) {
                assertEquals(0, gtBackend.calls.get(), "no stand-in is bought for it");
                assertFalse(ai.hasFailureState(SHARP));
                assertEquals(MANUAL_SHARP, ai.getCached(SHARP));
            } else {
                assertTrue(gtBackend.calls.get() >= 1, "control: the stand-in engine does start");
            }
        }
    }

    @Test
    void aLateAnswerForAManualParagraphIsNotReviewedWithAnExtraRequest() {
        // An answer that lost one paragraph break is normally sent once more for review.
        String key = "First⟦PB0⟧Second⟦PB1⟧Third";
        for (boolean edited : new boolean[] {false, true}) {
            ManualTranslations manual = manual();
            Backend backend = new Backend("");
            backend.render = text -> "第一⟦PB0⟧第二第三";
            TranslationCache cache = cache(backend, new MapStore(), manual);
            if (edited) {
                backend.duringCall = () -> manual.put(cache.semanticKey(key), "手動一⟦PB0⟧手動二⟦PB1⟧手動三");
            }
            cache.requestCoalesced(key, ignored -> { }, true);
            ticks(cache);

            if (edited) {
                assertEquals(1, backend.calls.get(), "only the request that was already out; no review");
                assertEquals("手動一⟦PB0⟧手動二⟦PB1⟧手動三", cache.getCached(key));
            } else {
                assertEquals(2, backend.calls.get(), "control: the review request does go out");
            }
        }
    }

    @Test
    void aRequestHandedToAWorkerBeforeTheEditIsNotSent() {
        java.util.Deque<Runnable> worker = new java.util.ArrayDeque<>();
        ManualTranslations manual = manual();
        Backend backend = new Backend("AI:");
        TranslationCache cache = new TranslationCache(backend, "zh-TW", worker::add, 100,
                10_000L, () -> 0L, new MapStore());
        cache.setManualTranslations(manual);
        List<String> got = new ArrayList<>();
        cache.requestAsync(SHARP, got::add);   // the single-request path: the task waits for the worker
        assertEquals(1, worker.size());

        manual.put(SHARP, MANUAL_SHARP);
        while (!worker.isEmpty()) worker.poll().run();

        assertEquals(0, backend.calls.get(), "the worker finds the sentence settled and sends nothing");
        assertEquals(List.of(MANUAL_SHARP), got);
    }

    @Test
    void aFinalWaiterLeftBehindNeverBuysAnUpgradeForAManualSentence() {
        for (boolean edited : new boolean[] {false, true}) {
            long[] now = {0L};
            ManualTranslations manual = manual();
            Backend backend = new Backend("GT:");
            backend.fallbackProduct = true; // only ever a stand-in
            TranslationCache cache = new TranslationCache(backend, "zh-TW", DIRECT, 100,
                    10_000L, () -> now[0], new MapStore());
            cache.setManualTranslations(manual);
            cache.setProvisionalRetryGate(() -> true);
            cache.requestCoalescedFinal(SHARP, ignored -> { });
            ticks(cache);
            int before = backend.calls.get();
            assertTrue(before >= 1);

            if (edited) manual.put(SHARP, MANUAL_SHARP); // and nobody calls notifyManualChanged
            now[0] += 3_600_000L;
            ticks(cache);
            ticks(cache);

            if (edited) assertEquals(before, backend.calls.get(), "the waiter does not start an upgrade");
            else assertTrue(backend.calls.get() > before, "control: without the edit the upgrade is retried");
        }
    }

    @Test
    void aFailureForAColouredManualSentenceLeavesNoLedgerRow() {
        for (boolean edited : new boolean[] {false, true}) {
            ManualTranslations manual = manual();
            MapStore failures = new MapStore();
            Backend backend = new Backend("AI:");
            backend.fail = true;
            TranslationCache cache = cache(backend, new MapStore(), manual);
            cache.setFailureStore(failures);
            if (edited) {
                backend.duringCall = () -> {
                    Map<String, String> rows = new LinkedHashMap<>();
                    rows.put(TITLE_SEMANTIC, MANUAL_TITLE);
                    rows.put(TITLE_COLOUR_KEY, MANUAL_TITLE_COLOUR);
                    manual.putAll(rows);
                };
            }
            cache.requestCoalescedExactStyle(TITLE, ignored -> { }, true);
            ticks(cache);

            assertEquals(1, backend.calls.get());
            if (edited) assertTrue(failures.rows.isEmpty(), "nothing is recorded for a sentence settled by hand");
            else assertFalse(failures.rows.isEmpty(), "control: a failure is recorded without the edit");
        }
    }

    // ------------------------------------------------------------------ what automatic paths cannot do

    @Test
    void replaceInvalidateAndClearLeaveTheManualSentenceAlone() {
        ManualTranslations manual = manual();
        MapStore rows = new MapStore();
        Backend backend = new Backend("AI:");
        TranslationCache cache = cache(backend, rows, manual);
        cache.requestAsync(SHARP);
        manual.put(SHARP, MANUAL_SHARP);
        int calls = backend.calls.get();

        assertFalse(cache.replaceFinal(SHARP, "別人的鋒利"), "a tooltip title cannot overwrite it");
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
        assertEquals("AI:Sharpness", rows.rows.get(SHARP), "and replaceFinal invalidated nothing on its way out");

        cache.invalidate(SHARP);   // R and P
        assertNull(rows.rows.get(SHARP));
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
        cache.requestBatched(SHARP);
        cache.requestAsync(SHARP);
        ticks(cache);
        assertEquals(calls, backend.calls.get(), "nothing is bought again either");

        cache.clear();             // "clear stored translations"
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP));
        assertEquals(MANUAL_SHARP, manual.get(SHARP));
        assertEquals(calls, backend.calls.get());
    }

    @Test
    void importedRowsAndTheOtherCacheCannotOverwriteAManualRow() {
        ManualTranslations manual = manual();
        TranslationCache ai = cache(new Backend("AI:"), new MapStore(), manual);
        manual.put(SHARP, MANUAL_SHARP);

        assertEquals(1, ai.importTranslations(Map.of(SHARP, "匯入的鋒利")), "the import lands in the AI file");
        assertEquals(MANUAL_SHARP, ai.getCached(SHARP), "but never shows over the manual row");
        assertEquals(MANUAL_SHARP, manual.get(SHARP));
    }

    @Test
    void theManualFileFollowsTheTargetLanguage(@TempDir Path dir) {
        ManualTranslations manual = ManualTranslations.open(dir, "nyanlex", "zh-TW");
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual);
        manual.put(SHARP, MANUAL_SHARP);
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP));

        cache.setTargetLang("ja-JP");
        assertNull(manual.get(SHARP), "another language, another manual file");
        assertEquals("AI:Sharpness", cache.translateBlocking(SHARP));

        cache.setTargetLang("zh-TW");
        assertEquals(MANUAL_SHARP, cache.getCached(SHARP), "and the first one is back, untouched");
    }

    @Test
    void anEmptyManualStoreCostsTheRenderPathNoLookup() {
        MapStore manualRows = new MapStore();
        ManualTranslations manual = new ManualTranslations(manualRows);
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual);
        cache.requestAsync(SHARP);
        manualRows.gets.set(0);

        for (int i = 0; i < 200; i++) {
            assertEquals("AI:Sharpness", cache.getCached(SHARP));
            assertEquals("AI:Sharpness", cache.getCachedFinal(SHARP));
            assertFalse(cache.hasManualTranslation(SHARP));
        }
        assertEquals(0, manualRows.gets.get(), "with no manual row the manual store is never even read");
    }

    @Test
    void aWaitingFinalCallerIsAnsweredWhenTheManualRowIsSaved() {
        ManualTranslations manual = manual();
        Backend backend = new Backend("GT:");
        backend.fallbackProduct = true;
        TranslationCache cache = cache(backend, new MapStore(), manual);
        cache.setProvisionalRetryGate(() -> false); // the AI is down: only a stand-in exists
        List<String> got = new ArrayList<>();
        cache.requestCoalescedFinal(SHARP, got::add);
        ticks(cache);
        assertTrue(got.isEmpty(), "a stand-in is not final");

        manual.put(SHARP, MANUAL_SHARP);
        cache.notifyManualChanged(SHARP);

        assertEquals(List.of(MANUAL_SHARP), got);
    }

    // ------------------------------------------------------------------ colour

    private static String colourKeyOf(String source) {
        return TextFilter.stripSectionCodes(new TranslationTemplate().prepare(source).key());
    }

    @Test
    void theColourKeysAreWhatTheFixturesSay() {
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual());
        assertEquals(TITLE_SEMANTIC, cache.semanticKey(TITLE));
        assertEquals(TITLE_COLOUR_KEY, colourKeyOf(TITLE));
        assertEquals(TITLE_OTHER_COLOUR_KEY, colourKeyOf(TITLE_OTHER));
        assertEquals(TITLE_SEMANTIC, TranslationCache.semanticOfColorKey(TITLE_COLOUR_KEY));
        assertEquals(TITLE_SEMANTIC, TranslationCache.semanticOfColorKey(TITLE_OTHER_COLOUR_KEY));
        assertNull(TranslationCache.semanticOfColorKey(TITLE_SEMANTIC));
    }

    @Test
    void aManualColourRowGivesExactColoursWithoutAFallbackMark() {
        ManualTranslations manual = manual();
        Backend backend = new Backend("AI:");
        TranslationCache cache = cache(backend, new MapStore(), manual);
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(TITLE_SEMANTIC, MANUAL_TITLE);
        rows.put(TITLE_COLOUR_KEY, MANUAL_TITLE_COLOUR);
        manual.putAll(rows);
        String exact = "⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";

        assertEquals(exact, cache.getCached(TITLE), "the colour runs stay on their own words");
        assertFalse(TextFilter.isStyleFallback(cache.getCached(TITLE)), "and it is no style fallback");
        assertEquals(exact, cache.getCachedFinal(TITLE));
        assertEquals(exact, cache.peekFinal(TITLE, true), "an exact-style consumer (chat) accepts it");
        List<String> chat = new ArrayList<>();
        cache.requestCoalescedExactStyle(TITLE, chat::add, true);
        cache.requestCoalescedExactStyleFinal(TITLE, chat::add);
        assertEquals(List.of(exact, exact), chat);
        assertEquals("我的終界之刃 ✪✪✪", cache.getCached(TITLE_PLAIN), "the plain line reads the plain row");
        assertEquals(0, backend.calls.get());
    }

    @Test
    void aColourRowThatDisagreesWithThePlainRowFallsBackToTheConservativeProjection() {
        ManualTranslations manual = manual();
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual);
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(TITLE_SEMANTIC, MANUAL_TITLE);
        rows.put(TITLE_COLOUR_KEY, "⟦CS0⟧完全不同的字⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        manual.putAll(rows);

        String hit = cache.getCached(TITLE);
        assertTrue(TextFilter.isStyleFallback(hit), "two wordings must not both show: the plain row wins");
        assertEquals("我的終界之刃 ✪✪✪", shown(hit));
        assertTrue(TextFilter.isStyleFallback(cache.peekFinal(TITLE, true)));
    }

    @Test
    void aBrokenColourRowIsIgnored() {
        ManualTranslations manual = manual();
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual);
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(TITLE_SEMANTIC, MANUAL_TITLE);
        rows.put(TITLE_COLOUR_KEY, "⟦CS0⟧我的終界之刃 ⟦CS1⟧⟦MT0⟧"); // damaged file: runs not closed
        manual.putAll(rows);

        String hit = cache.getCached(TITLE);
        assertTrue(TextFilter.isStyleFallback(hit));
        assertEquals("我的終界之刃 ✪✪✪", shown(hit));
    }

    @Test
    void eachColouringOfASentenceHasItsOwnRow() {
        ManualTranslations manual = manual();
        TranslationCache cache = cache(new Backend("AI:"), new MapStore(), manual);
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(TITLE_SEMANTIC, MANUAL_TITLE);
        rows.put(TITLE_COLOUR_KEY, MANUAL_TITLE_COLOUR);
        manual.putAll(rows);

        assertEquals("⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧", cache.getCached(TITLE));
        String other = cache.getCached(TITLE_OTHER);
        assertTrue(TextFilter.isStyleFallback(other), "another colouring has no row of its own yet");
        assertEquals("我的終界之刃 ✪✪✪", shown(other));

        manual.put(TITLE_OTHER_COLOUR_KEY, MANUAL_TITLE_OTHER_COLOUR);
        assertEquals("⟦CS0⟧我的終界⟦/CS0⟧⟦CS1⟧之刃 ✪✪✪⟦/CS1⟧", cache.getCached(TITLE_OTHER));
        assertEquals("⟦CS0⟧我的終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧", cache.getCached(TITLE),
                "adding the second one does not disturb the first");
    }

    @Test
    void withoutAColourRowEveryLookupKindGetsTheProjectedPlainRowAndSendsNothing() {
        ManualTranslations manual = manual();
        Backend backend = new Backend("AI:");
        TranslationCache cache = cache(backend, new MapStore(), manual);
        manual.put(TITLE_SEMANTIC, MANUAL_TITLE);
        List<String> chat = new ArrayList<>();

        cache.requestCoalescedExactStyle(TITLE, chat::add, true);
        cache.requestCoalescedExactStyleFinal(TITLE, chat::add);
        cache.requestCoalesced(TITLE, chat::add, true);
        cache.warmBatchAsync(List.of(TITLE));
        cache.requestBatched(TITLE);
        ticks(cache);

        for (String value : chat) {
            assertTrue(TextFilter.isStyleFallback(value), "a one-shot consumer is told it is a fallback");
            assertEquals("我的終界之刃 ✪✪✪", shown(value));
        }
        assertEquals(3, chat.size());
        assertEquals(0, backend.calls.get());
        assertTrue(TextFilter.isStyleFallback(cache.getCached(TITLE)));
        assertTrue(TextFilter.isStyleFallback(cache.getCachedFinal(TITLE)));
    }

    @Test
    void anAutomaticColourProjectionNeverOverridesAManualSemanticRow() {
        ManualTranslations manual = manual();
        MapStore rows = new MapStore();
        rows.rows.put(TITLE_SEMANTIC, "終界之刃 ⟦MT0⟧");
        rows.rows.put(TITLE_COLOUR_KEY, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        TranslationCache cache = cache(new Backend("AI:"), rows, manual);
        assertEquals("⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧", cache.getCached(TITLE), "the automatic colours");

        manual.put(TITLE_SEMANTIC, MANUAL_TITLE);

        String hit = cache.getCached(TITLE);
        assertEquals("我的終界之刃 ✪✪✪", shown(hit), "the AI projection must not revive the old wording");
        assertTrue(TextFilter.isStyleFallback(hit));
        assertNotNull(rows.rows.get(TITLE_COLOUR_KEY), "the automatic rows themselves stay");
    }
}
