package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.2 strong-frame player-name detection. Every input is an inline string shaped like
 * the real Hypixel text (names anonymised); no cache file or network is read.
 */
class PlayerNamePatternsTest {

    /** Names isolated by the shipped frames, in text order. */
    private static List<String> names(String text) {
        List<String> out = new ArrayList<>();
        for (int[] span : PlayerNamePatterns.nameSpans(text)) {
            out.add(text.substring(span[0], span[1]));
        }
        return out;
    }

    private static List<String> frameIds(String text) {
        List<String> sink = new ArrayList<>();
        PlayerNamePatterns.computeForDiagnostics(text, sink);
        List<String> ids = new ArrayList<>();
        for (String hit : sink) ids.add(hit.substring(0, hit.indexOf('\t')));
        return ids;
    }

    // ---- positives: one per shipped frame ----

    @Test
    void everyShippedFrameIsolatesItsName() {
        assertEquals(List.of("Seller_42"), names("Seller: [MVP+] Seller_42"));
        assertEquals(List.of("Buyer9"), names("Buyer: [VIP] Buyer9"));
        assertEquals(List.of("bidderx"), names("Bidder: bidderx"));
        assertEquals(List.of("Rival_1"), names("Top bid: 1,250,000 coins by [MVP++] Rival_1"));
        assertEquals(List.of("Lobby_Guy"), names("[MVP+] Lobby_Guy joined the lobby!"));
        assertEquals(List.of("GoldGuy"), names(">>> [MVP++] GoldGuy joined the lobby! <<<"));
        assertEquals(List.of("zLimm"),
                names("[Mod] You passed zLimm in the Nether Wart Collection Leaderboard!"));
        assertEquals(List.of("Flixy1"), names("1,234 behind Flixy1"));
        assertEquals(List.of("Flixy1"), names("12.5k behind Flixy1 [#12]"));
        assertEquals(List.of("D1fre"), names("Item sold to [MVP+] D1fre for a marvelous 5,000 coins, gg!"));
        assertEquals(List.of("Its_Lunith"), names(
                "You collected 2,000 coins from selling White Gift Talisman to Its_Lunith in an auction!"));
        assertEquals(List.of("LOC_8"), names("You claimed Day Crystal from [MVP+] LOC_8's auction!"));
        assertEquals(List.of("_WildRage"), names("[Auction] _WildRage bought Fabled Sword for 9 coins CLICK"));
        assertEquals(List.of("Paifan"), names("[MVP+] Paifan has left the party."));
        assertEquals(List.of("MrRmro"), names("MrRmro joined the party."));
        assertEquals(List.of("Srednet"), names("[MVP+] Srednet has disbanded the party!"));
        assertEquals(List.of("Tril3"), names("[VIP+] Tril3 has invited you to join their party!"));
        assertEquals(List.of("Not_Ashton", "areyn_"),
                names("The party was transferred to [VIP+] Not_Ashton because [MVP+] areyn_ left"));
        assertEquals(List.of("KiryuMiya", "crazywolfdog"),
                names("The party was transferred to [MVP+] KiryuMiya by [VIP] crazywolfdog"));
        assertEquals(List.of("Bok5"), names("Bok5 joined the dungeon group! (Archer Level 30)"));
        assertEquals(List.of("0cculte"), names("0cculte's Party"));
    }

    @Test
    void shippedFrameIdsAreTheVersionedTable() {
        assertEquals("names-v1", PlayerNamePatterns.VERSION);
        assertEquals(List.of("ah.field"), frameIds("Seller: [MVP+] Seller_42"));
        assertEquals(List.of("lobby.join"), frameIds("[MVP+] Lobby_Guy joined the lobby!"));
        assertEquals(List.of("leaderboard.passed"),
                frameIds("You passed zLimm in the Nether Wart Collection Leaderboard!"));
        assertEquals(List.of("leaderboard.behind"), frameIds("1,234 behind Flixy1"));
    }

    @Test
    void rankSplitByColourRunsIsReadFromTheMarkerFreeProjection() {
        // "[MVP+]" drawn in three colour runs: RANK_TAG cannot see it, the frame can.
        String cs = "⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧[MVP⟦/CS1⟧⟦CS2⟧+⟦/CS2⟧⟦CS1⟧] Bob_7⟦/CS1⟧";
        assertEquals(List.of("Bob_7"), names(cs));
        int[] span = PlayerNamePatterns.nameSpans(cs).get(0);
        assertEquals("Bob_7", cs.substring(span[0], span[1]), "position maps back to the raw text");

        String lobby = "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧+⟦/CS1⟧⟦CS2⟧] CrispHalo8⟦/CS2⟧ ⟦CS3⟧joined the lobby!⟦/CS3⟧";
        assertEquals(List.of("CrispHalo8"), names(lobby));
    }

    @Test
    void sectionFormatCodesAreInvisibleToTheFrame() {
        String legacy = "§7Seller: §b[MVP§c+§b] §bSeller_42";
        assertEquals(List.of("Seller_42"), names(legacy));
        assertEquals(List.of("DragonMeow1013"),
                names("§b[MVP§c+§b] DragonMeow1013 §6joined the lobby!"));
    }

