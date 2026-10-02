package com.dragonmeow.nyanslate.translate;

import java.util.function.Supplier;

/**
 * Uniform crash guard for every glue entry point that the game's render / tick /
 * event threads call into (mixin handlers, event callbacks). Contract:
 * <ul>
 *   <li>never lets an exception escape into the game: the caller returns the
 *       original, untranslated value;</li>
 *   <li>logs a given exception kind once per hook (plus a counter note at
 *       10/100/1000... repeats) so a per-frame failure cannot flood the log;</li>
 *   <li>a hook that keeps failing ({@link #DISABLE_THRESHOLD} failures inside
 *       {@link #WINDOW_SECONDS} seconds) is disabled until the next game start;</li>
 *   <li>also records the hook's first hit in {@link HookHealth}.</li>
 * </ul>
 *
 * <p>Inline pattern used by the mixins (no lambda allocation on the hot path):</p>
 * <pre>
 * if (!HookGuard.enter(ID)) return text;          // hit + disabled check
 * try { ...body... } catch (Throwable t) { HookGuard.fail(ID, t); return text; }
 * </pre>
 * Begin/end pairs that share render state use {@link #enterSticky(String)}: they
 * are never disabled (half-disabling a pair would unbalance it) but still log.
 */
public final class HookGuard {
    public static final int DISABLE_THRESHOLD = 20;
    public static final long WINDOW_SECONDS = 10L;

    private HookGuard() {
    }

    /** Records the hit; returns false when this hook has been disabled after repeated failures. */
    public static boolean enter(String id) {
        HookHealth.Hook hook = HookHealth.hook(id);
        HookHealth.markHit(hook);
        HookHealth.reportIfDue();
        return !hook.disabled;
    }

    /** Like {@link #enter} but the hook is never auto-disabled (paired begin/end handlers). */
    public static boolean enterSticky(String id) {
        HookHealth.Hook hook = HookHealth.hook(id);
        hook.sticky = true;
        HookHealth.markHit(hook);
        HookHealth.reportIfDue();
        return true;
    }

    /**
     * Handles an exception caught in a hook. VirtualMachineErrors other than
     * StackOverflowError (OutOfMemoryError etc.) are rethrown, everything else is absorbed.
     */
    public static void fail(String id, Throwable error) {
        if (error instanceof VirtualMachineError && !(error instanceof StackOverflowError)) {
            throw (VirtualMachineError) error;
        }
        HookHealth.Hook hook = HookHealth.hook(id);
        String kind = error.getClass().getName();
        boolean disableNow = false;
        String message = null;
        boolean withTrace = false;
        synchronized (hook) {
            hook.totalFailures++;
            int[] count = hook.failureKinds.get(kind);
            if (count == null) {
                count = new int[1];
                hook.failureKinds.put(kind, count);
            }
            int n = ++count[0];
            if (n == 1) {
                withTrace = true;
                message = "[Nyanslate] hook '" + id + "' threw " + kind + ": " + error.getMessage()
                        + " (returning the original text; repeats are rate-limited)";
            } else if (n == 10 || n == 100 || n == 1000 || n % 10000 == 0) {
                message = "[Nyanslate] hook '" + id + "' threw " + kind + " " + n + " times";
            }
            long now = HookHealth.now();
            if (hook.windowFailures == 0
                    || now - hook.windowStartNanos > HookHealth.secondsToNanos(WINDOW_SECONDS)) {
                hook.windowStartNanos = now;
                hook.windowFailures = 0;
            }
            hook.windowFailures++;
            if (!hook.sticky && !hook.disabled && hook.windowFailures >= DISABLE_THRESHOLD) {
                hook.disabled = true;
                disableNow = true;
            }
        }
        if (message != null) {
            if (withTrace) {
                java.io.StringWriter trace = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(trace));
                message = message + System.lineSeparator() + trace;
            }
            HookHealth.logWarn(message);
        }
        if (disableNow) {
            HookHealth.logWarn("[Nyanslate] hook '" + id + "' disabled until the next game start after "
                    + DISABLE_THRESHOLD + " failures within " + WINDOW_SECONDS + "s");
        }
    }

    /** Lambda form for callbacks: returns {@code fallback} on failure or when disabled. */
    public static <T> T call(String id, Supplier<T> body, Supplier<T> fallback) {
        if (!enter(id)) return fallback.get();
        try {
            return body.get();
        } catch (Throwable t) {
            fail(id, t);
            return fallback.get();
        }
    }

    /** Lambda form for void callbacks. */
    public static void run(String id, Runnable body) {
        if (!enter(id)) return;
        try {
            body.run();
        } catch (Throwable t) {
            fail(id, t);
        }
    }

    /** Sticky void variant (never auto-disabled). */
    public static void runSticky(String id, Runnable body) {
        enterSticky(id);
        try {
            body.run();
        } catch (Throwable t) {
            fail(id, t);
        }
    }
}
