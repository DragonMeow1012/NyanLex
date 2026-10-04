package com.dragonmeow.nyanlex.translate;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Translator} backed by the free (unofficial) Google endpoint
 * {@code translate.googleapis.com/translate_a/single}.
 *
 * <p>Inputs carrying ⟦…⟧ placeholder tokens are translated in WHOLE-LINE sentinel mode —
 * see {@link #translateWholeLine}: ⟦CS#⟧ colour markers and ⟦MT#⟧/mask slots become
 * numeric sentinels, and the sentence goes to Google as
 * ONE request with full context, so grammar stays coherent ("won" is a verb, not a currency).</p>
 *
 * <p>The HTTP layer is injected via {@link HttpTransport} so this class can be
 * unit-tested with an inline fake transport (no real network access).</p>
 */
public final class GoogleFreeTranslator implements Translator {

    /**
     * UNOFFICIAL endpoint: this is the key-free web endpoint, not a supported public API.
     * It may stop working or be rate-limited at any time.
     */
    static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    static final String FALLBACK_ENDPOINT = "https://translate.google.com/translate_a/single";

    private static final java.util.regex.Pattern ANY_TOKEN =
            java.util.regex.Pattern.compile("\\u27E6[^\\u27E6\\u27E7]*\\u27E7");

    private final HttpTransport transport;
    private final String sourceLang;
    private final RequestPacer pacer;
    private final MachineTranslationGate gate;
    /** Last endpoint that completed a request. Kept for the session so a blocked host is
     * not probed again for every translated line. */
    private volatile String activeEndpoint = ENDPOINT;

    public GoogleFreeTranslator(HttpTransport transport, String sourceLang) {
        this(transport, sourceLang, RequestPacer.disabled());
    }

    /** Pacer-injecting constructor: {@code pacer} throttles EVERY outbound HTTP request
     *  (whole-line, per-segment and batch calls alike — they all funnel through
     *  {@link #requestOnce}). */
    public GoogleFreeTranslator(HttpTransport transport, String sourceLang, RequestPacer pacer) {
        this(transport, sourceLang, pacer, new MachineTranslationGate());
    }

    /** Gate-injecting constructor: production passes {@link MachineTranslationGate#shared()}. */
    public GoogleFreeTranslator(HttpTransport transport, String sourceLang, RequestPacer pacer,
                                MachineTranslationGate gate) {
        this.gate = gate == null ? new MachineTranslationGate() : gate;
        this.transport = transport;
        this.sourceLang = (sourceLang == null || sourceLang.isBlank()) ? "auto" : sourceLang;
        this.pacer = pacer == null ? RequestPacer.disabled() : pacer;
    }

    /** Build the GET URL for the given text and target language (visible for testing). */
    public String buildUrl(String text, String targetLang) {
        return buildUrl(ENDPOINT, text, targetLang);
    }

    private String buildUrl(String endpoint, String text, String targetLang) {
        String sl = URLEncoder.encode(sourceLang, StandardCharsets.UTF_8);
        String tl = URLEncoder.encode(targetLang, StandardCharsets.UTF_8);
        String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
        return endpoint + "?client=gtx&sl=" + sl + "&tl=" + tl + "&dt=t&q=" + q;
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        if (text != null && ANY_TOKEN.matcher(text).find()) {
            return translateWholeLine(text, targetLang);
        }
        TranslationResult r = requestOnce(text, targetLang);
        if (!preservesTokens(text, r.translatedText())) {
            // Protocol damage is a retryable provider failure, never evidence that the
            // source legitimately needs no translation.
            return new TranslationResult("", r.detectedSourceLang(), false, "format/token lost");
        }
        return r;
    }

    /** One raw endpoint round-trip, no token handling (the mode-independent primitive). */
    private TranslationResult requestOnce(String text, String targetLang) throws TranslationException {
        boolean probe = gate.acquire(); // closed gate: RequestsPausedException, nothing is sent
        boolean settled = false;
        try {
            String body;
            String endpoint = activeEndpoint;
            try {
                body = requestEndpoint(endpoint, text, targetLang, probe);
            } catch (IOException e) {
                if (!MachineTranslationGate.isRateLimitMessage(e.getMessage())) {
                    throw new TranslationException("http error: " + e.getMessage(), e);
                }
                // Google exposes the same response format on two hosts. A 429 is often
                // host-specific, so try the other host exactly once before closing the
                // global gate. The pacer still spaces this second outbound request.
                String fallback = alternateEndpoint(endpoint);
                try {
                    body = requestEndpoint(fallback, text, targetLang, probe);
                    activeEndpoint = fallback;
                } catch (IOException fallbackError) {
                    settled = true;
                    String detail = MachineTranslationGate.isRateLimitMessage(fallbackError.getMessage())
                            ? fallbackError.getMessage()
                            : e.getMessage() + "; alternate failed: " + fallbackError.getMessage();
                    throw rateLimited(detail);
                }
            }
            TranslationResult result = GoogleResponseParser.parse(body);
            gate.onSuccess();
            settled = true;
            return result;
        } finally {
            if (!settled) gate.onAbort(probe);
        }
    }

    private String requestEndpoint(String endpoint, String text, String targetLang, boolean probe)
            throws IOException {
        pacer.acquire(); // 事前冷卻：every outbound request is spaced by requestCooldownMs
        gate.recheck(probe);
        String body = transport.get(buildUrl(endpoint, text, targetLang));
        if (MachineTranslationGate.isBlockPage(body)) throw new IOException("unusual traffic page");
        return body;
    }

    private static String alternateEndpoint(String endpoint) {
        return FALLBACK_ENDPOINT.equals(endpoint) ? ENDPOINT : FALLBACK_ENDPOINT;
    }

    /** Close the gate and abandon the request as "unsent, not failed". */
    private RequestsPausedException rateLimited(String detail) {
        int minutes = gate.onRateLimited();
        DebugErrorLog.report(DebugErrorLog.HTTP_ERROR, "Google translate rate limited (429)",
                "backoffMinutes", Integer.toString(minutes),
                "detail", detail == null ? "" : detail.substring(0, Math.min(detail.length(), 200)));
        return new RequestsPausedException();
    }

    @Override
    public boolean sendBlocked() {
        return gate.blocksRequests();
    }

    /** First numeric sentinel value. Five plain digits: Google keeps such numbers verbatim
     *  (no thousand separators to normalise), and a TEMPLATED line has no bare digits of its
     *  own (numbers all live inside ⟦MT#⟧ slots), so sentinels are unambiguous. */
    private static final int SENTINEL_BASE = 70001;

    /**
     * Whole-line mode for token-carrying lines (user decision: 「一句一句翻」— the old
     * fragment-wise requests killed sentence coherence: an isolated "won" came back as the
     * currency, not the verb). Every ⟦…⟧ token — CS style boundaries, MT template
     * slots, and name masks — is replaced by a numeric sentinel, and the sentence goes
     * to Google as ONE request with its full context. Every sentinel must come back exactly
     * once; any loss or mutation fails the WHOLE line so the retry ledger picks it up later.
     * The returned value carries
     * the complete original token set, including balanced CS style boundaries.
     */
    private TranslationResult translateWholeLine(String text, String targetLang) throws TranslationException {
        StringBuilder plain = new StringBuilder(text.length());
        List<String> slots = new ArrayList<>(); // sentinel index -> original token, in order
        java.util.regex.Matcher m = ANY_TOKEN.matcher(text);
        int pos = 0;
        while (m.find()) {
            plain.append(text, pos, m.start());
            String token = m.group();
            plain.append(SENTINEL_BASE + slots.size());
            slots.add(token);
            pos = m.end();
        }
        plain.append(text, pos, text.length());

        TranslationResult r;
        try {
            r = requestOnce(plain.toString(), targetLang);
        } catch (TranslationException e) {
            // Preserve the distinction between transport failure and an unusable content
            // response. TranslationCache backs network errors off, but only the latter is
            // eligible for the three-strike durable keep-original rule.
            throw e;
        }
        String translated = (r == null) ? null : r.translatedText();
        if (translated == null || translated.isBlank()) {
            return new TranslationResult("", r == null ? null : r.detectedSourceLang(),
                    false, "empty response");
        }
        // Every sentinel must survive EXACTLY once as a complete numeric marker.
        // Substrings such as 170001 must never impersonate marker 70001.
        Map<String, String> replacements = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            replacements.put(Integer.toString(SENTINEL_BASE + i), slots.get(i));
        }
        String out = NumericMarkerCodec.restoreExactlyOnce(translated, replacements);
        if (out == null) return new TranslationResult("", r.detectedSourceLang(),
                false, "format/token lost");
        if (!preservesTokens(text, out)) { // belt: the MT/mask multiset must match
            return new TranslationResult("", r.detectedSourceLang(),
                    false, "format/token lost");
        }
        return new TranslationResult(out, r.detectedSourceLang());
    }

    /** Char budget per joined request, pre-URL-encoding (keeps the GET URL well under limits). */
    static final int MAX_CHARS_PER_REQUEST = 1600;
    private static final int BATCH_ANCHOR_OVERHEAD = 12;

    /**
     * Batch translate with a numeric start/end anchor around every independent cache
     * item. Newlines alone are not a safe protocol: a provider can keep the same line
     * count while moving content between adjacent keys. Anchors preserve batching while
     * making reconstruction deterministic; damaged chunks are bisected and retried.
     */
    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        if (texts.isEmpty()) return List.of();
        List<TranslationResult> out = new ArrayList<>(texts.size());
        int start = 0;
        while (start < texts.size()) {
            int end = start + 1;
            int chars = texts.get(start).length() + BATCH_ANCHOR_OVERHEAD;
            while (end < texts.size()
                    && chars + 1 + texts.get(end).length() + BATCH_ANCHOR_OVERHEAD
                    <= MAX_CHARS_PER_REQUEST) {
                chars += 1 + texts.get(end).length() + BATCH_ANCHOR_OVERHEAD;
                end++;
            }
            translateChunk(texts.subList(start, end), targetLang, out);
            start = end;
        }
        return out;
    }

    private void translateChunk(List<String> texts, String targetLang, List<TranslationResult> out)
            throws TranslationException {
        if (texts.size() == 1) {
            out.add(translate(texts.get(0), targetLang));
            return;
        }
        // Protect every rich marker and hard line break with a numeric sentinel, then
        // wrap each independent cache item in its own numeric start/end anchors. This
        // lets GT receive the whole collected batch in one HTTP request without ever
        // seeing the renderer protocol itself.
        int protectedCount = texts.stream().mapToInt(GoogleFreeTranslator::protectedUnitCount).sum();
        int sentinelCount = texts.size() * 2 + protectedCount;
        int anchorBase = batchSentinelBase(texts, sentinelCount);
        int[] nextSentinel = {anchorBase + texts.size() * 2};
        List<BatchMasked> masked = new ArrayList<>(texts.size());
        for (String text : texts) masked.add(maskForBatch(text, nextSentinel));
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(anchorBase + i * 2)
                    .append(masked.get(i).wireText())
                    .append(anchorBase + i * 2 + 1);
        }
        TranslationResult combined = requestOnce(joined.toString(), targetLang);
        List<String> parts = extractAnchoredBatch(
                combined == null ? null : combined.translatedText(), texts.size(),
                anchorBase, sentinelCount);
        if (parts != null) {
            for (int i = 0; i < parts.size(); i++) {
                String src = texts.get(i);
                String restored = restoreBatchPart(parts.get(i), masked.get(i));
                if (restored == null) {
                    String reason = src.indexOf('\n') >= 0 || src.indexOf('\r') >= 0
                            || src.contains("⟦PB") ? "paragraph lost" : "format/token lost";
                    out.add(new TranslationResult("", combined.detectedSourceLang(), false, reason));
                } else if (!preservesTokens(src, restored)) {
                    out.add(new TranslationResult("", combined.detectedSourceLang(),
                            false, "format/token lost"));
                } else if (restored.isBlank()) {
                    out.add(new TranslationResult("", combined.detectedSourceLang(),
                            false, "empty response"));
                } else {
                    out.add(new TranslationResult(restored, combined.detectedSourceLang()));
                }
            }
            return;
        }
        // Misaligned: bisect to isolate the line the endpoint merged/split.
        int mid = texts.size() / 2;
        translateChunk(texts.subList(0, mid), targetLang, out);
        translateChunk(texts.subList(mid, texts.size()), targetLang, out);
    }

    private record BatchSlot(String sentinel, String original) {
    }

    private record BatchMasked(String wireText, List<BatchSlot> slots) {
    }

    private static int protectedUnitCount(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        java.util.regex.Matcher matcher = ANY_TOKEN.matcher(text);
        while (matcher.find()) count++;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || (ch == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                count++;
            }
        }
        return count;
    }

    private static BatchMasked maskForBatch(String text, int[] nextSentinel) {
        String source = text == null ? "" : text;
        StringBuilder wire = new StringBuilder(source.length());
        List<BatchSlot> slots = new ArrayList<>();
        java.util.regex.Matcher matcher = ANY_TOKEN.matcher(source);
        int pos = 0;
        while (matcher.find()) {
            appendBatchLiteral(source, pos, matcher.start(), wire, slots, nextSentinel);
            addBatchSlot(matcher.group(), wire, slots, nextSentinel);
            pos = matcher.end();
        }
        appendBatchLiteral(source, pos, source.length(), wire, slots, nextSentinel);
        return new BatchMasked(wire.toString(), List.copyOf(slots));
    }

    private static void appendBatchLiteral(String source, int start, int end,
                                           StringBuilder wire, List<BatchSlot> slots,
                                           int[] nextSentinel) {
        for (int i = start; i < end; i++) {
            char ch = source.charAt(i);
            if (ch == '\r') {
                if (i + 1 < end && source.charAt(i + 1) == '\n') i++;
                addBatchSlot("\n", wire, slots, nextSentinel);
            } else if (ch == '\n') {
                addBatchSlot("\n", wire, slots, nextSentinel);
            } else {
                wire.append(ch);
            }
        }
    }

    private static void addBatchSlot(String original, StringBuilder wire,
                                     List<BatchSlot> slots, int[] nextSentinel) {
        String sentinel = Integer.toString(nextSentinel[0]++);
        wire.append(sentinel);
        slots.add(new BatchSlot(sentinel, original));
    }

    private static String restoreBatchPart(String translated, BatchMasked masked) {
        if (translated == null || masked == null) return null;
        Map<String, String> replacements = new LinkedHashMap<>();
        for (BatchSlot slot : masked.slots()) {
            replacements.put(slot.sentinel(), slot.original());
        }
        return NumericMarkerCodec.restoreExactlyOnce(translated, replacements);
    }

    private static int batchSentinelBase(List<String> texts, int sentinelCount) {
        int base = SENTINEL_BASE;
        outer:
        while (true) {
            for (String text : texts) {
                String source = text == null ? "" : text;
                for (int i = 0; i < sentinelCount; i++) {
                    if (source.contains(Integer.toString(base + i))) {
                        base += 2_000;
                        continue outer;
                    }
                }
            }
            return base;
        }
    }

    private static List<String> extractAnchoredBatch(String translated, int count,
                                                     int base, int markerCount) {
        return NumericMarkerCodec.extractAnchored(translated, count, base, markerCount);
    }

    /** True unless any source token (CS style boundary, MT slot, or name mask) was lost
     *  or altered by the translation. A mismatch means restoration would lie, so the
     *  caller keeps the original line. Order-independent multiset. */
    static boolean preservesTokens(String source, String translated) {
        List<String> want = tokensOf(source);
        if (want.isEmpty()) return true;              // nothing fragile to protect
        List<String> got = tokensOf(translated);
        if (got.size() != want.size()) return false;
        for (String t : got) if (!want.remove(t)) return false;
        return want.isEmpty();
    }

    private static List<String> tokensOf(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = ANY_TOKEN.matcher(text);
        while (m.find()) {
            out.add(m.group().replace(" ", ""));
        }
        return out;
    }
}
