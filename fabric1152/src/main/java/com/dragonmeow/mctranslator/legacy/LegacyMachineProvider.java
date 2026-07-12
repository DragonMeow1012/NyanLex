package com.dragonmeow.mctranslator.legacy;

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
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java-8 adapters for experimental key-free website providers.
 *
 * <p>Google deliberately stays in {@link LegacyTranslator}; this class cannot alter its
 * endpoint, parser, or pacing behaviour. Callers must validate the shared numeric
 * batch anchors before caching any value returned here.</p>
 */
final class LegacyMachineProvider {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0";
    private static final String YOUDAO_CLIENT_SECRET = "cybibtzhdwayqjmrncst";
    private static final Pattern BING_PARAMS = Pattern.compile(
            "params_AbusePreventionHelper\\s?=\\s?(\\[[^\\]]+\\])");
    private static final Pattern BING_IG = Pattern.compile("IG:\\\"([^\\\"]+)\\\"");
    private static final Pattern BING_IID = Pattern.compile("data-iid=\\\"([^\\\"]+)\\\"");

    private final Object bingLock = new Object();
    private BingSession bing;

    String translate(String provider, String text, String sourceLanguage, String targetLanguage)
            throws Exception {
        String selected = LegacyConfig.normalizeMachineProvider(provider);
        if ("youdao".equals(selected)) return requestYoudao(text, sourceLanguage, targetLanguage);
        if ("deepl".equals(selected)) return requestDeepL(text, sourceLanguage, targetLanguage);
        if ("microsoft".equals(selected)) {
            return requestMicrosoft(text, sourceLanguage, targetLanguage, true);
        }
        throw new IllegalArgumentException("experimental provider required");
    }

    private String requestYoudao(String text, String sourceLanguage, String targetLanguage)
            throws Exception {
        String now = Long.toString(System.currentTimeMillis());
        String client = "deskdict";
        String signature = md5("client=" + client + "&mysticTime=" + now
                + "&product=deskdict&key=" + YOUDAO_CLIENT_SECRET);
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("keyfrom", "deskdict.main");
        query.put("client", client);
        query.put("from", mapYoudao(sourceLanguage));
        query.put("to", mapYoudao(targetLanguage));
        query.put("keyid", "deskdict");
        query.put("mysticTime", now);
        query.put("pointParam", "client,product,mysticTime");
        query.put("sign", signature);
        query.put("domain", "0");
        query.put("useTerm", "false");
        query.put("noCheckPrivate", "false");
        query.put("recTerms", "[]");
        query.put("id", "0a464aedddbc6e4b9");
        query.put("vendor", "fanyiweb_navigation");
        query.put("in", "YoudaoDict_fanyiweb_navigation");
        query.put("appVer", "11.2.0.0");
        query.put("appZengqiang", "0");
        query.put("abTest", "0");
        query.put("model", "Windows");
        query.put("screen", "1920*1080");
        query.put("OsVersion", "10.0");
        query.put("network", "none");
        query.put("mid", "windows10");
        query.put("appVersion", "11.2.0.0");
        query.put("product", "deskdict");
        query.put("source", "mine_transtab_realtime");

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Cookie", "DESKDICT_VENDOR=unknown");
        headers.put("Accept", "*/*");
        headers.put("User-Agent", "Youdao Desktop Dict (Windows NT 10.0)");
        String body = post("https://dict.youdao.com/dicttranslate?" + form(query),
                "i=" + enc(text), headers);
        JsonObject root = parseObject(body);
        if (root.has("code") && root.get("code").getAsInt() != 0) {
            throw new ProviderException("Youdao code " + root.get("code").getAsInt());
        }
        JsonArray rows = array(root, "translateResult");
        if (rows == null) throw new ProviderException("Youdao missing translateResult");
        StringBuilder translated = new StringBuilder();
        for (JsonElement row : rows) {
            if (row == null || !row.isJsonArray()) continue;
            for (JsonElement cell : row.getAsJsonArray()) {
                if (cell != null && cell.isJsonObject()) {
                    JsonObject object = cell.getAsJsonObject();
                    if (object.has("tgt") && !object.get("tgt").isJsonNull()) {
                        translated.append(object.get("tgt").getAsString());
                    }
                }
            }
        }
        if (translated.length() == 0) throw new ProviderException("Youdao empty translation");
        return translated.toString();
    }

