package com.dragonmeow.mctranslator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain configuration holder, serialised as JSON via Gson.
 *
 * <p>Intentionally free of any Minecraft dependency so it can be unit-tested
 * with an in-memory {@link Reader}/{@link Writer}.</p>
 */
public final class TranslatorConfig {

    // Per-surface display mode. Each surface can independently be 原文 (off) /
    // 原文＋翻譯 (both) / 只有翻譯 (translation only). Configured via the in-game
    // 翻譯設定 screen.
    public DisplayMode chatMode = DisplayMode.BOTH;              // 聊天：原文+翻譯 stacked (3-way)
    public DisplayMode tooltipMode = DisplayMode.TRANSLATION;    // 物品名稱／說明（提示與手持共用）(3-way)
    public DisplayMode scoreboardMode = DisplayMode.TRANSLATION; // 記分板 (on/off)
    public DisplayMode nameMode = DisplayMode.TRANSLATION;       // 名牌 / 全息 (on/off)
    public DisplayMode bossBarMode = DisplayMode.TRANSLATION;    // Boss 血條名稱 (on/off)
    public DisplayMode titleMode = DisplayMode.TRANSLATION;      // 標題 / 副標題 (on/off)
    public DisplayMode actionBarMode = DisplayMode.TRANSLATION;  // 動作列訊息 (on/off)
    public DisplayMode bookMode = DisplayMode.TRANSLATION;       // 書籍 / 講台書頁面 (on/off)
    // 自訂模組介面文字（光影/模組設定等，經 GuiGraphics 繪字）。預設關閉——較廣，使用者自行開啟。
    public DisplayMode screenTextMode = DisplayMode.ORIGINAL_ONLY; // 介面文字 (on/off, 預設關)

    // ---- AI fine-translation (精翻) — per-surface: each surface chooses 機翻(Google) or AI ----
    public boolean aiChat = false;
    public boolean aiTooltip = false;
    public boolean aiScoreboard = false;
    public boolean aiName = false;
    public boolean aiBossBar = false;
    public boolean aiTitle = false;
    public boolean aiActionBar = false;
    public boolean aiBook = false;
    /** Engine for the "translate current screen" hotkey (機翻 / AI 精翻). */
    public boolean aiScreenScan = false;
    /** Engine for the always-on custom-GUI text surface ({@link #screenTextMode}). */
    public boolean aiScreenText = false;

    /** OpenAI-compatible base URL (chat/completions is appended). Default: Gemini (high free quota). */
    public String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    /** Model id, e.g. {@code gemini-3.1-flash-lite}, {@code gpt-5.4-mini}, {@code deepseek-chat}. */
    public String aiModel = "gemini-3.1-flash-lite";
    /** One or more API keys (for the active endpoint); rotated round-robin and on failure. */
    public java.util.List<String> aiApiKeys = new java.util.ArrayList<>();

    /** Remembered keys per endpoint (raw, comma-separated) so switching providers restores its key. */
    public java.util.Map<String, String> aiKeysByEndpoint = new java.util.HashMap<>();

    /**
     * User-pinned term translations — the "訂翻譯" mechanism. Each entry is a line like
     * {@code "Skill Book=技能書"} ({@code "英文=中文"}). Only entries present in the
     * current request are appended to the compact Minecraft-aware prompt. Empty by default;
     * malformed / blank lines are ignored at prompt time.
     */
    public java.util.List<String> aiGlossary = new java.util.ArrayList<>();

    /** Google target language. Traditional Chinese = {@code zh-TW}. */
    public String targetLang = "zh-TW";

    /** Follow Minecraft's complete language selection, or use a fixed target chosen in the picker. */
    public boolean followGameLanguage = true;

    /** Google source language. {@code auto} lets Google detect it. */
    public String sourceLang = "auto";

    /**
     * Mask online player names before sending text to the translator, so names are
     * not sent out and stay verbatim (untranslated) in the result.
     */
    public boolean protectPlayerNames = true;

    // Chat is always non-blocking: the original is shown immediately
    // and the translation is appended asynchronously when ready — never hard-waits.

    /** HTTP request/connect timeout in milliseconds. */
    public int httpTimeoutMs = 4000;

    /**
     * 事前冷卻節流：minimum interval, in milliseconds, between two outbound translation
     * requests of the SAME engine (Google and AI each pace independently). Proactive
     * spacing so the free endpoints don't see request bursts; 0 disables pacing.
     */
    public int requestCooldownMs = 400;

