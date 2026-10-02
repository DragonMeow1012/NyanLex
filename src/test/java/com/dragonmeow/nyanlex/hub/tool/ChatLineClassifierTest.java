package com.dragonmeow.nyanlex.hub.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every case here is a plain string literal; nothing touches a file or the network. */
class ChatLineClassifierTest {

    // ---- item/other preservation features: must always be KEPT ----

    @Test
    void rarityLineIsKept() {
        assertFalse(ChatLineClassifier.isLikelyChat("EPIC DUNGEON GLOVES"));
        assertFalse(ChatLineClassifier.isLikelyChat("a MYTHIC ACCESSORY a"));
    }

    @Test
    void enchantListIsKept() {
        assertFalse(ChatLineClassifier.isLikelyChat("Sharpness V, Protection IV, Unbreaking III"));
    }

    @Test
    void singleEnchantTokenIsKept() {
        assertFalse(ChatLineClassifier.isLikelyChat("Sharpness VII"));
        assertFalse(ChatLineClassifier.isLikelyChat("Growth 6"));
    }

    @Test
    void statLineIsKeptNotMisreadAsNameColonChat() {
        assertFalse(ChatLineClassifier.isLikelyChat("Strength: +10"));
        assertFalse(ChatLineClassifier.isLikelyChat("Crit Chance: +10%"));
        assertFalse(ChatLineClassifier.isLikelyChat("True Defense: 50"));
    }

    @Test
    void abilityLineIsKept() {
        assertFalse(ChatLineClassifier.isLikelyChat("Ability: Flame Breath"));
        assertFalse(ChatLineClassifier.isLikelyChat("Item Ability: Rain of Arrows RIGHT CLICK"));
    }

    @Test
    void itemDecorationGlyphIsKept() {
        assertFalse(ChatLineClassifier.isLikelyChat("Midas' Sword ✦✦✦✦✦"));
    }

    @Test
    void auctionHouseSellerBuyerBidderFieldIsKeptNotChat() {
        // 2026-10-01 user decision: AH tooltip info is item content, collected even though
        // it carries a protected placeholder the way chat does.
        assertFalse(ChatLineClassifier.isLikelyChat(
                "Works while in Accessory Bag! ⟦PB0⟧ a LEGENDARY ACCESSORY a ⟦PB1⟧ ⟦MT0⟧ ⟦PB2⟧"
                        + " Seller: ⟦0⟧ ⟦PB3⟧ Buy it now: ⟦MT1⟧ coins"));
        assertFalse(ChatLineClassifier.isLikelyChat("Buyer: ⟦MT2⟧ ⟦1⟧"));
        assertFalse(ChatLineClassifier.isLikelyChat("Bidder: [MVP+] ⟦0⟧"));
    }

    @Test
    void auctionHousePriceAndTimerLinesAreKeptNotChat() {
        assertFalse(ChatLineClassifier.isLikelyChat("Starting bid: ⟦MT0⟧ coins"));
        assertFalse(ChatLineClassifier.isLikelyChat("Top bid: ⟦MT3⟧ coins by ⟦0⟧"));
        assertFalse(ChatLineClassifier.isLikelyChat("Current bid: ⟦MT1⟧ coins"));
        assertFalse(ChatLineClassifier.isLikelyChat("Ends in: ⟦MT2⟧"));
        assertFalse(ChatLineClassifier.isLikelyChat("Time left: ⟦MT4⟧"));
    }

    @Test
    void genuineWhisperGuildPartyDialogueIsStillChatEvenNearAuctionVocabulary() {
        // "Buy it now"/"Seller" etc. only exempt a line when paired with a placeholder
        // immediately after the colon (the AH tooltip's structural fingerprint) — a real
        // chat line that merely mentions the auction house in prose is still chat.
        assertTrue(ChatLineClassifier.isLikelyChat("To Alex: see you at the auction house"));
        assertTrue(ChatLineClassifier.isLikelyChat("Guild > anyone selling a Hyperion?"));
        assertTrue(ChatLineClassifier.isLikelyChat("⟦0⟧: buy it now from the AH lol"));
    }

    @Test
    void plainItemNameWithNoSignalsIsKeptByDefault() {
        // No masked name, no rank/routing prefix, no banner shape: falls through to the
        // "no chat signal at all" default, which keeps it.
        assertFalse(ChatLineClassifier.isLikelyChat("Hyperion"));
        assertFalse(ChatLineClassifier.isLikelyChat("Midas' Sword"));
        assertFalse(ChatLineClassifier.isLikelyChat("Click to open"));
    }

    // ---- explicit chat shapes: must always be DROPPED ----

    @Test
    void rankPrefixIsChat() {
        assertTrue(ChatLineClassifier.isLikelyChat("[VIP] hello there"));
        assertTrue(ChatLineClassifier.isLikelyChat("[MVP++] gg everyone"));
    }

    @Test
    void whisperPrefixIsChat() {
        assertTrue(ChatLineClassifier.isLikelyChat("From Steve: hey, are you online?"));
        assertTrue(ChatLineClassifier.isLikelyChat("To Alex: see you at the auction house"));
    }

    @Test
    void channelPrefixIsChat() {
        assertTrue(ChatLineClassifier.isLikelyChat("Guild > anyone want to run dungeons?"));
        assertTrue(ChatLineClassifier.isLikelyChat("Party > pulling now"));
        assertTrue(ChatLineClassifier.isLikelyChat("Co-op > need an invite"));
    }

    @Test
    void maskedNameColonDialogueIsChat() {
        assertTrue(ChatLineClassifier.isLikelyChat("⟦0⟧: hello everyone!"));
        assertTrue(ChatLineClassifier.isLikelyChat("[MVP+] ⟦0⟧: gg"));
    }

    @Test
    void longPbBannerAnnouncementIsChat() {
        String banner = "=====⟦PB0⟧GIVEAWAY TIME⟦PB1⟧type !join to enter"
                + "⟦PB2⟧winner picked in 5 minutes⟦PB3⟧good luck everyone"
                + "⟦PB4⟧=====";
        assertTrue(ChatLineClassifier.isLikelyChat(banner));
    }

    @Test
    void maskedNameWithUnrecognisedShapeFallsBackToChat() {
        // Doesn't match any clean shape (no leading colon, no rank/routing prefix), but
        // still carries a masked player name: the conservative default drops it.
        assertTrue(ChatLineClassifier.isLikelyChat("nice drop ⟦0⟧, congrats!"));
    }

    @Test
    void blankIsChat() {
        assertTrue(ChatLineClassifier.isLikelyChat(""));
        assertTrue(ChatLineClassifier.isLikelyChat("   "));
        assertTrue(ChatLineClassifier.isLikelyChat(null));
    }
}
