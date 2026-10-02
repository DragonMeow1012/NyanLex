package com.dragonmeow.nyanlex.fabric;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Text lookup for the core panels. On this Minecraft version a translation with a {@code %s} and no
 * argument silently drops the placeholder, but the core's card titles rely on seeing it ("聊天：%s" is
 * trimmed to "聊天"), so an argument-less lookup returns the raw template like newer versions do.
 */
final class LangText {
    private LangText() {}

    static String get(String key, Object... args) {
        if (args == null || args.length == 0) return Language.getInstance().getOrDefault(key);
        return new TranslatableComponent(key, args).getString();
    }
}
