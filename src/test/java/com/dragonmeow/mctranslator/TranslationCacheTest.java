package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.cache.PersistentStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.translate.TranslationException;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationCacheTest {


    private static final Executor DIRECT = Runnable::run;


    private static Translator countingUpper(AtomicInteger counter) {
        return (text, target) -> {
            counter.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
    }

    @Test
    void warmBatchAsyncSendsFullSurfaceContextButOnlyUncachedTodoLines() {
        List<List<String>> seenTexts = new ArrayList<>();
        List<List<String>> seenContexts = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, null);
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenTexts.add(new ArrayList<>(texts));
                seenContexts.add(surfaceContext == null ? null : new ArrayList<>(surfaceContext));
                List<TranslationResult> out = new ArrayList<>();
                for (String t : texts) out.add(new TranslationResult("T:" + t, null));
                return out;
            }
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);

        // The tooltip TITLE got translated on its own earlier and is already cached.
        cache.requestAsync("Iron Pickaxe");
        assertEquals("T:Iron Pickaxe", cache.getCached("Iron Pickaxe"));

        List<String> tooltip = List.of("Iron Pickaxe", "Recipes", "Used in smelting");
        cache.warmBatchAsync(tooltip, tooltip);

        assertEquals(1, seenTexts.size(), "one batched request for the tooltip");
        assertEquals(List.of("Recipes", "Used in smelting"), seenTexts.get(0),
                "the already-cached title must not be re-requested");
        assertEquals(List.of("Iron Pickaxe", "Recipes", "Used in smelting"), seenContexts.get(0),
                "surface context must still carry the WHOLE tooltip, cached title included");
        assertEquals("T:Recipes", cache.getCached("Recipes"));
    }

    @Test
    void warmBatchAsyncWithoutSurfaceLinesPassesNullContext() {
        List<List<String>> seenContexts = new ArrayList<>();
        Translator fake = new Translator() {
            @Override public TranslationResult translate(String text, String targetLang) {
                return new TranslationResult("T:" + text, null);
            }
            @Override public List<TranslationResult> translateBatch(
                    List<String> texts, String targetLang, List<String> surfaceContext) {
                seenContexts.add(surfaceContext);
                List<TranslationResult> out = new ArrayList<>();
                for (String t : texts) out.add(new TranslationResult("T:" + t, null));
                return out;
            }
        };
        TranslationCache cache = new TranslationCache(fake, "zh-TW", DIRECT, 100);
        cache.warmBatchAsync(List.of("Hello"));
        assertEquals(1, seenContexts.size());
        assertNull(seenContexts.get(0), "the old signature must keep sending NO context");
    }

    @Test
    void blockingTranslatesThenCaches() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        assertEquals("T:hi", cache.translateBlocking("hi"));
        assertEquals("T:hi", cache.translateBlocking("hi")); 
        assertEquals(1, calls.get(), "second call must hit cache, not the translator");
        assertEquals("T:hi", cache.getCached("hi"));
    }

    @Test
    void blockingReturnsNullOnFailureAndDoesNotCache() {
        AtomicInteger calls = new AtomicInteger();

        Translator failing = (text, target) -> {
            calls.incrementAndGet();
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);

        assertNull(cache.translateBlocking("hi"));
        assertNull(cache.getCached("hi"));

        assertNull(cache.translateBlocking("hi"));
        assertEquals(2, calls.get());
    }

    @Test
    void asyncWithDirectExecutorFillsCacheImmediately() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        assertNull(cache.getCached("hi"));
        cache.requestAsync("hi");
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(1, calls.get());
    }

    @Test
    void asyncIsDeduplicatedWhileInFlight() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();

        Executor manual = queue::add;
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", manual, 100);

        cache.requestAsync("hi");
        cache.requestAsync("hi"); 
        assertTrue(cache.isPending("hi"));
        assertEquals(1, queue.size(), "duplicate request must not enqueue a second task");

        queue.poll().run(); 
        assertEquals("T:hi", cache.getCached("hi"));
        assertFalse(cache.isPending("hi"));
        assertEquals(1, calls.get());
    }

    @Test
    void blockingDoesNotCorruptAsyncPending() {


        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);

        cache.requestAsync("hi");                 
        assertTrue(cache.isPending("hi"));

        cache.translateBlocking("hi");            
        assertTrue(cache.isPending("hi"),
                "blocking translate must leave the async pending entry intact");
    }

    @Test
    void failureBackoffSuppressesPerFrameRetries() {

        AtomicInteger calls = new AtomicInteger();
        Translator failing = (text, target) -> {
            calls.incrementAndGet();
            throw new TranslationException("boom");
        };
        long[] now = {1_000L};
        LongSupplier clock = () -> now[0];

        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100, 5_000L, clock);

        cache.requestAsync("hi");                 
        assertEquals(1, calls.get());
        cache.requestAsync("hi");                 
        cache.requestAsync("hi");
        assertEquals(1, calls.get(), "retries during backoff must be suppressed");

        now[0] = 6_000L;                          
        cache.requestAsync("hi");                 
        assertEquals(2, calls.get());
    }

    @Test
    void successClearsPreviousFailureBackoff() {
        AtomicInteger calls = new AtomicInteger();
        boolean[] failNext = {true};
        Translator flaky = (text, target) -> {
            calls.incrementAndGet();
            if (failNext[0]) throw new TranslationException("boom");
            return new TranslationResult("T:" + text, "en");
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(flaky, "zh-TW", DIRECT, 100, 5_000L, () -> now[0]);

        cache.requestAsync("hi");                 
        failNext[0] = false;
        now[0] = 10_000L;                         
        cache.requestAsync("hi");                 
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(2, calls.get());
    }


    private static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
        };
    }

    @Test
    void diskTierRecoversEntriesEvictedFromMemory() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();

        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 1, 10_000L, () -> 0L, inlineStore(disk));

        assertEquals("T:a", cache.translateBlocking("a")); 
        assertEquals("T:b", cache.translateBlocking("b")); 
        assertEquals(2, calls.get());


        assertEquals("T:a", cache.getCached("a"));
        assertEquals(2, calls.get(), "disk hit must not call the translator again");
        assertTrue(disk.containsKey("a") && disk.containsKey("b"));
    }

    @Test
    void diskHitPreventsRedundantAsyncTranslation() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("hi", "T:hi"); 
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", queue::add, 100, 10_000L, () -> 0L, inlineStore(disk));

        cache.requestAsync("hi");                 
        assertEquals(0, queue.size());
        assertEquals("T:hi", cache.getCached("hi"));
        assertEquals(0, calls.get());
    }

    @Test
    void mojibakeDiskHitIsDiscardedAndRetranslated() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        disk.put("hi", "\u83F4\uF8F0\u8782\uFF7D");
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertNull(cache.getCached("hi"));
        assertFalse(disk.containsKey("hi"));

        assertEquals("T:hi", cache.translateBlocking("hi"));
        assertEquals(1, calls.get());
        assertEquals("T:hi", disk.get("hi"));
    }

    @Test
    void asyncCallbackFiresWithTranslation() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertEquals(List.of("T:hi"), got);
    }

    @Test
    void asyncCallbackFiresImmediatelyWhenAlreadyCached() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);
        cache.translateBlocking("hi"); 
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertEquals(List.of("T:hi"), got);
        assertEquals(1, calls.get(), "cached callback must not re-translate");
    }

    @Test
    void duplicateAlwaysAsyncRequestsShareOneBackendCallAndBothCallbacksFire() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("hi", got::add);
        cache.translateAsyncAlways("hi", got::add);

        assertTrue(cache.isPending("hi"));
        assertEquals(1, queue.size());
        queue.poll().run();

        assertEquals(List.of("T:hi", "T:hi"), got);
        assertEquals(1, calls.get());
        assertFalse(cache.isPending("hi"));
    }

    @Test
    void cachedAlwaysAsyncReturnsImmediatelyWithoutQueueing() {
        AtomicInteger calls = new AtomicInteger();
        Deque<Runnable> queue = new ArrayDeque<>();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", queue::add, 100);
        cache.translateBlocking("hi");
        List<String> got = new ArrayList<>();

        cache.translateAsyncAlways("hi", got::add);

        assertEquals(List.of("T:hi"), got);
        assertEquals(0, queue.size());
        assertEquals(1, calls.get());
    }

    @Test
    void asyncCallbackNotFiredOnFailure() {
        Translator failing = (text, target) -> {
            throw new TranslationException("boom");
        };
        TranslationCache cache = new TranslationCache(failing, "zh-TW", DIRECT, 100);
        List<String> got = new ArrayList<>();
        cache.requestAsync("hi", got::add);
        assertTrue(got.isEmpty(), "callback must not fire when translation fails");
    }

    // ---- colour-stripped second tier (⟦CS#⟧-marked multi-colour lines) ----

    @Test
    void colourStrippedTierServesRecolouredVariantAsPlainText() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> disk = new java.util.HashMap<>();
        TranslationCache cache = new TranslationCache(
                countingUpper(calls), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        cache.requestAsync("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧");
        assertEquals(1, calls.get());

        // Same words, colour-run structure shifted (gradient/animation frame): new marked key.
        String variant = "⟦CS1⟧Hello⟦/CS1⟧ ⟦CS0⟧World⟦/CS0⟧";
        String served = cache.getCached(variant);
        assertEquals("T:Hello World", served, "stripped tier must serve the recoloured variant");
        assertFalse(served.contains("CS"), "tier-2 hit must be PLAIN text (glue re-applies colours)");
        assertEquals(1, calls.get(), "the variant must cost zero translator calls");
        assertFalse(disk.containsKey(variant),
                "tier-2 hits must NOT materialise per-colour-permutation entries");
    }

    @Test
    void colourStrippedTierSubstitutesCurrentNumbers() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧Kill streak⟦/CS0⟧ ⟦CS1⟧5⟦/CS1⟧");
        assertEquals(1, calls.get());

        // Number changed AND the colour boundaries moved: still one cached translation.
        assertEquals("T:Kill streak 10", cache.getCached("⟦CS0⟧Kill⟦/CS0⟧ ⟦CS1⟧streak 10⟦/CS1⟧"));
        assertEquals(1, calls.get(), "number/colour variants must not re-buy the translation");
    }

    @Test
    void bareCsResidueInValueSkipsColourStrippedCopy() {
        // A translator ate the ⟦⟧ brackets but kept the "CS1" body: poison for a plain entry.
        Translator markerEater = (text, target) -> new TranslationResult("你好 CS1 世界", null);
        TranslationCache cache = new TranslationCache(markerEater, "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧");
        assertEquals("你好 CS1 世界", cache.getCached("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧"),
                "the direct marked entry itself is still cached");
        assertNull(cache.getCached("⟦CS0⟧Hello World⟦/CS0⟧"),
                "a residue-carrying value must not seed the stripped tier");
    }

    @Test
    void letterlessStrippedSourceSkipsColourStrippedCopy() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧123⟦/CS0⟧ ⟦CS1⟧456⟦/CS1⟧");
        assertEquals(1, calls.get());

        assertNull(cache.getCached("⟦CS0⟧123 456⟦/CS0⟧"),
                "a pure-number skeleton must not be stored as a stripped copy");
    }

    @Test
    void colourStrippedCopyIsReachableThroughFallbackChain() {
        AtomicInteger aiCalls = new AtomicInteger();
        TranslationCache ai = new TranslationCache(countingUpper(aiCalls), "zh-TW", DIRECT, 100);
        AtomicInteger googleCalls = new AtomicInteger();
        TranslationCache google = new TranslationCache(countingUpper(googleCalls), "zh-TW", DIRECT, 100);
        google.setFallback(ai); // the Google cache consults the AI cache on miss

        ai.requestAsync("⟦CS0⟧Kill streak⟦/CS0⟧ ⟦CS1⟧5⟦/CS1⟧");
        assertEquals(1, aiCalls.get());

        assertEquals("T:Kill streak 10",
                google.getCached("⟦CS0⟧Kill⟦/CS0⟧ ⟦CS1⟧streak 10⟦/CS1⟧"),
                "the AI cache's stripped copy must serve the Google cache via fallback");
        assertEquals(0, googleCalls.get());
    }

    // ---- de-styled tier: literal § colour codes and centring padding ----

    @Test
    void sectionCodeVariantHitsAndKeepsFirstTranslationColours() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("§e連續擊殺 §65 §e隻！");
        assertEquals(1, calls.get());

        // Same words and number, every literal § colour code changed by the server.
        String served = cache.getCached("§a連續擊殺 §b5 §a隻！");
        assertEquals("T:§e連續擊殺 §65 §e隻！", served,
                "the de-styled tier must serve the recoloured line");
        assertTrue(served.contains("§e"),
                "the FIRST translation's § codes ride along by design (quota over colours)");
        assertEquals(1, calls.get(), "a § recolour must cost zero translator calls");
    }

    @Test
    void innerPaddingVariantHitsDeStyledTier() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("§eWeather:  §aSunny");
        assertEquals(1, calls.get());

        // The server re-centred the line: only the inner padding run changed.
        assertEquals("T:§eWeather:  §aSunny", cache.getCached("§eWeather:      §aSunny"));
        assertEquals(1, calls.get(), "a padding shift must cost zero translator calls");
    }

    @Test
    void mixedCsAndSectionCodeVariantHits() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧§eHello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧");
        assertEquals(1, calls.get());

        // CS run structure AND the baked-in § code both changed.
        String served = cache.getCached("⟦CS0⟧§bHello World⟦/CS0⟧");
        assertEquals("T:§eHello World", served,
                "CS markers stripped from the value, first-translation § codes kept");
        assertFalse(served.contains("CS"));
        assertEquals(1, calls.get());
    }

    @Test
    void sectionCodeAndNumberVariantSubstitutesCurrentNumber() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 100);

        cache.requestAsync("§e連續擊殺 §65 §e隻！");
        assertEquals(1, calls.get());

        // Colours changed AND the count went 5 → 10. The §6 colour code fused into the
        // original number slot ("§65" templated as "65") must be repaired, so the CURRENT
        // number lands after the first translation's §6 code — not inside it.
        assertEquals("T:§e連續擊殺 §610 §e隻！", cache.getCached("§a連續擊殺 §b10 §a隻！"));
        assertEquals(1, calls.get(), "a colour+number variant must not re-buy the translation");
    }

    // ---- discard→rematerialise disk-append loop (the 30,892-line tab-header incident) ----

    @Test
    void sectionCodeGluedCjkValueIsServedNotDiscarded() {
        Map<String, String> disk = new java.util.HashMap<>();
        String key = "§f   §d§lSB YEAR 500 §8| §b§lLOADOUTS";
        String value = "§d§lSB年500 §8| §b§l裝備包";
        disk.put(key, value);
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, inlineStore(disk));

        assertEquals(value, cache.getCached(key),
                "the §l code letter must not fake a half-transliteration verdict");
        assertTrue(disk.containsKey(key), "the disk entry must survive the read");
    }

    @Test
    void readRejectedValueIsNeverRematerialisedToDisk() {
        // The d141b32 live loop: the read side discards a poison raw entry, then the
        // templated tier restores + rematerialises it through the WEAKER one-arg gate —
        // one disk append per frame, forever. The symmetric write gate must keep the
        // disk write count at ZERO while still returning the restored value upstream.
        Map<String, String> disk = new java.util.HashMap<>();
        AtomicInteger diskWrites = new AtomicInteger();
        PersistentStore countingStore = new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) { diskWrites.incrementAndGet(); disk.put(key, value); }
            @Override public void clear() { disk.clear(); }
            @Override public void remove(String key) { disk.remove(key); }
        };
        // Raw entry the read side genuinely rejects ("mb", a proper suffix of CLIMB, glued to 年)…
        disk.put("CLIMB 500", "爬升 mb年500");
        // …and a templated sibling that PASSES the read check ("mb" not glued), but whose
        // restore re-glues it (tightenCjkSpacing treats 'b' as a number-suffix before CJK).
        disk.put("CLIMB ⟦MT0⟧", "爬升 mb 年 ⟦MT0⟧");
        TranslationCache cache = new TranslationCache(
                countingUpper(new AtomicInteger()), "zh-TW", DIRECT, 100, 10_000L, () -> 0L, countingStore);

        for (int frame = 0; frame < 2; frame++) {
            assertEquals("爬升 mb年500", cache.getCached("CLIMB 500"),
                    "the restored value is still returned (upstream decide() re-judges it)");
        }
        assertEquals(0, diskWrites.get(),
                "a value the read side rejects must never be written back by the lookup path");
    }

    @Test
    void identityEchoValueDoesNotSeedDeStyledCopy() {
        // GoogleFreeTranslator's preservesTokens guard returns its INPUT on failure: an
        // untranslated echo with pristine markers. De-styled it is clean English (no CS
        // residue to catch), so without the echo guard it would seed a copy whose value
        // IS the source — every styled variant would then "hit" fake English forever.
        Translator echoing = (text, target) -> new TranslationResult(text, null);
        TranslationCache cache = new TranslationCache(echoing, "zh-TW", DIRECT, 100);

        cache.requestAsync("⟦CS0⟧Hello⟦/CS0⟧ ⟦CS1⟧World⟦/CS1⟧"); // CS-marked echo
        cache.requestAsync("§eKill streak §65 §emobs!");          // §-literal echo, with number

        assertNull(cache.getCached("⟦CS1⟧Hello⟦/CS1⟧ ⟦CS0⟧World⟦/CS0⟧"),
                "a recoloured CS variant must MISS: the echo built no de-styled copy");
        assertNull(cache.getCached("§aKill streak §b5 §amobs!"),
                "a §-recoloured variant must MISS: the echo built no de-styled copy");
    }

    // ---- R9: GT 暫代 (provisional) entries + AI redo on hit ----

    /** Inline provisional-aware store fake: records values AND the provisional flag. */
    private static PersistentStore provisionalStore(Map<String, String> disk, java.util.Set<String> prov) {
        return new PersistentStore() {
            @Override public String get(String key) { return disk.get(key); }
            @Override public void put(String key, String value) { put(key, value, false); }
            @Override public void put(String key, String value, boolean provisional) {
                disk.put(key, value);
                if (provisional) prov.add(key); else prov.remove(key);
            }
            @Override public boolean isProvisional(String key) { return prov.contains(key); }
            @Override public void clear() { disk.clear(); prov.clear(); }
            @Override public void remove(String key) { disk.remove(key); prov.remove(key); }
        };
    }

    @Test
    void fallbackResultsAreStoredProvisionalAndPlainResultsAreNot() {
        Map<String, String> disk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        Translator mixed = (text, target) -> text.startsWith("A")
                ? new TranslationResult("GT:" + text, "en", true)  // dispatcher fell back
                : new TranslationResult("T:" + text, "en");         // plain backend (google keyspace)
        TranslationCache cache = new TranslationCache(
                mixed, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, provisionalStore(disk, prov));

        cache.requestAsync("Apple pie");
        cache.requestAsync("Berry pie");

        assertEquals("GT:Apple pie", cache.getCached("Apple pie"));
        assertTrue(prov.contains("Apple pie"), "a fallback product is stored PROVISIONAL");
        assertEquals("T:Berry pie", cache.getCached("Berry pie"));
        assertFalse(prov.contains("Berry pie"), "a plain backend product is stored FINAL");
    }

    @Test
    void provisionalHitTriggersAiRedoThatOverwritesUnmarksAndRebuildsCopies() {
        boolean[] aiUp = {false};
        AtomicInteger calls = new AtomicInteger();
        Translator dispatcher = (text, target) -> {
            calls.incrementAndGet();
            return aiUp[0] ? new TranslationResult("AI:" + text, "en")
                           : new TranslationResult("GT:" + text, "en", true);
        };
        Map<String, String> disk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        TranslationCache cache = new TranslationCache(
                dispatcher, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, provisionalStore(disk, prov));
        cache.setProvisionalRetryGate(() -> aiUp[0]);

        cache.requestAsync("§eHello §aWorld");                    // AI down: GT stands in
        assertEquals(1, calls.get());
        assertEquals("GT:§eHello §aWorld", cache.getCached("§eHello §aWorld"));
        assertEquals(1, calls.get(), "gate closed: a hit must not schedule a redo");
        assertTrue(prov.contains("§eHello §aWorld"));

        aiUp[0] = true;                                           // AI recovered: gate open
        assertEquals("GT:§eHello §aWorld", cache.getCached("§eHello §aWorld"),
                "the hit itself still serves the stand-in; the redo runs off the hit");
        assertEquals(2, calls.get(), "exactly ONE AI redo was scheduled by the hit");
        assertEquals("AI:§eHello §aWorld", cache.getCached("§eHello §aWorld"), "AI overwrote");
        assertFalse(prov.contains("§eHello §aWorld"), "the provisional mark is cleared");
        assertEquals("AI:§eHello §aWorld", cache.getCached("§bHello §cWorld"),
                "the de-styled copy was rebuilt from the AI value (recoloured variant hit)");
        assertEquals(2, calls.get(), "no further redos once the value is final");
    }

    @Test
    void provisionalRedoBacksOffWhileTheAiStillFallsBack() {
        AtomicInteger calls = new AtomicInteger();
        Translator alwaysFallback = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult("GT:" + text, "en", true);
        };
        long[] now = {0L};
        TranslationCache cache = new TranslationCache(
                alwaysFallback, "zh-TW", DIRECT, 100, 10_000L, () -> now[0], null);
        cache.setProvisionalRetryGate(() -> true); // gate open, but the dispatcher STILL falls back

        cache.requestAsync("Hello world");
        assertEquals(1, calls.get());

        cache.getCached("Hello world");            // hit → one redo attempt → still fallback → backoff
        assertEquals(2, calls.get());
        cache.getCached("Hello world");            // inside failedUntil: quiet
        cache.getCached("Hello world");
        assertEquals(2, calls.get(), "redo attempts must respect the failure backoff");
        assertEquals("GT:Hello world", cache.getCached("Hello world"), "the stand-in keeps serving");

        now[0] = 20_000L;                          // backoff expired: the next hit retries once
        cache.getCached("Hello world");
        assertEquals(3, calls.get());
    }

    @Test
    void invalidateEvictsDeStyledCopiesAndProvisionalMarks() {
        // The R hotkey path: after invalidate NOTHING may keep serving the old value —
        // including the de-styled tier-2 copy (else the re-warm sees "cached" and never
        // re-buys, making the hotkey feel like a no-op) — and no provisional residue stays.
        Map<String, String> disk = new java.util.HashMap<>();
        java.util.Set<String> prov = new java.util.HashSet<>();
        Translator gt = (text, target) -> new TranslationResult("GT:" + text, "en", true);
        TranslationCache cache = new TranslationCache(
                gt, "zh-TW", DIRECT, 100, 10_000L, () -> 0L, provisionalStore(disk, prov));

        cache.requestAsync("§eHello §aWorld");
        assertEquals("GT:§eHello §aWorld", cache.getCached("§eHello §aWorld"));
        assertEquals("GT:§eHello §aWorld", cache.getCached("§bHello §cWorld"), "tier-2 copy exists");
        assertTrue(prov.contains("§eHello §aWorld"));

        cache.invalidate("§eHello §aWorld");
        assertNull(cache.getCached("§eHello §aWorld"), "primary keys evicted");
        assertNull(cache.getCached("§bHello §cWorld"), "the de-styled tier-2 copy is evicted too");
        assertFalse(prov.contains("§eHello §aWorld"), "no provisional residue after invalidate");
        assertFalse(prov.contains("Hello World"), "the copy's mark is gone as well");
    }

    @Test
    void lruEvictsOldestBeyondMaxSize() {
        AtomicInteger calls = new AtomicInteger();
        TranslationCache cache = new TranslationCache(countingUpper(calls), "zh-TW", DIRECT, 2);

        cache.translateBlocking("a");
        cache.translateBlocking("b");

        cache.getCached("a");
        cache.translateBlocking("c"); 

        assertEquals(2, cache.size());
        assertEquals("T:a", cache.getCached("a"));
        assertNull(cache.getCached("b"), "b should have been evicted as least-recently-used");
        assertEquals("T:c", cache.getCached("c"));
    }
}
