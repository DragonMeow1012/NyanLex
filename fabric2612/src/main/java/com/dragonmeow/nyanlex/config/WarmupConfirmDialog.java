package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.warmup.ItemWarmupPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pre-translation confirmation as one card on the shared {@link DialogPanel} (no Minecraft types):
 * the scan progress, then the item counts, the estimate and all {@link #WARNING_COUNT} warnings. Every
 * paragraph wraps to the card and the content scrolls when the window is small, so nothing is dropped and
 * the footer buttons ([取消] left, [開始預先翻譯] right) stay pinned below the text.
 */
public final class WarmupConfirmDialog {

    public static final int CANCEL = 0;
    public static final int START = 1;
    /** How many numbered warnings ({@code screen.nyanlex.warmup.warn.1} ...) the screen always lists. */
    public static final int WARNING_COUNT = 5;

    private static final int GOLD = 0xFFFFD700;
    private static final int RED = 0xFFFF9090;
    private static final int GREEN = 0xFF80FF80;

    private WarmupConfirmDialog() {}

    /** Scanner progress: items looked at, items to look at, items whose tooltip could not be built. */
    public record Scan(int scanned, int total, int failed) {}

    /** Where the last run stopped, when it did not finish: the card then offers Continue instead of Start. */
    public record Last(boolean resumable, int scanned, int total) {
        public static final Last NONE = new Last(false, 0, 0);

        public static Last of(com.dragonmeow.nyanlex.warmup.ItemWarmupDriver.Progress p) {
            return p == null ? NONE : new Last(p.resumable(), p.lastScanned(), p.lastTotal());
        }
    }

    /** Every lang key the card uses (for the lang-file test). */
    public static List<String> allLangKeys() {
        List<String> keys = new ArrayList<>(List.of("screen.nyanlex.warmup.title",
                "screen.nyanlex.warmup.unavailable.engine", "screen.nyanlex.warmup.scanning",
                "screen.nyanlex.warmup.summary", "screen.nyanlex.warmup.skipped",
                "screen.nyanlex.warmup.skipped.world", "screen.nyanlex.warmup.nothing",
                "screen.nyanlex.warmup.estimate", "screen.nyanlex.warmup.start",
                "screen.nyanlex.warmup.continue", "screen.nyanlex.warmup.last"));
        for (int i = 1; i <= WARNING_COUNT; i++) keys.add("screen.nyanlex.warmup.warn." + i);
        return keys;
    }

    /**
     * What the card shows now.
     *
     * @param eligible whether items go through the AI service (machine translation only gets an explanation)
     * @param scan     scanner progress, or null when there is no scanner
     * @param plan     the finished scan, or null while it is still running
     * @param inWorld  whether a world is loaded (picks the wording of the "skipped" line)
     */
    public static DialogPanel.Content content(boolean eligible, Scan scan, ItemWarmupPlan plan, boolean inWorld,
                                              DialogContent.Lang lang) {
        return content(eligible, scan, plan, inWorld, Last.NONE, lang);
    }

    /** As above, with where the last run stopped ({@code last}) so an unfinished one is offered as Continue. */
    public static DialogPanel.Content content(boolean eligible, Scan scan, ItemWarmupPlan plan, boolean inWorld,
                                              Last last, DialogContent.Lang lang) {
        if (last == null) last = Last.NONE;
        List<DialogPanel.Block> blocks = new ArrayList<>();
        boolean canStart = false;
        if (!eligible) {
            blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.unavailable.engine"), GOLD));
        } else if (plan == null) {
            blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.scanning",
                    scan == null ? 0 : scan.scanned(), scan == null ? 0 : scan.total()), 0));
        } else {
            blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.summary",
                    plan.totalItems(), plan.cachedItems(), plan.nativeItems(), plan.missingItems()), 0));
            if (scan != null && scan.failed() > 0) {
                String key = inWorld ? "screen.nyanlex.warmup.skipped.world" : "screen.nyanlex.warmup.skipped";
                blocks.add(new DialogPanel.Text(lang.get(key, scan.failed()), DialogPanel.C_MUTED));
            }
            if (plan.nothingToDo()) {
                blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.nothing"), GREEN));
            } else {
                canStart = true;
                if (last.resumable()) {
                    blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.last",
                            last.scanned(), last.total()), DialogPanel.C_MUTED));
                }
                blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.estimate",
                        plan.willSubmitItems(), plan.estimatedRequests(), formatTokens(plan.estimatedTokens()),
                        plan.estimatedMinutes()), GOLD));
                blocks.add(new DialogPanel.Gap(4));
                for (int i = 1; i <= WARNING_COUNT; i++) {
                    blocks.add(new DialogPanel.Text(lang.get("screen.nyanlex.warmup.warn." + i),
                            i == 2 || i == 5 ? RED : DialogPanel.C_MUTED));
                }
            }
        }
        return new DialogPanel.Content(lang.get("screen.nyanlex.warmup.title"), blocks,
                DialogPanel.Footer.of(new DialogPanel.Btn(CANCEL, lang.get("gui.cancel")),
                        new DialogPanel.Btn(START, lang.get(last.resumable() && canStart
                                ? "screen.nyanlex.warmup.continue" : "screen.nyanlex.warmup.start"),
                                true, false, canStart)),
                CANCEL);
    }

    /** 1234 becomes "1K", 2500000 becomes "2.5M". */
    public static String formatTokens(long tokens) {
        if (tokens >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000L) return String.format(Locale.ROOT, "%.0fK", tokens / 1_000.0);
        return Long.toString(tokens);
    }
}
