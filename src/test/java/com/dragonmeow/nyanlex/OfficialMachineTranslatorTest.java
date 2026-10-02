package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.RequestPacer;
import com.dragonmeow.nyanlex.translate.SwitchingMachineTranslator;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Official DeepL / Microsoft APIs against an inline fake transport (never touches the network). */
class OfficialMachineTranslatorTest {

    private static final String DEEPL_KEY = "abcd1234-secret-deepl-key:fx";
    private static final String MS_KEY = "ms-secret-key-0123456789";

    @Test
    void deeplUsesOfficialEndpointAuthHeaderAndTraditionalChineseCode() throws Exception {
        EchoTransport transport = new EchoTransport();
        TranslatorConfig cfg = config();
        SwitchingMachineTranslator translator = machine(transport, "deepl_api", cfg);

        List<TranslationResult> out = translator.translateBatch(
                List.of("Red Sword", "⟦CS0⟧Blue Apple⟦/CS0⟧"), "zh-TW");

        assertEquals(List.of("紅劍", "⟦CS0⟧藍蘋果⟦/CS0⟧"), texts(out));
        assertEquals(1, transport.posts.size());
        assertEquals("https://api-free.deepl.com/v2/translate", transport.lastUrl,
                "a :fx key selects the free-plan host");
        assertEquals("DeepL-Auth-Key " + DEEPL_KEY, transport.lastHeaders.get("Authorization"));
        assertEquals("ZH-HANT", transport.deepLTarget);
        assertEquals("EN", transport.deepLSource);
        assertFalse(transport.posts.get(0).contains("⟦"), "private markers stay off the wire");
        assertFalse(transport.lastUrl.contains(DEEPL_KEY), "the key never appears in a URL");
    }

    @Test
    void deeplProKeyUsesProHost() throws Exception {
        EchoTransport transport = new EchoTransport();
        TranslatorConfig cfg = config();
        cfg.deeplApiKey = "pro-key-without-suffix";
        machine(transport, "deepl_api", cfg).translate("Red Sword", "zh-TW");
        assertEquals("https://api.deepl.com/v2/translate", transport.lastUrl);
    }

    @Test
    void microsoftUsesOfficialEndpointKeyAndRegionHeaders() throws Exception {
        EchoTransport transport = new EchoTransport();
        TranslatorConfig cfg = config();
        cfg.microsoftApiRegion = "eastus";
        SwitchingMachineTranslator translator = machine(transport, "microsoft_api", cfg);

        List<TranslationResult> out = translator.translateBatch(List.of("Red Sword", "Blue Apple"), "zh-TW");

        assertEquals(List.of("紅劍", "藍蘋果"), texts(out));
        assertEquals(1, transport.posts.size());
        assertTrue(transport.lastUrl.startsWith("https://api.cognitive.microsofttranslator.com/translate?"));
        assertTrue(transport.lastUrl.contains("api-version=3.0"));
        assertTrue(transport.lastUrl.contains("to=zh-Hant"));
        assertTrue(transport.lastUrl.contains("from=en"));
        assertEquals(MS_KEY, transport.lastHeaders.get("Ocp-Apim-Subscription-Key"));
        assertEquals("eastus", transport.lastHeaders.get("Ocp-Apim-Subscription-Region"));
        assertFalse(transport.lastUrl.contains(MS_KEY));
    }

    @Test
    void microsoftWithoutRegionOmitsTheRegionHeader() throws Exception {
        EchoTransport transport = new EchoTransport();
        machine(transport, "microsoft_api", config()).translate("Red Sword", "zh-TW");
        assertFalse(transport.lastHeaders.containsKey("Ocp-Apim-Subscription-Region"));
    }

    @Test
    void missingKeyFailsWithoutAnyRequest() {
        EchoTransport transport = new EchoTransport();
        TranslatorConfig cfg = new TranslatorConfig();
        assertThrows(TranslationException.class,
                () -> machine(transport, "deepl_api", cfg).translate("Red Sword", "zh-TW"));
        assertThrows(TranslationException.class,
                () -> machine(transport, "microsoft_api", cfg).translate("Red Sword", "zh-TW"));
        assertEquals(0, transport.posts.size());
    }

    @Test
    void errorTextNeverContainsTheKey() {
        HttpTransport failing = new HttpTransport() {
            @Override public String get(String url) { throw new AssertionError(); }
            @Override public String post(String url, String body, Map<String, String> headers)
                    throws java.io.IOException {
                throw new java.io.IOException("HTTP 403: bad key " + DEEPL_KEY);
            }
        };
        TranslationException e = assertThrows(TranslationException.class,
                () -> machine(failing, "deepl_api", config()).translate("Red Sword", "zh-TW"));
        assertFalse(e.getMessage().contains(DEEPL_KEY));
        assertTrue(e.getMessage().contains("403"));
    }

    @Test
    void overlappingOuterAnchorsAreBisectedBeforeAnyItemCanBeContaminated() throws Exception {
        EchoTransport transport = new EchoTransport();
        transport.interleaveFirstBatch = true;
        List<TranslationResult> out = machine(transport, "deepl_api", config())
                .translateBatch(List.of("Red Sword", "Blue Apple"), "zh-TW");

        assertEquals(List.of("紅劍", "藍蘋果"), texts(out));
        assertEquals(3, transport.posts.size(), "damaged pair is retried as two isolated items");
        assertFalse(texts(out).stream().anyMatch(text -> text.matches(".*76\\d{3}.*")));
    }

