package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Hook health registry: answers "did this mixin injection point / event callback
 * ever actually run?". Most mixin handlers use {@code require = 0}, so when a game
 * version changes the target method the injection silently never happens. Every
 * handler calls {@link #hit(String)} (through {@link HookGuard}); the first hit is
 * logged once, and a bounded time after the player is in a world, the hooks that
 * were registered but never hit are logged once.
 *
 * <p>Thread-safe and allocation-free on the hot path (one hash lookup + volatile
 * read). Never logs more than once per hook and once per report. Java 8 compatible
 * on purpose: the same source file is shared byte-for-byte with the legacy trees.</p>
 */
public final class HookHealth {
    /** Seconds after the first in-world ("ambient") hook hit before the miss report. */
    public static final long REPORT_DELAY_SECONDS = 30L;
    /** Fallback delay when no ambient hook ever fired (itself a strong signal). */
    static final long NO_AMBIENT_DELAY_SECONDS = 120L;

    static final class Hook {
        final String id;
        final boolean ambient;
        volatile boolean hit;
        volatile boolean disabled;
        /** Sticky hooks are begin/end style pairs that must never be half-disabled. */
        volatile boolean sticky;
        // Failure accounting (touched only on the exception path, under the hook's monitor).
        long windowStartNanos;
        int windowFailures;
        long totalFailures;
        final ConcurrentMap<String, int[]> failureKinds = new ConcurrentHashMap<String, int[]>();

        Hook(String id, boolean ambient) {
            this.id = id;
            this.ambient = ambient;
        }
    }

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("nyanlex");
    private static final ConcurrentMap<String, Hook> HOOKS = new ConcurrentHashMap<String, Hook>();
    private static volatile LongSupplier clock = new LongSupplier() {
        @Override public long getAsLong() { return System.nanoTime(); }
    };
    private static volatile Consumer<String> infoSink = new Consumer<String>() {
        @Override public void accept(String s) { LOG.info(s); }
    };
    private static volatile Consumer<String> warnSink = new Consumer<String>() {
        @Override public void accept(String s) { LOG.warning(s); }
    };

    private static volatile long firstAmbientHitNanos;   // 0 = none yet
    private static volatile long firstAnyHitNanos;       // 0 = none yet
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private HookHealth() {
    }

    // ---- registration -------------------------------------------------------------

    /** Registers hooks that should fire during ordinary in-world play (HUD, chat render...). */
    public static void expectAmbient(String... ids) {
        for (String id : ids) register(id, true);
    }

    /** Registers hooks that only fire in some situations (books, bossbars, quest UI, tooltips...). */
    public static void expect(String... ids) {
        for (String id : ids) register(id, false);
    }

    private static Hook register(String id, boolean ambient) {
        Hook existing = HOOKS.get(id);
        if (existing != null) return existing;
        Hook created = new Hook(id, ambient);
        Hook raced = HOOKS.putIfAbsent(id, created);
        return raced != null ? raced : created;
    }

    // ---- hot path -----------------------------------------------------------------

    /** Records that a hook ran. O(1); logs only on the very first hit of each hook. */
    public static void hit(String id) {
        Hook hook = HOOKS.get(id);
        if (hook == null) hook = register(id, false); // unregistered id: still tracked
        markHit(hook);
    }

    static Hook hook(String id) {
        Hook hook = HOOKS.get(id);
        return hook != null ? hook : register(id, false);
    }

    static void markHit(Hook hook) {
        if (hook.hit) return;
        synchronized (hook) {
            if (hook.hit) return;
            hook.hit = true;
        }
        long now = clock.getAsLong();
        if (firstAnyHitNanos == 0L) firstAnyHitNanos = now == 0L ? 1L : now;
        if (hook.ambient && firstAmbientHitNanos == 0L) firstAmbientHitNanos = now == 0L ? 1L : now;
        infoSink.accept("[NyanLex] hook active: " + hook.id);
    }

    // ---- reporting ----------------------------------------------------------------

    /**
     * Cheap time-based check, safe to call from any hook on any thread: emits the
     * "registered but never hit" report exactly once when it is due.
     */
    public static void reportIfDue() {
        if (REPORTED.get()) return;
        long ambient = firstAmbientHitNanos;
        long any = firstAnyHitNanos;
        if (any == 0L) return;
        long now = clock.getAsLong();
        boolean due = ambient != 0L
                ? now - ambient >= secondsToNanos(REPORT_DELAY_SECONDS)
                : now - any >= secondsToNanos(NO_AMBIENT_DELAY_SECONDS);
        if (due) report();
    }

    /** Emits the miss report once (later calls are no-ops). Returns true if it was emitted now. */
    public static boolean report() {
        if (!REPORTED.compareAndSet(false, true)) return false;
        List<String> missingAmbient = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        int total = 0;
        int hits = 0;
        for (Hook hook : HOOKS.values()) {
            total++;
            if (hook.hit) { hits++; continue; }
            (hook.ambient ? missingAmbient : missing).add(hook.id);
        }
        Collections.sort(missingAmbient);
        Collections.sort(missing);
        infoSink.accept("[NyanLex] hook report: " + hits + "/" + total + " hooks hit, "
                + (missing.size() + missingAmbient.size()) + " never hit"
                + (missing.isEmpty() ? "" : " (may only fire in some situations): " + missing));
        if (!missingAmbient.isEmpty()) {
            warnSink.accept("[NyanLex] in-world hooks NEVER hit (injection point probably broken on "
                    + "this game version): " + missingAmbient);
        }
        return true;
    }

    /** Hooks registered but not hit yet. */
    public static int unhitCount() {
        int n = 0;
        for (Hook hook : HOOKS.values()) if (!hook.hit) n++;
        return n;
    }

    public static int totalCount() {
        return HOOKS.size();
    }

    public static int disabledCount() {
        int n = 0;
        for (Hook hook : HOOKS.values()) if (hook.disabled) n++;
        return n;
    }

    /** One-line summary for the debug overlay. */
    public static String summary() {
        int total = HOOKS.size();
        int unhit = unhitCount();
        return "HOOKS hit " + (total - unhit) + "/" + total + " | miss " + unhit
                + " | disabled " + disabledCount();
    }

    /** Very short form for appending to an existing overlay line, e.g. {@code HOOKS miss 3/22 off 1}. */
    public static String shortSummary() {
        int disabled = disabledCount();
        return "HOOKS miss " + unhitCount() + "/" + HOOKS.size() + (disabled > 0 ? " off " + disabled : "");
    }

    public static boolean isHit(String id) {
        Hook hook = HOOKS.get(id);
        return hook != null && hook.hit;
    }

    public static boolean isDisabled(String id) {
        Hook hook = HOOKS.get(id);
        return hook != null && hook.disabled;
    }

    static long secondsToNanos(long seconds) {
        return seconds * 1_000_000_000L;
    }

    // ---- sinks / test seams -------------------------------------------------------

    /** Redirects log output (loader glue may route it to its own logger). */
    public static void setSinks(Consumer<String> info, Consumer<String> warn) {
        if (info != null) infoSink = info;
        if (warn != null) warnSink = warn;
    }

    static void logWarn(String s) {
        warnSink.accept(s);
        DebugErrorLog.report(DebugErrorLog.HOOK, s);
    }

    static long now() { return clock.getAsLong(); }

    static void setClock(LongSupplier c) { clock = c; }

    /** Test helper: forget everything. */
    static void resetForTest() {
        HOOKS.clear();
        firstAmbientHitNanos = 0L;
        firstAnyHitNanos = 0L;
        REPORTED.set(false);
    }
}
