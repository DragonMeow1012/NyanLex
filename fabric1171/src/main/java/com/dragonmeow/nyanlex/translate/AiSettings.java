package com.dragonmeow.nyanlex.translate;

import java.util.List;

/**
 * Live snapshot of the AI fine-translation settings, supplied to
 * {@link OpenAiTranslator}. Kept separate from the config class so the translate
 * package stays Minecraft-/config-agnostic and unit-testable with inline values.
 *
 * @param baseUrl  OpenAI-compatible base URL (e.g. {@code https://api.openai.com/v1})
 * @param model    model id (e.g. {@code gpt-5.6-luna})
 * @param apiKeys  zero or more API keys, rotated round-robin / on failure when present
 * @param glossary request-local user-pinned term overrides ("訂翻譯"), each a
 *                 {@code "English=中文"} line. Never {@code null} (defaults to empty).
 * @param effort   thinking-strength setting for this service (see {@link ReasoningEffort});
 *                 {@code null} or invalid means "the service default".
 */
public record AiSettings(String baseUrl, String model, List<String> apiKeys, List<String> glossary,
                         boolean fastMode, String effort) {

    /** Canonical constructor: null-guard optional lists so callers may pass {@code null}. */
    public AiSettings {
        if (apiKeys == null) apiKeys = List.of();
        if (glossary == null) glossary = List.of();
    }

    /** For call sites that predate the thinking-strength setting: the service default applies. */
    public AiSettings(String baseUrl, String model, List<String> apiKeys, List<String> glossary,
                      boolean fastMode) {
        this(baseUrl, model, apiKeys, glossary, fastMode, null);
    }

    /** Backward-compatible constructor for call sites that carry no user glossary. */
    public AiSettings(String baseUrl, String model, List<String> apiKeys) {
        this(baseUrl, model, apiKeys, List.of(), false);
    }

    public AiSettings(String baseUrl, String model, List<String> apiKeys, List<String> glossary) {
        this(baseUrl, model, apiKeys, glossary, false);
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && model != null && !model.isBlank();
    }
}
