package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.service.OriginalLanguageSwitch;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The "show original" key makes the game resolve translation keys with its English table. These
 * tests use an inline fake table type (a plain map) in place of the game's language class, and a
 * tiny key lookup that mimics what the glue hooks do with {@code effective()}.
 */
class OriginalLanguageSwitchTest {

    private static Map<String, String> table(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    /** What a hooked key lookup does: the English table when the switch is active, else the game's own. */
    private static String resolve(OriginalLanguageSwitch<Map<String, String>> sw,
                                  Map<String, String> gameTable, String key) {
        Map<String, String> active = sw.effective();
        Map<String, String> use = active != null ? active : gameTable;
        return use.getOrDefault(key, key);
    }

    @Test
    void englishTableIsNeededOnlyWhenTheGameIsNotAlreadyInEnglish() {
        assertTrue(OriginalLanguageSwitch.needsEnglishTable("zh_tw"));
        assertTrue(OriginalLanguageSwitch.needsEnglishTable("ja_jp"));
        assertTrue(OriginalLanguageSwitch.needsEnglishTable("en_gb"));
        assertFalse(OriginalLanguageSwitch.needsEnglishTable("en_us"));
        assertFalse(OriginalLanguageSwitch.needsEnglishTable("EN_US"));
        assertFalse(OriginalLanguageSwitch.needsEnglishTable(" en_us "));
        assertFalse(OriginalLanguageSwitch.needsEnglishTable(null));
        assertFalse(OriginalLanguageSwitch.needsEnglishTable(""));
        assertFalse(OriginalLanguageSwitch.needsEnglishTable("   "));
    }

    @Test
    void doesNothingUntilTheKeyIsOn() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        Map<String, String> game = table("menu.quit", "離開遊戲");
        sw.setTable(table("menu.quit", "Quit Game"));
        assertNull(sw.effective());
        assertEquals("離開遊戲", resolve(sw, game, "menu.quit"));
    }

    @Test
    void keyOnResolvesWithEnglishAndKeyOffRestoresTheGameTable() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        Map<String, String> game = table("menu.quit", "離開遊戲", "mod.title", "設定");
        Map<String, String> english = table("menu.quit", "Quit Game", "mod.title", "Settings");
        sw.setTable(english);

        sw.setShowOriginal(true);
        assertSame(english, sw.effective());
        assertEquals("Quit Game", resolve(sw, game, "menu.quit"));
        assertEquals("Settings", resolve(sw, game, "mod.title"));

        sw.setShowOriginal(false);
        assertNull(sw.effective());
        assertEquals("離開遊戲", resolve(sw, game, "menu.quit"));
        assertEquals("設定", resolve(sw, game, "mod.title"));
    }

    @Test
    void noEnglishTableMeansTheKeyChangesNothingInTheLanguageLayer() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        Map<String, String> game = table("menu.quit", "Quit Game");
        assertFalse(sw.affectsDisplay());
        sw.setShowOriginal(true);
        assertNull(sw.effective());
        assertFalse(sw.affectsDisplay());
        assertEquals("Quit Game", resolve(sw, game, "menu.quit"));
    }

    @Test
    void reloadingResourcesWhileTheKeyIsOnSwapsInTheFreshTable() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        Map<String, String> first = table("a", "first");
        Map<String, String> second = table("a", "second");
        sw.setTable(first);
        sw.setShowOriginal(true);
        assertSame(first, sw.effective());

        sw.setTable(second);
        assertSame(second, sw.effective(), "a resource reload must not leave the stale table active");
        assertTrue(sw.affectsDisplay());
    }

    @Test
    void switchingTheGameToEnglishWhileTheKeyIsOnReturnsToTheGameTable() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        sw.setTable(table("a", "english"));
        sw.setShowOriginal(true);
        assertTrue(sw.effective() != null);

        sw.setTable(null);
        assertNull(sw.effective());
        assertFalse(sw.affectsDisplay());

        // Switching back to a non-English language later makes the key effective again at once.
        Map<String, String> again = table("a", "english again");
        sw.setTable(again);
        assertSame(again, sw.effective());
    }

    @Test
    void flippingRepeatedlyEndsWhereItStarted() {
        OriginalLanguageSwitch<Map<String, String>> sw = new OriginalLanguageSwitch<>();
        sw.setTable(table("a", "english"));
        for (int i = 0; i < 5; i++) {
            sw.setShowOriginal(true);
            assertTrue(sw.effective() != null);
            sw.setShowOriginal(false);
            assertNull(sw.effective());
        }
    }
}
