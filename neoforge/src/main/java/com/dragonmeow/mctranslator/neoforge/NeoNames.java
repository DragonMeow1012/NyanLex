package com.dragonmeow.mctranslator.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects online player names (kept verbatim, never sent to the translator).
 * Cached with a short TTL so building the set isn't repeated per call on big servers.
 */
public final class NeoNames {

    private static final int MIN_NAME_LENGTH = 3;
    private static final long TTL_MS = 5000L;

    private static volatile Collection<String> cached = List.of();
    private static volatile long cachedAt = -TTL_MS;

    private NeoNames() {
    }

    public static Collection<String> current(boolean protect) {
        if (!protect) return List.of();
        long now = System.currentTimeMillis();
        if (now - cachedAt < TTL_MS) return cached;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) {
            cached = List.of();
            cachedAt = now;
            return cached;
        }
        Set<String> names = new HashSet<>();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().getName();
            if (name != null && name.length() >= MIN_NAME_LENGTH) names.add(name);
        }
        if (mc.player != null) {
            String self = mc.player.getGameProfile().getName();
            if (self != null && self.length() >= MIN_NAME_LENGTH) names.add(self);
        }
        cached = names;
        cachedAt = now;
        return names;
    }
}
