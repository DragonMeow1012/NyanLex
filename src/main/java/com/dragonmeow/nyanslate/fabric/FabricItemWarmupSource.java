package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.service.TranslationService;
import com.dragonmeow.nyanslate.warmup.ItemWarmupBackend;
import com.dragonmeow.nyanslate.warmup.ItemWarmupSource;
import com.dragonmeow.nyanslate.warmup.ItemWarmupTarget;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Loader glue for the all-item warm-up: enumerates {@code BuiltInRegistries.ITEM}
 * (default stacks only) and builds each item's tooltip translation units through the
 * same paragraph plan the hover path uses. Everything here runs on the client thread.
 */
final class FabricItemWarmupSource implements ItemWarmupSource {
    private List<Item> items;
    private int cursor;

    private List<Item> items() {
        if (items == null) reset();
        return items;
    }

    @Override
    public void reset() {
        List<Item> list = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR) list.add(item);
        }
        items = list;
        cursor = 0;
    }

    @Override
    public int totalItemCount() {
        return items().size();
    }

    @Override
    public boolean isAvailable() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.level != null && mc.player != null && mc.isSameThread();
    }

    @Override
    public boolean isExhausted() {
        return cursor >= items().size();
    }

    @Override
    public List<ItemWarmupTarget> probeNext(int maxItems) {
        Minecraft mc = Minecraft.getInstance();
        List<Item> all = items();
        List<ItemWarmupTarget> out = new ArrayList<>();
        if (mc == null || mc.level == null || mc.player == null) return out;
        int end = Math.min(all.size(), cursor + Math.max(1, maxItems));
        while (cursor < end) {
            out.add(NyanslateFabric.itemWarmupTarget(all.get(cursor), mc));
            cursor++;
        }
        return out;
    }

    /** Backend over the live {@link TranslationService}. */
    static final class Backend implements ItemWarmupBackend {
        private final BooleanSupplier rateLimited;

        Backend(BooleanSupplier rateLimited) {
            this.rateLimited = rateLimited;
        }

        @Override
        public boolean isAiEngine() {
            TranslationService s = NyanslateFabric.service();
            return s != null && s.isItemWarmupEngine();
        }

        @Override
        public boolean requestsEnabled() {
            var cfg = NyanslateFabric.config();
            return cfg != null && cfg.translationRequestsEnabled;
        }

        @Override
        public boolean isRateLimited() {
            return rateLimited.getAsBoolean();
        }

        @Override
        public boolean isReady(String source) {
            TranslationService s = NyanslateFabric.service();
            return s == null || s.isTooltipTranslationReady(source);
        }

        @Override
        public boolean isPending(String source) {
            TranslationService s = NyanslateFabric.service();
            return s != null && s.isTooltipTranslationPending(source);
        }

        @Override
        public void warm(List<String> sources) {
            TranslationService s = NyanslateFabric.service();
            if (s != null) s.warmTooltipBatchBackground(sources);
        }
    }
}
