package com.dragonmeow.nyanslate.translate;

import com.dragonmeow.nyanslate.config.MachineTranslationProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort adapters for key-free website endpoints. These providers are explicitly
 * experimental; the strict numeric protocol below is not. Every cache item is isolated,
 * rich markers/newlines never reach the endpoint, and a damaged response is rejected or
 * bisected before anything can be written to a neighbouring key.
 */
final class ExperimentalWebTranslator implements Translator {
    private static final Pattern ANY_TOKEN = Pattern.compile("\\u27E6[^\\u27E6\\u27E7]*\\u27E7");
    private static final int SENTINEL_BASE = 76001;
    private static final int NORMAL_MAX_WIRE_CHARS = 1400;
    private static final int MICROSOFT_MAX_WIRE_CHARS = 900;
    private static final int ITEM_OVERHEAD = 24;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0";
    private static final String YOUDAO_CLIENT_SECRET = "cybibtzhdwayqjmrncst";

    private final HttpTransport transport;
    private final Supplier<String> sourceLanguage;
    private final MachineTranslationProvider provider;
    private final RequestPacer pacer;
    private final Object bingLock = new Object();
    private BingSession bing;

    ExperimentalWebTranslator(HttpTransport transport, Supplier<String> sourceLanguage,
                              MachineTranslationProvider provider, RequestPacer pacer) {
        if (provider == null || provider == MachineTranslationProvider.GOOGLE) {
            throw new IllegalArgumentException("experimental provider required");
        }
        this.transport = transport;
        this.sourceLanguage = sourceLanguage;
        this.provider = provider;
        this.pacer = pacer == null ? RequestPacer.disabled() : pacer;
    }

    @Override public TranslationResult translate(String text, String targetLang)
            throws TranslationException {
        List<TranslationResult> results = translateBatch(List.of(text), targetLang);
        return results.isEmpty() ? new TranslationResult("", null) : results.get(0);
    }

    @Override public List<TranslationResult> translateBatch(List<String> texts, String targetLang)
            throws TranslationException {
        if (texts == null || texts.isEmpty()) return List.of();
        List<TranslationResult> out = new ArrayList<>(texts.size());
        int limit = provider == MachineTranslationProvider.MICROSOFT
                ? MICROSOFT_MAX_WIRE_CHARS : NORMAL_MAX_WIRE_CHARS;
        int start = 0;
        while (start < texts.size()) {
            int end = start + 1;
            int chars = safe(texts.get(start)).length() + ITEM_OVERHEAD;
            while (end < texts.size()) {
                int next = safe(texts.get(end)).length() + ITEM_OVERHEAD;
                if (chars + next > limit) break;
                chars += next;
                end++;
            }
            translateChunk(texts.subList(start, end), targetLang, out);
            start = end;
        }
        return out;
    }

    private void translateChunk(List<String> texts, String targetLang, List<TranslationResult> out)
            throws TranslationException {
        WireBatch wire = buildWire(texts);
        TranslationResult combined = request(wire.text(), targetLang);
        List<String> parts = extractAnchoredBatch(
                combined == null ? null : combined.translatedText(), texts.size(),
                wire.base(), wire.markerCount());
        if (parts == null) {
            if (texts.size() == 1) {
                out.add(new TranslationResult("", combined == null ? null : combined.detectedSourceLang()));
                return;
            }
            int mid = texts.size() / 2;
            translateChunk(texts.subList(0, mid), targetLang, out);
            translateChunk(texts.subList(mid, texts.size()), targetLang, out);
            return;
        }
        for (int i = 0; i < parts.size(); i++) {
            String restored = restorePart(parts.get(i), wire.items().get(i));
            if (restored == null || !GoogleFreeTranslator.preservesTokens(texts.get(i), restored)) {
                restored = "";
            }
            out.add(new TranslationResult(restored,
                    combined == null ? null : combined.detectedSourceLang()));
        }
    }

