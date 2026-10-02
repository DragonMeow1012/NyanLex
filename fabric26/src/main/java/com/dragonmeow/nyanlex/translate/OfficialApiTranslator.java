package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.config.MachineTranslationProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapters for the official translation APIs of DeepL and Microsoft Translator, used
 * with the player's own API key. The key is sent only as a request header to the
 * provider's own host, is never placed in a URL, and is scrubbed from any error text.
 *
 * <p>Every cache item is isolated: rich markers and newlines never reach the service
 * (they are masked to numeric sentinels), and a damaged response is rejected or bisected
 * before anything can be written to a neighbouring key.</p>
 */
final class OfficialApiTranslator implements Translator {
    private static final Pattern ANY_TOKEN = Pattern.compile("\\u27E6[^\\u27E6\\u27E7]*\\u27E7");
    private static final int SENTINEL_BASE = 76001;
    private static final int MAX_WIRE_CHARS = 1400;
    private static final int ITEM_OVERHEAD = 24;

    static final String DEEPL_FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate";
    static final String DEEPL_PRO_ENDPOINT = "https://api.deepl.com/v2/translate";
    static final String MICROSOFT_ENDPOINT =
            "https://api.cognitive.microsofttranslator.com/translate";

    /** Live credentials; read on every request so a settings change applies immediately. */
    interface Credentials {
        String deeplKey();
        String microsoftKey();
        String microsoftRegion();
    }

    private final HttpTransport transport;
    private final Supplier<String> sourceLanguage;
    private final MachineTranslationProvider provider;
    private final RequestPacer pacer;
    private final Credentials credentials;

    OfficialApiTranslator(HttpTransport transport, Supplier<String> sourceLanguage,
                          MachineTranslationProvider provider, RequestPacer pacer,
                          Credentials credentials) {
        if (provider == null || !provider.requiresKey()) {
            throw new IllegalArgumentException("official API provider required");
        }
        this.transport = transport;
        this.sourceLanguage = sourceLanguage;
        this.provider = provider;
        this.pacer = pacer == null ? RequestPacer.disabled() : pacer;
        this.credentials = credentials;
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
        int start = 0;
        while (start < texts.size()) {
            int end = start + 1;
            int chars = safe(texts.get(start)).length() + ITEM_OVERHEAD;
            while (end < texts.size()) {
                int next = safe(texts.get(end)).length() + ITEM_OVERHEAD;
                if (chars + next > MAX_WIRE_CHARS) break;
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
        String key = key();
        if (key.isEmpty()) {
            throw new TranslationException(provider.id() + ": no API key set");
        }
        try {
            pacer.acquire();
            return switch (provider) {
                case DEEPL_API -> requestDeepL(text, targetLang, key);
                case MICROSOFT_API -> requestMicrosoft(text, targetLang, key);
                default -> throw new IOException("unsupported provider " + provider.id());
            };
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains(key)) message = message.replace(key, "***");
            throw new TranslationException(provider.id() + " http error: " + message);
        }
    }

    private String key() {
        try {
            String value = provider == MachineTranslationProvider.DEEPL_API
                    ? credentials.deeplKey() : credentials.microsoftKey();
            return value == null ? "" : value.strip();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** DeepL: free-plan keys end with ":fx" and use the api-free host; others use api. */
    static String deeplEndpoint(String key) {
        return key.endsWith(":fx") ? DEEPL_FREE_ENDPOINT : DEEPL_PRO_ENDPOINT;
    }

    private TranslationResult requestDeepL(String text, String targetLang, String key)
            throws IOException {
        JsonObject payload = new JsonObject();
        JsonArray texts = new JsonArray();
        texts.add(text);
        payload.add("text", texts);
        payload.addProperty("target_lang", mapDeepLTarget(targetLang));
        String source = source();
        if (!isAuto(source)) payload.addProperty("source_lang", mapDeepLSource(source));
        payload.addProperty("split_sentences", "0");
        payload.addProperty("preserve_formatting", true);
        String body = transport.post(deeplEndpoint(key), payload.toString(), Map.of(
                "Authorization", "DeepL-Auth-Key " + key,
                "Content-Type", "application/json"));
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

    private TranslationResult requestMicrosoft(String text, String targetLang, String key)
            throws IOException {
        StringBuilder url = new StringBuilder(MICROSOFT_ENDPOINT)
                .append("?api-version=3.0&to=").append(enc(mapMicrosoft(targetLang)));
        String source = source();
        if (!isAuto(source)) url.append("&from=").append(enc(mapMicrosoft(source)));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Ocp-Apim-Subscription-Key", key);
        String region = region();
        if (!region.isEmpty()) headers.put("Ocp-Apim-Subscription-Region", region);
        headers.put("Content-Type", "application/json; charset=UTF-8");
        JsonArray payload = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("Text", text);
        payload.add(item);
        String body = transport.post(url.toString(), payload.toString(), headers);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException malformed) {
            throw new IOException("malformed JSON", malformed);
        }
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
    }

    private String region() {
        try {
            String value = credentials.microsoftRegion();
            return value == null ? "" : value.strip();
        } catch (RuntimeException ignored) {
            return "";
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
        int protectedCount = texts.stream().mapToInt(OfficialApiTranslator::protectedCount).sum();
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

    /** DeepL target codes: Chinese variants are ZH-HANT / ZH-HANS; EN and PT need a variant. */
    static String mapDeepLTarget(String language) {
        String tag = normalizedTag(language);
        if (isTraditionalChinese(tag)) return "ZH-HANT";
        if (tag.equals("zh") || tag.startsWith("zh-")) return "ZH-HANS";
        if (tag.equals("en")) return "EN-US";
        if (tag.equals("pt")) return "PT-BR";
        return tag.toUpperCase(Locale.ROOT);
    }

    /** DeepL source codes are bare language codes (no regional variant). */
    static String mapDeepLSource(String language) {
        return primary(normalizedTag(language)).toUpperCase(Locale.ROOT);
    }

    static String mapMicrosoft(String language) {
        String tag = normalizedTag(language);
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
                : value.strip().replace('_', '-').toLowerCase(Locale.ROOT);
    }
    private static String primary(String value) {
        int dash = value.indexOf('-');
        return dash < 0 ? value : value.substring(0, dash);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8);
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
}
