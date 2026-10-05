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
    void noticePreferencesPersistIndependentlyWithoutEnablingTranslation() {
        TranslatorConfig config = TranslatorConfig.fromReader(new StringReader("{}"));
        assertFalse(config.hideAntigravityNotice);
        assertFalse(config.hideGeminiApiNotice);
        config.translationRequestsEnabled = false;
        config.hideAntigravityNotice = true;
        StringWriter output = new StringWriter();
        config.writeTo(output);
        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(output.toString()));
        assertTrue(loaded.hideAntigravityNotice);
        assertFalse(loaded.hideGeminiApiNotice);
        assertFalse(loaded.antigravityEnabled);
        assertFalse(loaded.translationRequestsEnabled);
        loaded.hideGeminiApiNotice = true;
        loaded.hideAntigravityNotice = false;
        output = new StringWriter();
        loaded.writeTo(output);
        loaded = TranslatorConfig.fromReader(new StringReader(output.toString()));
        assertFalse(loaded.hideAntigravityNotice);
        assertTrue(loaded.hideGeminiApiNotice);
    }

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
                "{\"antigravityEnabled\":true,\"aiProvider\":\"antigravity\",\"antigravityModel\":null,"
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

    @Test
    void hiddenAntigravityNeverSilentlyFallsBackToApi() {
        TranslatorConfig config = TranslatorConfig.fromReader(new StringReader(
                "{\"aiProvider\":\"antigravity\",\"translationRequestsEnabled\":true}"));
        assertFalse(config.antigravityEnabled);
        assertFalse(config.usesAntigravity());
        assertFalse(config.translationRequestsEnabled);
        config.selectAiProvider(TranslatorConfig.AI_PROVIDER_ANTIGRAVITY);
        assertFalse(config.usesAntigravity());
        config.setAntigravityEnabled(true);
        config.selectAiProvider(TranslatorConfig.AI_PROVIDER_ANTIGRAVITY);
        assertTrue(config.usesAntigravity());
        config.translationRequestsEnabled = true;
        config.setAntigravityEnabled(false);
        assertFalse(config.usesAntigravity());
        assertFalse(config.translationRequestsEnabled);
    }
}
