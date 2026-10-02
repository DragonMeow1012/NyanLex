package com.dragonmeow.nyanlex.config;

import java.util.List;
import java.util.Locale;

/**
 * Machine-translation sources.
 *
 * <p>Only {@link #GOOGLE} exists: Google's key-free web endpoint. This is an UNOFFICIAL
 * endpoint that may stop working or be limited at any time. Every other id found in an old
 * config file (youdao, deepl, microsoft, bing, deepl_api, microsoft_api ...) is migrated to
 * Google on load; see {@link #isRetiredId(String)}.</p>
 */
public enum MachineTranslationProvider {
    GOOGLE("google", true);

    private final String id;
    private final boolean unofficial;

    MachineTranslationProvider(String id, boolean unofficial) {
        this.id = id;
        this.unofficial = unofficial;
    }

    public String id() { return id; }
    /** True for an unofficial, key-free endpoint that may be limited or stop working. */
    public boolean unofficial() { return unofficial; }

    /** Always {@link #GOOGLE}: it is the only source. */
    public static MachineTranslationProvider fromId(String value) { return GOOGLE; }

    /** True when {@code value} names some source other than Google (removed or unknown). */
    public static boolean isRetiredId(String value) {
        if (value == null) return false;
        String wanted = value.strip().toLowerCase(Locale.ROOT);
        return !wanted.isEmpty() && !wanted.equals(GOOGLE.id);
    }

    public static String normalize(String value) { return GOOGLE.id; }
    public static List<MachineTranslationProvider> selectable() { return List.of(values()); }
}
