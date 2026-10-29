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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Read-only model discovery shared by all loaders, including Java 8 clients. */
public final class ApiModelCatalog {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private ApiModelCatalog() { }

    interface Fetcher {
        String get(String url, Map<String, String> headers) throws IOException;
    }

    /**
     * One catalog entry. {@code effortLevels} is null when the endpoint does not say, and an
     * empty list when it says effort is not supported. {@code thinkingTypes} (Anthropic only) lists
     * the {@code thinking.type} values the model accepts; null when the endpoint does not say.
     * {@code maxTokens} is the model's output limit, 0 when the endpoint does not say.
     */
    public static final class Model {
        public final String id;
        public final List<String> effortLevels;
        public final List<String> thinkingTypes;
        public final int maxTokens;

        Model(String id, List<String> effortLevels) {
            this(id, effortLevels, null, 0);
        }

        Model(String id, List<String> effortLevels, int maxTokens) {
            this(id, effortLevels, null, maxTokens);
        }

        Model(String id, List<String> effortLevels, List<String> thinkingTypes, int maxTokens) {
            this.id = id;
            this.effortLevels = effortLevels == null ? null
                    : Collections.unmodifiableList(new ArrayList<String>(effortLevels));
            this.thinkingTypes = thinkingTypes == null ? null
                    : Collections.unmodifiableList(new ArrayList<String>(thinkingTypes));
            this.maxTokens = maxTokens;
        }
    }

    public static List<String> load(String baseUrl, List<String> keys) throws IOException {
        try { return load(baseUrl, keys, ApiModelCatalog::get); }
        catch (RuntimeException invalid) {
            // HTTP header validation can quote its input; never surface a key in an error.
            throw new IOException("Invalid model catalog configuration or response");
        }
    }

    /** Like {@link #load(String, List)} but keeps each model's advertised effort levels. */
    public static List<Model> loadModels(String baseUrl, List<String> keys) throws IOException {
        try { return loadModels(baseUrl, keys, ApiModelCatalog::get); }
        catch (RuntimeException invalid) {
            throw new IOException("Invalid model catalog configuration or response");
        }
    }

    static List<String> load(String baseUrl, List<String> keys, Fetcher fetcher) throws IOException {
        return load(baseUrl, keys, fetcher, AiFeatureMemory.shared());
    }

    /**
     * Model ids; the effort levels, thinking types and output limits the catalog reports are
     * remembered in {@code memory}.
     */
    static List<String> load(String baseUrl, List<String> keys, Fetcher fetcher, AiFeatureMemory memory)
            throws IOException {
        List<Model> models = loadModels(baseUrl, keys, fetcher);
        List<String> ids = new ArrayList<String>(models.size());
        for (Model model : models) {
            ids.add(model.id);
            if (model.effortLevels != null) memory.recordEffortLevels(model.id, model.effortLevels);
            if (model.thinkingTypes != null) memory.recordThinkingTypes(model.id, model.thinkingTypes);
            if (model.maxTokens > 0) memory.recordMaxOutputTokens(model.id, model.maxTokens);
        }
        return Collections.unmodifiableList(ids);
    }

    static List<Model> loadModels(String baseUrl, List<String> keys, Fetcher fetcher) throws IOException {
        final URI base;
        try { base = HttpEndpointPolicy.validate(baseUrl == null ? "" : baseUrl.trim()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid model catalog endpoint"); }
        if (base.getRawQuery() != null) throw new IOException("Model catalog base URL must not contain a query");
        String key = "";
        if (keys != null) for (String value : keys) {
            if (value != null && !value.trim().isEmpty()) { key = value.trim(); break; }
        }
        boolean gemini = "generativelanguage.googleapis.com".equalsIgnoreCase(base.getHost());
        boolean anthropic = HttpEndpointPolicy.isAnthropic(base.toString());
        boolean deepseek = HttpEndpointPolicy.isDeepSeek(base.toString());
        String endpoint = base.toString().replaceAll("/+$", "");
        if (endpoint.endsWith("/chat/completions")) endpoint = endpoint.substring(0, endpoint.length() - 17);
        if (gemini && endpoint.endsWith("/openai")) endpoint = endpoint.substring(0, endpoint.length() - 7);
        if (anthropic) endpoint = AnthropicWire.modelsUrl(endpoint);
        else if (!endpoint.endsWith("/models")) endpoint += "/models";
        Map<String, String> headers = Collections.<String, String>emptyMap();
        if (!key.isEmpty()) {
            if (anthropic) {
                // Anthropic's own key header; the key is never sent as an Authorization bearer.
                headers = new LinkedHashMap<String, String>();
                headers.put("x-api-key", key);
                headers.put("anthropic-version", AnthropicWire.VERSION);
            } else {
                headers = Collections.singletonMap(gemini ? "x-goog-api-key" : "Authorization", gemini ? key : "Bearer " + key);
            }
        }
        TreeMap<String, Model> models = new TreeMap<String, Model>();
        String cursor = "";
        long deadline = System.nanoTime() + 30_000_000_000L;
        for (int page = 0; page < 20; page++) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new IOException("Model catalog request timed out");
            String url = endpoint;
            if (anthropic) {
                url += "?limit=1000" + (cursor.isEmpty() ? "" : "&after_id=" + URLEncoder.encode(cursor, "UTF-8"));
            } else if (!cursor.isEmpty()) {
                url += "?" + (gemini ? "pageToken=" : "after=") + URLEncoder.encode(cursor, "UTF-8");
            }
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
                if (!id.isEmpty() && id.length() <= 128 && isTextCandidate(id) && !models.containsKey(id)) {
                    if (anthropic) models.put(id, new Model(id, effortLevels(model), thinkingTypes(model), maxTokens(model)));
                    else if (deepseek) models.put(id, new Model(id, supportedLevels(model)));
                    else models.put(id, new Model(id, null));
                }
                if (models.size() > 10000) throw new IOException("Model catalog is too large");
            }
            String next = gemini ? string(root, "nextPageToken") : "";
            if (!gemini && root.has("has_more") && root.get("has_more").isJsonPrimitive()
                    && root.get("has_more").getAsBoolean()) {
                next = string(root, "last_id");
                if (next.isEmpty()) throw new IOException("Model catalog pagination cursor is missing");
            }
            if (next.isEmpty()) return Collections.unmodifiableList(new ArrayList<Model>(models.values()));
            if (next.equals(cursor)) throw new IOException("Model catalog pagination did not advance");
            cursor = next;
        }
        throw new IOException("Model catalog has too many pages");
    }

