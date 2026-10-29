package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningEffortTest {
    private static final String OPENAI = "https://api.openai.com/v1";
    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String DEEPSEEK = "https://api.deepseek.com";
    private static final String CLAUDE = "https://api.anthropic.com";
    private static final String CUSTOM = "http://127.0.0.1:11434/v1";

    private static String body(String url, String model, String chosen, AiFeatureMemory memory) {
        JsonObject root = new JsonObject();
        boolean added = ReasoningEffort.addToChatBody(root, url, model, chosen, memory);
        assertEquals(added, root.size() > 0, root.toString());
        return root.toString();
    }

    @Test void everyServiceStartsWhereItDidBeforeTheSettingExisted() {
        assertEquals("medium", ReasoningEffort.defaultFor(CLAUDE));
        assertEquals("medium", ReasoningEffort.defaultFor("https://api.anthropic.com/v1"));
        assertEquals("off", ReasoningEffort.defaultFor(GEMINI));
        assertEquals("off", ReasoningEffort.defaultFor(DEEPSEEK));
        assertEquals("default", ReasoningEffort.defaultFor(OPENAI));
        assertEquals("default", ReasoningEffort.defaultFor(CUSTOM));
        assertEquals("default", ReasoningEffort.defaultFor("https://proxy.example/v1"));
        assertEquals("default", ReasoningEffort.defaultFor("codex://app-server"));
        assertEquals("default", ReasoningEffort.defaultFor("antigravity://cli"));
        assertEquals("default", ReasoningEffort.defaultFor(""));
        assertEquals("default", ReasoningEffort.defaultFor(null));
    }

    @Test void onlyTheDocumentedSettingsAreValidAndTheyAreCaseAndSpaceInsensitive() {
        assertEquals(List.of("default", "off", "minimal", "low", "medium", "high", "xhigh", "max"), ReasoningEffort.VALUES);
        for (String value : ReasoningEffort.VALUES) assertEquals(value, ReasoningEffort.normalize(value));
        assertEquals("high", ReasoningEffort.normalize("  HIGH "));
        assertEquals("off", ReasoningEffort.normalize("Off"));
        for (String bad : new String[] {null, "", " ", "none", "ultra", "maximum", "low high"}) {
            assertNull(ReasoningEffort.normalize(bad), String.valueOf(bad));
        }
        assertEquals("high", ReasoningEffort.resolve(OPENAI, "high"));
        assertEquals("default", ReasoningEffort.resolve(OPENAI, "ultra"));
        assertEquals("off", ReasoningEffort.resolve(GEMINI, null));
        assertEquals("medium", ReasoningEffort.resolve(CLAUDE, ""));
    }

    @Test void menusListEachApisOwnValuesLowestThinkingFirst() {
        AiFeatureMemory none = new AiFeatureMemory();
        assertEquals(List.of("default", "off", "low", "medium", "high", "xhigh", "max"),
                ReasoningEffort.menu(OPENAI, "gpt-x", none));
        assertEquals(List.of("default", "off", "minimal", "low", "medium", "high"),
                ReasoningEffort.menu(GEMINI, "gemini-x", none));
        assertEquals(List.of("default", "off", "low", "high", "max"), ReasoningEffort.menu(DEEPSEEK, "deepseek-flash", none),
                "levels the model list has not confirmed yet: the documented ones");
        assertEquals(List.of("off", "low", "medium", "high", "xhigh", "max"), ReasoningEffort.menu(CLAUDE, "m", none));
        assertEquals(ReasoningEffort.menu(OPENAI, "x", none), ReasoningEffort.menu(CUSTOM, "x", none),
                "a custom endpoint is offered the OpenAI list");
        assertEquals(ReasoningEffort.menu(OPENAI, "x", none), ReasoningEffort.menu("https://proxy.example/v1", "x", none));
        assertEquals(ReasoningEffort.menu(OPENAI, "x", null), ReasoningEffort.menu(OPENAI, "x", none), "no memory at all is fine");
    }

    @Test void deepSeekAndClaudeMenusFollowWhatTheirModelListSaid() {
        AiFeatureMemory memory = new AiFeatureMemory();
        memory.recordEffortLevels("deepseek-small", List.of("low", "high"));
        memory.recordEffortLevels("deepseek-odd", List.of("low", "ultra", "max", "default", "off"));
        memory.recordEffortLevels("deepseek-none", List.of());
        assertEquals(List.of("default", "off", "low", "high"), ReasoningEffort.menu(DEEPSEEK, "DeepSeek-Small", memory));
        assertEquals(List.of("default", "off", "low", "max"), ReasoningEffort.menu(DEEPSEEK, "deepseek-odd", memory),
                "values that are not settings are never offered, and the two special ones are not doubled");
        assertEquals(List.of("default", "off"), ReasoningEffort.menu(DEEPSEEK, "deepseek-none", memory));

        memory.recordEffortLevels("claude-a", List.of("low", "medium", "high", "max"));
        memory.recordEffortLevels("claude-b", List.of());
        assertEquals(List.of("off", "low", "medium", "high", "max"), ReasoningEffort.menu(CLAUDE, "claude-a", memory));
        assertEquals(List.of("off"), ReasoningEffort.menu(CLAUDE, "claude-b", memory),
                "a model without an effort setting can still be asked for less thinking");
        // other services ignore the catalog memory: their menus are fixed API enumerations
        assertEquals(ReasoningEffort.menu(OPENAI, "x", new AiFeatureMemory()), ReasoningEffort.menu(OPENAI, "claude-a", memory));
        for (String url : List.of(OPENAI, GEMINI, DEEPSEEK, CLAUDE, CUSTOM)) {
            for (String value : ReasoningEffort.menu(url, "claude-a", memory)) assertTrue(ReasoningEffort.VALUES.contains(value), value);
        }
    }

    @Test void openAiGeminiAndCustomSendReasoningEffortWithOffSpelledNone() {
        for (String url : List.of(OPENAI, GEMINI, CUSTOM, "https://proxy.example/v1")) {
            AiFeatureMemory memory = new AiFeatureMemory();
            assertEquals("{\"reasoning_effort\":\"none\"}", body(url, "m", "off", memory), url);
            assertEquals("{\"reasoning_effort\":\"high\"}", body(url, "m", "high", memory), url);
            assertEquals("{\"reasoning_effort\":\"minimal\"}", body(url, "m", "minimal", memory), url);
            assertEquals("{\"reasoning_effort\":\"xhigh\"}", body(url, "m", "xhigh", memory), url);
            assertEquals("{}", body(url, "m", "default", memory), url);
        }
        // nothing chosen means the service default
        assertEquals("{\"reasoning_effort\":\"none\"}", body(GEMINI, "m", null, new AiFeatureMemory()));
        assertEquals("{}", body(OPENAI, "m", null, new AiFeatureMemory()));
        assertEquals("{}", body(CUSTOM, "m", "bogus", new AiFeatureMemory()));
    }

    @Test void deepSeekSendsLevelsAsReasoningEffortAndOffAsTheThinkingSwitch() {
        AiFeatureMemory memory = new AiFeatureMemory();
        assertEquals("{\"thinking\":{\"type\":\"disabled\"}}", body(DEEPSEEK, "m", "off", memory));
        assertEquals("{\"thinking\":{\"type\":\"disabled\"}}", body(DEEPSEEK, "m", null, memory), "off is its default");
        assertEquals("{\"reasoning_effort\":\"low\"}", body(DEEPSEEK, "m", "low", memory));
        assertEquals("{\"reasoning_effort\":\"high\"}", body(DEEPSEEK, "m", "high", memory));
        assertEquals("{\"reasoning_effort\":\"max\"}", body(DEEPSEEK, "m", "max", memory));
        assertEquals("{}", body(DEEPSEEK, "m", "default", memory));
        JsonObject parsed = new JsonParser().parse(body(DEEPSEEK, "m", "off", memory)).getAsJsonObject();
        assertFalse(parsed.has("reasoning_effort"), "DeepSeek's reasoning_effort has no off value");
    }

    @Test void claudeAndTheLocalClientsAreNotHandledHere() {
        AiFeatureMemory memory = new AiFeatureMemory();
        for (String url : java.util.Arrays.asList(CLAUDE, "https://api.anthropic.com/v1", "codex://app-server", "antigravity://cli", "", null)) {
            for (String chosen : List.of("off", "high", "default")) {
                assertEquals("{}", body(url, "m", chosen, memory), url + " " + chosen);
            }
        }
    }

    @Test void aFieldTheModelRefusedIsNotSentAgainButOtherModelsStillGetIt() {
        AiFeatureMemory memory = new AiFeatureMemory();
        memory.markUnsupported("gpt-old", AnthropicWire.Feature.REASONING_EFFORT);
        assertEquals("{}", body(OPENAI, "gpt-old", "high", memory));
        assertEquals("{}", body(OPENAI, "GPT-OLD", "off", memory));
        assertEquals("{}", body(DEEPSEEK, "gpt-old", "off", memory));
        assertEquals("{\"reasoning_effort\":\"high\"}", body(OPENAI, "gpt-new", "high", memory));
        // refusing the thinking object of the Claude route is a separate matter
        AiFeatureMemory claudeOnly = new AiFeatureMemory();
        claudeOnly.markUnsupported("m", AnthropicWire.Feature.THINKING);
        assertEquals("{\"reasoning_effort\":\"high\"}", body(OPENAI, "m", "high", claudeOnly));
    }
}
