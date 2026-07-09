package com.dragonmeow.mctranslator.translate;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Translator} backed by the free (unofficial) Google endpoint
 * {@code translate.googleapis.com/translate_a/single}.
 *
 * <p>Inputs carrying ⟦…⟧ placeholder tokens are translated in WHOLE-LINE sentinel mode —
 * see {@link #translateWholeLine}: ⟦CS#⟧ colour markers are stripped (the glue re-applies
 * colours), ⟦MT#⟧/mask slots become numeric sentinels, and the sentence goes to Google as
 * ONE request with full context, so grammar stays coherent ("won" is a verb, not a currency).</p>
 *
 * <p>The HTTP layer is injected via {@link HttpTransport} so this class can be
 * unit-tested with an inline fake transport (no real network access).</p>
 */
public final class GoogleFreeTranslator implements Translator {

    static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";

    private static final java.util.regex.Pattern ANY_TOKEN =
            java.util.regex.Pattern.compile("\\u27E6[^\\u27E6\\u27E7]*\\u27E7");

    private final HttpTransport transport;
    private final String sourceLang;

    public GoogleFreeTranslator(HttpTransport transport, String sourceLang) {
        this.transport = transport;
        this.sourceLang = (sourceLang == null || sourceLang.isBlank()) ? "auto" : sourceLang;
    }

    /** Build the GET URL for the given text and target language (visible for testing). */
    public String buildUrl(String text, String targetLang) {
        String sl = URLEncoder.encode(sourceLang, StandardCharsets.UTF_8);
        String tl = URLEncoder.encode(targetLang, StandardCharsets.UTF_8);
        String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
        return ENDPOINT + "?client=gtx&sl=" + sl + "&tl=" + tl + "&dt=t&q=" + q;
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        if (text != null && ANY_TOKEN.matcher(text).find()) {
            return translateWholeLine(text, targetLang);
        }
        TranslationResult r = requestOnce(text, targetLang);
        if (!preservesTokens(text, r.translatedText())) {
            return new TranslationResult(text, r.detectedSourceLang());
        }
        return r;
    }

    /** One raw endpoint round-trip, no token handling (the mode-independent primitive). */
    private TranslationResult requestOnce(String text, String targetLang) throws TranslationException {
        try {
            String body = transport.get(buildUrl(text, targetLang));
            return GoogleResponseParser.parse(body);
        } catch (IOException e) {
            throw new TranslationException("http error: " + e.getMessage(), e);
        }
    }

    /** First numeric sentinel value. Five plain digits: Google keeps such numbers verbatim
     *  (no thousand separators to normalise), and a TEMPLATED line has no bare digits of its
     *  own (numbers all live inside ⟦MT#⟧ slots), so sentinels are unambiguous. */
    private static final int SENTINEL_BASE = 70001;

    /**
     * Whole-line mode for token-carrying lines (user decision: 「一句一句翻」— the old
     * fragment-wise requests killed sentence coherence: an isolated "won" came back as the
     * currency, not the verb). ⟦CS#⟧ colour markers are stripped outright — the glue's
     * anchored fallback re-applies the colours — while every OTHER ⟦…⟧ token (⟦MT#⟧
     * template slots, name masks) is replaced by a numeric sentinel, and the sentence goes
     * to Google as ONE request with its full context. Every sentinel must come back exactly
     * once; any loss or mutation reverts the WHOLE line to the source (existing failure
     * semantics — the R9 provisional retry picks it up later). The returned value carries
     * MT/mask tokens but NO CS markers.
     */
    private TranslationResult translateWholeLine(String text, String targetLang) throws TranslationException {
        StringBuilder plain = new StringBuilder(text.length());
        List<String> slots = new ArrayList<>(); // sentinel index -> original token, in order
        java.util.regex.Matcher m = ANY_TOKEN.matcher(text);
        int pos = 0;
        while (m.find()) {
            plain.append(text, pos, m.start());
            String token = m.group();
            String body = token.replace(" ", "");
            if (!body.startsWith("⟦CS") && !body.startsWith("⟦/CS")) {
                plain.append(SENTINEL_BASE + slots.size());
                slots.add(token);
            }
            pos = m.end();
        }
        plain.append(text, pos, text.length());

        TranslationResult r;
        try {
            r = requestOnce(plain.toString(), targetLang);
        } catch (TranslationException e) {
            return new TranslationResult(text, null); // whole-line fallback, never half done
        }
        String translated = (r == null) ? null : r.translatedText();
        if (translated == null || translated.isBlank()) {
            return new TranslationResult(text, r == null ? null : r.detectedSourceLang());
        }
        // Every sentinel must survive EXACTLY once, or the slot mapping would lie.
        for (int i = 0; i < slots.size(); i++) {
            String sentinel = Integer.toString(SENTINEL_BASE + i);
            int first = translated.indexOf(sentinel);
            if (first < 0 || translated.indexOf(sentinel, first + sentinel.length()) >= 0) {
                return new TranslationResult(text, r.detectedSourceLang());
            }
        }
        String out = translated;
        for (int i = 0; i < slots.size(); i++) {
            out = out.replace(Integer.toString(SENTINEL_BASE + i), slots.get(i));
        }
        if (!preservesTokens(text, out)) { // belt: the MT/mask multiset must match
            return new TranslationResult(text, r.detectedSourceLang());
        }
        return new TranslationResult(out, r.detectedSourceLang());
    }

