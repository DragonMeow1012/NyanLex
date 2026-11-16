package com.dragonmeow.nyanlex.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.translate.OverlayDialogue;
import com.dragonmeow.nyanlex.translate.OverlaySpace;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** An icon inside the spoken text, through the real colour-marker and rebuild code. */
class OverlayDialogueIconStylerTest {

    private static final String ROW = "minecraft:hud/dialogue/text/standard/body_";
    private static final String ICON_FONT = "minecraft:custom/icons";
    private static final String ICON = "";

    /** A font by id: 1.21.11 styles carry a {@code FontDescription}, not a bare resource id. */
    private static FontDescription font(String id) {
        return new FontDescription.Resource(Identifier.parse(id));
    }

    private final List<OverlayDialogue.Run> runs = new ArrayList<>();
    private final List<Style> styles = new ArrayList<>();

    private void run(String font, String text, Style style) {
        runs.add(new OverlayDialogue.Run(text, font));
        styles.add(style.withFont(font(font)));
    }

    private void page() {
        Style purple = Style.EMPTY.withColor(TextColor.fromRgb(0xAA00AA));
        run(ROW + 0, OverlaySpace.move(-92), Style.EMPTY);
        run(ROW + 0, "Go to the ", Style.EMPTY);
        run(ROW + 0, OverlaySpace.move(2), Style.EMPTY);
        run(ICON_FONT, ICON, Style.EMPTY);
        run(ICON_FONT, OverlaySpace.move(2), Style.EMPTY);
        run(ROW + 0, " shop", purple);
        run(ROW + 1, OverlaySpace.move(-80), Style.EMPTY);
        run(ROW + 1, "and come back.", Style.EMPTY);
        run(ROW + 0, OverlaySpace.move(-20), Style.EMPTY);
    }

    @Test
    void theIconKeepsItsFontInTheKeyAndInTheRebuiltTranslation() {
        page();
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        assertTrue(analysis.sources().containsKey("body"));
        OverlayDialogueView.Styler styler = new OverlayDialogueView.Styler(styles);
        String key = styler.key(analysis.bodyPieces());
        assertTrue(key.contains(ICON), key);
        // a translator keeps the markers around the icon and the phrases
        String translated = key.replace("Go to the", "請到").replace("shop", "商店")
                .replace("and come back.", "再回來。");
        List<OverlayDialogue.Segment> segments = styler.segments(translated);
        assertNotNull(segments, translated);
        Style iconStyle = null;
        for (OverlayDialogue.Segment segment : segments) {
            if (segment.text().contains(ICON)) {
                iconStyle = styles.get(segment.styleSource());
            }
        }
        assertNotNull(iconStyle, "the icon is in the translation");
        assertEquals(font(ICON_FONT), iconStyle.getFont());
        // the words around it are not in the icon's font
        for (OverlayDialogue.Segment segment : segments) {
            if (segment.text().contains("商店")) {
                assertNotEquals(font(ICON_FONT), styles.get(segment.styleSource()).getFont());
            }
        }
    }

    @Test
    void aTranslationWithoutMarkersNeverMovesTheIconIntoAWordFont() {
        page();
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        OverlayDialogueView.Styler styler = new OverlayDialogueView.Styler(styles);
        String key = styler.key(analysis.bodyPieces());
        String translated = key.replaceAll("⟦/?CS\\d+⟧", "").replace("Go to the", "請到")
                .replace("shop", "商店").replace("and come back.", "再回來。");
        List<OverlayDialogue.Segment> segments = styler.segments(translated);
        if (segments != null) {
            for (OverlayDialogue.Segment segment : segments) {
                if (segment.text().contains(ICON)) {
                    assertEquals(font(ICON_FONT), styles.get(segment.styleSource()).getFont());
                }
            }
        }
    }
}
