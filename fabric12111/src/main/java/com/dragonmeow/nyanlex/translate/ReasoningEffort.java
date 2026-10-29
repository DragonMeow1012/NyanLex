package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The thinking-strength setting every API provider shares, and how each one spells it on the wire.
 *
 * <p>A setting is one of {@link #VALUES}: {@code default} sends nothing and lets the model decide,
 * {@code off} asks for the least thinking the provider allows, the rest are the provider's own
 * effort levels. Nothing here names a model: the lists below are API enumerations, and what a
 * particular model really accepts is learned from its catalog entry or from a rejected request
 * (see {@link AiFeatureMemory}).</p>
 */
public final class ReasoningEffort {
    private ReasoningEffort() { }

    public static final String DEFAULT = "default";
    public static final String OFF = AnthropicWire.EFFORT_OFF;

    /** Every value a stored setting may hold. */
    public static final List<String> VALUES = Collections.unmodifiableList(Arrays.asList(
            DEFAULT, OFF, "minimal", "low", "medium", "high", "xhigh", "max"));

    private static final List<String> OPENAI_MENU = Collections.unmodifiableList(Arrays.asList(
            DEFAULT, OFF, "low", "medium", "high", "xhigh", "max"));
    private static final List<String> GEMINI_MENU = Collections.unmodifiableList(Arrays.asList(
            DEFAULT, OFF, "minimal", "low", "medium", "high"));
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
        return VALUES.contains(lower) ? lower : null;
    }

    /**
     * What a service uses until the user picks something: Claude {@code medium} (the level its
     * requests were tuned with), Gemini and DeepSeek {@code off} (fast, cheap translation lines
     * gain nothing from thinking), everything else {@code default}.
     */
    public static String defaultFor(String baseUrl) {
        switch (providerOf(baseUrl)) {
            case CLAUDE:
                return AnthropicWire.DEFAULT_EFFORT;
            case GEMINI:
            case DEEPSEEK:
                return OFF;
            default:
                return DEFAULT;
        }
    }

    /** {@code chosen} when it is a valid setting, otherwise the service default. */
    public static String resolve(String baseUrl, String chosen) {
        String value = normalize(chosen);
        return value != null ? value : defaultFor(baseUrl);
    }

    /**
     * The settings to offer for a service, lowest thinking first. {@code memory} may know the
     * levels a model's catalog entry lists (DeepSeek, Claude); without that the documented
     * levels are offered and the request layer falls back if the model refuses one.
     */
    public static List<String> menu(String baseUrl, String model, AiFeatureMemory memory) {
        List<String> known = memory == null ? null : memory.effortLevels(model);
        switch (providerOf(baseUrl)) {
            case GEMINI:
                return GEMINI_MENU;
            case DEEPSEEK:
                return withLevels(Arrays.asList(DEFAULT, OFF), known, DEEPSEEK_LEVELS);
            case CLAUDE:
                return withLevels(Collections.singletonList(OFF), known, AnthropicWire.EFFORT_LEVELS);
            default:
                return OPENAI_MENU;
        }
    }

    private static List<String> withLevels(List<String> head, List<String> known, List<String> documented) {
        List<String> out = new ArrayList<String>(head);
        for (String level : known == null ? documented : known) {
            if (!out.contains(level) && !DEFAULT.equals(level) && VALUES.contains(level)) out.add(level);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Add the field(s) that carry {@code chosen} to an OpenAI-style chat request body and report
     * whether anything was added. Claude is not handled here (its transport builds the whole
     * request) and neither are the CLI routes. Nothing is added for {@code default}, or when the
     * model has already refused the field this session.
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
        if (DEFAULT.equals(effort)) return false;
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
            body.addProperty("reasoning_effort", effort);
        }
        return true;
    }
}