    /**
     * The levels {@code capabilities.effort} marks as supported; an empty list when the model
     * says effort is unsupported; null when the entry does not say (or itemizes no level).
     */
    private static List<String> effortLevels(JsonObject model) {
        JsonObject effort = object(object(model, "capabilities"), "effort");
        if (effort == null) return null;
        JsonElement supported = effort.get("supported");
        if (!isBoolean(supported)) return null;
        List<String> levels = new ArrayList<String>();
        if (!supported.getAsBoolean()) return levels;
        for (String level : AnthropicWire.EFFORT_LEVELS) {
            JsonObject detail = object(effort, level);
            JsonElement flag = detail == null ? null : detail.get("supported");
            if (isBoolean(flag) && flag.getAsBoolean()) levels.add(level);
        }
        return levels.isEmpty() ? null : levels;
    }

    /**
     * The {@code thinking.type} values {@code capabilities.thinking.types} marks as supported
     * (for example {@code disabled}, {@code between_tools}, {@code adaptive}); an empty list when
     * the model says it has no thinking; null when the entry does not say.
     */
    private static List<String> thinkingTypes(JsonObject model) {
        JsonObject thinking = object(object(model, "capabilities"), "thinking");
        if (thinking == null) return null;
        JsonObject types = object(thinking, "types");
        List<String> supported = new ArrayList<String>();
        if (types == null) {
            JsonElement flag = thinking.get("supported");
            return isBoolean(flag) && !flag.getAsBoolean() ? supported : null;
        }
        boolean itemized = false;
        for (Map.Entry<String, JsonElement> entry : types.entrySet()) {
            JsonElement detail = entry.getValue();
            if (!detail.isJsonObject()) continue;
            JsonElement flag = detail.getAsJsonObject().get("supported");
            if (!isBoolean(flag)) continue;
            itemized = true;
            if (flag.getAsBoolean()) supported.add(entry.getKey());
        }
        return itemized ? supported : null;
    }

    /**
     * DeepSeek's {@code effort.supported_levels}: the values {@code reasoning_effort} accepts
     * with thinking on (the off switch is not among them). Null when the entry does not list
     * any; an empty array is kept as an empty list.
     */
    private static List<String> supportedLevels(JsonObject model) {
        JsonObject effort = object(model, "effort");
        JsonElement levels = effort == null ? null : effort.get("supported_levels");
        if (levels == null || !levels.isJsonArray()) return null;
        List<String> out = new ArrayList<String>();
        for (JsonElement level : levels.getAsJsonArray()) {
            if (!level.isJsonPrimitive() || !level.getAsJsonPrimitive().isString()) continue;
            String value = level.getAsString().trim().toLowerCase(Locale.ROOT);
            if (value.matches("[a-z]{1,16}") && !out.contains(value)) out.add(value);
        }
        return out;
    }

    /** The model's {@code max_tokens} output limit; 0 when absent or not a sane whole number. */
    private static int maxTokens(JsonObject model) {
        JsonElement value = model.get("max_tokens");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return 0;
        try {
            long limit = value.getAsLong();
            return limit > 0 && limit <= Integer.MAX_VALUE ? (int) limit : 0;
        } catch (RuntimeException unusable) {
            return 0;
        }
    }

    private static boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
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
