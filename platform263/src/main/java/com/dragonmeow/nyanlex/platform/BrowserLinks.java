package com.dragonmeow.nyanlex.platform;

import com.mojang.blaze3d.Blaze3D;

/** Opens external links using the Minecraft platform implementation. */
public final class BrowserLinks {
    private BrowserLinks() {}

    public static void open(String url) {
        Blaze3D.openUri(java.net.URI.create(url));
    }
}
