package com.dragonmeow.mctranslator.service;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.ChurnGuard;
import com.dragonmeow.mctranslator.translate.LayoutPreserver;
import com.dragonmeow.mctranslator.translate.NameMasker;
import com.dragonmeow.mctranslator.translate.TemplateText;
import com.dragonmeow.mctranslator.translate.TextFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Minecraft-free facade for all translation surfaces. It owns policy only:
 * surface mode/engine selection, source filtering, player-name masking, and final
 * display validation. Request coordination and persistence belong to
 * {@link TranslationCache}.
 */
public final class TranslationService {
    private static final int MAX_CONTEXTUAL_ITEM_RETRIES = 512;
    private final TranslatorConfig config;
    private final TranslationCache google;
    private final TranslationCache ai;
    /** Runtime language actually installed in both caches.  This is deliberately
     * independent from the mutable config object: UI code may edit the config before
     * notifying the service, but that must never make a real cache switch look like a
     * no-op. */
    private volatile String activeTargetLang;
    /** Loader-side visible-surface invalidation after an actual target-language change. */
    private volatile Runnable targetLangChangeListener = () -> { };
    private volatile boolean showOriginalOnly;
    private volatile Set<String> manualScreenSources = Set.of();
    private volatile Supplier<? extends Collection<String>> protectedNames = List::of;
    private volatile Supplier<String> itemSourceLanguage = () -> null;
    private final Set<String> invalidatedNameFailures = ConcurrentHashMap.newKeySet();
    /** At most one context-aware correction per item name and target language in a
     *  session. Prevents a stubborn model from creating a hover-triggered retry loop. */
    private final Set<String> contextualItemNameRetries = ConcurrentHashMap.newKeySet();

    public TranslationService(TranslatorConfig config, TranslationCache google,
                              TranslationCache ai) {
        this.config = config;
        this.google = google;
        this.ai = ai;
        this.activeTargetLang = normalizedTargetLang(config.targetLang);
        this.config.targetLang = this.activeTargetLang;
        if (google != null && ai != null && google != ai) {
            // One-way, failure-gated fallback. GT mode selects google directly and can
            // never reach AI; AI mode may reach google only after an actual AI failure.
            ai.setFallback(google, true);
            ai.setFallbackEnabled(() -> !config.disableGoogleFallbackForAi);
        }

        ChurnGuard guard = config.churnGuard
                ? new ChurnGuard(config.churnVariantThreshold,
                config.churnWindowSeconds * 1000L,
                config.churnCooldownSeconds * 1000L,
                System::currentTimeMillis)
                : null;
        if (google != null) google.setChurnGuard(guard);
        if (ai != null) ai.setChurnGuard(guard);
    }

    public void setProtectedNames(Supplier<? extends Collection<String>> supplier) {
        if (supplier != null) protectedNames = supplier;
    }

    /** Locale of Minecraft-provided item labels (for example ja_jp). Applied only to
     * item surfaces; chat remains language-autodetected/free-form. */
    public void setItemSourceLanguage(Supplier<String> supplier) {
        if (supplier != null) itemSourceLanguage = supplier;
    }

    /** Connect both engine queues to the live in-game batching-window setting. */
    public void setBatchWindowMs(IntSupplier supplier) {
        google.setBatchWindowMs(supplier);
        if (ai != google) ai.setBatchWindowMs(supplier);
    }

    private Collection<String> names() {
        if (!config.protectPlayerNames) return List.of();
        Collection<String> current = protectedNames.get();
        return current == null ? List.of() : current;
    }

    private TranslationCache cache(boolean useAi) {
        return useAi ? ai : google;
    }

    /**
     * Route one request according to the surface's engine switch.
     *
     * <p>GT mode is a hard GT-only path. AI mode first consults/requests AI and starts
     * GT only when the AI cache reports a real retryable failure for the same semantic
     * family. If AI recovers while GT is still in flight, the final AI value wins the
     * callback and the late GT value remains only in its own cache.</p>
     */
    private void requestByEngine(boolean useAi, String source, boolean exactStyle,
                                 Consumer<String> callback, boolean always) {
        requestByEngine(useAi, source, exactStyle, callback, always, false);
    }