    private String requestDeepL(String text, String sourceLanguage, String targetLanguage)
            throws Exception {
        JsonObject payload = new JsonObject();
        JsonArray texts = new JsonArray();
        texts.add(text);
        payload.add("text", texts);
        payload.addProperty("target_lang", mapDeepL(targetLanguage));
        String source = normalizeTag(sourceLanguage);
        if (!isAuto(source)) payload.addProperty("source_lang", mapDeepL(source));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "None");
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        JsonObject root = parseObject(post(
                "https://oneshot-free.www.deepl.com/v1/translate",
                payload.toString(), headers));
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
                                    boolean retry) throws Exception {
        try {
            BingRequest request = nextBingRequest();
            Map<String, String> fields = new LinkedHashMap<String, String>();
            fields.put("text", text);
            fields.put("fromLang", mapMicrosoft(sourceLanguage));
            fields.put("to", mapMicrosoft(targetLanguage));
            fields.put("tryFetchingGenderDebiasedTranslations", "true");
            fields.put("key", Long.toString(request.key));
            fields.put("token", request.token);

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            headers.put("Accept", "*/*");
            headers.put("Referer", "https://www.bing.com/translator");
            headers.put("User-Agent", USER_AGENT);
            if (!request.cookie.isEmpty()) headers.put("Cookie", request.cookie);
            String body = post(request.url, form(fields), headers);
            JsonElement parsed;
            try {
                parsed = new JsonParser().parse(body);
            } catch (RuntimeException malformed) {
                throw new SessionException("Microsoft malformed JSON", malformed);
            }
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() == 0
                    || !parsed.getAsJsonArray().get(0).isJsonObject()) {
                throw new SessionException("Microsoft unexpected response");
            }
            JsonObject first = parsed.getAsJsonArray().get(0).getAsJsonObject();
            JsonArray translations = array(first, "translations");
            if (translations == null || translations.size() == 0
                    || !translations.get(0).isJsonObject()) {
                throw new SessionException("Microsoft missing translations");
            }
            JsonObject translation = translations.get(0).getAsJsonObject();
            if (!translation.has("text") || translation.get("text").isJsonNull()) {
                throw new SessionException("Microsoft missing text");
            }
            String translated = translation.get("text").getAsString();
            if (translated.trim().isEmpty()) {
                throw new SessionException("Microsoft empty translation");
            }
            return translated;
        } catch (Exception failure) {
            if (retry && shouldRefreshMicrosoft(failure)) {
                synchronized (bingLock) { bing = null; }
                return requestMicrosoft(text, sourceLanguage, targetLanguage, false);
            }
            throw failure;
        }
    }

    private BingRequest nextBingRequest() throws Exception {
        synchronized (bingLock) {
            long now = System.currentTimeMillis();
            if (bing == null || now - bing.issuedAt
                    >= Math.max(1000L, bing.expiryMs - 30000L)) {
                PageResponse page = get("https://www.bing.com/translator");
                Matcher params = BING_PARAMS.matcher(page.body);
                Matcher ig = BING_IG.matcher(page.body);
                Matcher iid = BING_IID.matcher(page.body);
                if (!params.find() || !ig.find() || !iid.find()) {
                    throw new ProviderException("Microsoft session fields missing");
                }
                JsonArray values;
                try {
                    values = new JsonParser().parse(params.group(1)).getAsJsonArray();
                } catch (RuntimeException malformed) {
                    throw new ProviderException("Microsoft malformed session", malformed);
                }
                if (values.size() < 3) {
                    throw new ProviderException("Microsoft incomplete session");
                }
                bing = new BingSession(values.get(0).getAsLong(),
                        values.get(1).getAsString(), values.get(2).getAsLong(),
                        now, ig.group(1), iid.group(1), page.cookie, 0);
            }
            bing.counter++;
            String url = "https://www.bing.com/ttranslatev3?isVertical=1&&IG=" + enc(bing.ig)
                    + "&IID=" + enc(bing.iid) + "&SFX=" + bing.counter
                    + "&ref=TThis&edgepdftranslator=1";
            return new BingRequest(url, bing.key, bing.token, bing.cookie);
        }
    }

    private static boolean shouldRefreshMicrosoft(Exception failure) {
        if (failure instanceof SessionException) return true;
        if (failure instanceof HttpStatusException) {
            int code = ((HttpStatusException) failure).code;
            return code == 205 || code == 401;
        }
        return false;
    }

    private static PageResponse get(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            boolean failed = code == 205 || code / 100 != 2;
            String body = read(connection, failed);
            if (failed) throw new HttpStatusException(code, body);
            return new PageResponse(body, responseCookies(connection));
        } finally {
            connection.disconnect();
        }
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
            boolean failed = code == 205 || code / 100 != 2;
            String response = read(connection, failed);
            if (failed) throw new HttpStatusException(code, response);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String responseCookies(HttpURLConnection connection) {
        StringBuilder cookies = new StringBuilder();
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (headers == null) return "";
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())
                    || header.getValue() == null) continue;
            for (String value : header.getValue()) {
                if (value == null) continue;
                int end = value.indexOf(';');
                String pair = (end < 0 ? value : value.substring(0, end)).trim();
                if (pair.isEmpty()) continue;
                if (cookies.length() > 0) cookies.append("; ");
                cookies.append(pair);
            }
        }
        return cookies.toString();
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

    private static String mapYoudao(String language) {
        String tag = normalizeTag(language);
        if (isAuto(tag)) return "auto";
        if (isTraditionalChinese(tag)) return "zh-CHT";
        if ("zh".equals(tag) || tag.startsWith("zh-cn") || tag.startsWith("zh-hans")
                || tag.startsWith("zh-sg")) return "zh-CHS";
        return primary(tag);
    }

    private static String mapDeepL(String language) {
        String tag = normalizeTag(language);
        if (isTraditionalChinese(tag)) return "zh-Hant";
        if ("zh".equals(tag) || tag.startsWith("zh-cn") || tag.startsWith("zh-hans")
                || tag.startsWith("zh-sg")) return "zh-Hans";
        return tag;
    }

    private static String mapMicrosoft(String language) {
        String tag = normalizeTag(language);
        if (isAuto(tag)) return "auto-detect";
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

    private static String form(Map<String, String> values) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return out.toString();
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte item : digest) out.append(String.format("%02x", item & 0xFF));
        return out.toString();
    }

    private static String read(HttpURLConnection connection, boolean error) throws Exception {
        InputStream stream = error ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        } finally {
            reader.close();
        }
    }

    private static String compact(String value) {
        String flat = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "...";
    }

    private static final class PageResponse {
        final String body;
        final String cookie;
        PageResponse(String body, String cookie) {
            this.body = body;
            this.cookie = cookie == null ? "" : cookie;
        }
    }

    private static final class BingRequest {
        final String url;
        final long key;
        final String token;
        final String cookie;
        BingRequest(String url, long key, String token, String cookie) {
            this.url = url;
            this.key = key;
            this.token = token;
            this.cookie = cookie == null ? "" : cookie;
        }
    }

    private static final class BingSession {
        final long key;
        final String token;
        final long expiryMs;
        final long issuedAt;
        final String ig;
        final String iid;
        final String cookie;
        int counter;

        BingSession(long key, String token, long expiryMs, long issuedAt,
                    String ig, String iid, String cookie, int counter) {
            this.key = key;
            this.token = token;
            this.expiryMs = expiryMs;
            this.issuedAt = issuedAt;
            this.ig = ig;
            this.iid = iid;
            this.cookie = cookie == null ? "" : cookie;
            this.counter = counter;
        }
    }

    private static class ProviderException extends Exception {
        ProviderException(String message) { super(message); }
        ProviderException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class SessionException extends ProviderException {
        SessionException(String message) { super(message); }
        SessionException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code, String body) {
            super("HTTP " + code + ": " + compact(body));
            this.code = code;
        }
    }
}
