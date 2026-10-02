package com.dragonmeow.nyanslate.warmup;

import com.dragonmeow.nyanslate.translate.SessionTokenUsage;

import java.util.HashSet;
import java.util.Set;

/**
 * Accumulates a dry-run scan (feed each probed item to {@link #add}) and turns it into
 * an {@link ItemWarmupPlan}.
 *
 * <p>Formula (documented in ITEM-WARMUP-REPORT.md): units shared between items are
 * counted once. {@code requests = max(ceil(willSubmitItems / ITEMS_PER_REQUEST),
 * ceil(chars / CHARS_PER_REQUEST))}; {@code input = chars / 3.5 +
 * requests * PROMPT_OVERHEAD}; {@code output = chars / 2.5};
 * {@code tokens = input + output}. When this session already made at least
 * {@link #MIN_CALIBRATION_REQUESTS} requests the result is multiplied by
 * {@code clamp(measuredTokensPerRequest / defaultTokensPerRequest, 0.25, 4)}.</p>
 */
public final class ItemWarmupEstimator {
    public static final int ITEMS_PER_REQUEST = 8;
    public static final int CHARS_PER_REQUEST = 6000;
    public static final int PROMPT_OVERHEAD_TOKENS = 700;
    public static final long MIN_CALIBRATION_REQUESTS = 5;

    private final Set<String> missingUnits = new HashSet<>();
    private final ItemWarmupBackend backend;
    private int total;
    private int cached;
    private int missing;
    private long missingChars;

    public ItemWarmupEstimator(ItemWarmupBackend backend) {
        this.backend = backend;
    }

    public void add(ItemWarmupTarget target) {
        total++;
        boolean itemMissing = false;
        for (String source : target.sources()) {
            if (source == null || source.isBlank()) continue;
            if (backend.isReady(source)) continue;
            itemMissing = true;
            String key = source.strip();
            if (missingUnits.add(key)) missingChars += key.length();
        }
        if (itemMissing) missing++;
        else cached++;
    }

    public ItemWarmupPlan build(int maxItemsPerSession, SessionTokenUsage.Snapshot usage) {
        int willSubmit = Math.min(missing, Math.max(0, maxItemsPerSession));
        // Chars scale with the share of missing items we will actually submit.
        long chars = missing == 0 ? 0
                : (long) Math.ceil(missingChars * (willSubmit / (double) missing));
        int requests = willSubmit == 0 ? 0 : (int) Math.max(
                Math.ceil(willSubmit / (double) ITEMS_PER_REQUEST),
                Math.ceil(chars / (double) CHARS_PER_REQUEST));
        double input = chars / 3.5 + (double) requests * PROMPT_OVERHEAD_TOKENS;
        double output = chars / 2.5;
        double tokens = input + output;
        boolean calibrated = false;
        if (usage != null && usage.requests() >= MIN_CALIBRATION_REQUESTS
                && usage.totalTokens() > 0 && requests > 0) {
            double measured = usage.totalTokens() / (double) usage.requests();
            double base = tokens / requests;
            double scale = Math.max(0.25, Math.min(4.0, measured / base));
            tokens *= scale;
            calibrated = true;
        }
        return new ItemWarmupPlan(total, cached, missing, missingUnits.size(), missingChars,
                willSubmit, requests, Math.round(tokens), calibrated);
    }
}
