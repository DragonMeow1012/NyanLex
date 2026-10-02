package com.dragonmeow.nyanslate.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubPathsTest {

    @Test
    void indexPathIsFixed() {
        assertEquals("index.json", HubPaths.indexPath());
    }

    @Test
    void serverPathNormalizesLanguage() {
        assertEquals("servers/hypixel.net/zh-tw.json", HubPaths.serverPath("hypixel.net", "zh_TW"));
    }

    @Test
    void modpackPathNormalizesLanguage() {
        assertEquals("modpacks/my-pack/en-us.json", HubPaths.modpackPath("my-pack", "en_us"));
    }

    @Test
    void modPathNormalizesLanguage() {
        assertEquals("mods/somemod/zh-cn.json", HubPaths.modPath("somemod", "zh-CN"));
    }

    @Test
    void localCacheAndStateFileNamesShareOnePrefixConstant() {
        assertTrue(HubPaths.hubCacheFileName("zh-TW").startsWith(HubPaths.FILE_PREFIX));
        assertTrue(HubPaths.stateFileName().startsWith(HubPaths.FILE_PREFIX));
        assertEquals("nyanslate-hub-cache-zh-tw.json", HubPaths.hubCacheFileName("zh_TW"));
        assertEquals("nyanslate-hub-state.json", HubPaths.stateFileName());
    }

    @Test
    void defaultBaseUrlPointsAtTranslationHubFolder() {
        assertTrue(HubPaths.DEFAULT_BASE_URL.endsWith("/translation-hub"));
    }

    @Test
    void perFileCapsMatchTheRaised2026_10_01Limits() {
        // Raised twice the same day: 40,000/3 MiB -> 100,000/16 MiB -> 100,000/32 MiB
        // (the second raise so the hypixel.net seed's 54,281 kept rows fit in one file).
        assertEquals(32L * 1024 * 1024, HubPaths.MAX_FILE_BYTES);
        assertEquals(100_000, HubPaths.MAX_FILE_ROWS);
    }

    @Test
    void downloadResponseByteCapHasHeadroomAboveThePerFileByteCap() {
        assertTrue(HubPaths.downloadResponseByteCap() > HubPaths.MAX_FILE_BYTES,
                "the transport cap must never truncate a file sitting exactly at the per-file cap");
    }

    // --- A3 (2026-10-01): HubPaths whitelists host/slug/modid again right before building a
    // path, even though each already has its own upstream sanitizer, so a caller that skips
    // that upstream step cannot smuggle a path-breaking character into the repository URL. ---

    @Test
    void serverPathRejectsAnEmbeddedSlash() {
        assertThrows(IllegalArgumentException.class, () -> HubPaths.serverPath("evil.com/x/y.ext", "zh-TW"));
        assertThrows(IllegalArgumentException.class, () -> HubPaths.serverPath("../evil.com", "zh-TW"));
        assertThrows(IllegalArgumentException.class, () -> HubPaths.serverPath(null, "zh-TW"));
    }

    @Test
    void modpackPathRejectsAnEmbeddedSlash() {
        assertThrows(IllegalArgumentException.class, () -> HubPaths.modpackPath("../etc/passwd", "zh-TW"));
        assertThrows(IllegalArgumentException.class, () -> HubPaths.modpackPath("", "zh-TW"));
    }

    @Test
    void modPathRejectsAnEmbeddedSlashButAllowsUnderscores() {
        assertThrows(IllegalArgumentException.class, () -> HubPaths.modPath("../x", "zh-TW"));
        // Mod ids legitimately contain underscores (e.g. "placeholder_api"); the whitelist
        // must not reject them the way the stricter host/slug one would.
        assertEquals("mods/placeholder_api/zh-tw.json", HubPaths.modPath("placeholder_api", "zh-TW"));
    }
}
