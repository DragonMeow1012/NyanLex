package com.dragonmeow.mctranslator.translate;

import java.util.List;

/**
 * Live snapshot of the AI fine-translation settings, supplied to
 * {@link OpenAiTranslator}. Kept separate from the config class so the translate
 * package stays Minecraft-/config-agnostic and unit-testable with inline values.
 *
 * @param baseUrl  OpenAI-compatible base URL (e.g. {@code https://api.openai.com/v1})
 * @param model    model id (e.g. {@code gpt-5.4-mini})
 * @param apiKeys  one or more API keys, rotated round-robin / on failure
 * @param glossary request-local user-pinned term overrides ("訂翻譯"), each a
 *                 {@code "English=中文"} line. Never {@code null} (defaults to empty).
 */
public record AiSettings(String baseUrl, String model, List<String> apiKeys, List<String> glossary) {

    /** Canonical constructor: null-guard the glossary so callers may pass {@code null}. */
    public AiSettings {
        if (glossary == null) glossary = List.of();
    }

    /** Backward-compatible constructor for call sites that carry no user glossary. */
    public AiSettings(String baseUrl, String model, List<String> apiKeys) {
        this(baseUrl, model, apiKeys, List.of());
    }

    public boolean isConfigured() {
        return model != null && !model.isBlank()
                && apiKeys != null && !apiKeys.isEmpty();
    }
}
