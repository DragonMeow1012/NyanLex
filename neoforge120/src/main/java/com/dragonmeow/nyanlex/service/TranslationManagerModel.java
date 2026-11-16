package com.dragonmeow.nyanlex.service;

import java.util.List;

/**
 * What the translation manager screen (譯文管理) needs from the translation caches. The screen's
 * panel depends on this interface only; the cache layer implements it.
 *
 * <h2>Texts that cross this interface</h2>
 * <p>Every sentence and translation here is in its <b>stored (raw) form</b>: the protected markers are
 * {@code ⟦MTn⟧} (a value), {@code ⟦n⟧} (a name or term), {@code ⟦WSn⟧} (spacing), {@code ⟦PBn⟧} (paragraph
 * break), and in colour versions {@code ⟦CSn⟧ ... ⟦/CSn⟧}. The panel shows them as aliases ({@code {V0}} and
 * so on, see {@code ManualTextCodec}) and converts back before it calls {@link #save}. A sentence is
 * identified by its <b>semantic key</b>: the original text with the markers, the same key the caches
 * use for the plain-text row of that sentence.</p>
 *
 * <h2>Threads</h2>
 * <p>The panel runs on the client (render) thread and calls every method from there, and it calls
 * {@link #page()} once per frame. So {@code page()}, {@code searchAsync}, {@code refreshAsync} and
 * {@link #editData} must return at once and must never scan a whole cache or wait for a lock held
 * by a scan. {@link #save}, {@link #revertToAutomatic} and {@link #deleteAndRetranslate} may do a
 * little in-memory work and append to the manual-translation file, but must not scan either. All scans
 * (reading every stored row, filtering, sorting) belong on the implementation's own background thread.</p>
 *
 * <h2>Pages</h2>
 * <p>The implementation publishes results as immutable {@link Page}s through a volatile field. A new
 * search or refresh replaces the earlier one (a scan that is no longer wanted stops early). While a
 * scan runs, {@link #page()} keeps returning the last finished page, or a copy of it with
 * {@link Page#loading()} set; it never blocks.</p>
 */
public interface TranslationManagerModel {

    /** The most rows a page lists; a longer result is cut here and {@link Page#truncated()} is set. */
    int MAX_PAGE_ROWS = 200;

    /** The longest translation {@link #save} accepts, in characters ({@link SaveResult#TOO_LONG}). */
    int MAX_TRANSLATION_LENGTH = 2000;

    /** Where the translation that is in use for a sentence comes from. */
    enum Source {
        /** Written by the player in this screen; wins over every automatic source and is never overwritten. */
        MANUAL,
        /** Translated by an AI service. */
        AI,
        /** Translated by the machine translation service. */
        MACHINE
    }

    /**
     * One line of the list.
     *
     * @param key semantic key of the sentence (original text, raw form)
     * @param translation the translation that is in use right now (raw form)
     * @param source where that translation comes from
     * @param hasColorVersions whether the sentence also has colour (⟦CSn⟧) rows, so the editor will
     *                         offer the colour-tagged version(s)
     */
    record Row(String key, String translation, Source source, boolean hasColorVersions) {}

    /**
     * One published result of a search.
     *
     * @param rows the lines to show, at most {@link #MAX_PAGE_ROWS}: manual ones first, then the most
     *             recently saved first; immutable
     * @param matched how many rows matched the query (before the cut at {@link #MAX_PAGE_ROWS})
     * @param totalSaved how many sentences are stored in all, whatever the query
     * @param manualCount how many of those are manual
     * @param truncated {@code matched > rows.size()}: the list is cut and the screen says so
     * @param loading a scan is still running; {@code rows} is then the previous result (possibly empty)
     * @param query the query this page answers (trimmed; empty for none)
     * @param manualOnly whether this page lists manual rows only
     */
    record Page(List<Row> rows, int matched, int totalSaved, int manualCount, boolean truncated,
                boolean loading, String query, boolean manualOnly) {
        public Page {
            rows = List.copyOf(rows);
            query = query == null ? "" : query;
        }

        /** The page before anything has been read: no rows, {@code loading} set. */
        public static final Page LOADING = new Page(List.of(), 0, 0, 0, false, true, "", false);
    }

    /**
     * One colour version of a sentence: a coloured sentence is stored once per way it was coloured
     * (usually one). Both texts carry the colour markers.
     *
     * @param sourceKey the original text with its {@code ⟦CSn⟧ ... ⟦/CSn⟧} markers (the key of the colour row)
     * @param translation the translation of it with the same markers: the manual one if there is one,
     *                    otherwise the most recently stored automatic one. A colour row that has no
     *                    translation yet is not listed.
     */
    record ColorVersion(String sourceKey, String translation) {}

    /**
     * What the editor shows for one sentence.
     *
     * @param key semantic key (same as {@link Row#key()})
     * @param original the original text to show (raw form; normally equal to {@code key})
     * @param translation the translation in use (raw form, no colour markers)
     * @param source where {@code translation} comes from
     * @param automaticTranslation what the automatic layers would show if the manual row were removed,
     *                             or {@code null} when the sentence is not manual or there is none
     * @param automaticSource {@link Source#AI} or {@link Source#MACHINE} for
     *                        {@code automaticTranslation}; {@code null} when that is {@code null}
     * @param colorVersions the colour versions, empty when the sentence has none
     */
    record EditData(String key, String original, String translation, Source source,
                    String automaticTranslation, Source automaticSource, List<ColorVersion> colorVersions) {
        public EditData {
            colorVersions = List.copyOf(colorVersions);
        }
    }

