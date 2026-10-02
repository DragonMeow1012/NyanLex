package com.dragonmeow.nyanlex.translate;

import java.util.function.Supplier;

/**
 * Out-of-band guard for text that this mod has already translated and is now
 * handing back to Minecraft's renderer. Generic GUI hooks must not translate
 * that Component a second time merely because a screen is open.
 */
public final class InternalRenderGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private InternalRenderGuard() {
    }

    public static boolean active() {
        return DEPTH.get() > 0;
    }

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int next = DEPTH.get() - 1;
        if (next <= 0) DEPTH.remove();
        else DEPTH.set(next);
    }

    public static void run(Runnable action) {
        enter();
        try {
            action.run();
        } finally {
            exit();
        }
    }

    public static <T> T call(Supplier<T> action) {
        enter();
        try {
            return action.get();
        } finally {
            exit();
        }
    }
}
