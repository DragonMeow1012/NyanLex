package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The thinking-strength setting every API provider shares, and how each one spells it on the wire.
 *
 * <p>A setting is one of the six {@link #VALUES}: {@code off} asks for the least thinking the
 * provider allows, followed by five effort levels. Nothing here names a model: the lists below
 * are API enumerations, and what a
 * particular model really accepts is learned from its catalog entry or from a rejected request
 * (see {@link AiFeatureMemory}).</p>
 */
public final class ReasoningEffort {
    private ReasoningEffort() { }

    public static final String DEFAULT_EFFORT = AnthropicWire.DEFAULT_EFFORT;
    public static final String OFF = AnthropicWire.EFFORT_OFF;

    /** Every value a stored setting may hold. */
    public static final List<String> VALUES = Collections.unmodifiableList(Arrays.asList(
            OFF, "low", "medium", "high", "xhigh", "max"));

    private static final List<String> GEMINI_LEVELS = Collections.unmodifiableList(Arrays.asList(
            "low", "medium", "high"));
    /** DeepSeek's documented levels, offered until its model list says which ones a model takes. */
    private static final List<String> DEEPSEEK_LEVELS = Collections.unmodifiableList(Arrays.asList(
            "low", "high", "max"));

    private enum Provider { OPENAI, GEMINI, DEEPSEEK, CLAUDE, CUSTOM, NONE }

    private static Provider providerOf(String baseUrl) {
        if (baseUrl == null) return Provider.NONE;
        if (HttpEndpointPolicy.isAnthropic(baseUrl)) return Provider.CLAUDE;
        if (HttpEndpointPolicy.isGemini(baseUrl)) return Provider.GEMINI;
        if (HttpEndpointPolicy.isDeepSeek(baseUrl)) return Provider.DEEPSEEK;
        if (HttpEndpointPolicy.isOpenAi(baseUrl)) return Provider.OPENAI;
        String lower = baseUrl.trim().toLowerCase(Locale.ROOT);
        // The Codex and Antigravity routes use their own URL schemes and their own effort setting.
        return lower.startsWith("http://") || lower.startsWith("https://") ? Provider.CUSTOM : Provider.NONE;
    }

    /** The canonical form of {@code value} (trimmed, lower case), or null when it is not a valid setting. */
    public static String normalize(String value) {
        if (value == null) return null;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        // Migrate retired menu choices at the existing settings boundary.
        if ("default".equals(lower)) return DEFAULT_EFFORT;
        if ("minimal".equals(lower)) return "low";
        return VALUES.contains(lower) ? lower : null;
    }

    /**
     * Every service starts at medium. An explicit saved choice is retained by resolve.
     */
    public static String defaultFor(String baseUrl) {
        return DEFAULT_EFFORT;
    }

    /** {@code chosen} when it is a valid setting, otherwise the service default. */
    public static String resolve(String baseUrl, String chosen) {
        String value = normalize(chosen);
        return value != null ? value : defaultFor(baseUrl);
    }

    /**
     * Add the field(s) that carry {@code chosen} to an OpenAI-style chat request body and report
     * whether anything was added. Claude is not handled here (its transport builds the whole
     * request) and neither are the CLI routes. A service/model with fewer levels uses the existing
     * effort clamp at this boundary. Nothing is added when the model has refused the field.
     *
     * <ul>
     *   <li>OpenAI, Gemini, custom: {@code "reasoning_effort": level}; off is {@code "none"}.</li>
     *   <li>DeepSeek: {@code "reasoning_effort": level}; off is {@code "thinking":{"type":"disabled"}}
     *       because its {@code reasoning_effort} has no off value.</li>
     * </ul>
     */
    public static boolean addToChatBody(JsonObject body, String baseUrl, String model, String chosen,
                                        AiFeatureMemory memory) {
        Provider provider = providerOf(baseUrl);
        if (provider == Provider.CLAUDE || provider == Provider.NONE) return false;
        String effort = resolve(baseUrl, chosen);
        if (memory != null && memory.isUnsupported(model, AnthropicWire.Feature.REASONING_EFFORT)) return false;
        if (OFF.equals(effort)) {
            if (provider == Provider.DEEPSEEK) {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", "disabled");
                body.add("thinking", thinking);
            } else {
                body.addProperty("reasoning_effort", "none");
            }
        } else {
            List<String> supported = memory == null ? null : memory.effortLevels(model);
            if (supported == null && provider == Provider.GEMINI) supported = GEMINI_LEVELS;
            if (supported == null && provider == Provider.DEEPSEEK) supported = DEEPSEEK_LEVELS;
            effort = AnthropicWire.clampEffort(effort, supported);
            if (effort == null) return false;
            body.addProperty("reasoning_effort", effort);
        }
        return true;
    }
}
