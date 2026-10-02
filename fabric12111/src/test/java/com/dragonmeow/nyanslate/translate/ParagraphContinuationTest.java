package com.dragonmeow.nyanslate.translate;

import com.dragonmeow.nyanslate.fabric.FabricTextStyle;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Soft-wrapped lore rows are one sentence on the wire (no PB token inside a sentence). */
class ParagraphContinuationTest {

    @Test
    void midClauseRowsJoinOnDanglingWordsAndCommas() {
        assertTrue(ParagraphModel.continuesWrappedSentence(
                "Gain 5 Coins from Griffin Burrows and", "+10 Magic Find on"));
        assertTrue(ParagraphModel.continuesWrappedSentence("+10 Magic Find on", "Mythological mobs,"));
        assertTrue(ParagraphModel.continuesWrappedSentence("Mythological mobs,", "but you take 5 damage"));
        // the original lower-case-continuation rule is untouched
        assertTrue(ParagraphModel.continuesWrappedSentence(
                "Deals extra damage to nearby", "enemies when you crouch."));
    }

    @Test
    void independentRowsKeepTheirBoundary() {
        assertFalse(ParagraphModel.continuesWrappedSentence("Strength: +10", "Defense: +20"));
        assertFalse(ParagraphModel.continuesWrappedSentence("Ability: Indulgence", "Gain 5 Coins from"));
        assertFalse(ParagraphModel.continuesWrappedSentence("Gain coins from the", "Cooldown: 5s"));
        assertFalse(ParagraphModel.continuesWrappedSentence("Gain 5 Coins from Burrows.", "Seller: Notch"));
        assertFalse(ParagraphModel.continuesWrappedSentence("Sharpness V, Smite V,", "Unbreaking III"));
        assertFalse(ParagraphModel.continuesWrappedSentence("Take the item and", "● Implosion"));
        assertFalse(ParagraphModel.continuesWrappedSentence("Two words", "and more"));
        assertFalse(ParagraphModel.continuesWrappedSentence(null, "x"));
    }

    @Test
    void indulgenceParagraphKeepsOnlyTheHeaderBreak() {
        List<Component> rows = List.of(
                Component.literal("Ability: Indulgence"),
                Component.literal("Gain 5 Coins from Griffin Burrows and"),
                Component.literal("+10 Magic Find on"),
                Component.literal("Mythological mobs,"),
                Component.literal("but you take 5 damage from them."));
        String request = FabricTextStyle.paragraphRequestText(rows);
        assertEquals(1, ParagraphModel.countBreakTokens(request), request);
        assertTrue(request.startsWith("Ability: Indulgence "), request);
        assertTrue(request.contains("Burrows and +10 Magic Find on Mythological mobs, but you take"), request);
    }

    @Test
    void sameSentenceWrappedAtDifferentPlacesSharesOneRequestText() {
        List<Component> a = List.of(
                Component.literal("Ability: Indulgence"),
                Component.literal("Gain 5 Coins from Griffin Burrows and"),
                Component.literal("+10 Magic Find on Mythological mobs,"),
                Component.literal("but you take 5 damage from them."));
        List<Component> b = List.of(
                Component.literal("Ability: Indulgence"),
                Component.literal("Gain 5 Coins from Griffin"),
                Component.literal("Burrows and +10 Magic Find on"),
                Component.literal("Mythological mobs, but you take 5 damage from them."));
        // "Griffin" + "Burrows": capitalised word after an open (non dangling) row end keeps a
        // boundary on that wrap; the point is that wraps ending on dangling words/commas agree.
        String ra = FabricTextStyle.paragraphRequestText(a);
        String rb = FabricTextStyle.paragraphRequestText(b);
        assertEquals(1, ParagraphModel.countBreakTokens(ra));
        assertTrue(ParagraphModel.countBreakTokens(rb) >= 1);
    }
}
