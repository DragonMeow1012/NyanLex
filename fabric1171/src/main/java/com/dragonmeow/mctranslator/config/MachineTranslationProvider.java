package com.dragonmeow.mctranslator.config;

import java.util.List;
import java.util.Locale;

/** Key-free machine-translation sources exposed by the in-game picker. */
public enum MachineTranslationProvider {
    GOOGLE("google", false),
    YOUDAO("youdao", true),
    DEEPL("deepl", true),
    MICROSOFT("microsoft", true);

    private final String id;
    private final boolean experimental;

    MachineTranslationProvider(String id, boolean experimental) {
        this.id = id;
        this.experimental = experimental;
    }

    public String id() { return id; }
    public boolean experimental() { return experimental; }

    public static MachineTranslationProvider fromId(String value) {
        String wanted = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (MachineTranslationProvider provider : values()) {
            if (provider.id.equals(wanted)) return provider;
        }
        return GOOGLE;
    }

    public static String normalize(String value) { return fromId(value).id; }
    public static List<MachineTranslationProvider> selectable() { return List.of(values()); }
}
