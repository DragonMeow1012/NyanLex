package com.dragonmeow.mctranslator.service;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.LayoutPreserver;
import com.dragonmeow.mctranslator.translate.NameMasker;
import com.dragonmeow.mctranslator.translate.TextFilter;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Top-level orchestrator. Each surface (chat / tooltip / held item / scoreboard /
 * name tags) has its own {@link DisplayMode} (原文 / 原文＋翻譯 / 只有翻譯), set via
 * the in-game 翻譯設定 screen. Minecraft-free so it is unit-testable with inline mocks.
 */
public final class TranslationService {

    private final TranslatorConfig config;
    private final TranslationCache cache;     // 機翻 (Google) keyspace
    private final TranslationCache aiCache;   // AI 精翻 keyspace (separate, so the two engines never collide)

    // Master 原文/翻譯 override (the quick-toggle hotkey, default G). When true, EVERY auto-translated
    // surface falls back to its original text; the configured per-surface modes resume when toggled off.
    private volatile boolean forceShowOriginalOnly = false;

    // Online player names supplied by the loader glue. When protectPlayerNames is on, chat text
    // is masked with NameMasker before it leaves the client (names come back verbatim), and any
    // surface whose text IS exactly a player name (name tags, scoreboards) is left untranslated.
    private volatile Supplier<? extends Collection<String>> protectedNames = List::of;

    public void setProtectedNames(Supplier<? extends Collection<String>> namesSupplier) {
        if (namesSupplier != null) this.protectedNames = namesSupplier;
    }

    private Collection<String> protectedNamesNow() {
        if (!config.protectPlayerNames) return List.of();
        Collection<String> names = protectedNames.get();
        return names == null ? List.of() : names;
    }


    public TranslationService(TranslatorConfig config, TranslationCache cache, TranslationCache aiCache) {
        this.config = config;
        this.cache = cache;
        this.aiCache = aiCache;
        // A string already fine-translated by AI is at least as good as a fresh Google
        // result: let the Google cache reuse it instead of buying it again. One-way only.
        if (cache != null && aiCache != null && cache != aiCache) {
            cache.setFallback(aiCache);
        }
    }

    private TranslationCache cacheFor(boolean useAi) {
        return useAi ? aiCache : cache;
    }

    /** Diagnostic self-test: backend + config status. */
    public String selfTest() {
        String cfg = "chat=" + config.chatMode + " tooltip=" + config.tooltipMode + " target=" + config.targetLang;
        try {
            return "OK [" + cfg + "] Hello world -> " + cache.testTranslate("Hello world");
        } catch (Exception e) {
            return "FAIL [" + cfg + "] " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Toggle the master 原文/翻譯 switch (backs the quick-toggle hotkey); returns the NEW state
     * ({@code true} = now showing originals everywhere). The glue should clear its render memo
     * afterwards so persistent surfaces (tooltips / HUD / name tags / book / screens) flip at once.
     */
    public boolean toggleShowOriginal() {
        forceShowOriginalOnly = !forceShowOriginalOnly;
        return forceShowOriginalOnly;
    }

    /** Whether the master switch is currently forcing originals on every surface. */
    public boolean isShowOriginalOnly() {
        return forceShowOriginalOnly;
    }

    // ---- per-surface mode accessors (used by the glue + config screen) ----
    public DisplayMode chatMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.chatMode;
    }

    public DisplayMode tooltipMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.tooltipMode;
    }

