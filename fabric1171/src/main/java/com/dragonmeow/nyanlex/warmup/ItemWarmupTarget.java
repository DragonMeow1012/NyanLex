package com.dragonmeow.nyanlex.warmup;

import java.util.List;

/**
 * One registered item as seen by the warm-up: its registry id, the namespace (mod id)
 * and the tooltip translation units ("paragraph sources", the item's own title riding
 * along as the first non-blank entry) exactly as the hover path would submit them.
 */
public record ItemWarmupTarget(String itemId, String namespace, List<String> sources) {
    public ItemWarmupTarget {
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (itemId == null) itemId = "";
        if (namespace == null) namespace = "";
    }
}
