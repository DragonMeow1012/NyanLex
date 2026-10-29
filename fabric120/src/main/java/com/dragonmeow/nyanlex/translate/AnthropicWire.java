package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Anthropic Messages API conversion: request bodies, replies, error classification.
 *
 * <p>Java 8 and old-Gson compatible on purpose (no records, {@code var}, {@code List.of},
 * {@code JsonParser.parseString} ...): the Java 8 loaders copy this file verbatim. No I/O,
 * no clock, no key handling — callers own the transport and the credentials.</p>
 */
public final class AnthropicWire {
    private AnthropicWire() { }

    public static final String VERSION = "2023-06-01";
    public static final String BETA_FAST = "fast-mode-2026-02-01";
    public static final String BETA_FALLBACKS = "server-side-fallback-2026-07-01";
    /** Output ceiling asked for unless the model's catalog entry names a lower limit. */
    public static final int MAX_TOKENS = 16000;
    /** The only model id this module hard-codes; every other model comes from the user's catalog. */
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";
    public static final String DEFAULT_EFFORT = "medium";
    /** The chosen-effort value that asks for as little thinking as the model allows. */
    public static final String EFFORT_OFF = "off";
    /** The effort sent alongside a thinking-off request: thinking cannot always be removed, so also keep it light. */
    public static final String EFFORT_WHEN_OFF = "low";

    /** The five effort levels the API accepts, lowest first. Not a model list. */
    public static final List<String> EFFORT_LEVELS = Collections.unmodifiableList(
            Arrays.asList("low", "medium", "high", "xhigh", "max"));

    private static final long RETRY_AFTER_MIN_MS = 1_000L;
    private static final long RETRY_AFTER_MAX_MS = 600_000L;

    /** The {@code thinking.type} values that can switch thinking down; see {@link #offThinkingType}. */
    public static final String THINKING_DISABLED = "disabled";
    public static final String THINKING_BETWEEN_TOOLS = "between_tools";
    public static final String THINKING_ADAPTIVE = "adaptive";

    /**
     * Optional request features that only some models accept; each can be dropped on its own.
     * {@code THINKING} is the Claude {@code thinking} object; {@code REASONING_EFFORT} is the
     * {@code reasoning_effort} field of OpenAI-compatible endpoints (OpenAI, Gemini, DeepSeek, custom).
     */
    public enum Feature { EFFORT, FAST, FALLBACKS, THINKING, REASONING_EFFORT }

    /** User-chosen request options, read fresh for every request. */
    public static final class Options {
        public final String effort;
        public final boolean fast;

        public Options(String effort, boolean fast) {
            this.effort = effort;
            this.fast = fast;
        }
    }

    /** A request body plus the comma-joined {@code anthropic-beta} value ({@code null} when none). */
    public static final class Request {
        public final String body;
        public final String beta;

        Request(String body, String beta) {
            this.body = body;
            this.beta = beta;
        }
    }

    /** A successful reply: the concatenated text blocks and the raw {@code usage} object. */
    public static final class Reply {
        public final String text;
        public final JsonObject usage;

        Reply(String text, JsonObject usage) {
            this.text = text;
            this.usage = usage;
        }
    }

    /** The model declined to answer, or its answer was cut short; never a usable translation. */
    public static final class RejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        public enum Reason { REFUSAL, TRUNCATED }

        private final Reason reason;

        RejectedException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    // ---- URLs ----

    public static String messagesUrl(String base) {
        return root(base) + "/v1/messages";
    }

    public static String modelsUrl(String base) {
        return root(base) + "/v1/models";
    }

    /** The base URL without a trailing slash and without any API path the user pasted along. */
    private static String root(String base) {
        String value = trimSlashes(base == null ? "" : base.trim());
        String[] paths = {"/chat/completions", "/v1/messages", "/v1/models", "/v1"};
        for (String path : paths) {
            if (value.endsWith(path)) value = trimSlashes(value.substring(0, value.length() - path.length()));
        }
        return value;
    }

