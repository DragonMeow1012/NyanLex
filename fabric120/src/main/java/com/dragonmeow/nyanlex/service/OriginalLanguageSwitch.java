package com.dragonmeow.nyanlex.service;

/**
 * The link between the "show original" key and the game's own language files. That link is
 * switched off: the key flips this mod's translations back to the text the game showed
 * before the mod touched it, and nothing else. For a player who runs the game in Chinese
 * that text is Chinese, so the game's language table must never be swapped for the English
 * one. Doing so turned the whole interface English the moment the key was pressed.
 *
 * <p>The class keeps its shape because the loader glue (the language hooks of every modern
 * tree) is written against it. With every answer below fixed, that glue loads no extra
 * table, the hooks fall through to the game's own behaviour, and no screen is rebuilt for
 * the key. {@code L} is the game's language-table type.</p>
 */
public final class OriginalLanguageSwitch<L> {

    /** The language whose file holds the English wording of every key. */
    public static final String ORIGINAL_LANGUAGE_CODE = "en_us";

    /** Always false: no separate English table is loaded, whatever language the game runs in. */
    public static boolean needsEnglishTable(String gameLanguageCode) {
        return false;
    }

    /** Ignored: a table handed over by the glue is never used. */
    public void setTable(L englishTable) {
    }

    /** Ignored: the key does not reach the language layer. */
    public void setShowOriginal(boolean on) {
    }

    /** Always {@code null}: key lookups use the game's own table. */
    public L effective() {
        return null;
    }

    /** Always false: flipping the key changes nothing in the language layer. */
    public boolean affectsDisplay() {
        return false;
    }
}
