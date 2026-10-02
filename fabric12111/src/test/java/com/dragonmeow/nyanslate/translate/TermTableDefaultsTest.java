package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1.7: the shipped {@link TermTableDefaults} constants must stay in lock-step with
 * the user-approved {@code term-table-draft.json} (rarity + type sections). This test
 * cannot read the scratchpad draft file itself, so it pins a handful of the entries
 * the design review specifically called out (§1's 拍板 list) plus the total entry
 * counts, which change the moment a transcription drops or duplicates a row.
 */
class TermTableDefaultsTest {

    @Test
    void pinnedRarityTranslationsMatchTheApprovedDraft() {
        // EPIC must be 史詩, never 傳奇 (the whole point of P1.7 — draft.md §1: 14 of the
        // 33 real single-line EPIC keys in the cache sample were mistranslated as 傳奇).
        assertEquals("史詩", TermTableDefaults.RARITY.get("EPIC"));
        // LEGENDARY was the closest 拍板 call (80/20 split against 傳說); 傳奇 won.
        assertEquals("傳奇", TermTableDefaults.RARITY.get("LEGENDARY"));
        // UNCOMMON was the most contested entry (44% 罕見 vs 34% 稀有, a mistranslation
        // that collides with RARE); 罕見 won.
        assertEquals("罕見", TermTableDefaults.RARITY.get("UNCOMMON"));
        assertEquals("稀有", TermTableDefaults.RARITY.get("RARE"));
        assertEquals("神話", TermTableDefaults.RARITY.get("MYTHIC"));
    }

    @Test
    void dungeonTypeWordMatchesTheApprovedDraft() {
        assertEquals("地城", TermTableDefaults.TYPE.get("DUNGEON"));
        assertEquals("閃亮", TermTableDefaults.TYPE.get("SHINY"));
        assertEquals("寵物物品", TermTableDefaults.TYPE.get("PET ITEM"));
    }

    @Test
    void tablesHaveExactlyTheApprovedEntryCounts() {
        // term-table-draft.json: 12 rarity words, 55 type words/phrases. A change here
        // means an entry was added, removed or the draft was re-approved — update this
        // alongside a fresh read of term-table-draft.json, never on its own.
        assertEquals(12, TermTableDefaults.RARITY.size());
        assertEquals(55, TermTableDefaults.TYPE.size());
    }

    @Test
    void reforgeIsOutOfScopeForP17() {
        // The design explicitly excludes reforge prefixes from P1.7 ("reforge 這次不用，
        // 也不要放進程式"): neither map may contain a reforge-only word such as Ancient's
        // translation collision-avoidance spelling.
        assertNull(TermTableDefaults.RARITY.get("ANCIENT"));
        assertNull(TermTableDefaults.TYPE.get("ANCIENT"));
    }

    @Test
    void reforgeTableMatchesTheApprovedDraft() {
        // term-table-draft.json "reforge": 156 words; Withered/Spiritual were re-approved as
        // 枯萎/靈性 so they never double up with Wither/Spirit item names.
        assertEquals(156, TermTableDefaults.REFORGE.size());
        assertEquals("枯萎", TermTableDefaults.REFORGE.get("Withered"));
        assertEquals("靈性", TermTableDefaults.REFORGE.get("Spiritual"));
        assertEquals("混合", TermTableDefaults.REFORGE.get("Blended"));
        assertEquals("遠古", TermTableDefaults.REFORGE.get("Ancient"));
        assertEquals("傳說", TermTableDefaults.REFORGE.get("Fabled"));
        assertEquals("巨型", TermTableDefaults.REFORGE.get("Giant"));
        assertEquals("傑瑞的", TermTableDefaults.REFORGE.get("Jerry's"));
        assertEquals("油炸", TermTableDefaults.REFORGE.get("Deep Fried"));
        assertEquals("Blood-Soaked", TermTableDefaults.reforgeKey("blood-soaked"));
        assertNull(TermTableDefaults.reforgeKey("Enchanted"));
    }

    @Test
    void reforgeLookupIsCaseInsensitiveAndOverridesWin() {
        TermTable shipped = new TermTable(java.util.Map.of());
        assertEquals("混合", shipped.reforge("blended"));
        assertEquals("混合", shipped.reforge("BLENDED"));
        assertNull(shipped.reforge("Enchanted"), "never guessed");
        TermTable overridden = new TermTable(java.util.Map.of("blended", "融合", "ENCHANTED", "附魔"));
        assertEquals("融合", overridden.reforge("Blended"));
        assertNull(overridden.reforge("Enchanted"), "an override never adds a reforge word");
    }

    @Test
    void unknownWordsAreNull() {
        assertNull(TermTableDefaults.RARITY.get("NOT_A_RARITY"));
        assertNull(TermTableDefaults.TYPE.get("NOT_A_TYPE"));
    }
}
