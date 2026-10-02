package com.dragonmeow.nyanlex.hub.tool;

import java.util.regex.Pattern;

/**
 * Author-only export gate: a row whose source or translation names another client mod
 * (typically a chat message or prefix that a mod prints into the player's chat) is not
 * server content and must never enter the public translation repository. Matching is
 * case-insensitive on whole words so ordinary vocabulary is not hit.
 */
final class ThirdPartyModFilter {
    private static final Pattern MODS = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:SkyHanni|Skyblocker|Skytils|NotEnoughUpdates|Not Enough Updates"
                    + "|SkyblockAddons|Skyblock Addons|Firmament|SkyOcean|SoopyV2|Soopy|BetterMap"
                    + "|Cowlection|Hytils|Patcher|OneConfig|Taunahi|Danker'?s? Skyblock Mod"
                    + "|Hypixel Plus|Nyanlex|Nyanslate|MCTranslator|MinecraftTranslator)"
                    + "(?![A-Za-z0-9])|\\[NEU\\]");

    private ThirdPartyModFilter() {}

    /** True when {@code text} mentions a known third-party mod name. */
    static boolean mentionsMod(String text) {
        return text != null && !text.isEmpty() && MODS.matcher(text).find();
    }
}
