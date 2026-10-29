package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
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

    @Test void sixChoicesStartAtMediumAndRetainExplicitSelections() {
        assertEquals(List.of("off", "low", "medium", "high", "xhigh", "max"), ReasoningEffort.VALUES);
        for (String url : Arrays.asList(OPENAI, GEMINI, DEEPSEEK, CLAUDE, CUSTOM, "", null)) {
            assertEquals("medium", ReasoningEffort.defaultFor(url));
            assertEquals("medium", ReasoningEffort.resolve(url, null));
            assertEquals("medium", ReasoningEffort.resolve(url, "invalid"));
            assertEquals("off", ReasoningEffort.resolve(url, "OFF"));
            assertEquals("high", ReasoningEffort.resolve(url, " high "));
        }
    }

    @Test void retiredChoicesMigrateAndInvalidValuesDoNotBecomeMenuEntries() {
        assertEquals("medium", ReasoningEffort.normalize("default"));
        assertEquals("low", ReasoningEffort.normalize("Minimal"));
        for (String value : ReasoningEffort.VALUES) assertEquals(value, ReasoningEffort.normalize(value));
        for (String bad : Arrays.asList(null, "", "none", "ultra", "maximum")) assertNull(ReasoningEffort.normalize(bad));
    }

    @Test void openAiAndCustomSendMediumUnlessAnExplicitChoiceWasSaved() {
        for (String url : List.of(OPENAI, CUSTOM)) {
            assertEquals("{\"reasoning_effort\":\"medium\"}", body(url, "m", null, null));
            assertEquals("{\"reasoning_effort\":\"medium\"}", body(url, "m", "default", null));
            assertEquals("{\"reasoning_effort\":\"none\"}", body(url, "m", "off", null));
            assertEquals("{\"reasoning_effort\":\"xhigh\"}", body(url, "m", "xhigh", null));
            assertEquals("{\"reasoning_effort\":\"max\"}", body(url, "m", "max", null));
        }
    }

    @Test void providersWithFewerLevelsClampAtTheRequestBoundary() {
        assertEquals("{\"reasoning_effort\":\"medium\"}", body(GEMINI, "m", null, null));
        assertEquals("{\"reasoning_effort\":\"high\"}", body(GEMINI, "m", "max", null));
        assertEquals("{\"reasoning_effort\":\"high\"}", body(GEMINI, "m", "xhigh", null));
        assertEquals("{\"reasoning_effort\":\"low\"}", body(DEEPSEEK, "m", "medium", null));
        assertEquals("{\"reasoning_effort\":\"high\"}", body(DEEPSEEK, "m", "xhigh", null));
        assertEquals("{\"reasoning_effort\":\"max\"}", body(DEEPSEEK, "m", "max", null));
    }

    @Test void catalogCapabilitiesOverrideFallbackLevelsWithoutChangingTheSavedChoice() {
        AiFeatureMemory memory = new AiFeatureMemory();
        memory.recordEffortLevels("new-deepseek", List.of("low", "medium", "high"));
        assertEquals("{\"reasoning_effort\":\"medium\"}", body(DEEPSEEK, "new-deepseek", null, memory));
        memory.recordEffortLevels("small", List.of("low", "high"));
        assertEquals("{\"reasoning_effort\":\"high\"}", body(OPENAI, "small", "max", memory));
        assertEquals("max", ReasoningEffort.resolve(OPENAI, "max"));
        memory.recordEffortLevels("none", List.of());
        assertEquals("{}", body(OPENAI, "none", "medium", memory));
    }

    @Test void offRemainsAnExplicitProviderSpecificRequest() {
        assertEquals("{\"thinking\":{\"type\":\"disabled\"}}", body(DEEPSEEK, "m", "off", null));
        assertEquals("{\"reasoning_effort\":\"none\"}", body(GEMINI, "m", "off", null));
    }

    @Test void nativeClaudeAndCliTransportsKeepTheirOwnRequestBuilders() {
        for (String url : Arrays.asList(CLAUDE, "codex://app-server", "antigravity://cli", "", null)) {
            for (String chosen : ReasoningEffort.VALUES) assertEquals("{}", body(url, "m", chosen, null));
        }
    }

    @Test void refusedFieldIsNotRetriedForTheSameModelButOtherModelsKeepTheirSetting() {
        AiFeatureMemory memory = new AiFeatureMemory();
        memory.markUnsupported("old", AnthropicWire.Feature.REASONING_EFFORT);
        assertEquals("{}", body(OPENAI, "OLD", "medium", memory));
        assertEquals("{}", body(DEEPSEEK, "old", "off", memory));
        assertEquals("{\"reasoning_effort\":\"medium\"}", body(OPENAI, "new", "medium", memory));
    }
}
