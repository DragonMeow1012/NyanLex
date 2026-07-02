package com.dragonmeow.mctranslator.style;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a {@link ColorProfile} captured from the original text onto a translated
 * string, producing colour/format runs. Consecutive characters that resolve to
 * the same colour are merged into a single run to keep the rebuilt text compact.
 */
public final class StyleMapper {

    private StyleMapper() {
    }

    public static List<StyledRun> toRuns(String translated, ColorProfile profile) {
        List<StyledRun> runs = new ArrayList<>();
        if (translated == null || translated.isEmpty()) {
            return runs;
        }
        if (profile == null || !profile.hasAnyStyle()) {
            // Nothing to preserve: a single uncoloured run.
            runs.add(new StyledRun(translated, ColorProfile.NO_COLOR,
                    false, false, false, false, false));
            return runs;
        }

        int length = translated.length();
        int i = 0;
        while (i < length) {
            int color = profile.colorAt(i, length);
            int j = i + 1;
            while (j < length && profile.colorAt(j, length) == color) {
                j++;
            }
            // Never cut between a high and low surrogate (would split an
            // astral code point such as an emoji or CJK Extension-B glyph into
            // two broken halves). Extend the run to keep the pair together.
            if (j < length && Character.isHighSurrogate(translated.charAt(j - 1))
                    && Character.isLowSurrogate(translated.charAt(j))) {
                j++;
            }
            runs.add(new StyledRun(
                    translated.substring(i, j),
                    color,
                    profile.bold(), profile.italic(), profile.underline(),
                    profile.strikethrough(), profile.obfuscated()));
            i = j;
        }
        return runs;
    }
}
