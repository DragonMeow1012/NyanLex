package com.dragonmeow.nyanlex.forgelegacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Java-8 adapters for the official DeepL and Microsoft Translator APIs, used with the
 * player's own API key (sent only as a request header to the provider's own host).
 *
 * <p>Google deliberately stays in {@link LegacyTranslator}; this class cannot alter its
 * endpoint, parser, or pacing behaviour. Callers must validate the shared numeric
 * batch anchors before caching any value returned here.</p>
 */
final class LegacyMachineProvider {
    private static final int MAX_HTTP_RESPONSE_CHARS = 2_000_000;
    private static final String DEEPL_FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate";
    private static final String DEEPL_PRO_ENDPOINT = "https://api.deepl.com/v2/translate";
    private static final String MICROSOFT_ENDPOINT =
            "https://api.cognitive.microsofttranslator.com/translate";

    String translate(String provider, String text, String sourceLanguage, String targetLanguage,
                     LegacyConfig config) throws Exception {
        String selected = LegacyConfig.normalizeMachineProvider(provider);
        String key = "";
        try {
            if ("deepl_api".equals(selected)) {
                key = trim(config == null ? null : config.deeplApiKey);
                if (key.isEmpty()) throw new ProviderException("deepl_api: no API key set");
                return requestDeepL(text, sourceLanguage, targetLanguage, key);
            }
            if ("microsoft_api".equals(selected)) {
                key = trim(config == null ? null : config.microsoftApiKey);
                if (key.isEmpty()) throw new ProviderException("microsoft_api: no API key set");
                return requestMicrosoft(text, sourceLanguage, targetLanguage, key,
                        trim(config.microsoftApiRegion));
            }
        } catch (Exception failure) {
            throw redacted(failure, key);
        }
        throw new IllegalArgumentException("official API provider required");
    }

    private static Exception redacted(Exception failure, String key) {
        String message = failure.getMessage();
        if (key.isEmpty() || message == null || !message.contains(key)) return failure;
        return new ProviderException(message.replace(key, "***"));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** DeepL: free-plan keys end with ":fx" and use the api-free host; others use api. */
    static String deeplEndpoint(String key) {
        return key.endsWith(":fx") ? DEEPL_FREE_ENDPOINT : DEEPL_PRO_ENDPOINT;
    }

    private String requestDeepL(String text, String sourceLanguage, String targetLanguage,
                                String key) throws Exception {
        JsonObject payload = new JsonObject();
        JsonArray texts = new JsonArray();
        texts.add(new com.google.gson.JsonPrimitive(text));
        payload.add("text", texts);
        payload.addProperty("target_lang", mapDeepLTarget(targetLanguage));
        String source = normalizeTag(sourceLanguage);
        if (!isAuto(source)) payload.addProperty("source_lang", primary(source).toUpperCase(Locale.ROOT));
        payload.addProperty("split_sentences", "0");
        payload.addProperty("preserve_formatting", Boolean.TRUE);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "DeepL-Auth-Key " + key);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        JsonObject root = parseObject(post(deeplEndpoint(key), payload.toString(), headers));
        JsonArray translations = array(root, "translations");
        if (translations == null || translations.size() == 0
                || !translations.get(0).isJsonObject()) {
            throw new ProviderException("DeepL missing translations");
        }
        JsonObject first = translations.get(0).getAsJsonObject();
        if (!first.has("text") || first.get("text").isJsonNull()) {
            throw new ProviderException("DeepL missing text");
        }
        String translated = first.get("text").getAsString();
        if (translated.trim().isEmpty()) throw new ProviderException("DeepL empty translation");
        return translated;
    }

