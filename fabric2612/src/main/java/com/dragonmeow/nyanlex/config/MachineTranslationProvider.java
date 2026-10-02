package com.dragonmeow.nyanlex.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Machine-translation sources exposed by the in-game picker.
 *
 * <ul>
 *   <li>{@link #GOOGLE}: Google's key-free web endpoint. This is an UNOFFICIAL endpoint
 *       that may stop working or be limited at any time.</li>
 *   <li>{@link #DEEPL_API}, {@link #MICROSOFT_API}: the providers' official APIs, used
 *       with the player's own API key.</li>
 * </ul>
 */
public enum MachineTranslationProvider {
    GOOGLE("google", true, false),
    DEEPL_API("deepl_api", false, true),
    MICROSOFT_API("microsoft_api", false, true);

    /** Ids written by older builds for web endpoints that are no longer supported. */
    private static final Set<String> RETIRED_IDS = Set.of("youdao", "deepl", "microsoft");

    private final String id;
    private final boolean unofficial;
    private final boolean requiresKey;

    MachineTranslationProvider(String id, boolean unofficial, boolean requiresKey) {
        this.id = id;
        this.unofficial = unofficial;
        this.requiresKey = requiresKey;
    }

    public String id() { return id; }
    /** True for an unofficial, key-free endpoint that may be limited or stop working. */
    public boolean unofficial() { return unofficial; }
    /** True when the player must supply an API key for this source. */
    public boolean requiresKey() { return requiresKey; }

    public static MachineTranslationProvider fromId(String value) {
        String wanted = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (MachineTranslationProvider provider : values()) {
            if (provider.id.equals(wanted)) return provider;
        }
        return GOOGLE;
    }

    /** True when {@code value} names a web endpoint removed from this build. */
    public static boolean isRetiredId(String value) {
        return value != null && RETIRED_IDS.contains(value.strip().toLowerCase(Locale.ROOT));
    }

    public static String normalize(String value) { return fromId(value).id; }
    public static List<MachineTranslationProvider> selectable() { return List.of(values()); }
}