    private void requestByEngine(boolean useAi, String source, boolean exactStyle,
                                 Consumer<String> callback, boolean always,
                                 boolean followAiRecovery) {
        requestByEngineDetailed(useAi, source, exactStyle,
                (value, ignoredFinality) -> callback.accept(value), always, followAiRecovery);
    }

    @FunctionalInterface
    private interface EngineResultConsumer {
        void accept(String value, boolean finalResult);
    }

    private void requestByEngineDetailed(boolean useAi, String source, boolean exactStyle,
                                         EngineResultConsumer callback, boolean always,
                                         boolean followAiRecovery) {
        if (!useAi) {
            requestCache(google, source, exactStyle,
                    value -> callback.accept(value, true), always);
            return;
        }

        AtomicBoolean finalAiDelivered = new AtomicBoolean();
        requestCache(ai, source, exactStyle, primary -> {
            String finalNow = ai.getCachedFinal(source);
            boolean styleFallbackOnly = exactStyle && finalNow != null
                    && TextFilter.isStyleFallback(finalNow);
            if (finalNow != null && !styleFallbackOnly) {
                finalAiDelivered.set(true);
                callback.accept(finalNow, true);
                return;
            }
            Consumer<String> recoveredFinal = recovered -> {
                if (recovered != null && finalAiDelivered.compareAndSet(false, true)) {
                    callback.accept(recovered, true);
                }
            };
            if (styleFallbackOnly) {
                // The semantic wording is final; only the CS projection is missing.
                // Ship the approximate-colour fallback now, then let the exact-style
                // waiter replace it when the projection lands. Delivering BEFORE
                // registering keeps the exact value last even if the projection is
                // already present at registration time.
                callback.accept(finalNow, !followAiRecovery);
                if (followAiRecovery) ai.requestCoalescedExactStyleFinal(source, recoveredFinal);
                return;
            }
            // Register the recovery waiter before any early return: a miss without a
            // recorded failure state must still be back-filled once the value lands.
            if (followAiRecovery) {
                if (exactStyle) ai.requestCoalescedExactStyleFinal(source, recoveredFinal);
                else ai.requestCoalescedFinal(source, recoveredFinal);
            }
            // Strict AI mode deliberately leaves this request on the AI cache.
            // Subsequent renders retry after the normal failure backoff, and the
            // recovery waiter above delivers a later successful AI result.
            if (config.disableGoogleFallbackForAi) {
                if (always && !finalAiDelivered.get()) callback.accept(null, !followAiRecovery);
                return;
            }
            if (!ai.mayUseFallback(source)) {
                if (always && !finalAiDelivered.get()) callback.accept(null, !followAiRecovery);
                return;
            }
            if (primary != null) {
                if (!finalAiDelivered.get()) callback.accept(primary, !followAiRecovery);
                return;
            }
            requestCache(google, source, exactStyle, lower -> {
                String recovered = ai.getCachedFinal(source);
                if (recovered != null
                        && (!exactStyle || !TextFilter.isStyleFallback(recovered))) {
                    if (finalAiDelivered.compareAndSet(false, true)) callback.accept(recovered, true);
                } else if (!ai.mayUseFallback(source)) {
                    if (always && !finalAiDelivered.get()) callback.accept(null, !followAiRecovery);
                } else if (!finalAiDelivered.get() && (lower != null || always)) {
                    callback.accept(lower, !followAiRecovery);
                }
            }, true);
        }, true);
    }

    private static void requestCache(TranslationCache cache, String source,
                                     boolean exactStyle, Consumer<String> callback,
                                     boolean always) {
        if (exactStyle) cache.requestCoalescedExactStyle(source, callback, always);
        else cache.requestCoalesced(source, callback, always);
    }

    public boolean toggleShowOriginal() {
        return showOriginalOnly = !showOriginalOnly;
    }

    public boolean isShowOriginalOnly() {
        return showOriginalOnly;
    }