    @Test
    void routerReadsProviderSelectionLive() throws Exception {
        EchoTransport transport = new EchoTransport();
        AtomicReference<String> provider = new AtomicReference<>("deepl_api");
        TranslatorConfig cfg = config();
        SwitchingMachineTranslator translator = new SwitchingMachineTranslator(
                transport, () -> "en", provider::get, RequestPacer.disabled(), () -> cfg);
        assertEquals("紅劍", translator.translate("Red Sword", "zh-TW").translatedText());
        assertTrue(transport.lastUrl.contains("deepl.com"));
        provider.set("microsoft_api");
        assertEquals("紅劍", translator.translate("Red Sword", "zh-TW").translatedText());
        assertTrue(transport.lastUrl.contains("microsofttranslator.com"));
    }

    @Test
    void retiredWebEndpointIdsFallBackToGoogleAndAreReportedOnce() {
        for (String retired : List.of("youdao", "deepl", "microsoft")) {
            TranslatorConfig cfg = TranslatorConfig.fromReader(new StringReader(
                    "{\"machineTranslationProvider\":\"" + retired + "\"}"));
            assertEquals("google", cfg.machineTranslationProvider, retired);
            assertEquals(retired, cfg.takeRetiredProviderReset());
            assertNull(cfg.takeRetiredProviderReset(), "reported once");
        }
        TranslatorConfig kept = TranslatorConfig.fromReader(new StringReader(
                "{\"machineTranslationProvider\":\"deepl_api\"}"));
        assertEquals("deepl_api", kept.machineTranslationProvider);
        assertNull(kept.takeRetiredProviderReset());
        assertTrue(MachineTranslationProvider.GOOGLE.unofficial());
        assertFalse(MachineTranslationProvider.DEEPL_API.unofficial());
    }

    @Test
    void apiKeysAreListedAsSecretsForRedaction() {
        TranslatorConfig cfg = config();
        assertTrue(cfg.secretValues().contains(DEEPL_KEY));
        assertTrue(cfg.secretValues().contains(MS_KEY));
    }

    @Test
    void apiKeysRoundTripThroughTheConfigFile() {
        TranslatorConfig cfg = config();
        java.io.StringWriter out = new java.io.StringWriter();
        cfg.writeTo(out);
        TranslatorConfig loaded = TranslatorConfig.fromReader(new StringReader(out.toString()));
        assertEquals(DEEPL_KEY, loaded.deeplApiKey);
        assertEquals(MS_KEY, loaded.microsoftApiKey);
        assertNotNull(loaded.microsoftApiRegion);
    }

    private static TranslatorConfig config() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.deeplApiKey = DEEPL_KEY;
        cfg.microsoftApiKey = MS_KEY;
        return cfg;
    }

    private static SwitchingMachineTranslator machine(HttpTransport transport, String provider,
                                                       TranslatorConfig cfg) {
        return new SwitchingMachineTranslator(transport, () -> "en", () -> provider,
                RequestPacer.disabled(), () -> cfg);
    }

    private static List<String> texts(List<TranslationResult> results) {
        return results.stream().map(TranslationResult::translatedText).toList();
    }

    private static final class EchoTransport implements HttpTransport {
        private final List<String> posts = new ArrayList<>();
        private String lastUrl;
        private Map<String, String> lastHeaders = Map.of();
        private String deepLTarget;
        private String deepLSource;
        private boolean interleaveFirstBatch;

        @Override public String get(String url) { throw new AssertionError("no GET expected"); }

        @Override public String post(String url, String body, Map<String, String> headers) {
            lastUrl = url;
            lastHeaders = headers;
            posts.add(body);
            boolean deepl = url.contains("deepl.com");
            String wire;
            if (deepl) {
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                wire = request.getAsJsonArray("text").get(0).getAsString();
                deepLTarget = request.get("target_lang").getAsString();
                deepLSource = request.has("source_lang") ? request.get("source_lang").getAsString() : null;
            } else {
                wire = JsonParser.parseString(body).getAsJsonArray().get(0)
                        .getAsJsonObject().get("Text").getAsString();
            }
            String translated;
            if (interleaveFirstBatch && posts.size() == 1 && wire.contains("Red Sword")
                    && wire.contains("Blue Apple")) {
                translated = "76001紅劍76003藍蘋果7600276004";
            } else {
                translated = wire.replace("Red Sword", "紅劍")
                        .replace("Blue Apple", "藍蘋果")
                        .replace("Blue", "藍").replace("Apple", "蘋果");
            }
            if (deepl) {
                JsonObject item = new JsonObject();
                item.addProperty("text", translated);
                item.addProperty("detected_source_language", "EN");
                JsonArray translations = new JsonArray(); translations.add(item);
                JsonObject response = new JsonObject(); response.add("translations", translations);
                return response.toString();
            }
            JsonObject translation = new JsonObject();
            translation.addProperty("text", translated);
            translation.addProperty("to", "zh-Hant");
            JsonArray translations = new JsonArray(); translations.add(translation);
            JsonObject detected = new JsonObject(); detected.addProperty("language", "en");
            JsonObject result = new JsonObject();
            result.add("translations", translations); result.add("detectedLanguage", detected);
            JsonArray response = new JsonArray(); response.add(result);
            return response.toString();
        }
    }
}
