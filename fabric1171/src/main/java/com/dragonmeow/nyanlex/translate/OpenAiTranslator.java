package com.dragonmeow.nyanlex.translate;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link Translator} backed by an OpenAI-compatible chat-completions endpoint
 * (OpenAI, DeepSeek, local servers, …) for higher-quality "精翻".
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li><b>Context-aware batching:</b> {@link #translateBatch} sends every line of a
 *       surface (e.g. a whole item tooltip) in <em>one</em> strictly anchored request, so the
 *       model translates them coherently with shared context (fixes things like
 *       "EV Yields" → "電動車產量").</li>
 *   <li><b>Multi-key rotation:</b> keys are tried round-robin and, on failure
 *       (rate-limit / auth), the next key is tried before giving up.</li>
 * </ul>
 *
 * <p>The {@link HttpTransport} and {@link AiSettings} supplier are injected so this
 * is unit-testable with an inline fake transport.</p>
 */
public final class OpenAiTranslator implements Translator {

    private static final Gson GSON = new Gson();
    /** Five-digit outer boundaries are compact while remaining easy for chat models to copy. */
    public static final int BATCH_ANCHOR_BASE = 86001;

    private final HttpTransport transport;
    private final Supplier<AiSettings> settings;
    private final LongSupplier clock;
    private final RequestPacer pacer;
    private volatile SessionTokenUsage tokenUsage;

    // Each request retains its settings' health state. An older in-flight response
    // cannot change the active model's key health or rate-limit gate after a switch.
    private SettingsState settingsState;

    private static final class SettingsState {
        final String signature;
        final AtomicInteger keyCursor = new AtomicInteger();
        final Map<String, KeyState> keyStates = new HashMap<>();
        volatile long rateLimitedUntil;
        long penaltyMs; // guarded by this state

        SettingsState(String signature) {
            this.signature = signature;
        }
    }

    private static final long KEY_RATE_LIMIT_COOLDOWN_MS = 60_000L;
    private static final long KEY_TRANSIENT_COOLDOWN_MS = 10_000L;

    private static final class KeyState {
        final long unavailableUntil;
        final boolean rateLimited;

        KeyState(long unavailableUntil, boolean rateLimited) {
            this.unavailableUntil = unavailableUntil;
            this.rateLimited = rateLimited;
        }
    }

    // ---- global 429 backoff gate ----
    // When EVERY key in one rotation comes back 429, the account/model quota itself is
    // exhausted — rotating keys just burns more quota. The gate fails every request fast
    // (DispatchingTranslator then falls back to Google) until the penalty expires;
    // consecutive trips double the penalty, any success resets it.
    private static final long RATE_LIMIT_BASE_PENALTY_MS = 60_000L;
    private static final long RATE_LIMIT_MAX_PENALTY_MS = 600_000L;

    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings) {
        this(transport, settings, System::currentTimeMillis, RequestPacer.disabled());
    }

    /** Clock-injecting constructor so the 429 gate is unit-testable with a fake clock. */
    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings, LongSupplier clock) {
        this(transport, settings, clock, RequestPacer.disabled());
    }

    /** Pacer-injecting constructor: {@code pacer} throttles EVERY outbound HTTP request
     *  (including per-key rotation and transient-error retries). */
    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings, RequestPacer pacer) {
        this(transport, settings, System::currentTimeMillis, pacer);
    }

    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings,
                            LongSupplier clock, RequestPacer pacer) {
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.pacer = pacer == null ? RequestPacer.disabled() : pacer;
    }

    public void setTokenUsage(SessionTokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public boolean isConfigured() {
        AiSettings s = settings.get();
        return s != null && s.isConfigured();
    }

    /** Whether the global 429 gate is currently CLOSED (still backing off). Consulted by the
     *  provisional-retry gate: a GT stand-in is only re-asked of the AI once this is false. */
    public boolean isRateLimited() {
        AiSettings current = settings.get();
        if (current == null || !current.isConfigured()) return false;
        long until = refreshSettingsState(current).rateLimitedUntil;
        return until != 0 && clock.getAsLong() < until;
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        return translateBatch(List.of(text), targetLang).get(0);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        return translateBatch(texts, targetLang, null);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                  List<String> surfaceContext) throws TranslationException {
        List<List<String>> contexts = surfaceContext == null || surfaceContext.isEmpty()
                ? null : java.util.Collections.nCopies(texts.size(), surfaceContext);
        return translateBatchWithContexts(texts, targetLang, contexts);
    }

    /** One request for a window-collected batch whose units may come from different
     * surfaces. Units sharing a context are sent as one contiguous anchor range, so each
     * context names exactly the range it applies to; units without a context get none.
     * Results are returned in the caller's order. */
    @Override
    public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                              List<List<String>> itemContexts)
            throws TranslationException {
        if (texts.isEmpty()) return List.of();
        AiSettings s = settings.get();
        if (s == null || !s.isConfigured()) {
            throw new TranslationException("AI translator not configured (base URL / model missing)");
        }
        SettingsState health = refreshSettingsState(s);
        long gateUntil = health.rateLimitedUntil;
        if (clock.getAsLong() < gateUntil) {
            // Fail fast without HTTP: the caller's DispatchingTranslator falls back to Google.
            throw new TranslationException("AI rate-limited (429 on all keys): backing off");
        }

        List<List<String>> contexts = alignedContexts(itemContexts, texts.size());
        int[] order = groupedOrder(contexts, texts.size());
        List<TranslationResult> out = new ArrayList<>(texts.size());
        if (order == null) {
            translateChunk(texts, targetLang, contexts, s, health, out);
            return out;
        }
        List<String> orderedTexts = new ArrayList<>(texts.size());
        List<List<String>> orderedContexts = new ArrayList<>(texts.size());
        for (int index : order) {
            orderedTexts.add(texts.get(index));
            orderedContexts.add(contexts.get(index));
        }
        translateChunk(orderedTexts, targetLang, orderedContexts, s, health, out);
        TranslationResult[] restored = new TranslationResult[texts.size()];
        for (int position = 0; position < order.length; position++) {
            restored[order[position]] = out.get(position);
        }
        return new ArrayList<>(java.util.Arrays.asList(restored));
    }

    /** Pacing of this engine's next request (read-only peek; nothing is reserved). */
    @Override
    public long nextRequestDelayMs() {
        return pacer.delayUntilNextSlotMs();
    }

    /** {@code null} when no unit carries a context; otherwise aligned, empty contexts as null. */
    private static List<List<String>> alignedContexts(List<List<String>> itemContexts, int count) {
        if (itemContexts == null || itemContexts.size() != count) return null;
        List<List<String>> aligned = new ArrayList<>(count);
        boolean any = false;
        for (List<String> context : itemContexts) {
            boolean present = context != null && !context.isEmpty();
            aligned.add(present ? context : null);
            any |= present;
        }
        return any ? aligned : null;
    }

    /** Stable permutation that makes units with an equal context contiguous (groups in order
     * of first appearance), or {@code null} when they already are. */
    private static int[] groupedOrder(List<List<String>> contexts, int count) {
        if (contexts == null) return null;
        List<List<String>> groups = new ArrayList<>();
        int[] groupOf = new int[count];
        boolean contiguous = true;
        for (int i = 0; i < count; i++) {
            List<String> context = contexts.get(i);
            int group = -1;
            for (int g = 0; g < groups.size(); g++) {
                if (java.util.Objects.equals(groups.get(g), context)) {
                    group = g;
                    break;
                }
            }
            if (group < 0) {
                group = groups.size();
                groups.add(context);
            }
            if (i > 0 && group < groupOf[i - 1]) contiguous = false;
            groupOf[i] = group;
        }
        if (contiguous) return null;
        int[] order = new int[count];
        int position = 0;
        for (int g = 0; g < groups.size(); g++) {
            for (int i = 0; i < count; i++) {
                if (groupOf[i] == g) order[position++] = i;
            }
        }
        return order;
    }

    /** One normal chunk is one physical request; damaged boundaries are bisected safely. */
    private void translateChunk(List<String> texts, String targetLang,
                                List<List<String>> contexts, AiSettings settings, SettingsState health,
                                List<TranslationResult> out) throws TranslationException {
        int base = anchorBase(texts, contexts);
        List<AiWireItem> wire = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) wire.add(maskHardLines(texts.get(i), i));
        String anchored = buildContextBlocks(texts, contexts, base) + buildAnchoredPrompt(wire, base);
        int promptTokens = promptTokens(wire);
        // Wire codec: the internal ⟦...⟧ protocol tokens (CS/MT/WS/PB, protected names,
        // this method's own ⟦AI_LINE_i_j⟧ hard-line slots) cost 7-9 tokens each under
        // GPT-4o/4.1's tokenizer because ⟦/⟧ (U+27E6/U+27E7) have no merged token and
        // fall back to raw byte encoding — see AiWireCodec's class doc. Converting to the
        // ASCII {kind#} form ONLY for the HTTP round trip (never touching the cache key,
        // which stays ⟦...⟧ the whole time) cuts that to 3-4 tokens each with no change
        // to request count or validation semantics.
        AiWireCodec.Encoded wireCodec = AiWireCodec.encode(anchored);
        String requestBody = buildRequestBody(settings, targetLang, wireCodec.wire(), promptTokens, true);
        String plainBody = wantsNoReasoning(settings.model())
                ? buildRequestBody(settings, targetLang, wireCodec.wire(), promptTokens, false) : null;
        String content = postWithKeyRotation(settings, health, requestBody, plainBody);
        String rawResponseBody = content; // exactly what the model sent, for the debug dump below
        // Back to internal ⟦...⟧ format before anything below (anchor extraction,
        // restoreHardLines, tokensMatch) touches it — all of that keeps working exactly
        // as it did before this codec existed.
        content = AiWireCodec.decode(content, wireCodec);
        if (content == null || content.isBlank()) {
            List<TranslationResult> empty = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                empty.add(new TranslationResult("", null, false, "empty response"));
            }
            dumpExchange(requestBody, rawResponseBody, texts, empty);
            out.addAll(empty);
            return;
        }
        List<String> parts = extractAnchoredBatch(content, texts.size(), base);
        if (parts == null) {
            if (texts.size() == 1) {
                TranslationResult damaged = new TranslationResult("", null, false, "anchor/order damaged");
                dumpExchange(requestBody, rawResponseBody, texts, List.of(damaged));
                out.add(damaged);
                return;
            }
            dumpExchange(requestBody, rawResponseBody, texts, null);
            int mid = texts.size() / 2;
            translateChunk(texts.subList(0, mid), targetLang,
                    contexts == null ? null : contexts.subList(0, mid), settings, health, out);
            translateChunk(texts.subList(mid, texts.size()), targetLang,
                    contexts == null ? null : contexts.subList(mid, texts.size()), settings, health, out);
            return;
        }
        List<TranslationResult> chunkResults = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String restored = restoreHardLines(parts.get(i), wire.get(i));
            if (restored == null) {
                chunkResults.add(new TranslationResult("", null, false, "paragraph lost"));
            } else if (!paragraphBreakSequenceMatches(texts.get(i), restored)) {
                // Keep readable prose when only display wraps were lost. The cache
                // publishes it first, then owns one isolated review, as in the root core.
                boolean readable = ParagraphModel.canReflowBreakLoss(texts.get(i), restored)
                        && tokensMatch(ParagraphModel.flattenBreakTokens(texts.get(i)),
                                ParagraphModel.flattenBreakTokens(restored));
                chunkResults.add(readable ? new TranslationResult(restored, null)
                        : new TranslationResult("", null, false, "paragraph lost"));
            } else if (!tokensMatch(texts.get(i), restored)) {
                chunkResults.add(new TranslationResult("", null, false, "format/token lost"));
            } else {
                chunkResults.add(new TranslationResult(restored, null));
            }
        }
        dumpExchange(requestBody, rawResponseBody, texts, chunkResults);
        retryIsolatedFailures(texts, targetLang, contexts, settings, health, chunkResults);
        out.addAll(chunkResults);
    }

    /** Kept manually in sync with root's {@code OpenAiTranslator.ExchangeDumpSink} (this
     *  file is excluded from sync-core.ps1's automatic mirroring). See that interface's
     *  class doc for the full contract. */
    public interface ExchangeDumpSink {
        void record(String requestBody, String responseBody, List<String> sourceTexts,
                   List<TranslationResult> results);
    }

    private volatile ExchangeDumpSink dumpSink;

    public void setExchangeDumpSink(ExchangeDumpSink sink) {
        this.dumpSink = sink;
    }

    private void dumpExchange(String requestBody, String responseBody,
                              List<String> sourceTexts, List<TranslationResult> results) {
        ExchangeDumpSink sink = dumpSink;
        if (sink == null) return;
        try {
            sink.record(requestBody, responseBody, sourceTexts, results);
        } catch (RuntimeException ignored) {
            // Debug tooling must never break a real translation request.
        }
    }

    /**
     * Real multi-unit SkyBlock tooltip sessions show a recurring pattern: unit 0 of a
     * heterogeneous batch (item name/RARITY/ABILITY/TRADE/STATS/PROSE segments all sent
     * as one windowed request) translates correctly while every later unit in the SAME
     * physical response fails {@code tokensMatch} — "format/token lost" — even a unit that
     * carries no placeholder at all (plain prose like "Click to inspect!"). Whatever the
     * exact cause (a model's attention/instruction-following degrading over a long batched
     * output is a well-documented failure mode for smaller "mini"-tier chat models), a
     * content-level validation failure on one unit of a large batch is recoverable: give
     * that ONE unit a fresh, isolated, single-item follow-up request — no batch neighbours,
     * no shared wire-token namespace, the model's full attention — instead of leaving it
     * permanently stuck behind the cache's exponential failure backoff until the next
     * independent miss. Bounded to exactly one isolated pass per original chunk (the
     * {@code texts.size() <= 1} guard inside the recursive {@link #translateChunk} call
     * this triggers means the retry's own result can never trigger ANOTHER retry), so this
     * can never loop or multiply requests beyond one extra HTTP call per unit that failed
     * for a content reason. Transport-level failures (rate limit, auth, timeout) already
     * throw out of {@link #postWithKeyRotation} before any unit-level result exists here,
     * so they are never retried by this path — only "format/token lost"/"paragraph lost",
     * the two reasons a well-formed response can still fail per-unit validation.
     *
     * <p>Kept manually in sync with root's {@code OpenAiTranslator.retryIsolatedFailures}
     * (this file is excluded from sync-core.ps1's automatic mirroring — see that script's
     * {@code $fabric1171Excluded} list).</p>
     */
    private void retryIsolatedFailures(List<String> texts, String targetLang,
                                       List<List<String>> contexts, AiSettings settings, SettingsState health,
                                       List<TranslationResult> chunkResults) {
        if (texts.size() <= 1) return;
        for (int i = 0; i < chunkResults.size(); i++) {
            TranslationResult result = chunkResults.get(i);
            String reason = result.failureReason();
            if (reason == null) continue; // already succeeded
            if (!reason.equals("format/token lost") && !reason.equals("paragraph lost")) continue;
            try {
                List<TranslationResult> single = new ArrayList<>(1);
                List<List<String>> singleContext = contexts == null
                        ? null : java.util.Collections.singletonList(contexts.get(i));
                translateChunk(java.util.Collections.singletonList(texts.get(i)), targetLang,
                        singleContext, settings, health, single);
                if (!single.isEmpty() && single.get(0).failureReason() == null) {
                    chunkResults.set(i, single.get(0));
                }
            } catch (TranslationException retryFailed) {
                // Keep the original per-unit failure; the ordinary cache backoff retries
                // this key again later exactly as it did before this isolated retry existed.
            }
        }
    }

    private static final java.util.regex.Pattern ANY_TOKEN =
            java.util.regex.Pattern.compile("⟦[^⟦⟧]*⟧");
    private static final java.util.regex.Pattern CS_TOKEN =
            java.util.regex.Pattern.compile("⟦\\s*/?\\s*CS\\s*\\d+\\s*⟧");
    /** Kept manually in sync with root's identical pattern (this file is excluded from
     *  sync-core.ps1's automatic mirroring). */
    private static final java.util.regex.Pattern PARAGRAPH_BREAK_TOKEN =
            java.util.regex.Pattern.compile("⟦\\s*PB\\s*(\\d+)\\s*⟧");

    static boolean paragraphBreakSequenceMatches(String source, String translated) {
        return paragraphBreakSequence(source).equals(paragraphBreakSequence(translated));
    }

    private static List<String> paragraphBreakSequence(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = PARAGRAPH_BREAK_TOKEN.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Every protocol token must survive exactly once. Complete pairs may move for target
     * grammar, but a missing, duplicated, or foreign token would lose a live value or
     * poison another template and is therefore a per-line content failure. */
    static boolean tokensMatch(String source, String translated) {
        // In fixed-column rows, no live value or styled phrase may cross a WS boundary.
        // Ordinary prose without WS slots still allows complete MT/CS units to move for
        // target-language grammar.
        if (!TranslationTemplate.layoutSkeletonMatches(source, translated)) return false;
        if (!TranslationTemplate.styleSlotShapeMatches(source, translated)) return false;
        if (!sameTokenMultiset(tokensOf(source, CS_TOKEN), tokensOf(translated, CS_TOKEN))) {
            return false;
        }
        return sameTokenMultiset(tokensOf(source), tokensOf(translated));
    }

    private static List<String> tokensOf(String text) {
        return tokensOf(text, ANY_TOKEN);
    }

    private static List<String> tokensOf(String text, java.util.regex.Pattern pattern) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group().replace(" ", ""));
        return out;
    }

    private static boolean sameTokenMultiset(List<String> first, List<String> second) {
        if (first.size() != second.size()) return false;
        List<String> remaining = new ArrayList<>(first);
        for (String token : second) if (!remaining.remove(token)) return false;
        return remaining.isEmpty();
    }

    // ---- prompt / request building ----

    /**
     * Prefix for the user message when a numbered batch comes from one visible surface:
     * shows the model the whole information block so cached or dynamic rows still shape
     * terminology. No row is presumed to be a title; books, logs and HUD panels commonly
     * begin directly with body text. Returns "" when there is no context.
     */
    private static final int MAX_CONTEXT_LINES = 24;
    private static final int MAX_CONTEXT_CHARS = 2_000;
    private static final String CONTEXT_HEADER =
            "Minecraft visible block context (semantic reference for domain and terminology; layout is program-owned):\n";
    private static final String CONTEXTS_HEADER =
            "Minecraft visible block contexts (semantic reference for domain and terminology; layout is program-owned):\n";
    private static final String CONTEXT_OMITTED = "[remaining context omitted]\n";
    private static final String CONTEXT_FOOTER =
            "Translate ONLY the strictly anchored units below; do not output the visible-block context.\n\n";

    static String buildSurfaceContextBlock(List<String> surfaceContext) {
        return buildSurfaceContextBlock(surfaceContext, null);
    }

    /** Single-context block. Rows that are themselves anchored units of this request are not
     * listed again (Q1): the model already reads them in the unit list, and repeating them
     * doubled the input of a freshly hovered tooltip. Row indices keep their surface position,
     * so the gaps still show where the translated units sit. Limits are unchanged. */
    static String buildSurfaceContextBlock(List<String> surfaceContext, java.util.Set<String> unitTexts) {
        if (surfaceContext == null || surfaceContext.isEmpty()) return "";
        ContextRows rows = contextRows(surfaceContext, unitTexts, CONTEXT_HEADER.length());
        if (rows.textRows == 0) return "";
        StringBuilder sb = new StringBuilder(CONTEXT_HEADER.length() + rows.text.length() + 128);
        sb.append(CONTEXT_HEADER).append(rows.text);
        if (rows.truncated) sb.append(CONTEXT_OMITTED);
        sb.append(CONTEXT_FOOTER);
        return sb.toString();
    }

    /** Context blocks for one request. {@code contexts} is aligned with {@code texts}, and units
     * sharing a context are contiguous. One context for every unit keeps the single-block
     * format; otherwise every context is labelled with the boundary range of the units it
     * applies to, and units without a context get none. Each context keeps its own limits,
     * exactly as when it travelled in a request of its own. */
    static String buildContextBlocks(List<String> texts, List<List<String>> contexts, int base) {
        if (contexts == null || texts == null || contexts.size() != texts.size()) return "";
        java.util.Set<String> units = new java.util.HashSet<>(texts);
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            List<String> context = contexts.get(i);
            if (context == null) continue;
            int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[1] == i && context.equals(contexts.get(last[0]))) last[1] = i + 1;
            else runs.add(new int[] {i, i + 1});
        }
        if (runs.isEmpty()) return "";
        if (runs.size() == 1 && runs.get(0)[0] == 0 && runs.get(0)[1] == texts.size()) {
            return buildSurfaceContextBlock(contexts.get(0), units);
        }
        StringBuilder blocks = new StringBuilder();
        for (int[] run : runs) {
            String label = "[Context for units " + (base + run[0] * 2) + "-"
                    + (base + run[1] * 2 - 1) + "]\n";
            // Same row budget as the single-block format, so no context grows by batching.
            ContextRows rows = contextRows(contexts.get(run[0]), units, CONTEXT_HEADER.length());
            if (rows.textRows == 0) continue;
            blocks.append(label).append(rows.text);
            if (rows.truncated) blocks.append(CONTEXT_OMITTED);
        }
        if (blocks.length() == 0) return "";
        return CONTEXTS_HEADER + blocks + CONTEXT_FOOTER;
    }

    private static final class ContextRows {
        final StringBuilder text = new StringBuilder();
        int textRows;
        boolean truncated;
    }

    /** The listed rows of one context: at most {@link #MAX_CONTEXT_LINES} rows and
     *  {@link #MAX_CONTEXT_CHARS} characters including {@code used} header characters. */
    private static ContextRows contextRows(List<String> context, java.util.Set<String> unitTexts, int used) {
        ContextRows rows = new ContextRows();
        int emitted = 0;
        int i = 0;
        for (; i < context.size() && emitted < MAX_CONTEXT_LINES; i++) {
            String line = context.get(i);
            if (line == null) continue;
            if (unitTexts != null && !line.isEmpty() && unitTexts.contains(line)) continue;
            line = TextFilter.stripFormatting(line).replace('\n', ' ').strip();
            if (line.isEmpty()) {
                rows.text.append("[SECTION]\n");
                emitted++;
                continue;
            }
            if (used + rows.text.length() + line.length() + 28 > MAX_CONTEXT_CHARS) break;
            rows.text.append("[L").append(i).append(':')
                    .append(contextKind(line)).append("] ")
                    .append(line).append('\n');
            rows.textRows++;
            emitted++;
        }
        rows.truncated = i < context.size();
        return rows;
    }

    private static String contextKind(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(?:ability|skill|mana cost|cooldown|能力|技能|魔力消耗|冷卻).*")) {
            return "ABILITY";
        }
        if (lower.matches(".*(?:bin price|avg\\. price|item value|obtained|museum|售價|價格|博物館).*")) {
            return "MARKET";
        }
        if (lower.matches(".*(?:gear score|damage|strength|speed|intelligence|fortune|health|defense|裝備分數|傷害|力量|速度|智力|財富|生命|防禦).*[:：].*")) {
            return "STAT";
        }
        if (lower.matches(".*\\b(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)\\b.*")) return "ENCHANT";
        return "TEXT";
    }

    String buildPrompt(List<String> texts, String targetLang) {
        int base = anchorBase(texts, null);
        List<AiWireItem> wire = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) wire.add(maskHardLines(texts.get(i), i));
        return buildAnchoredPrompt(wire, base);
    }

    private static String buildAnchoredPrompt(List<AiWireItem> items, int base) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(base + i * 2).append(' ').append(items.get(i).wire())
                    .append(' ').append(base + i * 2 + 1).append('\n');
        }
        return sb.toString();
    }

    private String buildRequestBody(AiSettings s, String targetLang, String numberedLines,
                                    int promptTokens, boolean noReasoning) {
        String model = s.model();
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", 0.3);
        if (noReasoning && wantsNoReasoning(model)) {
            // Gemini Flash models may enable dynamic thinking on the OpenAI-compatible
            // layer; short translation lines do not benefit from those extra tokens.
            root.addProperty("reasoning_effort", "none");
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system",
                buildSystemPrompt(targetLang, s.glossary(), numberedLines, promptTokens)));
        messages.add(message("user", numberedLines));
        root.add("messages", messages);
        return GSON.toJson(root);
    }

    // ---- Minecraft-aware system prompt ----

    /**
     * Build the system message. Unconditionally frames the text as Minecraft (Java Edition
     * and mods) in-game strings and asks for Minecraft's established terminology. The former
     * built-in glossary was intentionally removed: repeating dozens of unrelated terms on
     * every request was a substantial fixed token cost. Explicit user overrides remain.
     */
    static String buildSystemPrompt(String targetLang, List<String> userGlossary) {
        return buildSystemPrompt(targetLang, userGlossary, null);
    }

    /**
     * Build the compact system message and include only glossary entries that occur in this
     * request. Sending the complete glossary on every HUD change used more input tokens than
     * the text being translated; exact request-local filtering preserves terminology without
     * paying that fixed cost.
     */
    static String buildSystemPrompt(String targetLang, List<String> userGlossary,
                                    String requestText) {
        return buildSystemPrompt(targetLang, userGlossary, requestText, PROMPT_ALL_TOKENS);
    }

    /** {@link #promptTokens} flags: the request's units carry at least one ⟦…⟧ placeholder
     *  of any kind (⟦n⟧, ⟦MTn⟧, CS, WS, PB, hard-line slots) / a CS pair / a WS slot / a PB. */
    static final int PROMPT_TOKEN_ANY = 1;
    static final int PROMPT_TOKEN_CS = 2;
    static final int PROMPT_TOKEN_WS = 4;
    static final int PROMPT_TOKEN_PB = 8;
    static final int PROMPT_ALL_TOKENS =
            PROMPT_TOKEN_ANY | PROMPT_TOKEN_CS | PROMPT_TOKEN_WS | PROMPT_TOKEN_PB;

    /** Which placeholder clauses this request needs, judged on its anchored units only
     *  (context rows are reference text and never have to be copied). */
    private static int promptTokens(List<AiWireItem> wire) {
        List<String> units = new ArrayList<>(wire.size());
        for (AiWireItem item : wire) units.add(item.wire());
        return promptTokens(units);
    }

    static int promptTokens(java.util.Collection<String> units) {
        int flags = 0;
        for (String unit : units) {
            if (unit == null || unit.indexOf('⟦') < 0) continue;
            java.util.regex.Matcher m = ANY_TOKEN.matcher(unit);
            while (m.find()) {
                flags |= PROMPT_TOKEN_ANY;
                String inner = m.group().substring(1, m.group().length() - 1).replace(" ", "");
                if (inner.startsWith("/")) inner = inner.substring(1);
                if (inner.startsWith("CS")) flags |= PROMPT_TOKEN_CS;
                else if (inner.startsWith("WS")) flags |= PROMPT_TOKEN_WS;
                else if (inner.startsWith("PB")) flags |= PROMPT_TOKEN_PB;
            }
            if (flags == PROMPT_ALL_TOKENS) break;
        }
        return flags;
    }

    /** Q2: the placeholder, CS, WS and PB clauses are appended only when this request's units
     * carry such a token. With every token present the text is exactly the former
     * unconditional prompt, so no request's system prompt becomes longer. */
    static String buildSystemPrompt(String targetLang, List<String> userGlossary,
                                    String requestText, int tokens) {
        String lang = langName(targetLang);
        StringBuilder sb = new StringBuilder();
        sb.append("Translate Minecraft Java/mod in-game text into ").append(lang).append(". ")
                .append("Use official Minecraft translations as the terminology baseline for vanilla concepts, not as a rigid word-for-word template. ")
                .append("Adapt naturally to the detected server/mod genre and keep wording coherent across lines. ")
                .append("The source may be vanilla Minecraft or any server/mod genre, including RPG/MMO equipment, stats, abilities, quests and economy. ")
                .append("Infer ambiguous terms from the entire visible-block context, never as isolated dictionary labels. ")
                .append("Each input unit begins and ends with a unique five-digit boundary token. Return the same boundary tokens exactly once, in the same order, with only that unit's translation between its pair and no commentary outside the pairs. ")
                .append("Never merge, split, add, remove or reorder anchored units; the program owns all sections, PB line breaks and blank lines. ")
                .append("Keep numbers, symbols and formatting codes intact. ")
                .append("Translate ordinary UI, item and location terms completely; do not leave a source-language location word unchanged while translating the rest. ")
                .append("Translate each word as a WHOLE: NEVER mix the original script and the target script inside a single word. ")
                .append("For names, translate/transliterate the WHOLE name or keep it unchanged; never turn \"jacob\" into \"傑cob\".");
        boolean cs = (tokens & PROMPT_TOKEN_CS) != 0;
        boolean ws = (tokens & PROMPT_TOKEN_WS) != 0;
        // Wording matches the wire format AiWireCodec substitutes in for the actual
        // request/response (never the ⟦...⟧ internal/cache format): {tag}, {/tag},
        // {csn}/{/csn}, {wsn}, {pbn}. See AiWireCodec's class doc for why.
        if ((tokens & PROMPT_ALL_TOKENS) != 0) {
            sb.append(" Copy every {tag} placeholder verbatim, including its braces and any leading /.");
        }
        if (cs) {
            sb.append(" Treat each {csn}...{/csn} pair like a BBCode style tag: keep the complete pair around the translation of the same semantic phrase even when target grammar reorders phrases.");
        }
        if (cs && ws) sb.append(" Never drop, nest incorrectly, or duplicate cs tags or {wsn} layout slots.");
        else if (cs) sb.append(" Never drop, nest incorrectly, or duplicate cs tags.");
        else if (ws) sb.append(" Never drop or duplicate {wsn} layout slots.");
        if ((tokens & PROMPT_TOKEN_PB) != 0) {
            sb.append(" Treat each {pbn} as an immutable line break inside one semantic paragraph: keep all pb tokens in the same order while translating coherently across them.");
        }

        if (isTraditionalChineseTarget(targetLang)) {
            sb.append(" Prefer established Minecraft and Traditional-Chinese gaming wording")
                    .append(" (for example, Enchant → 附魔). In an RPG stat or combat block,")
                    .append(" Damage means 傷害, not 損壞; interpret equipment and character")
                    .append(" stat labels by their gameplay meaning likewise. For server/mod-specific")
                    .append(" content, write concise, natural Taiwan player-facing RPG/MMO/mod text")
                    .append(" instead of stiff dictionary translations. Keep established proper names")
                    .append(" unchanged when translating them would be awkward or ambiguous.");
        }

        if (isChineseTarget(targetLang)) {
            List<String> user = relevantGlossary(parseUserGlossary(userGlossary), requestText);
            if (!user.isEmpty()) {
                sb.append("\nUser term overrides: ")
                        .append(String.join("; ", user))
                        .append('.');
            }
        }
        return sb.toString();
    }

    static List<String> relevantGlossary(List<String> entries, String requestText) {
        if (entries == null || entries.isEmpty() || requestText == null || requestText.isBlank()) {
            return List.of();
        }
        String haystack = requestText.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null) continue;
            int arrow = entry.indexOf('→');
            String english = (arrow < 0 ? entry : entry.substring(0, arrow)).strip();
            boolean found = false;
            for (String alternative : english.split("/")) {
                String term = alternative.strip().toLowerCase(Locale.ROOT);
                if (!term.isEmpty() && containsTerm(haystack, term)) {
                    found = true;
                    break;
                }
            }
            if (found) out.add(entry);
        }
        return out;
    }

    private static boolean containsTerm(String text, String term) {
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + 1)) {
            int end = at + term.length();
            boolean left = at == 0 || !asciiWord(text.charAt(at - 1));
            boolean right = end == text.length() || !asciiWord(text.charAt(end));
            if (left && right) return true;
        }
        return false;
    }

    private static boolean asciiWord(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    /** Parse user "英文=中文" glossary lines into compact "English → 中文" prompt entries.
     *  Splits on the FIRST {@code '='}; blank lines and lines missing an English or Chinese
     *  side are skipped so a malformed entry never corrupts the prompt. */
    static List<String> parseUserGlossary(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue; // no '=' at all, or empty English side
            String en = line.substring(0, eq).strip();
            String zh = line.substring(eq + 1).strip();
            if (en.isEmpty() || zh.isEmpty()) continue;
            out.add(en + " → " + zh);
        }
        return out;
    }

    /** Any Chinese target (Traditional or Simplified). */
    static boolean isChineseTarget(String targetLang) {
        return langName(targetLang).contains("Chinese");
    }

    /** True only for Traditional Chinese prompt wording. */
    static boolean isTraditionalChineseTarget(String targetLang) {
        return langName(targetLang).contains("Traditional Chinese");
    }

    /** Whether to disable model "thinking" for a Gemini Flash-family model id. */
    static boolean wantsNoReasoning(String model) {
        String m = model == null ? "" : model.toLowerCase();
        return m.contains("gemini") && m.contains("flash") && !m.contains("2.0");
    }

    /** Map a target language code (zh-TW / zh-CN / …) to the human name used in the AI prompt,
     *  so switching 繁體 ↔ 簡體 actually changes the AI output (not just the Google backend). */
    public static String langName(String targetLang) {
        String t = targetLang == null ? "" : targetLang.toLowerCase().replace('_', '-');
        if (t.startsWith("zh-cn") || t.startsWith("zh-hans") || t.equals("zh")) return "Simplified Chinese (zh-CN)";
        if (t.startsWith("zh-tw") || t.startsWith("zh-hant") || t.startsWith("zh-hk") || t.startsWith("zh")) return "Traditional Chinese (zh-TW)";
        return targetLang; // non-Chinese target: pass the code through
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    // ---- HTTP with optional key rotation ----

    /** Retries per key for transient server errors (e.g. Gemini's frequent 503 "overloaded"). */
    private static final int RETRIES_PER_KEY = 2;
    private static final long RETRY_BACKOFF_MS = 700L;

    private String postWithKeyRotation(AiSettings s, SettingsState health, String body, String fallbackBody) throws TranslationException {
        List<String> keys = s.apiKeys();
        String url = chatCompletionsUrl(s.baseUrl());
        IOException last = null;
        int usableKeys = 0;
        int attemptedKeys = 0;
        int rateLimitedKeys = 0;
        boolean hasUsableKey = keys.stream().anyMatch(key -> key != null && !key.isBlank());
        if (!hasUsableKey) {
            try {
                pacer.acquireForAi();
                String content = parseContent(transport.post(url, body, Map.of()));
                resetRateLimitGate(health);
                return content;
            } catch (IOException e) {
                if (isRateLimited(e)) tripRateLimitGate(health);
                throw new TranslationException("AI request failed (no API key): " + e.getMessage(), e);
            }
        }
        // Start at a rotating offset so load spreads across keys.
        int start = Math.floorMod(health.keyCursor.getAndIncrement(), keys.size());
        for (int n = 0; n < keys.size(); n++) {
            String key = keys.get((start + n) % keys.size());
            if (key == null || key.isBlank()) continue;
            usableKeys++;
            key = key.trim();
            if (isKeyUnavailable(health, key)) continue;
            attemptedKeys++;
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + key);
            for (int attempt = 0; attempt <= RETRIES_PER_KEY; attempt++) {
                try {
                    pacer.acquireForAi(); // 事前冷卻：every outbound request is spaced by requestCooldownMs
                    String content = parseContent(transport.post(url, body, headers));
                    clearKeyState(health, key);
                    resetRateLimitGate(health); // any success proves the quota is back
                    return content;
                } catch (IOException e) {
                    last = e;
                    // 429: this key's quota is gone RIGHT NOW — retrying it only digs the
                    // hole deeper. Move straight to the next key.
                    if (isRateLimited(e)) {
                        rateLimitedKeys++;
                        markKeyUnavailable(health, key, clock.getAsLong() + KEY_RATE_LIMIT_COOLDOWN_MS, true);
                        break;
                    }
                    // Invalid credentials should stay quarantined until the user edits
                    // the provider settings; retrying them only adds latency/noise.
                    if (isAuthenticationFailure(e)) {
                        markKeyUnavailable(health, key, Long.MAX_VALUE, false);
                        break;
                    }
                    // A 400 most likely means this endpoint rejects an optional field
                    // (e.g. reasoning_effort): drop to the plain body and retry once.
                    if (fallbackBody != null && isBadRequest(e)) {
                        body = fallbackBody;
                        fallbackBody = null;
                        continue;
                    }
                    // Retry the SAME key on a transient 5xx (overloaded); else move to the next key.
                    if (attempt < RETRIES_PER_KEY && isTransient(e)) {
                        sleep(RETRY_BACKOFF_MS * (attempt + 1));
                        continue;
                    }
                    if (isTransient(e)) {
                        markKeyUnavailable(health, key, clock.getAsLong() + KEY_TRANSIENT_COOLDOWN_MS, false);
                    }
                    break;
                }
            }
        }
        // A FULL rotation of 429s means the whole quota is exhausted: trip the gate.
        if (attemptedKeys > 0 && rateLimitedKeys == attemptedKeys && allKeysUnavailable(health, keys)) {
            tripRateLimitGate(health);
        }
        if (usableKeys > 0 && attemptedKeys == 0) {
            throw new TranslationException("AI request deferred: all API keys are cooling down");
        }
        throw new TranslationException("AI request failed (all keys): "
                + (last == null ? "no usable key" : last.getMessage()), last);
    }

    private void tripRateLimitGate(SettingsState health) {
        synchronized (health) {
            health.penaltyMs = (health.penaltyMs == 0)
                    ? RATE_LIMIT_BASE_PENALTY_MS
                    : Math.min(health.penaltyMs * 2, RATE_LIMIT_MAX_PENALTY_MS);
            health.rateLimitedUntil = clock.getAsLong() + health.penaltyMs;
        }
    }

    private void resetRateLimitGate(SettingsState health) {
        if (health.rateLimitedUntil == 0) return; // fast path: gate never tripped
        synchronized (health) {
            health.penaltyMs = 0;
            health.rateLimitedUntil = 0;
        }
    }

    private static boolean isBadRequest(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("HTTP 400");
    }

    private static boolean isRateLimited(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("HTTP 429");
    }

    private static boolean isAuthenticationFailure(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("HTTP 401") || m.contains("HTTP 403"));
    }

    private synchronized SettingsState refreshSettingsState(AiSettings s) {
        String signature = (s.baseUrl() == null ? "" : s.baseUrl().trim()) + '\n'
                + (s.model() == null ? "" : s.model().trim()) + '\n'
                + String.join("\n", s.apiKeys().stream()
                .filter(java.util.Objects::nonNull).map(String::trim).toList());
        if (settingsState == null || !signature.equals(settingsState.signature)) {
            settingsState = new SettingsState(signature);
        }
        return settingsState;
    }

    private boolean isKeyUnavailable(SettingsState health, String key) {
        synchronized (health) {
            KeyState state = health.keyStates.get(key);
            if (state == null) return false;
            if (state.unavailableUntil == Long.MAX_VALUE) return true;
            if (clock.getAsLong() < state.unavailableUntil) return true;
            health.keyStates.remove(key);
            return false;
        }
    }

    private void markKeyUnavailable(SettingsState health, String key, long until, boolean rateLimited) {
        synchronized (health) {
            health.keyStates.put(key, new KeyState(until, rateLimited));
        }
    }

    private void clearKeyState(SettingsState health, String key) {
        synchronized (health) {
            health.keyStates.remove(key);
        }
    }

    private boolean allKeysUnavailable(SettingsState health, List<String> keys) {
        boolean found = false;
        synchronized (health) {
            long now = clock.getAsLong();
            for (String raw : keys) {
                if (raw == null || raw.isBlank()) continue;
                found = true;
                KeyState state = health.keyStates.get(raw.trim());
                if (state == null || (state.unavailableUntil != Long.MAX_VALUE
                        && now >= state.unavailableUntil)) return false;
            }
        }
        return found;
    }

    private static boolean isTransient(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("HTTP 500") || m.contains("HTTP 502")
                || m.contains("HTTP 503") || m.contains("HTTP 504") || m.contains("HTTP 529"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Join base URL with the chat-completions path, tolerating a trailing slash or included path. */
    public static String chatCompletionsUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    // ---- response parsing ----

    private String parseContent(String responseBody) throws IOException {
        try {
            JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new IOException("no choices in AI response");
            }
            JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = msg.get("content").getAsString();
            if (content == null) throw new IOException("empty AI content");
            recordTokenUsage(root);
            return content;
        } catch (RuntimeException e) {
            throw new IOException("bad AI response: " + e.getMessage(), e);
        }
    }

    private void recordTokenUsage(JsonObject root) {
        SessionTokenUsage counter = tokenUsage;
        if (counter == null || root == null) return;
        JsonObject usage = objectMember(root, "usage");
        if (usage == null) return;

        long input = firstLong(usage, "prompt_tokens", "input_tokens", "inputTokens");
        long output = firstLong(usage, "completion_tokens", "output_tokens", "outputTokens");
        long total = firstLong(usage, "total_tokens", "totalTokens");
        long cached = firstLong(usage, "cached_input_tokens", "cachedInputTokens");
        long reasoning = firstLong(usage, "reasoning_output_tokens", "reasoningOutputTokens");

        JsonObject inputDetails = objectMember(usage, "prompt_tokens_details");
        if (inputDetails == null) inputDetails = objectMember(usage, "input_tokens_details");
        if (inputDetails != null) {
            cached = Math.max(cached,
                    firstLong(inputDetails, "cached_tokens", "cached_input_tokens"));
        }
        JsonObject outputDetails = objectMember(usage, "completion_tokens_details");
        if (outputDetails == null) outputDetails = objectMember(usage, "output_tokens_details");
        if (outputDetails != null) {
            reasoning = Math.max(reasoning,
                    firstLong(outputDetails, "reasoning_tokens", "reasoning_output_tokens"));
        }
        counter.recordRequest(input, cached, output, reasoning, total);
    }

    private static JsonObject objectMember(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static long firstLong(JsonObject object, String... keys) {
        if (object == null) return 0L;
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) continue;
            try {
                return Math.max(0L, value.getAsLong());
            } catch (RuntimeException ignored) {
                // Try the next compatible field spelling.
            }
        }
        return 0L;
    }

    private record AiLineSlot(String token, String original) {}
    private record AiWireItem(String wire, List<AiLineSlot> hardLines) {}

    private static AiWireItem maskHardLines(String text, int itemIndex) {
        String source = text == null ? "" : text;
        StringBuilder wire = new StringBuilder(source.length());
        List<AiLineSlot> slots = new ArrayList<>();
        int slotIndex = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch != '\r' && ch != '\n') {
                wire.append(ch);
                continue;
            }
            String original;
            if (ch == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                original = "\r\n";
                i++;
            } else original = Character.toString(ch);
            String token;
            do {
                token = "⟦AI_LINE_" + itemIndex + "_" + slotIndex++ + "⟧";
            } while (source.contains(token));
            wire.append(token);
            slots.add(new AiLineSlot(token, original));
        }
        return new AiWireItem(wire.toString(), List.copyOf(slots));
    }

    private static String restoreHardLines(String translated, AiWireItem item) {
        if (translated == null || item == null) return null;
        String restored = translated;
        for (AiLineSlot slot : item.hardLines()) {
            int first = restored.indexOf(slot.token());
            if (first < 0 || restored.indexOf(slot.token(), first + slot.token().length()) >= 0) {
                return null;
            }
            restored = restored.replace(slot.token(), slot.original());
        }
        return restored.contains("⟦AI_LINE_") ? null : restored;
    }

    /** Pick a compact boundary range not present in request text or any of its contexts. */
    private static int anchorBase(List<String> texts, List<List<String>> contexts) {
        int base = BATCH_ANCHOR_BASE;
        int count = Math.max(1, texts == null ? 0 : texts.size() * 2);
        while (true) {
            boolean collision = containsAnchorRange(texts, base, count)
                    || containsAnchorRangeInContexts(contexts, base, count);
            if (!collision) return base;
            base += 2_000;
        }
    }

    private static boolean containsAnchorRangeInContexts(List<List<String>> contexts, int base, int count) {
        if (contexts == null) return false;
        List<String> previous = null;
        for (List<String> context : contexts) {
            // Units sharing a context are contiguous: scan each context once.
            if (context == null || context == previous || context.equals(previous)) continue;
            previous = context;
            if (containsAnchorRange(context, base, count)) return true;
        }
        return false;
    }

    private static boolean containsAnchorRange(List<String> values, int base, int count) {
        if (values == null) return false;
        for (String value : values) {
            if (value == null) continue;
            for (int i = 0; i < count; i++) {
                if (value.contains(Integer.toString(base + i))) return true;
            }
        }
        return false;
    }

    public static List<String> extractAnchoredBatch(String content, int expected, int base) {
        if (content == null || expected < 0) return null;
        List<String> out = new ArrayList<>(expected);
        int cursor = 0;
        for (int i = 0; i < expected; i++) {
            String open = Integer.toString(base + i * 2);
            String close = Integer.toString(base + i * 2 + 1);
            int start = content.indexOf(open, cursor);
            if (start < 0 || content.indexOf(open) != start
                    || content.indexOf(open, start + open.length()) >= 0) return null;
            if (!content.substring(cursor, start).isBlank()) return null;
            int valueStart = start + open.length();
            int end = content.indexOf(close, valueStart);
            if (end < valueStart || content.indexOf(close) != end
                    || content.indexOf(close, end + close.length()) >= 0) return null;
            out.add(content.substring(valueStart, end).strip());
            cursor = end + close.length();
        }
        return content.substring(cursor).isBlank() ? out : null;
    }

    private static final java.util.regex.Pattern NUMBERED =
            java.util.regex.Pattern.compile("^(\\d+)\\s*[.)、]\\s*(.*)$");

    /**
     * Re-assemble the model's reply into exactly {@code expected} translations, keyed by
     * the leading "N." numbering. A translation wrapped across multiple physical lines is
     * joined back to its number; blank lines and a stray trailing note are tolerated; the
     * result is padded/truncated to {@code expected} so one misbehaving line never fails
     * the whole batch (empty entries are treated as per-item failures downstream).
     */
    public static List<String> parseNumbered(String content, int expected) {
        if (content == null) content = "";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(0, expected); i++) out.add("");
        List<String> unnumbered = new ArrayList<>();
        StringBuilder cur = null;
        int curIndex = -1;
        boolean sawNumber = false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher m = NUMBERED.matcher(line);
            if (m.matches()) {
                sawNumber = true;
                if (cur != null && curIndex >= 0) out.set(curIndex, cur.toString());
                int number;
                try {
                    number = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    number = -1;
                }
                int index = number - 1;
                curIndex = index >= 0 && index < expected && seen.add(index) ? index : -1;
                cur = curIndex >= 0 ? new StringBuilder(m.group(2).strip()) : null;
            } else if (cur != null) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(line); // continuation of a wrapped translation
            } else if (!sawNumber) {
                unnumbered.add(line);
            }
        }
        if (cur != null && curIndex >= 0) out.set(curIndex, cur.toString());
        // No numbering at all: fall back to one entry per non-blank line.
        if (!sawNumber) {
            out.clear();
            out.addAll(unnumbered);
            while (out.size() > expected) out.remove(out.size() - 1);
            while (out.size() < expected) out.add("");
        }
        return out;
    }
}
