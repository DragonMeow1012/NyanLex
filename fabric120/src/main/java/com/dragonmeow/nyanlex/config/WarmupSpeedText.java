package com.dragonmeow.nyanlex.config;

/**
 * Picks the lang key for the pre-translation speed sentence ("about N items per minute, about M minutes
 * left") so languages that mark the plural (English) read right at exactly one: "1 item", "1 minute".
 * Each variant keeps both {@code %s} placeholders, so the caller always passes the same two numbers;
 * languages without a plural (Chinese) carry the same wording in every variant.
 */
public final class WarmupSpeedText {

    private WarmupSpeedText() {}

    /** The progress screen's speed line. */
    public static String progressKey(long itemsPerMinute, long minutesLeft) {
        boolean item = itemsPerMinute == 1;
        boolean minute = minutesLeft == 1;
        if (item && minute) return "screen.nyanlex.warmup.progress.speed.item_one_minute_one";
        if (item) return "screen.nyanlex.warmup.progress.speed.item_one";
        if (minute) return "screen.nyanlex.warmup.progress.speed.minute_one";
        return "screen.nyanlex.warmup.progress.speed";
    }

    /** The settings card's running-state line. */
    public static String stateKey(long itemsPerMinute, long minutesLeft) {
        boolean item = itemsPerMinute == 1;
        boolean minute = minutesLeft == 1;
        if (item && minute) return "screen.nyanlex.warmup.state.running.speed.item_one_minute_one";
        if (item) return "screen.nyanlex.warmup.state.running.speed.item_one";
        if (minute) return "screen.nyanlex.warmup.state.running.speed.minute_one";
        return "screen.nyanlex.warmup.state.running.speed";
    }
}
