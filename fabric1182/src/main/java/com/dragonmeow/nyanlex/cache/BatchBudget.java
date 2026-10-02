package com.dragonmeow.nyanlex.cache;

/**
 * The one character budget and packing rule of an AI request that mixes several surfaces:
 * the interactive collector (screen capture, chat, tooltips) and the item warm-up both
 * pack their units with it, so a warm-up request is never larger than a screen-translation
 * request.
 *
 * <p>Units are atomic. A unit costs its length plus {@link #UNIT_OVERHEAD}; the first unit
 * always fits (a single oversized unit travels alone, whole), any later unit that would
 * push the total past the budget stays for the next request rather than being sliced.</p>
 */
public final class BatchBudget {
    /** Input character budget of one window-collected AI request. */
    public static final int WINDOWED_CHARS = 4_000;
    /** Per-unit bookkeeping cost (anchor, separators). */
    public static final int UNIT_OVERHEAD = 16;

    private final int budget;
    private int chars;
    private int count;

    public BatchBudget(int budget) {
        this.budget = Math.max(1, budget);
    }

    /** A budget of the shared AI request size. */
    public static BatchBudget windowed() {
        return new BatchBudget(WINDOWED_CHARS);
    }

    /** Cost of one unit of {@code text}. */
    public static int unitChars(String text) {
        return (text == null ? 0 : text.length()) + UNIT_OVERHEAD;
    }

    /** Whether a unit of {@code nextChars} may join the request being packed. */
    public boolean fits(int nextChars) {
        return count == 0 || chars + nextChars <= budget;
    }

    public void add(int unitChars) {
        chars += unitChars;
        count++;
    }

    /** The request is full: nothing more should be offered. */
    public boolean full() {
        return chars >= budget;
    }

    public int chars() {
        return chars;
    }

    public int count() {
        return count;
    }

    public int budget() {
        return budget;
    }
}