    @Test
    void lineAnchoredFramesMatchOnEveryParagraphLine() {
        String tooltip = "This item can be reforged! ⟦PB0⟧ LEGENDARY SWORD ⟦PB1⟧ 1,000 "
                + "⟦PB2⟧ Seller: [MVP+] cubeslayer21 ⟦PB3⟧ Buy it now: 1,000,000 coins";
        assertEquals(List.of("cubeslayer21"), names(tooltip));
        assertEquals(List.of("rnvz"), names("Top bid: 5 coins\nBidder: [MVP+] rnvz"));
        assertEquals(List.of("zLimm"), names("1,234 behind zLimm\r\nsomething else"));
    }

    @Test
    void storedKeysWithTemplatedRankAndValuesAreRecognisedToo() {
        // P1.3 applies the frames to STORED keys: ⟦MTn⟧ rank/number slots, ⟦WSn⟧ gaps.
        assertEquals(List.of("KENTdaddy"), names(
                "RARE ACCESSORY ⟦PB1⟧ ⟦MT0⟧ ⟦PB2⟧ Seller: ⟦MT1⟧ KENTdaddy ⟦PB3⟧ Buyer: ⟦MT2⟧ ⟦0⟧"));
        assertEquals(List.of("luv86"), names("⟦MT0⟧ behind luv86 [#⟦MT1⟧]"));
        assertEquals(List.of("Seller_42"), names("⟦WS0⟧ Seller: ⟦MT1⟧ Seller_42 ⟦WS1⟧"));
        assertEquals(List.of(), names("Seller: ⟦MT1⟧ ⟦0⟧"), "an already masked name is left alone");
    }

    // ---- negatives ----

    @Test
    void weakShapesStopWordsAndNonNamesAreNeverFrames() {
        assertEquals(List.of(), names("Dropped by Zombie"));
        assertEquals(List.of(), names("Dropped by Zombie Villager"));
        assertEquals(List.of(), names("From Bazaar: 64x Enchanted Sugar"));
        assertEquals(List.of(), names("From Colossal Experience Bottle Upgrade"));
        assertEquals(List.of(), names("[A] Healer_1 DEAD"));
        assertEquals(List.of(), names("Healer_1: hello there"));
        assertEquals(List.of(), names("I joined the lobby!"), "too short to be an account");
        assertEquals(List.of(), names("You joined the lobby!"), "stop word");
        assertEquals(List.of(), names("Seller: Unknown"), "stop word");
        assertEquals(List.of(), names("Seller: Refreshing..."), "the field must end after the name");
        assertEquals(List.of(), names("Seller: 12345"), "all digits is a number, not a name");
        assertEquals(List.of(), names("Missing: Healer"), "party-finder class rows are not shipped");
        assertEquals(List.of(), names("Teleport behind the enemy you are looking at,"));
        assertEquals(List.of(), names("- 3 [VIP+] behindONTHEBEAT 5 ago"));
        assertEquals(List.of(), names("Seller of the Month"));
    }

    @Test
    void aNameThatCrossesAStyleRunIsLeftAlone() {
        assertEquals(List.of(), names("⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧Bo⟦/CS1⟧⟦CS2⟧b_7⟦/CS2⟧"));
        assertEquals(List.of(), names("Seller: Bo§cb_7"));
        // A whole name in its own run is fine.
        assertEquals(List.of("Bob_7"), names("⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧Bob_7⟦/CS1⟧"));
    }

    // ---- cost controls ----

    @Test
    void textWithoutAnyPrefilterWordNeverReachesTheMemoOrARegex() {
        int before = PlayerNamePatterns.memoSize();
        String plain = "Grants +50 Strength and +20 Crit Damage while held in your hand";
        assertFalse(PlayerNamePatterns.mayContainFrame(plain));
        assertEquals(0, PlayerNamePatterns.spans(plain).length);
        assertTrue(PlayerNamePatterns.memoSize() <= before,
                "a prefiltered miss must not occupy a memo slot");
    }

    @Test
    void repeatedLookupsAreServedFromTheMemo() {
        String text = "Seller: [MVP+] MemoCheck_" + System.nanoTime() % 100000;
        int[] first = PlayerNamePatterns.spans(text);
        int[] second = PlayerNamePatterns.spans(new String(text.toCharArray()));
        assertSame(first, second, "an equal string is answered by the memoised spans");
        List<int[]> copy = PlayerNamePatterns.nameSpans(text);
        assertNotSame(first, copy.get(0), "the public view hands out copies");
    }

    @Test
    void memoStaysBoundedAndCorrectAcrossGenerationRotation() {
        for (int i = 0; i < 5_000; i++) {
            String name = "Rot_" + i;
            String text = "Seller: [VIP] " + name;
            assertEquals(List.of(name), names(text), text);
            assertTrue(PlayerNamePatterns.memoSize() <= 2 * 1024,
                    "two generations at most, got " + PlayerNamePatterns.memoSize());
        }
        // An evicted early entry is recomputed, not answered wrongly.
        assertEquals(List.of("Rot_0"), names("Seller: [VIP] Rot_0"));
        assertEquals(List.of("Rot_4999"), names("Seller: [VIP] Rot_4999"));
    }
}
