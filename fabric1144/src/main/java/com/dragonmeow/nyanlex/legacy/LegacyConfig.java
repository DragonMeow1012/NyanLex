package com.dragonmeow.nyanlex.legacy;

import java.util.Locale;

public final class LegacyConfig {
    static final String DEFAULT_CODEX_MODEL = "gpt-5.6-terra";
    static final String DEFAULT_CODEX_REASONING_EFFORT = "medium";
    boolean enabled = true;
    boolean followGameLanguage = true;
    boolean showOriginal = true;
    boolean deliverChatTranslationsInOrder = true;
    public boolean chatComposerEnabled = false;
    public String chatComposerLanguage = "en";
    public double chatComposerX = 1.0;
    public double chatComposerY = 1.0;
    /**
     * Online translation (master request switch). False keeps showing cached translations but
     * never sends a new translation request. Off for a new install; an older config file that
     * has no such field keeps behaving as before (see {@link #applyUpgradeDefaults}).
     * Volatile: worker and retry threads re-check the live value.
     */
    volatile boolean translationRequestsEnabled = false;
    /** True once the quick setup was finished or dismissed (or the config predates it). */
    boolean firstRunDone = false;
    /** Whole-word, case-insensitive terms kept verbatim (masked before cache keys and requests). */
    java.util.List<String> doNotTranslateTerms = new java.util.ArrayList<String>();
    String targetLang = "zh-TW";
    String sourceLang = "auto";
    /** Machine source: always google (unofficial key-free endpoint); any other stored id migrates to it. */
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
    int requestCooldownMs = 5000;
    /** Ordinary misses collect for this long; zero flushes on the next client tick. */
    int batchWindowMs = 5000;
    int failureBackoffMs = 10000;
    boolean debugTranslationOverlay = false;

    /** Which service the next translation request would go to (see {@link #serviceKind}). */
    static final int SERVICE_GOOGLE = 0;
    static final int SERVICE_AI = 1;
    static final int SERVICE_CODEX = 2;

    int serviceKind() {
        if (!aiEnabled) return SERVICE_GOOGLE;
        return aiUseCodex ? SERVICE_CODEX : SERVICE_AI;
    }

    /** Host part of the AI endpoint, for showing where text would be sent. */
    String aiHost() {
        String url = aiBaseUrl == null ? "" : aiBaseUrl.trim();
        int scheme = url.indexOf("://");
        if (scheme >= 0) url = url.substring(scheme + 3);
        int end = url.length();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') { end = i; break; }
        }
        return url.substring(0, end);
    }

    /** An API key or a ChatGPT (Codex) login is enough to use the AI service. */
    boolean aiConfigured() {
        if (aiUseCodex) return true;
        if (aiApiKeys != null) for (String key : aiApiKeys) if (key != null && !key.trim().isEmpty()) return true;
        if (aiKeysByEndpoint != null)
            for (String key : aiKeysByEndpoint.values()) if (key != null && !key.trim().isEmpty()) return true;
        return false;
    }

    static String normalizeMachineProvider(String value) {
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

    /**
     * Upgrade rules for a config file that already exists on disk. A file written before the
     * online-translation default changed has no "translationRequestsEnabled" field: that player
     * had translation requests on, so keep them on. A file without "firstRunDone" belongs to an
     * existing install, which must not get the first-run quick setup popped at it.
     */
    static LegacyConfig applyUpgradeDefaults(LegacyConfig loaded, boolean hadRequestsField,
                                             boolean hadFirstRunField) {
        if (loaded == null) return null;
        if (!hadRequestsField) loaded.translationRequestsEnabled = true;
        if (!hadFirstRunField) loaded.firstRunDone = true;
        return loaded;
    }

    static LegacyConfig normalizeLoaded(LegacyConfig loaded) {
        if (loaded == null) return null;
        if (loaded.aiApiKeys == null) loaded.aiApiKeys = new java.util.ArrayList<String>();
        loaded.doNotTranslateTerms = normalizeDoNotTranslateTerms(loaded.doNotTranslateTerms);
        if (loaded.chatComposerLanguage == null || loaded.chatComposerLanguage.trim().isEmpty()) loaded.chatComposerLanguage = "en";
        if (loaded.aiKeysByEndpoint == null)
            loaded.aiKeysByEndpoint = new java.util.LinkedHashMap<String, String>();
        if (loaded.codexModel == null || loaded.codexModel.trim().isEmpty())
            loaded.codexModel = DEFAULT_CODEX_MODEL;
        if (loaded.codexReasoningEffort == null || loaded.codexReasoningEffort.trim().isEmpty())
            loaded.codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;
        String stored = loaded.machineTranslationProvider == null
                ? "" : loaded.machineTranslationProvider.trim().toLowerCase(Locale.ROOT);
        if (!stored.isEmpty() && !"google".equals(stored)) {
            java.util.logging.Logger.getLogger("nyanlex").warning("Machine translation source '"
                    + stored + "' is no longer supported; switched back to Google.");
        }
        loaded.machineTranslationProvider = normalizeMachineProvider(loaded.machineTranslationProvider);
        if (loaded.pacingDefaultsVersion < 1) {
            if (loaded.requestCooldownMs == 6000) loaded.requestCooldownMs = 5000;
            loaded.pacingDefaultsVersion = 1;
        }
        if (loaded.requestCooldownMs < 0) loaded.requestCooldownMs = 5000;
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
        copy.firstRunDone = firstRunDone;
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