    /**
     * After a failed translation, suppress retries of the same string for this
     * many milliseconds. Prevents a per-frame request storm (and 429 bans) when
     * a hovered item's translation keeps failing.
     */
    public int failureBackoffMs = 10000;

    /** Maximum hot entries in memory. Disk entries are permanent and unbounded. */
    public int cacheMaxSize = 5000;

    // ---- 特效字/動畫字防護 (ChurnGuard) ----
    // 記分板倒數、閃爍裝飾字每次微變都是新請求 key；同一「簽名」（去掉數字/符號後的字母骨架）
    // 在視窗內累積過多相異 key 就判定為動畫字並冷卻，期間不再送出新請求（快取照常顯示）。

    /** Enable churn (animated/flashing text) detection & cooldown. */
    public boolean churnGuard = true;

    /** Distinct key variants of one signature within the window that trip the cooldown. */
    public int churnVariantThreshold = 4;

    /** Sliding detection window, in seconds. */
    public int churnWindowSeconds = 60;

    /** Cooldown once tripped: no new requests for this signature, in seconds. */
    public int churnCooldownSeconds = 300;

    /** Show canonical strings that actually cross the backend request boundary. */
    public boolean debugTranslationOverlay = false;

    /** Background worker thread count for async (tooltip/chat) translations. */
    public int workerThreads = 2;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Parse a config from a reader; never returns {@code null}. */
    public static TranslatorConfig fromReader(Reader reader) {
        TranslatorConfig cfg = GSON.fromJson(reader, TranslatorConfig.class);
        return (cfg == null ? new TranslatorConfig() : cfg).normalized();
    }

    /** Serialise this config to a writer. */
    public void writeTo(Writer writer) {
        GSON.toJson(this, writer);
    }

    /** Fill in sane defaults for any missing / invalid fields. */
    public TranslatorConfig normalized() {
        if (targetLang == null || targetLang.isBlank()) targetLang = "zh-TW";
        if (sourceLang == null || sourceLang.isBlank()) sourceLang = "auto";
        if (chatMode == null) chatMode = DisplayMode.BOTH;
        if (tooltipMode == null) tooltipMode = DisplayMode.TRANSLATION;
        if (scoreboardMode == null) scoreboardMode = DisplayMode.TRANSLATION;
        if (nameMode == null) nameMode = DisplayMode.TRANSLATION;
        if (bossBarMode == null) bossBarMode = DisplayMode.TRANSLATION;
        if (titleMode == null) titleMode = DisplayMode.TRANSLATION;
        if (actionBarMode == null) actionBarMode = DisplayMode.TRANSLATION;
        if (bookMode == null) bookMode = DisplayMode.TRANSLATION;
        if (screenTextMode == null) screenTextMode = DisplayMode.ORIGINAL_ONLY;
        // All surfaces are 3-way (原文 / 翻譯 / 原文＋翻譯). Single-line surfaces (HUD: held name,
        // scoreboard, name tag, boss bar, title, action bar, book, GUI text) render 原文＋翻譯
        // INLINE ("原文　譯文") since they can't stack two lines; chat & tooltip use a block.
        if (aiBaseUrl == null || aiBaseUrl.isBlank()) aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        if (aiModel == null) aiModel = "";
        if (aiApiKeys == null) aiApiKeys = new java.util.ArrayList<>();
        if (aiKeysByEndpoint == null) aiKeysByEndpoint = new java.util.HashMap<>();
        if (aiGlossary == null) aiGlossary = new java.util.ArrayList<>();
        if (httpTimeoutMs <= 0) httpTimeoutMs = 4000;
        if (requestCooldownMs < 0) requestCooldownMs = 400; // 0 is valid: pacing off
        if (failureBackoffMs < 0) failureBackoffMs = 10000;
        if (cacheMaxSize <= 0) cacheMaxSize = 5000;
        if (churnVariantThreshold < 2) churnVariantThreshold = 4;
        if (churnWindowSeconds <= 0) churnWindowSeconds = 60;
        if (churnCooldownSeconds <= 0) churnCooldownSeconds = 300;
        if (workerThreads <= 0) workerThreads = 2;
        return this;
    }

    /** Load from disk, creating a default file if none exists or it is corrupt. */
    public static TranslatorConfig load(Path path) {
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    return fromReader(r);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through and write a fresh default
        }
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.save(path);
        return cfg;
    }

    /** Best-effort save; failures are swallowed (config is non-critical). */
    public void save(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writeTo(w);
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}
