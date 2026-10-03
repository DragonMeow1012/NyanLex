package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A run snapshots the selected sources and shares the existing bounded scanner/driver. */
public final class ContentWarmupSource implements ItemWarmupSource {
    private final ItemWarmupSource items;
    private final ItemWarmupSource screen;
    private final Supplier<TranslatorConfig> config;
    private List<ItemWarmupSource> selected;
    private int cursor;

    public ContentWarmupSource(ItemWarmupSource items, ItemWarmupSource screen,
                               Supplier<TranslatorConfig> config) {
        this.items = items;
        this.screen = screen;
        this.config = config;
    }

    public void reset() {
        selected = new ArrayList<>();
        if (config.get().warmupItems) selected.add(items);
        if (config.get().warmupScreenText) selected.add(screen);
        for (ItemWarmupSource source : selected) source.reset();
        cursor = 0;
    }

    private void advance() {
        if (selected == null) reset();
        while (cursor < selected.size() && selected.get(cursor).isExhausted()) cursor++;
    }

    public int totalItemCount() {
        if (selected == null) reset();
        return selected.stream().mapToInt(ItemWarmupSource::totalItemCount).sum();
    }

    public boolean isAvailable() {
        advance();
        return selected.stream().allMatch(ItemWarmupSource::isAvailable);
    }

    public boolean isExhausted() {
        advance();
        return cursor == selected.size();
    }

    public List<ItemWarmupTarget> probeNext(int maxItems) {
        advance();
        return cursor == selected.size() ? List.of() : selected.get(cursor).probeNext(maxItems);
    }
}
