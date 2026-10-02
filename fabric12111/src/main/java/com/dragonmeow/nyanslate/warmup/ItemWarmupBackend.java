package com.dragonmeow.nyanslate.warmup;

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
    boolean isReady(String source);

    /** The unit currently has a request queued or in flight. */
    boolean isPending(String source);

    /** Submit the units at background (lowest) priority; never blocks. */
    void warm(List<String> sources);
}
