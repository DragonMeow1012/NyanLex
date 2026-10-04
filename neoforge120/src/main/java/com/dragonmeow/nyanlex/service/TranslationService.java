package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.warmup.WarmupCategory;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.ChurnGuard;
import com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanlex.translate.EnchantListComposer;
import com.dragonmeow.nyanlex.translate.ItemEntityRegistry;
import com.dragonmeow.nyanlex.translate.KnownNameLabels;
import com.dragonmeow.nyanlex.translate.LayoutPreserver;
import com.dragonmeow.nyanlex.translate.LocalTokenRenumberer;
import com.dragonmeow.nyanlex.translate.NameMasker;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.RarityLineComposer;
import com.dragonmeow.nyanlex.translate.ScrollNameListComposer;
import com.dragonmeow.nyanlex.translate.StatsLineComposer;
import com.dragonmeow.nyanlex.translate.TemplateText;
import com.dragonmeow.nyanlex.translate.TermTable;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TooltipSegmentPlanner;
import com.dragonmeow.nyanlex.translate.TooltipTraceWriter;
import com.dragonmeow.nyanlex.translate.TradeLineComposer;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /**
     * 2026-10-02 correction: "manual item translation" is no longer a global flag the
     * loader flips on once at startup -- it is judged LIVE, per call, from this surface's
     * CURRENTLY CONFIGURED engine, so switching engines in-game takes effect immediately
     * with no extra wiring:
     * <ul>
     *   <li>Item-class surfaces (tooltip, held item name, container-slot/HUD hotbar name
     *       pre-warm, enchant/segment pre-warm) are manual (cache-only; a miss shows the
     *       original and sends nothing, relying on the translate-key glue's {@link
     *       #requestItemLines}/{@link #retranslate}) exactly when {@code
     *       config.aiTooltip} is {@code false} (the machine-translation engine). When
     *       {@code config.aiTooltip} is {@code true} (the AI engine), these surfaces
     *       auto-request on miss exactly like every other automatic surface (chat/
     *       scoreboard/boss bar/title/action bar/book/name) -- the behaviour from before
     *       1.0.8's manual-item-translation feature existed.</li>
     *   <li>Screen-text surfaces are manual exactly when {@code config.aiScreenText} is
     *       {@code false}, independently of the item-surface decision above.</li>
     * </ul>
     * See {@link #isManualItemTranslation()} (the AI-vs-MT item check) and the per-call
     * sites below ({@link #translateItemLine}, {@link #translateHeld}, {@link
     * #translateScreenText}, {@link #warmTooltipBatch}, {@link #warmNamesBatch}, {@link
     * #reconcileItemNameWithTooltip}) that read {@code config.aiTooltip}/{@code
     * config.aiScreenText} directly instead of a cached field.
     */
    private volatile Supplier<? extends Collection<String>> protectedNames = List::of;
    /** Compiled form of {@code config.doNotTranslateTerms}, rebuilt when the list changes. */
    private volatile DoNotTranslateMatcher doNotTranslate = DoNotTranslateMatcher.EMPTY;
    private volatile KnownNameLabels knownNameLabels = new KnownNameLabels(List.of());
    private volatile Supplier<String> itemSourceLanguage = () -> null;
    /** Repository ("GitHub AI translation hub") read-through, consulted on a cache miss of
     *  either engine (see {@link #lookup}); {@code null} = no hub wired in (default,
     *  matches every pre-hub caller/test). Never throws into the render path. */
    private volatile java.util.function.Function<String, String> hubLookup;
    private final Set<String> invalidatedNameFailures = ConcurrentHashMap.newKeySet();
    /** Masked/templated cache keys an explicit retranslate (R/P — {@link #retranslate}/
     *  {@link #retranslateScreen}) just invalidated. While a key is in this set, the hub
     *  repository read-through ({@link #hubCachedValue}) is skipped for it: otherwise a
     *  stale hub row immediately re-covers the cache row retranslate just cleared, before
     *  a fresh AI/GT result can land, and R/P shows the exact same old wording it was
     *  supposed to replace. Removed the moment a REAL (non-hub) cache value exists for
     *  the key again — see {@link #resolveEnchantName}/{@link #lookup}. Bounded like
     *  {@link #invalidatedNameFailures}: a short-lived, explicitly-triggered signal, never
     *  steady-state cache, so overflow clears the whole set rather than evicting LRU. */
    private final Set<String> forceFreshKeys = ConcurrentHashMap.newKeySet();
    /** At most one context-aware correction per item name and target language in a
     *  session. Prevents a stubborn model from creating a hover-triggered retry loop. */
    private final Set<String> contextualItemNameRetries = ConcurrentHashMap.newKeySet();
    /** P2: bounded memo of a FULLY composed enchant-list tooltip paragraph (see
     *  {@link #composeEnchantList}), so a hovered tooltip does not re-run {@link #decide}
     *  for every enchant name on every render frame. Never written to the persistent
     *  translation cache (composition is a local splice, not a translator answer);
     *  cleared wherever the cache content this composition depends on can change under
     *  it (language switch, explicit clear/retranslate). */
    private static final int ENCHANT_COMPOSE_MEMO_MAX = 256;
    private final Map<String, String> enchantComposeMemo = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > ENCHANT_COMPOSE_MEMO_MAX;
                }
            });
    /** Bounded memo of a FULLY composed structured-tooltip paragraph (a rarity line, a
     *  trade field, a stat line and/or a scroll/ability name list glued together with no
     *  blank row between them — see {@link #composeStructuredTooltip}/{@link
     *  TooltipSegmentPlanner}), same role and lifecycle as {@link #enchantComposeMemo}. */
    private static final int STRUCTURED_COMPOSE_MEMO_MAX = 256;
    private final Map<String, String> structuredComposeMemo = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > STRUCTURED_COMPOSE_MEMO_MAX;
                }
            });
    /** Item-name entity layer: every item the glue sees, with its SkyBlock id/modifier
     *  (see {@link #registerItemEntity}). Bounded LRU; language-independent. */
    private final ItemEntityRegistry itemEntities = new ItemEntityRegistry();
    /** Loader-provided info log (debug overlay diagnostics only). */
    private volatile Consumer<String> infoLog = message -> { };
    /** Kept so loader glue that still wires the old per-segment tooltip trace compiles; the
     *  trace itself is gone (偵錯模式 only logs errors now, see {@link DebugErrorLog}). */
    @Deprecated
    public void setTooltipTraceWriter(TooltipTraceWriter sink) {
        // intentionally nothing
    }
    private static final int MAX_LOGGED_ITEM_ENTITIES = 4096;
    private final Set<String> loggedItemEntities = ConcurrentHashMap.newKeySet();

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
            // Every AI request pays a fixed system prompt: collect the screen for the whole
            // batching window and send it as one request once the engine may send. The GT
            // collector keeps its immediate hover flush (see setWindowedBatching).
            ai.setWindowedBatching(true);
        }
        // Master switch: closed = serve cached rows only and send nothing new.
        if (google != null) google.setRequestGate(() -> config.translationRequestsEnabled);
        if (ai != null) ai.setRequestGate(() -> config.translationRequestsEnabled);

        ChurnGuard guard = config.churnGuard
                ? new ChurnGuard(config.churnVariantThreshold,
                config.churnWindowSeconds * 1000L,
                config.churnCooldownSeconds * 1000L,
                System::currentTimeMillis)
                : null;
        if (google != null) google.setChurnGuard(guard);
        if (ai != null) ai.setChurnGuard(guard);
        // Pay the one-time cost of the tooltip pipeline now, off the render thread, instead of
        // on the first item the player points at.
        TooltipPrewarm.startOnce();
    }

    public void setProtectedNames(Supplier<? extends Collection<String>> supplier) {
        if (supplier != null) protectedNames = supplier;
    }

    /** Loader metadata is stable for this session; compile once, outside render callbacks. */
    public void setKnownNames(Collection<String> names) {
        knownNameLabels = new KnownNameLabels(names);
    }

    private boolean keepsKnownName(String key) {
        return !forceFreshKeys.contains(key) && knownNameLabels.keepsOriginal(key, activeTargetLang);
    }

    /**
     * Whether item-class surfaces (tooltip/held/container/HUD) are currently in manual
     * (translate-key-driven, cache-only) mode -- {@code true} exactly when this surface's
     * configured engine is the machine-translation engine ({@code config.aiTooltip ==
     * false}); the AI engine auto-translates, so it is never "manual". Read live every
     * call (no cached field), so switching engines in the config screen takes effect on
     * the very next render frame. Also gates the tooltip's trailing "press [R] to
     * translate" hint line (AI mode never shows it, since nothing is ever missing on
     * purpose there).
     */
    public boolean isManualItemTranslation() {
        return !config.aiTooltip;
    }

    /** Screen-text counterpart of {@link #isManualItemTranslation()}, keyed off {@code
     *  config.aiScreenText} instead of {@code config.aiTooltip}. */
    public boolean isManualScreenTranslation() {
        return !config.aiScreenText;
    }

    /**
     * Wire in the GitHub AI translation hub's local read-through cache. Consulted only
     * when this surface's own AI cache misses (see {@link #lookup}), and BEFORE the
     * "send a request" decision — so a hub hit displays even under
     * manual item mode ({@link #isManualItemTranslation()}, 0 requests) exactly like an
     * ordinary cache hit.
     * {@code null} (the default) disables the hub entirely. Consulted for both engines.
     */
    public void setHubLookup(java.util.function.Function<String, String> lookup) {
        this.hubLookup = lookup;
    }

    private String hubCachedValue(String maskedKey) {
        java.util.function.Function<String, String> lookup = hubLookup;
        if (lookup == null) return null;
        try {
            // Same template machinery as the cache's own lookup: a repository row stored under the
            // number-normalized key (⟦MT#⟧) also answers the line with today's numbers filled in.
            return ai.lookupExternal(maskedKey, lookup);
        } catch (RuntimeException ignored) {
            // A loader-side hub cache failure must never break the ordinary render path.
            return null;
        }
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

    /** Whether NEW translation requests may be sent (the master switch). */
    private boolean requestsEnabled() {
        return config.translationRequestsEnabled;
    }

    /** Do-not-translate terms apply to every surface and engine, independent of
     *  {@code protectPlayerNames}. Recompiled only when the configured list changes. */
    private DoNotTranslateMatcher doNotTranslateTerms() {
        List<String> configured = config.doNotTranslateTerms;
        if (configured == null || configured.isEmpty()) return DoNotTranslateMatcher.EMPTY;
        DoNotTranslateMatcher current = doNotTranslate;
        if (current.compiledFrom(configured)) return current;
        try {
            DoNotTranslateMatcher compiled = DoNotTranslateMatcher.compile(configured);
            doNotTranslate = compiled;
            return compiled;
        } catch (RuntimeException concurrentEdit) {
            return current; // list edited mid-copy: keep the last complete compilation
        }
    }

    /** TAB player names, do-not-translate terms, strong-frame player names (auction
     *  sellers, lobby arrivals, …) and — for a zh_TW/zh_HK target — registered item names
     *  (E slots, see {@link #registerItemEntity}) masked together in one pass. This is the
     *  key every surface line is looked up, warmed, requested and invalidated under. */
    private NameMasker.Masked mask(String text) {
        return NameMasker.mask(text, names(), doNotTranslateTerms(), entitiesNow());
    }

    /** The pre-entity-layer mask (P slots only). Used for independent small units (item
     *  base names, enchant names, rarity type words), for surface context, and to peek
     *  the row a line had before the entity layer existed. */
    private NameMasker.Masked maskPlain(String text) {
        return NameMasker.mask(text, names(), doNotTranslateTerms());
    }

    /**
     * Whether masked text still carries something to translate. Colour-run markers are
     * removed first: a line that is nothing but protected terms, even wrapped in colour
     * runs ({@code ⟦CS0⟧⟦0⟧⟦/CS0⟧}), must neither be sent nor displayed changed.
     *
     * <p>Always re-judges the content here, even when masking found nothing to mask.
     * {@code masked.text()} being unchanged by masking does NOT mean some earlier caller
     * already ran {@link TextFilter#shouldTranslate}/{@link #shouldTranslateItem} on it —
     * several independent-segment callers ({@link #resolveEnchantName}, {@link
     * #requestEnchantName}, and therefore every {@code TRADE}/{@code STATS}/{@code PROSE}/
     * {@code ABILITY} segment of {@link #composeStructuredTooltip}) call this FIRST, with
     * no prior content check of their own, on a raw per-segment/per-name string that may
     * be pure placeholder-only machine content (no protected term to mask, nothing to
     * translate either). Trusting an unmasked string as "already decided" let exactly
     * that shape of text reach the AI backend. {@link TextFilter#shouldTranslate} is
     * memoised, so re-checking an ordinary already-validated line costs a cache hit, not
     * a fresh scan.
     */
    private boolean translatableMasked(NameMasker.Masked masked, boolean itemText) {
        String judged = masked.hasMasks()
                ? TextFilter.stripTranslationMarkers(masked.text()) : masked.text();
        return itemText ? shouldTranslateItem(judged)
                : TextFilter.shouldTranslate(judged, activeTargetLang);
    }

    private TranslationCache cache(boolean useAi) {
        return useAi ? ai : google;
    }

    // -------------------------------------------------------------------------
    // Lookup order of a machine-translation surface (2026-10-03): wording the AI engine has
    // already produced for a text wins over a machine-translation row, whichever engine the
    // surface itself selected.
    //   AI service:      AI cache -> repository -> ask the AI                     (unchanged)
    //   machine service: AI cache -> repository -> machine cache -> ask Google
    // Only the AI cache's FINAL wording counts as a hit: a provisional stand-in, a kept-
    // original row and a failure mark are all misses, so such a text still goes the machine
    // way. Reading the AI cache here never starts an AI request (see
    // TranslationCache#peekFinal) and an AI hit is never copied into the machine cache.
    // -------------------------------------------------------------------------

    /** The AI cache's final wording of {@code key}, or {@code null} (no hit, not final,
     *  kept original, no separate AI cache). A pure read: never sends a request. */
    private String aiFinalOf(String key) {
        return aiFinalOf(key, false);
    }

    private String aiFinalOf(String key, boolean exactStyle) {
        if (key == null || ai == null || ai == google) return null;
        return ai.peekFinal(key, exactStyle);
    }

    /** Same cache precedence for item composition, readiness and blocking warmups as
     *  the render path: AI -> repository, or AI -> repository -> machine cache. */
    private String cachedValue(boolean useAi, String key) {
        if (!useAi) {
            String aiHit = aiFinalOf(key);
            if (aiHit != null) return aiHit;
            String hubHit = hubValue(key);
            if (hubHit != null) return hubHit;
            String own = google.getCached(key);
            return own != null ? own : keepsKnownName(key) ? key : null;
        }
        String own = ai.getCached(key);
        if (own != null) {
            forceFreshKeys.remove(key);
            // A learned identity echo must not hide an existing translated product name.
            if (!own.equals(key) || !keepsKnownName(key)) return own;
        }
        String hubHit = hubValue(key);
        if (hubHit != null) return hubHit;
        if (keepsKnownName(key)) {
            String machineHit = google.getCached(key);
            return machineHit != null ? machineHit : key;
        }
        return own;
    }

    /** Repository read-through that an explicit R/P retranslate of {@code key} switched off. */
    private String hubValue(String key) {
        return forceFreshKeys.contains(key) ? null : hubCachedValue(key);
    }

    /** Shared request admission for cache tiers outside the selected engine. The engine
     *  deduplicates its own rows; both engines must also respect repository hits. */
    private boolean answeredBeforeRequest(boolean useAi, String key) {
        return (!useAi && aiFinalOf(key) != null) || hubValue(key) != null || keepsKnownName(key);
    }

    /** Drops from {@code keys} (masked cache keys about to be sent) the ones that
     *  {@link #answeredBeforeRequest} already covers. */
    private List<String> dropAnsweredBeforeRequest(boolean useAi, List<String> keys) {
        if (keys == null || keys.isEmpty()) return keys;
        List<String> kept = null;
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (answeredBeforeRequest(useAi, key)) {
                if (kept == null) kept = new ArrayList<>(keys.subList(0, i));
            } else if (kept != null) {
                kept.add(key);
            }
        }
        return kept == null ? keys : kept;
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
        if (keepsKnownName(source)) {
            callback.accept(cachedValue(useAi, source), true);
            return;
        }
        String hubHit = hubValue(source);
        if (hubHit != null && aiFinalOf(source) == null
                && (!useAi || ai.getCached(source) == null)) {
            callback.accept(hubHit, true);
            return;
        }
        if (!useAi) {
            // AI cache first (see the lookup order above): an AI answer is final, sends no
            // Google request and writes nothing into the machine cache. A chat line whose
            // exact colour topology the AI cache lacks still takes the AI semantic wording
            // (marked as a style fallback, like the AI path itself delivers when it may not
            // ask for the projection) rather than buying a second, machine wording.
            String aiHit = aiFinalOf(source, exactStyle);
            if (aiHit == null && exactStyle) aiHit = aiFinalOf(source, false);
            if (aiHit != null) {
                callback.accept(aiHit, true);
                return;
            }
            requestCache(google, source, exactStyle,
                    value -> callback.accept(value, true), always);
            return;
        }

        AtomicBoolean finalAiDelivered = new AtomicBoolean();
        requestCache(ai, source, exactStyle, primary -> {
            String finalNow = ai.getCachedFinal(source);
            boolean styleFallbackOnly = exactStyle && finalNow != null
                    && TextFilter.isStyleFallback(finalNow);
            if (styleFallbackOnly && primary != null && !TextFilter.isStyleFallback(primary)) {
                // The exact colour projection is cached too, merely worded differently from
                // the semantic row: the exact-style lookup completing this request has just
                // delivered it. Like any cache hit it ships once, as final, whether or not
                // new requests are switched on — never the approximate fallback first.
                finalAiDelivered.set(true);
                callback.accept(primary, true);
                return;
            }
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
            // A recovery waiter exists only while new requests may be sent; with the
            // master switch off nothing can replace this delivery, so it is final.
            boolean waitsForRecovery = followAiRecovery && ai.requestsAllowed();
            if (styleFallbackOnly) {
                // The semantic wording is final; only the CS projection is missing.
                // Ship the approximate-colour fallback now, then let the exact-style
                // waiter replace it when the projection lands. Delivering BEFORE
                // registering keeps the exact value last even if the projection is
                // already present at registration time. A delivery that is already final
                // (requests switched off) is never followed by a second final one.
                callback.accept(finalNow, !waitsForRecovery);
                if (waitsForRecovery) ai.requestCoalescedExactStyleFinal(source, recoveredFinal);
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
                if (always && !finalAiDelivered.get()) callback.accept(null, !waitsForRecovery);
                return;
            }
            if (!ai.mayUseFallback(source)) {
                if (always && !finalAiDelivered.get()) callback.accept(null, !waitsForRecovery);
                return;
            }
            if (primary != null) {
                if (!finalAiDelivered.get()) callback.accept(primary, !waitsForRecovery);
                return;
            }
            requestCache(google, source, exactStyle, lower -> {
                String recovered = ai.getCachedFinal(source);
                if (recovered != null
                        && (!exactStyle || !TextFilter.isStyleFallback(recovered))) {
                    if (finalAiDelivered.compareAndSet(false, true)) callback.accept(recovered, true);
                } else if (!ai.mayUseFallback(source)) {
                    if (always && !finalAiDelivered.get()) callback.accept(null, !waitsForRecovery);
                } else if (!finalAiDelivered.get() && (lower != null || always)) {
                    callback.accept(lower, !waitsForRecovery);
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
        String request = screenTextRequest(source);
        NameMasker.Masked masked = asyncMask(request, onResult);
        if (masked == null || !translatableMasked(masked, false)) return;
        Consumer<String> ready = translated -> {
            String restored = NameMasker.unmask(translated, restoreValues(masked, request, true));
            if (restored != null) {
                onResult.accept(finalizeScreenText(source, restored));
            }
        };
        requestByEngine(config.aiScreenText, masked.text(), false, ready, false, true);
    }

    /**
     * Asynchronously completes text from the always-on custom GUI surface.
     *
     * <p>This is deliberately separate from {@link #requestScreenTextAsync(String, Consumer)}:
     * that method belongs to the manual "scan this screen" action. Both use the engine of the
     * 介面 surface ({@code aiScreenText}). The callback lets an
     * optional UI integration reflow itself as soon as the cached translation arrives.</p>
     */
    public void requestLiveScreenTextAsync(String source, Consumer<String> onResult) {
        // Automatic live-GUI request: manual (P) under the machine engine, see isManualScreenTranslation().
        if (!config.aiScreenText || !wantsScreenTextTranslation(source)) return;
        // Same masked key as the render-time lookup of this widget.
        String request = screenTextRequest(source);
        NameMasker.Masked masked = asyncMask(request, onResult);
        if (masked == null || !translatableMasked(masked, false)) return;
        Consumer<String> ready = translated -> {
            String restored = NameMasker.unmask(translated, restoreValues(masked, request, true));
            if (restored != null) {
                onResult.accept(finalizeScreenText(source, restored));
            }
        };
        requestByEngine(screenEngine(source), masked.text(), false, ready, false, true);
    }

    /**
     * Completes an action-bar miss immediately instead of relying only on the HUD
     * render hook. Some server/client combinations replace the overlay component
     * between render calls, so a render-only request can be lost entirely.
     */
    public void requestActionBarAsync(String source, Consumer<String> onResult) {
        // Manual under the machine engine: a cached line was already shown by translateActionBar;
        // a miss stays original until the player presses P in the world (beginHudCapture).
        if (!config.aiActionBar || !wantsActionBarTranslation(source)) return;
        NameMasker.Masked masked = asyncMask(source, onResult);
        if (masked == null || !translatableMasked(masked, false)) return;
        requestByEngine(config.aiActionBar, masked.text(), false, translated -> {
            String restored = NameMasker.unmask(translated, restoreValues(masked, source, true));
            if (restored != null) {
                onResult.accept(finalizeTranslatedText(
                        LayoutPreserver.matchOuterWhitespace(source, restored)));
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
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        ItemEntityRegistry entitiesNow = entitiesNow();
        for (int index : indexes) {
            String original = texts.get(index);
            NameMasker.Masked masked = NameMasker.mask(original, protectedNow, termsNow, entitiesNow);
            if (masked.hasEntities() && !translatableMasked(masked, false)) {
                // Nothing but item names: composed locally once every name is resolved;
                // until then requested exactly as before the entity layer (see asyncMask).
                String composed = composedEntityOnlyText(original, masked);
                if (composed != null) {
                    output[index] = finalizeTranslatedText(
                            LayoutPreserver.matchOuterWhitespace(original, composed));
                    if (remaining.decrementAndGet() == 0) onAll.accept(List.of(output.clone()));
                    continue;
                }
                masked = NameMasker.mask(original, protectedNow, termsNow);
            }
            if (!translatableMasked(masked, false)) {
                // Only protected terms: the segment stays original without a request.
                if (remaining.decrementAndGet() == 0) onAll.accept(List.of(output.clone()));
                continue;
            }
            NameMasker.Masked sent = masked;
            requestByEngine(config.aiChat, sent.text(), false, translated -> {
                String restored = NameMasker.unmask(translated, restoreValues(sent, original, true));
                if (meaningful(original, restored)) {
                    output[index] = finalizeTranslatedText(
                            LayoutPreserver.matchOuterWhitespace(original, restored));
                }
                if (remaining.decrementAndGet() == 0) onAll.accept(List.of(output.clone()));
            }, true);
        }
    }

    public void requestChatAsync(String source, Consumer<String> onTranslated) {
        if (!wantsChatTranslation(source)) return;
        NameMasker.Masked masked = asyncMask(source, onTranslated);
        if (masked == null || !translatableMasked(masked, false)) return;
        requestByEngine(config.aiChat, masked.text(), false, translated -> {
            String restored = NameMasker.unmask(translated, restoreValues(masked, source, true));
            if (meaningful(source, restored)) onTranslated.accept(finalizeTranslatedText(restored));
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
        NameMasker.Masked masked = asyncMask(content,
                composed -> onResult.accept(new ChatTranslationResult(composed, true)));
        if (masked == null) return; // nothing but item names, composed locally
        if (!translatableMasked(masked, false)) {
            // Nothing but protected terms (possibly colour-wrapped): original, no request.
            onResult.accept(new ChatTranslationResult(null, true));
            return;
        }
        // Chat is inserted once and is not re-rendered after a background cache update.
        // For CS-marked rich text, wait for the exact semantic style projection instead
        // of permanently displaying the marker-free fallback with guessed colours.
        requestByEngineDetailed(config.aiChat, masked.text(), true, (translated, finalResult) -> {
            // Strip the style-fallback prefix before unmask/layout: NameMasker and
            // LayoutPreserver treat the NUL prefix as content, so outer whitespace
            // would land BEFORE the prefix and break startsWith detection downstream.
            boolean styleFallback = TextFilter.isStyleFallback(translated);
            String semantic = TextFilter.stripStyleFallback(translated);
            String restored = NameMasker.unmask(semantic, restoreValues(masked, content, true));
            if (!meaningful(content, restored)) {
                onResult.accept(new ChatTranslationResult(null, finalResult));
                return;
            }
            String laidOut = finalizeTranslatedText(
                    LayoutPreserver.matchOuterWhitespace(content, restored));
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
        enchantComposeMemo.clear();
        structuredComposeMemo.clear();
        legacyConvertAttempted.clear();
    }

    public String targetLang() { return activeTargetLang; }

    public synchronized com.dragonmeow.nyanlex.translate.TranslationFile exportTranslations() {
        return new com.dragonmeow.nyanlex.translate.TranslationFile("modern-template-v1",
                activeTargetLang, com.dragonmeow.nyanlex.config.MachineTranslationProvider.normalize(
                        config.machineTranslationProvider), google.exportTranslations(), ai.exportTranslations());
    }

    public synchronized int importTranslations(com.dragonmeow.nyanlex.translate.TranslationFile file)
            throws java.io.IOException {
        file.requireCompatible("modern-template-v1", activeTargetLang,
                com.dragonmeow.nyanlex.config.MachineTranslationProvider.normalize(config.machineTranslationProvider));
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
        enchantComposeMemo.clear();
        structuredComposeMemo.clear();
        legacyConvertAttempted.clear();
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
        enchantComposeMemo.clear();
        structuredComposeMemo.clear();
        legacyConvertAttempted.clear();
        try {
            targetLangChangeListener.run();
        } catch (RuntimeException ignored) {
            // A loader-specific repaint hook must never leave the service half-switched.
        }
    }

    private static String normalizedTargetLang(String language) {
        return language == null || language.isBlank() ? "zh-TW" : language.strip();
    }

    // -------------------------------------------------------------------------
    // Item-name entity layer: registered items, proven reforge split, E slots
    // -------------------------------------------------------------------------

    /**
     * Register an item the loader just saw (tooltip, container scan, hotbar, held item).
     * {@code skyblockId} and {@code modifier} are the item's own SkyBlock data
     * ({@code minecraft:custom_data} — top level or inside {@code ExtraAttributes} — on
     * 1.20.5+, NBT {@code ExtraAttributes} before), {@code null} when absent. Cheap and
     * safe to call every frame / every scan from any thread: a known display name with the
     * same (or less) data is a single bounded-LRU lookup. Registration itself never sends
     * a request.
     *
     * <p>Effect (zh_TW/zh_HK targets only): the item's own title, held name and warmed
     * container/hotbar name are composed as reforge translation + base-name translation
     * when the modifier proves the reforge; and a registered item name inside any other
     * line is replaced by that same item translation (see {@link ItemEntityRegistry}).</p>
     */
    public void registerItemEntity(String displayName, String skyblockId, String modifier) {
        ItemEntityRegistry.Entry entry = itemEntities.register(displayName, skyblockId, modifier);
        if (entry == null || !config.debugTranslationOverlay) return;
        if (loggedItemEntities.size() >= MAX_LOGGED_ITEM_ENTITIES
                || !loggedItemEntities.add(displayName)) return;
        try {
            infoLog.accept(describeItemEntity(entry));
        } catch (RuntimeException ignored) {
            // A loader logging failure must never break a render/scan path.
        }
    }

    /** Where {@link #registerItemEntity}'s once-per-name debug line goes (the loader's
     *  logger). Only used while {@code debugTranslationOverlay} is on. */
    public void setInfoLog(Consumer<String> sink) {
        infoLog = sink == null ? message -> { } : sink;
    }

    static String describeItemEntity(ItemEntityRegistry.Entry entry) {
        return "[item-entity] name=\"" + entry.displayName() + "\" id=" + entry.skyblockId()
                + " modifier=" + entry.modifier() + " split="
                + (entry.split() ? entry.reforgeWord() + " + \"" + entry.baseName() + "\"" : "no");
    }

    /** The registry when the entity layer applies (zh_TW/zh_HK and something matchable). */
    private ItemEntityRegistry entitiesNow() {
        return isZhTwOrHk(activeTargetLang) && itemEntities.hasMatchableNames() ? itemEntities : null;
    }

    /** One engine for every item name, whatever surface shows it, so the same item never
     *  gets two translations: the tooltip engine (where item names primarily live). */
    private boolean itemEngine() {
        return config.aiTooltip;
    }

    /** Restore values for a masked line plus whether every E slot was resolved. */
    private static final class Restore {
        final List<String> values;
        final boolean complete;

        Restore(List<String> values, boolean complete) {
            this.values = values;
            this.complete = complete;
        }
    }

    /**
     * P slots restore verbatim; an E slot restores to its item's translated name, or to
     * the English name while that is not resolved yet — in which case, when
     * {@code request} is set, the missing unit is queued (context: this line).
     */
    private Restore restore(NameMasker.Masked masked, String original, boolean request) {
        if (!masked.hasEntities()) return new Restore(masked.names(), true);
        List<String> values = new ArrayList<>(masked.names());
        boolean complete = true;
        List<String> context = null;
        for (int index : masked.entitySlots()) {
            ItemEntityRegistry.Entry entry = itemEntities.forCore(values.get(index));
            String translated = entry == null ? null : itemNameTranslation(entry);
            if (translated != null) {
                values.set(index, translated);
                continue;
            }
            complete = false;
            if (request && entry != null) {
                if (context == null) context = List.of(maskPlain(original).text());
                requestItemNameUnit(entry, context, false);
            }
        }
        return new Restore(values, complete);
    }

    /** Restore values for an async callback (item names requested when missing). */
    private List<String> restoreValues(NameMasker.Masked masked, String original,
                                       boolean allowRequest) {
        return masked.hasEntities() ? restore(masked, original, allowRequest).values : masked.names();
    }

    /**
     * The one translation of a registered item's (core) name, or {@code null} while its
     * unit is not cached yet. Split: reforge table translation + base-name unit
     * translation. Unsplit: the item's own display-name row with its decoration trimmed —
     * the very row its title and warmed name already use.
     */
    private String itemNameTranslation(ItemEntityRegistry.Entry entry) {
        boolean useAi = itemEngine();
        if (entry.split()) {
            String reforge = new TermTable(config.termOverrides).reforge(entry.reforgeWord());
            if (reforge == null) return null;
            String base = resolveItemUnit(entry.baseName(), useAi);
            return base == null ? null : joinReforge(reforge, base);
        }
        String whole = resolveItemUnit(entry.displayName(), useAi);
        if (whole == null) return null;
        String core = ItemEntityRegistry.coreName(whole);
        return core.isEmpty() ? null : core;
    }

    /** Reforge + base with no separator, except a space next to a Latin letter/digit
     *  (a base the backend kept in English: {@code 混合 Soulweaver 手套}). */
    private static String joinReforge(String reforge, String base) {
        String b = base.strip();
        if (b.isEmpty()) return reforge;
        boolean latinSeam = asciiWordChar(reforge.charAt(reforge.length() - 1))
                || asciiWordChar(b.charAt(0));
        return latinSeam ? reforge + " " + b : reforge + b;
    }

    private static boolean asciiWordChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }

    /** A resolved item unit (base name or unsplit display name): its validated cached
     *  translation, itself when it is kept original / protected / not translatable, or
     *  {@code null} while not cached. Never requests. */
    private String resolveItemUnit(String unit, boolean useAi) {
        if (!shouldTranslateItem(unit)) return unit;
        NameMasker.Masked masked = maskPlain(unit);
        if (!translatableMasked(masked, true)) return unit;
        TranslationCache selected = cache(useAi);
        String aiHit = useAi ? null : aiFinalOf(masked.text());
        String cached = aiHit != null ? aiHit : cachedValue(useAi, masked.text());
        if (cached == null) return null;
        String semantic = TextFilter.stripStyleFallback(cached);
        if (semantic.strip().equals(masked.text().strip())) return unit; // kept original
        TranslationDecision decision = decide(unit, masked, cached, DisplayMode.TRANSLATION,
                aiHit != null ? ai : selected);
        return decision.changed() ? decision.translated() : null;
    }

    /** Cache key of the unit an item's name is composed from, or {@code null} when that
     *  unit is never requested (kept verbatim). Same key {@link #resolveItemUnit} reads. */
    private String itemUnitKey(ItemEntityRegistry.Entry entry) {
        String unit = entry.split() ? entry.baseName() : entry.displayName();
        if (!shouldTranslateItem(unit)) return null;
        NameMasker.Masked masked = maskPlain(unit);
        return translatableMasked(masked, true) ? masked.text() : null;
    }

    /** Queue an item's missing unit (the one-off small request per new base name) on the
     *  item engine. A no-op while the master switch is off, or once cached/in flight —
     *  {@link TranslationCache#warmBatchAsync} dedups all of them. */
    private void requestItemNameUnit(ItemEntityRegistry.Entry entry, List<String> context,
                                     boolean highPriority) {
        String key = itemUnitKey(entry);
        if (key == null || answeredBeforeRequest(itemEngine(), key)) return;
        TranslationCache selected = cache(itemEngine());
        if (highPriority) selected.warmBatchAsyncHigh(List.of(key), context);
        else selected.warmBatchAsync(List.of(key), context);
    }

    /** A line with E slots and nothing else to translate (an item's own title, a held
     *  name, {@code "⟦0⟧ x64"}): composed locally once every item name is resolved; until
     *  then it shows what it showed before the entity layer — a PEEK of its old row, never
     *  a request for it (the only new request is the item's own unit). */
    private TranslationDecision composeEntityOnly(String original, NameMasker.Masked masked,
                                                  DisplayMode mode, boolean useAi,
                                                  boolean itemText, boolean allowRequest) {
        Restore restored = restore(masked, original, allowRequest);
        if (restored.complete) {
            String text = NameMasker.unmask(masked.text(), restored.values);
            if (!meaningful(original, text)) return TranslationDecision.unchanged(original);
            return TranslationDecision.of(mode, original, finalizeTranslatedText(
                    LayoutPreserver.matchOuterWhitespace(original, text)));
        }
        NameMasker.Masked plain = maskPlain(original);
        if (!translatableMasked(plain, itemText)) return TranslationDecision.unchanged(original);
        TranslationCache selected = cache(useAi);
        String aiHit = useAi ? null : aiFinalOf(plain.text());
        String cached = aiHit != null ? aiHit : cachedValue(useAi, plain.text());
        return cached == null ? TranslationDecision.unchanged(original)
                : decide(original, plain, cached, mode, aiHit != null ? ai : selected, allowRequest);
    }

    /** Entity-only line text for a one-shot async surface, or {@code null} while some item
     *  name is unresolved (its unit is requested). */
    private String composedEntityOnlyText(String original, NameMasker.Masked masked) {
        Restore restored = restore(masked, original, true);
        if (!restored.complete) return null;
        String text = NameMasker.unmask(masked.text(), restored.values);
        return meaningful(original, text) ? text : null;
    }

    /**
     * Mask for a one-shot async surface. An entity-only line is composed and delivered
     * right here when every item name is resolved (returns {@code null}); while one is
     * not, the line is requested exactly as before the entity layer (its P-only key),
     * because a one-shot surface cannot update on a later frame.
     */
    private NameMasker.Masked asyncMask(String source, Consumer<String> onComposed) {
        NameMasker.Masked masked = mask(source);
        if (!masked.hasEntities() || translatableMasked(masked, false)) return masked;
        String composed = composedEntityOnlyText(source, masked);
        if (composed == null) return maskPlain(source);
        onComposed.accept(finalizeTranslatedText(LayoutPreserver.matchOuterWhitespace(source, composed)));
        return null;
    }

    // -------------------------------------------------------------------------
    // P1.7: fixed rarity/type term table for isolated tooltip rarity lines
    // -------------------------------------------------------------------------

    /** P1.7 only ever applies to Traditional Chinese targets — the shipped table (and
     *  the "傳奇/罕見" choices baked into it) was reviewed for zh_TW/zh_HK specifically;
     *  zh_CN (simplified) keeps going through the ordinary translator like before. */
    private static boolean isZhTwOrHk(String targetLang) {
        return "zh-TW".equalsIgnoreCase(targetLang) || "zh-HK".equalsIgnoreCase(targetLang);
    }

    /**
     * Compose an isolated SkyBlock rarity line ({@code "EPIC DUNGEON GLOVES"}) locally
     * from {@link TermTable}, or {@code null} when {@code original} is not one of the
     * shapes {@link RarityLineComposer} recognises — the caller then falls through to
     * the ordinary masked cache lookup as if P1.7 did not exist.
     *
     * <p>When every word the line needs is already in the shipped/override table, this
     * never touches the cache at all (0 requests, 0 cache queries). When a type word is
     * missing, each such word is peeked in the cache (itself a normal, already-cached-or-
     * requested small translation unit) — a hit means it was learned from an earlier
     * real translation and is used to compose the line right now; a miss queues that
     * ONE word (not the whole line) through the ordinary request pipeline, which is a
     * no-op while the master switch is off, and lets this render fall through to
     * whatever the line's own cache entry already shows (a hit) or the ordinary
     * whole-line translation flow below (a miss) — exactly like before P1.7.</p>
     */
    private TranslationDecision composeRarityLine(String original, DisplayMode mode, boolean useAi,
                                                  boolean allowRequest) {
        RarityLineComposer.Match match = RarityLineComposer.matchLenient(original);
        if (match == null) return null;
        TermTable terms = new TermTable(config.termOverrides);
        // P1.7 composes BEFORE mask()/the cache, so a user's own do-not-translate term
        // never gets the ordinary NameMasker treatment on this path. composeProtecting
        // re-checks it directly: any word overlapping a configured term is kept verbatim
        // instead of resolved from the table (see RarityLineComposer.Match.composeProtecting).
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        String composed = match.composeProtecting(original, termsNow, (kind, word) -> {
            if (kind == RarityLineComposer.Kind.RARITY) return terms.rarity(word);
            String viaTable = terms.type(word);
            return viaTable != null ? viaTable : peekLearnedTerm(word, useAi);
        });
        if (composed != null) return TranslationDecision.of(mode, original, composed);
        if (allowRequest) {
            for (RarityLineComposer.Word word : match.unprotectedWords(original, termsNow)) {
                if (word.kind() == RarityLineComposer.Kind.TYPE && terms.type(word.text()) == null) {
                    requestLearnedTerm(word.text(), useAi);
                }
            }
        }
        return null;
    }

    /** A Chinese translation the cache already has for an out-of-table rarity/type
     *  word, without ever sending a request (pure peek). */
    private String peekLearnedTerm(String upperCaseWord, boolean useAi) {
        NameMasker.Masked masked = maskPlain(upperCaseWord);
        if (!translatableMasked(masked, true)) return null;
        TranslationCache selected = cache(useAi);
        String aiHit = useAi ? null : aiFinalOf(masked.text());
        String cached = aiHit != null ? aiHit : cachedValue(useAi, masked.text());
        if (cached == null) return null;
        TranslationDecision decision =
                decide(upperCaseWord, masked, cached, DisplayMode.TRANSLATION,
                        aiHit != null ? ai : selected);
        return decision.changed() ? decision.translated() : null;
    }

    /** Queue the one-off small request for a rarity/type word P1.7 has not learned yet.
     *  A no-op while the master switch is off, or once it is already cached/queued
     *  (the cache's own {@code requestBatchedPassive} dedups both). */
    private void requestLearnedTerm(String upperCaseWord, boolean useAi) {
        NameMasker.Masked masked = maskPlain(upperCaseWord);
        if (!translatableMasked(masked, true) || answeredBeforeRequest(useAi, masked.text())) return;
        cache(useAi).requestBatchedPassive(masked.text());
    }

    // -------------------------------------------------------------------------
    // P2: enchant-list tooltip paragraphs decomposed into one unit per enchant name
    // -------------------------------------------------------------------------

    /**
     * Decompose an isolated SkyBlock enchant-list tooltip paragraph (for example
     * {@code "Soul Eater V, Toxophilite IV, Chance IV"}, possibly joined with more
     * {@code ⟦PBn⟧} rows) into one independent, cacheable translation request PER
     * ENCHANT NAME, or {@code null} when {@code original} is not that shape — the caller
     * then falls through to the ordinary masked cache lookup exactly as if this composer
     * did not exist.
     *
     * <p>Unlike {@link #composeRarityLine}, a shape match here NEVER falls through to
     * the ordinary whole-paragraph request: the paragraph's wording is near-
     * combinatorially unique per item (which enchants, which levels, in which order), so
     * caching or requesting it as one unit is essentially never reused — the exact bug
     * this composer exists to fix. Once every name is resolved the composed line is
     * memoised in {@link #enchantComposeMemo} (never written to the translation cache
     * itself, which only ever learns individual enchant NAMES) so a hovered tooltip does
     * not re-run {@link #decide} for every name on every render frame.</p>
     *
     * <p>Applies to every target language (unlike P1.7's fixed Chinese rarity/type
     * table): each name is just an ordinary small translation unit routed through the
     * normal AI/GT cache request pipeline, which already supports any target.</p>
     */
    private TranslationDecision composeEnchantList(String original, DisplayMode mode, boolean useAi,
                                                   boolean allowRequest) {
        EnchantListComposer.Match match = EnchantListComposer.match(original);
        if (match == null) return null;

        String memoized = enchantComposeMemo.get(original);
        if (memoized != null) return TranslationDecision.of(mode, original, memoized);

        List<String> enchantNames = match.names();
        Map<String, String> resolved = new HashMap<>(enchantNames.size() * 2);
        List<String> missing = null;
        for (String name : enchantNames) {
            String value = resolveEnchantName(name, useAi);
            if (value != null) {
                resolved.put(name, value);
            } else {
                if (missing == null) missing = new ArrayList<>(enchantNames.size());
                missing.add(name);
            }
        }

        if (missing == null) {
            String composed = match.compose(original, resolved::get);
            if (composed != null) {
                enchantComposeMemo.put(original, composed);
                return TranslationDecision.of(mode, original, composed);
            }
        } else if (allowRequest) {
            // The context is the masked original list row(s): it tells the backend these
            // are enchant names (P1: "Chance" must not become "機率") without spending any
            // extra tokens once the name is learned — see resolveEnchantName/requestEnchantName.
            List<String> context = List.of(maskPlain(original).text());
            for (String name : missing) requestEnchantName(name, useAi, context, false);
        }

        // Never buy the whole paragraph — only PEEK a translation an older build (or an
        // imported file) may already have cached for it, exactly like the pre-existing
        // "not ready yet" display for any other tooltip line.
        NameMasker.Masked masked = maskPlain(original);
        if (translatableMasked(masked, true)) {
            TranslationCache selected = cache(useAi);
            String aiHit = useAi ? null : aiFinalOf(masked.text());
            String cachedWhole = aiHit != null ? aiHit : selected.getCached(masked.text());
            if (cachedWhole != null) {
                TranslationDecision old = decide(original, masked, cachedWhole, mode,
                        aiHit != null ? ai : selected, allowRequest);
                if (old.changed()) return old;
            }
        }
        return TranslationDecision.unchanged(original);
    }

    /** A resolved enchant-name translation, or {@code null} when it is not cached yet
     *  (the caller then requests it and shows the old whole-line cache/original in the
     *  meantime). A name fully covered by a do-not-translate term (or otherwise nothing
     *  left to translate) resolves to itself, verbatim, without ever being requested —
     *  checked through the SAME {@link #mask}/{@link #translatableMasked} pair every
     *  other surface uses, so this can never repeat P1.7's original bug of judging
     *  protection before, or differently from, the real masking pass.
     *
     * <p>2026-10-02 segment-key normalisation: {@code name} is ALSO this method's shared
     * chokepoint for every TRADE/STATS/PROSE/ABILITY {@link TooltipSegmentPlanner} segment
     * (see {@code composeStructuredTooltip}/{@code warmStructuredTooltipComponents}), whose
     * raw span keeps the GLOBAL {@code ⟦CSn⟧}/{@code ⟦MTn⟧}/bare-slot numbering it happened
     * to carry at its position inside the WHOLE tooltip paragraph {@code markChatContent}
     * numbered — which varies per item (a longer enchant list, an extra trade field, a
     * different rank prefix before the Seller name, …), so the exact same semantic row
     * ({@code "Seller: [MVP+] <name>"}, {@code "Crit Chance: X (Y)"}, …) would otherwise
     * mint a DIFFERENT, never-reused cache key every time. {@link
     * LocalTokenRenumberer#localize} strips that positional dependence before the key is
     * ever computed — a request/cache row is now keyed on the LOCAL numbering a standalone
     * request for this exact text alone would have produced, identical for the same row
     * regardless of item or position — and {@link LocalTokenRenumberer#restore} maps the
     * resolved value's tokens back to {@code name}'s own GLOBAL indices before returning,
     * so splicing it into the full paragraph still lines up with THAT paragraph's own
     * style-run table. A no-op (text unchanged, empty mapping) for the many callers that
     * pass plain, token-free text (an enchant/scroll name, …). */
    private static final java.util.regex.Pattern SEGMENT_CS_TOKEN =
            java.util.regex.Pattern.compile("⟦(/?)CS(\\d+)⟧");
    private static final java.util.regex.Pattern ANY_PROTOCOL_TOKEN =
            java.util.regex.Pattern.compile("⟦[^⟦⟧]*⟧");

    /**
     * The colour-free {@code semanticFallback} wording of {@code coloredSource} (a segment
     * whose own colour topology is not cached), wrapped in the segment's DOMINANT colour pair
     * -- the one covering the most letters, first on a tie -- with every other pair of the
     * source re-emitted EMPTY after it, so the marker multiset the paragraph projection
     * validates against is unchanged. {@code null} when the value cannot be projected.
     */
    static String projectStyleFallback(String coloredSource, String semanticFallback) {
        String semantic = TextFilter.stripStyleFallback(semanticFallback);
        if (semantic == null || semantic.isBlank()) return null;
        java.util.regex.Matcher matcher = SEGMENT_CS_TOKEN.matcher(coloredSource);
        List<String> order = new ArrayList<>();
        Map<String, Integer> letters = new HashMap<>();
        String open = null;
        int openEnd = 0;
        while (matcher.find()) {
            boolean closing = !matcher.group(1).isEmpty();
            String index = matcher.group(2);
            if (!closing) {
                if (open != null) return null; // nested pairs: not a shape this fallback understands
                open = index;
                openEnd = matcher.end();
                order.add(index);
            } else {
                if (open == null || !open.equals(index)) return null;
                String inside = ANY_PROTOCOL_TOKEN.matcher(
                        coloredSource.substring(openEnd, matcher.start())).replaceAll("");
                int count = 0;
                for (int i = 0; i < inside.length(); ) {
                    int cp = inside.codePointAt(i);
                    i += Character.charCount(cp);
                    if (Character.isLetter(cp)) count++;
                }
                letters.merge(index, count, Integer::sum);
                open = null;
            }
        }
        if (open != null) return null;
        if (order.isEmpty()) return semantic;
        String dominant = order.get(0);
        for (String index : order) {
            if (letters.getOrDefault(index, 0) > letters.getOrDefault(dominant, 0)) dominant = index;
        }
        StringBuilder out = new StringBuilder(semantic.length() + 16 * order.size());
        out.append('⟦').append("CS").append(dominant).append('⟧').append(semantic)
                .append("⟦/CS").append(dominant).append('⟧');
        for (String index : order) {
            if (index.equals(dominant)) continue;
            out.append("⟦CS").append(index).append("⟧⟦/CS").append(index).append('⟧');
        }
        return out.toString();
    }

    private String resolveEnchantName(String name, boolean useAi) {
        LocalTokenRenumberer.Localized localized = LocalTokenRenumberer.localize(name);
        String localName = localized.text();
        NameMasker.Masked masked = maskPlain(localName);
        if (!translatableMasked(masked, true)) return name;
        TranslationCache selected = cache(useAi);
        String key = masked.text();
        String cached = cachedValue(useAi, key);
        if (!useAi && aiFinalOf(key) != null) selected = ai;
        if (key.equals(cached) && keepsKnownName(key)) return name;
        if (cached == null) return null;
        TranslationDecision decision = decide(localName, masked, cached, DisplayMode.TRANSLATION, selected);
        if (!decision.changed()) return null;
        String translated = decision.translated();
        // A style-fallback value (TranslationCache#getCached found only the colour-
        // INDEPENDENT canonical semantic row, not yet this exact ⟦CSn⟧ topology's own
        // projection) is a signal meant for a TOP-LEVEL, WHOLE-STRING consumer that can
        // re-anchor styling onto the original surface (see
        // FabricTextStyle#markedChat's isStyleFallback branch: it strips the
        // \u0000-delimited sentinel and re-wraps the plain semantic text with the
        // surface's own colour runs). Every caller of this method (resolveNameListSegment,
        // and composeStructuredTooltip's TRADE/STATS/PROSE/ABILITY branch) instead SPLICES
        // the return value into the MIDDLE of a larger composed paragraph, where no
        // startsWith()-based consumer can ever detect or strip the sentinel again — it
        // leaked as literal "MT_STYLE_FALLBACK" text to the player, AND silently dropped
        // this segment's own CS colour markers (TranslationServiceStyleFallbackSegmentTest
        // reproduces both symptoms with a fully rule-following fake AI). Treat it exactly
        // like "not cached yet": stay pending and keep showing the original/previous value
        // this frame; once the exact-style projection lands the next lookup returns it
        // directly (TranslationCache#getCached's own sameSemanticText fast path), with no
        // extra request ever sent for an already-cached key.
        // R1: a colour-insensitive hit used to leave the segment pending forever (nothing ever
        // buys the exact colour topology on this path), so a fragmented segment -- which is now
        // requested as its plain semantic row -- would never show. Splice the semantic wording
        // into the segment's own colour structure instead (dominant colour, other pairs empty):
        // marker multiset and nesting stay valid for the whole-paragraph projection.
        if (TextFilter.isStyleFallback(translated)) {
            translated = projectStyleFallback(localName, translated);
            if (translated == null) return null;
        }
        // Map the LOCAL-numbered resolved value back to name's own GLOBAL indices (see
        // this method's class doc "2026-10-02 segment-key normalisation") — a no-op when
        // localized.newToOld() is empty (plain token-free text, the common enchant/scroll
        // name case), so every pre-existing caller/behaviour is unaffected.
        return LocalTokenRenumberer.restore(translated, localized.newToOld());
    }

    /** Queue the one-off small request for an enchant name not learned yet, carrying the
     *  original list row(s) as surface context so the backend knows these are enchant
     *  names. A no-op while the master switch is off, while the name is protected, or
     *  once it is already cached/in flight — {@link TranslationCache#warmBatchAsync}
     *  dedups all three, exactly like every other warm.
     *
     * <p>Localizes {@code name} FIRST, exactly like {@link #resolveEnchantName} — the
     * SAME shared chokepoint, so a warm-time request and a render-time lookup for the
     * same TRADE/STATS/PROSE/ABILITY segment always compute the identical key (see
     * {@link #resolveEnchantName}'s class doc). */
    private void requestEnchantName(String name, boolean useAi, List<String> context,
                                    boolean highPriority) {
        String localName = LocalTokenRenumberer.localize(name).text();
        NameMasker.Masked masked = maskPlain(localName);
        if (!translatableMasked(masked, true) || answeredBeforeRequest(useAi, masked.text())) return;
        TranslationCache selected = cache(useAi);
        if (highPriority) selected.warmBatchAsyncHigh(List.of(masked.text()), context);
        else selected.warmBatchAsync(List.of(masked.text()), context);
    }

    /** Queue every not-yet-resolved component of an enchant-list tooltip paragraph
     *  during warm, instead of waiting for the render-time query path to discover them
     *  one by one — see {@link #warmMasked}. */
    private void warmEnchantListComponents(EnchantListComposer.Match match, String original,
                                           boolean useAi, boolean highPriority) {
        List<String> context = List.of(maskPlain(original).text());
        for (String name : match.names()) {
            if (resolveEnchantName(name, useAi) == null) {
                requestEnchantName(name, useAi, context, highPriority);
            }
        }
    }

    /** Whether every distinct enchant name of an enchant-list paragraph already has a
     *  final translation — BOTH mode's tooltip commit is all-or-nothing, so a partially
     *  resolved list must not be reported ready. Used by {@link #isTooltipTranslationReady}. */
    private boolean enchantListReady(EnchantListComposer.Match match, boolean useAi) {
        for (String name : match.names()) {
            if (resolveEnchantName(name, useAi) == null) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Segment cache: a paragraph mixing a rarity line, a trade field (Seller:/Buyer:/
    // Bidder:, Buy it now:/Starting bid:/…), a stat line (Strength: +10) and/or a scroll/
    // ability name list with no blank row between them, decomposed into one independent,
    // cacheable unit PER ROW/RUN instead of one opaque multi-row paragraph key — see
    // TooltipSegmentPlanner and design-segment-cache.md §2/§6. The ability description
    // block (Ability: … Cooldown: …) is deliberately left untouched (TooltipSegmentPlanner
    // declines any text carrying one): it already sits in its own blank-line-delimited
    // paragraph and already caches/shares correctly across items.
    // -------------------------------------------------------------------------

    /**
     * Compose a structured tooltip paragraph from {@link TooltipSegmentPlanner}, or
     * {@code null} when {@code original} is not eligible for row-level decomposition at
     * all — the caller then falls through to the ordinary masked cache lookup exactly as
     * if this composer did not exist. Mirrors {@link #composeEnchantList}'s shape:
     * memoised once fully composed, every missing segment requested in one pass (never
     * just the first), nothing is ever written for a segment that could not be resolved.
     */
    private TranslationDecision composeStructuredTooltip(String original, DisplayMode mode,
                                                          boolean useAi, boolean allowRequest) {
        TooltipSegmentPlanner.Plan plan =
                TooltipSegmentPlanner.plan(original, isZhTwOrHk(activeTargetLang));
        if (plan == null) return null;

        String memoized = structuredComposeMemo.get(original);
        if (memoized != null) return TranslationDecision.of(mode, original, memoized);

        TermTable terms = new TermTable(config.termOverrides);
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        // ONE context list for every segment of this item (never rebuilt per segment): the
        // render path has no title hint available (unlike warmStructuredTooltipComponents,
        // which the glue's title+body sources make possible) — still far smaller than the
        // old "whole raw paragraph" context, and shared verbatim (by List#equals) across
        // every segment request so OpenAiTranslator's own context-run coalescing (see
        // groupedOrder/buildContextBlocks) emits it ONCE per HTTP request, not once per
        // segment. See #structuredTooltipContext for the per-unit dedup rationale.
        List<String> context = structuredTooltipContext(plan, original, null);
        TooltipSegmentPlanner.SegmentResolver resolver = (segment, rawSegmentText) -> {
            String resolved;
            switch (segment.kind()) {
                case INERT:
                    resolved = rawSegmentText;
                    break;
                case RARITY:
                    resolved = resolveRaritySegment(
                            (RarityLineComposer.Match) segment.detail(), rawSegmentText,
                            terms, termsNow, useAi, allowRequest);
                    break;
                case ENCHANT: {
                    EnchantListComposer.Match m = (EnchantListComposer.Match) segment.detail();
                    resolved = resolveNameListSegment(m.names(), rawSegmentText, m::compose,
                            useAi, allowRequest, context);
                    break;
                }
                case SCROLL: {
                    ScrollNameListComposer.Match m = (ScrollNameListComposer.Match) segment.detail();
                    resolved = resolveNameListSegment(m.names(), rawSegmentText, m::compose,
                            useAi, allowRequest, context);
                    break;
                }
                case TRADE:
                case STATS:
                case PROSE:
                case ABILITY: {
                    // The REAL cache key resolveEnchantName/requestEnchantName compute
                    // internally (localize THEN maskPlain) — shown/looked-up here exactly
                    // as-is so the trace file and the actual cache agree on what "this
                    // segment's key" means.
                    resolved = resolveEnchantName(rawSegmentText, useAi);
                    if (resolved == null && allowRequest) {
                        requestEnchantName(rawSegmentText, useAi, context, false);
                    }
                    break;
                }
                default:
                    resolved = null;
            }
            return resolved;
        };

        TooltipSegmentPlanner.Composition composition =
                TooltipSegmentPlanner.composeDetailed(original, plan, resolver);
        String composed = composition.full();
        if (composed != null) {
            structuredComposeMemo.put(original, composed);
            return TranslationDecision.of(mode, original, composed);
        }

        // Legacy whole-paragraph cache fallback + lazy segment-copy conversion (see
        // convertLegacyWholeCache/design-segment-cache.md §3): mirrors composeEnchantList's
        // own pre-existing "peek, never buy, the OLD whole-line cache row" fallback — a
        // paragraph an older build (or an imported translations file) already translated as
        // ONE opaque multi-row unit must keep displaying correctly today, not regress to
        // showing the English original just because this segment planner now exists and has
        // not yet re-earned every one of its OWN segment-level keys from scratch.
        NameMasker.Masked wholeMasked = maskPlain(original);
        if (translatableMasked(wholeMasked, true)) {
            String aiHit = useAi ? null : aiFinalOf(wholeMasked.text());
            TranslationCache selected = aiHit != null ? ai : cache(useAi);
            String cachedWhole = aiHit != null ? aiHit : selected.getCached(wholeMasked.text());
            if (cachedWhole != null) {
                // The converted rows go to the cache the whole paragraph came from: an AI
                // paragraph read on a machine surface never lands in the machine cache.
                convertLegacyWholeCache(original, plan, wholeMasked.text(), cachedWhole, selected);
                TranslationDecision old = decide(original, wholeMasked, cachedWhole, mode, selected, allowRequest);
                if (old.changed()) return old;
            }
        }
        // R6: some segments are done, others failed validation or are still in flight. Show
        // what is finished and leave only the unfinished segments in the original wording
        // (never memoised, so each arriving segment replaces its raw span on the next frame).
        // BOTH mode stays all-or-nothing: its appended block must mirror the whole tooltip.
        if (composition.partial() != null && mode != DisplayMode.BOTH && !strictFullTooltip.get()) {
            return TranslationDecision.of(mode, original, composition.partial());
        }
        return TranslationDecision.unchanged(original);
    }

    /** Test-only: current size of {@link #structuredComposeMemo}. */
    int structuredComposeMemoSizeForTest() {
        return structuredComposeMemo.size();
    }

    /** Bounded "already attempted" guard so a hovered tooltip does not repeat the (cheap
     *  but non-zero) row scan in {@link #convertLegacyWholeCache} — nor its {@link
     *  TranslationCache#importTranslationsAsync} dispatch — on every render frame for the
     *  same not-yet-fully-segmented paragraph. Same bounded-LRU discipline as {@link
     *  #structuredComposeMemo}; a language switch/clear already invalidates every memo this
     *  class keeps (see the {@code structuredComposeMemo.clear()} call sites), and this one
     *  piggybacks on the exact same sites since a converted row is only ever meaningful for
     *  the language it was converted under. */
    private static final int LEGACY_CONVERT_ATTEMPTED_MAX = 512;
    private final Map<String, Boolean> legacyConvertAttempted = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > LEGACY_CONVERT_ATTEMPTED_MAX;
                }
            });

    /**
     * Best-effort, lazy conversion of a pre-segment-cache whole-paragraph translation
     * ({@code cachedWhole}, already known to exist under the OLD whole-text cache key —
     * see the caller) into independent, row-level segment cache entries, so a LATER render
     * of this or any OTHER item sharing one of those rows' masked text needs zero new
     * requests (design-segment-cache.md §3 "讀取時懶轉換"). Runs at most once per distinct
     * {@code original} string (see {@link #legacyConvertAttempted}); the row
     * alignment/extraction itself is pure CPU work safe to run on the calling (possibly
     * render) thread, but every actual write goes through {@link
     * TranslationCache#importTranslationsAsync}, which that cache dispatches onto its own
     * background executor — never onto the calling thread (never "寫檔 on render thread").
     *
     * <p>Structural validation is all-or-nothing and gates EVERY row, not just one:
     * {@code cachedWhole}'s own ⟦PBn⟧ sequence must be exactly {@code 0..rows-1} in order
     * ({@link ParagraphModel#validBreakSequence}). A mismatched count or a reordered/
     * hallucinated sequence means row-INDEX correspondence with {@code original} cannot be
     * trusted for ANY row, so nothing is converted at all — this is the "PB 數量/順序不符
     * → 整件放棄" rule; the paragraph still keeps displaying via the caller's own
     * unconditional whole-text peek, which needs no split to be correct.</p>
     *
     * <p>Once that holds, only TRADE/STATS segments are attempted: by {@link
     * TooltipSegmentPlanner}'s own construction each is EXACTLY one row, so the
     * corresponding translated row (same row index) is the segment's own value with no
     * further slicing needed. RARITY (resolved for free from the local term table) and
     * INERT (nothing to translate) segments need no cache entry. ENCHANT/SCROLL segments
     * are a multi-NAME, within-row shape — recovering a reliable per-name boundary from
     * free-form prose that has already been translated as one unit is not attempted here;
     * they are simply left to be requested fresh later, same as for a brand new item, and
     * never block converting a sibling TRADE/STATS row of the SAME paragraph.</p>
     *
     * <p>{@code cachedWhole}'s {@code ⟦MTn⟧}/{@code ⟦n⟧}/{@code ⟦CSn⟧} slot numbers run
     * sequentially across the WHOLE multi-row text, while a segment's OWN fresh key numbers
     * them locally from 0 for that row alone — {@link LocalTokenRenumberer#renumber}
     * recovers the LOCAL numbering a standalone request for this one row would have
     * produced from the row's position inside {@code maskedWhole} (the P-slot-masked, not
     * yet MT-templated, form of the whole paragraph — further templated here the same way
     * {@link TranslationCache#getCached} does internally for a bare masked string), and
     * applies that exact mapping to the extracted translated row. A row {@code
     * LocalTokenRenumberer} cannot safely renumber (a value token with no counterpart in
     * the key row — its real scope is some OTHER row) is simply skipped, never guessed. The
     * result is still handed to {@link TranslationCache#importTranslationsAsync}, which
     * applies the ordinary {@link TranslationCache#usableForBulkTransfer} shape gate already
     * trusted for hub-repository imports as a final backstop.</p>
     */
    private void convertLegacyWholeCache(String original, TooltipSegmentPlanner.Plan plan,
                                         String maskedWhole, String cachedWhole,
                                         TranslationCache target) {
        if (legacyConvertAttempted.putIfAbsent(original, Boolean.TRUE) != null) return;
        List<int[]> originalRows = ParagraphModel.splitRawRows(original);
        if (originalRows == null || originalRows.size() < 2) return;
        int expectedBreaks = originalRows.size() - 1;
        if (!ParagraphModel.validBreakSequence(cachedWhole, expectedBreaks)) return;
        List<int[]> translatedRows = ParagraphModel.splitRawRows(cachedWhole);
        if (translatedRows == null || translatedRows.size() != originalRows.size()) return;
        // The SAME two-stage pipeline TranslationCache.getCached applies internally to a
        // bare masked string (NameMasker, then TranslationTemplate) — see unitKeyFor's own
        // doc — so this row split lines up 1:1, by index, with cachedWhole's own rows.
        String preparedWhole = CONTEXT_KEY_TEMPLATES.prepare(maskedWhole).key();
        List<int[]> keyRows = ParagraphModel.splitRawRows(preparedWhole);
        if (keyRows == null || keyRows.size() != originalRows.size()) return;

        Map<String, String> candidates = null;
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            if (segment.kind() != TooltipSegmentPlanner.Kind.TRADE
                    && segment.kind() != TooltipSegmentPlanner.Kind.STATS) {
                continue;
            }
            int rowIndex = ParagraphModel.countBreakTokens(original.substring(0, segment.start()));
            if (rowIndex < 0 || rowIndex >= translatedRows.size()) continue;
            int[] translatedRow = translatedRows.get(rowIndex);
            String translatedText = cachedWhole.substring(translatedRow[0], translatedRow[1]).strip();
            if (translatedText.isEmpty()) continue;
            int[] keyRow = keyRows.get(rowIndex);
            String globalKeyRow = preparedWhole.substring(keyRow[0], keyRow[1]).strip();
            if (globalKeyRow.isEmpty()) continue;
            LocalTokenRenumberer.Renumbered renumbered =
                    LocalTokenRenumberer.renumber(globalKeyRow, translatedText);
            if (renumbered == null) continue;
            if (candidates == null) candidates = new LinkedHashMap<>();
            candidates.put(renumbered.key(), renumbered.value());
        }
        if (candidates != null && !candidates.isEmpty()) {
            target.importTranslationsAsync(candidates);
        }
    }

    /** Same legacy-whole-key peek as {@link #composeStructuredTooltip}'s own fallback
     *  (never buys the whole paragraph, just reads it), but for the WARM path ({@link
     *  #warmMasked}): runs {@link #convertLegacyWholeCache}'s side effect BEFORE {@link
     *  #warmStructuredTooltipComponents} queues a fresh request for a segment conversion
     *  could supply for free, so a tooltip's FIRST hover does not waste a request on a
     *  segment an older build's whole-paragraph translation already answers. The warm path
     *  never needs the whole blob's own display value (only {@link #composeStructuredTooltip}
     *  does, for its own immediate-display fallback), so this discards it after the
     *  conversion attempt. */
    private void peekLegacyWholeCacheForWarm(String original, TooltipSegmentPlanner.Plan plan,
                                             boolean useAi) {
        NameMasker.Masked wholeMasked = maskPlain(original);
        if (!translatableMasked(wholeMasked, true)) return;
        String aiHit = useAi ? null : aiFinalOf(wholeMasked.text());
        String cachedWhole = aiHit != null ? aiHit : cache(useAi).getCached(wholeMasked.text());
        if (cachedWhole != null) {
            convertLegacyWholeCache(original, plan, wholeMasked.text(), cachedWhole,
                    aiHit != null ? ai : cache(useAi));
        }
    }

    /** Bounds on {@link #structuredTooltipContext}: a cached sibling segment used only as
     *  context must never let one item's request balloon regardless of its row count —
     *  both caps apply (whichever is hit first stops collection). The character budget is
     *  the one that actually matters for "不得增加 prompt token": a handful of short
     *  scroll/enchant names fits comfortably under {@link #STRUCTURED_CONTEXT_MAX_LINES}
     *  but a few verbose trade/stat rows could still blow the byte budget without it. */
    private static final int STRUCTURED_CONTEXT_MAX_LINES = 16;
    private static final int STRUCTURED_CONTEXT_MAX_CHARS = 32;

    /**
     * Per-item surface context for a structured tooltip request: {@code titleHint} (the
     * item's own name/title, when known — see {@link #warmMasked}, which can read it from
     * the SAME hover batch's sibling sources) followed by ONE LINE PER TRANSLATABLE UNIT
     * the plan would itself produce (a rarity row, a trade/stat row, each enchant/scroll
     * NAME on its own — never a whole raw row for those, never the whole raw paragraph).
     *
     * <p>Two reasons this exact shape matters, both required by the 2026-10-01 review
     * ("不得增加 prompt token"):</p>
     * <ol>
     *   <li>{@link OpenAiTranslator#buildContextBlocks}/{@code contextRows} already drops
     *       any context LINE that is byte-identical to one of THIS SAME request's
     *       translatable units ({@code unitTexts.contains(line)}) — so a segment that is
     *       ALSO in {@code todo} this round (its own context line equals its own unit
     *       text) is never duplicated. Using the raw ⟦PBn⟧-joined paragraph as context (the
     *       old shape) defeated this entirely: no single todo unit ever equalled the whole
     *       blob.</li>
     *   <li>Building the SAME list (by content) once per item and reusing it unchanged for
     *       every one of that item's segment requests lets {@code OpenAiTranslator}'s own
     *       {@code groupedOrder}/{@code buildContextBlocks} coalesce them into ONE context
     *       block for the whole group within one HTTP request, instead of resending it per
     *       unit — this was already true for the single-blob shape too, but is preserved
     *       here (callers must keep passing this SAME returned list to every segment of one
     *       item, not rebuild a fresh-but-equal one per call, to not break List#equals-based
     *       coalescing by accident).</li>
     * </ol>
     */
    /** Stateless (no store/cache dependency); reused to compute {@link
     *  #structuredTooltipContext}'s lines at EXACTLY the same key shape {@link
     *  TranslationCache} would derive for that same text if it were a request of its own. */
    private static final TranslationTemplate CONTEXT_KEY_TEMPLATES = new TranslationTemplate();

    /** The exact cache key {@code text} would get if requested on its own: NameMasker
     *  P-slot masking, THEN {@link TranslationTemplate}'s own MT/WS templating — the SAME
     *  two-stage pipeline {@link TranslationCache#getCached}/{@code warmBatchAsync} apply
     *  (P-slot masking happens in {@link #maskPlain} here; the cache does the templating
     *  stage internally via {@code templates.prepare()} when a bare masked string is
     *  handed to it). A context LINE must match this precisely, not just the P-slot-masked
     *  form, for {@code OpenAiTranslator.buildContextBlocks}'s own context/unit dedup
     *  ({@code contextRows}' {@code unitTexts.contains(line)}) to actually recognise that a
     *  context line duplicates one of THIS SAME request's translatable units. */
    private String unitKeyFor(String text) {
        return CONTEXT_KEY_TEMPLATES.prepare(maskPlain(text).text()).key();
    }

    private List<String> structuredTooltipContext(TooltipSegmentPlanner.Plan plan, String original,
                                                   String titleHint) {
        List<String> lines = new ArrayList<>();
        int[] charsUsed = {0};
        if (titleHint != null && !titleHint.isBlank()) {
            addContextLine(lines, charsUsed, unitKeyFor(titleHint));
        }
        outer:
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            if (lines.size() >= STRUCTURED_CONTEXT_MAX_LINES || charsUsed[0] >= STRUCTURED_CONTEXT_MAX_CHARS) {
                break;
            }
            String rawSegmentText = original.substring(segment.start(), segment.end());
            switch (segment.kind()) {
                // RARITY is deliberately NOT contributed: it never becomes a todo unit
                // (resolved for free from the local term table, see resolveRaritySegment)
                // and carries low cross-reference value for translating a trade/stat row
                // or a proper-noun scroll/enchant name. Keeping it out means a first-sight
                // item whose ONLY other context would have been the rarity word pays ZERO
                // context framing overhead instead of paying ~200 bytes of header/footer
                // sentences to carry one word (2026-10-01 review: "不得增加 prompt token").
                case TRADE:
                case STATS: {
                    // Localize first (see resolveEnchantName's class doc): this context
                    // line must byte-match the actual TODO unit text a sibling TRADE/STATS
                    // request for the SAME row sends, or OpenAiTranslator's own context/
                    // unit dedup (unitTexts.contains(line)) can never recognise the
                    // duplicate and silently pays for it twice.
                    String localRawSegmentText = LocalTokenRenumberer.localize(rawSegmentText).text();
                    if (!addContextLine(lines, charsUsed, unitKeyFor(localRawSegmentText))) break outer;
                    break;
                }
                case ENCHANT: {
                    EnchantListComposer.Match m = (EnchantListComposer.Match) segment.detail();
                    for (String name : m.names()) {
                        if (!addContextLine(lines, charsUsed, unitKeyFor(name))) break outer;
                    }
                    break;
                }
                case SCROLL: {
                    ScrollNameListComposer.Match m = (ScrollNameListComposer.Match) segment.detail();
                    for (String name : m.names()) {
                        if (!addContextLine(lines, charsUsed, unitKeyFor(name))) break outer;
                    }
                    break;
                }
                // PROSE/ABILITY are deliberately NOT contributed, for the same reason as
                // RARITY above plus one more: unlike every other kind, a PROSE/ABILITY
                // segment's own raw span has NO length bound (a multi-row ability
                // description can run to hundreds of characters) — and addContextLine lets
                // the very FIRST line through uncapped so at least ONE context line is
                // always available. Contributing one here could blow the "不得增加 prompt
                // token" budget in a single line. They are still perfectly good REQUEST
                // units on their own (see the resolver switch above) and still benefit from
                // whatever title/TRADE/STATS/name context this same list already carries.
                case PROSE:
                case ABILITY:
                case INERT:
                default:
                    break;
            }
        }
        return List.copyOf(lines);
    }

    /** Appends {@code line} to {@code lines} and advances {@code charsUsed[0]}, unless
     *  either {@link #STRUCTURED_CONTEXT_MAX_LINES} or {@link #STRUCTURED_CONTEXT_MAX_CHARS}
     *  would be exceeded, in which case nothing is appended; returns whether the caller may
     *  keep trying further lines (false once EITHER budget is exhausted — a caller that
     *  still has more candidates should stop collecting entirely, not skip one and try the
     *  next, so collection order stays deterministic: title, then plan order). */
    private boolean addContextLine(List<String> lines, int[] charsUsed, String line) {
        if (lines.size() >= STRUCTURED_CONTEXT_MAX_LINES) return false;
        if (charsUsed[0] + line.length() > STRUCTURED_CONTEXT_MAX_CHARS && !lines.isEmpty()) return false;
        lines.add(line);
        charsUsed[0] += line.length();
        return charsUsed[0] < STRUCTURED_CONTEXT_MAX_CHARS;
    }

    /** RARITY segment resolution, shared by {@link #composeStructuredTooltip} and {@link
     *  #structuredTooltipReady} — same term-table composition {@link #composeRarityLine}
     *  uses for an isolated rarity line, applied to just this segment's own raw span. */
    private String resolveRaritySegment(RarityLineComposer.Match match, String rawSegmentText,
                                        TermTable terms, DoNotTranslateMatcher termsNow,
                                        boolean useAi, boolean allowRequest) {
        String composed = match.composeProtecting(rawSegmentText, termsNow, (kind, word) -> {
            if (kind == RarityLineComposer.Kind.RARITY) return terms.rarity(word);
            String viaTable = terms.type(word);
            return viaTable != null ? viaTable : peekLearnedTerm(word, useAi);
        });
        if (composed == null && allowRequest) {
            for (RarityLineComposer.Word word : match.unprotectedWords(rawSegmentText, termsNow)) {
                if (word.kind() == RarityLineComposer.Kind.TYPE && terms.type(word.text()) == null) {
                    requestLearnedTerm(word.text(), useAi);
                }
            }
        }
        return composed;
    }

    /** ENCHANT/SCROLL segment resolution: both {@link EnchantListComposer.Match} and
     *  {@link ScrollNameListComposer.Match} expose the same "distinct names, resolve
     *  independently, splice back" shape, so one generic helper serves both kinds —
     *  {@code composeFn} is {@code Match::compose}. Every missing name is requested (not
     *  just the first) with the whole run's row(s) as shared surface context, exactly like
     *  {@link #warmEnchantListComponents}. */
    private String resolveNameListSegment(List<String> names, String rawSegmentText,
                                          java.util.function.BiFunction<String,
                                                  java.util.function.Function<String, String>,
                                                  String> composeFn,
                                          boolean useAi, boolean allowRequest, List<String> context) {
        Map<String, String> resolved = new HashMap<>(names.size() * 2);
        List<String> missing = null;
        for (String name : names) {
            String value = resolveEnchantName(name, useAi);
            if (value != null) {
                resolved.put(name, value);
            } else {
                if (missing == null) missing = new ArrayList<>(names.size());
                missing.add(name);
            }
        }
        if (missing != null) {
            if (allowRequest) {
                for (String name : missing) requestEnchantName(name, useAi, context, false);
            }
            return null;
        }
        return composeFn.apply(rawSegmentText, resolved::get);
    }

    /** Queue every not-yet-resolved segment of a structured tooltip paragraph during warm,
     *  instead of waiting for the render-time query path to discover them one by one — see
     *  {@link #warmMasked}, mirrors {@link #warmEnchantListComponents}. {@code context} is
     *  precomputed ONCE by the caller (see {@link #structuredTooltipContext}) and reused
     *  verbatim for every segment of this item — never a fresh-but-equal copy per segment —
     *  so {@code OpenAiTranslator}'s own context-run coalescing (by {@code List#equals})
     *  still emits it ONCE per HTTP request, not once per segment. */
    private void warmStructuredTooltipComponents(TooltipSegmentPlanner.Plan plan, String original,
                                                 boolean useAi, boolean highPriority,
                                                 List<String> context) {
        TermTable terms = new TermTable(config.termOverrides);
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            String rawSegmentText = original.substring(segment.start(), segment.end());
            switch (segment.kind()) {
                case RARITY: {
                    RarityLineComposer.Match m = (RarityLineComposer.Match) segment.detail();
                    for (RarityLineComposer.Word word : m.unprotectedWords(rawSegmentText, termsNow)) {
                        if (word.kind() == RarityLineComposer.Kind.TYPE
                                && terms.type(word.text()) == null
                                && peekLearnedTerm(word.text(), useAi) == null) {
                            requestLearnedTerm(word.text(), useAi);
                        }
                    }
                    break;
                }
                case ENCHANT: {
                    EnchantListComposer.Match m = (EnchantListComposer.Match) segment.detail();
                    for (String name : m.names()) {
                        if (resolveEnchantName(name, useAi) == null) {
                            requestEnchantName(name, useAi, context, highPriority);
                        }
                    }
                    break;
                }
                case SCROLL: {
                    ScrollNameListComposer.Match m = (ScrollNameListComposer.Match) segment.detail();
                    for (String name : m.names()) {
                        if (resolveEnchantName(name, useAi) == null) {
                            requestEnchantName(name, useAi, context, highPriority);
                        }
                    }
                    break;
                }
                case TRADE:
                case STATS:
                case PROSE:
                case ABILITY:
                    if (resolveEnchantName(rawSegmentText, useAi) == null) {
                        requestEnchantName(rawSegmentText, useAi, context, highPriority);
                    }
                    break;
                case INERT:
                default:
                    break;
            }
        }
    }

    /** What {@link #isTooltipTranslationReady} reports for a structured paragraph: every
     *  segment final, or (R6, not BOTH) at least one finished so the paragraph can show its
     *  finished segments while a failed/in-flight one stays original. */
    private boolean structuredTooltipDisplayable(TooltipSegmentPlanner.Plan plan, String original,
                                                 boolean useAi) {
        int[] progress = structuredTooltipProgress(plan, original, useAi);
        if (progress[0] == progress[1]) return true;
        // Only a segment that needed the provider counts as progress: a rarity word resolved
        // from the local term table is "done" on the very first frame and must not make every
        // tooltip flash a half-translated state before anything was actually answered.
        return config.tooltipMode != DisplayMode.BOTH && progress[2] > 0;
    }

    /** {@code {finalSegments, translatableSegments, finalSegmentsThatNeededTheProvider}}. */
    private int[] structuredTooltipProgress(TooltipSegmentPlanner.Plan plan, String original,
                                            boolean useAi) {
        TermTable terms = new TermTable(config.termOverrides);
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        int done = 0;
        int total = 0;
        int doneRemote = 0;
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            String rawSegmentText = original.substring(segment.start(), segment.end());
            boolean ready;
            switch (segment.kind()) {
                case RARITY: {
                    RarityLineComposer.Match m = (RarityLineComposer.Match) segment.detail();
                    String composed = m.composeProtecting(rawSegmentText, termsNow, (kind, word) -> {
                        if (kind == RarityLineComposer.Kind.RARITY) return terms.rarity(word);
                        String viaTable = terms.type(word);
                        return viaTable != null ? viaTable : peekLearnedTerm(word, useAi);
                    });
                    ready = composed != null;
                    break;
                }
                case ENCHANT: {
                    EnchantListComposer.Match m = (EnchantListComposer.Match) segment.detail();
                    ready = true;
                    for (String name : m.names()) {
                        if (resolveEnchantName(name, useAi) == null) {
                            ready = false;
                            break;
                        }
                    }
                    break;
                }
                case SCROLL: {
                    ScrollNameListComposer.Match m = (ScrollNameListComposer.Match) segment.detail();
                    ready = true;
                    for (String name : m.names()) {
                        if (resolveEnchantName(name, useAi) == null) {
                            ready = false;
                            break;
                        }
                    }
                    break;
                }
                case TRADE:
                case STATS:
                case PROSE:
                case ABILITY:
                    ready = resolveEnchantName(rawSegmentText, useAi) != null;
                    break;
                case INERT:
                default:
                    continue;
            }
            total++;
            if (ready) {
                done++;
                if (segment.kind() != TooltipSegmentPlanner.Kind.RARITY) doneRemote++;
            }
        }
        return new int[] {done, total, doneRemote};
    }

    /** Whether some segment of a structured tooltip paragraph currently has a request
     *  queued or in flight — mirrors the enchant-list branch of {@link
     *  #isTooltipTranslationPending}. */
    private boolean structuredTooltipPending(TooltipSegmentPlanner.Plan plan, String original,
                                             boolean useAi) {
        TranslationCache selected = cache(useAi);
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            String rawSegmentText = original.substring(segment.start(), segment.end());
            switch (segment.kind()) {
                case RARITY: {
                    RarityLineComposer.Match m = (RarityLineComposer.Match) segment.detail();
                    for (RarityLineComposer.Word word : m.unprotectedWords(rawSegmentText, termsNow)) {
                        NameMasker.Masked masked = maskPlain(word.text());
                        if (translatableMasked(masked, true) && selected.isPending(masked.text())) {
                            return true;
                        }
                    }
                    break;
                }
                case ENCHANT: {
                    EnchantListComposer.Match m = (EnchantListComposer.Match) segment.detail();
                    for (String name : m.names()) {
                        NameMasker.Masked masked = maskPlain(name);
                        if (translatableMasked(masked, true) && selected.isPending(masked.text())) {
                            return true;
                        }
                    }
                    break;
                }
                case SCROLL: {
                    ScrollNameListComposer.Match m = (ScrollNameListComposer.Match) segment.detail();
                    for (String name : m.names()) {
                        NameMasker.Masked masked = maskPlain(name);
                        if (translatableMasked(masked, true) && selected.isPending(masked.text())) {
                            return true;
                        }
                    }
                    break;
                }
                case TRADE:
                case STATS:
                case PROSE:
                case ABILITY: {
                    // Same local-renumbering chokepoint as resolveEnchantName/
                    // requestEnchantName (see that method's class doc): the pending check
                    // must test the SAME key a request for this segment was actually
                    // queued under, not the raw, position-dependent global-numbered text.
                    String localSegmentText = LocalTokenRenumberer.localize(rawSegmentText).text();
                    NameMasker.Masked masked = maskPlain(localSegmentText);
                    if (translatableMasked(masked, true) && selected.isPending(masked.text())) {
                        return true;
                    }
                    break;
                }
                case INERT:
                default:
                    break;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // P1.4: display-only calendar date localisation
    // -------------------------------------------------------------------------
    //
    // TemplateText slots a whole "Month d, yyyy" run as one opaque ⟦MT⟧ value, so the
    // cache always restores it byte-for-byte in English (B6) — every day/month/year
    // variant shares one cached translation, and the English wording is the only thing
    // ever written to disk or sent back through retokenize/rebuild paths. A zh-family
    // target instead wants to SEE "2026年11月15日", never the cache content itself, so
    // that conversion happens exactly once, here, on the finished text this service is
    // about to hand back to a render surface — never inside TranslationCache.
    //
    // finalizeTranslatedText() is the single choke point every "final translated text"
    // exit point in this class calls: decide() (every DisplayMode surface looked up via
    // lookup()/decide()) and the async chat/screen/action-bar callbacks. An untranslated
    // original is never passed here — every caller already returned it separately via
    // TranslationDecision.unchanged()/a null callback before reaching this method — so a
    // line that stays in English is never touched, matching "原文不轉".

    // Bounded LRU, same eviction discipline as TemplateText's own per-string MEMO
    // (single-entry eviction on overflow, never a whole-table clear): this method runs
    // on every DisplayMode surface's render-frame lookup, but the SAME finished string
    // repeats every frame for an unchanging tooltip/scoreboard/name-tag line, so a plain
    // memo keyed by that finished string turns almost every call into an O(1) hit. The
    // conversion is a pure function of the text alone (the "is this a zh target" gate
    // already happened in the caller), so sharing one static memo across every service
    // instance and target-language session is safe.
    private static final int DISPLAY_DATE_MEMO_MAX = 2048;
    private static final java.util.Map<String, String> DISPLAY_DATE_MEMO =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, String> eldest) {
                            return size() > DISPLAY_DATE_MEMO_MAX;
                        }
                    });

    /** Single shared exit-point transform: every finished translation this service
     *  hands back to a render surface passes through here exactly once. */
    private String finalizeTranslatedText(String text) {
        if (text == null || !TextFilter.isTargetChinese(activeTargetLang)) return text;
        return localizeCalendarDatesForDisplay(text);
    }

    private static String localizeCalendarDatesForDisplay(String text) {
        // Literal prefilter: every date this can match has a four-digit year, so a plain
        // scan rejects the common case (most translated lines) without ever building a
        // Matcher — the "字面預篩" this per-frame path is required to have.
        if (!com.dragonmeow.nyanlex.translate.CalendarDates.mayContainDate(text)) return text;
        String memoized = DISPLAY_DATE_MEMO.get(text);
        if (memoized != null) return memoized;
        String converted = com.dragonmeow.nyanlex.translate.CalendarDates.toChinese(text);
        DISPLAY_DATE_MEMO.put(text, converted);
        return converted;
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
        tickHudCapture();
        google.flushBatch();
        ai.flushBatch();
    }

    // -------------------------------------------------------------------------
    // P while no screen is open: translate what the HUD is drawing right now
    // -------------------------------------------------------------------------

    /** The HUD text surfaces the in-world P scan collects. */
    public enum HudSurface { SCOREBOARD, BOSS_BAR, TITLE, ACTION_BAR, NAME_TAG, SCREEN_TEXT }

    /** Client ticks the collection window stays open: at least one full render frame passes. */
    private static final int HUD_CAPTURE_TICKS = 3;
    private static final int HUD_CAPTURE_MAX_TEXTS = 256;

    private static final class HudCapture {
        final java.util.EnumMap<HudSurface, LinkedHashSet<String>> texts =
                new java.util.EnumMap<>(HudSurface.class);
        final java.util.function.IntConsumer onSent;
        int ticksLeft = HUD_CAPTURE_TICKS;
        int total;

        HudCapture(java.util.function.IntConsumer onSent) {
            this.onSent = onSent;
        }
    }

    private final Object hudLock = new Object();
    private volatile HudCapture hudCapture;

    /** Whether some HUD surface is set to the machine-translation engine (so P in the world
     *  is a manual machine action and must pass the Google gate). */
    public boolean usesMachineEngineForHud() {
        return !config.aiScoreboard || !config.aiBossBar || !config.aiTitle
                || !config.aiActionBar || !config.aiName || !config.aiScreenText;
    }

    public boolean isHudCaptureActive() {
        return hudCapture != null;
    }

    /**
     * Opens a short collection window (a few client ticks) in which every HUD text the render
     * hooks actually look up -- each scoreboard row, boss bar name, title/subtitle, action bar
     * line and name tag drawn in those frames -- is remembered. When the window closes (on the
     * {@link #flushBatches} ticks) every collected text is retranslated in ONE batch per engine:
     * like the screen scan, a stale row is discarded first, and the cache ordering of the
     * lookup path still applies. {@code onSent} receives the number of texts sent ({@code 0}
     * when nothing translatable was on screen). The caller owns the consent and Google-gate
     * checks. Returns {@code false} when a window is already open or there is no
     * HUD surface to scan.
     */
    public boolean beginHudCapture(java.util.function.IntConsumer onSent) {
        synchronized (hudLock) {
            if (hudCapture != null || showOriginalOnly) return false;
            hudCapture = new HudCapture(onSent);
            return true;
        }
    }

    private DisplayMode hudMode(HudSurface surface) {
        return switch (surface) {
            case SCOREBOARD -> config.scoreboardMode;
            case BOSS_BAR -> config.bossBarMode;
            case TITLE -> config.titleMode;
            case ACTION_BAR -> config.actionBarMode;
            case NAME_TAG -> config.nameMode;
            case SCREEN_TEXT -> config.screenTextMode;
        };
    }

    private boolean hudEngine(HudSurface surface) {
        return switch (surface) {
            case SCOREBOARD -> config.aiScoreboard;
            case BOSS_BAR -> config.aiBossBar;
            case TITLE -> config.aiTitle;
            case ACTION_BAR -> config.aiActionBar;
            case NAME_TAG -> config.aiName;
            case SCREEN_TEXT -> config.aiScreenText;
        };
    }

    /** Called by the HUD lookups: records the exact text the render hook asked about. */
    private void noteHud(HudSurface surface, String text) {
        HudCapture capture = hudCapture;
        if (capture == null || text == null || text.isBlank()) return;
        if (hudMode(surface) == DisplayMode.ORIGINAL_ONLY
                || !TextFilter.shouldTranslate(text, activeTargetLang)) return;
        synchronized (hudLock) {
            if (hudCapture != capture || capture.total >= HUD_CAPTURE_MAX_TEXTS) return;
            if (capture.texts.computeIfAbsent(surface, k -> new LinkedHashSet<>()).add(text)) {
                capture.total++;
            }
        }
    }

    private void tickHudCapture() {
        HudCapture capture = hudCapture;
        if (capture == null) return;
        synchronized (hudLock) {
            if (hudCapture != capture) return;
            if (--capture.ticksLeft > 0) return;
            hudCapture = null;
        }
        int sent = 0;
        try {
            sent = sendHudCapture(capture);
        } finally {
            if (capture.onSent != null) {
                try {
                    capture.onSent.accept(sent);
                } catch (RuntimeException ignored) {
                    // a loader feedback failure must never break the client tick
                }
            }
        }
    }

    private int sendHudCapture(HudCapture capture) {
        // Cache-only mode (master switch off): nothing may be discarded that cannot be bought again.
        if (!requestsEnabled()) return 0;
        List<String> viaMachine = new ArrayList<>();
        List<String> viaAi = new ArrayList<>();
        for (Map.Entry<HudSurface, LinkedHashSet<String>> entry : capture.texts.entrySet()) {
            List<String> target = hudEngine(entry.getKey()) ? viaAi : viaMachine;
            for (String text : entry.getValue()) {
                NameMasker.Masked masked = mask(text);
                if (translatableMasked(masked, false) && !target.contains(text)) target.add(text);
            }
        }
        int sent = 0;
        for (boolean useAi : new boolean[] {false, true}) {
            List<String> sources = useAi ? viaAi : viaMachine;
            if (sources.isEmpty()) continue;
            invalidateSources(sources);
            // One batch per engine: the whole HUD is each other's surface context.
            warmMasked(sources, true, DisplayMode.TRANSLATION, useAi, true, false, false);
            sent += sources.size();
        }
        enchantComposeMemo.clear();
        structuredComposeMemo.clear();
        legacyConvertAttempted.clear();
        return sent;
    }

    public int translatedCount() { return google.size() + ai.size(); }
    public int pendingCount() { return google.pendingCount() + ai.pendingCount(); }
    public void retranslate(List<String> sources) {
        // Cache-only mode: deleting rows that cannot be re-requested would only hide
        // translations, so an explicit retranslate is a no-op while requests are off.
        if (!requestsEnabled()) return;
        invalidateSources(sources);
        enchantComposeMemo.clear();
        structuredComposeMemo.clear();
        legacyConvertAttempted.clear();
        // Explicit (key-triggered) entry point: always sends, even in manual item mode —
        // see requestItemLines/isManualItemTranslation().
        requestItemLines(sources);
    }

    /** Explicit rescan: discard stale/failed rows, then queue the original screen inputs. */
    public void retranslateScreen(List<String> sources) {
        if (!requestsEnabled()) return; // see retranslate(): never invalidate without resending
        invalidateSources(sources);
        // Same three memos retranslate() clears, for the same reason: translateScreenText()
        // passes enchantList=true (see the warm call below), so an enchant/scroll-list-shaped
        // widget paragraph can be fully composed and memoised by composeEnchantList exactly
        // like an item tooltip's can. That memo is keyed by the raw paragraph text and is
        // consulted BEFORE any per-name cache lookup, so leaving a stale entry in it here
        // made P keep displaying the old composed wording forever even though
        // invalidateSources() above already correctly cleared every per-name key underneath
        // it — the fresh names were there, just never reached. structuredComposeMemo/
        // legacyConvertAttempted are cleared too for the same reason the other three
        // call sites clear all three together (itemText is false for every screen surface
        // today, so composeStructuredTooltip never runs here, but this keeps the four
        // invalidation sites from silently drifting apart if that ever changes).
        enchantComposeMemo.clear();
        structuredComposeMemo.clear();
        legacyConvertAttempted.clear();
        // enchantList=true (not itemText): an explicit rescan must decompose an
        // enchant-list paragraph exactly like the per-frame translateScreenText() lookup
        // does, instead of ever buying the whole near-combinatorially-unique paragraph as
        // one throwaway request.
        warmMasked(sources, true, config.screenTextMode, config.aiScreenText, false, false, true);
    }

    private boolean screenEngine(String source) {
        return config.aiScreenText;
    }

    private void invalidateSources(List<String> sources) {
        Collection<String> protectedNow = names();
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        ItemEntityRegistry entitiesNow = entitiesNow();
        for (String source : sources) {
            invalidateBothForceFresh(source);
            NameMasker.Masked maskedSource = NameMasker.mask(source, protectedNow, termsNow, entitiesNow);
            String masked = maskedSource.text();
            if (!masked.equals(source)) invalidateBothForceFresh(masked);
            // The paragraph may ALSO be rendered from independent segment-level keys
            // (TooltipSegmentPlanner — RARITY/ENCHANT/SCROLL/TRADE/STATS/PROSE/ABILITY),
            // never from the whole-paragraph key above. Without this, R/P invalidated only
            // a row nothing actually reads from any more, and composeStructuredTooltip
            // immediately re-resolved every segment from its still-cached (now stale) old
            // value — see invalidateStructuredSegments.
            invalidateStructuredSegments(source);
            if (!maskedSource.hasEntities()) continue;
            // The row this line had before the entity layer is stale too.
            String plain = NameMasker.mask(source, protectedNow, termsNow).text();
            if (!plain.equals(source) && !plain.equals(masked)) invalidateBothForceFresh(plain);
            if (translatableMasked(maskedSource, true)) continue;
            // An item's own (composed) name: retranslating it re-buys the unit it is
            // composed from — the one warm below queues again.
            for (int index : maskedSource.entitySlots()) {
                ItemEntityRegistry.Entry entry = itemEntities.forCore(maskedSource.names().get(index));
                String unitKey = entry == null ? null : itemUnitKey(entry);
                if (unitKey != null) invalidateBothForceFresh(unitKey);
            }
        }
    }

    /**
     * Invalidate every independent segment-level cache key {@link TooltipSegmentPlanner}
     * decomposes {@code original} into — the SAME classification {@link
     * #composeStructuredTooltip}'s resolver switches on, mirrored here so retranslate()/
     * retranslateScreen() actually reaches what the tooltip is really rendered from. A
     * RARITY segment's fixed-table words need nothing (never cached); its learned
     * out-of-table TYPE words do. ENCHANT/SCROLL invalidate each distinct name. TRADE/
     * STATS/PROSE/ABILITY invalidate the segment's own raw span, exactly the key {@link
     * #resolveEnchantName}/{@link #requestEnchantName} request it under.
     */
    private void invalidateStructuredSegments(String original) {
        TooltipSegmentPlanner.Plan plan =
                TooltipSegmentPlanner.plan(original, isZhTwOrHk(activeTargetLang));
        if (plan == null) return;
        TermTable terms = new TermTable(config.termOverrides);
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            String rawSegmentText = original.substring(segment.start(), segment.end());
            switch (segment.kind()) {
                case RARITY -> {
                    RarityLineComposer.Match m = (RarityLineComposer.Match) segment.detail();
                    for (RarityLineComposer.Word word : m.unprotectedWords(rawSegmentText, termsNow)) {
                        if (word.kind() == RarityLineComposer.Kind.TYPE
                                && terms.type(word.text()) == null) {
                            invalidateMaskedUnit(word.text());
                        }
                    }
                }
                case ENCHANT -> {
                    EnchantListComposer.Match m = (EnchantListComposer.Match) segment.detail();
                    for (String name : m.names()) invalidateMaskedUnit(name);
                }
                case SCROLL -> {
                    ScrollNameListComposer.Match m = (ScrollNameListComposer.Match) segment.detail();
                    for (String name : m.names()) invalidateMaskedUnit(name);
                }
                case TRADE, STATS, PROSE, ABILITY -> invalidateMaskedUnit(rawSegmentText);
                case INERT -> { }
            }
        }
    }

    /** Invalidate the cache row for one independent small unit (an enchant/scroll name,
     *  a learned rarity/type word, a TRADE/STATS/PROSE/ABILITY segment's own raw span) —
     *  the SAME {@link #maskPlain} key {@link #resolveEnchantName}/{@link
     *  #requestEnchantName}/{@link #peekLearnedTerm} resolve it under. */
    private void invalidateMaskedUnit(String unitText) {
        invalidateBothForceFresh(maskPlain(unitText).text());
    }

    private void invalidateBoth(String source) {
        google.invalidate(source);
        ai.invalidate(source);
    }

    /** Invalidate, and mark the key so the hub repository read-through ({@link
     *  #hubCachedValue}) is skipped for it until a real cache value lands again — see
     *  {@link #forceFreshKeys}. */
    private void invalidateBothForceFresh(String source) {
        invalidateBoth(source);
        if (source == null) return;
        if (forceFreshKeys.size() >= 512) forceFreshKeys.clear();
        forceFreshKeys.add(source);
    }

    public TranslationDecision translateChat(String text) {
        return lookup(text, config.chatMode, config.aiChat);
    }
    public TranslationDecision translateItemLine(String text) {
        // allowRequest == config.aiTooltip: the AI engine auto-sends on a miss; the
        // machine-translation engine is cache-only (manual, translate-key-driven) -- see
        // isManualItemTranslation().
        TranslationDecision d = lookup(text, config.tooltipMode, config.aiTooltip, true, true, true,
                config.aiTooltip);
        if (!d.changed()) return d;
        // Display-only tooltip clean-up: preserved wide column padding looks like a hole
        // after the much narrower CJK translation. Applied AFTER the cache lookup, so the
        // stored translation (and its retokenised template) stays untouched; scoreboard /
        // boss bar / chat / book surfaces never pass through here.
        String tightened = TemplateText.collapseTranslatedColumnGaps(d.translated());
        // R3: a Chinese target gets full-width punctuation after Chinese text (display only).
        if (activeTargetLang != null && activeTargetLang.toLowerCase(java.util.Locale.ROOT).startsWith("zh")) {
            tightened = TemplateText.fullWidthPunctuationAfterCjk(tightened);
        }
        return tightened.equals(d.translated()) ? d
                : TranslationDecision.of(d.mode(), d.original(), tightened);
    }
    public TranslationDecision translateHeld(String text) {
        return lookup(text, config.tooltipMode, config.aiTooltip, false, true, true,
                config.aiTooltip);
    }
    // 2026-10-03: under the machine-translation engine (Google) only CHAT translates on its
    // own; every other surface is manual -- a cache hit (AI cache, repository, machine cache,
    // in that order) still displays, a miss shows the original and sends nothing until the
    // player triggers a translation (R: item, P: the open screen, or the HUD while no screen is
    // open -- see beginHudCapture). Each surface follows its OWN engine switch, so a surface the
    // player set to the AI engine keeps translating automatically.
    public TranslationDecision translateScoreboardLine(String text) {
        noteHud(HudSurface.SCOREBOARD, text);
        return lookup(text, config.scoreboardMode, config.aiScoreboard, true, false, false,
                config.aiScoreboard);
    }
    public TranslationDecision translateUi(String text) {
        noteHud(HudSurface.NAME_TAG, text);
        return lookup(text, config.nameMode, config.aiName, false, false, false, config.aiName);
    }
    public TranslationDecision translateBossBar(String text) {
        noteHud(HudSurface.BOSS_BAR, text);
        return lookup(text, config.bossBarMode, config.aiBossBar, false, false, false,
                config.aiBossBar);
    }
    public TranslationDecision translateTitle(String text) {
        noteHud(HudSurface.TITLE, text);
        return lookup(text, config.titleMode, config.aiTitle, false, false, false, config.aiTitle);
    }
    public TranslationDecision translateActionBar(String text) {
        noteHud(HudSurface.ACTION_BAR, text);
        return lookup(text, config.actionBarMode, config.aiActionBar, false, false, false,
                config.aiActionBar);
    }
    public TranslationDecision translateBook(String text) {
        return lookup(text, config.bookMode, config.aiBook, true, false, false, config.aiBook);
    }
    public TranslationDecision translateScreenText(String text) {
        // Not itemText (screen widgets keep TextFilter.shouldTranslate()/requestBatched()
        // gating, unlike a tooltip), but the SAME enchant-list paragraphs can appear in a
        // custom GUI's plain text, so P2 decomposition still applies -- otherwise the
        // identical combinatorial-paragraph bug (see EnchantListComposer) just reappears
        // on this surface instead.
        // allowRequest == config.aiScreenText: screen-text surfaces are manual (cache-only)
        // under the machine-translation engine, independent of the item-surface decision
        // above -- see isManualScreenTranslation(). Note this is NOT screenEngine(text)
        // (the CACHE selection a few lines up): a manually-scanned source still only
        // auto-sends when aiScreenText itself is AI.
        String request = screenTextRequest(text);
        return screenTextDecision(text,
                lookup(request, config.screenTextMode, screenEngine(text), true, false, true,
                        config.aiScreenText));
    }

    /**
     * Screen-style text rendered by an in-world mod HUD, such as FTB Quests' pinned tracker.
     * It uses the same mode, engine and prose-layout policy as ordinary GUI text, while also
     * participating in the manual in-world HUD scan when the machine engine is selected.
     */
    public TranslationDecision translateQuestHudText(String text) {
        noteHud(HudSurface.SCREEN_TEXT, screenTextRequest(text));
        return translateScreenText(text);
    }
    /** {@code §x} codes around one plain run: the shape a widget draws a coloured label in. */
    private static final java.util.regex.Pattern LEGACY_WRAPPED =
            java.util.regex.Pattern.compile("^((?:§.)+)([^§]+)((?:§.)*)$", java.util.regex.Pattern.DOTALL);

    /**
     * Screen text that arrives as a plain {@link String} (a widget calling {@code drawString}).
     * A single run wrapped in legacy colour codes ({@code §fName}) is looked up by its plain text,
     * exactly the key the component path produces for a one-colour line, and the same codes are put
     * back around the translation, so the colour is kept and a row stored under the plain key hits.
     * Any other string goes through {@link #translateScreenText} unchanged.
     */
    public TranslationDecision translateScreenString(String text) {
        if (text == null) return translateScreenText(text);
        java.util.regex.Matcher wrapped = LEGACY_WRAPPED.matcher(text);
        if (!wrapped.matches() || text.indexOf('\n') >= 0) return translateScreenText(text);
        TranslationDecision inner = translateScreenText(wrapped.group(2));
        if (!inner.changed()) return TranslationDecision.unchanged(text);
        return TranslationDecision.of(inner.mode(), text,
                wrapped.group(1) + inner.translated() + wrapped.group(3));
    }

    public TranslationDecision translateScreenScanText(String text) {
        String request = screenTextRequest(text);
        return screenTextDecision(text,
                lookup(request, config.screenTextMode, config.aiScreenText, true, false, false,
                        config.aiScreenText));
    }

    /** Ordinary GUI text is prose. Third-party screens often hand us strings that were
     *  already padded for an English visual wrap; sending those gaps through
     *  TranslationTemplate would turn them into fixed WS columns and lock the target
     *  language back into the English word positions. Normalize only this surface at
     *  its boundary. Scoreboards and other true column surfaces keep their raw input. */
    private static String screenTextRequest(String source) {
        return TemplateText.collapseProseLayoutGaps(source);
    }

    private TranslationDecision screenTextDecision(
            String original, TranslationDecision decision) {
        if (decision == null || !decision.changed()) return TranslationDecision.unchanged(original);
        String translated = finalizeScreenText(original, decision.translated());
        return TranslationDecision.of(decision.mode(), original, translated);
    }

    private String finalizeScreenText(String original, String translated) {
        String flowed = TemplateText.collapseProseLayoutGaps(translated);
        return finalizeTranslatedText(LayoutPreserver.matchOuterWhitespace(original, flowed));
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
        // Every pre-existing itemText caller (tooltip/held) already wants P2 enchant-list
        // decomposition; screen text opts in separately (see the 6-arg overload) without
        // taking on itemText's other effects (shouldTranslateItem/requestBatchedPassive/
        // the rarity-line composer).
        return lookup(original, mode, useAi, requireFinalAi, itemText, itemText, true);
    }

    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi,
                                       boolean requireFinalAi, boolean itemText,
                                       boolean enchantList) {
        return lookup(original, mode, useAi, requireFinalAi, itemText, enchantList, true);
    }

    /**
     * @param allowRequest {@code false} for the cache-only item/screen render path (see
     *                      {@link #isManualItemTranslation()}/{@link
     *                      #isManualScreenTranslation()}): a cache miss shows the
     *                      original and nothing is queued — not for the whole line, nor
     *                      for any entity/rarity/enchant sub-unit it is composed from.
     *                      Every other surface passes {@code true}, unchanged.
     */
    private TranslationDecision lookup(String original, DisplayMode mode, boolean useAi,
                                       boolean requireFinalAi, boolean itemText,
                                       boolean enchantList, boolean allowRequest) {
        if (showOriginalOnly || mode == DisplayMode.ORIGINAL_ONLY
                || !(itemText ? shouldTranslateItem(original)
                : TextFilter.shouldTranslate(original, activeTargetLang))) {
            return TranslationDecision.unchanged(original);
        }

        if (itemText && isZhTwOrHk(activeTargetLang)) {
            TranslationDecision composed = composeRarityLine(original, mode, useAi, allowRequest);
            if (composed != null) return composed;
        }
        if (enchantList) {
            // P2 applies to every target language (unlike P1.7's fixed Chinese term
            // table): it only decomposes into ordinary per-name cache requests.
            TranslationDecision enchantComposed = composeEnchantList(original, mode, useAi, allowRequest);
            if (enchantComposed != null) return enchantComposed;
        }
        if (itemText) {
            // Segment cache: a paragraph mixing a rarity line/trade field/stat line/
            // scroll-name list with no blank row between them — composeRarityLine/
            // composeEnchantList above only ever handle the SIMPLE single-row/homogeneous
            // shapes (see their own null contracts), so this only ever runs for text they
            // already declined; see TooltipSegmentPlanner.
            TranslationDecision structured = composeStructuredTooltip(original, mode, useAi, allowRequest);
            if (structured != null) return structured;
        }

        NameMasker.Masked masked = mask(original);
        if (!translatableMasked(masked, itemText)) {
            // Nothing but item names (E slots) besides symbols/P slots: composed locally.
            if (masked.hasEntities()) {
                return composeEntityOnly(original, masked, mode, useAi, itemText, allowRequest);
            }
            return TranslationDecision.unchanged(original);
        }

        TranslationCache selected = cache(useAi);
        String key = masked.text();
        String translated = cachedValue(useAi, key);
        if (!useAi && aiFinalOf(key) != null) selected = ai;
        if (key.equals(translated) && keepsKnownName(key)) return TranslationDecision.unchanged(original);
        if (translated == null) {
            if (allowRequest) {
                if (itemText) selected.requestBatchedPassive(masked.text());
                else selected.requestBatched(masked.text());
                // Queue the line's missing item names alongside (their E slots show English
                // until then; decide() swaps the translated names in on a later frame).
                if (masked.hasEntities()) restore(masked, original, true);
            }
            return TranslationDecision.unchanged(original);
        }
        return decide(original, masked, translated, mode, selected, allowRequest);
    }

    public boolean warmUp(String source) {
        if (config.tooltipMode == DisplayMode.ORIGINAL_ONLY
                || !shouldTranslateItem(source)) return true;
        NameMasker.Masked masked = mask(source);
        if (masked.hasEntities() && !translatableMasked(masked, true)) {
            // Composed from item-name units (requested here when missing), never bought whole.
            return restore(masked, source, true).complete;
        }
        if (!translatableMasked(masked, true)) return true;
        TranslationCache selected = cache(config.aiTooltip);
        return cachedValue(config.aiTooltip, masked.text()) != null
                || selected.translateBlocking(masked.text()) != null;
    }

    public void warmTooltipBatch(List<String> sources) {
        // Manual item mode (config.aiTooltip == false, the machine-translation engine):
        // the hover/render path is cache-only -- see isManualItemTranslation(). Nothing to
        // warm ahead of render; requestItemLines() is the explicit, key-triggered
        // equivalent that still sends. Under the AI engine this always warms, restoring
        // the pre-manual-mode automatic hover behaviour.
        if (!config.aiTooltip) return;
        warmMasked(sources, true, config.tooltipMode, config.aiTooltip, true, true);
    }

    /**
     * Background (low-priority) twin of {@link #warmTooltipBatch} for the all-item
     * warm-up: same "whole item sent, segments stored" path, but queued behind every
     * foreground request. Only meaningful under the AI engine ({@code config.aiTooltip});
     * under machine translation it is a no-op, like {@link #warmTooltipBatch}.
     */
    public void warmTooltipBatchBackground(List<String> sources) {
        if (!config.aiTooltip) return;
        // Dedicated warm-up lane: every unit this call discovers leaves as one request on
        // its own pool, outside the interactive collector and its cooldown.
        TranslationCache.collectWarmLane(() ->
                warmMasked(sources, true, config.tooltipMode, config.aiTooltip, false, true));
    }

    /** Whether chat, tooltips or key-triggered translation are queued or in flight. */
    public boolean isInteractiveTranslationBusy() {
        return google.hasInteractiveWork() || ai.hasInteractiveWork();
    }

    /** Whether the AI engine is a serialized account-authenticated local CLI route. */
    public boolean isSerialLocalAiEngine() {
        return config.usesLocalAiCli();
    }

    /** Compatibility for loader glue not yet exposing Antigravity in its settings screen. */
    @Deprecated
    public boolean isCodexEngine() {
        return isSerialLocalAiEngine();
    }

    /**
     * Whether the item text needs no translation at all (already in the target language,
     * a number, a machine code...): the core's own verdict, the same one the render path uses.
     */
    public boolean isItemTextNativeOrUntranslatable(String source) {
        return source == null || !shouldTranslateItem(source);
    }

    /** Whether the item warm-up can run at all: the AI engine owns item text. */
    public boolean isItemWarmupEngine() {
        return config.aiTooltip && config.tooltipMode != DisplayMode.ORIGINAL_ONLY;
    }

    /** Only selected surfaces participate; warm-up never silently switches their engine. */
    public boolean isContentWarmupEngine() {
        return (config.warmupItems || config.warmupScreenText)
                && (!config.warmupItems || isItemWarmupEngine())
                && (!config.warmupScreenText || config.aiScreenText
                    && config.screenTextMode != DisplayMode.ORIGINAL_ONLY);
    }

    public boolean isWarmupTextNativeOrUntranslatable(
            WarmupCategory category, String source) {
        return category == WarmupCategory.ITEMS
                ? isItemTextNativeOrUntranslatable(source)
                : source == null || !TextFilter.shouldTranslate(source, activeTargetLang);
    }

    public void warmContentBatchBackground(WarmupCategory category,
                                           List<String> sources) {
        if (category == WarmupCategory.ITEMS) {
            warmTooltipBatchBackground(sources);
        } else if (config.aiScreenText) {
            TranslationCache.collectWarmLane(() -> warmMasked(sources, true,
                    config.screenTextMode, config.aiScreenText, false, false, true));
        }
    }

    public boolean isWarmupTranslationReady(WarmupCategory category,
                                             String source) {
        if (category == WarmupCategory.ITEMS) {
            return isTooltipTranslationReady(source);
        }
        if (!wantsScreenTextTranslation(source)) return true;
        EnchantListComposer.Match enchant = EnchantListComposer.match(source);
        if (enchant != null) return enchantListReady(enchant, config.aiScreenText);
        NameMasker.Masked masked = mask(source);
        if (!translatableMasked(masked, false)) {
            return !masked.hasEntities() || restore(masked, source, false).complete
                    || cachedValue(config.aiScreenText, maskPlain(source).text()) != null;
        }
        return cachedValue(config.aiScreenText, masked.text()) != null;
    }

    public boolean isWarmupTranslationPending(WarmupCategory category,
                                               String source) {
        if (category == WarmupCategory.ITEMS) {
            return isTooltipTranslationPending(source);
        }
        if (!wantsScreenTextTranslation(source)) return false;
        EnchantListComposer.Match enchant = EnchantListComposer.match(source);
        if (enchant != null) {
            for (String name : enchant.names()) {
                if (cache(config.aiScreenText).isPending(maskPlain(name).text())) return true;
            }
        }
        NameMasker.Masked masked = mask(source);
        if (cache(config.aiScreenText).isPending(masked.text())) return true;
        for (int index : masked.entitySlots()) {
            ItemEntityRegistry.Entry entry = itemEntities.forCore(masked.names().get(index));
            String key = entry == null ? null : itemUnitKey(entry);
            if (key != null && cache(itemEngine()).isPending(key)) return true;
        }
        return false;
    }

    /**
     * Explicit manual entry point for the item-translation hotkey: sends exactly the
     * lines that are not already cached (dedup happens inside {@link #warmMasked}/
     * {@link TranslationCache#warmBatchAsync}, same as the pre-1.0.8 automatic hover
     * warm), decomposing an enchant-list paragraph into its missing name components.
     * Unlike {@link #warmTooltipBatch} this always sends, independent of {@link
     * #isManualItemTranslation()} -- it exists precisely to be that mode's "send now"
     * action.
     */
    public void requestItemLines(List<String> sources) {
        warmMasked(sources, true, config.tooltipMode, config.aiTooltip, true, true);
    }

    /** Warm every blank-line/indent-delimited paragraph on the current book page in one
     * context-aware request while keeping each paragraph as one translation unit. */
    public void warmBookBatch(List<String> sources) {
        // Manual under the machine engine (see translateBook): reading a book sends nothing.
        if (!config.aiBook) return;
        warmMasked(sources, true, config.bookMode, config.aiBook);
    }

    /** Warm stable per-row scoreboard keys together. The complete sidebar remains AI
     * context, while optional/animated neighbouring rows cannot change a label's cache
     * identity or make its wording flicker. */
    public void warmScoreboardBatch(List<String> sources) {
        // Manual under the machine engine (see translateScoreboardLine): a visible sidebar
        // sends nothing; the HUD scan (beginHudCapture) is what buys it.
        if (!config.aiScoreboard) return;
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
        // P2: an enchant-list paragraph's readiness is about its NAME components (the
        // whole paragraph is essentially never cached under this composer), all-or-none.
        EnchantListComposer.Match enchantMatch = EnchantListComposer.match(source);
        if (enchantMatch != null) return enchantListReady(enchantMatch, config.aiTooltip);
        // Segment cache: only ever reached for text the enchant-list branch above already
        // declined — same layering as composeStructuredTooltip/warmMasked.
        TooltipSegmentPlanner.Plan structuredPlan =
                TooltipSegmentPlanner.plan(source, isZhTwOrHk(activeTargetLang));
        if (structuredPlan != null) {
            return structuredTooltipDisplayable(structuredPlan, source, config.aiTooltip);
        }
        NameMasker.Masked masked = mask(source);
        if (!translatableMasked(masked, true)) {
            if (!masked.hasEntities()) return true;
            // Composed from item names: ready once all are resolved, or when the row the
            // line had before the entity layer can be shown meanwhile (pure checks).
            if (restore(masked, source, false).complete) return true;
            NameMasker.Masked plain = maskPlain(source);
            return !translatableMasked(plain, true)
                    || cachedValue(config.aiTooltip, plain.text()) != null;
        }
        return cachedValue(config.aiTooltip, masked.text()) != null;
    }

    /**
     * Whether a tooltip translation unit (or, for an enchant-list paragraph, any of its
     * name components; or, for an item-name-only line, its item's unit) currently has a
     * request queued or in flight. Used by the tooltip "translating…" hint so it only
     * shows while {@link #requestItemLines} is actually working on this line, never
     * merely because the line is untranslated (that is {@link #isTooltipTranslationReady}
     * returning {@code false}). A pure read: never creates a request.
     */
    public boolean isTooltipTranslationPending(String source) {
        if (source == null || config.tooltipMode == DisplayMode.ORIGINAL_ONLY
                || !shouldTranslateItem(source)) return false;
        EnchantListComposer.Match enchantMatch = EnchantListComposer.match(source);
        if (enchantMatch != null) {
            TranslationCache selected = cache(config.aiTooltip);
            for (String name : enchantMatch.names()) {
                NameMasker.Masked masked = maskPlain(name);
                if (translatableMasked(masked, true) && selected.isPending(masked.text())) return true;
            }
            return false;
        }
        TooltipSegmentPlanner.Plan structuredPlan =
                TooltipSegmentPlanner.plan(source, isZhTwOrHk(activeTargetLang));
        if (structuredPlan != null) {
            return structuredTooltipPending(structuredPlan, source, config.aiTooltip);
        }
        NameMasker.Masked masked = mask(source);
        if (!translatableMasked(masked, true)) {
            if (!masked.hasEntities()) return false;
            TranslationCache units = cache(itemEngine());
            for (int index : masked.entitySlots()) {
                ItemEntityRegistry.Entry entry = itemEntities.forCore(masked.names().get(index));
                String unitKey = entry == null ? null : itemUnitKey(entry);
                if (unitKey != null && units.isPending(unitKey)) return true;
            }
            return false;
        }
        return cache(config.aiTooltip).isPending(masked.text());
    }

    /**
     * Whether a hovered item's tooltip, right now, has NOTHING left outstanding for the
     * R/Y ("retranslate this item") hotkey to simply wait on — decided by reusing {@link
     * #translateItemLine}, the exact same compose path the renderer itself calls, plus
     * {@link #isTooltipTranslationPending} for any line still missing. This is
     * deliberately NOT {@link #isTooltipTranslationReady}: that method is a separate
     * structural "can this be composed at all" predicate (used to gate the
     * "translating…" hint and automatic warm requests) that can, in principle, disagree
     * with what actually renders — the R-key glue needs the ACTUAL displayed state, not a
     * parallel readiness flag, so it asks the same question the screen itself is about to
     * ask.
     *
     * <p>Used by {@link #shouldFullyRetranslateOnKeyPress} (the machine-translation-engine
     * branch of the R/Y policy) to choose between two very different actions:
     * <ul>
     *   <li>{@code false} (at least one line is still showing original, untranslated text,
     *       AND a request for it is currently in flight): only the missing lines should
     *       be bought ({@link #requestItemLines}) — nothing already resolved is
     *       invalidated, so an already-correct segment shared with OTHER items (for
     *       example a common "Buy it now" row) is never thrown away and re-bought just
     *       because a sibling line on THIS tooltip was still missing, and an in-flight
     *       request is never interrupted or duplicated.</li>
     *   <li>{@code true} (every line either already shows SOME translated wording —
     *       whether it came from the AI cache, the shared hub repository, legacy
     *       whole-paragraph lazy conversion, or a segment-composed value — OR has nothing
     *       currently in flight for it): the whole tooltip should be invalidated and
     *       resent ({@link #retranslate}). 2026-10-02 fix: the OLD rule required EVERY
     *       line to already be translated, which made R/Y a permanent no-op whenever even
     *       one line kept failing validation every round — requestItemLines() silently
     *       does nothing for a key that already failed and is sitting in the cache's
     *       exponential failure backoff, so a player pressing R/Y against a tooltip with a
     *       persistently-rejected line saw no effect no matter how many times they
     *       pressed it. The new rule only requires that NOTHING is actively being waited
     *       on — once every still-missing line has already been tried and settled (with
     *       no request outstanding right now), the next press can only mean "give it a
     *       completely fresh try", exactly like the all-translated case always meant
     *       "this wording is wrong, redo it".</li>
     * </ul>
     *
     * <p>A cache miss on a still-untranslated line may still queue an ordinary passive
     * request as a side effect of composing it — the same, unavoidable side effect
     * {@link #translateItemLine} already has on every other render frame; this check never
     * invalidates or clears anything itself. A line with nothing translatable on it at all
     * (blank padding, a bare symbol, {@code ORIGINAL_ONLY} mode, a row this build never
     * even captures because another mod appends it after our own hook runs — such a row
     * is simply absent from {@code lines} to begin with) never counts as evidence either
     * way. An empty/null tooltip, or one with no translatable line at all, is reported
     * {@code false} (never "ready"), which keeps the caller in the safe, non-destructive
     * "request only" branch.
     */
    public boolean isTooltipFullyDisplayedTranslated(List<String> lines) {
        if (lines == null) return false;
        boolean sawTranslatableLine = false;
        // "Fully" means every segment: the R6 partial composition must not count here.
        strictFullTooltip.set(Boolean.TRUE);
        try {
            for (String line : lines) {
                if (line == null || config.tooltipMode == DisplayMode.ORIGINAL_ONLY
                        || !shouldTranslateItem(line)) {
                    continue;
                }
                sawTranslatableLine = true;
                if (translateItemLine(line).changed()) continue;
                if (isTooltipTranslationPending(line)) return false;
            }
        } finally {
            strictFullTooltip.set(Boolean.FALSE);
        }
        return sawTranslatableLine;
    }

    /** Set while a caller needs the all-segments-final answer instead of the R6 partial one. */
    private final ThreadLocal<Boolean> strictFullTooltip = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * R/Y ("retranslate this item") policy entry point: whether the hotkey should
     * invalidate and resend the WHOLE tooltip ({@link #retranslate}) rather than merely
     * buy whatever of {@code lines} is still missing ({@link #requestItemLines}). Read
     * live from {@code config.aiTooltip} (no cached field), so switching engines in the
     * config screen changes this on the very next key press:
     * <ul>
     *   <li>AI engine: item tooltips already auto-translate on hover (see
     *       {@link #warmTooltipBatch}), so a deliberate key press against them can only
     *       mean "this wording is wrong, redo it" — always a full invalidate+resend.</li>
     *   <li>Machine-translation engine (manual/translate-key-driven mode): see
     *       {@link #isTooltipFullyDisplayedTranslated} for the missing-only vs
     *       full-invalidate rule.</li>
     * </ul>
     */
    public boolean shouldFullyRetranslateOnKeyPress(List<String> lines) {
        return config.aiTooltip || isTooltipFullyDisplayedTranslated(lines);
    }

    public void warmNamesBatch(List<String> sources) {
        // Manual item mode (config.aiTooltip == false): container-slot/HUD name pre-warm
        // is cache-only too — merely having an item visible in a slot or hotbar must never
        // send a request by itself. Under the AI engine this always warms.
        if (!config.aiTooltip) return;
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
        // Manual item mode (config.aiTooltip == false): this correction mechanism exists
        // purely to fix up a NAME it may itself re-request (see below) -- it must never
        // fire from merely hovering, so the very next check already excludes it.
        if (!config.aiTooltip || itemName == null || itemName.isBlank()
                || tooltipSources == null || tooltipSources.isEmpty()) return;

        Collection<String> protectedNow = names();
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        ItemEntityRegistry entitiesNow = entitiesNow();
        // A reforge-split name is composed (never its own isolated row): nothing to reconcile.
        NameMasker.Masked maskedName = NameMasker.mask(itemName, protectedNow, termsNow, entitiesNow);
        if (maskedName.hasMasks()) return;
        TranslationCache selected = cache(true);
        // Wording known only from a colour projection (its semantic row was evicted, or is the
        // session copy rebuilt from that projection) is display-only here: before, such a
        // line had no semantic row at all, and reconciling it would buy suffix/context requests.
        if (selected.hasProjectionOnlyWording(maskedName.text())) return;
        String nameTranslation = selected.getCachedFinal(maskedName.text());
        if (nameTranslation == null || nameTranslation.isBlank()) return;

        List<String> context = new ArrayList<>(tooltipSources.size());
        String mismatchSource = null;
        String mismatchTranslation = null;
        for (String source : tooltipSources) {
            if (source == null) continue;
            NameMasker.Masked maskedSource = NameMasker.mask(source, protectedNow, termsNow, entitiesNow);
            context.add(maskedSource.hasEntities()
                    ? NameMasker.mask(source, protectedNow, termsNow).text() : maskedSource.text());
            String comparableSource = TextFilter.stripFormatting(source);
            if (!startsWithItemName(comparableSource, itemName)) continue;
            // An item-name E slot shows exactly that item's own translation by construction.
            if (maskedSource.hasEntities()) continue;
            if (selected.hasProjectionOnlyWording(maskedSource.text())) continue;
            // Compare restored wording: the unmasked source is compared the same way.
            String lineTranslation = NameMasker.unmask(
                    selected.getCachedFinal(maskedSource.text()), maskedSource.names());
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
        // The suffix is requested on its own, so it is masked like every other request.
        NameMasker.Masked maskedSuffix = NameMasker.mask(suffix, protectedNow, termsNow);
        String suffixTranslation = NameMasker.unmask(
                selected.getCachedFinal(maskedSuffix.text()), maskedSuffix.names());
        if (suffixTranslation == null) {
            // Usually a shared template such as "Damage: [n]"; one short request can
            // reconcile the names of every weapon using the same suffix.
            selected.requestBatched(maskedSuffix.text());
            return;
        }
        String authoritative = contextualPrefix(mismatchTranslation, suffixTranslation);
        if (authoritative != null) {
            // Remove a possible GT fallback copy, then atomically replace the AI key.
            // Cache-only mode keeps the GT row: it could not be re-requested if the
            // local replacement below fails, and a final AI row outranks it anyway.
            if (requestsEnabled()) google.invalidate(maskedName.text());
            if (selected.replaceFinal(maskedName.text(), authoritative)) {
                rememberContextualItemRetry(retryKey);
                return;
            }
        }

        // Cache-only mode: the retry below deletes the name rows to re-ask the AI, which
        // cannot be re-requested now. Keep the current wording and the unused retry.
        if (!requestsEnabled()) return;
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
        // Every pre-existing itemText caller already wants P2 warm; a non-itemText caller
        // that wants it too (screen text's explicit rescan) uses the 7-arg overload below.
        warmMasked(sources, includeContext, mode, useAi, highPriority, itemText, itemText);
    }

    private void warmMasked(List<String> sources, boolean includeContext,
                            DisplayMode mode, boolean useAi, boolean highPriority,
                            boolean itemText, boolean enchantList) {
        if (mode == DisplayMode.ORIGINAL_ONLY || sources == null) return;
        Collection<String> protectedNow = names();
        DoNotTranslateMatcher termsNow = doNotTranslateTerms();
        ItemEntityRegistry entitiesNow = entitiesNow();
        List<String> todo = new ArrayList<>();
        List<String> context = includeContext ? new ArrayList<>() : null;
        // Tooltip hover batch: sources[0] is normally the item's own verified title (see
        // NyanLexFabric#tooltipParagraphPlan, which isolates row 0 into its own group
        // when it matches the stack's hover name) riding alongside the body paragraph(s) in
        // the SAME warmTooltipBatch/requestItemLines call — read it here (no new glue
        // surface needed) so a structured body segment's AI context still says what item
        // this is ("整件送" — 2026-10-01 review), without resending the whole raw paragraph
        // (see structuredTooltipContext).
        String titleHint = null;
        if (includeContext && itemText) {
            for (String candidate : sources) {
                if (candidate != null && !candidate.isBlank()) {
                    titleHint = candidate;
                    break;
                }
            }
        }
        LinkedHashSet<String> itemUnits = null;
        for (String source : sources) {
            if (source == null) continue;
            if (enchantList) {
                EnchantListComposer.Match enchantMatch = EnchantListComposer.match(source);
                if (enchantMatch != null) {
                    // Never queue the whole paragraph (see composeEnchantList): warm just
                    // the missing NAME components. 2026-10-01 review: this source's OWN
                    // text must NOT be added to the outer shared `context` list here — that
                    // used to hand the WHOLE raw paragraph to every OTHER source in this
                    // same warm call (e.g. the item's title) as ITS surface context,
                    // reintroducing the exact "whole blob resent as context" bloat this
                    // decomposition exists to avoid. The per-name request context stays as
                    // is (see warmEnchantListComponents); only this cross-source leak is cut.
                    warmEnchantListComponents(enchantMatch, source, useAi, highPriority);
                    continue;
                }
            }
            if (itemText) {
                // Segment cache: only ever reached for text the enchantList branch above
                // already declined (a pure homogeneous enchant list never falls through
                // here — see composeStructuredTooltip's layering note).
                TooltipSegmentPlanner.Plan plan =
                        TooltipSegmentPlanner.plan(source, isZhTwOrHk(activeTargetLang));
                if (plan != null) {
                    // Never use the title AS ITS OWN context (a lone-source call, or the
                    // title row itself somehow matching a Plan, would otherwise reintroduce
                    // the whole-paragraph-as-context problem this exists to avoid).
                    String effectiveTitleHint =
                            titleHint != null && !titleHint.equals(source) ? titleHint : null;
                    // Computed ONCE: used both as this item's segment-request context AND
                    // (deduped-friendly, already unit-key-shaped, never the raw paragraph)
                    // contributed to the outer shared `context` so a SIBLING source in this
                    // same warm call (the title) still gets a small, useful cross-reference
                    // instead of either nothing or the whole blob.
                    List<String> segmentContext = structuredTooltipContext(plan, source, effectiveTitleHint);
                    if (context != null) context.addAll(segmentContext);
                    // Lazy legacy-whole-paragraph conversion runs here too (not just on the
                    // render path's composeStructuredTooltip), so the FIRST hover of an item
                    // an older build already translated as one opaque unit does not waste a
                    // fresh request on a segment that whole-paragraph row could supply for
                    // free -- see convertLegacyWholeCache.
                    peekLegacyWholeCacheForWarm(source, plan, useAi);
                    warmStructuredTooltipComponents(plan, source, useAi, highPriority, segmentContext);
                    continue;
                }
            }
            NameMasker.Masked maskedSource =
                    NameMasker.mask(source, protectedNow, termsNow, entitiesNow);
            String masked = maskedSource.text();
            if (maskedSource.hasEntities()) {
                // Context keeps the item names readable (P-only mask of the same line).
                if (context != null) context.add(NameMasker.mask(source, protectedNow, termsNow).text());
                for (int index : maskedSource.entitySlots()) {
                    ItemEntityRegistry.Entry entry = itemEntities.forCore(maskedSource.names().get(index));
                    if (entry == null || itemNameTranslation(entry) != null) continue;
                    String unitKey = itemUnitKey(entry);
                    if (unitKey == null) continue;
                    if (itemUnits == null) itemUnits = new LinkedHashSet<>();
                    itemUnits.add(unitKey);
                }
                // A line of nothing but item names is composed locally: never bought whole.
                if (translatableMasked(maskedSource, itemText)) todo.add(masked);
                continue;
            }
            if (context != null) context.add(masked);
            boolean translatable = maskedSource.hasMasks()
                    ? translatableMasked(maskedSource, itemText)
                    : itemText ? shouldTranslateItem(masked)
                    : TextFilter.shouldTranslate(masked, activeTargetLang);
            if (translatable) todo.add(masked);
        }
        if (itemUnits != null && cache(itemEngine()) == cache(useAi)) {
            // Same engine: the new item-name units ride in this very batch.
            for (String unit : itemUnits) if (!todo.contains(unit)) todo.add(unit);
            itemUnits = null;
        }
        // 2026-10-01 review: a source that reaches the generic path (typically the item's
        // title) may have self-added its own masked text AND separately received a
        // structured sibling's already-equal contribution (see the itemText branch above),
        // making this list a SUPERSET of that sibling's own context by one duplicate line —
        // which breaks OpenAiTranslator's List#equals-based context-run coalescing
        // (groupedOrder/buildContextBlocks) and buys a second, redundant context block in
        // the SAME physical request. Deduping here (order-preserving) is always safe and
        // makes the two lists compare equal again whenever their DISTINCT content already
        // matched.
        List<String> dedupedContext = dedupePreservingOrder(context);
        // The warm path must consult the same external cache tiers as the render path
        // before the selected engine collects its misses.
        todo = dropAnsweredBeforeRequest(useAi, todo);
        if (!todo.isEmpty()) {
            TranslationCache selected = cache(useAi);
            if (highPriority) selected.warmBatchAsyncHigh(todo, dedupedContext);
            else selected.warmBatchAsync(todo, dedupedContext);
        }
        if (itemUnits != null) {
            TranslationCache units = cache(itemEngine());
            List<String> unitList = dropAnsweredBeforeRequest(itemEngine(), new ArrayList<>(itemUnits));
            if (unitList.isEmpty()) return;
            if (highPriority) units.warmBatchAsyncHigh(unitList, dedupedContext);
            else units.warmBatchAsync(unitList, dedupedContext);
        }
    }

    /** Order-preserving de-duplication (first occurrence kept); {@code null}/short lists
     *  pass through unchanged (no allocation). See the review note above its caller. */
    private static List<String> dedupePreservingOrder(List<String> list) {
        if (list == null || list.size() < 2) return list;
        LinkedHashSet<String> deduped = new LinkedHashSet<>(list);
        return deduped.size() == list.size() ? list : new ArrayList<>(deduped);
    }

    private TranslationDecision decide(String original, NameMasker.Masked masked,
                                       String cachedTranslation, DisplayMode mode,
                                       TranslationCache selected) {
        return decide(original, masked, cachedTranslation, mode, selected, true);
    }

    /**
     * @param allowRequest whether an unresolved item-name E slot inside this line may
     *                      queue its missing unit (see {@link #restore}). {@code false}
     *                      for the cache-only item/screen render path, so a hit on the
     *                      OUTER line never sends a request for an inner one.
     */
    private TranslationDecision decide(String original, NameMasker.Masked masked,
                                       String cachedTranslation, DisplayMode mode,
                                       TranslationCache selected, boolean allowRequest) {
        boolean styleFallback = TextFilter.isStyleFallback(cachedTranslation);
        String maskedSemantic = TextFilter.stripStyleFallback(cachedTranslation);
        // P slots restore verbatim; an item-name E slot restores to that item's one
        // translation (English until its unit lands — queued here — then the next frame).
        String semantic = NameMasker.unmask(maskedSemantic, restoreValues(masked, original, allowRequest));
        // Half-transliteration is judged on the MASKED pair (the cache validates the same
        // pair): a protected term kept verbatim before CJK ("SkyBlock's best island" ->
        // "SkyBlock的最佳島嶼", "Steve's sword" -> "Steve的劍") is not a half-converted word.
        if (!meaningful(original, semantic)
                || TextFilter.isPartialTransliteration(masked.text(), maskedSemantic)) {
            return TranslationDecision.unchanged(original);
        }
        if (masked.hasMasks() && (!protectedTermsSurvive(masked, semantic)
                || !listedNamesSurvive(original, semantic))) {
            // Wording known only from a colour projection was never displayed before 1.0.7:
            // show the original as then, without deleting or re-buying it. That includes a
            // GT projection read through while the AI tier has no final wording of its own.
            boolean projectionOnly = selected.hasProjectionOnlyWording(masked.text())
                    || (selected == ai && google != ai && ai.getCachedFinal(masked.text()) == null
                    && google.hasProjectionOnlyWording(masked.text()));
            if (!projectionOnly) invalidateMangledOnce(original, masked.text());
            return TranslationDecision.unchanged(original);
        }
        String laidOut = finalizeTranslatedText(
                LayoutPreserver.matchOuterWhitespace(original, semantic));
        return TranslationDecision.of(mode, original,
                styleFallback ? TextFilter.markStyleFallback(laidOut) : laidOut);
    }

    private static boolean meaningful(String source, String translated) {
        return translated != null && !translated.isEmpty()
                && !translated.equals(source)
                && !translated.trim().equals(source == null ? "" : source.trim());
    }

    /**
     * R17: every masked original — TAB player ID or do-not-translate term — must still be
     * present, as a whole word, in the restored translation. A backend that lost or
     * rewrote a placeholder must never display the damaged wording.
     *
     * <p>S2: a name that only a {@code PlayerNamePatterns} frame isolated must merely be
     * present. Its family key is shared by every seller/rival, and the translators
     * already require each {@code ⟦n⟧} exactly once (AI token multiset, GT sentinel
     * restore), so the whole-word rule would only misfire when the translation glues
     * the restored name to a neighbouring value ({@code wi11} + {@code 5} ->
     * {@code wi115}) — and then evict the shared family row once per new seller.</p>
     *
     * <p>B7: only P slots are checked. An item-name E slot is restored to the item's
     * TRANSLATED name, so its English original is legitimately absent; its placeholder's
     * presence is what the translators' token validation (tokensMatch) guarantees.</p>
     */
    private static boolean protectedTermsSurvive(NameMasker.Masked masked, String translated) {
        if (translated == null) return false;
        List<String> patternNames = masked.patternNames();
        List<String> names = masked.names();
        for (int index = 0; index < names.size(); index++) {
            if (masked.isEntitySlot(index)) continue;
            String term = names.get(index);
            if (!patternNames.isEmpty() && patternNames.contains(term)) {
                if (translated.indexOf(term) < 0) return false;
            } else if (!containsWholeTerm(translated, term)) {
                return false;
            }
        }
        return true;
    }

    /**
     * R17 (unchanged pre-1.0.7 rule): every TAB-listed ID that appears in the original as
     * an ASCII word must survive, even one the masker could not isolate (for example a
     * name glued to CJK text, {@code Steve說…}). Only the supplied set's {@code contains}
     * is used, so a large live name set is never copied.
     */
    private boolean listedNamesSurvive(String original, String translated) {
        Collection<String> current = names();
        if (current.isEmpty()) return true;
        Set<?> protectedSet = current instanceof Set<?> ? (Set<?>) current
                : new java.util.HashSet<>(current);
        for (int i = 0; i < original.length(); ) {
            if (!nameCharacter(original.charAt(i))) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < original.length() && nameCharacter(original.charAt(end))) end++;
            String token = original.substring(i, end);
            if (protectedSet.contains(token) && !containsWholeTerm(translated, token)) return false;
            i = end;
        }
        return true;
    }

    /** Word boundaries apply only at an edge whose own character is a word character. */
    private static boolean containsWholeTerm(String text, String term) {
        if (term == null || term.isEmpty()) return true;
        boolean wordStart = nameCharacter(term.charAt(0));
        boolean wordEnd = nameCharacter(term.charAt(term.length() - 1));
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + 1)) {
            int end = at + term.length();
            if ((!wordStart || at == 0 || !nameCharacter(text.charAt(at - 1)))
                    && (!wordEnd || end == text.length() || !nameCharacter(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameCharacter(char c) {
        return c == '_' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                || c >= '0' && c <= '9';
    }

    /** Evict the damaged row once per original: exactly the key that was just decided
     *  ({@code maskedKey} — an entity-layer key, a pre-entity peek key or a unit key). */
    private void invalidateMangledOnce(String original, String maskedKey) {
        if (invalidatedNameFailures.size() >= 512) invalidatedNameFailures.clear();
        if (!invalidatedNameFailures.add(original)) return;
        invalidateBoth(original);
        if (maskedKey != null && !maskedKey.equals(original)) invalidateBoth(maskedKey);
    }
}
