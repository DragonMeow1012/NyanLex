package com.dragonmeow.mctranslator.forgelegacy;

final class LegacyConfig {
    boolean enabled = true;
    boolean followGameLanguage = true;
    boolean showOriginal = true;
    String targetLang = "zh-TW";
    String sourceLang = "auto";
    boolean aiEnabled = false;
    boolean disableGoogleFallbackForAi = false;
    String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    String aiModel = "gemini-3.1-flash-lite";
    java.util.List<String> aiApiKeys = new java.util.ArrayList<String>();
    int requestCooldownMs = 2000;
    int failureBackoffMs = 10000;
    boolean debugTranslationOverlay = false;
}
