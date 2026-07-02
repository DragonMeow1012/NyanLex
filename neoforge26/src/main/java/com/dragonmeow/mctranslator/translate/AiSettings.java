package com.dragonmeow.mctranslator.translate;

import java.util.List;

/**
 * Live snapshot of the AI fine-translation settings, supplied to
 * {@link OpenAiTranslator}. Kept separate from the config class so the translate
 * package stays Minecraft-/config-agnostic and unit-testable with inline values.
 *
 * @param baseUrl OpenAI-compatible base URL (e.g. {@code https://api.openai.com/v1})
 * @param model   model id (e.g. {@code gpt-4o-mini})
 * @param apiKeys one or more API keys, rotated round-robin / on failure
 */
public record AiSettings(String baseUrl, String model, List<String> apiKeys) {

    public boolean isConfigured() {
        return model != null && !model.isBlank()
                && apiKeys != null && !apiKeys.isEmpty();
    }
}
