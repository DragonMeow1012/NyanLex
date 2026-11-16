package com.dragonmeow.nyanlex.cache;

import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
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
 * {@link TranslationCache#invalidateFamily}: deleting a sentence must also delete its colour rows,
 * otherwise the next coloured lookup rebuilds the old semantic row from them. Inline fakes only.
 */
class InvalidateFamilyTest {

    private static final Executor DIRECT = Runnable::run;

    private static final String TITLE = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧✪✪✪⟦/CS1⟧";
    private static final String TITLE_PLAIN = "Aspect of the End ✪✪✪";
    private static final String SEMANTIC = "Aspect of the End ⟦MT0⟧";
    private static final String COLOUR = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String OTHER_COLOUR = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ⟦MT0⟧⟦/CS1⟧";
    private static final String SEMANTIC_ROW = "終界之刃 ⟦MT0⟧";
    private static final String COLOUR_ROW = "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String OTHER_COLOUR_ROW = "⟦CS0⟧終界⟦/CS0⟧⟦CS1⟧之刃 ⟦MT0⟧⟦/CS1⟧";

    private static final class MapStore implements PersistentStore {
        final Map<String, String> rows = new LinkedHashMap<>();
        final Set<String> provisional = new HashSet<>();
        final AtomicInteger scans = new AtomicInteger();

        @Override public String get(String key) { return rows.get(key); }
        @Override public void put(String key, String value) { put(key, value, false); }
        @Override public void put(String key, String value, boolean isProvisional) {
            rows.put(key, value);
            if (isProvisional) provisional.add(key);
            else provisional.remove(key);
        }
        @Override public boolean isProvisional(String key) { return provisional.contains(key); }
        @Override public void clear() { rows.clear(); provisional.clear(); }
        @Override public void remove(String key) { rows.remove(key); provisional.remove(key); }
        @Override public Map<String, String> entries() {
            scans.incrementAndGet();
            return new LinkedHashMap<>(rows);
        }
        @Override public int size() { return rows.size(); }
    }

    private static final class Backend implements Translator {
        final AtomicInteger calls = new AtomicInteger();
        Runnable duringCall = () -> { };

        @Override
        public TranslationResult translate(String text, String targetLang) {
            calls.incrementAndGet();
            duringCall.run();
            return new TranslationResult(text.replace("Aspect of the End", "新的終界之刃"), "en");
        }
    }

    private static MapStore seeded() {
        MapStore rows = new MapStore();
        rows.rows.put(SEMANTIC, SEMANTIC_ROW);
        rows.rows.put(COLOUR, COLOUR_ROW);
        rows.rows.put(OTHER_COLOUR, OTHER_COLOUR_ROW);
        rows.rows.put("Sharpness", "鋒利");
        return rows;
    }

    private static TranslationCache cache(Backend backend, MapStore rows, Executor executor) {
        return new TranslationCache(backend, "zh-TW", executor, 100, 10_000L, () -> 0L, rows);
    }

    private static void ticks(TranslationCache cache) {
        for (int i = 0; i < 8; i++) cache.flushBatch();
    }

    @Test
    void invalidatingOnlyTheSemanticRowLetsTheColourRowReviveTheOldWording() {
        // Control: this is the failure invalidateFamily exists to prevent.
        MapStore rows = seeded();
        TranslationCache cache = cache(new Backend(), rows, DIRECT);
        cache.invalidate(SEMANTIC);
        assertNull(rows.rows.get(SEMANTIC));
        assertNotNull(rows.rows.get(COLOUR), "invalidate() does not find the colour rows");

        assertNotNull(cache.getCached(TITLE), "a coloured lookup serves the old projection again");
        ticks(cache);
        assertNotNull(cache.getCached(TITLE_PLAIN), "and rebuilds the semantic row from it");
    }

    @Test
    void invalidateFamilyRemovesTheSemanticRowAndEveryColourRow() {
        MapStore rows = seeded();
        TranslationCache cache = cache(new Backend(), rows, DIRECT);
        assertEquals(List.of(COLOUR, OTHER_COLOUR), cache.colorProjectionKeys(SEMANTIC));

        cache.invalidateFamily(SEMANTIC);

        assertNull(rows.rows.get(SEMANTIC));
        assertNull(rows.rows.get(COLOUR));
        assertNull(rows.rows.get(OTHER_COLOUR));
        assertEquals("鋒利", rows.rows.get("Sharpness"), "another sentence is not touched");
        assertNull(cache.getCached(TITLE));
        assertNull(cache.getCached(TITLE_PLAIN));
        ticks(cache);
        assertNull(cache.getCached(TITLE_PLAIN), "nothing is rebuilt from a colour row afterwards");
        assertNull(cache.getCached("⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ✪✪✪⟦/CS1⟧"));
    }

    @Test
    void aSessionCopyDerivedFromAColourRowGoesToo() {
        MapStore rows = new MapStore();
        rows.rows.put(COLOUR, COLOUR_ROW); // only the projection survived
        TranslationCache cache = cache(new Backend(), rows, DIRECT);
        assertNotNull(cache.getCached(TITLE));
        ticks(cache);
        assertNotNull(cache.getCached(TITLE_PLAIN), "the semantic row is a session copy now");

        cache.invalidateFamily(SEMANTIC);

        assertNull(cache.getCached(TITLE_PLAIN));
        assertNull(cache.getCached(TITLE));
        assertTrue(rows.rows.isEmpty());
    }

    @Test
    void failureMarksAndProvisionalBitsAreClearedToo() {
        MapStore rows = seeded();
        rows.provisional.add(SEMANTIC);
        MapStore failures = new MapStore();
        failures.rows.put(SEMANTIC, "temporary:2:" + (System.currentTimeMillis() + 3_600_000L));
        failures.rows.put("\u0000MT_STYLE_FAILURE:" + COLOUR, "temporary:1:" + (System.currentTimeMillis() + 3_600_000L));
        failures.rows.put("Smite", "temporary:1:" + (System.currentTimeMillis() + 3_600_000L));
        TranslationCache cache = cache(new Backend(), rows, DIRECT);
        cache.setFailureStore(failures);
        assertTrue(cache.hasFailureState(TITLE_PLAIN));

        cache.invalidateFamily(SEMANTIC);

        assertFalse(cache.hasFailureState(TITLE_PLAIN));
        assertFalse(failures.rows.containsKey(SEMANTIC));
        assertFalse(failures.rows.containsKey("\u0000MT_STYLE_FAILURE:" + COLOUR));
        assertNotNull(failures.rows.get("Smite"), "another sentence's mark stays");
        assertFalse(rows.provisional.contains(SEMANTIC));
    }

    @Test
    void afterwardsTheSentenceIsRequestedExactlyOnce() {
        MapStore rows = seeded();
        Backend backend = new Backend();
        TranslationCache cache = cache(backend, rows, DIRECT);
        cache.invalidateFamily(SEMANTIC);
        assertEquals(0, backend.calls.get(), "nothing is sent at deletion time");

        cache.requestBatched(TITLE_PLAIN);
        cache.requestBatched("Aspect of the End ✪✪✪✪✪"); // another star count, same sentence
        ticks(cache);
        assertEquals(1, backend.calls.get());
        assertEquals("新的終界之刃 ✪✪✪", cache.getCached(TITLE_PLAIN));
        cache.requestBatched(TITLE_PLAIN);
        ticks(cache);
        assertEquals(1, backend.calls.get(), "and then it is cached again");
    }

    @Test
    void aResponseThatWasInFlightWhenTheFamilyWasDiscardedIsDropped() {
        MapStore rows = seeded();
        Backend backend = new Backend();
        TranslationCache cache = cache(backend, rows, DIRECT);
        cache.invalidateFamily(SEMANTIC);
        backend.duringCall = () -> cache.invalidateFamily(SEMANTIC); // deleted again while the answer is on its way
        cache.requestBatched(TITLE_PLAIN);
        ticks(cache);

        assertEquals(1, backend.calls.get());
        assertNull(rows.rows.get(SEMANTIC), "the late answer does not refill the deleted row");
        assertNull(cache.getCached(TITLE_PLAIN));
    }

    @Test
    void aRequestQueuedForTheSentenceIsDroppedByTheDeletion() {
        Deque<Runnable> worker = new ArrayDeque<>();
        MapStore rows = seeded();
        Backend backend = new Backend();
        TranslationCache cache = cache(backend, rows, worker::add);
        cache.invalidateFamily(SEMANTIC);
        cache.requestBatched(TITLE_PLAIN);
        cache.flushBatch();
        cache.flushBatch();
        cache.flushBatch();
        cache.flushBatch(); // the request is handed to the (held) worker

        cache.invalidateFamily(SEMANTIC);
        while (!worker.isEmpty()) worker.poll().run();

        assertEquals(0, backend.calls.get(), "the held request notices the deletion before it is sent");
        assertNull(rows.rows.get(SEMANTIC));
    }

    @Test
    void colourRowsOfOtherSentencesAndUnrelatedForeignKeysAreLeftAlone() {
        MapStore rows = seeded();
        String signSplit = "⟦CS0⟧Berserk: +⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦MT1⟧ Strength⟦/CS1⟧"; // plain key reads "⟦MT0⟧ Strength"
        rows.rows.put(signSplit, "⟦CS0⟧狂戰士：+⟦/CS0⟧⟦CS1⟧⟦MT0⟧⟦MT1⟧ 力量⟦/CS1⟧");
        rows.rows.put("Berserk: ⟦MT0⟧ Strength", "狂戰士：⟦MT0⟧ 力量");
        TranslationCache cache = cache(new Backend(), rows, DIRECT);

        cache.invalidateFamily(SEMANTIC);
        assertNotNull(rows.rows.get(signSplit));
        assertNotNull(rows.rows.get("Berserk: ⟦MT0⟧ Strength"));

        // The known residue: a colour run that cuts a sign from its number is not matched by the
        // cheap comparison; asking by its own colour key (the manager knows it) removes it.
        assertEquals(List.of(), cache.colorProjectionKeys("Berserk: ⟦MT0⟧ Strength"));
        cache.invalidateFamily("Berserk: ⟦MT0⟧ Strength", List.of(signSplit));
        assertNull(rows.rows.get(signSplit));
        assertNull(rows.rows.get("Berserk: ⟦MT0⟧ Strength"));
    }

    @Test
    void knowingTheColourRowsAvoidsTheScan() {
        MapStore rows = seeded();
        TranslationCache cache = cache(new Backend(), rows, DIRECT);
        int before = rows.scans.get();

        cache.invalidateFamily(SEMANTIC, List.of(COLOUR, OTHER_COLOUR));

        assertEquals(before, rows.scans.get(), "no store scan on the caller's thread");
        assertNull(rows.rows.get(SEMANTIC));
        assertNull(rows.rows.get(COLOUR));
        assertNull(rows.rows.get(OTHER_COLOUR));
    }

    @Test
    void manualRowsAreNeverPartOfTheFamilyThatGetsInvalidated() {
        MapStore rows = seeded();
        ManualTranslations manual = new ManualTranslations(new MapStore());
        manual.put(SEMANTIC, "手動 ⟦MT0⟧");
        TranslationCache cache = cache(new Backend(), rows, DIRECT);
        cache.setManualTranslations(manual);

        cache.invalidateFamily(SEMANTIC);

        assertEquals("手動 ⟦MT0⟧", manual.get(SEMANTIC));
        assertEquals("手動5", cache.getCached("Aspect of the End 5"));
        assertNull(rows.rows.get(SEMANTIC), "only the automatic rows went");
    }

    @Test
    void browseListsFinalSentenceRowsAndGroupsColourRows() {
        MapStore rows = seeded();
        rows.rows.put("Kept", "\u0000MT_KEEP_ORIGINAL2");
        rows.rows.put("Stand in", "暫代");
        rows.provisional.add("Stand in");
        rows.rows.put("§aStyled", "有樣式");
        rows.rows.put("Echo", "Echo");
        TranslationCache cache = cache(new Backend(), rows, DIRECT);

        TranslationCache.Browse browse = cache.browse();

        assertEquals(List.of(SEMANTIC, "Sharpness"), List.copyOf(browse.rows().keySet()),
                "write order, final sentence rows only");
        assertEquals(Map.of(SEMANTIC, List.of(COLOUR, OTHER_COLOUR)), browse.colorKeys());
        assertEquals(browse.rows(), cache.browseRows());
        assertEquals(SEMANTIC_ROW, cache.rawRow(SEMANTIC));
        assertEquals(COLOUR_ROW, cache.rawRow(COLOUR));
        assertNull(cache.rawRow("Kept"), "a keep-original verdict is not a translation");
        assertNull(cache.rawRow("Stand in"), "neither is a stand-in");
        assertNull(cache.rawRow("missing"));
        assertNull(cache.rawRow(null));
    }
}
