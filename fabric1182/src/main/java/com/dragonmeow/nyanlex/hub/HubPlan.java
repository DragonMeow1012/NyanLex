package com.dragonmeow.nyanlex.hub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Result of {@link HubDownloader#plan}: everything the confirmation screen needs to
 * show before the player presses "confirm download".
 */
public final class HubPlan {
    private final List<HubPlanItem> items;

    public HubPlan(List<HubPlanItem> items) {
        this.items = List.copyOf(items);
    }

    public static HubPlan empty() {
        return new HubPlan(List.of());
    }

    public List<HubPlanItem> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** Whether at least one item has repository content at all (whether or not it is
     *  already up to date) — the "repository has nothing for you" empty state is only
     *  shown when this is {@code false}. */
    public boolean hasAnyContent() {
        return items.stream().anyMatch(HubPlanItem::hasContent);
    }

    /** Sum of {@link HubPlanItem#bytes()} over items that have content and are not
     *  already up to date — the "共 N MB" total the confirmation screen shows. */
    public long totalDownloadBytes() {
        long total = 0L;
        for (HubPlanItem item : items) {
            if (item.hasContent() && !item.upToDate()) total += item.bytes();
        }
        return total;
    }

    /** Items this plan will actually fetch, in download/merge priority order (server,
     *  then modpack, then mods — see {@link HubSource#priority()}), so merging them in
     *  this order alone is enough to realize that priority in {@link HubLocalCache}. */
    public List<HubPlanItem> downloadable() {
        List<HubPlanItem> result = new ArrayList<>();
        for (HubPlanItem item : items) {
            if (item.hasContent() && !item.upToDate()) result.add(item);
        }
        result.sort(Comparator.comparingInt(item -> item.source().priority()));
        return Collections.unmodifiableList(result);
    }
}
