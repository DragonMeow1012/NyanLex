package com.dragonmeow.nyanlex.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dragonmeow.nyanlex.translate.OverlayDialogue;
import com.dragonmeow.nyanlex.translate.OverlayLineWrap;
import com.dragonmeow.nyanlex.translate.OverlaySpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The real glue styler (project colour markers, rebuild of the translated text) on real
 * Minecraft components: a coloured phrase in the middle of the spoken text, spread over rows
 * that each use their own row font.
 */
class OverlayDialogueStylerTest {

    private static final String ROW = "minecraft:hud/dialogue/text/standard/body_";
    private static final Style AQUA = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));

    /** A font by id: 1.21.11 styles carry a {@code FontDescription}, not a bare resource id. */
    private static FontDescription font(String id) {
        return new FontDescription.Resource(Identifier.parse(id));
    }

    private final List<OverlayDialogue.Run> runs = new ArrayList<>();
    private final List<Style> styles = new ArrayList<>();

    private void run(int row, String text, Style style) {
        runs.add(new OverlayDialogue.Run(text, ROW + row));
        styles.add(style.withFont(font(ROW + row)));
    }

    /** "...look around | for a way across. | I'll keep looking here." with the middle run coloured. */
    private void spokenText() {
        run(0, OverlaySpace.move(-92), Style.EMPTY);
        run(0, "Both of you look around ", Style.EMPTY);
        run(0, "for a way across.", AQUA);
        run(1, OverlaySpace.move(-120), Style.EMPTY);
        run(1, " I'll keep looking here.", Style.EMPTY);
        run(0, OverlaySpace.move(30), Style.EMPTY);
    }

    @Test
    void theColouredPhraseGetsItsOwnMarkersAndTheRowFontsDoNotSplitTheText() {
        spokenText();
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        String plain = analysis.sources().get("body");
        assertEquals("Both of you look around for a way across. I'll keep looking here.", plain);

        OverlayDialogueView.Styler styler = new OverlayDialogueView.Styler(styles);
        String key = styler.key(analysis.bodyPieces());
        assertTrue(key.contains("⟦CS1⟧for a way across.⟦/CS1⟧"), key);
        // the plain words around the phrase are separate marked runs, not one marker per row font
        assertFalse(key.contains("CS3"), key);
    }

    @Test
    void aSingleColourTextKeepsThePlainKey() {
        run(0, OverlaySpace.move(-92), Style.EMPTY);
        run(0, "Both of you look around.", Style.EMPTY);
        run(1, OverlaySpace.move(-100), Style.EMPTY);
        run(1, "I'll keep looking here.", Style.EMPTY);
        run(0, OverlaySpace.move(30), Style.EMPTY);
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        OverlayDialogueView.Styler styler = new OverlayDialogueView.Styler(styles);
        assertEquals("Both of you look around. I'll keep looking here.", styler.key(analysis.bodyPieces()));
        List<OverlayDialogue.Segment> segments = styler.segments("你們兩個四處看看。我會繼續在這裡找。");
        assertNotNull(segments);
        assertEquals(1, segments.size());
    }

    @Test
    void theTranslationColoursTheMatchingCharactersAcrossRows() {
        spokenText();
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        OverlayDialogueView.Styler styler = new OverlayDialogueView.Styler(styles);
        styler.key(analysis.bodyPieces());
        String translated = "⟦CS0⟧你們兩個人一起四處看看⟦/CS0⟧ "
                + "⟦CS1⟧找出過河的路。⟦/CS1⟧ ⟦CS2⟧我會繼續在這裡找。⟦/CS2⟧";
        List<OverlayDialogue.Segment> segments = styler.segments(translated);
        assertNotNull(segments);
        StringBuilder coloured = new StringBuilder();
        StringBuilder other = new StringBuilder();
        for (OverlayDialogue.Segment segment : segments) {
            Style style = styles.get(segment.styleSource());
            boolean aqua = style.getColor() != null && style.getColor().getValue() == 0x55FFFF;
            (aqua ? coloured : other).append(segment.text());
        }
        // the space that follows a marked phrase takes that phrase's style (existing rebuild behaviour)
        assertEquals("找出過河的路。", coloured.toString().strip());
        assertTrue(other.toString().contains("你們兩個人一起四處看看"));
        assertTrue(other.toString().contains("我會繼續在這裡找。"));

        // through the core: the phrase keeps its colour even where a row break falls inside it
        OverlayDialogue.Wrapper wrap = (font, style, text, limit) -> OverlayLineWrap.wrap(text, 80, cp -> 9);
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(analysis, Map.of(), segments,
                (font, style, text) -> text.codePointCount(0, text.length()), 80, wrap);
        assertNotNull(out);
        int aquaChars = 0;
        for (OverlayDialogue.OutRun run : out) {
            Style style = styles.get(run.styleSource());
            if (run.font() != null && style.getColor() != null && style.getColor().getValue() == 0x55FFFF) {
                aquaChars += (int) run.text().codePoints().filter(cp -> !OverlaySpace.isMove(cp) && !Character.isWhitespace(cp)).count();
            }
        }
        assertEquals("找出過河的路。".length(), aquaChars);
        // the coloured phrase is cut by a row break: both halves are coloured
        int aquaRuns = 0;
        for (OverlayDialogue.OutRun run : out) {
            Style style = styles.get(run.styleSource());
            if (run.font() != null && style.getColor() != null && style.getColor().getValue() == 0x55FFFF
                    && run.text().codePoints().anyMatch(cp -> !OverlaySpace.isMove(cp) && !Character.isWhitespace(cp))) {
                aquaRuns++;
            }
        }
        assertEquals(2, aquaRuns);
    }

    @Test
    void aResponseThatLostItsMarkersIsNotUsed() {
        spokenText();
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        OverlayDialogueView.Styler styler = new OverlayDialogueView.Styler(styles);
        styler.key(analysis.bodyPieces());
        assertNull(styler.segments("你們兩個四處看看，找出過河的路。我會繼續在這裡找。"));
    }
}
