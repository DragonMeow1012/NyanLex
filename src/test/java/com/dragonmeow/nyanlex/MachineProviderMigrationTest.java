package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.SwitchingMachineTranslator;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Google is the only machine source; every other stored id migrates to it. */
class MachineProviderMigrationTest {

    @Test
    void everyNonGoogleIdFallsBackToGoogleAndIsReportedOnce() {
        for (String old : List.of("youdao", "deepl", "microsoft", "bing", "deepl_api", "microsoft_api")) {
            TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(
                    "{\"machineTranslationProvider\":\"" + old + "\"}"));
            assertEquals("google", cfg.machineTranslationProvider, old);
            assertEquals(old, cfg.takeRetiredProviderReset(), old);
            assertNull(cfg.takeRetiredProviderReset(), "reported once");
        }
    }

    @Test
    void googleAndMissingIdsAreNotReported() {
        for (String json : List.of("{\"machineTranslationProvider\":\"google\"}", "{}",
                "{\"machineTranslationProvider\":\"\"}")) {
            TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(json));
            assertEquals("google", cfg.machineTranslationProvider, json);
            assertNull(cfg.takeRetiredProviderReset(), json);
        }
        assertEquals(List.of(MachineTranslationProvider.GOOGLE), MachineTranslationProvider.selectable());
        assertTrue(MachineTranslationProvider.GOOGLE.unofficial());
    }

    @Test
    void oldKeyFieldsAreDroppedAndNeverWrittenBack() {
        String old = "{\"machineTranslationProvider\":\"deepl_api\","
                + "\"deeplApiKey\":\"deepl-secret-111:fx\","
                + "\"microsoftApiKey\":\"ms-secret-222\",\"microsoftApiRegion\":\"eastus\"}";
        TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(old));
        StringWriter out = new StringWriter();
        cfg.writeTo(out);
        String saved = out.toString();
        assertFalse(saved.contains("deepl-secret-111"), saved);
        assertFalse(saved.contains("ms-secret-222"), saved);
        assertFalse(saved.toLowerCase().contains("deepl"), saved);
        assertFalse(saved.toLowerCase().contains("microsoft"), saved);
        assertFalse(cfg.secretValues().contains("deepl-secret-111:fx"));
        assertFalse(cfg.secretValues().contains("ms-secret-222"));
    }

    @Test
    void aiKeysStayRedactedAfterTheMachineKeysWereRemoved() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.aiApiKeys = new java.util.ArrayList<>(List.of("sk-test-key-1"));
        assertTrue(cfg.secretValues().contains("sk-test-key-1"));
    }

    @Test
    void routerAlwaysUsesTheGoogleEndpointWhateverTheStoredId() throws Exception {
        for (String id : List.of("google", "deepl_api", "microsoft", "youdao", "")) {
            AtomicReference<String> url = new AtomicReference<>();
            HttpTransport transport = u -> {
                url.set(u);
                return "[[[\"紅劍\",\"Red Sword\",null,null]],null,\"en\"]";
            };
            SwitchingMachineTranslator router = new SwitchingMachineTranslator(
                    transport, () -> "en", () -> id, RequestPacer.disabled());
            assertEquals("紅劍", router.translate("Red Sword", "zh-TW").translatedText(), id);
            assertTrue(url.get().startsWith("https://translate.googleapis.com/"), id + " -> " + url.get());
        }
    }
}