    private DisplayMode visibleMode(DisplayMode configured) {
        return showOriginalOnly ? DisplayMode.ORIGINAL_ONLY : configured;
    }

    public DisplayMode chatMode() { return visibleMode(config.chatMode); }
    public DisplayMode tooltipMode() { return visibleMode(config.tooltipMode); }
    public DisplayMode heldMode() { return visibleMode(config.tooltipMode); }
    public DisplayMode scoreboardMode() { return visibleMode(config.scoreboardMode); }
    public DisplayMode nameMode() { return visibleMode(config.nameMode); }
    public DisplayMode bossBarMode() { return visibleMode(config.bossBarMode); }
    public DisplayMode titleMode() { return visibleMode(config.titleMode); }
    public DisplayMode actionBarMode() { return visibleMode(config.actionBarMode); }
    public DisplayMode bookMode() { return visibleMode(config.bookMode); }
    public DisplayMode screenTextMode() { return visibleMode(config.screenTextMode); }

    public boolean wantsScreenTextTranslation(String source) {
        return !showOriginalOnly && config.screenTextMode != DisplayMode.ORIGINAL_ONLY
                && TextFilter.shouldTranslate(source, activeTargetLang);
    }

    public boolean wantsChatTranslation(String source) {
        return !showOriginalOnly && config.chatMode != DisplayMode.ORIGINAL_ONLY
                && TextFilter.shouldTranslate(source, activeTargetLang);
    }

    public boolean wantsActionBarTranslation(String source) {
        return !showOriginalOnly && config.actionBarMode != DisplayMode.ORIGINAL_ONLY
                && TextFilter.shouldTranslate(source, activeTargetLang);
    }

