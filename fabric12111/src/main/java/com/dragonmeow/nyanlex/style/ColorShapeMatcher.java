package com.dragonmeow.nyanlex.style;

import java.util.List;

/**
 * Decides whether two renders of the SAME plain text (same words, same masked slots —
 * the caller has already confirmed that) can share one cached translation, given only
 * how each one is coloured. Minecraft-free and side-effect-free on purpose, like
 * {@link ColorProfile}, so the decision table below is directly unit-testable.
 *
 * <p>Motivating bug (2026-10-02): a Hypixel rarity line such as {@code "MYTHIC DUNGEON
 * BOW"} sometimes renders as one solid colour and sometimes as a per-character rainbow
 * animation (the same English text, caught at a different animation frame). Today every
 * distinct colour SHAPE mints its own cache key/⟦CSn⟧ marker layout, so the rainbow frame
 * re-sends the line to the translator even though nothing about its MEANING changed.
 * This class names the three outcomes that matter and the one rule that tells them
 * apart:</p>
 *
 * <ol>
 *   <li><b>{@link Category#SAME_STRUCTURE}</b> — both renders cut the text at the exact
 *       same character positions, and neither run is a gradient: only the RGB values
 *       differ. The existing translation is reused verbatim; the caller just re-applies
 *       this render's own colours at the same cut points (already how the ⟦CSn⟧ marker
 *       protocol works, since a marker index never encodes a colour VALUE).</li>
 *   <li><b>{@link Category#GRADIENT_REMAP}</b> — the two shapes disagree only because one
 *       (or both) sides is a per-character gradient/rainbow covering the WHOLE span: one
 *       side solid + the other a gradient, or both gradients with different sequences.
 *       The existing translation is still reused; the caller instead REDISTRIBUTES this
 *       render's colour sequence across the translated text's characters (see
 *       {@link #redistribute}) so the gradient still animates on the translated word.</li>
 *   <li><b>{@link Category#DIFFERENT_SEMANTIC}</b> — the cut points differ in some OTHER
 *       way (e.g. only one word out of several changed colour, or an extra colour run
 *       appeared mid-phrase). Nothing says the translated characters still line up with
 *       the ORIGINAL translation's structure, so it is not safe to reuse: this is
 *       classified as independent content and re-translated under its own key.</li>
 * </ol>
 *
 * <p>Cache organisation this implies (see class javadoc of the caller):
 * the cache is keyed by PLAIN TEXT; a plain-text entry carries one PRIMARY translation
 * (reused for every {@code SAME_STRUCTURE}/{@code GRADIENT_REMAP} render) plus, only when
 * {@code DIFFERENT_SEMANTIC} renders are seen, additional entries keyed by plain text +
 * the run-boundary "shape" that produced them.</p>
 */
public final class ColorShapeMatcher {

    private ColorShapeMatcher() {
    }

    public enum Category { SAME_STRUCTURE, GRADIENT_REMAP, DIFFERENT_SEMANTIC }

    /**
     * One coloured run over a SHARED plain text: the visible character range
     * {@code [start, end)} it covers, plus one RGB value per character in that range
     * ({@code perCharColor.length == end - start}). A "solid" run repeats the same value
     * for every character; a "gradient" run varies it — see {@link #isGradient}.
     */
    public record ColorRun(int start, int end, int[] perCharColor) {
        public ColorRun {
            if (end <= start) {
                throw new IllegalArgumentException("empty/backwards run: [" + start + "," + end + ")");
            }
            if (perCharColor == null || perCharColor.length != end - start) {
                throw new IllegalArgumentException(
                        "perCharColor must have exactly " + (end - start) + " entries");
            }
            perCharColor = perCharColor.clone();
        }

        @Override
        public int[] perCharColor() {
            return perCharColor.clone();
        }

        int length() {
            return end - start;
        }
    }

    /** A run "is a gradient" when it carries more than one distinct colour — a solid run
     *  (including a run with no explicit colour at all) always has exactly one. */
    public static boolean isGradient(ColorRun run) {
        return distinctColors(run) > 1;
    }

