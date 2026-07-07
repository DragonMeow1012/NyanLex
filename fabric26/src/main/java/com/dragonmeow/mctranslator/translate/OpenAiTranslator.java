package com.dragonmeow.mctranslator.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    public OpenAiTranslator(HttpTransport transport, Supplier<AiSettings> settings) {
        this.transport = transport;
        this.settings = settings;
    }

    public boolean isConfigured() {
        AiSettings s = settings.get();
        return s != null && s.isConfigured();
    }

    @Override
    public TranslationResult translate(String text, String targetLang) throws TranslationException {
        return translateBatch(List.of(text), targetLang).get(0);
    }

    @Override
    public List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        if (texts.isEmpty()) return List.of();
        AiSettings s = settings.get();
        if (s == null || !s.isConfigured()) {
            throw new TranslationException("AI translator not configured (model / API key missing)");
        }

        String numbered = buildPrompt(texts, targetLang);
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

    /**
     * A translated line may ABSORB source tokens (models often rewrite "Jun ⟦MT0⟧, ⟦MT1⟧"
     * into a native date) — that only loses a substitution. What it must NEVER do is
     * contain tokens that are not the source line's own: that means the model merged or
     * shifted lines, and caching it would poison another line's key.
     */
    static boolean tokensMatch(String source, String translated) {
        List<String> got = tokensOf(translated);
        if (got.isEmpty()) return true;
        List<String> want = tokensOf(source);
        for (String token : got) {
            if (!want.remove(token)) return false; // alien or over-counted token
        }
        return true;
    }

    private static List<String> tokensOf(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = ANY_TOKEN.matcher(text);
        while (m.find()) out.add(m.group().replace(" ", ""));
        return out;
    }

    // ---- prompt / request building ----

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
            // Gemini 2.5 Flash enables dynamic thinking by default on the OpenAI-compat
            // layer; for short translation lines it only burns output tokens.
            root.addProperty("reasoning_effort", "none");
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", buildSystemPrompt(targetLang, s.glossary())));
        messages.add(message("user", numberedLines));
        root.add("messages", messages);
        return GSON.toJson(root);
    }

    // ---- Minecraft-aware system prompt ----

    /**
     * Curated Minecraft English → 繁體中文 glossary (official language-file /
     * community wording). Appended to the system prompt for Traditional-Chinese targets so
     * the model uses Minecraft's ESTABLISHED terms instead of generic dictionary
     * translations — e.g. Melon 西瓜 (not 甜瓜), Enchant 附魔 (not 魔法), Skill Book 技能書
     * (not 食譜書).
     *
     * <p>Easy to extend: add a {@code "English → 中文"} line. When several English synonyms
     * share one Chinese term, list them on one line separated by {@code " / "}. Keep the
     * arrow ({@code →}) so the compact prompt rendering stays consistent.</p>
     */
    static final List<String> MINECRAFT_GLOSSARY_ZH_TW = List.of(
            "Melon / Watermelon → 西瓜",
            "Enchant / Enchanting / Enchantment → 附魔",
            "Enchanting Table → 附魔台",
            "Skill Book → 技能書",
            "Recipe Book → 食譜",
            "Creeper → 苦力怕",
            "Enderman → 終界使者",
            "The End → 終界",
            "Ender Pearl → 終界珍珠",
            "Nether → 地獄",
            "Redstone → 紅石",
            "Obsidian → 黑曜石",
            "Netherite → 獄髓",
            "Diamond → 鑽石",
            "Mob → 生物",
            "Spawn → 生成",
            "Spawner / Monster Spawner → 生怪磚",
            "Villager → 村民",
            "Raid → 襲擊",
            "Trident → 三叉戟",
            "Ghast → 惡魂",
            "Blaze → 烈焰使者",
            "Elytra → 鞘翅",
            "Shulker → 界伏蚌",
            "Slime → 史萊姆");

    /**
     * Build the system message. Unconditionally frames the text as Minecraft (Java Edition
     * and mods) in-game strings and asks for Minecraft's established terminology. For Chinese
     * targets it then appends a glossary block: the curated 繁體 defaults (Traditional targets
     * only, since the terms are Traditional-specific) followed by the user's own overrides.
     */
    static String buildSystemPrompt(String targetLang, List<String> userGlossary) {
        String lang = langName(targetLang);
        StringBuilder sb = new StringBuilder();
        sb.append("You are a professional video-game localizer for Minecraft (Java Edition) and its mods. ")
                .append("The numbered lines below are in-game text (item and block names, tooltips/lore, GUI labels, chat, advancements). ")
                .append("Translate each numbered line into ").append(lang).append(", keeping terminology coherent across lines. ")
                .append("Use Minecraft's own ESTABLISHED terminology in the target language — the wording from the game's official language files and the community glossary — rather than a generic dictionary translation. ")
                .append("Keep numbers, symbols and formatting codes intact. ")
                .append("Translate each word as a WHOLE: NEVER mix the original script and the target script inside a single word. ")
                .append("For a personal name or any untranslatable proper noun, either transliterate/translate it COMPLETELY into ").append(lang)
                .append(" or keep it ENTIRELY in its original spelling — never output a partly-converted word (for example, never turn \"jacob\" into \"傑cob\"; write either \"雅各\" or \"jacob\"). ")
                .append("Any ⟦…⟧ token (e.g. ⟦MT0⟧, ⟦0⟧, ⟦CS1⟧…⟦/CS1⟧ marker pairs) must be copied verbatim and stay attached to the words it wraps. ")
                .append("Reply with ONLY the numbered translations, same numbering and line count, no commentary.");

        if (isChineseTarget(targetLang)) {
            List<String> user = parseUserGlossary(userGlossary);
            boolean traditional = isTraditionalChineseTarget(targetLang);
            // Only emit the glossary block if there is actually something to put in it.
            if (traditional || !user.isEmpty()) {
                List<String> entries = new ArrayList<>();
                if (traditional) entries.addAll(MINECRAFT_GLOSSARY_ZH_TW);
                entries.addAll(user); // user entries come LAST → they override the defaults
                sb.append("\n\nMinecraft glossary — use these exact translations")
                        .append(" (if a term appears more than once, the LAST entry wins): ")
                        .append(String.join("; ", entries))
                        .append('.');
            }
        }
        return sb.toString();
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

    /** Traditional Chinese only — the script the curated 繁體 glossary is written for. */
    static boolean isTraditionalChineseTarget(String targetLang) {
        return langName(targetLang).contains("Traditional Chinese");
    }

    /** Whether to disable model "thinking" for this model id (Gemini 2.5+ Flash family). */
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
        // Start at a rotating offset so load spreads across keys.
        int start = Math.floorMod(keyCursor.getAndIncrement(), keys.size());
        for (int n = 0; n < keys.size(); n++) {
            String key = keys.get((start + n) % keys.size());
            if (key == null || key.isBlank()) continue;
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + key.trim());
            for (int attempt = 0; attempt <= RETRIES_PER_KEY; attempt++) {
                try {
                    return parseContent(transport.post(url, body, headers));
                } catch (IOException e) {
                    last = e;
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
        throw new TranslationException("AI request failed (all keys): "
                + (last == null ? "no usable key" : last.getMessage()), last);
    }

    private static boolean isBadRequest(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("HTTP 400");
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
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        boolean sawNumber = false;
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher m = NUMBERED.matcher(line);
            if (m.matches()) {
                sawNumber = true;
                if (cur != null) out.add(cur.toString());
                cur = new StringBuilder(m.group(2).strip());
            } else if (cur != null) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(line); // continuation of a wrapped translation
            } else {
                out.add(line); // unnumbered leading line
            }
        }
        if (cur != null) out.add(cur.toString());
        // No numbering at all: fall back to one entry per non-blank line.
        if (!sawNumber) {
            out.clear();
            for (String raw : content.split("\n", -1)) {
                String line = raw.strip();
                if (!line.isEmpty()) out.add(line);
            }
        }
        if (expected > 0) {
            while (out.size() > expected) out.remove(out.size() - 1);
            while (out.size() < expected) out.add("");
        }
        return out;
    }
}
