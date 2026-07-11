package com.dragonmeow.mctranslator.translate;

/**
 * Re-applies an original line's leading and trailing whitespace to its translation,
 * so the translation lines up the same way the original did
 * (「原文怎麼排列翻譯後就怎麼排」).
 *
 * <p>The translation backend trims surrounding whitespace, so a server line that
 * was indented / centred with leading spaces (e.g. {@code "    WEBSITE: ..."})
 * comes back flush-left. Wrapping the translated core back in the original's outer
 * whitespace restores the original's column alignment / indentation.</p>
 *
 * <p>Minecraft-free and idempotent (the translated core is stripped before being
 * re-wrapped), so applying it twice is a no-op.</p>
 */
public final class LayoutPreserver {

    private LayoutPreserver() {
    }

    /**
     * @return {@code translated} wrapped in {@code original}'s leading and trailing
     *         whitespace. When the original has no surrounding whitespace the
     *         translation is returned unchanged.
     */
    public static String matchOuterWhitespace(String original, String translated) {
        if (original == null || translated == null) return translated;
        int n = original.length();
        int start = 0;
        while (start < n && Character.isWhitespace(original.charAt(start))) start++;
        int end = n;
        while (end > start && Character.isWhitespace(original.charAt(end - 1))) end--;
        if (start == 0 && end == n) return translated; // original has no outer whitespace
        return original.substring(0, start) + translated.strip() + original.substring(end);
    }
}
