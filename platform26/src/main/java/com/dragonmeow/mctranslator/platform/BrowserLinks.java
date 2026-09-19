package com.dragonmeow.mctranslator.platform;

import net.minecraft.util.Util;

/** Opens external links using the Minecraft platform implementation. */
public final class BrowserLinks {
    private BrowserLinks() {}

    public static void open(String url) {
        Util.getPlatform().openUri(url);
    }
}
