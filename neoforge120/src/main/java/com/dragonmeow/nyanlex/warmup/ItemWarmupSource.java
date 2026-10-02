package com.dragonmeow.nyanlex.warmup;

import java.util.List;

/**
 * Loader glue port: enumerates the item registry and probes tooltips. Every method is
 * called on the client thread only (tooltips must be built there), a few items per tick.
 */
public interface ItemWarmupSource {
    /** Number of registered items the source will enumerate. */
    int totalItemCount();

    /** Whether tooltips can currently be probed (the item registry is ready; a world is not required). */
    boolean isAvailable();

    /** Probe up to {@code maxItems} further items; empty once {@link #isExhausted()}. */
    List<ItemWarmupTarget> probeNext(int maxItems);

    boolean isExhausted();

    /** Rewind the cursor to the first item. */
    void reset();
}
