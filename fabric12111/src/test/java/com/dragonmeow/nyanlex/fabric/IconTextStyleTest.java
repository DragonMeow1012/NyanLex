package com.dragonmeow.nyanlex.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.translate.TextFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Icons and decoration must come out of every rebuild in the font and style they had, whichever
 * way the translation was coloured. Real components, the real rebuild code.
 */
class IconTextStyleTest {

    private static final FontDescription ICONS = font("minecraft:custom/icons");
    private static final FontDescription BADGES = font("minecraft:custom/badges");
    private static final Style YELLOW = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
    private static final Style GRAY = Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA));
    private static final Style AQUA = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
    private static final Style GREEN_ICON = Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55)).withFont(ICONS);
    private static final Style PLAIN_ICON = Style.EMPTY.withFont(ICONS);

    /** A font by id: 1.21.11 styles carry a {@code FontDescription}, not a bare resource id. */
    private static FontDescription font(String id) {
        return new FontDescription.Resource(Identifier.parse(id));
    }

    private record Run(String text, Style style) {
    }

    private static List<Run> runs(Component component) {
        List<Run> out = new ArrayList<>();
        component.visit((style, text) -> {
            if (!text.isEmpty()) out.add(new Run(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** The style of the run that holds {@code text}. */
    private static Style styleOf(Component component, String text) {
        for (Run run : runs(component)) {
            if (run.text().contains(text)) return run.style();
        }
        throw new AssertionError("no run with " + text + " in " + runs(component));
    }

    private static MutableComponent helmet() {
        return Component.empty()
                .append(Component.literal("").setStyle(PLAIN_ICON))
                .append(Component.literal(" Unidentified Helmet").setStyle(YELLOW));
    }

    @Test
    void aLeadingIconKeepsItsFontWhenTheWholeLineTakesTheDominantStyle() {
        // the marker-less fallback paints the whole translation in the style of the words
        Component out = FabricTextStyle.styledChatContent(helmet(), 0, "未鑑定的頭盔");
        assertEquals("未鑑定的頭盔", out.getString());
        assertEquals(ICONS, styleOf(out, "").getFont());
        Style words = styleOf(out, "未鑑定");
        assertEquals(YELLOW.getColor(), words.getColor());
        assertNotEquals(ICONS, words.getFont());
    }

    @Test
    void aStyleFallbackTranslationOfAMarkedLineKeepsTheIconToo() {
        Component original = helmet();
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(original, 0);
        assertTrue(marked.marked());
        // what the cache hands over when the semantic wording is ready but this colour layout is not
        String fallback = TextFilter.markStyleFallback(" 未鑑定的頭盔");
        Component out = FabricTextStyle.rebuildRich(original, fallback, marked);
        assertEquals(ICONS, styleOf(out, "").getFont());
        assertEquals(YELLOW.getColor(), styleOf(out, "未鑑定").getColor());
    }

    @Test
    void anIconInTheMiddleOfAnAnchoredLineKeepsItsFontAndColour() {
        Component original = Component.empty()
                .append(Component.literal("This item's power has been sealed, an ").setStyle(GRAY))
                .append(Component.literal("").setStyle(GREEN_ICON))
                .append(Component.literal(" Item Identifier").setStyle(AQUA))
                .append(Component.literal(" can unlock its potential.").setStyle(GRAY));
        Component out = FabricTextStyle.styledAnchored(original, 0, "此物品的力量已被封印，物品鑑定師可以解放它的潛力。");
        Style icon = styleOf(out, "");
        assertEquals(ICONS, icon.getFont());
        assertEquals(GREEN_ICON.getColor(), icon.getColor());
    }

    @Test
    void anIconTheMarkersDroppedIntoAWordRegionIsStillPutBack() {
        Component original = Component.empty()
                .append(Component.literal("an ").setStyle(GRAY))
                .append(Component.literal("").setStyle(GREEN_ICON))
                .append(Component.literal(" Item Identifier").setStyle(AQUA));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(original, 0);
        assertTrue(marked.marked());
        // the translator moved the icon inside the coloured words' region
        String translated = "⟦CS0⟧一個⟦/CS0⟧ ⟦CS1⟧⟦/CS1⟧⟦CS2⟧物品鑑定師⟦/CS2⟧";
        Component out = FabricTextStyle.markedChat(original, 0, translated, marked);
        assertEquals(ICONS, styleOf(out, "").getFont());
        assertEquals(AQUA.getColor(), styleOf(out, "物品").getColor());
    }

    @Test
    void equalIconsArePairedInOrderOfAppearance() {
        Style first = Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)).withFont(ICONS);
        Style second = Style.EMPTY.withColor(TextColor.fromRgb(0x5555FF)).withFont(ICONS);
        Component original = Component.empty()
                .append(Component.literal("").setStyle(first))
                .append(Component.literal(" Life ").setStyle(GRAY))
                .append(Component.literal("").setStyle(second))
                .append(Component.literal(" Mana").setStyle(GRAY));
        Component out = FabricTextStyle.styledChatContent(original, 0, "生命 魔力");
        List<Run> icons = new ArrayList<>();
        for (Run run : runs(out)) {
            if (run.text().contains("")) icons.add(run);
        }
        assertEquals(2, icons.size());
        assertEquals(first.getColor(), icons.get(0).style().getColor());
        assertEquals(second.getColor(), icons.get(1).style().getColor());
    }

    @Test
    void aSeriesOfDifferentFontIconsEachGetsItsOwnFont() {
        Component original = Component.empty()
                .append(Component.literal("").setStyle(PLAIN_ICON))
                .append(Component.literal("").setStyle(Style.EMPTY.withFont(BADGES)))
                .append(Component.literal(" Unique Helmet").setStyle(YELLOW));
        Component out = FabricTextStyle.styledChatContent(original, 0, "獨特頭盔");
        assertEquals(ICONS, styleOf(out, "").getFont());
        assertEquals(BADGES, styleOf(out, "").getFont());
    }

    @Test
    void anIconTheTranslationDroppedIsLeftAlone() {
        Component out = FabricTextStyle.styledChatContent(helmet(), 0, "未鑑定的頭盔");
        assertEquals("未鑑定的頭盔", out.getString());
        assertEquals(YELLOW.getColor(), styleOf(out, "未鑑定").getColor());
    }

    @Test
    void aLayoutMoveBetweenAFigureAndItsLabelKeepsItsPlace() {
        String move = new String(Character.toChars(0xD0002));
        Style big = Style.EMPTY.withFont(font("minecraft:custom/big"));
        Style small = Style.EMPTY.withFont(font("minecraft:custom/small"));
        Component original = Component.empty()
                .append(Component.literal("1-5").setStyle(big))
                .append(Component.literal(move).setStyle(small))
                .append(Component.literal("Level Range").setStyle(small));
        Component out = FabricTextStyle.styledChatContent(original, 0, "1-5" + move + "等級範圍");
        assertEquals("1-5" + move + "等級範圍", out.getString());
        assertEquals(small.getFont(), styleOf(out, move).getFont());
    }

    @Test
    void aSpaceTheTranslationKeptIsNotDoubled() {
        Style a = Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555));
        Style b = Style.EMPTY.withColor(TextColor.fromRgb(0x5555FF));
        Component original = Component.empty()
                .append(Component.literal("Red").setStyle(a))
                .append(Component.literal(" Blue").setStyle(b));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(original, 0);
        String translated = "⟦CS0⟧紅⟦/CS0⟧ ⟦CS1⟧藍⟦/CS1⟧";
        assertEquals("紅 藍", FabricTextStyle.markedChat(original, 0, translated, marked).getString());
        assertNotNull(marked);
    }
}