    private static int distinctColors(ColorRun run) {
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int c : run.perCharColor) seen.add(c);
        return seen.size();
    }

    /** Validates that {@code runs} are sorted, non-overlapping and cover exactly
     *  {@code [0, textLength)} with no gaps — every caller's shape must describe the
     *  WHOLE text, so a classification is never made from a partial view. */
    private static void validate(List<ColorRun> runs, int textLength) {
        int cursor = 0;
        for (ColorRun run : runs) {
            if (run.start() != cursor) {
                throw new IllegalArgumentException(
                        "runs must be contiguous from 0: expected start " + cursor
                                + " but got " + run.start());
            }
            cursor = run.end();
        }
        if (cursor != textLength) {
            throw new IllegalArgumentException(
                    "runs must cover the whole text: covered " + cursor + " of " + textLength);
        }
    }

    /** Whether {@code runs} is exactly one run spanning the whole {@code [0, textLength)}. */
    private static boolean isSingleWholeSpan(List<ColorRun> runs, int textLength) {
        return runs.size() == 1 && runs.get(0).start() == 0 && runs.get(0).end() == textLength;
    }

    /** Whether {@code previous} and {@code current} cut the text at the exact same
     *  character boundaries (same run count, same {@code [start,end)} per pair, in order —
     *  the only thing that may differ is each pair's actual colour values). */
    private static boolean boundariesMatch(List<ColorRun> previous, List<ColorRun> current) {
        if (previous.size() != current.size()) return false;
        for (int i = 0; i < previous.size(); i++) {
            ColorRun a = previous.get(i);
            ColorRun b = current.get(i);
            if (a.start() != b.start() || a.end() != b.end()) return false;
        }
        return true;
    }

    /**
     * Classifies how {@code current} relates to {@code previous} for the SAME plain text
     * (length {@code textLength}); see the class javadoc for what each outcome means and
     * what the caller should do with it. Both lists must be validated run sequences
     * (sorted, contiguous, covering the whole text) or this throws
     * {@link IllegalArgumentException} — callers build {@link ColorRun}s from an already
     * color-extracted render, so a malformed shape is a caller bug, not recoverable input.
     */
    public static Category classify(List<ColorRun> previous, List<ColorRun> current, int textLength) {
        if (previous == null || current == null) {
            throw new IllegalArgumentException("runs must not be null");
        }
        validate(previous, textLength);
        validate(current, textLength);

        if (boundariesMatch(previous, current)) {
            boolean anyGradient = false;
            for (int i = 0; i < previous.size(); i++) {
                if (isGradient(previous.get(i)) || isGradient(current.get(i))) {
                    anyGradient = true;
                    break;
                }
            }
            return anyGradient ? Category.GRADIENT_REMAP : Category.SAME_STRUCTURE;
        }

        // Boundaries differ. The only safe-to-reuse shape mismatch recognised here is
        // "the whole text is treated as ONE continuous run on each side, and at least one
        // side is a gradient" — e.g. a per-character rainbow spanning several words on one
        // render, a single flat colour spanning the identical words on another. Anything
        // else (a different NUMBER of semantic cut points not explained by that) means some
        // word individually changed treatment, which this cannot safely map character-for-
        // character — classified as independent content instead of guessed.
        if (isSingleWholeSpan(previous, textLength) && isSingleWholeSpan(current, textLength)
                && (isGradient(previous.get(0)) || isGradient(current.get(0)))) {
            return Category.GRADIENT_REMAP;
        }
        return Category.DIFFERENT_SEMANTIC;
    }

    /**
     * Proportionally maps a {@code sourceLength}-character colour/gradient sequence onto
     * {@code targetLength} characters (the translated text, usually a different length):
     * output character {@code i} takes {@code source[i * sourceLength / targetLength]}.
     * Used for {@link Category#GRADIENT_REMAP}: the ORIGINAL per-character sequence is
     * redistributed across the (already-translated, reused) output text, rather than
     * recomputed — nothing here guesses word alignment, only character proportion.
     */
    public static int[] redistribute(int[] sourceColors, int targetLength) {
        if (targetLength <= 0) return new int[0];
        if (sourceColors == null || sourceColors.length == 0) {
            int[] out = new int[targetLength];
            java.util.Arrays.fill(out, ColorProfile.NO_COLOR);
            return out;
        }
        int[] out = new int[targetLength];
        for (int i = 0; i < targetLength; i++) {
            out[i] = sourceColors[sourceIndexFor(targetLength, sourceColors.length, i)];
        }
        return out;
    }

    /**
     * The proportional index into a {@code sourceLength}-long sequence that output
     * position {@code targetIndex} (of {@code targetLength} total) should sample — the
     * single piece of arithmetic every gradient-redistribution call site shares (plain
     * {@code int[]} colours here, {@code net.minecraft.network.chat.Style} lists in the
     * Minecraft-aware glue). Endpoint-anchored: output index {@code 0} always samples
     * source index {@code 0} and output index {@code targetLength - 1} always samples
     * {@code sourceLength - 1}, with every index in between rounded to the nearest
     * proportional source sample — so the translated text's first and last characters
     * always show the gradient's true first and last colour, whether the gradient is
     * stretched onto more characters or compressed onto fewer.
     */
    public static int sourceIndexFor(int targetLength, int sourceLength, int targetIndex) {
        if (targetLength <= 0 || sourceLength <= 0) return 0;
        if (targetLength == 1 || sourceLength == 1) return 0;
        long idx = Math.round(targetIndex * (sourceLength - 1) / (double) (targetLength - 1));
        if (idx < 0) idx = 0;
        if (idx >= sourceLength) idx = sourceLength - 1;
        return (int) idx;
    }
}
