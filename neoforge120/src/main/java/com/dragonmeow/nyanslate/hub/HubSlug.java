package com.dragonmeow.nyanslate.hub;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalizes a free-form name into a safe repository path segment: lower-case,
 *  {@code [^a-z0-9-]} collapsed to a single dash, no leading/trailing dash, capped
 *  at 64 characters. */
public final class HubSlug {
    private static final Pattern INVALID = Pattern.compile("[^a-z0-9-]+");
    private static final Pattern DASHES = Pattern.compile("-{2,}");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final int MAX_LENGTH = 64;

    private HubSlug() {
    }

    public static String of(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        String replaced = INVALID.matcher(lower).replaceAll("-");
        String collapsed = DASHES.matcher(replaced).replaceAll("-");
        String trimmed = EDGE_DASHES.matcher(collapsed).replaceAll("");
        if (trimmed.length() <= MAX_LENGTH) return trimmed;
        String cut = trimmed.substring(0, MAX_LENGTH);
        return EDGE_DASHES.matcher(cut).replaceAll("");
    }
}
