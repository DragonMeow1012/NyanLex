package com.dragonmeow.nyanlex.translate;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presents the Anthropic Messages API to {@link OpenAiTranslator} as if it were an
 * OpenAI-style endpoint: it reads the OpenAI-shaped request, sends a {@code POST /v1/messages}
 * and answers in OpenAI shape. Requests to any other host pass through untouched.
 *
 * <p>The user's key travels only in {@code x-api-key} to {@code api.anthropic.com}; the
 * {@code Authorization} header is never forwarded. Optional features a model rejects
 * (effort, thinking, fast mode, fallbacks) are dropped one at a time and remembered for the
 * session.</p>
 */
public final class AnthropicTransport implements HttpTransport {
    /**
     * Every optional feature can be dropped once, and the thinking object may need two steps
     * (disabled, then between_tools, then none), so six sends bound a single call.
     */
    private static final int MAX_SENDS = 6;
    private static final long FAST_BACKOFF_MS = 60_000L;
    /**
     * A batch of translations with thinking can take close to half a minute (30 % of the
     * medium-effort requests measured against the live API outran the 30 s of other
     * providers), so only this route is given longer; model lists keep their own timeout.
     */
    static final Duration MESSAGES_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern STATUS = Pattern.compile("^\\s*HTTP (\\d{3})(?::\\s*(.*))?$", Pattern.DOTALL);

    private final HttpTransport inner;
    private final Supplier<AnthropicWire.Options> options;
    private final AiFeatureMemory memory;
    private final LongSupplier clock;
    private final Map<String, Long> fastBackoffUntil = new ConcurrentHashMap<>();

    public AnthropicTransport(HttpTransport inner, Supplier<AnthropicWire.Options> options,
                              AiFeatureMemory memory, LongSupplier clock) {
        this.inner = inner;
        this.options = options;
        this.memory = memory;
        this.clock = clock;
    }

    @Override
    public long blockedUntil() {
        return inner.blockedUntil();
    }

    @Override
    public String get(String url) throws IOException {
        return inner.get(url);
    }

    @Override
    public String post(String url, String body, Map<String, String> headers) throws IOException {
        if (!HttpEndpointPolicy.isAnthropic(url)) return inner.post(url, body, headers);
        LocalAiTransportSupport.Request request = LocalAiTransportSupport.parseRequest(body);
        String key = bearerKey(headers);
        if (key.isEmpty()) throw new IOException("HTTP 401: API key required");
        String model = request.model();
        String endpoint = AnthropicWire.messagesUrl(url);

        IOException last = null;
        for (int send = 0; send < MAX_SENDS; send++) {
            Built built = buildRequest(model, request);
            AnthropicWire.Request wire = built.wire;
            Map<String, String> outgoing = new HashMap<>();
            outgoing.put("x-api-key", key);
            outgoing.put("anthropic-version", AnthropicWire.VERSION);
            outgoing.put("content-type", "application/json");
            if (wire.beta != null) outgoing.put("anthropic-beta", wire.beta);
            String response;
            try {
                response = inner.post(endpoint, wire.body, outgoing, MESSAGES_TIMEOUT);
            } catch (IOException e) {
                last = e;
                if (degrade(model, built, e)) continue;
                throw e;
            }
            AnthropicWire.Reply reply = AnthropicWire.parseReply(response);
            return LocalAiTransportSupport.response(reply.text, reply.usage);
        }
        throw last == null ? new IOException("HTTP 400: request rejected") : last;
    }

    /** A request together with the optional features (and thinking type) it actually carries. */
    private static final class Built {
        final AnthropicWire.Request wire;
        final Set<AnthropicWire.Feature> sent;
        /** The {@code thinking.type} sent, or null when the request has no thinking object. */
        final String thinking;

        Built(AnthropicWire.Request wire, Set<AnthropicWire.Feature> sent, String thinking) {
            this.wire = wire;
            this.sent = sent;
            this.thinking = thinking;
        }
    }

    /**
     * The request for the features still allowed. {@code default} sends no effort and no
     * thinking object; a level sends that effort; {@code off} asks for the least thinking the
     * model allows: a thinking object chosen from the model's thinking types (see
     * {@link AnthropicWire#offThinkingType}) plus the lowest effort.
     */
    private Built buildRequest(String model, LocalAiTransportSupport.Request request) {
        Set<AnthropicWire.Feature> sent = EnumSet.noneOf(AnthropicWire.Feature.class);
        AnthropicWire.Options chosen = options == null ? null : options.get();
        String selected = chosen == null || chosen.effort == null ? ""
                : chosen.effort.trim().toLowerCase(Locale.ROOT);
        boolean off = AnthropicWire.EFFORT_OFF.equals(selected);
        String wanted = off ? AnthropicWire.EFFORT_WHEN_OFF : (AnthropicWire.isEffortLevel(selected) ? selected : null);
        String effort = null;
        if (wanted != null && !memory.isUnsupported(model, AnthropicWire.Feature.EFFORT)) {
            List<String> levels = memory.effortLevels(model);
            if (levels == null) effort = wanted;
            else if (!levels.isEmpty()) effort = AnthropicWire.clampEffort(wanted, levels);
        }
        if (effort != null) sent.add(AnthropicWire.Feature.EFFORT);
        String thinking = off && !memory.isUnsupported(model, AnthropicWire.Feature.THINKING)
                ? AnthropicWire.offThinkingType(memory.thinkingTypes(model)) : null;
        if (thinking != null) sent.add(AnthropicWire.Feature.THINKING);
        boolean fast = chosen != null && chosen.fast
                && !memory.isUnsupported(model, AnthropicWire.Feature.FAST) && !fastBackedOff(model);
        if (fast) sent.add(AnthropicWire.Feature.FAST);
        boolean fallbacks = !memory.isUnsupported(model, AnthropicWire.Feature.FALLBACKS);
        if (fallbacks) sent.add(AnthropicWire.Feature.FALLBACKS);
        AnthropicWire.Request wire = AnthropicWire.build(model, request.system(), request.user(), effort,
                thinking, fast, fallbacks, memory.maxOutputTokens(model));
        return new Built(wire, sent, thinking);
    }

