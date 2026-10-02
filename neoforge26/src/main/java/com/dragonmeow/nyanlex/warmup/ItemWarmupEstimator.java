package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.cache.BatchBudget;
import com.dragonmeow.nyanlex.translate.SessionTokenUsage;

import java.util.HashSet;
import java.util.Set;

/**
 * Accumulates a dry-run scan (feed each probed item to {@link #add}) and turns it into
 * an {@link ItemWarmupPlan}.
 *
 * <p>Formula: units shared between items are counted once. Requests are counted by packing
 * the missing items exactly like the run does ({@link BatchBudget}, the shared request
 * character budget; an item is atomic and one larger than the budget travels alone).
 * {@code input = chars / 3.5 + requests * PROMPT_OVERHEAD}; {@code output = chars / 2.5};
 * {@code tokens = input + output}. When this session already made at least
 * {@link #MIN_CALIBRATION_REQUESTS} requests the result is multiplied by
 * {@code clamp(measuredTokensPerRequest / defaultTokensPerRequest, 0.25, 4)}. Items that
 * need no translation (already in the target language) are not counted as missing.</p>
 *
 * <p>The time figure assumes {@value #ASSUMED_LATENCY_SECONDS} s per request, the run's
 * dispatch interval and its concurrency (1 for ChatGPT sign-in, otherwise up to
 * {@value ItemWarmupDriver#MAX_CONCURRENCY}); real latency decides the actual speed.</p>
 */
public final class ItemWarmupEstimator {
    public static final int PROMPT_OVERHEAD_TOKENS = 700;
    public static final long MIN_CALIBRATION_REQUESTS = 5;
    public static final double ASSUMED_LATENCY_SECONDS = 10.0;

    private final Set<String> missingUnits = new HashSet<>();
    private final ItemWarmupBackend backend;
    private int total;
    private int cached;
    private int nativeItems;
    private int missing;
    private long missingChars;
    private int requests;
    private BatchBudget pack;

    public ItemWarmupEstimator(ItemWarmupBackend backend) {
        this.backend = backend;
    }

    public void add(ItemWarmupTarget target) {
        total++;
        int cost = 0;
        long chars = 0;
        Set<String> fresh = new HashSet<>();
        boolean itemMissing = false;
        boolean anyUnit = false;
        boolean allNative = true;
        for (String source : target.sources()) {
            if (source == null || source.isBlank()) continue;
            anyUnit = true;
            if (!backend.needsNoTranslation(source)) allNative = false;
            if (backend.isReady(source)) continue;
            itemMissing = true;
            String key = source.strip();
            if (!missingUnits.contains(key) && fresh.add(key)) {
                cost += BatchBudget.unitChars(key);
                chars += key.length();
            }
        }
        if (!itemMissing) {
            if (anyUnit && allNative) nativeItems++;
            else cached++;
            return;
        }
        missing++;
        missingUnits.addAll(fresh);
        missingChars += chars;
        if (pack == null || !pack.fits(cost)
                || pack.count() >= ItemWarmupDriver.MAX_ITEMS_PER_BATCH) {
            pack = BatchBudget.windowed();
            requests++;
        }
        for (String key : fresh) pack.add(BatchBudget.unitChars(key));
        if (fresh.isEmpty()) pack.add(0);
    }

    /** @param maxItemsPerSession per-launch cap, {@code 0} = unlimited */
    public ItemWarmupPlan build(int maxItemsPerSession, SessionTokenUsage.Snapshot usage) {
        int willSubmit = maxItemsPerSession > 0 ? Math.min(missing, maxItemsPerSession) : missing;
        double share = missing == 0 ? 0 : willSubmit / (double) missing;
        // Chars and requests scale with the share of missing items we will actually submit.
        long chars = (long) Math.ceil(missingChars * share);
        int plannedRequests = willSubmit == 0 ? 0 : (int) Math.max(1, Math.ceil(requests * share));
        double input = chars / 3.5 + (double) plannedRequests * PROMPT_OVERHEAD_TOKENS;
        double output = chars / 2.5;
        double tokens = input + output;
        boolean calibrated = false;
        if (usage != null && usage.requests() >= MIN_CALIBRATION_REQUESTS
                && usage.totalTokens() > 0 && plannedRequests > 0) {
            double measured = usage.totalTokens() / (double) usage.requests();
            double base = tokens / plannedRequests;
            double scale = Math.max(0.25, Math.min(4.0, measured / base));
            tokens *= scale;
            calibrated = true;
        }
        int concurrency = backend.usesCodex() ? 1 : ItemWarmupDriver.MAX_CONCURRENCY;
        double secondsPerRequest = Math.max(1.5, ASSUMED_LATENCY_SECONDS / concurrency);
        int minutes = plannedRequests == 0 ? 0
                : (int) Math.max(1, Math.ceil(plannedRequests * secondsPerRequest / 60.0));
        return new ItemWarmupPlan(total, cached, missing, missingUnits.size(), missingChars,
                willSubmit, plannedRequests, Math.round(tokens), calibrated, nativeItems, minutes);
    }
}
