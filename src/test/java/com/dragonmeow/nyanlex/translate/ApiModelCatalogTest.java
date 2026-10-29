package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiModelCatalogTest {
    @Test void openAiCatalogUsesCurrentCredentialsPaginatesAndKeepsUnknownTextModels() throws Exception {
        List<String> urls = new ArrayList<>();
        List<String> models = ApiModelCatalog.load("https://api.openai.com/v1/chat/completions", List.of("", "secret"), (url, headers) -> {
            urls.add(url);
            assertEquals("Bearer secret", headers.get("Authorization"));
            assertFalse(url.contains("secret"));
            return urls.size() == 1
                    ? "{\"data\":[{\"id\":\"gpt-5.6-luna\"},{\"id\":\"gpt-audio\"},{\"id\":\"text-embedding-3-small\"}],\"has_more\":true,\"last_id\":\"last/page\"}"
                    : "{\"data\":[{\"id\":\"gpt-5.6-luna\"},{\"id\":\"future-text-model\"}],\"has_more\":false}";
        });
        assertEquals(List.of("future-text-model", "gpt-5.6-luna"), models);
        assertEquals(List.of("https://api.openai.com/v1/models", "https://api.openai.com/v1/models?after=last%2Fpage"), urls);
    }

    @Test void geminiUsesNativeCapabilityListAndPageTokens() throws Exception {
        List<String> urls = new ArrayList<>();
        List<String> models = ApiModelCatalog.load("https://generativelanguage.googleapis.com/v1beta/openai/", List.of("key"), (url, headers) -> {
            urls.add(url);
            assertEquals("key", headers.get("x-goog-api-key"));
            assertFalse(headers.containsKey("Authorization"));
            return urls.size() == 1
                    ? "{\"models\":[{\"name\":\"models/gemini-3.5-flash-lite\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/embedding\",\"supportedGenerationMethods\":[\"embedContent\"]}],\"nextPageToken\":\"page2\"}"
                    : "{\"models\":[{\"name\":\"models/gemini-future\",\"supportedGenerationMethods\":[\"generateContent\"]}]}";
        });
        assertEquals(List.of("gemini-3.5-flash-lite", "gemini-future"), models);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageToken=page2", urls.get(1));
    }

    @Test void customLocalProviderCanBeKeylessAndMalformedCatalogDoesNotInventModels() throws Exception {
        assertEquals(List.of("local-model"), ApiModelCatalog.load("http://localhost:11434/v1", List.of(), (url, headers) -> {
            assertEquals("http://localhost:11434/v1/models", url);
            assertTrue(headers.isEmpty());
            return "{\"data\":[{\"id\":\"local-model\"}]}";
        }));
        assertThrows(IOException.class, () -> ApiModelCatalog.load("https://example.test/v1", List.of(), (u,h) -> "{}"));
        assertThrows(IOException.class, () -> ApiModelCatalog.load("http://remote.test/v1", List.of(), (u,h) -> { fail(); return ""; }));
        assertThrows(IOException.class, () -> ApiModelCatalog.load("https://example.test/v1", List.of(), (u,h) ->
                "{\"data\":[],\"has_more\":true,\"last_id\":\"repeated\"}"));
    }
}
