package com.dragonmeow.mctranslator.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Parses the response of the free Google endpoint
 * {@code translate.googleapis.com/translate_a/single}.
 *
 * <p>The response is a JSON array shaped like:</p>
 * <pre>
 * [
 *   [ ["你好","Hello",null,null,10], ["世界","World",...] ],   // [0] = translated sentence chunks
 *   null,
 *   "en",                                                       // [2] = detected source language
 *   ...
 * ]
 * </pre>
 * The translated text is the concatenation of every {@code chunk[0]}.
 */
public final class GoogleResponseParser {

    private GoogleResponseParser() {
    }

    public static TranslationResult parse(String body) throws TranslationException {
        if (body == null || body.isBlank()) {
            throw new TranslationException("empty response body");
        }
        try {
            JsonElement root = new JsonParser().parse(body);
            if (!root.isJsonArray()) {
                throw new TranslationException("unexpected response: root is not a JSON array");
            }
            JsonArray arr = root.getAsJsonArray();
            if (arr.size() == 0 || arr.get(0).isJsonNull() || !arr.get(0).isJsonArray()) {
                throw new TranslationException("unexpected response: missing sentence array");
            }

            JsonArray sentences = arr.get(0).getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : sentences) {
                if (!el.isJsonArray()) continue;
                JsonArray chunk = el.getAsJsonArray();
                if (chunk.size() == 0 || chunk.get(0).isJsonNull()) continue;
                sb.append(chunk.get(0).getAsString());
            }

            String detected = null;
            if (arr.size() > 2 && arr.get(2).isJsonPrimitive()) {
                detected = arr.get(2).getAsString();
            }

            String translated = sb.toString();
            return translated.isBlank()
                    ? new TranslationResult("", detected, false, "empty response")
                    : new TranslationResult(translated, detected);
        } catch (TranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TranslationException("failed to parse response: " + e.getMessage(), e);
        }
    }
}