    public void requestScreenTextAsync(String source, Consumer<String> onResult) {
        if (!TextFilter.shouldTranslate(source, activeTargetLang)) return;
        Consumer<String> ready = translated -> {
            if (translated != null) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, translated));
            }
        };
        requestByEngine(config.aiScreenScan, source, false, ready, false, true);
    }

    /**
     * Asynchronously completes text from the always-on custom GUI surface.
     *
     * <p>This is deliberately separate from {@link #requestScreenTextAsync(String, Consumer)}:
     * that method belongs to the manual "scan this screen" action and therefore uses
     * {@code aiScreenScan}. Live screen widgets normally use {@code aiScreenText};
     * explicitly rescanned sources use the scan engine until the next scan. The callback lets an
     * optional UI integration reflow itself as soon as the cached translation arrives.</p>
     */
    public void requestLiveScreenTextAsync(String source, Consumer<String> onResult) {
        if (!wantsScreenTextTranslation(source)) return;
        Consumer<String> ready = translated -> {
            if (translated != null) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, translated));
            }
        };
        requestByEngine(screenEngine(source), source, false, ready, false, true);
    }

    /**
     * Completes an action-bar miss immediately instead of relying only on the HUD
     * render hook. Some server/client combinations replace the overlay component
     * between render calls, so a render-only request can be lost entirely.
     */
    public void requestActionBarAsync(String source, Consumer<String> onResult) {
        if (!wantsActionBarTranslation(source)) return;
        NameMasker.Masked masked = NameMasker.mask(source, names());
        requestByEngine(config.aiActionBar, masked.text(), false, translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            if (restored != null) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, restored));
            }
        }, false, true);
    }

    public void translateChatSegmentsAsync(List<String> texts, Consumer<List<String>> onAll) {
        if (showOriginalOnly || config.chatMode == DisplayMode.ORIGINAL_ONLY) {
            onAll.accept(new ArrayList<>(texts));
            return;
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (TextFilter.shouldTranslate(texts.get(i), activeTargetLang)) indexes.add(i);
        }
        if (indexes.isEmpty()) {
            onAll.accept(new ArrayList<>(texts));
            return;
        }

        String[] output = texts.toArray(String[]::new);
        AtomicInteger remaining = new AtomicInteger(indexes.size());
        Collection<String> protectedNow = names();
        for (int index : indexes) {
            String original = texts.get(index);
            NameMasker.Masked masked = NameMasker.mask(original, protectedNow);
            requestByEngine(config.aiChat, masked.text(), false, translated -> {
                String restored = NameMasker.unmask(translated, masked.names());
                if (meaningful(original, restored)) {
                    output[index] = LayoutPreserver.matchOuterWhitespace(original, restored);
                }
                if (remaining.decrementAndGet() == 0) onAll.accept(List.of(output.clone()));
            }, true);
        }
    }

    public void requestChatAsync(String source, Consumer<String> onTranslated) {
        if (!wantsChatTranslation(source)) return;
        NameMasker.Masked masked = NameMasker.mask(source, names());
        requestByEngine(config.aiChat, masked.text(), false, translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            if (meaningful(source, restored)) onTranslated.accept(restored);
        }, false);
    }

    public static final class ChatTranslationResult {
        private final String text;
        private final boolean finalResult;

        private ChatTranslationResult(String text, boolean finalResult) {
            this.text = text;
            this.finalResult = finalResult;
        }

        public String text() { return text; }
        public boolean finalResult() { return finalResult; }
    }

    public void translateChatAsync(String content, Consumer<String> onResult) {
        translateChatAsyncDetailed(content, result -> onResult.accept(result.text()));
    }

    /** Chat-only callback that distinguishes a provisional row from its final recovery. */
    public void translateChatAsyncDetailed(String content,
                                           Consumer<ChatTranslationResult> onResult) {
        if (!wantsChatTranslation(content)) {
            onResult.accept(new ChatTranslationResult(null, true));
            return;
        }
        NameMasker.Masked masked = NameMasker.mask(content, names());
        // Chat is inserted once and is not re-rendered after a background cache update.
        // For CS-marked rich text, wait for the exact semantic style projection instead
        // of permanently displaying the marker-free fallback with guessed colours.
        requestByEngineDetailed(config.aiChat, masked.text(), true, (translated, finalResult) -> {
            // Strip the style-fallback prefix before unmask/layout: NameMasker and
            // LayoutPreserver treat the NUL prefix as content, so outer whitespace
            // would land BEFORE the prefix and break startsWith detection downstream.
            boolean styleFallback = TextFilter.isStyleFallback(translated);
            String semantic = TextFilter.stripStyleFallback(translated);
            String restored = NameMasker.unmask(semantic, masked.names());
            if (!meaningful(content, restored)) {
                onResult.accept(new ChatTranslationResult(null, finalResult));
                return;
            }
            String laidOut = LayoutPreserver.matchOuterWhitespace(content, restored);
            onResult.accept(new ChatTranslationResult(
                    styleFallback ? TextFilter.markStyleFallback(laidOut) : laidOut,
                    finalResult));
        }, true, true);
    }

    public void clearTranslations() {
        google.clear();
        ai.clear();
        contextualItemNameRetries.clear();
        invalidatedNameFailures.clear();
    }

    public String targetLang() { return activeTargetLang; }

    public synchronized com.dragonmeow.mctranslator.translate.TranslationFile exportTranslations() {
        return new com.dragonmeow.mctranslator.translate.TranslationFile("modern-template-v1",
                activeTargetLang, com.dragonmeow.mctranslator.config.MachineTranslationProvider.normalize(
                        config.machineTranslationProvider), google.exportTranslations(), ai.exportTranslations());
    }

    public synchronized int importTranslations(com.dragonmeow.mctranslator.translate.TranslationFile file)
            throws java.io.IOException {
        file.requireCompatible("modern-template-v1", activeTargetLang,
                com.dragonmeow.mctranslator.config.MachineTranslationProvider.normalize(config.machineTranslationProvider));
        return google.importTranslations(file.machine) + ai.importTranslations(file.ai);
    }

    public void setTargetLangChangeListener(Runnable listener) {
        targetLangChangeListener = listener == null ? () -> { } : listener;
    }

    /** Apply a live machine-provider selection while preserving every provider's disk rows. */
    public synchronized void reloadMachineProvider() {
        google.reloadProviderPartition();
        contextualItemNameRetries.clear();
        invalidatedNameFailures.clear();
        try { targetLangChangeListener.run(); }
        catch (RuntimeException ignored) { }
    }

    public synchronized void setTargetLang(String language) {
        if (language == null) return;
        String next = normalizedTargetLang(language);
        if (next.equals(activeTargetLang)) {
            config.targetLang = next;
            return;
        }
        // Both generations are invalidated before either cache switches the shared
        // namespaced failure store, so an old-language worker cannot write into the
        // new language partition during the hand-off.
        google.beginTargetLangChange();
        if (ai != google) ai.beginTargetLangChange();
        google.completeTargetLangChange(next);
        if (ai != google) ai.completeTargetLangChange(next);
        activeTargetLang = next;
        config.targetLang = next;
        contextualItemNameRetries.clear();
        invalidatedNameFailures.clear();
        try {
            targetLangChangeListener.run();
        } catch (RuntimeException ignored) {
            // A loader-specific repaint hook must never leave the service half-switched.
        }
    }

    private static String normalizedTargetLang(String language) {
        return language == null || language.isBlank() ? "zh-TW" : language.strip();
    }

    private boolean shouldTranslateItem(String source) {
        String hint;
        try {
            hint = itemSourceLanguage.get();
        } catch (RuntimeException ignored) {
            hint = null;
        }
        return TextFilter.shouldTranslate(source, activeTargetLang, hint);
    }

    public void flushBatches() {
        google.flushBatch();
        ai.flushBatch();
    }

    public int translatedCount() { return google.size() + ai.size(); }
    public int pendingCount() { return google.pendingCount() + ai.pendingCount(); }
    public void retranslate(List<String> sources) {
        invalidateSources(sources);
        warmTooltipBatch(sources);
    }

    /** Explicit rescan: discard stale/failed rows, then queue the original screen inputs. */
    public void retranslateScreen(List<String> sources) {
        manualScreenSources = Set.copyOf(sources);
        invalidateSources(sources);
        warmMasked(sources, true, config.screenTextMode, config.aiScreenScan);
    }

    private boolean screenEngine(String source) {
        return manualScreenSources.contains(source) ? config.aiScreenScan : config.aiScreenText;
    }

    private void invalidateSources(List<String> sources) {
        Collection<String> protectedNow = names();
        for (String source : sources) {
            invalidateBoth(source);
            String masked = NameMasker.mask(source, protectedNow).text();
            if (!masked.equals(source)) invalidateBoth(masked);
        }
    }

    private void invalidateBoth(String source) {
        google.invalidate(source);
        ai.invalidate(source);
    }

    public TranslationDecision translateChat(String text) {
        return lookup(text, config.chatMode, config.aiChat);
    }
    public TranslationDecision translateItemLine(String text) {
        TranslationDecision d = lookup(text, config.tooltipMode, config.aiTooltip, true, true);
        if (!d.changed()) return d;
        // Display-only tooltip clean-up: preserved wide column padding looks like a hole
        // after the much narrower CJK translation. Applied AFTER the cache lookup, so the
        // stored translation (and its retokenised template) stays untouched; scoreboard /
        // boss bar / chat / book surfaces never pass through here.
        String tightened = TemplateText.collapseTranslatedColumnGaps(d.translated());
        return tightened.equals(d.translated()) ? d
                : TranslationDecision.of(d.mode(), d.original(), tightened);
    }
    public TranslationDecision translateHeld(String text) {
        return lookup(text, config.tooltipMode, config.aiTooltip, false, true);
    }
    public TranslationDecision translateScoreboardLine(String text) {
        return lookup(text, config.scoreboardMode, config.aiScoreboard, true);
    }
    public TranslationDecision translateUi(String text) {
        return lookup(text, config.nameMode, config.aiName);
    }
    public TranslationDecision translateBossBar(String text) {
        return lookup(text, config.bossBarMode, config.aiBossBar);
    }
    public TranslationDecision translateTitle(String text) {
        return lookup(text, config.titleMode, config.aiTitle);
    }
    public TranslationDecision translateActionBar(String text) {
        return lookup(text, config.actionBarMode, config.aiActionBar);
    }
    public TranslationDecision translateBook(String text) {
        return lookup(text, config.bookMode, config.aiBook, true);
    }
    public TranslationDecision translateScreenText(String text) {
        return lookup(text, config.screenTextMode, screenEngine(text), true);
    }
    public TranslationDecision translateScreenScanText(String text) {
        return lookup(text, config.screenTextMode, config.aiScreenScan, true);
    }

    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi) {
        return lookup(original, mode, useAi, false);
    }

    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi,
                                       boolean requireFinalAi) {
        return lookup(original, mode, useAi, requireFinalAi, false);
    }

    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi,
                                       boolean requireFinalAi, boolean itemText) {
        if (showOriginalOnly || mode == DisplayMode.ORIGINAL_ONLY
                || !(itemText ? shouldTranslateItem(original)
                : TextFilter.shouldTranslate(original, activeTargetLang))) {
            return TranslationDecision.unchanged(original);
        }

        NameMasker.Masked masked = NameMasker.mask(original, names());
        if (masked.hasMasks() && !(itemText ? shouldTranslateItem(masked.text())
                : TextFilter.shouldTranslate(masked.text(), activeTargetLang))) {
            return TranslationDecision.unchanged(original);
        }

        TranslationCache selected = cache(useAi);
        String translated = selected.getCached(masked.text());
        if (translated == null) {
            if (itemText) selected.requestBatchedPassive(masked.text());
            else selected.requestBatched(masked.text());
            return TranslationDecision.unchanged(original);
        }
        return decide(original, NameMasker.unmask(translated, masked.names()), mode,
                masked.hasMasks());
    }

    public boolean warmUp(String source) {
        if (config.tooltipMode == DisplayMode.ORIGINAL_ONLY
                || !shouldTranslateItem(source)) return true;
        TranslationCache selected = cache(config.aiTooltip);
        return selected.getCached(source) != null || selected.translateBlocking(source) != null;
    }

    public void warmTooltipBatch(List<String> sources) {
        warmMasked(sources, true, config.tooltipMode, config.aiTooltip, true, true);
    }

    /** Warm every blank-line/indent-delimited paragraph on the current book page in one
     * context-aware request while keeping each paragraph as one translation unit. */
    public void warmBookBatch(List<String> sources) {
        warmMasked(sources, true, config.bookMode, config.aiBook);
    }

    /** Warm stable per-row scoreboard keys together. The complete sidebar remains AI
     * context, while optional/animated neighbouring rows cannot change a label's cache
     * identity or make its wording flicker. */
    public void warmScoreboardBatch(List<String> sources) {
        warmMasked(sources, true, config.scoreboardMode, config.aiScoreboard);
    }

    /** Whether a tooltip translation unit has reached a terminal cache state.  Used by
     * the renderer to commit a blank-line-delimited paragraph atomically: a cached
     * translation and a durable keep-original decision are both ready, while a miss is
     * not. This lookup never creates a new ordinary request; {@link #warmTooltipBatch}
     * owns submission for the complete visible tooltip. */
    public boolean isTooltipTranslationReady(String source) {
        if (source == null || config.tooltipMode == DisplayMode.ORIGINAL_ONLY
                || !shouldTranslateItem(source)) return true;
        NameMasker.Masked masked = NameMasker.mask(source, names());
        if (masked.hasMasks() && !shouldTranslateItem(masked.text())) {
            return true;
        }
        TranslationCache selected = cache(config.aiTooltip);
        return selected.getCached(masked.text()) != null;
    }

    public void warmNamesBatch(List<String> sources) {
        warmMasked(sources, false, config.tooltipMode, config.aiTooltip, false, true);
    }

    /**
     * Correct an isolated AI item-name translation when a context-rich tooltip title
     * translated the same name differently. For example, an isolated
     * "Aspect of the End" must not remain "末影之視" while the title line is
     * "終界之刃 傷害…". It first subtracts a separately translated, reusable suffix
     * ("傷害…") from the contextual title and stores the remaining name as authoritative.
     * Only if that deterministic extraction is impossible does it retry the name once
     * with the complete tooltip as reference context.
     */
    public void reconcileItemNameWithTooltip(String itemName, List<String> tooltipSources) {
        if (!config.aiTooltip || itemName == null || itemName.isBlank()
                || tooltipSources == null || tooltipSources.isEmpty()) return;

        Collection<String> protectedNow = names();
        NameMasker.Masked maskedName = NameMasker.mask(itemName, protectedNow);
        if (maskedName.hasMasks()) return;
        TranslationCache selected = cache(true);
        String nameTranslation = selected.getCachedFinal(maskedName.text());
        if (nameTranslation == null || nameTranslation.isBlank()) return;

        List<String> context = new ArrayList<>(tooltipSources.size());
        String mismatchSource = null;
        String mismatchTranslation = null;
        for (String source : tooltipSources) {
            if (source == null) continue;
            String maskedSource = NameMasker.mask(source, protectedNow).text();
            context.add(maskedSource);
            String comparableSource = TextFilter.stripFormatting(source);
            if (!startsWithItemName(comparableSource, itemName)) continue;
            String lineTranslation = selected.getCachedFinal(maskedSource);
            String comparableTranslation = TextFilter.stripFormatting(lineTranslation);
            if (comparableTranslation != null
                    && !sameTranslatedNamePrefix(comparableTranslation,
                    TextFilter.stripFormatting(nameTranslation))) {
                mismatchSource = comparableSource;
                mismatchTranslation = comparableTranslation;
                break;
            }
        }
        if (mismatchSource == null) return;

        String retryKey = activeTargetLang + '\0' + maskedName.text();
        String suffix = mismatchSource.substring(itemName.length());
        String suffixTranslation = selected.getCachedFinal(suffix);
        if (suffixTranslation == null) {
            // Usually a shared template such as "Damage: [n]"; one short request can
            // reconcile the names of every weapon using the same suffix.
            selected.requestBatched(suffix);
            return;
        }
        String authoritative = contextualPrefix(mismatchTranslation, suffixTranslation);
        if (authoritative != null) {
            // Remove a possible GT fallback copy, then atomically replace the AI key.
            google.invalidate(maskedName.text());
            if (selected.replaceFinal(maskedName.text(), authoritative)) {
                rememberContextualItemRetry(retryKey);
                return;
            }
        }

        if (!rememberContextualItemRetry(retryKey)) return;
        // Clear both tiers so the AI request cannot be short-circuited by a GT fallback
        // copy of the same isolated name.
        invalidateBoth(maskedName.text());
        selected.warmBatchAsync(List.of(maskedName.text()), context);
    }

    private boolean rememberContextualItemRetry(String key) {
        synchronized (contextualItemNameRetries) {
            if (contextualItemNameRetries.contains(key)) return false;
            if (contextualItemNameRetries.size() >= MAX_CONTEXTUAL_ITEM_RETRIES) {
                var oldest = contextualItemNameRetries.iterator();
                if (oldest.hasNext()) contextualItemNameRetries.remove(oldest.next());
            }
            return contextualItemNameRetries.add(key);
        }
    }

    private static String contextualPrefix(String wholeTranslation, String suffixTranslation) {
        String whole = wholeTranslation == null ? "" : wholeTranslation.strip();
        String suffix = suffixTranslation == null ? "" : suffixTranslation.strip();
        if (whole.isEmpty() || suffix.isEmpty() || !whole.endsWith(suffix)) return null;
        String prefix = whole.substring(0, whole.length() - suffix.length()).strip();
        if (prefix.isEmpty() || TextFilter.isLikelyMojibake(prefix)) return null;
        return prefix;
    }

    private static boolean startsWithItemName(String line, String itemName) {
        if (!line.startsWith(itemName) || line.length() <= itemName.length()) return false;
        int next = line.codePointAt(itemName.length());
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    private static boolean sameTranslatedNamePrefix(String lineTranslation,
                                                    String nameTranslation) {
        String line = lineTranslation.stripLeading();
        String name = nameTranslation.strip();
        if (name.isEmpty() || !line.startsWith(name)) return false;
        if (line.length() == name.length()) return true;
        int next = line.codePointAt(name.length());
        return !Character.isLetterOrDigit(next) && next != '_';
    }

    private void warmMasked(List<String> sources, boolean includeContext,
                            DisplayMode mode, boolean useAi) {
        warmMasked(sources, includeContext, mode, useAi, false);
    }

    private void warmMasked(List<String> sources, boolean includeContext,
                            DisplayMode mode, boolean useAi, boolean highPriority) {
        warmMasked(sources, includeContext, mode, useAi, highPriority, false);
    }

    private void warmMasked(List<String> sources, boolean includeContext,
                            DisplayMode mode, boolean useAi, boolean highPriority,
                            boolean itemText) {
        if (mode == DisplayMode.ORIGINAL_ONLY || sources == null) return;
        Collection<String> protectedNow = names();
        List<String> todo = new ArrayList<>();
        List<String> context = includeContext ? new ArrayList<>() : null;
        for (String source : sources) {
            if (source == null) continue;
            String masked = NameMasker.mask(source, protectedNow).text();
            if (context != null) context.add(masked);
            if (itemText ? shouldTranslateItem(masked)
                    : TextFilter.shouldTranslate(masked, activeTargetLang)) todo.add(masked);
        }
        if (!todo.isEmpty()) {
            TranslationCache selected = cache(useAi);
            if (highPriority) selected.warmBatchAsyncHigh(todo, context);
            else selected.warmBatchAsync(todo, context);
        }
    }

    private TranslationDecision decide(String original, String translated, DisplayMode mode,
                                       boolean hadProtectedNames) {
        boolean styleFallback = TextFilter.isStyleFallback(translated);
        String semantic = TextFilter.stripStyleFallback(translated);
        if (!meaningful(original, semantic)
                || TextFilter.isPartialTransliteration(original, semantic)) {
            return TranslationDecision.unchanged(original);
        }
        if (hadProtectedNames && !protectedNamesSurvive(original, semantic)) {
            invalidateMangledOnce(original);
            return TranslationDecision.unchanged(original);
        }
        String laidOut = LayoutPreserver.matchOuterWhitespace(original, semantic);
        return TranslationDecision.of(mode, original,
                styleFallback ? TextFilter.markStyleFallback(laidOut) : laidOut);
    }

    private static boolean meaningful(String source, String translated) {
        return translated != null && !translated.isEmpty()
                && !translated.equals(source)
                && !translated.trim().equals(source == null ? "" : source.trim());
    }

    private boolean protectedNamesSurvive(String original, String translated) {
        Collection<String> current = names();
        if (current.isEmpty()) return true;
        Set<?> protectedSet = current instanceof Set<?> set ? set : new HashSet<>(current);
        for (int i = 0; i < original.length(); ) {
            if (!nameCharacter(original.charAt(i))) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < original.length() && nameCharacter(original.charAt(end))) end++;
            String token = original.substring(i, end);
            if (protectedSet.contains(token) && !containsWholeName(translated, token)) return false;
            i = end;
        }
        return true;
    }

    private static boolean containsWholeName(String text, String name) {
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
            int end = at + name.length();
            if ((at == 0 || !nameCharacter(text.charAt(at - 1)))
                    && (end == text.length() || !nameCharacter(text.charAt(end)))) return true;
        }
        return false;
    }

    private static boolean nameCharacter(char c) {
        return c == '_' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                || c >= '0' && c <= '9';
    }

    private void invalidateMangledOnce(String original) {
        if (invalidatedNameFailures.size() >= 512) invalidatedNameFailures.clear();
        if (!invalidatedNameFailures.add(original)) return;
        invalidateBoth(original);
        String masked = NameMasker.mask(original, names()).text();
        if (!masked.equals(original)) invalidateBoth(masked);
    }
}
