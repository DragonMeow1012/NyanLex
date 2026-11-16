package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.List;

/** Shared builders for the overlay dialogue tests: inline fonts, widths and frames. */
final class OverlayFixture {

    static final String BODY = "minecraft:hud/dialogue/text/standard/body_";
    static final String NAME = "minecraft:hud/dialogue/text/nameplate";
    static final String CONTROL = "minecraft:hud/dialogue/text/control";
    static final String BOX = "minecraft:hud/dialogue/style/default/box";
    static final String FRAME = "minecraft:hud/dialogue/style/default/frame";
    static final String PORTRAIT = "minecraft:hud/dialogue/portrait";
    static final String FADE = "minecraft:hud/dialogue/effect/fade";
    static final String HUD = "minecraft:hud/gameplay/default/bottom_middle";

    /** Advance of U+0020..U+007E in the pixel font of the dialogue box. */
    private static final int[] ASCII = {
            4, 2, 4, 6, 6, 6, 6, 2, 3, 3, 4, 6, 2, 5, 2, 4, 5, 4, 5, 5, 5, 5, 5, 5, 5, 5, 2, 2, 4, 5, 4, 5,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 4, 6, 5, 5, 6, 5, 5, 5, 5, 5, 5, 6, 5, 6, 6, 6, 6, 5, 3, 4, 3, 6, 6,
            3, 5, 5, 5, 5, 5, 5, 5, 5, 2, 5, 5, 3, 6, 5, 5, 5, 5, 5, 5, 4, 5, 5, 6, 6, 5, 5, 4, 2, 4, 8};

    private OverlayFixture() {
    }

    static OverlayDialogue.Run run(String font, String text) {
        return new OverlayDialogue.Run(text, font);
    }

    static String move(int px) {
        return OverlaySpace.move(px);
    }

    /** Measure with the real advances of the Latin font, 9 for CJK, 30 for the hint icon, 10 for artwork. */
    static final OverlayDialogue.Measure MEASURE = (font, style, text) -> {
        int w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (OverlaySpace.isMove(cp)) {
                w += OverlaySpace.advance(cp);
            } else if (cp >= 0x20 && cp <= 0x7E) {
                w += ASCII[cp - 0x20];
            } else if (OverlayLineWrap.isWide(cp)) {
                w += 9;
            } else if (font.endsWith("text/control") && (cp == 0xE000 || cp == 0xE001)) {
                w += 30;
            } else {
                w += 10;
            }
        }
        return w;
    };

    /** Total advance of a whole overlay, the quantity that must not change. */
    static int total(List<OverlayDialogue.Run> runs) {
        int w = 0;
        for (int i = 0; i < runs.size(); i++) {
            w += MEASURE.width(runs.get(i).font(), i, runs.get(i).text());
        }
        return w;
    }

    static List<OverlayDialogue.Run> toRuns(List<OverlayDialogue.OutRun> out, List<OverlayDialogue.Run> in) {
        List<OverlayDialogue.Run> runs = new ArrayList<>();
        for (OverlayDialogue.OutRun r : out) {
            String font = r.font() != null ? r.font() : in.get(r.styleSource()).font();
            runs.add(new OverlayDialogue.Run(r.text(), font));
        }
        return runs;
    }

    /**
     * A frame shaped like a real one: HUD artwork, fade, hint, box, text rows, name, portrait.
     * Every element is [move in][content][move out] and nets to zero.
     */
    static List<OverlayDialogue.Run> frame(String name, String hintIcon, String... rows) {
        String[][] pieces = new String[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            pieces[i] = new String[] {rows[i]};
        }
        return frameRows(name, hintIcon, pieces);
    }

    /** Like {@link #frame} with every row made of several runs (e.g. a differently styled phrase). */
    static List<OverlayDialogue.Run> frameRows(String name, String hintIcon, String[][] rows) {
        List<OverlayDialogue.Run> r = new ArrayList<>();
        r.add(run(HUD, move(-104) + "\ue00a" + move(-1) + move(104 - 10 + 1)));
        r.add(run(FADE, move(-2048) + "\ue003" + move(2048 - 10)));
        if (hintIcon != null) {
            r.add(run(CONTROL, move(-41)));
            r.add(run(CONTROL, hintIcon + " to continue"));
            r.add(run(CONTROL, move(-42)));
        }
        r.add(run(BOX, move(-128) + "\ue003" + move(128 - 10)));
        if (rows.length > 0) {
            r.add(run(BODY + 0, move(-92)));
            for (int i = 0; i < rows.length; i++) {
                int w = 0;
                for (String piece : rows[i]) {
                    r.add(run(BODY + i, piece));
                    w += MEASURE.width(BODY + i, 0, piece);
                }
                boolean last = i == rows.length - 1;
                r.add(run(BODY + (last ? 0 : i + 1), move(last ? -w + 92 : -w)));
            }
        }
        if (name != null) {
            int w = MEASURE.width(NAME, 0, name);
            int before = -(w / 2) - 40;
            r.add(run(NAME, move(before)));
            r.add(run(NAME, name));
            r.add(run(NAME, move(-before - w)));
        }
        r.add(run(FRAME, move(-160) + "\ue000" + move(160 - 10)));
        r.add(run(PORTRAIT, move(-152) + "\ue421" + move(152 - 10)));
        return r;
    }
}
