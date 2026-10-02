package com.dragonmeow.nyanslate.translate;

import java.util.function.BooleanSupplier;

/**
 * Worker-thread binding of the live "send new translation requests" switch.
 *
 * <p>{@code TranslationCache} binds its gate around every backend call it makes, and
 * {@link RequestPacer#acquire()} checks the binding immediately before each outbound
 * request and again after sleeping for a send slot. A worker that went to sleep before
 * the switch was turned off therefore gives up instead of sending. Threads without a
 * binding (connection tests, account refresh) are never blocked.</p>
 */
public final class RequestGate {

    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

    private RequestGate() {
    }

    /**
     * Bind {@code gate} to the current thread.
     *
     * @return the previous binding, to be passed to {@link #restore} in a finally block
     */
    public static BooleanSupplier bind(BooleanSupplier gate) {
        BooleanSupplier previous = CURRENT.get();
        if (gate == null) CURRENT.remove();
        else CURRENT.set(gate);
        return previous;
    }

    /** Restore the binding returned by {@link #bind}. */
    public static void restore(BooleanSupplier previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /** True unless the current thread is bound to a gate that is currently closed. */
    public static boolean isOpen() {
        BooleanSupplier gate = CURRENT.get();
        if (gate == null) return true;
        try {
            return gate.getAsBoolean();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** @throws RequestsPausedException when the bound gate is closed */
    public static void checkOpen() {
        if (!isOpen()) throw new RequestsPausedException();
    }
}
