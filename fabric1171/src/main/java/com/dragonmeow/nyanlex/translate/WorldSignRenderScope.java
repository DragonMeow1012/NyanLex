package com.dragonmeow.nyanlex.translate;

/**
 * Marks the synchronous renderer call in which a world sign may expose complete translated rows.
 * Without this scope the sign mixin falls through to vanilla, so a missing or disabled renderer
 * adapter can never leave an over-wide translation on screen.
 */
public final class WorldSignRenderScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private WorldSignRenderScope() {
    }

    public static void begin() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static boolean active() {
        return ACTIVE.get() == Boolean.TRUE;
    }

    public static void end() {
        ACTIVE.remove();
    }

    /** Scale needed to keep a complete row inside vanilla's available sign width. */
    public static float fitScale(int availableWidth, int projectedWidth) {
        if (availableWidth <= 0 || projectedWidth <= availableWidth) return 1.0F;
        return Math.max(0.01F, (float) availableWidth / (float) projectedWidth);
    }
}
