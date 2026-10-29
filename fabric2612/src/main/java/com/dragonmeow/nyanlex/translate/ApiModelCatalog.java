package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Read-only model discovery shared by all loaders, including Java 8 clients. */
public final class ApiModelCatalog {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private ApiModelCatalog() { }

    interface Fetcher {
        String get(String url, Map<String, String> headers) throws IOException;
    }

    public static List<String> load(String baseUrl, List<String> keys) throws IOException {
        try { return load(baseUrl, keys, ApiModelCatalog::get); }
        catch (RuntimeException invalid) {
            // HTTP header validation can quote its input; never surface a key in an error.
            throw new IOException("Invalid model catalog configuration or response");
        }
    }

    static List<String> load(String baseUrl, List<String> keys, Fetcher fetcher) throws IOException {
        final URI base;
        try { base = HttpEndpointPolicy.validate(baseUrl == null ? "" : baseUrl.trim()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid model catalog endpoint"); }
        if (base.getRawQuery() != null) throw new IOException("Model catalog base URL must not contain a query");
        String key = "";
        if (keys != null) for (String value : keys) {
            if (value != null && !value.trim().isEmpty()) { key = value.trim(); break; }
        }
        boolean gemini = "generativelanguage.googleapis.com".equalsIgnoreCase(base.getHost());
        String endpoint = base.toString().replaceAll("/+$", "");
        if (endpoint.endsWith("/chat/completions")) endpoint = endpoint.substring(0, endpoint.length() - 17);
        if (gemini && endpoint.endsWith("/openai")) endpoint = endpoint.substring(0, endpoint.length() - 7);
        if (!endpoint.endsWith("/models")) endpoint += "/models";
        Map<String, String> headers = key.isEmpty() ? Collections.<String, String>emptyMap()
                : Collections.singletonMap(gemini ? "x-goog-api-key" : "Authorization", gemini ? key : "Bearer " + key);
        TreeSet<String> models = new TreeSet<String>();
        String cursor = "";
        long deadline = System.nanoTime() + 30_000_000_000L;
        for (int page = 0; page < 20; page++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new IOException("Model catalog request timed out");
            String url = endpoint + (cursor.isEmpty() ? "" : "?" + (gemini ? "pageToken=" : "after=")
                    + URLEncoder.encode(cursor, "UTF-8"));
            JsonObject root;
            try { root = new JsonParser().parse(fetcher.get(url, headers)).getAsJsonObject(); }
            catch (RuntimeException malformed) { throw new IOException("Invalid model catalog response"); }
            JsonElement data = root.get(gemini ? "models" : "data");
            if (data == null || !data.isJsonArray()) throw new IOException("This endpoint did not return a model list");
            for (JsonElement item : data.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject model = item.getAsJsonObject();
                String id = string(model, gemini ? "name" : "id");
                if (gemini) {
                    if (!supportsGeneration(model.get("supportedGenerationMethods"))) continue;
                    if (id.startsWith("models/")) id = id.substring(7);
                }
                if (!id.isEmpty() && id.length() <= 128 && isTextCandidate(id)) models.add(id);
                if (models.size() > 10000) throw new IOException("Model catalog is too large");
            }
            String next = gemini ? string(root, "nextPageToken") : "";
            if (!gemini && root.has("has_more") && root.get("has_more").isJsonPrimitive()
                    && root.get("has_more").getAsBoolean()) {
                next = string(root, "last_id");
                if (next.isEmpty()) throw new IOException("Model catalog pagination cursor is missing");
            }
            if (next.isEmpty()) return Collections.unmodifiableList(new ArrayList<String>(models));
            if (next.equals(cursor)) throw new IOException("Model catalog pagination did not advance");
            cursor = next;
        }
        throw new IOException("Model catalog has too many pages");
    }

    private static boolean supportsGeneration(JsonElement methods) {
        if (methods == null || !methods.isJsonArray()) return false;
        for (JsonElement method : methods.getAsJsonArray()) {
            if (method.isJsonPrimitive() && "generateContent".equals(method.getAsString())) return true;
        }
        return false;
    }

    private static boolean isTextCandidate(String id) {
        String name = id.toLowerCase(Locale.ROOT);
        return !name.contains("embedding") && !name.contains("moderation") && !name.contains("realtime")
                && !name.contains("audio") && !name.contains("transcribe") && !name.contains("whisper") && !name.contains("tts")
                && !name.contains("image") && !name.contains("imagen") && !name.startsWith("dall-e")
                && !name.startsWith("veo-") && !name.startsWith("lyria-") && !name.startsWith("sora-");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : "";
    }

    private static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) HttpEndpointPolicy.validate(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
        try {
            int code = connection.getResponseCode();
            if (code / 100 != 2) throw new IOException("Model catalog: HTTP " + code);
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Model catalog is too large");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Model catalog request cancelled");
                    if (output.size() + count > MAX_BYTES) throw new IOException("Model catalog is too large");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
