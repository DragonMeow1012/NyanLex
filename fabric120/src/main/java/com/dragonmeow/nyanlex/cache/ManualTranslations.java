package com.dragonmeow.nyanlex.cache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translations the player wrote by hand (the translation manager screen), one file per target
 * language ({@code nyanlex-manual-<tag>.json}, the same JSON-Lines format as the other caches).
 *
 * <p>The rows are kept apart from every automatic cache on purpose: an AI answer, a machine
 * translation, a re-translation, a pre-translation or a repository download can only ever write
 * into its own file, so none of them can overwrite a manual row. {@link TranslationCache} consults
 * this store before everything else.</p>
 *
 * <p>Two kinds of rows live here. A <b>semantic row</b> is keyed by the sentence's semantic key
 * ({@link TranslationCache#semanticKey}, the text with its value markers and without colour) and holds
 * the plain translation. A <b>colour row</b> is keyed by the colour-marked form of the sentence (its
 * {@code ⟦CSn⟧ ... ⟦/CSn⟧} projection key) and holds the translation with the same colour markers, so a
 * coloured line keeps its per-word colours. Colour rows are grouped under the sentence they belong
 * to, see {@link #colorKeysOf}.</p>
 *
 * <p>Cost for the render path: {@link #isEmpty()} is one atomic read, so a player who never edited a
 * translation pays nothing; {@link #get} is a hash lookup. Writing is serialised and is never done
 * on a scan; {@link #entries()} copies the whole table and belongs on a background thread.</p>
 */
public final class ManualTranslations {
    /** The most rows (semantic rows and colour rows together) the store accepts; it never evicts. */
    public static final int MAX_ROWS = 20_000;
    /** File-name part between the mod prefix and the language tag. */
    public static final String FILE_PART = "-manual";
    /** Slack above {@link #MAX_ROWS} so the file store underneath never evicts a manual row by itself. */
    private static final int STORE_SLACK = 1_000;

    /** What a write did. */
    public enum Result {
        /** Stored. */
        OK,
        /** Not stored: the store already holds {@link #MAX_ROWS} rows. */
        FULL,
        /** Not stored: the file cannot be written. */
        IO_ERROR
    }

    private final PersistentStore store;
    private final Object writeLock = new Object();
    private final AtomicInteger rows = new AtomicInteger();
    private final AtomicLong revision = new AtomicLong();
    /** Colour rows by the semantic key they project; guarded by {@link #writeLock}. */
    private final Map<String, Set<String>> colorRows = new HashMap<>();
    /** The language tag the store was last switched to (null: unknown); guarded by {@link #writeLock}. */
    private String language;

    /**
     * Opens (or creates) the manual file of {@code language} in {@code configDir}.
     *
     * @param filePrefix the mod's file prefix (the file is {@code <prefix>-manual-<tag>.json})
     */
    public static ManualTranslations open(Path configDir, String filePrefix, String language) {
        ManualTranslations opened = new ManualTranslations(new LanguageFileStore(
                configDir, filePrefix + FILE_PART, language, MAX_ROWS + STORE_SLACK));
        opened.language = LanguageFileStore.languageTag(language);
        return opened;
    }

    /** Wraps any store; embedders and tests pass an in-memory one. */
    public ManualTranslations(PersistentStore store) {
        if (store == null) throw new IllegalArgumentException("store");
        this.store = store;
        reindex();
    }

    /** The manual translation stored under {@code key}, or {@code null}. Lock-free. */
    public String get(String key) {
        return key == null ? null : store.get(key);
    }

    public boolean contains(String key) {
        return get(key) != null;
    }

    /** Whether there is no manual row at all; one atomic read. */
    public boolean isEmpty() {
        return rows.get() == 0;
    }

    /** How many rows (semantic and colour) are stored. */
    public int size() {
        return rows.get();
    }

    /** Counts up on every change (and on a language switch); lets a reader notice that it is stale. */
    public long revision() {
        return revision.get();
    }

    public Result put(String key, String text) {
        return putAll(Map.of(key, text));
    }

    /**
     * Stores all rows or none: the rows of one sentence (its semantic row and the colour row being edited)
     * must never be half written.
     */
    public Result putAll(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return Result.OK;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                throw new IllegalArgumentException("a manual row needs a key and a text");
            }
        }
        synchronized (writeLock) {
            if (!store.isWritable()) return Result.IO_ERROR;
            Map<String, String> previous = new LinkedHashMap<>();
            int added = 0;
            for (String key : entries.keySet()) {
                String old = store.get(key);
                previous.put(key, old);
                if (old == null) added++;
            }
            if (rows.get() + added > MAX_ROWS) return Result.FULL;
            store.putBatch(entries, Set.of());
            boolean stored = store.isWritable();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                stored &= e.getValue().equals(store.get(e.getKey()));
            }
            if (!stored) {
                // Nothing may be half stored: put the table back as it was.
                for (Map.Entry<String, String> old : previous.entrySet()) {
                    if (old.getValue() == null) store.remove(old.getKey());
                    else store.put(old.getKey(), old.getValue());
                }
                return Result.IO_ERROR;
            }
            for (String key : entries.keySet()) {
                if (previous.get(key) == null) indexAdd(key);
            }
            rows.addAndGet(added);
            revision.incrementAndGet();
            return Result.OK;
        }
    }

    /** Removes one row; {@code false} when the file could not be written (the row is then kept). */
    public boolean remove(String key) {
        return key == null || removeAll(List.of(key));
    }

    /** Removes the rows (missing keys are ignored); {@code false} when the file could not be written. */
    public boolean removeAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return true;
        synchronized (writeLock) {
            List<String> present = new ArrayList<>(keys.size());
            for (String key : keys) {
                if (key != null && store.get(key) != null && !present.contains(key)) present.add(key);
            }
            if (present.isEmpty()) return true;
            if (!store.isWritable()) return false;
            store.removeBatch(present);
            for (String key : present) indexRemove(key);
            rows.addAndGet(-present.size());
            revision.incrementAndGet();
            return store.isWritable();
        }
    }

    /**
     * The colour rows stored for the sentence with this semantic key, in write order. Reads an index
     * kept by the writes: no scan.
     */
    public List<String> colorKeysOf(String semanticKey) {
        if (semanticKey == null) return List.of();
        synchronized (writeLock) {
            Set<String> keys = colorRows.get(semanticKey);
            return keys == null ? List.of() : new ArrayList<>(keys);
        }
    }

    /** A copy of the whole table in write order, oldest first. Background threads only. */
    public Map<String, String> entries() {
        return store.entries();
    }

    /** Switches to the file of another target language (the previous one stays on disk). */
    public void setLanguage(String language) {
        String tag = LanguageFileStore.languageTag(language);
        synchronized (writeLock) {
            if (tag.equals(this.language)) return;
            store.setLanguage(language);
            this.language = tag;
            reindex();
        }
    }

    private void reindex() {
        synchronized (writeLock) {
            colorRows.clear();
            int count = 0;
            for (String key : store.entries().keySet()) {
                count++;
                indexAdd(key);
            }
            rows.set(count);
            revision.incrementAndGet();
        }
    }

    private void indexAdd(String key) {
        String semantic = TranslationCache.semanticOfColorKey(key);
        if (semantic != null) colorRows.computeIfAbsent(semantic, ignored -> new LinkedHashSet<>()).add(key);
    }

    private void indexRemove(String key) {
        String semantic = TranslationCache.semanticOfColorKey(key);
        if (semantic == null) return;
        Set<String> keys = colorRows.get(semantic);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) colorRows.remove(semantic);
    }
}
