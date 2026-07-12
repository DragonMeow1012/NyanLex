package com.dragonmeow.mctranslator.legacy;

import java.util.Locale;

final class LegacyConfig {
    boolean enabled = true;
    boolean followGameLanguage = true;
    boolean showOriginal = true;
    String targetLang = "zh-TW";
    String sourceLang = "auto";
    /** Key-free machine source: google, youdao, deepl, or microsoft. */
    String machineTranslationProvider = "google";
    boolean aiEnabled = false;
    boolean disableGoogleFallbackForAi = false;
    String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    String aiModel = "gemini-3.1-flash-lite";
    java.util.List<String> aiApiKeys = new java.util.ArrayList<String>();
    int requestCooldownMs = 6000;
    /** Ordinary misses collect for this long; zero flushes on the next client tick. */
    int batchWindowMs = 5000;
    int failureBackoffMs = 10000;
    boolean debugTranslationOverlay = false;

    static String normalizeMachineProvider(String value) {
        String provider = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("youdao".equals(provider) || "deepl".equals(provider)
                || "microsoft".equals(provider)) return provider;
        return "google";
    }
}
