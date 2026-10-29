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

    private static final String CAPS_ALL_BUT_XHIGH = "\"capabilities\":{\"effort\":{\"supported\":true,"
            + "\"low\":{\"supported\":true},\"medium\":{\"supported\":true},\"high\":{\"supported\":true},"
            + "\"xhigh\":null,\"max\":{\"supported\":true}}}";

    @Test void anthropicCatalogUsesItsOwnHeadersLimitCursorAndEffortCapabilities() throws Exception {
        List<String> urls = new ArrayList<>();
        AiFeatureMemory memory = new AiFeatureMemory();
        List<String> models = ApiModelCatalog.load("https://api.anthropic.com", List.of("", "sk-ant-secret"), (url, headers) -> {
            urls.add(url);
            assertEquals("sk-ant-secret", headers.get("x-api-key"));
            assertEquals("2023-06-01", headers.get("anthropic-version"));
            assertFalse(headers.containsKey("Authorization"), "the key is never sent as a bearer");
            assertFalse(url.contains("sk-ant-secret"));
            return urls.size() == 1
                    ? "{\"data\":[{\"id\":\"claude-opus-5-5\"," + CAPS_ALL_BUT_XHIGH + "},"
                    + "{\"id\":\"claude-haiku-4-5\",\"capabilities\":{\"effort\":{\"supported\":false}}},"
                    + "{\"id\":\"claude-future\"}],\"has_more\":true,\"last_id\":\"claude-future\"}"
                    : "{\"data\":[{\"id\":\"claude-haiku-5-5\",\"capabilities\":{\"effort\":{\"supported\":true,"
                    + "\"low\":{\"supported\":true},\"medium\":{\"supported\":true},\"high\":{\"supported\":true},"
                    + "\"xhigh\":{\"supported\":true},\"max\":{\"supported\":true}}}}],\"has_more\":false}";
        }, memory);

        assertEquals(List.of("claude-future", "claude-haiku-4-5", "claude-haiku-5-5", "claude-opus-5-5"), models);
        assertEquals(List.of("https://api.anthropic.com/v1/models?limit=1000",
                "https://api.anthropic.com/v1/models?limit=1000&after_id=claude-future"), urls);
        assertEquals(List.of("low", "medium", "high", "max"), memory.effortLevels("claude-opus-5-5"));
        assertEquals(List.of(), memory.effortLevels("claude-haiku-4-5"), "an explicit no is an empty list");
        assertNull(memory.effortLevels("claude-future"), "no capabilities means unknown, not unsupported");
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), memory.effortLevels("CLAUDE-HAIKU-5-5"));
    }

    @Test void anthropicCatalogAcceptsAPastedApiPathAndKeepsTheOtherProvidersMemoryUntouched() throws Exception {
        List<String> urls = new ArrayList<>();
        AiFeatureMemory memory = new AiFeatureMemory();
        for (String base : List.of("https://api.anthropic.com/v1", "https://api.anthropic.com/v1/messages", "https://api.anthropic.com/")) {
            ApiModelCatalog.load(base, List.of("k"), (url, headers) -> { urls.add(url); return "{\"data\":[{\"id\":\"claude-x\"}]}"; }, memory);
        }
        assertEquals(List.of("https://api.anthropic.com/v1/models?limit=1000", "https://api.anthropic.com/v1/models?limit=1000",
                "https://api.anthropic.com/v1/models?limit=1000"), urls);
        ApiModelCatalog.load("https://api.openai.com/v1", List.of("k"),
                (url, headers) -> "{\"data\":[{\"id\":\"gpt-x\"}]}", memory);
        assertNull(memory.effortLevels("gpt-x"));
    }

    @Test void anthropicCatalogReadsEffortLevelsPerModelAndTreatsOddShapesAsUnknown() throws Exception {
        List<ApiModelCatalog.Model> models = ApiModelCatalog.loadModels("https://api.anthropic.com", List.of("k"), (url, headers) ->
                "{\"data\":[{\"id\":\"a\"," + CAPS_ALL_BUT_XHIGH + "},"
                        + "{\"id\":\"b\",\"capabilities\":{\"effort\":{\"supported\":\"yes\"}}},"
                        + "{\"id\":\"c\",\"capabilities\":null},"
                        + "{\"id\":\"d\",\"capabilities\":{\"effort\":{\"supported\":true}}}]}");
        assertEquals(List.of("a", "b", "c", "d"), models.stream().map(m -> m.id).toList());
        assertEquals(List.of("low", "medium", "high", "max"), models.get(0).effortLevels);
        assertNull(models.get(1).effortLevels);
        assertNull(models.get(2).effortLevels);
        assertNull(models.get(3).effortLevels, "supported but no level listed: unknown rather than a false no");
        // other providers never report levels
        assertNull(ApiModelCatalog.loadModels("https://api.openai.com/v1", List.of(), (url, headers) ->
                "{\"data\":[{\"id\":\"gpt-x\"}]}").get(0).effortLevels);
    }

    @Test void anthropicCatalogReadsEachModelsOutputLimitAndOnlyAnthropicEntriesHaveOne() throws Exception {
        List<ApiModelCatalog.Model> models = ApiModelCatalog.loadModels("https://api.anthropic.com", List.of("k"), (url, headers) ->
                "{\"data\":[{\"id\":\"a\",\"max_tokens\":128000},{\"id\":\"b\",\"max_tokens\":8192},"
                        + "{\"id\":\"c\"},{\"id\":\"d\",\"max_tokens\":\"lots\"},{\"id\":\"e\",\"max_tokens\":0},"
                        + "{\"id\":\"f\",\"max_tokens\":-5},{\"id\":\"g\",\"max_tokens\":99999999999},"
                        + "{\"id\":\"h\",\"max_tokens\":null},{\"id\":\"i\",\"max_tokens\":4096.0}]}");
        assertEquals(List.of(128000, 8192, 0, 0, 0, 0, 0, 0, 4096), models.stream().map(m -> m.maxTokens).toList());
        assertEquals(0, ApiModelCatalog.loadModels("https://api.openai.com/v1", List.of(), (url, headers) ->
                "{\"data\":[{\"id\":\"gpt-x\",\"max_tokens\":1000}]}").get(0).maxTokens);

        AiFeatureMemory memory = new AiFeatureMemory();
        ApiModelCatalog.load("https://api.anthropic.com", List.of("k"), (url, headers) ->
                "{\"data\":[{\"id\":\"claude-old\",\"max_tokens\":8192},{\"id\":\"claude-new\",\"max_tokens\":64000},"
                        + "{\"id\":\"claude-unsaid\"}]}", memory);
        assertEquals(8192, memory.maxOutputTokens("claude-old"));
        assertEquals(8192, memory.maxOutputTokens("CLAUDE-OLD"), "ids are not case sensitive");
        assertEquals(64000, memory.maxOutputTokens("claude-new"));
        assertEquals(0, memory.maxOutputTokens("claude-unsaid"), "a catalog that does not say leaves it unknown");
        assertEquals(0, memory.maxOutputTokens("never-listed"));
        // a later catalog without the number does not erase what an earlier one said
        ApiModelCatalog.load("https://api.anthropic.com", List.of("k"), (url, headers) ->
                "{\"data\":[{\"id\":\"claude-old\"}]}", memory);
        assertEquals(8192, memory.maxOutputTokens("claude-old"));
        memory.recordMaxOutputTokens("claude-old", 0);
        assertEquals(8192, memory.maxOutputTokens("claude-old"), "a non-positive limit is ignored");
    }

    @Test void anthropicCatalogPaginationGuardsAndErrorsNeverEchoTheKey() throws Exception {
        assertThrows(IOException.class, () -> ApiModelCatalog.load("https://api.anthropic.com", List.of("k"),
                (url, headers) -> "{\"data\":[],\"has_more\":true,\"last_id\":\"same\"}"));
        assertThrows(IOException.class, () -> ApiModelCatalog.load("https://api.anthropic.com", List.of("k"),
                (url, headers) -> "{\"data\":[],\"has_more\":true}"));
        IOException failure = assertThrows(IOException.class, () -> ApiModelCatalog.load("https://api.anthropic.com",
                List.of("sk-ant-secret"), (url, headers) -> { throw new IOException("Model catalog: HTTP 401"); }));
        assertFalse(failure.getMessage().contains("sk-ant-secret"));
        // a key-less request is still sent, so the service can answer with its own 401
        assertEquals(List.of("claude-x"), ApiModelCatalog.load("https://api.anthropic.com", List.of(), (url, headers) -> {
            assertTrue(headers.isEmpty());
            return "{\"data\":[{\"id\":\"claude-x\"}]}";
        }, new AiFeatureMemory()));
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

    /** The capabilities of four real listings (Anthropic /v1/models, 2026-10-29), cut down to what matters here. */
    private static final String REAL_LISTING = "{\"data\":["
            + "{\"type\":\"model\",\"id\":\"claude-haiku-5-5\",\"max_tokens\":128000,\"capabilities\":{"
            + "\"effort\":{\"supported\":true,\"low\":{\"supported\":true},\"medium\":{\"supported\":true},\"high\":{\"supported\":true},"
            + "\"xhigh\":{\"supported\":true},\"max\":{\"supported\":true}},"
            + "\"thinking\":{\"supported\":true,\"types\":{\"enabled\":{\"supported\":false},\"adaptive\":{\"supported\":true},"
            + "\"disabled\":{\"supported\":true},\"between_tools\":{\"supported\":false}}}}},"
            + "{\"type\":\"model\",\"id\":\"claude-sonnet-5-5\",\"max_tokens\":128000,\"capabilities\":{"
            + "\"thinking\":{\"supported\":true,\"types\":{\"enabled\":{\"supported\":false},\"adaptive\":{\"supported\":true},"
            + "\"disabled\":{\"supported\":false},\"between_tools\":{\"supported\":true}}}}},"
            + "{\"type\":\"model\",\"id\":\"claude-opus-5-5\",\"max_tokens\":128000,\"capabilities\":{"
            + "\"thinking\":{\"supported\":true,\"types\":{\"enabled\":{\"supported\":false},\"adaptive\":{\"supported\":true},"
            + "\"disabled\":{\"supported\":false},\"between_tools\":{\"supported\":false}}}}},"
            + "{\"type\":\"model\",\"id\":\"claude-haiku-4-5-20251001\",\"max_tokens\":64000,\"capabilities\":{"
            + "\"effort\":{\"supported\":false,\"low\":{\"supported\":false}},"
            + "\"thinking\":{\"supported\":true,\"types\":{\"enabled\":{\"supported\":true},\"adaptive\":{\"supported\":false},"
            + "\"disabled\":{\"supported\":true},\"between_tools\":{\"supported\":false}}}}}"
            + "],\"has_more\":false}";

    @Test void anthropicCatalogRecordsWhichThinkingTypesEachModelAccepts() throws Exception {
        AiFeatureMemory memory = new AiFeatureMemory();
        ApiModelCatalog.load("https://api.anthropic.com", List.of("k"), (url, headers) -> REAL_LISTING, memory);

        assertEquals(List.of("adaptive", "disabled"), memory.thinkingTypes("claude-haiku-5-5"));
        assertEquals(List.of("adaptive", "between_tools"), memory.thinkingTypes("claude-sonnet-5-5"));
        assertEquals(List.of("adaptive"), memory.thinkingTypes("claude-opus-5-5"));
        assertEquals(List.of("enabled", "disabled"), memory.thinkingTypes("claude-haiku-4-5-20251001"));
        // what the request layer then does with each of them
        assertEquals("disabled", AnthropicWire.offThinkingType(memory.thinkingTypes("claude-haiku-5-5")));
        assertEquals("between_tools", AnthropicWire.offThinkingType(memory.thinkingTypes("claude-sonnet-5-5")));
        assertNull(AnthropicWire.offThinkingType(memory.thinkingTypes("claude-opus-5-5")));
        assertEquals("disabled", AnthropicWire.offThinkingType(memory.thinkingTypes("claude-haiku-4-5-20251001")));
        assertNull(memory.thinkingTypes("claude-unlisted"));
    }

    @Test void anthropicCatalogTreatsOddThinkingShapesAsUnknownAndAnExplicitNoAsNone() throws Exception {
        List<ApiModelCatalog.Model> models = ApiModelCatalog.loadModels("https://api.anthropic.com", List.of("k"), (url, headers) ->
                "{\"data\":[{\"id\":\"a\"},"
                        + "{\"id\":\"b\",\"capabilities\":{\"thinking\":{\"supported\":false}}},"
                        + "{\"id\":\"c\",\"capabilities\":{\"thinking\":{\"supported\":true,\"types\":{}}}},"
                        + "{\"id\":\"d\",\"capabilities\":{\"thinking\":{\"supported\":true}}},"
                        + "{\"id\":\"e\",\"capabilities\":{\"thinking\":{\"types\":{\"disabled\":{\"supported\":\"yes\"},\"adaptive\":5}}}},"
                        + "{\"id\":\"f\",\"capabilities\":{\"thinking\":null}}]}");
        assertNull(models.get(0).thinkingTypes, "no capabilities");
        assertEquals(List.of(), models.get(1).thinkingTypes, "an explicit no thinking is an empty list");
        assertNull(models.get(2).thinkingTypes, "an empty types object says nothing");
        assertNull(models.get(3).thinkingTypes, "supported without a type list says nothing");
        assertNull(models.get(4).thinkingTypes, "unreadable flags say nothing");
        assertNull(models.get(5).thinkingTypes);
        // other providers never report thinking types
        assertNull(ApiModelCatalog.loadModels("https://api.openai.com/v1", List.of(), (url, headers) ->
                "{\"data\":[{\"id\":\"gpt-x\",\"capabilities\":{\"thinking\":{\"types\":{\"disabled\":{\"supported\":true}}}}}]}")
                .get(0).thinkingTypes);
    }

    @Test void deepSeekCatalogRecordsTheEffortLevelsEachModelTakes() throws Exception {
        List<String> urls = new ArrayList<>();
        AiFeatureMemory memory = new AiFeatureMemory();
        List<String> ids = ApiModelCatalog.load("https://api.deepseek.com", List.of("sk-ds-secret"), (url, headers) -> {
            urls.add(url);
            assertEquals("Bearer sk-ds-secret", headers.get("Authorization"));
            return "{\"object\":\"list\",\"data\":["
                    + "{\"id\":\"deepseek-flash\",\"object\":\"model\",\"owned_by\":\"deepseek\","
                    + "\"effort\":{\"supported_levels\":[\"low\",\"high\",\"max\"],\"default_level\":\"high\"}},"
                    + "{\"id\":\"deepseek-small\",\"effort\":{\"supported_levels\":[\"LOW\",\"high\",\"high\",7,\"two words\"]}},"
                    + "{\"id\":\"deepseek-plain\"},"
                    + "{\"id\":\"deepseek-none\",\"effort\":{\"supported_levels\":[]}},"
                    + "{\"id\":\"deepseek-odd\",\"effort\":{\"supported_levels\":\"low\"}}]}";
        }, memory);

        assertEquals(List.of("https://api.deepseek.com/models"), urls);
        assertEquals(List.of("deepseek-flash", "deepseek-none", "deepseek-odd", "deepseek-plain", "deepseek-small"), ids);
        assertEquals(List.of("low", "high", "max"), memory.effortLevels("deepseek-flash"));
        assertEquals(List.of("low", "high"), memory.effortLevels("deepseek-small"), "case folded, duplicates and junk dropped");
        assertEquals(List.of(), memory.effortLevels("deepseek-none"), "an empty list is a real answer");
        assertNull(memory.effortLevels("deepseek-plain"));
        assertNull(memory.effortLevels("deepseek-odd"));
        assertNull(memory.thinkingTypes("deepseek-flash"), "that is an Anthropic notion");

        // another service's entry with the same shape is none of our business
        AiFeatureMemory other = new AiFeatureMemory();
        ApiModelCatalog.load("https://api.openai.com/v1", List.of("k"), (url, headers) ->
                "{\"data\":[{\"id\":\"gpt-x\",\"effort\":{\"supported_levels\":[\"low\"]}}]}", other);
        assertNull(other.effortLevels("gpt-x"));
        // a look-alike host is not DeepSeek
        assertNull(ApiModelCatalog.loadModels("https://api.deepseek.com.evil.test", List.of(), (url, headers) ->
                "{\"data\":[{\"id\":\"m\",\"effort\":{\"supported_levels\":[\"low\"]}}]}").get(0).effortLevels);
    }
}
