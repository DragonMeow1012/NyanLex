package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.AnthropicWire.Feature;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicTransportTest {

    private static final String URL = "https://api.anthropic.com/chat/completions";
    private static final String MODEL = "claude-haiku-5-5";
    private static final String KEY = "sk-ant-secret-0123456789";

    /** An OpenAI-shaped request exactly as the translator builds it, extra sampling fields included. */
    private static final String OPENAI_BODY = "{\"model\":\"" + MODEL + "\",\"temperature\":0.3,\"messages\":["
            + "{\"role\":\"system\",\"content\":\"SYS\"},{\"role\":\"user\",\"content\":\"USER\"}]}";

    private static String message(String text) {
        return message(text, "end_turn");
    }

    private static String message(String text, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + MODEL + "\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"" + stopReason
                + "\",\"usage\":{\"input_tokens\":12,\"output_tokens\":7}}";
    }

    private static String badRequest(String text) {
        return "HTTP 400: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"" + text + "\"}}";
    }

    private static final String EFFORT_REJECTED =
            badRequest("output_config.effort requires a model that supports per-turn effort; this model does not");
    private static final String FAST_REJECTED = badRequest("speed: Extra inputs are not permitted");
    private static final String FALLBACKS_REJECTED = badRequest(
            "Unexpected value(s) `server-side-fallback-2026-07-01` for the `anthropic-beta` header.");

    private static final class Sent {
        final String url;
        final JsonObject body;
        final Map<String, String> headers;
        final String rawBody;

        Sent(String url, String body, Map<String, String> headers) {
            this.url = url;
            this.rawBody = body;
            this.body = new JsonParser().parse(body).getAsJsonObject();
            this.headers = headers;
        }
    }

    /** A scripted wire: each post() consumes the next entry, a String reply or an IOException to throw. */
    private static final class Rig {
        final List<Sent> sent = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        final Deque<Object> script = new ArrayDeque<>();
        final AiFeatureMemory memory = new AiFeatureMemory();
        final long[] now = {1_000_000L};
        Supplier<AnthropicWire.Options> options = () -> new AnthropicWire.Options(null, false);

        final HttpTransport inner = new HttpTransport() {
            @Override public String get(String url) { throw new UnsupportedOperationException(); }
            @Override public String post(String url, String body, Map<String, String> headers) throws IOException {
                sent.add(new Sent(url, body, headers));
                Object next = script.poll();
                if (next == null) throw new AssertionError("unscripted request #" + sent.size());
                if (next instanceof IOException) throw (IOException) next;
                return (String) next;
            }
            @Override public String post(String url, String body, Map<String, String> headers, Duration timeout)
                    throws IOException {
                timeouts.add(timeout);
                return post(url, body, headers);
            }
        };

        Rig script(Object... replies) {
            for (Object reply : replies) script.add(reply instanceof String && ((String) reply).startsWith("HTTP ")
                    ? new IOException((String) reply) : reply);
            return this;
        }

        Rig options(String effort, boolean fast) {
            options = () -> new AnthropicWire.Options(effort, fast);
            return this;
        }

        AnthropicTransport transport() {
            return new AnthropicTransport(inner, () -> options.get(), memory, () -> now[0]);
        }

        String post() throws IOException {
            return transport().post(URL, OPENAI_BODY, Map.of("Authorization", "Bearer " + KEY));
        }
    }

    private static boolean has(Sent sent, String field) {
        return sent.body.has(field);
    }

    // ---- shape ----

    @Test void nonAnthropicUrlIsPassedThroughUntouched() throws Exception {
        List<Sent> seen = new ArrayList<>();
        HttpTransport inner = new HttpTransport() {
            @Override public String get(String url) { return "got " + url; }
            @Override public String post(String url, String body, Map<String, String> headers) {
                seen.add(new Sent(url, body, headers));
                return "{\"choices\":[]}";
            }
        };
        AnthropicTransport transport = new AnthropicTransport(inner,
                () -> new AnthropicWire.Options("max", true), new AiFeatureMemory(), System::currentTimeMillis);
        String openAi = "{\"model\":\"gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        for (String url : List.of("https://api.openai.com/v1/chat/completions", "https://api.anthropic.com.evil.test/chat/completions",
                "http://api.anthropic.com/chat/completions", "https://api.anthropic.com:8443/chat/completions")) {
            assertEquals("{\"choices\":[]}", transport.post(url, openAi, Map.of("Authorization", "Bearer k")));
        }
        assertEquals(4, seen.size());
        for (Sent s : seen) {
            assertEquals(openAi, s.rawBody);
            assertEquals("Bearer k", s.headers.get("Authorization"));
            assertFalse(s.headers.containsKey("x-api-key"));
        }
        assertEquals("got https://x", transport.get("https://x"));
    }

    @Test void translatesRequestAndNeverForwardsBearer() throws Exception {
        Rig rig = new Rig().options("low", false).script(message("你好"));

        String response = rig.post();

        assertEquals(1, rig.sent.size());
        Sent sent = rig.sent.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", sent.url);
        assertEquals(KEY, sent.headers.get("x-api-key"));
        assertEquals("2023-06-01", sent.headers.get("anthropic-version"));
        assertEquals("application/json", sent.headers.get("content-type"));
        for (String name : sent.headers.keySet()) assertNotEquals("authorization", name.toLowerCase(), "Authorization must not be forwarded");
        assertFalse(sent.rawBody.contains(KEY), "the key must never be in the body");
        assertEquals(MODEL, sent.body.get("model").getAsString());
        assertEquals("SYS", sent.body.get("system").getAsString());
        assertEquals("USER", sent.body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
        assertFalse(has(sent, "temperature"));
        assertEquals("low", sent.body.getAsJsonObject("output_config").get("effort").getAsString());
        assertFalse(has(sent, "speed"));
        // fallbacks are tried first, so the beta header carries only that value
        assertEquals("server-side-fallback-2026-07-01", sent.headers.get("anthropic-beta"));

        JsonObject reply = new JsonParser().parse(response).getAsJsonObject();
        assertEquals("你好", reply.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString());
        assertEquals(12, reply.getAsJsonObject("usage").get("input_tokens").getAsInt());
        assertEquals(7, reply.getAsJsonObject("usage").get("output_tokens").getAsInt());
    }

    @Test void missingKeyFailsWithoutSendingAnything() {
        Rig rig = new Rig();
        for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("Authorization", "Bearer  "))) {
            IOException e = assertThrows(IOException.class, () -> rig.transport().post(URL, OPENAI_BODY, headers));
            assertTrue(e.getMessage().startsWith("HTTP 401"), e.getMessage());
        }
        assertTrue(rig.sent.isEmpty());
    }

    @Test void theKeyNeverAppearsInAnErrorMessage() {
        Rig rig = new Rig().script("HTTP 401: {\"error\":{\"message\":\"invalid x-api-key\"}}");
        IOException e = assertThrows(IOException.class, rig::post);
        assertFalse(e.getMessage().contains(KEY));
        assertThrows(IOException.class, () -> rig.transport().post(URL, "{not json", Map.of("Authorization", "Bearer " + KEY)));
    }

    // ---- timeout ----

    @Test void onlyTheMessagesRequestGetsTheLongerTimeout() throws Exception {
        List<Duration> timeouts = new ArrayList<>();
        List<String> longPosts = new ArrayList<>();
        List<String> plainPosts = new ArrayList<>();
        List<String> gets = new ArrayList<>();
        HttpTransport inner = new HttpTransport() {
            @Override public String get(String url) { gets.add(url); return "{}"; }
            @Override public String post(String url, String body, Map<String, String> headers) {
                plainPosts.add(url);
                return "{\"choices\":[]}";
            }
            @Override public String post(String url, String body, Map<String, String> headers, Duration timeout) {
                timeouts.add(timeout);
                longPosts.add(url);
                return message("好");
            }
        };
        AnthropicTransport transport = new AnthropicTransport(inner,
                () -> new AnthropicWire.Options(null, false), new AiFeatureMemory(), () -> 0L);

        transport.post(URL, OPENAI_BODY, Map.of("Authorization", "Bearer " + KEY));
        transport.post("https://api.openai.com/v1/chat/completions", "{}", Map.of("Authorization", "Bearer k"));
        transport.post("https://api.deepseek.com/chat/completions", "{}", Map.of("Authorization", "Bearer k"));
        transport.get("https://api.anthropic.com/v1/models");

        assertEquals(List.of(Duration.ofSeconds(120)), timeouts);
        assertEquals(List.of("https://api.anthropic.com/v1/messages"), longPosts);
        assertEquals(List.of("https://api.openai.com/v1/chat/completions", "https://api.deepseek.com/chat/completions"),
                plainPosts, "other providers keep the transport's own timeout (30 s on the real one)");
        assertEquals(List.of("https://api.anthropic.com/v1/models"), gets, "model lists keep theirs too");
    }

    @Test void everySendOfADegradedCallKeepsTheLongTimeout() throws Exception {
        Rig rig = new Rig().options("low", true).script(FAST_REJECTED, EFFORT_REJECTED, message("好"));

        rig.post();

        assertEquals(3, rig.sent.size());
        assertEquals(List.of(Duration.ofSeconds(120), Duration.ofSeconds(120), Duration.ofSeconds(120)), rig.timeouts);
    }

    // ---- max_tokens ----

    @Test void maxTokensFollowsTheOutputLimitTheCatalogGaveTheModel() throws Exception {
        Rig rig = new Rig().script(message("a"), message("b"), message("c"), message("d"));

        rig.post(); // the catalog was never read
        rig.memory.recordMaxOutputTokens(MODEL, 8192);
        rig.post();
        rig.memory.recordMaxOutputTokens(MODEL, 128000);
        rig.post();
        rig.memory.recordMaxOutputTokens(MODEL.toUpperCase(), 4096); // model ids are not case sensitive
        rig.post();

        assertEquals(16000, rig.sent.get(0).body.get("max_tokens").getAsInt());
        assertEquals(8192, rig.sent.get(1).body.get("max_tokens").getAsInt());
        assertEquals(16000, rig.sent.get(2).body.get("max_tokens").getAsInt());
        assertEquals(4096, rig.sent.get(3).body.get("max_tokens").getAsInt());
    }

    // ---- effort ----

    private static String effortOf(Sent sent) {
        return sent.body.has("output_config") ? sent.body.getAsJsonObject("output_config").get("effort").getAsString() : null;
    }

    /** The live API's answer to an effort level the model does not take (opus-4-5, recorded 2026-10-29). */
    private static UrlHttpTransport.HttpStatusException levelRejected() {
        return new UrlHttpTransport.HttpStatusException(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"This model does not support effort level 'xhigh'. Supported levels: high, low, medium.\"},"
                + "\"request_id\":\"req_011CgVnTPdSxDv8WDqpn4XM7\"}", 0L);
    }

    @Test void aLevelTheModelLacksIsClampedAndResentNotDropped() throws Exception {
        Rig rig = new Rig().options("xhigh", true).script(levelRejected(), message("好"), message("好"));

        assertTrue(rig.post().contains("好"));

        assertEquals(2, rig.sent.size());
        assertEquals("xhigh", effortOf(rig.sent.get(0)));
        assertEquals("high", effortOf(rig.sent.get(1)), "the nearest level the model lists below the chosen one");
        assertTrue(has(rig.sent.get(1), "speed") && has(rig.sent.get(1), "fallbacks"), "the other features stay");
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.EFFORT), "effort itself is still supported");
        assertEquals(List.of("high", "low", "medium"), rig.memory.effortLevels(MODEL));

        rig.post();
        assertEquals(3, rig.sent.size(), "the next call needs no extra try");
        assertEquals("high", effortOf(rig.sent.get(2)));
    }

    @Test void ifEvenTheClampedLevelIsRefusedEffortIsFinallyDropped() throws Exception {
        Rig rig = new Rig().options("high", false).script(levelRejected(), message("好"));
        rig.memory.recordEffortLevels(MODEL, List.of("high", "low", "medium")); // already what the error says

        assertTrue(rig.post().contains("好"));

        assertEquals(2, rig.sent.size());
        assertEquals("high", effortOf(rig.sent.get(0)));
        assertNull(effortOf(rig.sent.get(1)));
        assertTrue(rig.memory.isUnsupported(MODEL, Feature.EFFORT));
    }

    @Test void aLevelComplaintForAnEffortThatWasNotSentDegradesNothing() {
        Rig rig = new Rig().options(null, false).script(levelRejected());

        IOException e = assertThrows(IOException.class, rig::post);

        assertTrue(e.getMessage().startsWith("HTTP 400"), e.getMessage());
        assertEquals(1, rig.sent.size());
        assertNull(rig.memory.effortLevels(MODEL));
    }

    @Test void effortRespectsCatalogAndMemory() throws Exception {
        Rig rig = new Rig().options("max", false).script(message("a"), message("b"), message("c"), message("d"));

        rig.post(); // never read the catalog: sent as chosen
        assertEquals("max", rig.sent.get(0).body.getAsJsonObject("output_config").get("effort").getAsString());

        rig.memory.recordEffortLevels(MODEL, List.of("low", "medium", "high"));
        rig.post(); // clamped to the nearest level the catalog lists
        assertEquals("high", rig.sent.get(1).body.getAsJsonObject("output_config").get("effort").getAsString());

        rig.memory.recordEffortLevels(MODEL, List.of("low", "medium", "high", "xhigh", "max"));
        rig.post();
        assertEquals("max", rig.sent.get(2).body.getAsJsonObject("output_config").get("effort").getAsString());

        rig.memory.recordEffortLevels(MODEL, List.of()); // the catalog says effort is unsupported
        rig.post();
        assertFalse(has(rig.sent.get(3), "output_config"));
    }

    @Test void blankEffortIsNotSent() throws Exception {
        Rig rig = new Rig().options("", false).script(message("a"));
        rig.post();
        assertFalse(has(rig.sent.get(0), "output_config"));
    }

    // ---- degradation ----

    @Test void degradeDropsOnlyTheNamedFeatureAndRemembersIt() throws Exception {
        Rig rig = new Rig().options("low", false).script(EFFORT_REJECTED, message("好"), message("好"));

        assertTrue(rig.post().contains("好"));
        assertEquals(2, rig.sent.size());
        assertTrue(has(rig.sent.get(0), "output_config"));
        assertFalse(has(rig.sent.get(1), "output_config"), "the retry inside the same call drops effort");
        assertEquals("server-side-fallback-2026-07-01", rig.sent.get(1).headers.get("anthropic-beta"), "other features stay");
        assertTrue(rig.memory.isUnsupported(MODEL, Feature.EFFORT));
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.FALLBACKS));

        rig.post();
        assertEquals(3, rig.sent.size());
        assertFalse(has(rig.sent.get(2), "output_config"), "the next call does not even try effort");
    }

    @Test void unrelatedBadRequestIsNotDegradedAndNotRemembered() {
        Rig rig = new Rig().options("low", true).script(
                badRequest("max_tokens: 16000 > 8192, which is the maximum allowed number of output tokens"));

        IOException e = assertThrows(IOException.class, rig::post);

        assertTrue(e.getMessage().startsWith("HTTP 400"), e.getMessage());
        assertEquals(1, rig.sent.size());
        for (Feature feature : Feature.values()) assertFalse(rig.memory.isUnsupported(MODEL, feature));
    }

    @Test void fastRejectedBy400IsDroppedAndRemembered() throws Exception {
        Rig rig = new Rig().options(null, true).script(FAST_REJECTED, message("好"), message("好"));

        rig.post();

        assertEquals("fast", rig.sent.get(0).body.get("speed").getAsString());
        assertEquals("fast-mode-2026-02-01,server-side-fallback-2026-07-01", rig.sent.get(0).headers.get("anthropic-beta"));
        assertFalse(has(rig.sent.get(1), "speed"));
        assertEquals("server-side-fallback-2026-07-01", rig.sent.get(1).headers.get("anthropic-beta"));
        assertTrue(rig.memory.isUnsupported(MODEL, Feature.FAST));
        rig.post();
        assertFalse(has(rig.sent.get(2), "speed"));
    }

    @Test void fastRateLimitedFallsBackToStandardWithoutTrippingGate() throws Exception {
        Rig rig = new Rig().options(null, true).script("HTTP 429: fast capacity", message("a"), message("b"), message("c"), message("d"));
        AnthropicTransport transport = rig.transport();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + KEY);

        assertTrue(transport.post(URL, OPENAI_BODY, headers).contains("a"), "no exception: the standard-speed retry answers");
        assertEquals(2, rig.sent.size());
        assertTrue(has(rig.sent.get(0), "speed"));
        assertFalse(has(rig.sent.get(1), "speed"));
        assertFalse(rig.sent.get(1).headers.get("anthropic-beta").contains("fast-mode"));

        rig.now[0] += 59_999L; // inside the one-minute window
        transport.post(URL, OPENAI_BODY, headers);
        assertFalse(has(rig.sent.get(2), "speed"), "inside the window speed is not sent");
        rig.now[0] += 2L; // window over
        transport.post(URL, OPENAI_BODY, headers);
        assertTrue(has(rig.sent.get(3), "speed"), "after the window fast mode is tried again");
    }

    @Test void fastBackoffFollowsALongerRetryAfter() throws Exception {
        Rig rig = new Rig().options(null, true).script(
                new UrlHttpTransport.HttpStatusException(529, "overloaded", 120_000L), message("a"), message("b"), message("c"));
        AnthropicTransport transport = rig.transport();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + KEY);

        transport.post(URL, OPENAI_BODY, headers);
        assertFalse(has(rig.sent.get(1), "speed"));
        rig.now[0] += 119_000L;
        transport.post(URL, OPENAI_BODY, headers);
        assertFalse(has(rig.sent.get(2), "speed"), "the server asked for two minutes");
        rig.now[0] += 1_001L;
        transport.post(URL, OPENAI_BODY, headers);
        assertTrue(has(rig.sent.get(3), "speed"));
    }

    @Test void standardRateLimitIsRethrown() {
        Rig rig = new Rig().options("low", false).script("HTTP 429: quota exceeded");

        IOException e = assertThrows(IOException.class, rig::post);

        assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
        assertEquals(1, rig.sent.size(), "without fast mode the 429 is left to the caller's own gate");
    }

    @Test void fallbacksRejectedIsDroppedAndBetaHeaderShrinks() throws Exception {
        Rig rig = new Rig().options(null, true).script(FALLBACKS_REJECTED, message("好"));

        rig.post();

        assertEquals("fast-mode-2026-02-01,server-side-fallback-2026-07-01", rig.sent.get(0).headers.get("anthropic-beta"));
        assertEquals("fast-mode-2026-02-01", rig.sent.get(1).headers.get("anthropic-beta"));
        assertFalse(has(rig.sent.get(1), "fallbacks"));
        assertTrue(has(rig.sent.get(1), "speed"));
        assertTrue(rig.memory.isUnsupported(MODEL, Feature.FALLBACKS));
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.FAST));
    }

    @Test void allThreeRejectedSequentiallyEndsWithBareRequest() throws Exception {
        Rig rig = new Rig().options("low", true).script(EFFORT_REJECTED, FAST_REJECTED, FALLBACKS_REJECTED, message("好"));

        assertTrue(rig.post().contains("好"));

        assertEquals(4, rig.sent.size());
        Sent bare = rig.sent.get(3);
        assertFalse(has(bare, "output_config") || has(bare, "speed") || has(bare, "fallbacks"));
        assertFalse(bare.headers.containsKey("anthropic-beta"));
        for (Feature feature : List.of(Feature.EFFORT, Feature.FAST, Feature.FALLBACKS)) {
            assertTrue(rig.memory.isUnsupported(MODEL, feature));
        }
    }

    @Test void aRejectedBetaHeaderDropsBothFeaturesThatRideOnIt() throws Exception {
        Rig rig = new Rig().options("low", true).script(badRequest("Unexpected value(s) for the `anthropic-beta` header."), message("好"));

        rig.post();

        Sent retry = rig.sent.get(1);
        assertFalse(has(retry, "speed") || has(retry, "fallbacks"));
        assertTrue(has(retry, "output_config"), "effort does not need the header");
        assertFalse(retry.headers.containsKey("anthropic-beta"));
    }

    @Test void aStubbornBadRequestStopsAfterTheBoundedNumberOfSends() {
        Rig rig = new Rig().options("low", true).script(EFFORT_REJECTED, FAST_REJECTED, FALLBACKS_REJECTED, EFFORT_REJECTED, EFFORT_REJECTED);

        IOException e = assertThrows(IOException.class, rig::post);

        assertTrue(e.getMessage().startsWith("HTTP 400"));
        assertEquals(4, rig.sent.size());
    }

    // ---- thinking off ----

    private static final String OPUS_REFUSED = badRequest(AnthropicWireTest.OPUS_DISABLED_REFUSED);
    private static final String SONNET_REFUSED = badRequest(AnthropicWireTest.SONNET_DISABLED_REFUSED);
    private static final String BETWEEN_TOOLS_REFUSED =
            badRequest("\\\"thinking.type.between_tools\\\" is not supported for this model.");

    private static JsonObject thinkingOf(Sent sent) {
        return sent.body.has("thinking") ? sent.body.getAsJsonObject("thinking") : null;
    }

    @Test void offUsesARealDisableWhereTheCatalogOffersOne() throws Exception {
        Rig rig = new Rig().options("off", false).script(message("好"));
        rig.memory.recordThinkingTypes(MODEL, List.of("adaptive", "disabled"));

        rig.post();

        assertEquals(1, rig.sent.size());
        JsonObject thinking = thinkingOf(rig.sent.get(0));
        assertEquals(1, thinking.entrySet().size());
        assertEquals("disabled", thinking.get("type").getAsString());
        assertEquals("low", effortOf(rig.sent.get(0)));
    }

    @Test void offFallsBackToBetweenToolsThenToEffortAloneAsTheCatalogRunsOut() throws Exception {
        Rig between = new Rig().options("off", false).script(message("好"));
        between.memory.recordThinkingTypes(MODEL, List.of("adaptive", "between_tools"));
        between.post();
        JsonObject thinking = thinkingOf(between.sent.get(0));
        assertEquals(1, thinking.entrySet().size(), "between_tools allows no other field in the object");
        assertEquals("between_tools", thinking.get("type").getAsString());
        assertEquals("low", effortOf(between.sent.get(0)));

        Rig adaptive = new Rig().options("off", false).script(message("好"));
        adaptive.memory.recordThinkingTypes(MODEL, List.of("adaptive"));
        adaptive.post();
        assertNull(thinkingOf(adaptive.sent.get(0)), "thinking cannot be switched off: only the effort goes down");
        assertEquals("low", effortOf(adaptive.sent.get(0)));
    }

    @Test void offWithoutACatalogTriesDisabledFirst() throws Exception {
        Rig rig = new Rig().options("off", false).script(message("好"));

        rig.post();

        assertEquals("disabled", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        assertEquals("low", effortOf(rig.sent.get(0)));
    }

    @Test void aModelThatCannotDisableIsTaughtByItsRefusalToUseAdaptiveStyleLowEffort() throws Exception {
        Rig rig = new Rig().options("off", false).script(OPUS_REFUSED, message("好"), message("再"));

        assertTrue(rig.post().contains("好"));

        assertEquals(2, rig.sent.size());
        assertEquals("disabled", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        assertNull(thinkingOf(rig.sent.get(1)), "the retry sends no thinking object");
        assertEquals("low", effortOf(rig.sent.get(1)));
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.EFFORT), "the message names effort as the cure, not the culprit");
        assertEquals(List.of("adaptive"), rig.memory.thinkingTypes(MODEL));

        rig.post();
        assertEquals(3, rig.sent.size(), "remembered: no further failed attempt");
        assertNull(thinkingOf(rig.sent.get(2)));
        assertEquals("low", effortOf(rig.sent.get(2)));
    }

    @Test void aModelWithAFloorIsTaughtToUseBetweenTools() throws Exception {
        Rig rig = new Rig().options("off", false).script(SONNET_REFUSED, message("好"), message("再"));

        assertTrue(rig.post().contains("好"));

        assertEquals(2, rig.sent.size());
        assertEquals("disabled", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        JsonObject retry = thinkingOf(rig.sent.get(1));
        assertEquals(1, retry.entrySet().size());
        assertEquals("between_tools", retry.get("type").getAsString());
        assertEquals("low", effortOf(rig.sent.get(1)));
        assertEquals(List.of("between_tools", "adaptive"), rig.memory.thinkingTypes(MODEL));

        rig.post();
        assertEquals(3, rig.sent.size());
        assertEquals("between_tools", thinkingOf(rig.sent.get(2)).get("type").getAsString());
    }

    @Test void aRefusedBetweenToolsEndsWithNoThinkingObject() throws Exception {
        Rig rig = new Rig().options("off", false).script(BETWEEN_TOOLS_REFUSED, message("好"));
        rig.memory.recordThinkingTypes(MODEL, List.of("adaptive", "between_tools"));

        rig.post();

        assertEquals(2, rig.sent.size());
        assertEquals("between_tools", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        assertNull(thinkingOf(rig.sent.get(1)));
        assertEquals(List.of("adaptive"), rig.memory.thinkingTypes(MODEL));
    }

    @Test void offChainsDownEvenWhenEveryStepIsRefused() throws Exception {
        Rig rig = new Rig().options("off", false).script(SONNET_REFUSED, BETWEEN_TOOLS_REFUSED, message("好"));

        rig.post();

        assertEquals(3, rig.sent.size());
        assertEquals("disabled", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        assertEquals("between_tools", thinkingOf(rig.sent.get(1)).get("type").getAsString());
        assertNull(thinkingOf(rig.sent.get(2)));
        assertEquals("low", effortOf(rig.sent.get(2)));
    }

    @Test void offKeepsThinkingDisabledWhenOnlyEffortIsUnavailable() throws Exception {
        Rig rig = new Rig().options("off", false).script(message("好"));
        rig.memory.recordThinkingTypes(MODEL, List.of("enabled", "disabled"));
        rig.memory.recordEffortLevels(MODEL, List.of());

        rig.post();

        assertEquals("disabled", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        assertNull(effortOf(rig.sent.get(0)), "the model has no effort setting");
    }

    @Test void offStepsEffortDownToTheLowestLevelTheModelHas() throws Exception {
        Rig rig = new Rig().options("off", false).script(message("好"));
        rig.memory.recordThinkingTypes(MODEL, List.of("disabled"));
        rig.memory.recordEffortLevels(MODEL, List.of("medium", "high"));

        rig.post();

        assertEquals("medium", effortOf(rig.sent.get(0)));
    }

    @Test void defaultAndLevelsNeverSendAThinkingObject() throws Exception {
        for (String selected : new String[] {"default", "low", "medium", "max", null, "", "not-a-level", "minimal"}) {
            Rig rig = new Rig().options(selected, false).script(message("好"));
            rig.memory.recordThinkingTypes(MODEL, List.of("adaptive", "disabled"));
            rig.post();
            assertNull(thinkingOf(rig.sent.get(0)), String.valueOf(selected));
            boolean sendsEffort = "low".equals(selected) || "medium".equals(selected) || "max".equals(selected);
            assertEquals(sendsEffort ? selected : null, effortOf(rig.sent.get(0)), String.valueOf(selected));
        }
    }

    @Test void aThinkingComplaintWhenNoThinkingWasSentChangesNothing() throws Exception {
        Rig rig = new Rig().options("low", false).script(OPUS_REFUSED);

        IOException e = assertThrows(IOException.class, rig::post);

        assertTrue(e.getMessage().startsWith("HTTP 400"));
        assertEquals(1, rig.sent.size());
        assertNull(rig.memory.thinkingTypes(MODEL));
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.EFFORT));
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.THINKING));
    }

    @Test void anUnrelatedBadRequestWhileThinkingIsOffIsNotBlamedOnThinking() {
        Rig rig = new Rig().options("off", false).script(badRequest("max_tokens: Input should be a valid integer"));

        assertThrows(IOException.class, rig::post);

        assertEquals(1, rig.sent.size());
        assertNull(rig.memory.thinkingTypes(MODEL));
        assertFalse(rig.memory.isUnsupported(MODEL, Feature.THINKING));
    }

    @Test void theChosenSettingIsReadAgainForEveryRequest() throws Exception {
        Rig rig = new Rig().script(message("1"), message("2"), message("3"));
        rig.memory.recordThinkingTypes(MODEL, List.of("adaptive", "disabled"));
        String[] selected = {"off"};
        rig.options = () -> new AnthropicWire.Options(selected[0], false);

        rig.post();
        selected[0] = "high";
        rig.post();
        selected[0] = "default";
        rig.post();

        assertEquals("disabled", thinkingOf(rig.sent.get(0)).get("type").getAsString());
        assertNull(thinkingOf(rig.sent.get(1)));
        assertEquals("high", effortOf(rig.sent.get(1)));
        assertNull(thinkingOf(rig.sent.get(2)));
        assertNull(effortOf(rig.sent.get(2)));
    }

    // ---- results that are not translations ----

    @Test void aTruncatedSingleSentenceFailsAndNoOtherKeyIsTried() {
        Rig rig = new Rig().script(message("partial", "max_tokens"), message("second key", "max_tokens"));
        OpenAiTranslator translator = new OpenAiTranslator(rig.transport(),
                () -> new AiSettings("https://api.anthropic.com", MODEL, List.of("key-1", "key-2")));

        TranslationException e = assertThrows(TranslationException.class, () -> translator.translate("Hello", "zh-TW"));

        assertEquals(1, rig.sent.size(), "no second key is tried");
        assertTrue(e.getMessage().contains("rejected"), e.getMessage());
        assertEquals("key-1", rig.sent.get(0).headers.get("x-api-key"));
    }

    @Test void aRefusedSingleSentenceIsAnsweredWithTheOriginalAndNoOtherKeyIsTried() throws Exception {
        Rig rig = new Rig().script(message("sorry", "refusal"), message("second key", "refusal"));
        OpenAiTranslator translator = new OpenAiTranslator(rig.transport(),
                () -> new AiSettings("https://api.anthropic.com", MODEL, List.of("key-1", "key-2")));

        TranslationResult result = translator.translate("Hello", "zh-TW");

        assertEquals("Hello", result.translatedText(), "the text is left as it is, the way an echoing model would");
        assertNull(result.failureReason());
        assertFalse(result.fromFallback());
        assertEquals(1, rig.sent.size(), "no second key is tried");
        assertEquals("key-1", rig.sent.get(0).headers.get("x-api-key"));
    }

    /**
     * A Messages API that answers an anchored batch in the translator's own format ("T:" before
     * every text). A batch holding REFUSED is declined, one holding more than {@code maxUnits}
     * units is cut off, and one holding TRUNCATED is always cut off.
     */
    private static final class Oracle implements HttpTransport {
        final List<Sent> sent = new ArrayList<>();
        int maxUnits = Integer.MAX_VALUE;

        @Override public String get(String url) { throw new UnsupportedOperationException(); }

        @Override public String post(String url, String body, Map<String, String> headers) {
            Sent request = new Sent(url, body, headers);
            sent.add(request);
            String user = request.body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            if (user.contains("REFUSED")) return message("I can't help with that.", "refusal");
            StringBuilder reply = new StringBuilder();
            int units = 0;
            Matcher matcher = Pattern.compile("(?m)^(\\d+) (.*) (\\d+)$").matcher(user);
            while (matcher.find()) {
                units++;
                if (reply.length() > 0) reply.append("\\n");
                reply.append(matcher.group(1)).append("T:").append(matcher.group(2)).append(matcher.group(3));
            }
            if (user.contains("TRUNCATED") || units > maxUnits) return message("partial", "max_tokens");
            return message(reply.toString());
        }

        OpenAiTranslator translator() {
            return new OpenAiTranslator(new AnthropicTransport(this, () -> new AnthropicWire.Options(null, false),
                    new AiFeatureMemory(), () -> 0L),
                    () -> new AiSettings("https://api.anthropic.com", MODEL, List.of(KEY)));
        }
    }

    @Test void aRefusedSentenceDoesNotSinkTheRestOfItsBatch() throws Exception {
        Oracle oracle = new Oracle();

        List<TranslationResult> out = oracle.translator().translateBatch(List.of("Alpha", "REFUSED beta", "Gamma"), "zh-TW");

        assertEquals(3, out.size());
        assertEquals("T:Alpha", out.get(0).translatedText());
        assertEquals("REFUSED beta", out.get(1).translatedText(), "the declined sentence is left as it is");
        assertEquals("T:Gamma", out.get(2).translatedText());
        for (TranslationResult result : out) assertNull(result.failureReason());
        // the whole batch, then Alpha alone and the other two, then those two one by one
        assertEquals(5, oracle.sent.size());
    }

    @Test void aBatchThatIsCutOffIsSplitUntilEachPartFits() throws Exception {
        Oracle oracle = new Oracle();
        oracle.maxUnits = 2;

        List<TranslationResult> out = oracle.translator().translateBatch(List.of("A one", "B two", "C three", "D four", "E five"), "zh-TW");

        assertEquals(List.of("T:A one", "T:B two", "T:C three", "T:D four", "T:E five"),
                out.stream().map(TranslationResult::translatedText).toList());
        for (TranslationResult result : out) assertNull(result.failureReason());
    }

    @Test void aSingleSentenceThatIsCutOffStillFailsTheCall() {
        Oracle oracle = new Oracle();

        TranslationException e = assertThrows(TranslationException.class,
                () -> oracle.translator().translateBatch(List.of("Alpha", "TRUNCATED beta", "Gamma"), "zh-TW"));

        assertTrue(e.getMessage().contains("rejected"), e.getMessage());
    }

    @Test void aRefusedSentenceIsKeptAsIsByTheCacheAfterThreeAnswersAndNeverSentAgain() {
        Oracle oracle = new Oracle();
        TranslationCache cache = new TranslationCache(oracle.translator(), "zh-TW", Runnable::run, 100, 0L, () -> 0L);
        String text = "Welcome REFUSED to the server";

        for (int i = 0; i < 3; i++) cache.requestAsync(text);
        assertEquals(3, oracle.sent.size());
        assertEquals(text, cache.getCached(text), "the third identical answer settles it: keep the original");

        for (int i = 0; i < 4; i++) cache.requestAsync(text);
        assertEquals(3, oracle.sent.size(), "a settled sentence is never sent again");

        cache.invalidate(text);
        assertNull(cache.getCached(text), "retranslating by hand clears the decision");
        cache.requestAsync(text);
        assertEquals(4, oracle.sent.size(), "and then it may be asked again");
    }

    @Test void errorStatusesKeepHttpPrefix() {
        for (int status : new int[]{401, 402, 403, 404, 429, 500, 529}) {
            Rig rig = new Rig().script(new UrlHttpTransport.HttpStatusException(status, "{\"error\":{}}", 0L));
            IOException e = assertThrows(IOException.class, rig::post);
            assertTrue(e.getMessage().startsWith("HTTP " + status), e.getMessage());
            assertEquals(1, rig.sent.size(), "status " + status);
        }
    }

    // ---- through the real translator ----

    @Test void endToEndWithOpenAiTranslator() throws Exception {
        String anchored = OpenAiTranslator.BATCH_ANCHOR_BASE + "你好" + (OpenAiTranslator.BATCH_ANCHOR_BASE + 1) + "\\n"
                + (OpenAiTranslator.BATCH_ANCHOR_BASE + 2) + "世界" + (OpenAiTranslator.BATCH_ANCHOR_BASE + 3);
        Rig rig = new Rig().options("low", false).script(message(anchored));
        OpenAiTranslator translator = new OpenAiTranslator(rig.transport(),
                () -> new AiSettings("https://api.anthropic.com", MODEL, List.of(KEY)));
        SessionTokenUsage usage = new SessionTokenUsage();
        translator.setTokenUsage(usage);

        List<TranslationResult> out = translator.translateBatch(List.of("Hello", "World"), "zh-TW");

        assertEquals("你好", out.get(0).translatedText());
        assertEquals("世界", out.get(1).translatedText());
        Sent sent = rig.sent.get(0);
        assertEquals("https://api.anthropic.com/v1/messages", sent.url);
        assertEquals(KEY, sent.headers.get("x-api-key"));
        assertFalse(sent.headers.containsKey("Authorization"));
        assertTrue(sent.body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("86001 Hello 86002"));
        assertEquals(12L, usage.snapshot().inputTokens());
        assertEquals(7L, usage.snapshot().outputTokens());
    }

    @Test void nativeThinkingUsageIsCountedOnceAsPartOfOutputNotAddedToTotal() throws Exception {
        String reply = message("86001 你好 86002");
        JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
        JsonObject nativeUsage = json.getAsJsonObject("usage");
        nativeUsage.addProperty("input_tokens", 825);
        nativeUsage.addProperty("output_tokens", 445);
        JsonObject details = new JsonObject();
        details.addProperty("thinking_tokens", 324);
        nativeUsage.add("output_tokens_details", details);
        Rig rig = new Rig().script(json.toString());
        OpenAiTranslator translator = new OpenAiTranslator(rig.transport(),
                () -> new AiSettings("https://api.anthropic.com", MODEL, List.of(KEY)));
        SessionTokenUsage usage = new SessionTokenUsage();
        translator.setTokenUsage(usage);

        assertEquals("你好", translator.translate("Hello", "zh-TW").translatedText());
        assertEquals(825, usage.snapshot().inputTokens());
        assertEquals(445, usage.snapshot().outputTokens());
        assertEquals(324, usage.snapshot().reasoningOutputTokens());
        assertEquals(1270, usage.snapshot().totalTokens());
        assertEquals(1, usage.snapshot().requests());
    }

    @Test void configuredEffortChangesApplyToTheNextRequest() throws Exception {
        String[] effort = {"low"};
        Rig rig = new Rig().script(message("a"), message("b"));
        rig.options = () -> new AnthropicWire.Options(effort[0], false);
        AnthropicTransport transport = rig.transport();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + KEY);

        transport.post(URL, OPENAI_BODY, headers);
        effort[0] = "high";
        transport.post(URL, OPENAI_BODY, headers);

        assertEquals("low", rig.sent.get(0).body.getAsJsonObject("output_config").get("effort").getAsString());
        assertEquals("high", rig.sent.get(1).body.getAsJsonObject("output_config").get("effort").getAsString());
    }
}