    /** Char budget per joined request, pre-URL-encoding (keeps the GET URL well under limits). */
    static final int MAX_CHARS_PER_REQUEST = 1600;

    /**
     * Batch translate by joining texts with newlines and splitting the result back
     * (the endpoint preserves line breaks). Large batches are chunked by character
     * budget rather than item count, so many short lines still share one request.
     * If a chunk's line count comes back misaligned (rare), it is halved and retried
     * — O(log n) extra requests around the offending line instead of one per item.
     */
    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        if (texts.isEmpty()) return List.of();
        List<TranslationResult> out = new ArrayList<>(texts.size());
        int start = 0;
        while (start < texts.size()) {
            int end = start + 1;
            int chars = texts.get(start).length();
            while (end < texts.size() && chars + 1 + texts.get(end).length() <= MAX_CHARS_PER_REQUEST) {
                chars += 1 + texts.get(end).length();
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
        // Token-carrying lines must go through the whole-line sentinel path (their ⟦…⟧
        // tokens are never allowed on the wire), so a chunk containing any is translated
        // line by line instead of as one joined request. The batch protocol for token-free
        // chunks is unchanged.
        for (String t : texts) {
            if (t != null && ANY_TOKEN.matcher(t).find()) {
                for (String each : texts) out.add(translate(each, targetLang));
                return;
            }
        }
        // Inner newlines would break the 1:1 line alignment — flatten them per item.
        List<String> lines = new ArrayList<>(texts.size());
        for (String t : texts) {
            lines.add(t.replace('\n', ' '));
        }
        TranslationResult combined = translate(String.join("\n", lines), targetLang);
        String translated = combined.translatedText();
        if (translated != null) {
            String[] parts = translated.split("\n", -1);
            if (parts.length == texts.size()) {
                for (int i = 0; i < parts.length; i++) {
                    String src = texts.get(i);
                    String part = preservesTokens(src, parts[i]) ? parts[i] : src;
                    out.add(new TranslationResult(part, combined.detectedSourceLang()));
                }
                return;
            }
        }
        // Misaligned: bisect to isolate the line the endpoint merged/split.
        int mid = texts.size() / 2;
        translateChunk(texts.subList(0, mid), targetLang, out);
        translateChunk(texts.subList(mid, texts.size()), targetLang, out);
    }

    /** True unless the source's CONTENT tokens (⟦MT#⟧ template slots, name masks) were lost
     *  or altered by the translation — a mismatch means slot restoration would lie, so the
     *  caller keeps the original line. ⟦CS#⟧ colour markers are EXEMPT: whole-line mode
     *  strips them deliberately (the glue re-applies colours). Order-independent multiset. */
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
            String t = m.group().replace(" ", "");
            if (t.startsWith("⟦CS") || t.startsWith("⟦/CS")) continue; // stripped by design
            out.add(t);
        }
        return out;
    }
}
