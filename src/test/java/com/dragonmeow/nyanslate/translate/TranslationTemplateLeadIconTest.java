package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R4: a unit-leading decorative icon is not part of the request key, and comes back in front. */
class TranslationTemplateLeadIconTest {

    private final TranslationTemplate templates = new TranslationTemplate();
    private static final String ICON = "";

    @Test
    void leadingIconIsTakenOutOfTheKeyAndRestoredInFront() {
        TranslationTemplate.Snapshot s = templates.prepare(ICON + " Requires Enderman Slayer 7.");
        assertEquals("Requires Enderman Slayer ⟦MT0⟧.", s.key());
        assertEquals(ICON + " ", s.leadIcon());
        assertEquals(ICON + " 需要終界殍手7。",
                s.restore("需要終界殍手 ⟦MT0⟧。"));
        assertTrue(s.changed());
    }

    @Test
    void sameSentenceWithDifferentIconOrNumberSharesOneKey() {
        String a = templates.prepare(ICON + " Requires Enderman Slayer 7.").key();
        String b = templates.prepare("⚔ Requires Enderman Slayer 9.").key();
        String c = templates.prepare("Requires Enderman Slayer 3.").key();
        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    void leadingIconInItsOwnColourPairMovesWholeAndLeavesTheRestBalanced() {
        TranslationTemplate.Snapshot s = templates.prepare(
                "⟦CS0⟧" + ICON + "⟦/CS0⟧ ⟦CS1⟧Requires Enderman Slayer⟦/CS1⟧");
        assertEquals("⟦CS1⟧Requires Enderman Slayer⟦/CS1⟧", s.key());
        assertEquals("⟦CS0⟧" + ICON + "⟦/CS0⟧ ⟦CS1⟧需要終界殍手⟦/CS1⟧",
                s.restore("⟦CS1⟧需要終界殍手⟦/CS1⟧"));
    }

    @Test
    void anIconInsideALargerColourRegionIsLeftAlone() {
        String source = "⟦CS0⟧" + ICON + " Requires Enderman Slayer now⟦/CS0⟧";
        TranslationTemplate.Snapshot s = templates.prepare(source);
        assertEquals("", s.leadIcon(), "an opened colour pair must not be split by the prefix");
    }

    @Test
    void shortStatRowsDividersAndMidSentenceIconsKeepTheirHistoricalKeys() {
        assertEquals("", templates.prepare("❁ Strength: +10").leadIcon(), "short stat row");
        assertEquals("", templates.prepare("━━━━ Inventory and more things").leadIcon(),
                "divider rule is not an icon");
        assertEquals("", templates.prepare("Requires Enderman Slayer " + ICON + " now").leadIcon());
        assertEquals("", templates.prepare(ICON + " 12345").leadIcon());
        assertEquals("", templates.prepare(ICON).leadIcon());
    }

    @Test
    void retokenizeOnlyAcceptsTextCarryingTheSamePrefix() {
        TranslationTemplate.Snapshot s = templates.prepare(ICON + " Requires Enderman Slayer 7.");
        assertNull(s.retokenize("no prefix 7"));
        assertEquals("需要 ⟦MT0⟧", s.retokenize(ICON + " 需要 7"));
    }
}
