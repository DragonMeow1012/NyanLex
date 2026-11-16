package com.dragonmeow.nyanlex.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manual translation store: rows, capacity, persistence and language files, the colour-row
 * index, and honest reporting of a file that cannot be written. Logic tests use an inline
 * in-memory store; persistence tests use the real file store in a temporary directory.
 */
class ManualTranslationsTest {

    /** Inline store with switches for the failures a file store can have. */
    private static final class FakeStore implements PersistentStore {
        final Map<String, String> rows = new LinkedHashMap<>();
        boolean writable = true;
        /** Flips {@link #writable} off during the next write, the way a file store goes read-only. */
        boolean failDuringNextWrite;
        final AtomicInteger scans = new AtomicInteger();
        final List<String> languages = new ArrayList<>();

        @Override public String get(String key) { return rows.get(key); }
        @Override public void put(String key, String value) {
            if (failDuringNextWrite) {
                failDuringNextWrite = false;
                writable = false;
            }
            rows.put(key, value);
        }
        @Override public void clear() { rows.clear(); }
        @Override public void remove(String key) { rows.remove(key); }
        @Override public Map<String, String> entries() {
            scans.incrementAndGet();
            return new LinkedHashMap<>(rows);
        }
        @Override public int size() {
            scans.incrementAndGet();
            return rows.size();
        }
        @Override public boolean isWritable() { return writable; }
        @Override public void setLanguage(String language) { languages.add(language); }
    }

    private static final String SEMANTIC = "Aspect of the End ⟦MT0⟧";
    private static final String COLOUR_A = "⟦CS0⟧Aspect of the End⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧";
    private static final String COLOUR_B = "⟦CS0⟧Aspect of⟦/CS0⟧ ⟦CS1⟧the End ⟦MT0⟧⟦/CS1⟧";

    @Test
    void putGetRemoveAndTheCounters() {
        ManualTranslations manual = new ManualTranslations(new FakeStore());
        assertTrue(manual.isEmpty());
        assertNull(manual.get("a"));
        assertNull(manual.get(null));
        long before = manual.revision();

        assertEquals(ManualTranslations.Result.OK, manual.put("a", "甲"));
        assertEquals(ManualTranslations.Result.OK, manual.put("b", "乙"));
        assertFalse(manual.isEmpty());
        assertEquals(2, manual.size());
        assertEquals("甲", manual.get("a"));
        assertTrue(manual.contains("b"));
        assertTrue(manual.revision() > before, "every change counts the revision up");

        assertEquals(ManualTranslations.Result.OK, manual.put("a", "丙"));
        assertEquals(2, manual.size(), "overwriting a row adds none");
        assertEquals("丙", manual.get("a"));

        assertTrue(manual.remove("a"));
        assertTrue(manual.remove("missing"), "removing what is not there is fine");
        assertNull(manual.get("a"));
        assertEquals(1, manual.size());
        assertTrue(manual.removeAll(List.of("b", "b", "missing")));
        assertTrue(manual.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> manual.putAll(Map.of("a", "")));
    }

    @Test
    void anEmptyStoreCostsNoScan() {
        FakeStore store = new FakeStore();
        ManualTranslations manual = new ManualTranslations(store);
        int afterOpening = store.scans.get();
        for (int i = 0; i < 1000; i++) {
            assertTrue(manual.isEmpty());
            assertNull(manual.get("Hello"));
        }
        assertEquals(afterOpening, store.scans.get(),
                "isEmpty() and get() on the render path must not copy or size the table");
    }

    @Test
    void theStoreIsFullAtMaxRowsAndNeverEvicts() {
        FakeStore store = new FakeStore();
        for (int i = 0; i < ManualTranslations.MAX_ROWS; i++) store.rows.put("k" + i, "v" + i);
        ManualTranslations manual = new ManualTranslations(store);
        assertEquals(ManualTranslations.MAX_ROWS, manual.size());

        assertEquals(ManualTranslations.Result.FULL, manual.put("one more", "x"));
        assertNull(manual.get("one more"));
        assertEquals("v0", manual.get("k0"), "a full store drops nothing");
        assertEquals(ManualTranslations.Result.OK, manual.put("k0", "changed"),
                "an existing row can still be edited when the store is full");
        assertEquals("changed", manual.get("k0"));

        // All or none: a pair whose second row does not fit is not half written.
        manual.remove("k1");
        Map<String, String> pair = new LinkedHashMap<>();
        pair.put("new 1", "x");
        pair.put("new 2", "y");
        assertEquals(ManualTranslations.Result.FULL, manual.putAll(pair));
        assertNull(manual.get("new 1"));
        assertEquals(ManualTranslations.MAX_ROWS - 1, manual.size());
    }

