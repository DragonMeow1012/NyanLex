package com.dragonmeow.nyanslate;

import com.dragonmeow.nyanslate.translate.HttpTransport;
import com.dragonmeow.nyanslate.translate.RequestPacer;
import com.dragonmeow.nyanslate.translate.SwitchingMachineTranslator;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentalMachineTranslatorTest {

    @Test
    void youdaoBatchesRichItemsOnceAndRestoresMarkersAndParagraphs() throws Exception {
        EchoTransport transport = new EchoTransport("youdao");
        AtomicReference<String> provider = new AtomicReference<>("youdao");
        SwitchingMachineTranslator translator = machine(transport, provider);

        List<TranslationResult> out = translator.translateBatch(List.of(
                "⟦CS0⟧Red Sword⟦/CS0⟧", "Blue\nApple"), "zh-TW");

        assertEquals(List.of("⟦CS0⟧紅劍⟦/CS0⟧", "藍\n蘋果"), texts(out));
        assertEquals(1, transport.posts.size());
        assertFalse(transport.posts.get(0).contains("⟦"), "private markers stay off the wire");
        assertTrue(transport.lastUrl.contains("to=zh-CHT"));
    }

    @Test
    void deeplUsesAnonymousOneShotAndTraditionalChineseCode() throws Exception {
        EchoTransport transport = new EchoTransport("deepl");
        AtomicReference<String> provider = new AtomicReference<>("deepl");
        SwitchingMachineTranslator translator = machine(transport, provider);

        List<TranslationResult> out = translator.translateBatch(List.of("Red Sword", "Blue Apple"), "zh-TW");

        assertEquals(List.of("紅劍", "藍蘋果"), texts(out));
        assertEquals(1, transport.posts.size());
        assertEquals("zh-Hant", transport.deepLTarget);
        assertEquals("None", transport.lastHeaders.get("Authorization"));
    }

    @Test
    void microsoftFetchesSessionOnceThenUsesStrictAnchoredBatch() throws Exception {
        EchoTransport transport = new EchoTransport("microsoft");
        AtomicReference<String> provider = new AtomicReference<>("microsoft");
        SwitchingMachineTranslator translator = machine(transport, provider);

        List<TranslationResult> out = translator.translateBatch(List.of("Red Sword", "Blue Apple"), "zh-TW");

        assertEquals(List.of("紅劍", "藍蘋果"), texts(out));
        assertEquals(1, transport.gets);
        assertEquals(1, transport.posts.size());
        assertTrue(transport.lastUrl.contains("IG=ABCDEF"));
        assertTrue(transport.lastUrl.contains("IID=translator.5023"));
        assertTrue(transport.lastUrl.contains("SFX=1"));
        assertEquals("zh-Hant", formValue(transport.posts.get(0), "to"));
    }

    @Test
    void overlappingOuterAnchorsAreBisectedBeforeAnyItemCanBeContaminated() throws Exception {
        EchoTransport transport = new EchoTransport("deepl");
        transport.interleaveFirstBatch = true;
        AtomicReference<String> provider = new AtomicReference<>("deepl");
        SwitchingMachineTranslator translator = machine(transport, provider);

        List<TranslationResult> out = translator.translateBatch(List.of("Red Sword", "Blue Apple"), "zh-TW");

        assertEquals(List.of("紅劍", "藍蘋果"), texts(out));
        assertEquals(3, transport.posts.size(), "damaged pair is retried as two isolated items");
        assertFalse(texts(out).stream().anyMatch(text -> text.matches(".*76\\d{3}.*")));
    }

    @Test
    void routerReadsProviderSelectionLive() throws Exception {
        EchoTransport transport = new EchoTransport("multi");
        AtomicReference<String> provider = new AtomicReference<>("youdao");
        SwitchingMachineTranslator translator = machine(transport, provider);
        assertEquals("紅劍", translator.translate("Red Sword", "zh-TW").translatedText());
        provider.set("deepl");
        assertEquals("紅劍", translator.translate("Red Sword", "zh-TW").translatedText());
        assertTrue(transport.lastUrl.contains("deepl.com"));
    }

    private static SwitchingMachineTranslator machine(HttpTransport transport,
                                                       AtomicReference<String> provider) {
        return new SwitchingMachineTranslator(transport, () -> "en", provider::get,
                RequestPacer.disabled());
    }

    private static List<String> texts(List<TranslationResult> results) {
        return results.stream().map(TranslationResult::translatedText).toList();
    }

    private static String formValue(String body, String key) {
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            if (URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8).equals(key)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static final class EchoTransport implements HttpTransport {
        private final String mode;
        private final List<String> posts = new ArrayList<>();
        private int gets;
        private String lastUrl;
        private Map<String, String> lastHeaders = Map.of();
        private String deepLTarget;
        private boolean interleaveFirstBatch;

        private EchoTransport(String mode) { this.mode = mode; }

        @Override public String get(String url) {
            gets++;
            return "<html><script>var params_AbusePreventionHelper = "
                    + "[1700000000000,\"TOKEN\",3600000]; var x={IG:\"ABCDEF\"};</script>"
                    + "<div data-iid=\"translator.5023\"></div></html>";
        }

        @Override public String post(String url, String body, Map<String, String> headers) {
            lastUrl = url;
            lastHeaders = headers;
            posts.add(body);
            String active = mode.equals("multi")
                    ? (url.contains("youdao") ? "youdao" : url.contains("deepl") ? "deepl" : "microsoft")
                    : mode;
            String wire;
            if (active.equals("youdao")) {
                wire = formValue(body, "i");
            } else if (active.equals("deepl")) {
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                wire = request.getAsJsonArray("text").get(0).getAsString();
                deepLTarget = request.get("target_lang").getAsString();
            } else {
                wire = formValue(body, "text");
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

            if (active.equals("youdao")) {
                JsonObject cell = new JsonObject();
                cell.addProperty("tgt", translated);
                JsonArray row = new JsonArray(); row.add(cell);
                JsonArray rows = new JsonArray(); rows.add(row);
                JsonObject response = new JsonObject();
                response.addProperty("code", 0);
                response.addProperty("guessLanguage", "en");
                response.add("translateResult", rows);
                return response.toString();
            }
            if (active.equals("deepl")) {
                JsonObject item = new JsonObject();
                item.addProperty("text", translated);
                item.addProperty("detected_source_language", "en");
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
