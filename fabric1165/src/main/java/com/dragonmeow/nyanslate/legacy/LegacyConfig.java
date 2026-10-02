package com.dragonmeow.nyanslate.legacy;

import java.util.Locale;

final class LegacyConfig {
    static final String DEFAULT_CODEX_MODEL = "gpt-5.6-terra";
    static final String DEFAULT_CODEX_REASONING_EFFORT = "medium";
    boolean enabled = true;
    boolean followGameLanguage = true;
    boolean showOriginal = true;
    boolean deliverChatTranslationsInOrder = true;
    /**
     * Master request switch. False keeps showing cached translations but never sends a new
     * translation request. Volatile: worker and retry threads re-check the live value.
     */
    volatile boolean translationRequestsEnabled = true;
    /** Whole-word, case-insensitive terms kept verbatim (masked before cache keys and requests). */
    java.util.List<String> doNotTranslateTerms = new java.util.ArrayList<String>();
    String targetLang = "zh-TW";
    String sourceLang = "auto";
    /** Key-free machine source: google, youdao, deepl, or microsoft. */
    String machineTranslationProvider = "google";
    boolean aiEnabled = false;
    /** 1.0.7 UI round 3: machine-translation fallback defaults to off ("補譯關"). */
    boolean disableGoogleFallbackForAi = true;
    String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    String aiModel = "gemini-3.1-flash-lite";
    boolean aiUseCodex = false;
    String codexModel = DEFAULT_CODEX_MODEL;
    String codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;
    java.util.List<String> aiApiKeys = new java.util.ArrayList<String>();
    java.util.Map<String, String> aiKeysByEndpoint = new java.util.LinkedHashMap<String, String>();
    /** One-time migration marker for the safer Gemini 3.1 Flash-Lite pacing default. */
    int pacingDefaultsVersion = 0;
    int requestCooldownMs = 10000;
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

    /** null -> empty; trim each term; drop blanks; case-insensitive de-duplication (first spelling wins). */
    static java.util.List<String> normalizeDoNotTranslateTerms(java.util.List<String> terms) {
        java.util.List<String> normalized = new java.util.ArrayList<String>();
        if (terms == null) return normalized;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String term : terms) {
            String trimmed = trimTerm(term);
            if (trimmed.isEmpty()) continue;
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) normalized.add(trimmed);
        }
        return normalized;
    }

    /** Java-8 String.strip(): drops leading/trailing Character.isWhitespace (incl. U+3000). */
    private static String trimTerm(String term) {
        if (term == null) return "";
        int start = 0;
        int end = term.length();
        while (start < end && Character.isWhitespace(term.charAt(start))) start++;
        while (end > start && Character.isWhitespace(term.charAt(end - 1))) end--;
        return term.substring(start, end);
    }

    static LegacyConfig normalizeLoaded(LegacyConfig loaded) {
        if (loaded == null) return null;
        if (loaded.aiApiKeys == null) loaded.aiApiKeys = new java.util.ArrayList<String>();
        loaded.doNotTranslateTerms = normalizeDoNotTranslateTerms(loaded.doNotTranslateTerms);
        if (loaded.aiKeysByEndpoint == null)
            loaded.aiKeysByEndpoint = new java.util.LinkedHashMap<String, String>();
        if (loaded.codexModel == null || loaded.codexModel.trim().isEmpty())
            loaded.codexModel = DEFAULT_CODEX_MODEL;
        if (loaded.codexReasoningEffort == null || loaded.codexReasoningEffort.trim().isEmpty())
            loaded.codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;
        loaded.machineTranslationProvider = normalizeMachineProvider(loaded.machineTranslationProvider);
        if (loaded.pacingDefaultsVersion < 1) {
            if (loaded.requestCooldownMs == 6000) loaded.requestCooldownMs = 10000;
            loaded.pacingDefaultsVersion = 1;
        }
        if (loaded.requestCooldownMs < 0) loaded.requestCooldownMs = 10000;
        if (loaded.batchWindowMs < 0) loaded.batchWindowMs = 5000;
        if (loaded.failureBackoffMs < 0) loaded.failureBackoffMs = 10000;
        return loaded;
    }

    /** Deep request snapshot: queued work never observes later UI mutations. */
    LegacyConfig snapshotForRequest() {
        LegacyConfig copy = new LegacyConfig();
        copy.enabled = enabled;
        copy.followGameLanguage = followGameLanguage;
        copy.showOriginal = showOriginal;
        copy.deliverChatTranslationsInOrder = deliverChatTranslationsInOrder;
        copy.translationRequestsEnabled = translationRequestsEnabled;
        copy.doNotTranslateTerms = doNotTranslateTerms == null
                ? new java.util.ArrayList<String>()
                : new java.util.ArrayList<String>(doNotTranslateTerms);
        copy.targetLang = targetLang;
        copy.sourceLang = sourceLang;
        copy.machineTranslationProvider = normalizeMachineProvider(machineTranslationProvider);
        copy.aiEnabled = aiEnabled;
        copy.disableGoogleFallbackForAi = disableGoogleFallbackForAi;
        copy.aiBaseUrl = aiBaseUrl;
        copy.aiModel = aiModel;
        copy.aiUseCodex = aiUseCodex;
        copy.codexModel = codexModel;
        copy.codexReasoningEffort = codexReasoningEffort;
        copy.aiApiKeys = aiApiKeys == null
                ? new java.util.ArrayList<String>()
                : new java.util.ArrayList<String>(aiApiKeys);
        copy.aiKeysByEndpoint = aiKeysByEndpoint == null
                ? new java.util.LinkedHashMap<String, String>()
                : new java.util.LinkedHashMap<String, String>(aiKeysByEndpoint);
        copy.pacingDefaultsVersion = pacingDefaultsVersion;
        copy.requestCooldownMs = requestCooldownMs;
        copy.batchWindowMs = batchWindowMs;
        copy.failureBackoffMs = failureBackoffMs;
        copy.debugTranslationOverlay = debugTranslationOverlay;
        return copy;
    }
}
