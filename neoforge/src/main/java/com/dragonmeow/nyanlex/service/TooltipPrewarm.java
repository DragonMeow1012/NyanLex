package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the tooltip render pipeline once, off the render thread, right after startup.
 *
 * <p>The first tooltip the player points at used to pay for loading and initialising the whole
 * pipeline (a dozen classes, their regex tables, the JIT's first pass): measured at roughly
 * 50 ms for one 40-line frame, against a fifth of a millisecond once warm. That is a visible
 * hitch on exactly the first hover. This pushes a few representative lines through a
 * throw-away service on a low-priority daemon thread, so the cost is paid while the game is
 * still loading.</p>
 *
 * <p>Nothing leaves the process: the throw-away service has requests switched off (the
 * default of a fresh {@link TranslatorConfig}), an engine that is never called, an executor
 * that drops everything, and no store. The shared memos it fills are pure and bounded.</p>
 */
final class TooltipPrewarm {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static final List<String> SAMPLES = Arrays.asList(
            "Gear Score: 912 (1,243)",
            "⟦CS0⟧Damage: ⟦/CS0⟧⟦CS1⟧+319 (+30)⟦/CS1⟧",
            "⟦CS0⟧Strength: ⟦/CS0⟧⟦CS1⟧+281⟦/CS1⟧",
            "Ultimate Wise V, One For All I, Sharpness VI, Giant Killer VII, Cleave VI",
            "Item Ability: Wither Impact RIGHT CLICK",
            "Teleport 10 blocks ahead of you. Then implode dealing 10,000 damage to nearby enemies.",
            "Mana Cost: 300",
            "Requires Catacombs Dungeon Level 12",
            "Sell Price: 1,000,000 Coins",
            "MYTHIC DUNGEON SWORD",
            "Seller: Steve");

    private TooltipPrewarm() {
    }

    /** Starts the warm-up once per process; later calls do nothing. */
    static void startOnce() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(TooltipPrewarm::run, "nyanlex-prewarm");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** The warm-up itself (also called directly by the frame-cost test). Never throws. */
    static void run() {
        try {
            TranslatorConfig config = new TranslatorConfig();
            config.targetLang = "zh-TW";
            config.tooltipMode = DisplayMode.TRANSLATION;
            config.aiTooltip = true;
            config.translationRequestsEnabled = false; // cache-only: nothing can be queued or sent
            Translator never = new Translator() {
                @Override
                public TranslationResult translate(String text, String targetLang) throws TranslationException {
                    throw new TranslationException("prewarm never sends");
                }
            };
            TranslationCache google = new TranslationCache(never, config.targetLang, task -> { }, 256);
            TranslationCache ai = new TranslationCache(never, config.targetLang, task -> { }, 256);
            TranslationService service = new TranslationService(config, google, ai);
            // The hub read-through hashes every key it asks for: load the SHA-256 provider now.
            service.setHubLookup(key -> HubKeyHash.of(key).isEmpty() ? "" : null);
            for (int frame = 0; frame < 3; frame++) {
                service.warmTooltipBatch(SAMPLES);
                for (String line : SAMPLES) service.translateItemLine(line);
                service.translateScreenText(SAMPLES.get(0));
            }
        } catch (Throwable ignored) {
            // A warm-up is an optimisation only; whatever goes wrong here must never be visible.
        }
    }
}
