package com.dragonmeow.nyanlex.translate;

/**
 * Horizontal-move characters of an overlay layout. A resource pack can ship a {@code space}
 * font whose glyphs have no ink and only an advance; overlay dialogue boxes position every
 * element with them. This class knows which code points those are, what advance each has,
 * and how to spell an arbitrary move.
 *
 * <p>Encoding: code point {@code U+D0000 + n} advances by {@code n} px for
 * {@code -4096 <= n <= 4096}; {@code U+C0001} and {@code U+DFFFF} are the two large jumps
 * (-16384 / +16384). The rest of {@code U+C0000..U+DFFFF} is undefined in the pack.</p>
 */
public final class OverlaySpace {

    /** First code point of the move-character planes. */
    public static final int FIRST = 0xC0000;
    /** Last code point of the move-character planes. */
    public static final int LAST = 0xDFFFF;
    /** Code point whose advance is 0. */
    public static final int ZERO = 0xD0000;
    /** Largest single-character move. */
    public static final int MAX_STEP = 4096;
    /** Returned by {@link #advance(int)} for a code point the pack does not define. */
    public static final int UNDEFINED = Integer.MIN_VALUE;

    private static final int NEGATIVE_JUMP = 0xC0001;
    private static final int POSITIVE_JUMP = 0xDFFFF;
    private static final int JUMP = 16384;

    private OverlaySpace() {
    }

    /** True for every code point of the move-character planes (defined or not). */
    public static boolean isMove(int codePoint) {
        return codePoint >= FIRST && codePoint <= LAST;
    }

    /** Advance in px of a move character, or {@link #UNDEFINED}. */
    public static int advance(int codePoint) {
        if (codePoint >= ZERO - MAX_STEP && codePoint <= ZERO + MAX_STEP) {
            return codePoint - ZERO;
        }
        if (codePoint == NEGATIVE_JUMP) {
            return -JUMP;
        }
        if (codePoint == POSITIVE_JUMP) {
            return JUMP;
        }
        return UNDEFINED;
    }

    /** Appends the characters that move the cursor by {@code px} (nothing for 0). */
    public static void appendMove(StringBuilder out, int px) {
        int left = px;
        while (left != 0) {
            int step = Math.max(-MAX_STEP, Math.min(MAX_STEP, left));
            out.appendCodePoint(ZERO + step);
            left -= step;
        }
    }

    /** The characters that move the cursor by {@code px}. */
    public static String move(int px) {
        StringBuilder out = new StringBuilder(2);
        appendMove(out, px);
        return out.toString();
    }
}
