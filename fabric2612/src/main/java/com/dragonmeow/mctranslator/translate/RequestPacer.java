package com.dragonmeow.mctranslator.translate;

import java.util.function.LongSupplier;

/**
 * Per-instance minimum interval between outbound requests (事前冷卻節流).
 *
 * <p>Every translator engine owns its own pacer: {@link #acquire()} is called
 * immediately before EACH outbound HTTP request. If the previous request was
 * sent less than the configured cooldown ago, the caller sleeps for the
 * remainder before being released. This is proactive spacing, unlike the
 * existing reactive 429 backoff gates.</p>
 *
 * <p>Concurrency: the lock is only held while RESERVING a send slot
 * ({@code nextAllowedAt} bookkeeping); the actual sleep happens outside the
 * lock. Concurrent callers therefore each reserve consecutive slots and sleep
 * in parallel for their own scheduled time — requests stay spaced by the
 * cooldown, and a sleeping thread never blocks another engine's pacer (each
 * engine has its own instance) nor the slot bookkeeping of its own engine.</p>
 *
 * <p>The cooldown is read from a supplier on every acquire so live config
 * changes apply immediately; {@code <= 0} disables pacing entirely. Clock and
 * sleeper are injectable so unit tests never really sleep.</p>
 */
public final class RequestPacer {

    /** Injectable stand-in for {@link Thread#sleep(long)}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final LongSupplier cooldownMs;
    private final LongSupplier clock;
    private final Sleeper sleeper;

    /** Next wall-clock time a request may be sent. Guarded by {@code this}. */
    private long nextAllowedAt;

    /** Production constructor: real clock, real sleep. */
    public RequestPacer(LongSupplier cooldownMs) {
        this(cooldownMs, System::currentTimeMillis, Thread::sleep);
    }

    /** Test constructor: injectable clock and sleeper. */
    public RequestPacer(LongSupplier cooldownMs, LongSupplier clock, Sleeper sleeper) {
        this.cooldownMs = cooldownMs;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** A pacer that never throttles (for callers that don't wire a config value). */
    public static RequestPacer disabled() {
        return new RequestPacer(() -> 0L);
    }

    /**
     * Block until this instance's minimum send interval has elapsed since the
     * previously reserved slot, then reserve the next slot. Returns immediately
     * when the cooldown is {@code <= 0}. An interrupt re-asserts the thread's
     * interrupt flag and releases the caller (the transport call that follows
     * will surface the failure).
     */
    public void acquire() {
        long cooldown = cooldownMs.getAsLong();
        if (cooldown <= 0) return;
        long waitMs;
        synchronized (this) {
            long now = clock.getAsLong();
            waitMs = nextAllowedAt - now;
            nextAllowedAt = Math.max(now, nextAllowedAt) + cooldown;
        }
        if (waitMs > 0) {
            try {
                sleeper.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
