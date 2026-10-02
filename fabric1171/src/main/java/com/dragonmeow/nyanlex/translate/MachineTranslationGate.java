package com.dragonmeow.nyanlex.translate;

import java.util.function.LongSupplier;

/**
 * Global back-off gate for the key-free Google endpoint.
 *
 * <p>A 429 (or Google's "unusual traffic" page) from that endpoint blocks the whole client
 * address, not one text, so continuing to send only prolongs the block. Once tripped the gate
 * is CLOSED: no machine-translation request of any kind is sent until the back-off ends.
 * Steps: 1, 2, 5, 15 minutes, then 30 minutes (the cap). When the back-off ends the gate lets
 * exactly ONE probe request through; a success resets the gate to the first step, another
 * 429 moves on to the next step.</p>
 *
 * <p>Waiting work is never failed by the gate: callers get {@link RequestsPausedException},
 * which the cache treats as "unsent, not failed" (no per-text back-off, no negative cache).
 * Minecraft-free; the clock is injectable so tests never wait.</p>
 */
public final class MachineTranslationGate {

    /** Back-off per consecutive 429, in minutes; the last step repeats. */
    private static final long[] BACKOFF_MINUTES = {1, 2, 5, 15, 30};
    private static final long MINUTE_MS = 60_000L;

    /** Told once each time the gate closes (including a re-close after a failed probe). */
    @FunctionalInterface
    public interface Listener {
        void onClosed(int minutes);
    }

    private static final MachineTranslationGate SHARED = new MachineTranslationGate();

    private final LongSupplier clock;
    private final Object lock = new Object();
    private int level;          // consecutive trips; 0 = healthy
    private long closedUntil;   // guarded by lock
    private boolean probing;    // a probe request is in flight
    private volatile Listener listener;

    public MachineTranslationGate() {
        this(System::currentTimeMillis);
    }

    public MachineTranslationGate(LongSupplier clock) {
        this.clock = clock;
    }

    /** The one gate production machine translation shares. */
    public static MachineTranslationGate shared() {
        return SHARED;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Permission to send one request.
     *
     * @return {@code true} when the caller is the probe of a reopened gate, {@code false}
     *         for an ordinary request
     * @throws RequestsPausedException while closed, or while another probe is in flight
     */
    public boolean acquire() {
        synchronized (lock) {
            if (level == 0) return false;
            if (clock.getAsLong() < closedUntil || probing) throw new RequestsPausedException();
            probing = true;
            return true;
        }
    }

    /** Re-check after a pacing sleep: an ordinary request abandons when the gate closed meanwhile. */
    public void recheck(boolean probe) {
        if (probe) return;
        synchronized (lock) {
            if (level != 0) throw new RequestsPausedException();
        }
    }

    /** A request got a normal answer: Google is reachable again, back to the first step. */
    public void onSuccess() {
        synchronized (lock) {
            level = 0;
            closedUntil = 0L;
            probing = false;
        }
    }

    /** The request ended without proving anything (other failure, abandoned): free the probe slot. */
    public void onAbort(boolean probe) {
        if (!probe) return;
        synchronized (lock) {
            probing = false;
        }
    }

    /**
     * A request was rate limited. The first one (or a failed probe) closes the gate for the next
     * step; further 429s of requests that were already in flight are absorbed.
     *
     * @return the back-off in minutes now in force
     */
    public int onRateLimited() {
        int minutes;
        boolean closedNow;
        synchronized (lock) {
            long now = clock.getAsLong();
            probing = false;
            if (level > 0 && now < closedUntil) {
                return (int) minutesLeft(now); // already closed: do not escalate twice
            }
            level++;
            long step = BACKOFF_MINUTES[Math.min(level, BACKOFF_MINUTES.length) - 1];
            closedUntil = now + step * MINUTE_MS;
            minutes = (int) step;
            closedNow = true;
        }
        Listener l = listener;
        if (closedNow && l != null) {
            try {
                l.onClosed(minutes);
            } catch (RuntimeException ignored) {
                // feedback must never break translation
            }
        }
        return minutes;
    }

    /** True while requests are held back: closed, or a probe is already testing the endpoint. */
    public boolean blocksRequests() {
        synchronized (lock) {
            return level > 0 && (clock.getAsLong() < closedUntil || probing);
        }
    }

    /** Whole minutes (rounded up, at least 1) until the gate reopens; 0 when it is not blocking. */
    public int minutesUntilReopen() {
        synchronized (lock) {
            if (level == 0) return 0;
            return (int) minutesLeft(clock.getAsLong());
        }
    }

    /** Milliseconds until the gate reopens; 0 when it is open or only a probe is pending. */
    public long millisUntilReopen() {
        synchronized (lock) {
            return level == 0 ? 0L : Math.max(0L, closedUntil - clock.getAsLong());
        }
    }

    private long minutesLeft(long now) {
        long ms = Math.max(0L, closedUntil - now);
        return Math.max(1L, (ms + MINUTE_MS - 1) / MINUTE_MS);
    }

    /** Whether Google's answer is the "unusual traffic" block page instead of a translation. */
    public static boolean isBlockPage(String body) {
        if (body == null) return false;
        String head = body.length() > 6000 ? body.substring(0, 6000) : body;
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unusual traffic")
                || (lower.contains("<html") && lower.contains("/sorry/"));
    }

    /** Whether a transport failure message is an HTTP 429. */
    public static boolean isRateLimitMessage(String message) {
        return message != null && (message.contains("HTTP 429") || isBlockPage(message));
    }
}
