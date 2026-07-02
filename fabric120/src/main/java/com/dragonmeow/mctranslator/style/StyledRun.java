package com.dragonmeow.mctranslator.style;

/**
 * A run of consecutive translated characters that share one colour + format.
 * The glue turns each run into a styled {@code Text} sibling.
 *
 * @param color RGB colour, or {@link ColorProfile#NO_COLOR} for the default colour
 */
public record StyledRun(String text, int color, boolean bold, boolean italic,
                        boolean underline, boolean strikethrough, boolean obfuscated) {

    public boolean hasColor() {
        return color != ColorProfile.NO_COLOR;
    }
}
