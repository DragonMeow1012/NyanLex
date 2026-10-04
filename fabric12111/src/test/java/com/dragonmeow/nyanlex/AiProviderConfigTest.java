package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProviderConfigTest {

    @Test
    void legacyCodexFlagMigratesToSingleProviderField() {
        TranslatorConfig config = TranslatorConfig.fromReader(
                new StringReader("{\"aiUseCodex\":true}"));
        assertEquals(TranslatorConfig.AI_PROVIDER_CODEX, config.aiProvider);
        assertTrue(config.usesCodex());
        assertTrue(config.aiUseCodex);
    }

    @Test
    void antigravitySettingsNormalizeAndRoundTrip() {
        TranslatorConfig config = TranslatorConfig.fromReader(new StringReader(
                "{\"aiProvider\":\"antigravity\",\"antigravityModel\":null,"
                        + "\"antigravityReasoningEffort\":\"invalid\",\"aiUseCodex\":true}"));
        assertTrue(config.usesAntigravity());
        assertFalse(config.aiUseCodex);
        assertEquals(TranslatorConfig.DEFAULT_ANTIGRAVITY_MODEL, config.antigravityModel);

        StringWriter output = new StringWriter();
        config.writeTo(output);
        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(output.toString()));
        assertTrue(loaded.usesAntigravity());
        assertFalse(loaded.aiUseCodex);
    }
}