    private TranslationResult request(String text, String targetLang) throws TranslationException {
        try {
            pacer.acquire();
            return switch (provider) {
                case YOUDAO -> requestYoudao(text, targetLang);
                case DEEPL -> requestDeepL(text, targetLang);
                case MICROSOFT -> requestMicrosoft(text, targetLang, true);
                default -> throw new IOException("unsupported provider " + provider.id());
            };
        } catch (IOException e) {
            throw new TranslationException(provider.id() + " http error: " + e.getMessage(), e);
        }
    }

    private TranslationResult requestYoudao(String text, String targetLang) throws IOException {
        String now = Long.toString(System.currentTimeMillis());
        String client = "deskdict";
        String signature = md5("client=" + client + "&mysticTime=" + now
                + "&product=deskdict&key=" + YOUDAO_CLIENT_SECRET);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("keyfrom", "deskdict.main");
        query.put("client", client);
        query.put("from", mapYoudao(source()));
        query.put("to", mapYoudao(targetLang));
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
        String body = transport.post("https://dict.youdao.com/dicttranslate?" + form(query),
                "i=" + enc(text), Map.of(
                        "Content-Type", "application/x-www-form-urlencoded",
                        "Cookie", "DESKDICT_VENDOR=unknown",
                        "Accept", "*/*",
                        "User-Agent", "Youdao Desktop Dict (Windows NT 10.0)"));
        JsonObject root = parseObject(body);
        if (root.has("code") && root.get("code").getAsInt() != 0) {
            throw new IOException("Youdao code " + root.get("code").getAsInt());
        }
        JsonArray rows = root.getAsJsonArray("translateResult");
        if (rows == null) throw new IOException("Youdao missing translateResult");
        StringBuilder translated = new StringBuilder();
        for (JsonElement row : rows) {
            if (!row.isJsonArray()) continue;
            for (JsonElement cell : row.getAsJsonArray()) {
                if (cell.isJsonObject() && cell.getAsJsonObject().has("tgt")) {
                    translated.append(cell.getAsJsonObject().get("tgt").getAsString());
                }
            }
        }
        String detected = root.has("guessLanguage") ? root.get("guessLanguage").getAsString() : null;
        return new TranslationResult(translated.toString(), detected);
    }

    private TranslationResult requestDeepL(String text, String targetLang) throws IOException {
        JsonObject payload = new JsonObject();
        JsonArray texts = new JsonArray();
        texts.add(text);
        payload.add("text", texts);
        payload.addProperty("target_lang", mapDeepL(targetLang));
        String source = source();
        if (!isAuto(source)) payload.addProperty("source_lang", mapDeepL(source));
        String body = transport.post("https://oneshot-free.www.deepl.com/v1/translate",
                payload.toString(), Map.of(
                        "Authorization", "None",
                        "Content-Type", "application/json",
                        "User-Agent", USER_AGENT));
        JsonObject root = parseObject(body);
        JsonArray translations = root.getAsJsonArray("translations");
        if (translations == null || translations.size() == 0) {
            throw new IOException("DeepL missing translations");
        }
        JsonObject first = translations.get(0).getAsJsonObject();
        String detected = first.has("detected_source_language")
                ? first.get("detected_source_language").getAsString() : null;
        return new TranslationResult(first.get("text").getAsString(), detected);
    }

