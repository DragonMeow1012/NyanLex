package com.dragonmeow.mctranslator.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link Translator} backed by an OpenAI-compatible chat-completions endpoint
 * (OpenAI, DeepSeek, OpenRouter, local servers, …) for higher-quality "精翻".
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li><b>Context-aware batching:</b> {@link #translateBatch} sends every line of a
 *       surface (e.g. a whole item tooltip) in <em>one</em> request, numbered, so the
 *       model translates them coherently with shared context (fixes things like
 *       "EV Yields" → "電動車產量").</li>
 *   <li><b>Multi-key rotation:</b> keys are tried round-robin and, on failure
 *       (rate-limit / auth), the next key is tried before giving up.</li>
 * </ul>
 *
 * <p>The {@link HttpTransport} and {@link AiSettings} supplier are injected so this
 * is unit-testable with an inline fake transport.</p>
 */
public final class OpenAiTranslator implements Translator {

    private static final Gson GSON = new Gson();

    private final HttpTransport transport;
    private final Supplier<AiSettings> settings;
    private final AtomicInteger keyCursor = new AtomicInteger();
    private final LongSupplier clock;
    private final RequestPacer pacer;

    // ---- global 429 backoff gate ----
    // When EVERY key in one rotation comes back 429, the account/model quota itself is
    // exhausted — rotating keys just burns more quota. The gate fails every request fast
    // (DispatchingTranslator then falls back to Google) until the penalty expires;
    // consecutive trips double the penalty, any success resets it.
    private static final long RATE_LIMIT_BASE_PENALTY_MS = 60_000L;
    private static final long RATE_LIMIT_MAX_PENALTY_MS = 600_000L;
    private final Object gateLock = new Object();
    private volatile long rateLimitedUntil;
    private long penaltyMs; // guarded by gateLock

    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings) {
        this(transport, settings, System::currentTimeMillis, RequestPacer.disabled());
    }

    /** Clock-injecting constructor so the 429 gate is unit-testable with a fake clock. */
    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings, LongSupplier clock) {
        this(transport, settings, clock, RequestPacer.disabled());
    }

    /** Pacer-injecting constructor: {@code pacer} throttles EVERY outbound HTTP request
     *  (including per-key rotation and transient-error retries). */
    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings, RequestPacer pacer) {
        this(transport, settings, System::currentTimeMillis, pacer);
    }

    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings,
                            LongSupplier clock, RequestPacer pacer) {
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.pacer = pacer == null ? RequestPacer.disabled() : pacer;
    }

    public boolean isConfigured() {
        AiSettings s = settings.get();
        return s != null && s.isConfigured();
    }

    /** Whether the global 429 gate is currently CLOSED (still backing off). Consulted by the
     *  provisional-retry gate: a GT stand-in is only re-asked of the AI once this is false. */
    public boolean isRateLimited() {
        long until = rateLimitedUntil;
        return until != 0 && clock.getAsLong() < until;
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        return translateBatch(List.of(text), targetLang).get(0);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        return translateBatch(texts, targetLang, null);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                  List<String> surfaceContext) throws TranslationException {
        if (texts.isEmpty()) return List.of();
        AiSettings s = settings.get();
        if (s == null || !s.isConfigured()) {
            throw new TranslationException("AI translator not configured (model / API key missing)");
        }
        long gateUntil = rateLimitedUntil;
        if (clock.getAsLong() < gateUntil) {
            // Fail fast without HTTP: the caller's DispatchingTranslator falls back to Google.
            throw new TranslationException("AI rate-limited (429 on all keys): backing off");
        }

        String numbered = buildSurfaceContextBlock(surfaceContext) + buildPrompt(texts, targetLang);
        String requestBody = buildRequestBody(s, targetLang, numbered, true);
        // If the endpoint rejects the reasoning override (HTTP 400), retry without it.
        String plainBody = wantsNoReasoning(s.model()) ? buildRequestBody(s, targetLang, numbered, false) : null;
        String content = postWithKeyRotation(s, requestBody, plainBody);
        List<String> lines = parseNumbered(content, texts.size());
        if (lines.size() != texts.size()) {
            throw new TranslationException(
                    "AI returned " + lines.size() + " lines, expected " + texts.size());
        }
        List<TranslationResult> out = new ArrayList<>(texts.size());
        for (int i = 0; i < lines.size(); i++) {
            // A translated line must carry the SAME ⟦…⟧ tokens as its source line.
            // A mismatch means the model merged/shifted lines — caching that would
            // poison the wrong key (e.g. /levels text landing on a progress bar), so
            // the line is failed individually instead.
            out.add(new TranslationResult(tokensMatch(texts.get(i), lines.get(i)) ? lines.get(i) : "", null));
        }
        return out;
    }

    private static final java.util.regex.Pattern ANY_TOKEN =
            java.util.regex.Pattern.compile("⟦[^⟦⟧]*⟧");
    private static final java.util.regex.Pattern CS_TOKEN =
            java.util.regex.Pattern.compile("⟦\\s*/?\\s*CS\\s*\\d+\\s*⟧");

    /** Every protocol token must survive exactly once. Complete pairs may move for target
     * grammar, but a missing, duplicated, or foreign token would lose a live value or
     * poison another template and is therefore a per-line content failure. */
    static boolean tokensMatch(String source, String translated) {
        // In fixed-column rows, no live value or styled phrase may cross a WS boundary.
        // Ordinary prose without WS slots still allows complete MT/CS units to move for
        // target-language grammar.
        if (!TranslationTemplate.layoutSkeletonMatches(source, translated)) return false;
        if (!TranslationTemplate.styleSlotShapeMatches(source, translated)) return false;
        if (!sameTokenMultiset(tokensOf(source, CS_TOKEN), tokensOf(translated, CS_TOKEN))) {
            return false;
        }
        return sameTokenMultiset(tokensOf(source), tokensOf(translated));
    }

    private static List<String> tokensOf(String text) {
        return tokensOf(text, ANY_TOKEN);
    }

    private static List<String> tokensOf(String text, java.util.regex.Pattern pattern) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group().replace(" ", ""));
        return out;
    }

    private static boolean sameTokenMultiset(List<String> first, List<String> second) {
        if (first.size() != second.size()) return false;
        List<String> remaining = new ArrayList<>(first);
        for (String token : second) if (!remaining.remove(token)) return false;
        return remaining.isEmpty();
    }

    // ---- prompt / request building ----

    /**
     * Prefix for the user message when a numbered batch comes from one visible surface:
     * shows the model the whole information block so cached or dynamic rows still shape
     * terminology. No row is presumed to be a title; books, logs and HUD panels commonly
     * begin directly with body text. Returns "" when there is no context.
     */
    private static final int MAX_CONTEXT_LINES = 24;
    private static final int MAX_CONTEXT_CHARS = 2_000;

    static String buildSurfaceContextBlock(List<String> surfaceContext) {
        if (surfaceContext == null || surfaceContext.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Minecraft visible block context (semantic reference for domain and terminology; layout is program-owned):\n");
        int emitted = 0;
        for (int i = 0; i < surfaceContext.size() && emitted < MAX_CONTEXT_LINES; i++) {
            String line = surfaceContext.get(i);
            if (line == null) continue;
            line = TextFilter.stripFormatting(line).replace('\n', ' ').strip();
            if (line.isEmpty()) {
                sb.append("[SECTION]\n");
                emitted++;
                continue;
            }
            if (sb.length() + line.length() + 28 > MAX_CONTEXT_CHARS) break;
            sb.append("[L").append(i).append(':')
                    .append(contextKind(line)).append("] ")
                    .append(line).append('\n');
            emitted++;
        }
        if (emitted == 0) return "";
        if (emitted < surfaceContext.size()) sb.append("[remaining context omitted]\n");
        sb.append("Translate ONLY the numbered units below; do not output the visible-block context.\n\n");
        return sb.toString();
    }

    private static String contextKind(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(?:ability|skill|mana cost|cooldown|能力|技能|魔力消耗|冷卻).*")) {
            return "ABILITY";
        }
        if (lower.matches(".*(?:bin price|avg\\. price|item value|obtained|museum|售價|價格|博物館).*")) {
            return "MARKET";
        }
        if (lower.matches(".*(?:gear score|damage|strength|speed|intelligence|fortune|health|defense|裝備分數|傷害|力量|速度|智力|財富|生命|防禦).*[:：].*")) {
            return "STAT";
        }
        if (lower.matches(".*\\b(?:i|ii|iii|iv|v|vi|vii|viii|ix|x)\\b.*")) return "ENCHANT";
        return "TEXT";
    }

    String buildPrompt(List<String> texts, String targetLang) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            sb.append(i + 1).append(". ").append(texts.get(i).replace("\n", " ")).append('\n');
        }
        return sb.toString();
    }

    private String buildRequestBody(AiSettings s, String targetLang, String numberedLines, boolean noReasoning) {
        String model = s.model();
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", 0.3);
        if (noReasoning && wantsNoReasoning(model)) {
            // Gemini Flash models may enable dynamic thinking on the OpenAI-compatible
            // layer; short translation lines do not benefit from those extra tokens.
            root.addProperty("reasoning_effort", "none");
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", buildSystemPrompt(targetLang, s.glossary(), numberedLines)));
        messages.add(message("user", numberedLines));
        root.add("messages", messages);
        return GSON.toJson(root);
    }

    // ---- Minecraft-aware system prompt ----

    /**
     * Build the system message. Unconditionally frames the text as Minecraft (Java Edition
     * and mods) in-game strings and asks for Minecraft's established terminology. The former
     * built-in glossary was intentionally removed: repeating dozens of unrelated terms on
     * every request was a substantial fixed token cost. Explicit user overrides remain.
     */
    static String buildSystemPrompt(String targetLang, List<String> userGlossary) {
        return buildSystemPrompt(targetLang, userGlossary, null);
    }

    /**
     * Build the compact system message and include only glossary entries that occur in this
     * request. Sending the complete glossary on every HUD change used more input tokens than
     * the text being translated; exact request-local filtering preserves terminology without
     * paying that fixed cost.
     */
    static String buildSystemPrompt(String targetLang, List<String> userGlossary,
                                    String requestText) {
        String lang = langName(targetLang);
        StringBuilder sb = new StringBuilder();
        sb.append("Translate Minecraft Java/mod in-game text into ").append(lang).append(". ")
                .append("Use official Minecraft translations as the terminology baseline for vanilla concepts, not as a rigid word-for-word template. ")
                .append("Adapt naturally to the detected server/mod genre and keep wording coherent across lines. ")
                .append("The source may be vanilla Minecraft or any server/mod genre, including RPG/MMO equipment, stats, abilities, quests and economy. ")
                .append("Infer ambiguous terms from the entire visible-block context, never as isolated dictionary labels. ")
                .append("Return exactly one numbered translation per numbered input unit, with the same numbering and no commentary. ")
                .append("Never merge, split, add, remove or reorder numbered units; the program owns all sections, PB line breaks and blank lines. ")
                .append("Keep numbers, symbols and formatting codes intact. ")
                .append("Translate ordinary UI, item and location terms completely; do not leave a source-language location word unchanged while translating the rest. ")
                .append("Translate each word as a WHOLE: NEVER mix the original script and the target script inside a single word. ")
                .append("For names, translate/transliterate the WHOLE name or keep it unchanged; never turn \"jacob\" into \"傑cob\". ")
                .append("Copy every ⟦…⟧ placeholder verbatim. Treat each ⟦CSn⟧...⟦/CSn⟧ pair like a BBCode style tag: keep the complete pair around the translation of the same semantic phrase even when target grammar reorders phrases. Never drop, nest incorrectly, or duplicate CS tags or ⟦WSn⟧ layout slots. ")
                .append("Treat each ⟦PBn⟧ as an immutable line break inside one semantic paragraph: keep all PB tokens in the same order while translating coherently across them.");

        if (isTraditionalChineseTarget(targetLang)) {
            sb.append(" Prefer established Minecraft and Traditional-Chinese gaming wording")
                    .append(" (for example, Enchant → 附魔). In an RPG stat or combat block,")
                    .append(" Damage means 傷害, not 損壞; interpret equipment and character")
                    .append(" stat labels by their gameplay meaning likewise. For server/mod-specific")
                    .append(" content, write concise, natural Taiwan player-facing RPG/MMO/mod text")
                    .append(" instead of stiff dictionary translations. Keep established proper names")
                    .append(" unchanged when translating them would be awkward or ambiguous.");
        }

        if (isChineseTarget(targetLang)) {
            List<String> user = relevantGlossary(parseUserGlossary(userGlossary), requestText);
            if (!user.isEmpty()) {
                sb.append("\nUser term overrides: ")
                        .append(String.join("; ", user))
                        .append('.');
            }
        }
        return sb.toString();
    }

    static List<String> relevantGlossary(List<String> entries, String requestText) {
        if (entries == null || entries.isEmpty() || requestText == null || requestText.isBlank()) {
            return List.of();
        }
        String haystack = requestText.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null) continue;
            int arrow = entry.indexOf('→');
            String english = (arrow < 0 ? entry : entry.substring(0, arrow)).strip();
            boolean found = false;
            for (String alternative : english.split("/")) {
                String term = alternative.strip().toLowerCase(Locale.ROOT);
                if (!term.isEmpty() && containsTerm(haystack, term)) {
                    found = true;
                    break;
                }
            }
            if (found) out.add(entry);
        }
        return out;
    }

    private static boolean containsTerm(String text, String term) {
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + 1)) {
            int end = at + term.length();
            boolean left = at == 0 || !asciiWord(text.charAt(at - 1));
            boolean right = end == text.length() || !asciiWord(text.charAt(end));
            if (left && right) return true;
        }
        return false;
    }

    private static boolean asciiWord(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    /** Parse user "英文=中文" glossary lines into compact "English → 中文" prompt entries.
     *  Splits on the FIRST {@code '='}; blank lines and lines missing an English or Chinese
     *  side are skipped so a malformed entry never corrupts the prompt. */
    static List<String> parseUserGlossary(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue; // no '=' at all, or empty English side
            String en = line.substring(0, eq).strip();
            String zh = line.substring(eq + 1).strip();
            if (en.isEmpty() || zh.isEmpty()) continue;
            out.add(en + " → " + zh);
        }
        return out;
    }

    /** Any Chinese target (Traditional or Simplified). */
    static boolean isChineseTarget(String targetLang) {
        return langName(targetLang).contains("Chinese");
    }

    /** True only for Traditional Chinese prompt wording. */
    static boolean isTraditionalChineseTarget(String targetLang) {
        return langName(targetLang).contains("Traditional Chinese");
    }

    /** Whether to disable model "thinking" for a Gemini Flash-family model id. */
    static boolean wantsNoReasoning(String model) {
        String m = model == null ? "" : model.toLowerCase();
        return m.contains("gemini") && m.contains("flash") && !m.contains("2.0");
    }

    /** Map a target language code (zh-TW / zh-CN / …) to the human name used in the AI prompt,
     *  so switching 繁體 ↔ 簡體 actually changes the AI output (not just the Google backend). */
    public static String langName(String targetLang) {
        String t = targetLang == null ? "" : targetLang.toLowerCase().replace('_', '-');
        if (t.startsWith("zh-cn") || t.startsWith("zh-hans") || t.equals("zh")) return "Simplified Chinese (zh-CN)";
        if (t.startsWith("zh-tw") || t.startsWith("zh-hant") || t.startsWith("zh-hk") || t.startsWith("zh")) return "Traditional Chinese (zh-TW)";
        return targetLang; // non-Chinese target: pass the code through
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    // ---- HTTP with key rotation ----

    /** Retries per key for transient server errors (e.g. Gemini's frequent 503 "overloaded"). */
    private static final int RETRIES_PER_KEY = 2;
    private static final long RETRY_BACKOFF_MS = 700L;

    private String postWithKeyRotation(AiSettings s, String body, String fallbackBody) throws TranslationException {
        List<String> keys = s.apiKeys();
        String url = chatCompletionsUrl(s.baseUrl());
        IOException last = null;
        int usableKeys = 0;
        int rateLimitedKeys = 0;
        // Start at a rotating offset so load spreads across keys.
        int start = Math.floorMod(keyCursor.getAndIncrement(), keys.size());
        for (int n = 0; n < keys.size(); n++) {
            String key = keys.get((start + n) % keys.size());
            if (key == null || key.isBlank()) continue;
            usableKeys++;
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + key.trim());
            for (int attempt = 0; attempt <= RETRIES_PER_KEY; attempt++) {
                try {
                    pacer.acquire(); // 事前冷卻：every outbound request is spaced by requestCooldownMs
                    String content = parseContent(transport.post(url, body, headers));
                    resetRateLimitGate(); // any success proves the quota is back
                    return content;
                } catch (IOException e) {
                    last = e;
                    // 429: this key's quota is gone RIGHT NOW — retrying it only digs the
                    // hole deeper. Move straight to the next key.
                    if (isRateLimited(e)) {
                        rateLimitedKeys++;
                        break;
                    }
                    // A 400 most likely means this endpoint rejects an optional field
                    // (e.g. reasoning_effort): drop to the plain body and retry once.
                    if (fallbackBody != null && isBadRequest(e)) {
                        body = fallbackBody;
                        fallbackBody = null;
                        continue;
                    }
                    // Retry the SAME key on a transient 5xx (overloaded); else move to the next key.
                    if (attempt < RETRIES_PER_KEY && isTransient(e)) {
                        sleep(RETRY_BACKOFF_MS * (attempt + 1));
                        continue;
                    }
                    break;
                }
            }
        }
        // A FULL rotation of 429s means the whole quota is exhausted: trip the gate.
        if (usableKeys > 0 && rateLimitedKeys == usableKeys) {
            tripRateLimitGate();
        }
        throw new TranslationException("AI request failed (all keys): "
                + (last == null ? "no usable key" : last.getMessage()), last);
    }

    private void tripRateLimitGate() {
        synchronized (gateLock) {
            penaltyMs = (penaltyMs == 0)
                    ? RATE_LIMIT_BASE_PENALTY_MS
                    : Math.min(penaltyMs * 2, RATE_LIMIT_MAX_PENALTY_MS);
            rateLimitedUntil = clock.getAsLong() + penaltyMs;
        }
    }

    private void resetRateLimitGate() {
        if (rateLimitedUntil == 0) return; // fast path: gate never tripped
        synchronized (gateLock) {
            penaltyMs = 0;
            rateLimitedUntil = 0;
        }
    }

    private static boolean isBadRequest(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("HTTP 400");
    }

    private static boolean isRateLimited(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("HTTP 429");
    }

    private static boolean isTransient(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("HTTP 500") || m.contains("HTTP 502")
                || m.contains("HTTP 503") || m.contains("HTTP 504") || m.contains("HTTP 529"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Join base URL with the chat-completions path, tolerating a trailing slash or included path. */
    public static String chatCompletionsUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    // ---- response parsing ----

    private String parseContent(String responseBody) throws IOException {
        try {
            JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("no choices in AI response");
            }
            JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = msg.get("content").getAsString();
            if (content == null) throw new IOException("empty AI content");
            return content;
        } catch (RuntimeException e) {
            throw new IOException("bad AI response: " + e.getMessage(), e);
        }
    }

    private static final java.util.regex.Pattern NUMBERED =
            java.util.regex.Pattern.compile("^(\\d+)\\s*[.)、]\\s*(.*)$");

    /**
     * Re-assemble the model's reply into exactly {@code expected} translations, keyed by
     * the leading "N." numbering. A translation wrapped across multiple physical lines is
     * joined back to its number; blank lines and a stray trailing note are tolerated; the
     * result is padded/truncated to {@code expected} so one misbehaving line never fails
     * the whole batch (empty entries are treated as per-item failures downstream).
     */
    public static List<String> parseNumbered(String content, int expected) {
        if (content == null) content = "";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(0, expected); i++) out.add("");
        List<String> unnumbered = new ArrayList<>();
        StringBuilder cur = null;
        int curIndex = -1;
        boolean sawNumber = false;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher m = NUMBERED.matcher(line);
            if (m.matches()) {
                sawNumber = true;
                if (cur != null && curIndex >= 0) out.set(curIndex, cur.toString());
                int number;
                try {
                    number = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    number = -1;
                }
                int index = number - 1;
                curIndex = index >= 0 && index < expected && seen.add(index) ? index : -1;
                cur = curIndex >= 0 ? new StringBuilder(m.group(2).strip()) : null;
            } else if (cur != null) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(line); // continuation of a wrapped translation
            } else if (!sawNumber) {
                unnumbered.add(line);
            }
        }
        if (cur != null && curIndex >= 0) out.set(curIndex, cur.toString());
        // No numbering at all: fall back to one entry per non-blank line.
        if (!sawNumber) {
            out.clear();
            out.addAll(unnumbered);
            while (out.size() > expected) out.remove(out.size() - 1);
            while (out.size() < expected) out.add("");
        }
        return out;
    }
}
