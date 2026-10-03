package com.dragonmeow.nyanlex.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

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

    public static final int PACING_DEFAULTS_VERSION = 1;
    public static final String DEFAULT_CODEX_MODEL = "gpt-5.6-terra";
    public static final String DEFAULT_CODEX_REASONING_EFFORT = "medium";
    public static final int MAX_WORKER_THREADS = 8;
    public static final int MAX_MEMORY_CACHE_ENTRIES = 50_000;
    public static final int DEFAULT_PERSISTENT_CACHE_ENTRIES = 100_000;
    public static final int MAX_PERSISTENT_CACHE_ENTRIES = 250_000;
    private static final int LEGACY_REQUEST_COOLDOWN_MS = 6000;
    private static final int LEGACY_WARMUP_CHUNK_DELAY_MS = 3000;
    private static final int LEGACY_WARMUP_MAX_ITEMS = 3000;

    // Per-surface display mode. Each surface can independently be 不翻譯 (ORIGINAL_ONLY: not
    // translated and nothing sent for it) / 雙語 (BOTH) / 譯文 (TRANSLATION). Configured via the
    // in-game 翻譯設定 screen.
    public DisplayMode chatMode = DisplayMode.BOTH;              // 聊天：原文+翻譯 stacked (3-way)
    /** Preserve received chat order; false displays each translation as soon as it is ready. */
    public boolean deliverChatTranslationsInOrder = true;
    /** Explicit outgoing drafts; independent of the language used to read the game. */
    public boolean chatComposerEnabled = false;
    public String chatComposerLanguage = "en";
    public double chatComposerX = 1.0;
    public double chatComposerY = 1.0;
    public DisplayMode tooltipMode = DisplayMode.TRANSLATION;    // 物品名稱／說明（提示與手持共用）(3-way)
    public DisplayMode scoreboardMode = DisplayMode.TRANSLATION; // 記分板 (on/off)
    public DisplayMode nameMode = DisplayMode.TRANSLATION;       // 名牌 / 全息 (on/off)
    public DisplayMode bossBarMode = DisplayMode.TRANSLATION;    // Boss 血條名稱 (on/off)
    public DisplayMode titleMode = DisplayMode.TRANSLATION;      // 標題 / 副標題 (on/off)
    public DisplayMode actionBarMode = DisplayMode.TRANSLATION;  // 動作列訊息 (on/off)
    public DisplayMode bookMode = DisplayMode.TRANSLATION;       // 書籍 / 講台書頁面 (on/off)
    // 自訂模組介面文字（光影/模組設定等，經 GuiGraphics 繪字）。新安裝預設翻譯；既有設定檔沿用自己的值。
    public DisplayMode screenTextMode = DisplayMode.TRANSLATION;   // 介面文字 (on/off)

    // ---- AI fine-translation (精翻) — per-surface: each surface chooses 機翻(Google) or AI ----
    public boolean aiChat = false;
    public boolean aiTooltip = false;
    public boolean aiScoreboard = false;
    public boolean aiName = false;
    public boolean aiBossBar = false;
    public boolean aiTitle = false;
    public boolean aiActionBar = false;
    public boolean aiBook = false;
    /** Engine for the custom-GUI text surface ({@link #screenTextMode}) and the "translate this screen" hotkey. */
    public boolean aiScreenText = false;

    /** Keep AI-selected surfaces on AI after temporary failures instead of using GT. */
    public boolean disableGoogleFallbackForAi = true;

    /** OpenAI-compatible base URL (chat/completions is appended). Default: Gemini (high free quota). */
    public String aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    /** Model id, e.g. {@code gemini-3.1-flash-lite}, {@code gpt-5.4-mini}, {@code deepseek-chat}. */
    public String aiModel = "gemini-3.1-flash-lite";
    /** One or more API keys (for the active endpoint); rotated round-robin and on failure. */
    public java.util.List<String> aiApiKeys = new java.util.ArrayList<>();

    /** Use ChatGPT-authenticated Codex through a local app-server. */
    public boolean aiUseCodex = false;
    /** Codex model selected from the signed-in account's live catalog. */
    public String codexModel = DEFAULT_CODEX_MODEL;
    /** Reasoning effort advertised by the selected Codex model. */
    public String codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;

    /** Remembered keys per endpoint (raw, comma-separated) so switching providers restores its key. */
    public java.util.Map<String, String> aiKeysByEndpoint = new java.util.HashMap<>();

    /**
     * User-pinned term translations — the "訂翻譯" mechanism. Each entry is a line like
     * {@code "Skill Book=技能書"} ({@code "英文=中文"}). Only entries present in the
     * current request are appended to the compact Minecraft-aware prompt. Empty by default;
     * malformed / blank lines are ignored at prompt time.
     */
    public java.util.List<String> aiGlossary = new java.util.ArrayList<>();

    /**
     * P1.7 user overrides for the fixed rarity/type term table (稀有度＋類型詞): key is
     * the exact upper-case English word/phrase from a tooltip's rarity line (e.g.
     * {@code "EPIC"}, {@code "PET ITEM"}), value is the Chinese replacement. Layered on
     * top of the shipped {@code TermTableDefaults}; an override always wins. Words
     * learned at runtime from an out-of-table type word are NOT written here — they
     * live in the ordinary translation cache, never in this config file. Field type is
     * part of the persisted format and must never change.
     */
    public java.util.Map<String, String> termOverrides = new java.util.LinkedHashMap<>();

    /** Google target language. Traditional Chinese = {@code zh-TW}. */
    public String targetLang = "zh-TW";

    /** Follow Minecraft's complete language selection, or use a fixed target chosen in the picker. */
    public boolean followGameLanguage = true;

    /** Google source language. {@code auto} lets Google detect it. */
    public String sourceLang = "auto";

    /** Machine source: always google (unofficial key-free endpoint); other ids migrate to it on load. */
    public String machineTranslationProvider = MachineTranslationProvider.GOOGLE.id();

    /**
     * Mask online player names before sending text to the translator, so names are
     * not sent out and stay verbatim (untranslated) in the result.
     */
    public boolean protectPlayerNames = true;

    /**
     * Master switch for NEW translation requests (送出翻譯請求). {@code false} keeps showing
     * every cached translation but sends nothing new; uncached text stays original and
     * nothing is recorded as a failure, so switching back on simply resumes requests.
     * A fresh install starts with it OFF: no text leaves the game until the player turns it
     * on. A config file written by an earlier build that does not have the key keeps sending
     * (see {@link #fromReader}). Field type is part of the persisted format and must never change.
     */
    public boolean translationRequestsEnabled = false;

    /**
     * The first-start card on the title screen has been answered (any of its choices) or was
     * never needed (an existing config that already translates). It is shown once, while this
     * is false and 線上翻譯 is off. Field type is part of the persisted format and must never change.
     */
    public boolean firstRunDone = false;

    /**
     * Do-not-translate terms (不翻譯詞彙): case-insensitive whole words/phrases that are
     * masked before translation on every surface and engine, then shown verbatim in the
     * original spelling. Independent of {@link #protectPlayerNames}. Field type is part
     * of the persisted format and must never change.
     */
    public java.util.List<String> doNotTranslateTerms = new java.util.ArrayList<>();

    // ---- GitHub AI translation hub (download only) ----

    /**
     * @deprecated Superseded by the explicit "識別當前伺服器/MOD下載並匯入翻譯檔" button
     * (manual plan-then-confirm download, never automatic). Kept — unread by core — only
     * so an already-published config file deserialises without error; field type/name
     * must never change once released. Do not read this field in new code.
     */
    @Deprecated
    public boolean hubAutoImport = false;

    /**
     * Legacy: read only by the older tabbed settings screens of the other loader trees; the
     * card-style screen no longer shows a first-open hint. Field type is part of the persisted
     * format and must never change.
     */
    public boolean settingsIntroSeen = false;

    /**
     * Legacy: the startup check for translation packs no longer exists. A config file that
     * still carries this key is read without error and the value is ignored; it is never
     * written back ({@code transient}). Kept only so older loader glue keeps compiling.
     */
    public transient boolean hubStartupPromptDisabled = false;

    /**
     * Legacy: older builds used this as "resume the pre-translation by itself after the next
     * launch". The pre-translation now only ever runs after the player presses Start or Continue
     * (and, within that same run, after a 429 pause), so a config file that still carries this key
     * is read without error and the value is ignored; it is never written back ({@code transient}).
     * Kept only so older loader glue keeps compiling.
     */
    public transient boolean itemWarmupEnabled = false;

    /** Where the last pre-translation run stopped: items looked at / items in the registry. */
    public int itemWarmupLastScanned = 0;
    public int itemWarmupLastTotal = 0;
    /** The last run reached the end (not stopped, not cut short by the per-launch limit). */
    public boolean itemWarmupLastFinished = false;
    /** Items the last run skipped because their tooltip could not be built. */
    public int itemWarmupLastSkipped = 0;
    /** The last run started without a world, so those skipped items only need a world to be translated. */
    public boolean itemWarmupLastNeedsWorld = false;

    /** The player has seen and accepted the cost/429 warning of the item warm-up. */
    public boolean itemWarmupWarningAcknowledged = false;

    /** Show the small warm-up progress readout in the HUD corner while a run is active. */
    public boolean warmupItems = true;
    public boolean warmupScreenText = true;
    public boolean itemWarmupHud = true;

    /** Minimum pause between two warm-up requests, in milliseconds (independent of the interactive cooldown). */
    public int itemWarmupChunkDelayMs = 1500;

    /** Upper bound of items submitted per game launch; {@code 0} (the default) means no limit. */
    public int itemWarmupMaxItemsPerSession = 0;

    // Chat is always non-blocking (non-blocking): the original is shown immediately
    // and the translation is appended asynchronously when ready — never hard-waits.

    /** HTTP request/connect timeout in milliseconds. */
    public int httpTimeoutMs = 4000;

    /**
     * 事前冷卻節流：minimum interval, in milliseconds, between two outbound translation
     * requests of the SAME engine (Google and AI each pace independently). Proactive
     * spacing so the free endpoints don't see request bursts; 0 disables pacing.
     */
    public int requestCooldownMs = 5000;

    /** Persisted migration marker for pacing defaults. */
    public int pacingDefaultsVersion = PACING_DEFAULTS_VERSION;

    /**
     * Collect ordinary render/chat misses for this long before sending one batch.
     * {@code 0} disables the collection window (the next client tick sends it).
     */
    public int batchWindowMs = 5000;

    /**
     * After a failed translation, suppress retries of the same string for this
     * many milliseconds. Prevents a per-frame request storm (and 429 bans) when
     * a hovered item's translation keeps failing.
     */
    public int failureBackoffMs = 10000;

    /** Maximum hot entries in memory. */
    public int cacheMaxSize = 5000;

    /** Maximum live entries retained in each persistent language/provider partition. */
    public int persistentCacheMaxEntries = DEFAULT_PERSISTENT_CACHE_ENTRIES;

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

    /** An independent copy (the questionnaire edits one until 完成); {@code null} fields are normalised. */
    public TranslatorConfig copy() {
        TranslatorConfig out = GSON.fromJson(GSON.toJson(this), TranslatorConfig.class);
        return (out == null ? new TranslatorConfig() : out).normalized();
    }

    /** Parse a config from a reader; never returns {@code null}. */
    public static TranslatorConfig fromReader(Reader reader) {
        JsonElement json = new JsonParser().parse(reader);
        TranslatorConfig cfg = GSON.fromJson(json, TranslatorConfig.class);
        if (cfg != null && json != null && json.isJsonObject()
                && !json.getAsJsonObject().has("pacingDefaultsVersion")) {
            cfg.pacingDefaultsVersion = 0;
        }
        if (cfg != null && json != null && json.isJsonObject()
                && !json.getAsJsonObject().has("translationRequestsEnabled")) {
            cfg.translationRequestsEnabled = true; // an existing config from before the switch: keep its behaviour
        }
        if (cfg != null && json != null && json.isJsonObject()
                && !json.getAsJsonObject().has("firstRunDone") && cfg.translationRequestsEnabled) {
            cfg.firstRunDone = true; // an existing user who already translates never sees the first-start card
        }
        return (cfg == null ? new TranslatorConfig() : cfg).normalized();
    }

    /** Serialise this config to a writer. */
    public void writeTo(Writer writer) {
        GSON.toJson(this, writer);
    }

    /**
     * Set by {@link #normalized()} when a loaded file selected a web endpoint this build no
     * longer supports (youdao, deepl, microsoft, bing, deepl_api, microsoft_api ...); the source was reset to Google.
     * Not persisted. Loaders log it once via {@link #takeRetiredProviderReset()}.
     */
    private transient String retiredProviderReset;

    /** Returns the retired provider id that was reset to Google during load, once, else null. */
    public String takeRetiredProviderReset() {
        String value = retiredProviderReset;
        retiredProviderReset = null;
        return value;
    }

    /** Every secret value held in this config (for redaction in logs / debug dumps). */
    public java.util.List<String> secretValues() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (aiApiKeys != null) out.addAll(aiApiKeys);
        if (aiKeysByEndpoint != null) {
            for (String joined : aiKeysByEndpoint.values()) {
                if (joined == null) continue;
                for (String part : joined.split("[,\\s]+")) if (!part.isBlank()) out.add(part);
            }
        }
        return out;
    }

    /** Fill in sane defaults for any missing / invalid fields. */
    public TranslatorConfig normalized() {
        if (chatComposerLanguage == null || chatComposerLanguage.isBlank()) chatComposerLanguage = "en";
        if (targetLang == null || targetLang.isBlank()) targetLang = "zh-TW";
        if (sourceLang == null || sourceLang.isBlank()) sourceLang = "auto";
        if (MachineTranslationProvider.isRetiredId(machineTranslationProvider)) {
            retiredProviderReset = machineTranslationProvider.strip();
        }
        machineTranslationProvider = MachineTranslationProvider.normalize(machineTranslationProvider);
        if (chatMode == null) chatMode = DisplayMode.BOTH;
        if (tooltipMode == null) tooltipMode = DisplayMode.TRANSLATION;
        if (scoreboardMode == null) scoreboardMode = DisplayMode.TRANSLATION;
        if (nameMode == null) nameMode = DisplayMode.TRANSLATION;
        if (bossBarMode == null) bossBarMode = DisplayMode.TRANSLATION;
        if (titleMode == null) titleMode = DisplayMode.TRANSLATION;
        if (actionBarMode == null) actionBarMode = DisplayMode.TRANSLATION;
        if (bookMode == null) bookMode = DisplayMode.TRANSLATION;
        if (screenTextMode == null) screenTextMode = DisplayMode.TRANSLATION;
        // All surfaces are 3-way (原文 / 翻譯 / 原文＋翻譯). Single-line surfaces (HUD: held name,
        // scoreboard, name tag, boss bar, title, action bar, book, GUI text) render 原文＋翻譯
        // INLINE ("原文　譯文") since they can't stack two lines; chat & tooltip use a block.
        if (aiBaseUrl == null || aiBaseUrl.isBlank()) aiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
        if (aiModel == null) aiModel = "";
        if (aiApiKeys == null) aiApiKeys = new java.util.ArrayList<>();
        if (codexModel == null || codexModel.isBlank()) codexModel = DEFAULT_CODEX_MODEL;
        if (codexReasoningEffort == null || codexReasoningEffort.isBlank()) {
            codexReasoningEffort = DEFAULT_CODEX_REASONING_EFFORT;
        }
        if (aiKeysByEndpoint == null) aiKeysByEndpoint = new java.util.HashMap<>();
        if (aiGlossary == null) aiGlossary = new java.util.ArrayList<>();
        doNotTranslateTerms = normalizedTerms(doNotTranslateTerms);
        termOverrides = normalizedTermOverrides(termOverrides);
        if (httpTimeoutMs <= 0) httpTimeoutMs = 4000;
        // Older versions shipped 3000 ms / 3000 items as the defaults: those are the old
        // defaults, not a choice, so they move to the new pace (1.5 s, no per-launch limit).
        if (itemWarmupChunkDelayMs == LEGACY_WARMUP_CHUNK_DELAY_MS) itemWarmupChunkDelayMs = 1500;
        if (itemWarmupMaxItemsPerSession == LEGACY_WARMUP_MAX_ITEMS) itemWarmupMaxItemsPerSession = 0;
        if (itemWarmupChunkDelayMs < 500) itemWarmupChunkDelayMs = 500;
        if (itemWarmupMaxItemsPerSession < 0) itemWarmupMaxItemsPerSession = 0;
        if (itemWarmupLastScanned < 0) itemWarmupLastScanned = 0;
        if (itemWarmupLastTotal < 0) itemWarmupLastTotal = 0;
        if (itemWarmupLastSkipped < 0) itemWarmupLastSkipped = 0;
        if (pacingDefaultsVersion < PACING_DEFAULTS_VERSION) {
            if (requestCooldownMs == LEGACY_REQUEST_COOLDOWN_MS) requestCooldownMs = 5000;
            pacingDefaultsVersion = PACING_DEFAULTS_VERSION;
        }
        if (requestCooldownMs < 0) requestCooldownMs = 5000; // 0 is valid: pacing off
        if (batchWindowMs < 0) batchWindowMs = 5000; // 0 is valid: batching off
        if (batchWindowMs > 60_000) batchWindowMs = 60_000;
        if (failureBackoffMs < 0) failureBackoffMs = 10000;
        if (cacheMaxSize <= 0) cacheMaxSize = 5000;
        else if (cacheMaxSize > MAX_MEMORY_CACHE_ENTRIES) {
            cacheMaxSize = MAX_MEMORY_CACHE_ENTRIES;
        }
        if (persistentCacheMaxEntries <= 0) {
            persistentCacheMaxEntries = DEFAULT_PERSISTENT_CACHE_ENTRIES;
        } else if (persistentCacheMaxEntries > MAX_PERSISTENT_CACHE_ENTRIES) {
            persistentCacheMaxEntries = MAX_PERSISTENT_CACHE_ENTRIES;
        }
        if (churnVariantThreshold < 2) churnVariantThreshold = 4;
        if (churnWindowSeconds <= 0) churnWindowSeconds = 60;
        if (churnCooldownSeconds <= 0) churnCooldownSeconds = 300;
        if (workerThreads <= 0) workerThreads = 2;
        else if (workerThreads > MAX_WORKER_THREADS) workerThreads = MAX_WORKER_THREADS;
        return this;
    }

    /**
     * Do-not-translate list hygiene: null becomes empty, every entry is trimmed, blank
     * entries are dropped, and case-insensitive duplicates keep their FIRST spelling.
     * Always returns a new mutable list.
     */
    public static java.util.List<String> normalizedTerms(java.util.List<String> terms) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (terms == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : terms) {
            if (raw == null) continue;
            String term = raw.strip();
            if (term.isEmpty()) continue;
            if (seen.add(term.toLowerCase(java.util.Locale.ROOT))) out.add(term);
        }
        return out;
    }

    /**
     * P1.7 term-override hygiene: null becomes empty, every key is trimmed and upper-cased
     * (matching the exact case the rarity line itself is matched in), blank keys/values
     * are dropped. Always returns a new mutable map.
     */
    public static java.util.Map<String, String> normalizedTermOverrides(
            java.util.Map<String, String> raw) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (raw == null) return out;
        for (java.util.Map.Entry<String, String> entry : raw.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().strip().toUpperCase(java.util.Locale.ROOT);
            if (key.isEmpty()) continue;
            String value = entry.getValue();
            if (value == null) continue;
            String trimmedValue = value.strip();
            if (trimmedValue.isEmpty()) continue;
            out.put(key, trimmedValue);
        }
        return out;
    }

    /** Load from disk, creating a default file if none exists or it is corrupt. */
    public static TranslatorConfig load(Path path) {
        try {
            if (Files.exists(path)) {
                TranslatorConfig loaded;
                try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    loaded = fromReader(r);
                }
                // Persist normalization and one-time migration markers only after the
                // input reader is closed, so a later user choice cannot be mistaken for
                // an untouched legacy default on the next launch.
                loaded.save(path);
                String retired = loaded.takeRetiredProviderReset();
                if (retired != null) {
                    java.util.logging.Logger.getLogger("nyanlex").warning(
                            "Machine translation source '" + retired
                                    + "' is no longer supported; switched back to Google.");
                }
                return loaded;
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