    private static String trimSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }

    // ---- request ----

    /**
     * Build a Messages request. Only the fields below are ever sent: no sampling parameters
     * or assistant prefill, and a {@code thinking} object only when asked for. {@code effort}
     * may be {@code null} or empty to omit it.
     */
    public static Request build(String model, String system, String user, String effort,
                                boolean fast, boolean fallbacks) {
        return build(model, system, user, effort, fast, fallbacks, 0);
    }

    /** {@code modelMaxTokens} is the model's own output limit from the catalog, 0 when unknown. */
    public static Request build(String model, String system, String user, String effort,
                                boolean fast, boolean fallbacks, int modelMaxTokens) {
        return build(model, system, user, effort, null, fast, fallbacks, modelMaxTokens);
    }

    /**
     * {@code thinkingType} is the value of {@code thinking.type} to send ({@code null} or empty
     * sends no {@code thinking} object); the object never carries any other field.
     */
    public static Request build(String model, String system, String user, String effort, String thinkingType,
                                boolean fast, boolean fallbacks, int modelMaxTokens) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("max_tokens", maxTokens(modelMaxTokens));
        root.addProperty("system", system);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", user);
        JsonArray messages = new JsonArray();
        messages.add(message);
        root.add("messages", messages);
        if (thinkingType != null && !thinkingType.trim().isEmpty()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", thinkingType.trim());
            root.add("thinking", thinking);
        }
        if (effort != null && !effort.trim().isEmpty()) {
            JsonObject output = new JsonObject();
            output.addProperty("effort", effort.trim());
            root.add("output_config", output);
        }
        StringBuilder beta = new StringBuilder();
        if (fast) {
            root.addProperty("speed", "fast");
            beta.append(BETA_FAST);
        }
        if (fallbacks) {
            root.addProperty("fallbacks", "default");
            if (beta.length() > 0) beta.append(',');
            beta.append(BETA_FALLBACKS);
        }
        return new Request(root.toString(), beta.length() == 0 ? null : beta.toString());
    }

    /** The {@code max_tokens} to send: {@link #MAX_TOKENS}, or the model's own limit when that is lower. */
    public static int maxTokens(int modelMaxTokens) {
        return modelMaxTokens > 0 ? Math.min(MAX_TOKENS, modelMaxTokens) : MAX_TOKENS;
    }

    // ---- reply ----

    /**
     * Read a Messages reply. Only {@code stop_reason == "end_turn"} counts as success; a
     * refusal or a truncated answer throws {@link RejectedException}. Thinking, fallback and
     * every other non-text block is ignored.
     */
    public static Reply parseReply(String json) throws IOException {
        final JsonObject root;
        try {
            JsonElement parsed = new JsonParser().parse(json == null ? "" : json);
            if (!parsed.isJsonObject()) throw new IOException("bad AI response: not an object");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("bad AI response: " + e.getClass().getSimpleName(), e);
        }
        String stop = string(root, "stop_reason");
        if ("refusal".equals(stop)) {
            throw new RejectedException(RejectedException.Reason.REFUSAL, "the model declined this text (refusal)");
        }
        if ("max_tokens".equals(stop)) {
            throw new RejectedException(RejectedException.Reason.TRUNCATED, "the reply was cut off (max_tokens)");
        }
        if (!"end_turn".equals(stop)) {
            throw new IOException("unexpected stop_reason: " + (stop.length() > 40 ? stop.substring(0, 40) : stop));
        }
        StringBuilder text = new StringBuilder();
        JsonElement content = root.get("content");
        if (content != null && content.isJsonArray()) {
            JsonArray blocks = content.getAsJsonArray();
            for (int i = 0; i < blocks.size(); i++) {
                JsonElement block = blocks.get(i);
                if (!block.isJsonObject()) continue;
                JsonObject object = block.getAsJsonObject();
                if ("text".equals(string(object, "type"))) text.append(string(object, "text"));
            }
        }
        JsonElement usage = root.get("usage");
        return new Reply(text.toString(), usage != null && usage.isJsonObject() ? usage.getAsJsonObject() : null);
    }

    // ---- degradation ----

    private static final Pattern INVALID_REQUEST = Pattern.compile("\"type\"\\s*:\\s*\"invalid_request_error\"");
    private static final Pattern SUPPORTED_LEVELS =
            Pattern.compile("supported levels:\\s*([a-z]+(?:\\s*,\\s*[a-z]+)*)");
    private static final String[] EFFORT_WORDS = {"output_config", "effort"};
    private static final String[] FAST_WORDS = {"speed", "fast-mode", "fast mode"};
    private static final String[] FALLBACK_WORDS = {"fallbacks", "server-side-fallback"};
    private static final String[] REJECT_WORDS = {"extra inputs are not permitted", "not permitted", "not supported",
            "unsupported", "does not support", "unexpected value", "requires a model", "unknown", "unrecognized",
            "not available"};
    private static final String[] NOT_A_FEATURE_PROBLEM = {"spend limit", "credit", "usage limit", "max_tokens",
            "prefill"};
    private static final Pattern THINKING_TYPE = Pattern.compile("thinking\\.type\\.([a-z_]+)");
    private static final String[] THINKING_TYPES = {THINKING_DISABLED, "enabled", THINKING_ADAPTIVE,
            THINKING_BETWEEN_TOOLS};

    /**
     * How the thinking-off request should be shaped, given the {@code thinking.type} values the
     * model is known to accept ({@code null}: never learned). Order of preference: a real
     * {@code disabled}; the {@code between_tools} floor; otherwise {@code null}, meaning send no
     * {@code thinking} object at all (thinking cannot be switched off, effort alone lowers it).
     * With nothing known the first attempt is {@code disabled}.
     */
    public static String offThinkingType(List<String> supportedTypes) {
        if (supportedTypes == null) return THINKING_DISABLED;
        if (supportedTypes.contains(THINKING_DISABLED)) return THINKING_DISABLED;
        if (supportedTypes.contains(THINKING_BETWEEN_TOOLS)) return THINKING_BETWEEN_TOOLS;
        return null;
    }

    /**
     * The {@code thinking.type} values a 400 names when it refuses the thinking object that was
     * sent, in the order they appear (for example {@code "thinking.type.disabled" is not
     * supported for this model. Use "thinking.type.between_tools" for the lowest thinking
     * setting, or "thinking.type.adaptive" ...}: disabled, between_tools, adaptive). The
     * first is what was refused, the rest are what the message recommends instead. {@code null}
     * when the error is anything else.
     */
    public static List<String> thinkingRejection(int status, String bodyText) {
        if (status != 400 || bodyText == null || !INVALID_REQUEST.matcher(bodyText).find()) return null;
        String message = errorMessage(bodyText).toLowerCase(Locale.ROOT);
        if (!message.contains("thinking") || !containsAny(message, REJECT_WORDS)) return null;
        if (containsAny(message, NOT_A_FEATURE_PROBLEM)) return null;
        List<String> types = new ArrayList<String>();
        Matcher matcher = THINKING_TYPE.matcher(message);
        while (matcher.find()) {
            String type = matcher.group(1);
            if (Arrays.asList(THINKING_TYPES).contains(type) && !types.contains(type)) types.add(type);
        }
        return types.isEmpty() ? null : Collections.unmodifiableList(types);
    }

    /**
     * The effort levels a 400 lists as accepted, when what the model refused is the chosen
     * <em>level</em> and not effort itself (for example {@code This model does not support
     * effort level 'xhigh'. Supported levels: high, low, medium.}). {@code null} for any
     * other error, including a level complaint that lists no known level.
     */
    public static List<String> effortLevelsFromError(int status, String bodyText) {
        if (status != 400 || bodyText == null || !INVALID_REQUEST.matcher(bodyText).find()) return null;
        String message = errorMessage(bodyText).toLowerCase(Locale.ROOT);
        if (!message.contains("effort level")) return null;
        Matcher matcher = SUPPORTED_LEVELS.matcher(message);
        if (!matcher.find()) return null;
        List<String> levels = new ArrayList<String>();
        for (String part : matcher.group(1).split(",")) {
            String level = part.trim();
            if (isEffortLevel(level) && !levels.contains(level)) levels.add(level);
        }
        return levels.isEmpty() ? null : Collections.unmodifiableList(levels);
    }

    /**
     * Which of the optional features in {@code sent} a 400 response says the model rejected.
     * Empty when the error is anything else, so an unrelated 400 never switches a feature off.
     * A complaint about one effort level is not a rejected feature either: see
     * {@link #effortLevelsFromError}.
     */
    public static Set<Feature> unsupportedFeatures(int status, String bodyText, Set<Feature> sent) {
        Set<Feature> out = EnumSet.noneOf(Feature.class);
        if (status != 400 || sent == null || sent.isEmpty() || bodyText == null) return out;
        if (!INVALID_REQUEST.matcher(bodyText).find()) return out;
        if (effortLevelsFromError(status, bodyText) != null) return out;
        if (sent.contains(Feature.THINKING) && thinkingRejection(status, bodyText) != null) {
            // The message also names output_config.effort as the replacement, which is not a
            // complaint about effort: only the thinking object is at fault.
            out.add(Feature.THINKING);
            return out;
        }
        String message = errorMessage(bodyText).toLowerCase(Locale.ROOT);
        if (message.contains("thinking.type") || containsAny(message, NOT_A_FEATURE_PROBLEM)
                || !containsAny(message, REJECT_WORDS)) return out;
        if (sent.contains(Feature.EFFORT) && containsAny(message, EFFORT_WORDS)) out.add(Feature.EFFORT);
        if (sent.contains(Feature.FAST) && containsAny(message, FAST_WORDS)) out.add(Feature.FAST);
        if (sent.contains(Feature.FALLBACKS) && containsAny(message, FALLBACK_WORDS)) out.add(Feature.FALLBACKS);
        if (out.isEmpty() && message.contains("anthropic-beta")) {
            // The beta header itself was refused, and both of these features ride on it.
            if (sent.contains(Feature.FAST)) out.add(Feature.FAST);
            if (sent.contains(Feature.FALLBACKS)) out.add(Feature.FALLBACKS);
        }
        return out;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) if (haystack.contains(needle)) return true;
        return false;
    }

    /**
     * The {@code error.message} text by substring scan (the body may be truncated, so it
     * is not parsed as JSON); the whole body when no message field is found.
     */
    private static String errorMessage(String bodyText) {
        int key = bodyText.indexOf("\"message\"");
        if (key < 0) return bodyText;
        int colon = bodyText.indexOf(':', key + 9);
        if (colon < 0) return bodyText;
        int quote = colon + 1;
        while (quote < bodyText.length() && Character.isWhitespace(bodyText.charAt(quote))) quote++;
        if (quote >= bodyText.length() || bodyText.charAt(quote) != '"') return bodyText;
        StringBuilder message = new StringBuilder();
        for (int i = quote + 1; i < bodyText.length(); i++) {
            char c = bodyText.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < bodyText.length()) {
                char next = bodyText.charAt(++i);
                if (next == 'n' || next == 't' || next == 'r') message.append(' ');
                else if (next == 'u' && i + 4 < bodyText.length()) {
                    try {
                        message.append((char) Integer.parseInt(bodyText.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (NumberFormatException ignored) {
                        message.append('u');
                    }
                } else message.append(next);
                continue;
            }
            message.append(c);
        }
        return message.toString();
    }

    // ---- small helpers ----

    /** Header value in whole seconds, as milliseconds clamped to 1 s .. 10 min; 0 when unusable. */
    public static long retryAfterMs(String headerValue) {
        if (headerValue == null) return 0L;
        String value = headerValue.trim();
        if (value.isEmpty() || value.length() > 9) return 0L;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return 0L;
        }
        long ms = Long.parseLong(value) * 1000L;
        return Math.max(RETRY_AFTER_MIN_MS, Math.min(RETRY_AFTER_MAX_MS, ms));
    }

    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP (\\d{3})");

    /** Lang key of the plain-language hint for an exception message such as {@code HTTP 401: ...}, or null. */
    public static String hintKey(String exceptionMessage) {
        if (exceptionMessage == null) return null;
        Matcher matcher = HTTP_STATUS.matcher(exceptionMessage);
        if (!matcher.find()) return null;
        int code = Integer.parseInt(matcher.group(1));
        switch (code) {
            case 401: return "screen.nyanlex.ai.claude.err.401";
            case 402: return "screen.nyanlex.ai.claude.err.402";
            case 403: return "screen.nyanlex.ai.claude.err.403";
            case 404: return "screen.nyanlex.ai.claude.err.404";
            case 429: return "screen.nyanlex.ai.claude.err.429";
            default:
                return code >= 500 && code <= 599 ? "screen.nyanlex.ai.claude.err.529" : null;
        }
    }

    /**
     * The highest level the model supports that is not above {@code selected}; the lowest
     * supported level when all are higher. {@code null} supported levels (catalog never read)
     * keep the selection as it is; an empty list means nothing is supported and returns null.
     */
    public static String clampEffort(String selected, List<String> supportedLevels) {
        if (supportedLevels == null) return selected;
        int wanted = rank(selected);
        int lowest = -1;
        int best = -1;
        for (String level : supportedLevels) {
            int rank = rank(level);
            if (rank < 0) continue;
            if (rank == wanted) return EFFORT_LEVELS.get(rank);
            if (lowest < 0 || rank < lowest) lowest = rank;
            if (wanted >= 0 && rank < wanted && rank > best) best = rank;
        }
        if (best >= 0) return EFFORT_LEVELS.get(best);
        return lowest >= 0 ? EFFORT_LEVELS.get(lowest) : null;
    }

    private static int rank(String level) {
        return EFFORT_LEVELS.indexOf(level == null ? "" : level.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code true} when {@code value} is one of {@link #EFFORT_LEVELS}. */
    public static boolean isEffortLevel(String value) {
        return value != null && EFFORT_LEVELS.contains(value);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