    private String requestMicrosoft(String text, String sourceLanguage, String targetLanguage,
                                    String key, String region) throws Exception {
        StringBuilder url = new StringBuilder(MICROSOFT_ENDPOINT)
                .append("?api-version=3.0&to=").append(enc(mapMicrosoft(targetLanguage)));
        String source = normalizeTag(sourceLanguage);
        if (!isAuto(source)) url.append("&from=").append(enc(mapMicrosoft(source)));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Ocp-Apim-Subscription-Key", key);
        if (!region.isEmpty()) headers.put("Ocp-Apim-Subscription-Region", region);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("Accept", "application/json");
        JsonArray payload = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("Text", text);
        payload.add(item);
        String body = post(url.toString(), payload.toString(), headers);
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(body);
        } catch (RuntimeException malformed) {
            throw new ProviderException("Microsoft malformed JSON", malformed);
        }
        if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() == 0
                || !parsed.getAsJsonArray().get(0).isJsonObject()) {
            throw new ProviderException("Microsoft unexpected response");
        }
        JsonObject first = parsed.getAsJsonArray().get(0).getAsJsonObject();
        JsonArray translations = array(first, "translations");
        if (translations == null || translations.size() == 0
                || !translations.get(0).isJsonObject()) {
            throw new ProviderException("Microsoft missing translations");
        }
        JsonObject translation = translations.get(0).getAsJsonObject();
        if (!translation.has("text") || translation.get("text").isJsonNull()) {
            throw new ProviderException("Microsoft missing text");
        }
        String translated = translation.get("text").getAsString();
        if (translated.trim().isEmpty()) throw new ProviderException("Microsoft empty translation");
        return translated;
    }

    private static String post(String endpoint, String body, Map<String, String> headers)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream output = connection.getOutputStream();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
            int code = connection.getResponseCode();
            boolean failed = code / 100 != 2;
            String response = read(connection, failed);
            if (failed) throw new HttpStatusException(code, response);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static JsonObject parseObject(String body) throws ProviderException {
        try {
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) throw new ProviderException("unexpected JSON root");
            return parsed.getAsJsonObject();
        } catch (ProviderException expected) {
            throw expected;
        } catch (RuntimeException malformed) {
            throw new ProviderException("malformed JSON", malformed);
        }
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement element = object == null ? null : object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    /** DeepL target codes: Chinese variants are ZH-HANT / ZH-HANS; EN and PT need a variant. */
    static String mapDeepLTarget(String language) {
        String tag = normalizeTag(language);
        if (isTraditionalChinese(tag)) return "ZH-HANT";
        if ("zh".equals(tag) || tag.startsWith("zh-")) return "ZH-HANS";
        if ("en".equals(tag)) return "EN-US";
        if ("pt".equals(tag)) return "PT-BR";
        return tag.toUpperCase(Locale.ROOT);
    }

    static String mapMicrosoft(String language) {
        String tag = normalizeTag(language);
        if (isTraditionalChinese(tag)) return "zh-Hant";
        if ("zh".equals(tag) || tag.startsWith("zh-cn") || tag.startsWith("zh-hans")
                || tag.startsWith("zh-sg")) return "zh-Hans";
        return tag;
    }

    private static String normalizeTag(String value) {
        return value == null || value.trim().isEmpty() ? "auto"
                : value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static boolean isTraditionalChinese(String tag) {
        return tag.startsWith("zh-tw") || tag.startsWith("zh-hk")
                || tag.startsWith("zh-mo") || tag.startsWith("zh-hant");
    }

    private static boolean isAuto(String value) {
        return value == null || "auto".equalsIgnoreCase(value);
    }

    private static String primary(String value) {
        int dash = value.indexOf('-');
        return dash < 0 ? value : value.substring(0, dash);
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String read(HttpURLConnection connection, boolean error) throws Exception {
        InputStream stream = error ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (body.length() + count > MAX_HTTP_RESPONSE_CHARS) {
                    throw new ProviderException("HTTP response too large");
                }
                body.append(buffer, 0, count);
            }
            return body.toString();
        } finally {
            reader.close();
        }
    }

    private static String compact(String value) {
        String flat = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "...";
    }

    private static class ProviderException extends Exception {
        ProviderException(String message) { super(message); }
        ProviderException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code, String body) {
            super("HTTP " + code + ": " + compact(body));
            this.code = code;
        }
    }
}
