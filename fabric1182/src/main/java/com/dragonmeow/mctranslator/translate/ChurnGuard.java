package com.dragonmeow.mctranslator.translate;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Frequency detector for "churning" text: animated / flashing server decorations
 * ({@code »» VOTE ««} ↔ {@code »»» VOTE «««}) and countdown fragments that
 * {@link TemplateText} could not normalise away. Every micro-variant of such a line
 * is a distinct request key, so one scoreboard animation can mint a new HTTP request
 * per second and rate-limit (429) the whole backend.
 *
 * <p>Detection is signature-based: a key's <b>signature</b> is what remains after
 * dropping all {@code ⟦…⟧} tokens and every non-letter character (case-folded).
 * Cosmetic variants of one line share a signature while carrying distinct keys.
 * When one signature accumulates {@code variantThreshold} distinct keys inside a
 * sliding {@code windowMs} window, the signature is put on cooldown and
 * {@link #shouldSuppress} answers {@code true} until {@code cooldownMs} passes —
 * new requests are silently dropped (the surface simply keeps showing the original
 * text); already-cached translations are untouched because the cache consults
 * this guard only when it is about to enqueue a miss.</p>
 *
 * <p>Minecraft-free and deterministic: the clock is injected so tests drive time
 * with a fake {@link LongSupplier}.</p>
 */
public final class ChurnGuard {

    public static final int DEFAULT_VARIANT_THRESHOLD = 4;
    public static final long DEFAULT_WINDOW_MS = 60_000L;
    public static final long DEFAULT_COOLDOWN_MS = 300_000L;

    /** Hard bounds for signature and per-signature variant state. */
    private static final int MAX_SIGNATURES = 512;

    private static final Pattern ANY_TOKEN = Pattern.compile("⟦[^⟦⟧]*⟧");

    private final int variantThreshold;
    private final long windowMs;
    private final long cooldownMs;
    private final LongSupplier clock;

    private final Map<String, Entry> bySignature = new ConcurrentHashMap<>();
    private final Object evictionLock = new Object();

    /** Per-signature state: distinct keys seen inside the window (with their last-seen
     *  time, so stale ones slide out) and the cooldown deadline once tripped. */
    private static final class Entry {
        final Map<String, Long> variantSeenAt = new HashMap<>();
        long cooldownUntil;
        long lastSeenAt;
    }

    public ChurnGuard() {
        this(DEFAULT_VARIANT_THRESHOLD, DEFAULT_WINDOW_MS, DEFAULT_COOLDOWN_MS, System::currentTimeMillis);
    }

    public ChurnGuard(int variantThreshold, long windowMs, long cooldownMs, LongSupplier clock) {
        this.variantThreshold = Math.max(2, variantThreshold);
        this.windowMs = Math.max(1L, windowMs);
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.clock = clock;
    }

    /**
     * Record one about-to-be-enqueued request key and decide whether to drop it.
     * Recording continues DURING cooldown, so an animation that never stops churning
     * re-trips the guard the moment its cooldown expires instead of buying another
     * burst of doomed requests.
     *
     * @param requestKey the normalised/templated cache key about to be requested
     * @return {@code true} when the key's signature is churning (or cooling down)
     *         and the request must be silently dropped
     */
    public boolean shouldSuppress(String requestKey) {
        if (requestKey == null || requestKey.isEmpty()) return false;
        String signature = signatureOf(requestKey);
        if (signature.isEmpty()) return false; // letter-free line: TextFilter's problem, not ours
        if (bySignature.size() >= MAX_SIGNATURES && !bySignature.containsKey(signature)) {
            evictOneSignature(clock.getAsLong());
        }
        Entry entry = bySignature.computeIfAbsent(signature, ignored -> new Entry());
        long now = clock.getAsLong();
        synchronized (entry) {
            Iterator<Map.Entry<String, Long>> it = entry.variantSeenAt.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > windowMs) it.remove();
            }
            entry.variantSeenAt.put(requestKey, now);
            entry.lastSeenAt = now;
            while (entry.variantSeenAt.size() > variantThreshold) {
                String oldest = entry.variantSeenAt.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (oldest == null) break;
                entry.variantSeenAt.remove(oldest);
            }
            if (now < entry.cooldownUntil) return true;
            if (entry.variantSeenAt.size() >= variantThreshold) {
                entry.cooldownUntil = now + cooldownMs;
                return true;
            }
            return false;
        }
    }

    /** Evict one least-recent settled signature; if every entry is cooling, evict only
     *  the least-recent one. Never clear the whole table and release every animation. */
    private void evictOneSignature(long now) {
        synchronized (evictionLock) {
            if (bySignature.size() < MAX_SIGNATURES) return;
            String candidate = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> item : bySignature.entrySet()) {
                Entry entry = item.getValue();
                synchronized (entry) {
                    if (now >= entry.cooldownUntil && entry.lastSeenAt < oldest) {
                        oldest = entry.lastSeenAt;
                        candidate = item.getKey();
                    }
                }
            }
            if (candidate == null) {
                for (Map.Entry<String, Entry> item : bySignature.entrySet()) {
                    Entry entry = item.getValue();
                    synchronized (entry) {
                        if (entry.lastSeenAt < oldest) {
                            oldest = entry.lastSeenAt;
                            candidate = item.getKey();
                        }
                    }
                }
            }
            if (candidate != null) bySignature.remove(candidate);
        }
    }

    /** A key's churn signature: {@code ⟦…⟧} tokens dropped, then only Unicode LETTERS
     *  (CJK included) kept, lower-cased — punctuation/digit/whitespace churn collapses. */
    public static String signatureOf(String key) {
        String noTokens = ANY_TOKEN.matcher(key).replaceAll("");
        StringBuilder sb = new StringBuilder(noTokens.length());
        for (int i = 0; i < noTokens.length(); ) {
            int cp = noTokens.codePointAt(i);
            if (Character.isLetter(cp)) sb.appendCodePoint(Character.toLowerCase(cp));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** Number of tracked signatures (test hook for the size cap). */
    public int signatureCount() {
        return bySignature.size();
    }
}
