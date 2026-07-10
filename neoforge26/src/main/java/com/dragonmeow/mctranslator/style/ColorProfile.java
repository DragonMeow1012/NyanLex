package com.dragonmeow.mctranslator.style;

/**
 * A snapshot of the colour + formatting of a piece of original text, captured
 * per visible character. This is what lets us "remember" a multi-colour / rainbow
 * ("色彩跑馬燈") original and re-apply that colour sweep onto the translated text.
 *
 * <p>Minecraft-free on purpose: the glue extracts the colours out of a
 * {@code Text} into the {@code int[]}, and this class owns the mapping logic so
 * it can be unit-tested.</p>
 */
public final class ColorProfile {

    /** Sentinel for "no explicit colour" (use the default render colour). */
    public static final int NO_COLOR = -1;

    private final int[] colors; // RGB per original visible char, or NO_COLOR
    private final boolean bold;
    private final boolean italic;
    private final boolean underline;
    private final boolean strikethrough;
    private final boolean obfuscated;

    public ColorProfile(int[] colors, boolean bold, boolean italic,
                        boolean underline, boolean strikethrough, boolean obfuscated) {
        this.colors = (colors == null) ? new int[0] : colors.clone();
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
        this.strikethrough = strikethrough;
        this.obfuscated = obfuscated;
    }

    public static ColorProfile empty() {
        return new ColorProfile(new int[0], false, false, false, false, false);
    }

    /**
     * The single most-common explicit colour in the original (or {@link #NO_COLOR}
     * if the original has no colour). Used to colour the whole translation in one
     * clean colour instead of stretching a per-character sweep (which misaligns and
     * looks messy on translated text).
     */
    public int dominantColor() {
        java.util.HashMap<Integer, Integer> freq = new java.util.HashMap<>();
        int best = NO_COLOR;
        int bestCount = 0;
        for (int c : colors) {
            if (c == NO_COLOR) continue;
            int n = freq.merge(c, 1, Integer::sum);
            if (n > bestCount) {
                bestCount = n;
                best = c;
            }
        }
        return best;
    }

    public boolean hasAnyColor() {
        for (int c : colors) {
            if (c != NO_COLOR) return true;
        }
        return false;
    }

    /**
     * Number of distinct explicit colours in the original. {@code >= 2} means the
     * line is multi-coloured (e.g. a coloured chat broadcast), so the glue maps the
     * colours onto the translation per-segment instead of using one flat colour.
     */
    public int distinctColorCount() {
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int c : colors) {
            if (c != NO_COLOR) seen.add(c);
        }
        return seen.size();
    }

    public boolean hasAnyStyle() {
        return hasAnyColor() || bold || italic || underline || strikethrough || obfuscated;
    }

    /**
     * Colour for character {@code translatedIndex} of a translated string of
     * length {@code translatedLen}. The original colour sequence is stretched /
     * compressed across the translation, so a gradient on a 4-char original is
     * spread evenly over a 2-char or 10-char translation.
     */
    public int colorAt(int translatedIndex, int translatedLen) {
        if (colors.length == 0 || translatedLen <= 0) return NO_COLOR;
        int src = (int) ((long) translatedIndex * colors.length / translatedLen);
        if (src < 0) src = 0;
        if (src >= colors.length) src = colors.length - 1;
        return colors[src];
    }

    public int originalLength() {
        return colors.length;
    }

    public boolean bold() {
        return bold;
    }

    public boolean italic() {
        return italic;
    }

    public boolean underline() {
        return underline;
    }

    public boolean strikethrough() {
        return strikethrough;
    }

    public boolean obfuscated() {
        return obfuscated;
    }
}