    /** Result of {@link #save}; everything except {@link #OK} means nothing was stored. */
    enum SaveResult {
        /** Stored. The sentence now uses this translation and automatic translation never replaces it. */
        OK,
        /** Blank after trimming. */
        EMPTY,
        /** Equal to the original after trimming (use a do-not-translate term to keep an original). */
        SAME_AS_SOURCE,
        /** Markers of the original are missing; {@link SaveOutcome#tags()} lists them. */
        MISSING_TAGS,
        /** The translation has markers the original does not (or more copies); {@link SaveOutcome#tags()} lists them. */
        EXTRA_TAGS,
        /** The marker set is right but their order or paragraph row is not (paragraph breaks keep their order, values stay in their row), or the colour tags are not in clean pairs, or a name marker is glued to a letter, a digit or another name or value marker (the display check would refuse that text). */
        TAG_ORDER,
        /** The number of line breaks differs from the original; {@link SaveOutcome#number()} is the original's count. */
        NEWLINES,
        /** The translation is mixed with unfinished Latin letters, which the cache would refuse to show. */
        PARTIAL,
        /** Contains a character the caches use internally (NUL and the low control characters). */
        CONTROL_CHARS,
        /** Longer than {@link #MAX_TRANSLATION_LENGTH}; {@link SaveOutcome#number()} is that limit. */
        TOO_LONG,
        /** The manual store is full; {@link SaveOutcome#number()} is its limit. */
        FULL,
        /** The file could not be written. */
        IO_ERROR
    }

    /**
     * Result of {@link #save} with the details the message needs.
     *
     * @param result what happened
     * @param tags for {@code MISSING_TAGS} and {@code EXTRA_TAGS}: the tags in display form
     *             ({@code {V0}}, {@code {V0}×2}); empty otherwise. Build them with {@code ManualTextCodec.compare}.
     * @param number for {@code NEWLINES}, {@code TOO_LONG} and {@code FULL}: the count or limit; 0 otherwise
     */
    record SaveOutcome(SaveResult result, List<String> tags, int number) {
        public SaveOutcome {
            tags = List.copyOf(tags);
        }

        public static final SaveOutcome OK = new SaveOutcome(SaveResult.OK, List.of(), 0);

        public static SaveOutcome of(SaveResult result) { return new SaveOutcome(result, List.of(), 0); }

        public static SaveOutcome tags(SaveResult result, List<String> tags) { return new SaveOutcome(result, tags, 0); }

        public static SaveOutcome number(SaveResult result, int number) { return new SaveOutcome(result, List.of(), number); }

        public boolean ok() { return result == SaveResult.OK; }
    }

    // ------------------------------------------------------------------ the list

    /**
     * The latest published page. Never null. Cheap and non-blocking: a volatile read; called every frame.
     * Before anything was read it is {@link Page#LOADING}.
     */
    Page page();

    /**
     * Starts a search over the stored sentences and replaces any search still running. The result
     * appears later through {@link #page()}. The first call after the model was created also reads the
     * stores (see {@link #refreshAsync}). Returns at once; the scan runs on the implementation's own
     * background thread and checks now and then whether a newer search replaced it.
     *
     * <p>A query is split at blanks; a row matches when every word occurs, ignoring case, in its original
     * or in its translation. Markers are skipped while matching ({@code ManualTextCodec.matchesAll}
     * does exactly this). A blank query matches every row.</p>
     *
     * @param query what the player typed (any length; may be null or blank)
     * @param manualOnly when true only rows whose translation is manual match
     */
    void searchAsync(String query, boolean manualOnly);

    /**
     * Reads the stores again on the background thread and then applies the most recent query given to
     * {@link #searchAsync} (an empty one when there was none). Called when the screen opens, so
     * translations that arrived since the last visit show up. Returns at once.
     */
    void refreshAsync();

    // ------------------------------------------------------------------ one sentence

    /**
     * The data the editor needs for one sentence, read from memory only (no scan, no disk). Called when
     * the player opens a row and again right after a successful save.
     *
     * @return null when the sentence is no longer stored
     */
    EditData editData(String key);

    /**
     * Stores a manual translation. Validates first and stores nothing unless the result is
     * {@link SaveResult#OK}. Checked in this order: blank, same as the original, control characters,
     * length, markers missing or extra (multiset), order/paragraph shape and colour pairing, line-break
     * count, unfinished Latin letters. The plain row and, when {@code colorKey} is given, that
     * colour row are written together.
     *
     * <p>On success the model must itself update what {@link #page()} publishes before it returns (the
     * row now reads {@link Source#MANUAL}, the manual count grows when it was not manual), then refresh
     * in the background to correct the counts. The panel does not patch its list.</p>
     *
     * @param key semantic key of the sentence
     * @param translation the plain translation, raw form, no colour markers
     * @param colorKey key of the colour version being saved (a {@link ColorVersion#sourceKey()}), or null
     *                 for a sentence without colour versions
     * @param colorTranslation that version's translation with its colour markers, or null with a null
     *                         {@code colorKey}; {@code translation} equals it with the colour markers removed
     */
    SaveOutcome save(String key, String translation, String colorKey, String colorTranslation);

    /**
     * Removes the manual translation of the sentence, its colour rows included, and leaves every
     * automatic row alone, so the automatic translation shows again at once. Updates {@link #page()} the
     * way {@link #save} does (the row reads AI or machine again, or disappears when no automatic row exists).
     *
     * @return false when the manual file could not be written
     */
    boolean revertToAutomatic(String key);

    /**
     * Removes everything stored for the sentence (the manual rows and the automatic ones, colour rows,
     * failure marks, and waiting requests) so that it is translated again the next time it appears.
     * Nothing is requested now. Updates {@link #page()} the way {@link #save} does (the row disappears).
     *
     * @return whether online translation is switched on: when false the sentence is translated again
     *         only after the player turns it on, and the screen says so
     */
    boolean deleteAndRetranslate(String key);
}
