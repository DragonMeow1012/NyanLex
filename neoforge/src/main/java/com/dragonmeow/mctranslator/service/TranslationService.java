package com.dragonmeow.mctranslator.service;

import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.ChurnGuard;
import com.dragonmeow.mctranslator.translate.LayoutPreserver;
import com.dragonmeow.mctranslator.translate.NameMasker;
import com.dragonmeow.mctranslator.translate.TextFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Minecraft-free facade for all translation surfaces. It owns policy only:
 * surface mode/engine selection, source filtering, player-name masking, and final
 * display validation. Request coordination and persistence belong to
 * {@link TranslationCache}.
 */
public final class TranslationService {
    private final TranslatorConfig config;
    private final TranslationCache google;
    private final TranslationCache ai;
    private volatile boolean showOriginalOnly;
    private volatile Supplier<? extends Collection<String>> protectedNames = List::of;
    private final Set<String> invalidatedNameFailures = ConcurrentHashMap.newKeySet();
    /** At most one context-aware correction per item name and target language in a
     *  session. Prevents a stubborn model from creating a hover-triggered retry loop. */
    private final Set<String> contextualItemNameRetries = ConcurrentHashMap.newKeySet();

    public TranslationService(TranslatorConfig config, TranslationCache google,
                              TranslationCache ai) {
        this.config = config;
        this.google = google;
        this.ai = ai;
        if (google != null && ai != null && google != ai) {
            // AI is the preferred tier. A GT hit remains immediately displayable but
            // becomes provisional in the AI cache and schedules an AI supplement.
            ai.setFallback(google, true);
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

    private Collection<String> names() {
        if (!config.protectPlayerNames) return List.of();
        Collection<String> current = protectedNames.get();
        return current == null ? List.of() : current;
    }

    private TranslationCache cache(boolean useAi) {
        return useAi ? ai : google;
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
                && TextFilter.shouldTranslate(source, config.targetLang);
    }

    public boolean wantsChatTranslation(String source) {
        return !showOriginalOnly && config.chatMode != DisplayMode.ORIGINAL_ONLY
                && TextFilter.shouldTranslate(source, config.targetLang);
    }

    public boolean wantsActionBarTranslation(String source) {
        return !showOriginalOnly && config.actionBarMode != DisplayMode.ORIGINAL_ONLY
                && TextFilter.shouldTranslate(source, config.targetLang);
    }

    public void requestScreenTextAsync(String source, Consumer<String> onResult) {
        if (!TextFilter.shouldTranslate(source, config.targetLang)) return;
        TranslationCache selected = cache(config.aiScreenScan);
        Consumer<String> ready = translated -> {
            if (meaningful(source, translated)) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, translated));
            }
        };
        if (config.aiScreenScan) selected.requestCoalescedFinal(source, ready);
        else selected.requestCoalesced(source, ready, false);
    }

    /**
     * Asynchronously completes text from the always-on custom GUI surface.
     *
     * <p>This is deliberately separate from {@link #requestScreenTextAsync(String, Consumer)}:
     * that method belongs to the manual "scan this screen" action and therefore uses
     * {@code aiScreenScan}.  FTB Library fields are live screen widgets and must use the
     * {@code aiScreenText} engine selected for that surface.  The callback also lets an
     * optional UI integration reflow itself as soon as the cached translation arrives.</p>
     */
    public void requestLiveScreenTextAsync(String source, Consumer<String> onResult) {
        if (!wantsScreenTextTranslation(source)) return;
        TranslationCache selected = cache(config.aiScreenText);
        Consumer<String> ready = translated -> {
            if (meaningful(source, translated)) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, translated));
            }
        };
        if (config.aiScreenText) selected.requestCoalescedFinal(source, ready);
        else selected.requestCoalesced(source, ready, false);
    }

    /**
     * Completes an action-bar miss immediately instead of relying only on the HUD
     * render hook. Some server/client combinations replace the overlay component
     * between render calls, so a render-only request can be lost entirely.
     */
    public void requestActionBarAsync(String source, Consumer<String> onResult) {
        if (!wantsActionBarTranslation(source)) return;
        NameMasker.Masked masked = NameMasker.mask(source, names());
        cache(config.aiActionBar).requestCoalesced(masked.text(), translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            if (meaningful(source, restored)) {
                onResult.accept(LayoutPreserver.matchOuterWhitespace(source, restored));
            }
        }, false);
    }

    public void translateChatSegmentsAsync(List<String> texts, Consumer<List<String>> onAll) {
        if (showOriginalOnly) {
            onAll.accept(new ArrayList<>(texts));
            return;
        }
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (TextFilter.shouldTranslate(texts.get(i), config.targetLang)) indexes.add(i);
        }
        if (indexes.isEmpty()) {
            onAll.accept(new ArrayList<>(texts));
            return;
        }

        String[] output = texts.toArray(String[]::new);
        AtomicInteger remaining = new AtomicInteger(indexes.size());
        Collection<String> protectedNow = names();
        TranslationCache selected = cache(config.aiChat);
        for (int index : indexes) {
            String original = texts.get(index);
            NameMasker.Masked masked = NameMasker.mask(original, protectedNow);
            selected.requestCoalesced(masked.text(), translated -> {
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
        cache(config.aiChat).requestCoalesced(masked.text(), translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            if (meaningful(source, restored)) onTranslated.accept(restored);
        }, false);
    }

    public void translateChatAsync(String content, Consumer<String> onResult) {
        if (!wantsChatTranslation(content)) {
            onResult.accept(null);
            return;
        }
        NameMasker.Masked masked = NameMasker.mask(content, names());
        cache(config.aiChat).requestCoalesced(masked.text(), translated -> {
            String restored = NameMasker.unmask(translated, masked.names());
            onResult.accept(meaningful(content, restored)
                    ? LayoutPreserver.matchOuterWhitespace(content, restored) : null);
        }, true);
    }

    public void clearTranslations() {
        google.clear();
        ai.clear();
        contextualItemNameRetries.clear();
        invalidatedNameFailures.clear();
    }

    public String targetLang() { return config.targetLang; }

    public void setTargetLang(String language) {
        if (language == null || language.equals(config.targetLang)) return;
        config.targetLang = language;
        google.setTargetLang(language);
        ai.setTargetLang(language);
        contextualItemNameRetries.clear();
        invalidatedNameFailures.clear();
    }

    public void flushBatches() {
        google.flushBatch();
        ai.flushBatch();
    }

    public int translatedCount() { return google.size() + ai.size(); }
    public int pendingCount() { return google.pendingCount() + ai.pendingCount(); }
    public void retranslate(List<String> sources) {
        Collection<String> protectedNow = names();
        for (String source : sources) {
            invalidateBoth(source);
            String masked = NameMasker.mask(source, protectedNow).text();
            if (!masked.equals(source)) invalidateBoth(masked);
        }
        warmTooltipBatch(sources);
    }

    private void invalidateBoth(String source) {
        google.invalidate(source);
        ai.invalidate(source);
    }

    public TranslationDecision translateChat(String text) {
        return lookup(text, config.chatMode, config.aiChat);
    }
    public TranslationDecision translateItemLine(String text) {
        return lookup(text, config.tooltipMode, config.aiTooltip, true);
    }
    public TranslationDecision translateHeld(String text) {
        return lookup(text, config.tooltipMode, config.aiTooltip);
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
        return lookup(text, config.screenTextMode, config.aiScreenText, true);
    }
    public TranslationDecision translateScreenScanText(String text) {
        return lookup(text, config.screenTextMode, config.aiScreenScan, true);
    }

    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi) {
        return lookup(original, mode, useAi, false);
    }

    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi,
                                       boolean requireFinalAi) {
        if (showOriginalOnly || mode == DisplayMode.ORIGINAL_ONLY
                || !TextFilter.shouldTranslate(original, config.targetLang)) {
            return TranslationDecision.unchanged(original);
        }

        NameMasker.Masked masked = NameMasker.mask(original, names());
        if (masked.hasMasks() && !TextFilter.shouldTranslate(masked.text(), config.targetLang)) {
            return TranslationDecision.unchanged(original);
        }

        TranslationCache selected = cache(useAi);
        String translated = useAi && requireFinalAi
                ? selected.getCachedFinal(masked.text()) : selected.getCached(masked.text());
        if (translated == null) {
            selected.requestBatched(masked.text());
            return TranslationDecision.unchanged(original);
        }
        return decide(original, NameMasker.unmask(translated, masked.names()), mode);
    }

    public boolean warmUp(String source) {
        if (config.tooltipMode == DisplayMode.ORIGINAL_ONLY
                || !TextFilter.shouldTranslate(source, config.targetLang)) return true;
        TranslationCache selected = cache(config.aiTooltip);
        return selected.getCached(source) != null || selected.translateBlocking(source) != null;
    }

    public void warmTooltipBatch(List<String> sources) {
        warmMasked(sources, true, config.tooltipMode, config.aiTooltip);
    }

    /** Warm every blank-line/indent-delimited paragraph on the current book page in one
     * context-aware request while keeping each paragraph as one translation unit. */
    public void warmBookBatch(List<String> sources) {
        warmMasked(sources, true, config.bookMode, config.aiBook);
    }

    /** Warm every scoreboard paragraph together so the AI can disambiguate labels,
     * objectives and locations from the complete sidebar while live values remain MT slots. */
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
                || !TextFilter.shouldTranslate(source, config.targetLang)) return true;
        NameMasker.Masked masked = NameMasker.mask(source, names());
        if (masked.hasMasks() && !TextFilter.shouldTranslate(masked.text(), config.targetLang)) {
            return true;
        }
        TranslationCache selected = cache(config.aiTooltip);
        return (config.aiTooltip ? selected.getCachedFinal(masked.text())
                : selected.getCached(masked.text())) != null;
    }

    public void warmNamesBatch(List<String> sources) {
        warmMasked(sources, false, config.tooltipMode, config.aiTooltip);
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

        String retryKey = config.targetLang + '\0' + maskedName.text();
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
                contextualItemNameRetries.add(retryKey);
                return;
            }
        }

        if (!contextualItemNameRetries.add(retryKey)) return;
        // Clear both tiers so the AI request cannot be short-circuited by a GT fallback
        // copy of the same isolated name.
        invalidateBoth(maskedName.text());
        selected.warmBatchAsync(List.of(maskedName.text()), context);
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
        if (mode == DisplayMode.ORIGINAL_ONLY || sources == null) return;
        Collection<String> protectedNow = names();
        List<String> todo = new ArrayList<>();
        List<String> context = includeContext ? new ArrayList<>() : null;
        for (String source : sources) {
            if (source == null) continue;
            String masked = NameMasker.mask(source, protectedNow).text();
            if (context != null) context.add(masked);
            if (TextFilter.shouldTranslate(masked, config.targetLang)) todo.add(masked);
        }
        if (!todo.isEmpty()) cache(useAi).warmBatchAsync(todo, context);
    }

    private TranslationDecision decide(String original, String translated, DisplayMode mode) {
        boolean styleFallback = TextFilter.isStyleFallback(translated);
        String semantic = TextFilter.stripStyleFallback(translated);
        if (!meaningful(original, semantic)
                || TextFilter.isPartialTransliteration(original, semantic)) {
            return TranslationDecision.unchanged(original);
        }
        if (!protectedNamesSurvive(original, semantic)) {
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
        Set<String> protectedSet = new HashSet<>(current);
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
