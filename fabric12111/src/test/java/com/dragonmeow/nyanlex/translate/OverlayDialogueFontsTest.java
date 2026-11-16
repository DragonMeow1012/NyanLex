package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverlayDialogueFontsTest {

    @Test
    void classifiesTextFontsWithoutNamingStyles() {
        assertEquals(OverlayDialogueFonts.Kind.BODY, OverlayDialogueFonts.kind(OverlayFixture.BODY + 0));
        assertEquals(OverlayDialogueFonts.Kind.BODY,
                OverlayDialogueFonts.kind("minecraft:hud/dialogue/text/anyothername/body_4"));
        assertEquals(OverlayDialogueFonts.Kind.NAMEPLATE, OverlayDialogueFonts.kind(OverlayFixture.NAME));
        assertEquals(OverlayDialogueFonts.Kind.CONTROL, OverlayDialogueFonts.kind(OverlayFixture.CONTROL));
        assertEquals(OverlayDialogueFonts.Kind.CHOICE,
                OverlayDialogueFonts.kind("minecraft:hud/dialogue/text/x/choice_3"));
    }

    @Test
    void artworkAndOutOfRangeRowsAreNotText() {
        assertEquals(OverlayDialogueFonts.Kind.OTHER, OverlayDialogueFonts.kind(OverlayFixture.BOX));
        assertEquals(OverlayDialogueFonts.Kind.OTHER, OverlayDialogueFonts.kind(OverlayFixture.PORTRAIT));
        assertEquals(OverlayDialogueFonts.Kind.OTHER, OverlayDialogueFonts.kind(OverlayFixture.HUD));
        assertEquals(OverlayDialogueFonts.Kind.OTHER,
                OverlayDialogueFonts.kind("minecraft:hud/dialogue/text/x/body_5"));
        assertEquals(OverlayDialogueFonts.Kind.OTHER,
                OverlayDialogueFonts.kind("minecraft:hud/dialogue/text/x/choice_4"));
        assertEquals(OverlayDialogueFonts.Kind.OTHER,
                OverlayDialogueFonts.kind("minecraft:hud/dialogue/text/x/y/body_0"));
        assertEquals(OverlayDialogueFonts.Kind.OTHER, OverlayDialogueFonts.kind(null));
        assertEquals(OverlayDialogueFonts.Kind.OTHER, OverlayDialogueFonts.kind("minecraft:default"));
    }

    @Test
    void dialogueMembershipCoversArtworkToo() {
        assertTrue(OverlayDialogueFonts.isDialogue(OverlayFixture.BOX));
        assertTrue(OverlayDialogueFonts.isDialogue(OverlayFixture.BODY + 2));
        assertFalse(OverlayDialogueFonts.isDialogue(OverlayFixture.HUD));
        assertFalse(OverlayDialogueFonts.isDialogue("minecraft:default"));
    }

    @Test
    void rowAndSiblingFont() {
        assertEquals(3, OverlayDialogueFonts.line(OverlayFixture.BODY + 3));
        assertEquals(-1, OverlayDialogueFonts.line(OverlayFixture.NAME));
        assertEquals(OverlayFixture.BODY + 4, OverlayDialogueFonts.bodyFont(OverlayFixture.BODY + 0, 4));
    }
}
