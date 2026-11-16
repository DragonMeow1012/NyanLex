package com.dragonmeow.nyanlex.service;

/**
 * Decides which language table the game should resolve translation keys with while the
 * "show original" key is on, and holds that table. The point: with the key on, every text
 * the game builds from a language file shows its English source, whatever language the
 * player runs the game in and whoever wrote that language's file.
 *
 * <p>The class is platform free. {@code L} is the game's own language-table type; the glue
 * layer creates one instance, hands it the English table each time resources reload, and
 * asks {@link #effective()} from its hooks. {@code effective()} is on the hot path (every
 * translation key lookup), so it is a single volatile read and never throws.</p>
 *
 * <p>With the key off, or when no separate English table exists, {@code effective()}
 * returns {@code null} and the hooks fall through to the game's own behaviour.</p>
 */
public final class OriginalLanguageSwitch<L> {

    /** The language whose file holds the original (source) wording of every key. */
    public static final String ORIGINAL_LANGUAGE_CODE = "en_us";

    private L table;
    private boolean showOriginal;
    private volatile L effective;

    /**
     * True when the game's selected language is not the original one, i.e. a separate English
     * table has to be loaded. A missing code means the game default, which is already English.
     */
    public static boolean needsEnglishTable(String gameLanguageCode) {
        if (gameLanguageCode == null) return false;
        String code = gameLanguageCode.trim();
        return !code.isEmpty() && !ORIGINAL_LANGUAGE_CODE.equalsIgnoreCase(code);
    }

    /** The English table, or {@code null} to clear it (selected language is English, or loading failed). */
    public synchronized void setTable(L englishTable) {
        this.table = englishTable;
        refresh();
    }

    /** Called when the player flips the "show original" key. */
    public synchronized void setShowOriginal(boolean on) {
        this.showOriginal = on;
        refresh();
    }

    /** The table key lookups must use right now, or {@code null} for the game's own table. */
    public L effective() {
        return effective;
    }

    /**
     * Whether flipping the key changes how the game resolves keys at all. When false (the
     * game is already in English) the flip changes nothing in the language layer, so callers
     * can skip any redraw work that only exists for it.
     */
    public synchronized boolean affectsDisplay() {
        return table != null;
    }

    private void refresh() {
        effective = showOriginal ? table : null;
    }
}