    /**
     * Whether {@code failure} was caused by an optional feature and the request is worth
     * resending without it. Any other failure is left for the caller's own retry logic.
     */
    private boolean degrade(String model, Built built, IOException failure) {
        Set<AnthropicWire.Feature> sent = built.sent;
        int status = statusOf(failure);
        if (status == 400) {
            String body = bodyOf(failure);
            if (built.thinking != null) {
                List<String> mentioned = AnthropicWire.thinkingRejection(status, body);
                if (mentioned != null) {
                    learnThinkingRejection(model, built.thinking, mentioned);
                    return true;
                }
            }
            List<String> accepted = AnthropicWire.effortLevelsFromError(status, body);
            if (accepted != null && sent.contains(AnthropicWire.Feature.EFFORT)) {
                // The model does take effort, just not the chosen level: learn what it takes and
                // resend at the nearest lower level instead of switching effort off.
                List<String> known = memory.effortLevels(model);
                memory.recordEffortLevels(model, accepted);
                if (known == null || known.size() != accepted.size() || !known.containsAll(accepted)) return true;
                // Already clamped to exactly these levels and still refused: stop sending effort.
                memory.markUnsupported(model, AnthropicWire.Feature.EFFORT);
                return true;
            }
            Set<AnthropicWire.Feature> rejected = AnthropicWire.unsupportedFeatures(status, body, sent);
            for (AnthropicWire.Feature feature : rejected) memory.markUnsupported(model, feature);
            return !rejected.isEmpty();
        }
        if (sent.contains(AnthropicWire.Feature.FAST) && (status == 429 || status == 529)) {
            // Fast mode has its own capacity; retry at standard speed without tripping the 429 gate.
            long wait = Math.max(FAST_BACKOFF_MS, retryAfterOf(failure));
            fastBackoffUntil.put(key(model), clock.getAsLong() + wait);
            return true;
        }
        return false;
    }

    /**
     * The model refused the {@code thinking.type} that was sent. Forget that type, adopt the
     * ones the message recommends instead, and let the next build pick again; if that changes
     * nothing, stop sending a thinking object rather than repeat the same refusal.
     */
    private void learnThinkingRejection(String model, String rejected, List<String> mentioned) {
        List<String> known = memory.thinkingTypes(model);
        List<String> next = new ArrayList<>(known == null ? Collections.<String>emptyList() : known);
        next.remove(rejected);
        for (String type : mentioned) if (!type.equals(rejected) && !next.contains(type)) next.add(type);
        memory.recordThinkingTypes(model, next);
        if (known != null && known.equals(next)) memory.markUnsupported(model, AnthropicWire.Feature.THINKING);
    }

    private boolean fastBackedOff(String model) {
        Long until = fastBackoffUntil.get(key(model));
        return until != null && clock.getAsLong() < until;
    }

    private static String key(String model) {
        return model == null ? "" : model.toLowerCase(Locale.ROOT);
    }

    /** The API key from the OpenAI-style bearer header the translator set; never forwarded as such. */
    private static String bearerKey(Map<String, String> headers) {
        if (headers == null) return "";
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!"authorization".equalsIgnoreCase(header.getKey()) || header.getValue() == null) continue;
            String value = header.getValue();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) value = value.substring(7);
            value = value.trim();
            return value.equalsIgnoreCase("Bearer") ? "" : value;
        }
        return "";
    }

    private static int statusOf(IOException e) {
        if (e instanceof UrlHttpTransport.HttpStatusException) {
            return ((UrlHttpTransport.HttpStatusException) e).statusCode();
        }
        Matcher matcher = STATUS.matcher(e.getMessage() == null ? "" : e.getMessage());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String bodyOf(IOException e) {
        if (e instanceof UrlHttpTransport.HttpStatusException) {
            return ((UrlHttpTransport.HttpStatusException) e).bodySnippet();
        }
        Matcher matcher = STATUS.matcher(e.getMessage() == null ? "" : e.getMessage());
        return matcher.matches() && matcher.group(2) != null ? matcher.group(2) : "";
    }

    private static long retryAfterOf(IOException e) {
        return e instanceof UrlHttpTransport.HttpStatusException
                ? ((UrlHttpTransport.HttpStatusException) e).retryAfterMs() : 0L;
    }
}
