package com.dragonmeow.nyanlex.translate;

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

    /**
     * Worker-thread marker of the item warm-up lane. The lane paces itself (its own
     * dispatch interval and concurrency), so AI requests it makes skip the interactive
     * cooldown; a request of any other thread still reserves and waits for its slot.
     */
    private static final ThreadLocal<Boolean> UNPACED = new ThreadLocal<>();

    /** Mark the current thread as the warm-up lane; returns the previous mark for {@link #restoreUnpaced}. */
    public static Boolean bindUnpaced() {
        Boolean previous = UNPACED.get();
        UNPACED.set(Boolean.TRUE);
        return previous;
    }

    public static void restoreUnpaced(Boolean previous) {
        if (previous == null) UNPACED.remove();
        else UNPACED.set(previous);
    }

    /** Whether the current thread is exempt from the interactive cooldown. */
    public static boolean isUnpacedThread() {
        return Boolean.TRUE.equals(UNPACED.get());
    }

    /**
     * Like {@link #acquire()}, but a thread marked by {@link #bindUnpaced} only checks the
     * request switch and reserves nothing (interactive requests keep their own spacing).
     */
    public void acquireForAi() {
        if (isUnpacedThread()) {
            RequestGate.checkOpen();
            return;
        }
        acquire();
    }

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
     * Read-only peek: how long an {@link #acquire()} issued now would sleep, {@code 0}
     * when the next send slot is already free or pacing is off. Reserves nothing, so a
     * batch collector can keep collecting until the engine may actually send (the
     * collector itself guards against composing a second batch before the first one has
     * reserved its slot).
     */
    public long delayUntilNextSlotMs() {
        if (cooldownMs.getAsLong() <= 0) return 0L;
        synchronized (this) {
            return Math.max(0L, nextAllowedAt - clock.getAsLong());
        }
    }

    /**
     * Block until this instance's minimum send interval has elapsed since the
     * previously reserved slot, then reserve the next slot. Returns immediately
     * when the cooldown is {@code <= 0}. An interrupt re-asserts the thread's
     * interrupt flag and aborts the request before it reaches the transport.
     *
     * <p>The worker's {@link RequestGate} is checked first (even with pacing off) and
     * again after sleeping, so a request waiting here when new requests are switched
     * off is abandoned with {@link RequestsPausedException} instead of being sent.</p>
     */
    public void acquire() {
        RequestGate.checkOpen();
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
                throw new RequestsPausedException();
            }
            RequestGate.checkOpen();
        }
    }
}
