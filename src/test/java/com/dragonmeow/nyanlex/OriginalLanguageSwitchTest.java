package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dragonmeow.nyanlex.service.OriginalLanguageSwitch;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The "show original" key must leave the game's own language alone: a player who runs the game
 * in Chinese keeps a Chinese interface with the key on. These tests use an inline fake table
 * type (a plain map) in place of the game's language class, and a tiny key lookup that mimics
 * what the glue hooks do with {@code effective()}.
 */
class OriginalLanguageSwitchTest {

    private static Map<String, String> table(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    /** What a hooked key lookup does: the switch's table when it offers one, else the game's own. */
    private static String resolve(OriginalLanguageSwitch<Map<String, String>> sw,
                                  Map<String, String> gameTable, String key) {
        Map<String, String> active = sw.effective();
        Map<String, String> use = active != null ? active : gameTable;
        return use.getOrDefault(key, key);
    }

    @Test
    void noEnglishTableIsEverRequested() {
        for (String code : new String[] {"zh_tw", "zh_cn", "ja_jp", "en_gb", "en_us", "EN_US", " en_us ", "", "   "}) {
            assertFalse(OriginalLanguageSwitch.needsEnglishTable(code), code);
        }
        assertFalse(OriginalLanguageSwitch.needsEnglishTable(null));
    }

    @Test
    void keyOnKeepsTheGameInItsOwnLanguage() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        Map<String, String> game = table("menu.quit", "離開遊戲", "mod.title", "設定");
        sw.setTable(table("menu.quit", "Quit Game", "mod.title", "Settings"));

        sw.setShowOriginal(true);
        assertNull(sw.effective());
        assertFalse(sw.affectsDisplay());
        assertEquals("離開遊戲", resolve(sw, game, "menu.quit"));
        assertEquals("設定", resolve(sw, game, "mod.title"));

        sw.setShowOriginal(false);
        assertNull(sw.effective());
        assertEquals("離開遊戲", resolve(sw, game, "menu.quit"));
    }

    @Test
    void aTableHandedOverWhileTheKeyIsOnIsStillNotUsed() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        Map<String, String> game = table("menu.quit", "離開遊戲");
        sw.setShowOriginal(true);
        sw.setTable(table("menu.quit", "Quit Game"));
        assertNull(sw.effective());
        assertFalse(sw.affectsDisplay());
        assertEquals("離開遊戲", resolve(sw, game, "menu.quit"));
    }

    @Test
    void flippingRepeatedlyNeverReachesTheLanguageLayer() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        sw.setTable(table("a", "english"));
        for (int i = 0; i < 5; i++) {
            sw.setShowOriginal(true);
            assertNull(sw.effective());
            sw.setShowOriginal(false);
            assertNull(sw.effective());
        }
        assertFalse(sw.affectsDisplay());
    }
}