    public DisplayMode heldMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.heldMode;
    }

    public DisplayMode scoreboardMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.scoreboardMode;
    }

    public DisplayMode nameMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.nameMode;
    }

    public DisplayMode bossBarMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.bossBarMode;
    }

    public DisplayMode titleMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.titleMode;
    }

    public DisplayMode actionBarMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.actionBarMode;
    }

    public DisplayMode bookMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.bookMode;
    }

    public DisplayMode screenTextMode() {
        return forceShowOriginalOnly ? DisplayMode.ORIGINAL_ONLY : config.screenTextMode;
    }

    /** Whether a chat line is eligible for translation right now (chat surface on + worth translating). */
    public boolean wantsChatTranslation(String source) {
        return !forceShowOriginalOnly
                && config.chatMode != DisplayMode.ORIGINAL_ONLY
                && TextFilter.shouldTranslate(source, config.targetLang);
    }

    /**
     * Manual one-shot used by the "translate current screen" hotkey: translate arbitrary
     * UI text (e.g. quest-book buttons) async, ignoring the per-surface on/off toggles
     * (it is an explicit user action). Uses the tooltip engine (機翻 / AI per
     * {@code aiTooltip}); the callback fires only on a real translation, off-thread.
     */
    public void requestScreenTextAsync(String source, Consumer<String> onResult) {
        if (!TextFilter.shouldTranslate(source, config.targetLang)) return;
        // The hotkey fires once per visible string: coalesce the whole screen into
        // the next tick's single batched request instead of one request per string.
        cacheFor(config.aiScreenScan).requestCoalesced(source, translated -> {
            if (translated != null && !translated.equals(source)
                    && !translated.trim().equals(source.trim())) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, translated));
            }
        }, false);
    }

    /**
     * Translate the colour runs of a multi-colour chat line independently (one batched
     * request) and deliver the per-run translations aligned to {@code texts}. A run that
     * is not worth translating (numbers / punctuation / spaces) or that fails comes back
     * unchanged, keeping its colour. The callback runs off-thread.
     */
    public void translateChatSegmentsAsync(java.util.List<String> texts, Consumer<java.util.List<String>> onAll) {
        if (forceShowOriginalOnly) {
            onAll.accept(new java.util.ArrayList<>(texts));
            return;
        }
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (TextFilter.shouldTranslate(texts.get(i), config.targetLang)) {
                idx.add(i);
            }
        }
        if (idx.isEmpty()) {
            onAll.accept(new java.util.ArrayList<>(texts));
            return;
        }
        // Each segment joins the shared per-tick batch (so several chat lines arriving
        // in the same tick cost ONE request); the callback assembles the line once the
        // last segment reports in. The last decrement's happens-before edge makes all
        // out[] writes visible to the assembling thread.
        String[] out = texts.toArray(new String[0]);
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(idx.size());
        TranslationCache c = cacheFor(config.aiChat);
        Collection<String> names = protectedNamesNow();
        for (int i : idx) {
            String src = texts.get(i);
            NameMasker.Masked masked = NameMasker.mask(src, names);
            c.requestCoalesced(masked.text(), tr -> {
                String restored = NameMasker.unmask(tr, masked.names());
                if (restored != null && !restored.isEmpty() && !restored.trim().equals(src.trim())) {
                    out[i] = LayoutPreserver.matchOuterWhitespace(src, restored);
                }
                if (remaining.decrementAndGet() == 0) {
                    onAll.accept(java.util.Arrays.asList(out.clone()));
                }
            }, true);
        }
    }

    /** Live-chat path: coalesced warm + callback (immediately if cached). Callback runs off-thread. */
    public void requestChatAsync(String source, Consumer<String> onTranslated) {
        if (forceShowOriginalOnly || !TextFilter.shouldTranslate(source, config.targetLang)) return;
        NameMasker.Masked masked = NameMasker.mask(source, protectedNamesNow());
        cacheFor(config.aiChat).requestCoalesced(masked.text(), translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            if (restored != null && !restored.equals(source)) {
                onTranslated.accept(restored);
            }
        }, false);
    }

    /**
     * Chat path for the loader glue: translate {@code content} off-thread and invoke
     * {@code onResult} exactly once — with the translation, or {@code null} if it
     * should not / could not be translated. Never blocks; never drops the callback,
     * so a caller that hid the original (只有翻譯) can safely restore it on {@code null}.
     */
    public void translateChatAsync(String content, Consumer<String> onResult) {
        if (config.chatMode == DisplayMode.ORIGINAL_ONLY
                || !TextFilter.shouldTranslate(content, config.targetLang)) {
            onResult.accept(null);
            return;
        }
        // Mask player names so they never leave the client and come back verbatim
        // (also makes "<Bob> gg" and "<Alice> gg" share one cache entry).
        NameMasker.Masked masked = NameMasker.mask(content, protectedNamesNow());
        cacheFor(config.aiChat).requestCoalesced(masked.text(), translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            if (restored == null || restored.isEmpty()
                    || restored.equals(content) || restored.trim().equals(content.trim())) {
                onResult.accept(null);
            } else {
                // Keep the original line's indentation/centering on the translation.
                onResult.accept(LayoutPreserver.matchOuterWhitespace(content, restored));
            }
        }, true);
    }

    /** Wipe all cached translations (both engines) so everything is re-fetched. */
    public void clearTranslations() {
        cache.clear();
        aiCache.clear();
    }

    /** Current target language (e.g. {@code zh-TW} / {@code zh-CN}). */
    public String targetLang() {
        return config.targetLang;
    }

    /**
     * Switch the translation output language at runtime (繁體 {@code zh-TW} ↔ 簡體
     * {@code zh-CN}): retarget both engine caches and wipe them so every surface is
     * re-translated into the new language. No-op if the language is unchanged.
     */
    public void setTargetLang(String lang) {
        if (lang == null || lang.equals(config.targetLang)) return;
        config.targetLang = lang;
        cache.setTargetLang(lang);
        aiCache.setTargetLang(lang);
        clearTranslations();
    }

    /** Flush both engines' coalesced per-frame request buffers (call once per client tick). */
    public void flushBatches() {
        cache.flushBatch();
        aiCache.flushBatch();
    }

    /** Total cached translations across both engines (for the settings-screen progress display). */
    public int translatedCount() {
        return cache.size() + aiCache.size();
    }

    /** Translations currently in flight across both engines (queued / being fetched). */
    public int pendingCount() {
        return cache.pendingCount() + aiCache.pendingCount();
    }

    /** Wipe only the AI cache (used when AI model/keys change). */
    public void clearAiTranslations() {
        aiCache.clear();
    }

    /**
     * Re-translate a specific set of lines (e.g. one item's tooltip): evict them from
     * both engine caches, then re-warm via the tooltip engine. Backs the "re-translate
     * pointed item" hotkey.
     */
    public void retranslate(List<String> sources) {
        for (String s : sources) {
            cache.invalidate(s);
            aiCache.invalidate(s);
        }
        warmTooltipBatch(sources);
    }

    /** Chat path: non-blocking cache-or-warm using the chat surface mode. */
    public TranslationDecision translateChat(String original) {
        return lookup(original, config.chatMode, config.aiChat);
    }

    public TranslationDecision translateItemLine(String original) {
        return lookup(original, config.tooltipMode, config.aiTooltip);
    }

    public TranslationDecision translateHeld(String original) {
        return lookup(original, config.heldMode, config.aiHeld);
    }

    public TranslationDecision translateScoreboardLine(String original) {
        return lookup(original, config.scoreboardMode, config.aiScoreboard);
    }

    public TranslationDecision translateUi(String original) {
        return lookup(original, config.nameMode, config.aiName);
    }

    public TranslationDecision translateBossBar(String original) {
        return lookup(original, config.bossBarMode, config.aiBossBar);
    }

    public TranslationDecision translateTitle(String original) {
        return lookup(original, config.titleMode, config.aiTitle);
    }

    public TranslationDecision translateActionBar(String original) {
        return lookup(original, config.actionBarMode, config.aiActionBar);
    }

    /** Book / lectern page text (per-frame, non-blocking cache-or-warm). */
    public TranslationDecision translateBook(String original) {
        return lookup(original, config.bookMode, config.aiBook);
    }

    /** Custom-GUI text (per-draw, non-blocking) — e.g. shader-pack / mod settings screens. */
    public TranslationDecision translateScreenText(String original) {
        return lookup(original, config.screenTextMode, config.aiScreenText);
    }

    /** Non-blocking cache-only lookup for the per-frame render surfaces. */
    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi) {
        if (forceShowOriginalOnly) return TranslationDecision.unchanged(original);
        if (mode == DisplayMode.ORIGINAL_ONLY) return TranslationDecision.unchanged(original);
        if (!TextFilter.shouldTranslate(original, config.targetLang)) return TranslationDecision.unchanged(original);
        // 1.0.0-style: EVERYTHING translates (ground items, NPC names, holograms) — but
        // player names are masked out first and substituted back verbatim afterwards,
        // so they never reach the backend and never come back mangled.
        NameMasker.Masked masked = NameMasker.mask(original, protectedNamesNow());
        String source = masked.text();
        if (masked.hasMasks() && !TextFilter.shouldTranslate(source, config.targetLang)) {
            return TranslationDecision.unchanged(original); // nothing left but the name itself
        }
        TranslationCache c = cacheFor(useAi);
        String translated = c.getCached(source);
        if (translated == null) {
            // Coalesce per-frame render-surface misses into one batched request per tick
            // (cuts request count hugely vs one request per string — matters for AI endpoints).
            c.requestBatched(source);
            return TranslationDecision.unchanged(original);
        }
        return decide(original, NameMasker.unmask(translated, masked.names()), mode);
    }

    /** Whether the item warm-up should run (any item surface is on). */
    private boolean itemSurfacesActive() {
        return config.tooltipMode != DisplayMode.ORIGINAL_ONLY || config.heldMode != DisplayMode.ORIGINAL_ONLY;
    }

    public boolean warmUp(String source) {
        if (!itemSurfacesActive()) return true;
        if (!TextFilter.shouldTranslate(source, config.targetLang)) return true;
        if (cache.getCached(source) != null) return true;
        return cache.translateBlocking(source) != null;
    }

    /**
     * Warm a whole tooltip's lines together in one background batch — so the AI
     * backend translates them with shared context (coherent across the tooltip),
     * and even the Google backend issues one request instead of N. Non-blocking;
     * the per-line render then picks up the cached results.
     */
    public void warmTooltipBatch(List<String> sources) {
        if (config.tooltipMode == DisplayMode.ORIGINAL_ONLY) return;
        List<String> todo = new java.util.ArrayList<>();
        for (String s : sources) {
            if (s != null && TextFilter.shouldTranslate(s, config.targetLang)) todo.add(s);
        }
        if (!todo.isEmpty()) cacheFor(config.aiTooltip).warmBatchAsync(todo);
    }

    public boolean warmUpBatch(List<String> sources) {
        if (!itemSurfacesActive()) return true;
        List<String> todo = new java.util.ArrayList<>();
        for (String s : sources) {
            if (TextFilter.shouldTranslate(s, config.targetLang)) todo.add(s);
        }
        return todo.isEmpty() || cache.warmBatch(todo);
    }

    private TranslationDecision decide(String original, String translated, DisplayMode mode) {
        // Filter no-op translations: identical, or differing only in surrounding whitespace.
        if (translated == null || translated.isEmpty()
                || translated.equals(original) || translated.trim().equals(original.trim())) {
            return TranslationDecision.unchanged(original);
        }
        // Keep the original line's indentation/centering on the translation.
        return TranslationDecision.of(mode, original, LayoutPreserver.matchOuterWhitespace(original, translated));
    }
}
