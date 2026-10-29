package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.translate.AnthropicWire.Feature;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicWireTest {

    private static JsonObject json(String text) {
        return new JsonParser().parse(text).getAsJsonObject();
    }

    @Test void buildHasRequiredShapeAndNothingForbidden() {
        AnthropicWire.Request request = AnthropicWire.build("claude-haiku-5-5", "SYS", "USER", null, false, false);
        JsonObject body = json(request.body);
        assertEquals("claude-haiku-5-5", body.get("model").getAsString());
        assertEquals(16000, body.get("max_tokens").getAsInt());
        assertEquals("SYS", body.get("system").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("USER", messages.get(0).getAsJsonObject().get("content").getAsString());
        for (String forbidden : List.of("thinking", "temperature", "top_p", "top_k", "output_config", "speed", "fallbacks")) {
            assertFalse(body.has(forbidden), forbidden);
        }
        assertNull(request.beta);
    }

    @Test void maxTokensNeverExceedsTheModelsOwnLimit() {
        assertEquals(16000, AnthropicWire.maxTokens(0), "unknown limit: the default");
        assertEquals(16000, AnthropicWire.maxTokens(-1));
        assertEquals(16000, AnthropicWire.maxTokens(128000), "a roomy model still gets the default");
        assertEquals(16000, AnthropicWire.maxTokens(16000));
        assertEquals(8192, AnthropicWire.maxTokens(8192), "a smaller model is not asked for more than it has");
        assertEquals(1, AnthropicWire.maxTokens(1));
        assertEquals(8192, json(AnthropicWire.build("m", "s", "u", null, false, false, 8192).body).get("max_tokens").getAsInt());
        assertEquals(16000, json(AnthropicWire.build("m", "s", "u", null, false, false, 0).body).get("max_tokens").getAsInt());
        assertEquals(16000, json(AnthropicWire.build("m", "s", "u", null, false, false).body).get("max_tokens").getAsInt());
    }

    @Test void optionalFieldsAppearOnlyWhenRequested() {
        JsonObject effort = json(AnthropicWire.build("m", "s", "u", "low", false, false).body);
        assertEquals("low", effort.getAsJsonObject("output_config").get("effort").getAsString());
        assertFalse(effort.has("speed"));
        assertFalse(effort.has("fallbacks"));

        AnthropicWire.Request fast = AnthropicWire.build("m", "s", "u", null, true, false);
        assertEquals("fast", json(fast.body).get("speed").getAsString());
        assertEquals("fast-mode-2026-02-01", fast.beta);

        AnthropicWire.Request fallbacks = AnthropicWire.build("m", "s", "u", null, false, true);
        assertEquals("default", json(fallbacks.body).get("fallbacks").getAsString());
        assertEquals("server-side-fallback-2026-07-01", fallbacks.beta);

        AnthropicWire.Request all = AnthropicWire.build("m", "s", "u", "max", true, true);
        assertEquals("fast-mode-2026-02-01,server-side-fallback-2026-07-01", all.beta);
        JsonObject body = json(all.body);
        assertTrue(body.has("output_config") && body.has("speed") && body.has("fallbacks"));
        assertFalse(body.has("thinking"));
        assertNull(AnthropicWire.build("m", "s", "u", "  ", false, false).beta);
        assertFalse(json(AnthropicWire.build("m", "s", "u", "  ", false, false).body).has("output_config"));
    }

    @Test void parseConcatenatesOnlyTextBlocks() throws Exception {
        String reply = "{\"type\":\"message\",\"stop_reason\":\"end_turn\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"hmm\"},{\"type\":\"text\",\"text\":\"你好\"},"
                + "{\"type\":\"fallback\",\"text\":\"ignored\"},{\"type\":\"text\",\"text\":\"世界\"}],"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":7}}";
        AnthropicWire.Reply parsed = AnthropicWire.parseReply(reply);
        assertEquals("你好世界", parsed.text);
        assertEquals(12, parsed.usage.get("input_tokens").getAsInt());
    }

    @Test void parseRejectsRefusalAndMaxTokens() throws Exception {
        AnthropicWire.RejectedException refusal = assertThrows(AnthropicWire.RejectedException.class,
                () -> AnthropicWire.parseReply("{\"stop_reason\":\"refusal\",\"content\":[{\"type\":\"text\",\"text\":\"no\"}]}"));
        assertEquals(AnthropicWire.RejectedException.Reason.REFUSAL, refusal.reason());
        AnthropicWire.RejectedException cut = assertThrows(AnthropicWire.RejectedException.class,
                () -> AnthropicWire.parseReply("{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}"));
        assertEquals(AnthropicWire.RejectedException.Reason.TRUNCATED, cut.reason());

        IOException unknown = assertThrows(IOException.class,
                () -> AnthropicWire.parseReply("{\"stop_reason\":\"pause_turn\",\"content\":[]}"));
        assertFalse(unknown instanceof AnthropicWire.RejectedException);
        assertTrue(unknown.getMessage().contains("unexpected stop_reason"));
        assertThrows(IOException.class, () -> AnthropicWire.parseReply("{\"content\":[]}"));
        assertThrows(IOException.class, () -> AnthropicWire.parseReply("not json"));
        assertThrows(IOException.class, () -> AnthropicWire.parseReply("[]"));

        assertEquals("", AnthropicWire.parseReply("{\"stop_reason\":\"end_turn\",\"content\":[]}").text);
    }

    // ---- error classification ----

    private static String error(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"},\"request_id\":\"req_1\"}";
    }

    private static Set<Feature> classify(int status, String body, Feature... sent) {
        return AnthropicWire.unsupportedFeatures(status, body, sent.length == 0
                ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(Arrays.asList(sent)));
    }

    private static final Feature[] ALL = {Feature.EFFORT, Feature.FAST, Feature.FALLBACKS};

    /** A body exactly as the live API sent it (recorded 2026-10-29 against api.anthropic.com), request_id included. */
    private static String live(String message, String requestId) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"" + message
                + "\"},\"request_id\":\"" + requestId + "\"}";
    }

    private static final String LIVE_EFFORT_UNSUPPORTED =
            live("This model does not support the effort parameter.", "req_011CgVnK8SfxYbnzw9RscSgj");
    private static final String LIVE_SPEED_UNSUPPORTED = live("'claude-haiku-5-5' does not support the `speed` parameter. "
            + "This feature is only available on supported models.", "req_011CgVnKF5nBqEtxktcj7w8r");
    private static final String LIVE_FALLBACKS_EXTRA_INPUTS =
            live("fallbacks: Extra inputs are not permitted", "req_011CgVnZakb7Sy6dAwX78NuS");
    private static final String LIVE_FAST_BETA_REJECTED = live("Unexpected value(s) `fast-mode-2020-01-01` for the "
            + "`anthropic-beta` header. Please consult our documentation at platform.claude.com/docs or try again "
            + "without the header.", "req_011CgVnZJS5ADcoMuAtdqVWM");
    private static final String LIVE_FALLBACKS_BETA_REJECTED = live("Unexpected value(s) `server-side-fallback-2020-01-01` "
            + "for the `anthropic-beta` header. Please consult our documentation at platform.claude.com/docs or try "
            + "again without the header.", "req_011CgVnZPtjeb2TQf9EtT4ur");
    /** Names a beta value the game never sends, so neither feature word matches: the header as a whole is blamed. */
    private static final String LIVE_UNKNOWN_BETA_VALUE = live("Unexpected value(s) `bogus-beta-2099-01-01` for the "
            + "`anthropic-beta` header. Please consult our documentation at platform.claude.com/docs or try again "
            + "without the header.", "req_011CgVnMfSJ8kWZuszUQ7e8P");
    private static final String LIVE_LEVEL_OPUS_45 = live("This model does not support effort level 'xhigh'. "
            + "Supported levels: high, low, medium.", "req_011CgVnTPdSxDv8WDqpn4XM7");
    private static final String LIVE_LEVEL_OPUS_45_MAX = live("This model does not support effort level 'max'. "
            + "Supported levels: high, low, medium.", "req_011CgVnTV8qqribZhZ8h5r2B");
    private static final String LIVE_LEVEL_SONNET_46 = live("This model does not support effort level 'xhigh'. "
            + "Supported levels: high, low, max, medium.", "req_011CgVnTg5eYsbj698RF8tQd");

    @Test void classifierTable() {
        // Bodies named LIVE_* are verbatim API answers; the few below built with error(...) have no live
        // counterpart (the docs quote the sentence pattern, no real call produced it).
        assertEquals(EnumSet.of(Feature.FAST), classify(400, LIVE_SPEED_UNSUPPORTED, ALL));
        assertEquals(EnumSet.of(Feature.EFFORT), classify(400, LIVE_EFFORT_UNSUPPORTED, ALL));
        assertEquals(EnumSet.of(Feature.EFFORT),
                classify(400, error("invalid_request_error", "messages.0.output_config: Extra inputs are not permitted"), ALL));
        assertEquals(EnumSet.of(Feature.FALLBACKS), classify(400, LIVE_FALLBACKS_EXTRA_INPUTS, ALL));
        assertEquals(EnumSet.of(Feature.FALLBACKS), classify(400, LIVE_FALLBACKS_BETA_REJECTED, ALL));
        assertEquals(EnumSet.of(Feature.FAST), classify(400, LIVE_FAST_BETA_REJECTED, ALL));
        assertEquals(EnumSet.of(Feature.FAST, Feature.FALLBACKS), classify(400, LIVE_UNKNOWN_BETA_VALUE, ALL));
        // naming the fast beta must not take the fallbacks feature down with it
        assertEquals(EnumSet.of(Feature.FAST), classify(400, LIVE_FAST_BETA_REJECTED, Feature.FAST, Feature.FALLBACKS));
        // a body cut off mid-JSON still classifies
        assertEquals(EnumSet.of(Feature.EFFORT), classify(400,
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"output_config.effort is not supported by this mo", ALL));
        // whitespace inside the JSON does not matter
        assertEquals(EnumSet.of(Feature.FAST), classify(400,
                "{ \"error\" : { \"type\" : \"invalid_request_error\" , \"message\" : \"speed is not supported\" } }", ALL));
    }

    @Test void classifierRefusesToBlameAFeatureForUnrelatedErrors() {
        String[] unrelated = {
                live("max_tokens: Input should be a valid integer", "req_011CgVnLR1qi4SSmhb6tVnpk"),
                live("messages: Input should be a valid array", "req_011CgVnLXMaxcp9pfYJA4sTQ"),
                live("messages.0: user messages must have non-empty content", "req_011CgVnLdk5BrZfY6z67qi85"),
                live("foo_bar: Extra inputs are not permitted", "req_011CgVnLk5KbxDk3LV9Spp4G"),
                live("max_tokens: 10000000 > 128000, which is the maximum allowed number of output tokens for claude-haiku-5-5",
                        "req_011CgVnLrVnCXnrgkEXJMQGr"),
                live("temperature: range: 0..1", "req_011CgVnM5PBEQW4QEozq3HgY"),
                live("output_config.effort: Input should be 'low', 'medium', 'high', 'xhigh' or 'max'", "req_011CgVnMmompoKcyQwxgBTXD"),
                // a complaint about one effort LEVEL is handled by effortLevelsFromError, not by switching effort off
                LIVE_LEVEL_OPUS_45, LIVE_LEVEL_OPUS_45_MAX, LIVE_LEVEL_SONNET_46,
                error("invalid_request_error", "max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens for claude-3"),
                error("invalid_request_error", "This model does not support assistant message prefill. The conversation must end with a user message."),
                error("invalid_request_error", "Your credit balance is too low to access the Anthropic API."),
                error("invalid_request_error", "You have reached your specified workspace API usage limit. The effort is not supported until the spend limit resets."),
                error("invalid_request_error", "\\\"thinking.type.disabled\\\" is not supported for this model; effort is not supported"),
                error("authentication_error", "speed: Extra inputs are not permitted"),
                error("invalid_request_error", "messages: at least one message is required"),
        };
        for (String body : unrelated) assertTrue(classify(400, body, ALL).isEmpty(), body);
        assertTrue(classify(400, "", ALL).isEmpty());
        assertTrue(classify(400, null, ALL).isEmpty());
        assertTrue(classify(400, "{\"type\":\"error\"", ALL).isEmpty());
        assertTrue(classify(400, "<html>Bad Request</html>", ALL).isEmpty());
        // only a 400 is about the request shape
        String speed = LIVE_SPEED_UNSUPPORTED;
        for (int status : new int[]{401, 402, 403, 404, 413, 429, 500, 529}) {
            assertTrue(classify(status, speed, ALL).isEmpty(), "status " + status);
        }
        assertTrue(classify(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":"
                + "\"model: claude-no-such-model-9\"},\"request_id\":\"req_011CgVnLy59uAgg6nFLDKEQc\"}", ALL).isEmpty());
    }

    @Test void effortLevelComplaintsYieldTheAcceptedLevelsInsteadOfBlamingTheFeature() {
        assertEquals(List.of("high", "low", "medium"), AnthropicWire.effortLevelsFromError(400, LIVE_LEVEL_OPUS_45));
        assertEquals(List.of("high", "low", "medium"), AnthropicWire.effortLevelsFromError(400, LIVE_LEVEL_OPUS_45_MAX));
        assertEquals(List.of("high", "low", "max", "medium"), AnthropicWire.effortLevelsFromError(400, LIVE_LEVEL_SONNET_46));
        // wording case, spacing and a body cut off after the list do not matter
        assertEquals(List.of("low", "high"), AnthropicWire.effortLevelsFromError(400,
                error("invalid_request_error", "This model does not support EFFORT LEVEL 'max'. SUPPORTED LEVELS:  low ,high")));
        assertEquals(List.of("high", "low", "medium"), AnthropicWire.effortLevelsFromError(400,
                LIVE_LEVEL_OPUS_45.substring(0, LIVE_LEVEL_OPUS_45.indexOf("medium.") + 7)));
        // the same message is not a rejected feature: the caller keeps effort and clamps
        assertTrue(classify(400, LIVE_LEVEL_OPUS_45, ALL).isEmpty());
        assertTrue(classify(400, LIVE_LEVEL_SONNET_46, Feature.EFFORT).isEmpty());

        // everything else is left alone: no level list, no level complaint, nothing usable in the list, not a 400
        assertNull(AnthropicWire.effortLevelsFromError(400, LIVE_EFFORT_UNSUPPORTED));
        assertNull(AnthropicWire.effortLevelsFromError(400, LIVE_SPEED_UNSUPPORTED));
        assertNull(AnthropicWire.effortLevelsFromError(400, error("invalid_request_error",
                "This model does not support effort level 'xhigh'.")));
        assertNull(AnthropicWire.effortLevelsFromError(400, error("invalid_request_error",
                "This model does not support effort level 'xhigh'. Supported levels: warp, ludicrous")));
        assertNull(AnthropicWire.effortLevelsFromError(400, error("invalid_request_error",
                "Supported levels: high, low")), "a list without the level complaint is not ours");
        assertNull(AnthropicWire.effortLevelsFromError(500, LIVE_LEVEL_OPUS_45));
        assertNull(AnthropicWire.effortLevelsFromError(400, error("authentication_error",
                "This model does not support effort level 'xhigh'. Supported levels: high, low, medium.")));
        assertNull(AnthropicWire.effortLevelsFromError(400, null));
        assertNull(AnthropicWire.effortLevelsFromError(400, ""));
    }

    @Test void classifierOnlyBlamesFeaturesThatWereActuallySent() {
        String speed = LIVE_SPEED_UNSUPPORTED;
        assertTrue(classify(400, speed, Feature.EFFORT, Feature.FALLBACKS).isEmpty());
        assertTrue(classify(400, speed).isEmpty());
        String effort = LIVE_EFFORT_UNSUPPORTED;
        assertTrue(classify(400, effort, Feature.FAST).isEmpty());
        assertEquals(EnumSet.of(Feature.EFFORT), classify(400, effort, Feature.EFFORT, Feature.FAST));
        String beta = LIVE_UNKNOWN_BETA_VALUE;
        assertTrue(classify(400, beta, Feature.EFFORT).isEmpty());
        assertEquals(EnumSet.of(Feature.FAST), classify(400, beta, Feature.EFFORT, Feature.FAST));
    }

    // ---- thinking ----

    /** The two refusals of a thinking-off request, word for word as the API words them. */
    static final String OPUS_DISABLED_REFUSED = "\\\"thinking.type.disabled\\\" is not supported for this model. "
            + "Use \\\"thinking.type.adaptive\\\" and \\\"output_config.effort\\\" to control thinking behavior.";
    static final String SONNET_DISABLED_REFUSED = "\\\"thinking.type.disabled\\\" is not supported for this model. "
            + "Use \\\"thinking.type.between_tools\\\" for the lowest thinking setting, or \\\"thinking.type.adaptive\\\" "
            + "and \\\"output_config.effort\\\" to control thinking behavior.";

    @Test void thinkingObjectCarriesOnlyItsType() {
        for (String type : List.of("disabled", "between_tools", "adaptive")) {
            JsonObject body = json(AnthropicWire.build("m", "s", "u", "low", type, false, false, 0).body);
            JsonObject thinking = body.getAsJsonObject("thinking");
            assertEquals(1, thinking.entrySet().size(), "no budget_tokens or other field may ride along");
            assertEquals(type, thinking.get("type").getAsString());
            assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
        }
        for (String none : new String[] {null, "", "  "}) {
            assertFalse(json(AnthropicWire.build("m", "s", "u", "low", none, false, false, 0).body).has("thinking"));
        }
        assertFalse(json(AnthropicWire.build("m", "s", "u", "low", false, false).body).has("thinking"));
    }

    @Test void thinkingOffPicksTheStrongestSwitchTheModelOffers() {
        assertEquals("disabled", AnthropicWire.offThinkingType(null), "never learned: try a real off first");
        assertEquals("disabled", AnthropicWire.offThinkingType(List.of("enabled", "adaptive", "disabled")));
        assertEquals("between_tools", AnthropicWire.offThinkingType(List.of("adaptive", "between_tools")));
        assertEquals("disabled", AnthropicWire.offThinkingType(List.of("between_tools", "disabled")));
        assertNull(AnthropicWire.offThinkingType(List.of("adaptive")), "cannot be switched off: no thinking object");
        assertNull(AnthropicWire.offThinkingType(List.of("enabled")), "enabled is the opposite of off");
        assertNull(AnthropicWire.offThinkingType(List.of()));
    }

    @Test void aThinkingRefusalNamesWhatWasRefusedThenWhatToUseInstead() {
        assertEquals(List.of("disabled", "adaptive"),
                AnthropicWire.thinkingRejection(400, live(OPUS_DISABLED_REFUSED, "req_1")));
        assertEquals(List.of("disabled", "between_tools", "adaptive"),
                AnthropicWire.thinkingRejection(400, live(SONNET_DISABLED_REFUSED, "req_2")));
        assertEquals(List.of("disabled"), AnthropicWire.thinkingRejection(400,
                error("invalid_request_error", "\\\"thinking.type.disabled\\\" is not supported for this model.")));
        // a body cut off mid-sentence still yields what came before the cut
        String cut = live(SONNET_DISABLED_REFUSED, "req_2");
        assertEquals(List.of("disabled", "between_tools"), AnthropicWire.thinkingRejection(400,
                cut.substring(0, cut.indexOf("thinking.type.adaptive"))));

        // anything else is not a thinking refusal
        assertNull(AnthropicWire.thinkingRejection(400, LIVE_EFFORT_UNSUPPORTED));
        assertNull(AnthropicWire.thinkingRejection(400, LIVE_SPEED_UNSUPPORTED));
        assertNull(AnthropicWire.thinkingRejection(400, error("invalid_request_error", "thinking: Input should be an object")));
        assertNull(AnthropicWire.thinkingRejection(400, error("invalid_request_error",
                "Your credit balance is too low; thinking.type.disabled is not supported until you top up")));
        assertNull(AnthropicWire.thinkingRejection(400, error("authentication_error", OPUS_DISABLED_REFUSED)));
        assertNull(AnthropicWire.thinkingRejection(429, live(OPUS_DISABLED_REFUSED, "req_1")));
        assertNull(AnthropicWire.thinkingRejection(400, null));
        assertNull(AnthropicWire.thinkingRejection(400, ""));
    }

    @Test void thinkingIsOnlyBlamedWhenAThinkingObjectWasSent() {
        String opus = live(OPUS_DISABLED_REFUSED, "req_1");
        // sent: the refusal is about the thinking object, and not about effort even though effort is named
        assertEquals(EnumSet.of(Feature.THINKING), classify(400, opus, Feature.THINKING, Feature.EFFORT, Feature.FALLBACKS));
        assertEquals(EnumSet.of(Feature.THINKING), classify(400, live(SONNET_DISABLED_REFUSED, "req_2"), Feature.THINKING));
        // not sent: thinking.type is still never blamed on another feature
        assertTrue(classify(400, opus, ALL).isEmpty());
        assertTrue(classify(400, opus, Feature.EFFORT).isEmpty());
        // the chosen effort level is a different complaint and stays one
        assertTrue(classify(400, LIVE_LEVEL_OPUS_45, Feature.THINKING, Feature.EFFORT).isEmpty());
    }

    // ---- small helpers ----

    @Test void retryAfterIsParsedAndClamped() {
        assertEquals(30_000L, AnthropicWire.retryAfterMs("30"));
        assertEquals(30_000L, AnthropicWire.retryAfterMs(" 30 "));
        assertEquals(1_000L, AnthropicWire.retryAfterMs("0"));
        assertEquals(600_000L, AnthropicWire.retryAfterMs("86400"));
        assertEquals(0L, AnthropicWire.retryAfterMs(null));
        assertEquals(0L, AnthropicWire.retryAfterMs(""));
        assertEquals(0L, AnthropicWire.retryAfterMs("soon"));
        assertEquals(0L, AnthropicWire.retryAfterMs("-5"));
        assertEquals(0L, AnthropicWire.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0L, AnthropicWire.retryAfterMs("99999999999999999999"));
    }

    @Test void hintKeyMapsStatusCodes() {
        assertEquals("screen.nyanlex.ai.claude.err.401", AnthropicWire.hintKey("HTTP 401: {\"error\":{}}"));
        assertEquals("screen.nyanlex.ai.claude.err.402", AnthropicWire.hintKey("AI request failed (all keys): HTTP 402: billing"));
        assertEquals("screen.nyanlex.ai.claude.err.403", AnthropicWire.hintKey("HTTP 403"));
        assertEquals("screen.nyanlex.ai.claude.err.404", AnthropicWire.hintKey("Model catalog: HTTP 404"));
        assertEquals("screen.nyanlex.ai.claude.err.429", AnthropicWire.hintKey("HTTP 429: slow down"));
        assertEquals("screen.nyanlex.ai.claude.err.529", AnthropicWire.hintKey("HTTP 529: overloaded"));
        assertEquals("screen.nyanlex.ai.claude.err.529", AnthropicWire.hintKey("HTTP 500: api_error"));
        assertNull(AnthropicWire.hintKey("HTTP 400: bad"));
        assertNull(AnthropicWire.hintKey("HTTP 413"));
        assertNull(AnthropicWire.hintKey("connection reset"));
        assertNull(AnthropicWire.hintKey(null));
    }

    @Test void clampEffortPicksNearestLowerLevel() {
        assertEquals("max", AnthropicWire.clampEffort("max", null));
        assertEquals("high", AnthropicWire.clampEffort("high", List.of("low", "medium", "high")));
        assertEquals("high", AnthropicWire.clampEffort("max", List.of("low", "medium", "high")));
        assertEquals("medium", AnthropicWire.clampEffort("xhigh", List.of("low", "medium", "max")));
        assertEquals("high", AnthropicWire.clampEffort("low", List.of("high", "max")));
        assertEquals("low", AnthropicWire.clampEffort("low", List.of("max", "low")));
        assertNull(AnthropicWire.clampEffort("low", List.of()));
        assertEquals("low", AnthropicWire.clampEffort("bogus", List.of("low", "high")));
        assertTrue(AnthropicWire.isEffortLevel("xhigh"));
        assertFalse(AnthropicWire.isEffortLevel("ultra"));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), AnthropicWire.EFFORT_LEVELS);
    }

    @Test void urlsAreNormalisedAndNeverGrowTheirPath() {
        for (String base : List.of("https://api.anthropic.com", "https://api.anthropic.com/", "https://api.anthropic.com/v1",
                "https://api.anthropic.com/v1/", "https://api.anthropic.com/v1/messages",
                "https://api.anthropic.com/chat/completions", " https://api.anthropic.com ")) {
            assertEquals("https://api.anthropic.com/v1/messages", AnthropicWire.messagesUrl(base), base);
            assertEquals("https://api.anthropic.com/v1/models", AnthropicWire.modelsUrl(base), base);
        }
    }

    @Test void noModelIdIsHardCodedBesidesTheDefault() throws IOException {
        assertEquals("claude-haiku-5-5", AnthropicWire.DEFAULT_MODEL);
        assertEquals("medium", AnthropicWire.DEFAULT_EFFORT);
        for (String name : List.of("AnthropicWire", "AiFeatureMemory", "AnthropicTransport")) {
            String source = new String(Files.readAllBytes(Path.of("src/main/java/com/dragonmeow/nyanlex/translate/" + name + ".java")),
                    StandardCharsets.UTF_8);
            int count = source.split("claude-(haiku|sonnet|opus|fable)", -1).length - 1;
            assertEquals(name.equals("AnthropicWire") ? 1 : 0, count, name);
        }
    }

    // ---- the Java 8 loaders copy these files verbatim ----

    private static final List<String> SHARED_WITH_JAVA_8 = List.of(
            "AnthropicWire.java", "AiFeatureMemory.java", "ApiModelCatalog.java", "HttpEndpointPolicy.java");

    @Test void filesSharedWithTheJava8LoadersCompileAtLanguageLevel8(@TempDir Path out) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "no system compiler");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<Path> sources = new ArrayList<>();
            for (String name : SHARED_WITH_JAVA_8) sources.add(Path.of("src/main/java/com/dragonmeow/nyanlex/translate/" + name));
            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "8", "-proc:none", "-d", out.toString(), "-cp", gsonLocation()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call();
            StringBuilder errors = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.append(d.getMessage(null)).append('\n');
            }
            assertTrue(ok, errors.toString());
        }
    }

    private static String gsonLocation() {
        try {
            return Path.of(JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test void filesSharedWithTheJava8LoadersAvoidNewerGsonApis() throws IOException {
        for (String name : SHARED_WITH_JAVA_8) {
            String source = new String(Files.readAllBytes(Path.of("src/main/java/com/dragonmeow/nyanlex/translate/" + name)),
                    StandardCharsets.UTF_8);
            for (String newer : List.of("JsonParser.parseString(", ".keySet()", "getAsJsonArray().isEmpty()", ".stream()", "\"\"\"")) {
                assertFalse(source.contains(newer), name + " uses " + newer);
            }
        }
    }
}
