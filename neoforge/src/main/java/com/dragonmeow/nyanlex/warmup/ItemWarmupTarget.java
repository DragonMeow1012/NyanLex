package com.dragonmeow.nyanlex.warmup;

import java.util.List;

/**
 * One registered item as seen by the warm-up: its registry id, the namespace (mod id)
 * and the tooltip translation units ("paragraph sources", the item's own title riding
 * along as the first non-blank entry) exactly as the hover path would submit them.
 *
 * <p>{@code failed} marks an item whose tooltip could not be built at all (typically a mod
 * that needs world data and is probed from the title screen); it is skipped and counted,
 * and tried again by the next run.</p>
 */
public record ItemWarmupTarget(String itemId, String namespace, List<String> sources, boolean failed) {
    public ItemWarmupTarget(String itemId, String namespace, List<String> sources) {
        this(itemId, namespace, sources, false);
    }

    /** An item whose tooltip could not be probed. */
    public static ItemWarmupTarget failed(String itemId, String namespace) {
        return new ItemWarmupTarget(itemId, namespace, List.of(), true);
    }

    public ItemWarmupTarget {
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (itemId == null) itemId = "";
        if (namespace == null) namespace = "";
    }
}