    @Test
    void aFileThatCannotBeWrittenIsReportedAndNothingIsHalfStored() {
        FakeStore store = new FakeStore();
        ManualTranslations manual = new ManualTranslations(store);
        manual.put("keep", "舊");

        store.writable = false;
        assertEquals(ManualTranslations.Result.IO_ERROR, manual.put("new", "新"));
        assertNull(manual.get("new"), "a refused write leaves no row behind");
        assertFalse(manual.removeAll(List.of("keep")), "a row that cannot be deleted from the file is kept");
        assertEquals("舊", manual.get("keep"));

        store.writable = true;
        store.failDuringNextWrite = true; // goes read-only while writing the first of two rows
        Map<String, String> pair = new LinkedHashMap<>();
        pair.put("keep", "更新");
        pair.put("fresh", "全新");
        assertEquals(ManualTranslations.Result.IO_ERROR, manual.putAll(pair));
        assertEquals("舊", manual.get("keep"), "the old wording is put back");
        assertNull(manual.get("fresh"));
        assertEquals(1, manual.size(), "and the counter agrees");
    }

    @Test
    void colourRowsAreGroupedUnderTheirSentence() {
        ManualTranslations manual = new ManualTranslations(new FakeStore());
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(SEMANTIC, "終界之刃 ⟦MT0⟧");
        rows.put(COLOUR_A, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        rows.put(COLOUR_B, "⟦CS0⟧終界⟦/CS0⟧ ⟦CS1⟧之刃 ⟦MT0⟧⟦/CS1⟧");
        rows.put("Other", "其他");
        assertEquals(ManualTranslations.Result.OK, manual.putAll(rows));

        assertEquals(List.of(COLOUR_A, COLOUR_B), manual.colorKeysOf(SEMANTIC));
        assertEquals(List.of(), manual.colorKeysOf("Other"));
        assertEquals(List.of(), manual.colorKeysOf(null));

        manual.remove(COLOUR_A);
        assertEquals(List.of(COLOUR_B), manual.colorKeysOf(SEMANTIC));
        manual.removeAll(List.of(COLOUR_B, SEMANTIC));
        assertEquals(List.of(), manual.colorKeysOf(SEMANTIC));
        assertEquals(1, manual.size());
    }

    @Test
    void rowsSurviveAReopenAndDeletionsAreRemembered(@TempDir Path dir) {
        ManualTranslations first = ManualTranslations.open(dir, "nyanlex", "zh-TW");
        first.put("a", "甲");
        first.put("b", "乙");
        first.put(COLOUR_A, "⟦CS0⟧終界之刃⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧");
        first.remove("a");

        assertTrue(Files.isRegularFile(dir.resolve("nyanlex-manual-zh-tw.json")),
                "one file per language, next to the other caches");
        ManualTranslations second = ManualTranslations.open(dir, "nyanlex", "zh-TW");
        assertNull(second.get("a"), "a deleted row stays deleted");
        assertEquals("乙", second.get("b"));
        assertEquals(2, second.size());
        assertEquals(List.of(COLOUR_A), second.colorKeysOf(SEMANTIC), "the colour index is rebuilt from the file");
    }

    @Test
    void eachLanguageHasItsOwnFileAndSwitchingKeepsTheOthers(@TempDir Path dir) {
        ManualTranslations manual = ManualTranslations.open(dir, "nyanlex", "zh-TW");
        manual.put("Hello", "你好");
        long revision = manual.revision();

        manual.setLanguage("zh-TW");
        assertEquals(revision, manual.revision(), "the language that is already active is not reloaded");

        manual.setLanguage("ja-JP");
        assertTrue(manual.isEmpty(), "another language starts with its own, empty file");
        assertNull(manual.get("Hello"));
        manual.put("Hello", "こんにちは");
        assertTrue(manual.revision() > revision);

        manual.setLanguage("zh_tw"); // the same tag in another spelling
        assertEquals("你好", manual.get("Hello"));
        assertEquals(1, manual.size());
        manual.setLanguage("ja-JP");
        assertEquals("こんにちは", manual.get("Hello"));
        assertTrue(Files.isRegularFile(dir.resolve("nyanlex-manual-zh-tw.json")));
        assertTrue(Files.isRegularFile(dir.resolve("nyanlex-manual-ja-jp.json")));
    }

    @Test
    void aWriteThatCannotReachTheRealFileIsReported(@TempDir Path dir) throws Exception {
        // A directory where the journal file should be: every append fails and the store turns read-only.
        Files.createDirectories(dir.resolve("nyanlex-manual-zh-tw.json"));
        ManualTranslations manual = ManualTranslations.open(dir, "nyanlex", "zh-TW");
        ManualTranslations.Result first = manual.put("a", "甲");
        assertEquals(ManualTranslations.Result.IO_ERROR, first);
        assertNull(manual.get("a"));
        assertEquals(ManualTranslations.Result.IO_ERROR, manual.put("b", "乙"), "and it stays reported");
    }
}