    private TranslationResult requestMicrosoft(String text, String targetLang, boolean retry)
            throws IOException {
        BingRequest request = nextBingRequest();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", text);
        fields.put("fromLang", mapMicrosoft(source()));
        fields.put("to", mapMicrosoft(targetLang));
        fields.put("tryFetchingGenderDebiasedTranslations", "true");
        fields.put("key", Long.toString(request.key()));
        fields.put("token", request.token());
        try {
            String body = transport.post(request.url(), form(fields), Map.of(
                    "Content-Type", "application/x-www-form-urlencoded",
                    "Accept", "*/*",
                    "Referer", "https://www.bing.com/translator",
                    "User-Agent", USER_AGENT));
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().size() == 0) {
                throw new IOException("Microsoft unexpected response");
            }
            JsonObject first = parsed.getAsJsonArray().get(0).getAsJsonObject();
            JsonArray translations = first.getAsJsonArray("translations");
            if (translations == null || translations.size() == 0) {
                throw new IOException("Microsoft missing translations");
            }
            String detected = null;
            if (first.has("detectedLanguage") && first.get("detectedLanguage").isJsonObject()) {
                JsonObject language = first.getAsJsonObject("detectedLanguage");
                if (language.has("language")) detected = language.get("language").getAsString();
            }
            return new TranslationResult(
                    translations.get(0).getAsJsonObject().get("text").getAsString(), detected);
        } catch (IOException failure) {
            if (retry && (failure.getMessage().contains("401")
                    || failure.getMessage().contains("205")
                    || failure.getMessage().contains("unexpected"))) {
                synchronized (bingLock) { bing = null; }
                return requestMicrosoft(text, targetLang, false);
            }
            throw failure;
        }
    }

    private BingRequest nextBingRequest() throws IOException {
        synchronized (bingLock) {
            long now = System.currentTimeMillis();
            if (bing == null || now - bing.issuedAt() >= Math.max(1_000L, bing.expiryMs() - 30_000L)) {
                String page = transport.get("https://www.bing.com/translator");
                Matcher params = Pattern.compile(
                        "params_AbusePreventionHelper\\s?=\\s?(\\[[^\\]]+\\])").matcher(page);
                Matcher ig = Pattern.compile("IG:\\\"([^\\\"]+)\\\"").matcher(page);
                Matcher iid = Pattern.compile("data-iid=\\\"([^\\\"]+)\\\"").matcher(page);
                if (!params.find() || !ig.find() || !iid.find()) {
                    throw new IOException("Microsoft session fields missing");
                }
                JsonArray values = JsonParser.parseString(params.group(1)).getAsJsonArray();
                long key = values.get(0).getAsLong();
                bing = new BingSession(key, values.get(1).getAsString(),
                        values.get(2).getAsLong(), now, ig.group(1), iid.group(1), 0);
            }
            bing = bing.incremented();
            String url = "https://www.bing.com/ttranslatev3?isVertical=1&&IG=" + enc(bing.ig())
                    + "&IID=" + enc(bing.iid()) + "&SFX=" + bing.counter()
                    + "&ref=TThis&edgepdftranslator=1";
            return new BingRequest(url, bing.key(), bing.token());
        }
    }

    private String source() {
        try {
            String value = sourceLanguage == null ? null : sourceLanguage.get();
            return value == null || value.isBlank() ? "auto" : value;
        } catch (RuntimeException ignored) {
            return "auto";
        }
    }

    private static WireBatch buildWire(List<String> texts) {
        int protectedCount = texts.stream().mapToInt(ExperimentalWebTranslator::protectedCount).sum();
        int sentinelCount = texts.size() * 2 + protectedCount;
        int base = sentinelBase(texts, sentinelCount);
        int[] next = {base + texts.size() * 2};
        List<MaskedItem> masked = new ArrayList<>(texts.size());
        for (String text : texts) masked.add(mask(text, next));
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(base + i * 2).append(masked.get(i).wire())
                    .append(base + i * 2 + 1);
        }
        return new WireBatch(joined.toString(), base, sentinelCount, List.copyOf(masked));
    }

    private static int protectedCount(String text) {
        String value = safe(text);
        int count = 0;
        Matcher matcher = ANY_TOKEN.matcher(value);
        while (matcher.find()) count++;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || (ch == '\r' && (i + 1 >= value.length() || value.charAt(i + 1) != '\n'))) count++;
        }
        return count;
    }

    private static MaskedItem mask(String text, int[] next) {
        String source = safe(text);
        StringBuilder wire = new StringBuilder(source.length());
        List<Slot> slots = new ArrayList<>();
        Matcher matcher = ANY_TOKEN.matcher(source);
        int cursor = 0;
        while (matcher.find()) {
            appendLiteral(source, cursor, matcher.start(), wire, slots, next);
            addSlot(matcher.group(), wire, slots, next);
            cursor = matcher.end();
        }
        appendLiteral(source, cursor, source.length(), wire, slots, next);
        return new MaskedItem(wire.toString(), List.copyOf(slots));
    }

    private static void appendLiteral(String source, int start, int end, StringBuilder wire,
                                      List<Slot> slots, int[] next) {
        for (int i = start; i < end; i++) {
            char ch = source.charAt(i);
            if (ch == '\r') {
                if (i + 1 < end && source.charAt(i + 1) == '\n') i++;
                addSlot("\n", wire, slots, next);
            } else if (ch == '\n') addSlot("\n", wire, slots, next);
            else wire.append(ch);
        }
    }

    private static void addSlot(String original, StringBuilder wire, List<Slot> slots, int[] next) {
        String sentinel = Integer.toString(next[0]++);
        wire.append(sentinel);
        slots.add(new Slot(sentinel, original));
    }

    private static String restorePart(String translated, MaskedItem item) {
        if (translated == null) return null;
        Map<String, String> replacements = new LinkedHashMap<>();
        for (Slot slot : item.slots()) {
            replacements.put(slot.sentinel(), slot.original());
        }
        return NumericMarkerCodec.restoreExactlyOnce(translated, replacements);
    }

    private static List<String> extractAnchoredBatch(String translated, int count,
                                                     int base, int markerCount) {
        return NumericMarkerCodec.extractAnchored(translated, count, base, markerCount);
    }

    private static int sentinelBase(List<String> texts, int count) {
        int base = SENTINEL_BASE;
        outer: while (true) {
            for (String text : texts) {
                String source = safe(text);
                for (int i = 0; i < count; i++) {
                    if (source.contains(Integer.toString(base + i))) {
                        base += 2_000;
                        continue outer;
                    }
                }
            }
            return base;
        }
    }

    private static String mapYoudao(String language) {
        String tag = normalizedTag(language);
        if (isAuto(tag)) return "auto";
        if (isTraditionalChinese(tag)) return "zh-CHT";
        if (tag.equals("zh") || tag.startsWith("zh-cn") || tag.startsWith("zh-hans")
                || tag.startsWith("zh-sg")) return "zh-CHS";
        return primary(tag);
    }

    private static String mapDeepL(String language) {
        String tag = normalizedTag(language);
        if (isTraditionalChinese(tag)) return "zh-Hant";
        if (tag.equals("zh") || tag.startsWith("zh-cn") || tag.startsWith("zh-hans")
                || tag.startsWith("zh-sg")) return "zh-Hans";
        return tag;
    }

    private static String mapMicrosoft(String language) {
        String tag = normalizedTag(language);
        if (isAuto(tag)) return "auto-detect";
        if (isTraditionalChinese(tag)) return "zh-Hant";
        if (tag.equals("zh") || tag.startsWith("zh-cn") || tag.startsWith("zh-hans")
                || tag.startsWith("zh-sg")) return "zh-Hans";
        return tag;
    }

    private static boolean isTraditionalChinese(String tag) {
        return tag.startsWith("zh-tw") || tag.startsWith("zh-hk")
                || tag.startsWith("zh-mo") || tag.startsWith("zh-hant");
    }
    private static boolean isAuto(String value) { return value == null || value.equalsIgnoreCase("auto"); }
    private static String normalizedTag(String value) {
        return value == null || value.isBlank() ? "auto"
                : value.strip().replace('_', '-').toLowerCase(java.util.Locale.ROOT);
    }
    private static String primary(String value) {
        int dash = value.indexOf('-');
        return dash < 0 ? value : value.substring(0, dash);
    }

    private static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        return out.toString();
    }
    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    private static String md5(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b & 0xFF));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("MD5 unavailable", impossible);
        }
    }
    private static JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) throw new IOException("unexpected JSON root");
            return parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("malformed JSON", malformed);
        }
    }
    private static String safe(String text) { return text == null ? "" : text; }

    private record Slot(String sentinel, String original) { }
    private record MaskedItem(String wire, List<Slot> slots) { }
    private record WireBatch(String text, int base, int markerCount, List<MaskedItem> items) { }
    private record BingRequest(String url, long key, String token) { }
    private record BingSession(long key, String token, long expiryMs, long issuedAt,
                               String ig, String iid, int counter) {
        BingSession incremented() {
            return new BingSession(key, token, expiryMs, issuedAt, ig, iid, counter + 1);
        }
    }
}
