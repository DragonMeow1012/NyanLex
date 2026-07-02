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
 * <p>The HTTP layer is injected via {@link HttpTransport} so this class can be
 * unit-tested with an inline fake transport (no real network access).</p>
 */
public final class GoogleFreeTranslator implements Translator {

    static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";

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
        try {
            String body = transport.get(buildUrl(text, targetLang));
            return GoogleResponseParser.parse(body);
        } catch (IOException e) {
            throw new TranslationException("http error: " + e.getMessage(), e);
        }
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
                for (String part : parts) {
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
}
