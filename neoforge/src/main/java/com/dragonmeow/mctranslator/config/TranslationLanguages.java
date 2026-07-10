package com.dragonmeow.mctranslator.config;

import java.util.Locale;

/** Language-code conversion shared by every loader and Minecraft version. */
public final class TranslationLanguages {
    private TranslationLanguages() {
    }

    /** Convert Minecraft's {@code language_region} id to an API language tag. */
    public static String fromMinecraftCode(String minecraftCode) {
        String raw = minecraftCode == null ? "" : minecraftCode.strip().replace('_', '-');
        if (raw.isEmpty()) return "zh-TW";
        String[] parts = raw.split("-");
        StringBuilder out = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            out.append('-');
            if (parts[i].length() == 2 || parts[i].length() == 3 && isDigits(parts[i])) {
                out.append(parts[i].toUpperCase(Locale.ROOT));
            } else if (parts[i].length() == 4) {
                out.append(Character.toUpperCase(parts[i].charAt(0)))
                        .append(parts[i].substring(1).toLowerCase(Locale.ROOT));
            } else {
                out.append(parts[i].toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) return false;
        }
        return true;
    }
}
