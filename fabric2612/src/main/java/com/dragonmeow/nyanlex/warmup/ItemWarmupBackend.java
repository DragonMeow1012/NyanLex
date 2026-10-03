package com.dragonmeow.nyanlex.warmup;

import java.util.List;

/** Translation-side port the warm-up drives (implemented by the loader over TranslationService). */
public interface ItemWarmupBackend {
    /** True only for the AI engine; machine translation never warms ahead. */
    boolean isAiEngine();

    /** The master request switch. */
    boolean requestsEnabled();

    /** The shared 429 circuit breaker of the AI engine is open. */
    boolean isRateLimited();

    /** The unit already reached a terminal cache state (translated or kept original). */
    boolean isReady(WarmupCategory category, String source);

    /** The unit currently has a request queued or in flight. */
    boolean isPending(WarmupCategory category, String source);

    /** Submit the units at background (lowest) priority; never blocks. */
    void warm(WarmupCategory category, List<String> sources);

    /**
     * Chat, tooltip or key-triggered translation is queued or in flight: the warm-up holds
     * back new requests until it is done. Backends without the notion never yield.
     */
    default boolean interactiveBusy() {
        return false;
    }

    /** The AI engine is the ChatGPT (Codex) sign-in: the warm-up keeps one request at a time. */
    default boolean usesCodex() {
        return false;
    }

    /**
     * The unit needs no translation at all (already in the target language, a number, a
     * machine code): the core's own verdict, never sent.
     */
    default boolean needsNoTranslation(WarmupCategory category, String source) {
        return false;
    }
}
