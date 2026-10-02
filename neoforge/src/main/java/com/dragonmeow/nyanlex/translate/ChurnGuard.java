package com.dragonmeow.nyanlex.translate;

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
 * When one signature accumulates {@code variantThreshold} distinct text variants (keys
 * compared without their {@code ⟦CS⟧} colour markers) inside a sliding
 * {@code windowMs} window — or one text keeps changing only its colour topology that
 * often — the signature is put on cooldown and
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

    // signatureOf/variantOf are pure and run for every missing line on every frame.
    private static final LruMemo<String> SIGNATURES = new LruMemo<>(4096);
    private static final LruMemo<String> VARIANTS = new LruMemo<>(4096);

    private final int variantThreshold;
    private final long windowMs;
    private final long cooldownMs;
    private final LongSupplier clock;

    private final Map<String, Entry> bySignature = new ConcurrentHashMap<>();
    private final Object evictionLock = new Object();

    /** Per-signature state: distinct TEXT variants (key with colour markers removed) and
     *  distinct exact keys (colour topologies) seen inside the window, each with its
     *  last-seen time so stale ones slide out, plus the cooldown deadline once tripped. */
    private static final class Entry {
        final Map<String, Long> variantSeenAt = new HashMap<>();
        final Map<String, Long> topologySeenAt = new HashMap<>();
        final Map<String, String> topologyVariant = new HashMap<>();
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
        String variant = variantOf(requestKey);
        synchronized (entry) {
            slideOut(entry.variantSeenAt, now, null);
            slideOut(entry.topologySeenAt, now, entry.topologyVariant);
            entry.variantSeenAt.put(variant, now);
            entry.topologySeenAt.put(requestKey, now);
            entry.topologyVariant.put(requestKey, variant);
            entry.lastSeenAt = now;
            keepNewest(entry.variantSeenAt, null);
            keepNewest(entry.topologySeenAt, entry.topologyVariant);
            if (now < entry.cooldownUntil) return true;
            if (entry.variantSeenAt.size() >= variantThreshold
                    || colourTopologyChurn(entry, variant)) {
                entry.cooldownUntil = now + cooldownMs;
                return true;
            }
            return false;
        }
    }

    /**
     * S5: churn is counted per TEXT variant — the key with its {@code ⟦CS⟧} colour
     * markers removed. Once player names become one {@code ⟦n⟧} slot, lobby arrivals of
     * different rank colourings ({@code [MVP+]} with a red or an aqua plus, …) share a
     * signature while differing only in colour-run topology; counting each topology as
     * a variant would put a busy lobby on cooldown. A key without colour markers is its
     * own variant, so plain keys behave exactly as before.
     */
    public static String variantOf(String requestKey) {
        if (requestKey.indexOf("CS") < 0) return requestKey;
        return VARIANTS.get(requestKey, ChurnGuard::computeVariant);
    }

    private static String computeVariant(String requestKey) {
        // Plain character scan instead of a regex: a key that stays churn-suppressed is
        // re-submitted every frame, so this runs on a per-frame path.
        StringBuilder out = null;
        int copied = 0;
        for (int i = requestKey.indexOf('⟦'); i >= 0; i = requestKey.indexOf('⟦', i + 1)) {
            int close = requestKey.indexOf('⟧', i + 1);
            if (close < 0) break;
            if (!isColourMarker(requestKey, i + 1, close)) continue;
            if (out == null) out = new StringBuilder(requestKey.length());
            out.append(requestKey, copied, i);
            copied = close + 1;
            i = close;
        }
        if (out == null) return requestKey;
        return out.append(requestKey, copied, requestKey.length()).toString();
    }

    /** Body of a {@code ⟦…⟧} token between {@code from} and {@code to} is {@code [/]CSn}
     *  (whitespace tolerated like the rest of the protocol parsers). */
    private static boolean isColourMarker(String text, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        if (i < to && text.charAt(i) == '/') i++;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        if (i + 2 > to || text.charAt(i) != 'C' || text.charAt(i + 1) != 'S') return false;
        i += 2;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        int digits = i;
        while (i < to && text.charAt(i) >= '0' && text.charAt(i) <= '9') i++;
        if (i == digits) return false;
        while (i < to && Character.isWhitespace(text.charAt(i))) i++;
        return i == to;
    }

    /**
     * A single text whose colour topology alone keeps changing (a highlight sweeping
     * across an otherwise constant title) is still churn: it trips once every tracked
     * topology in the window belongs to that one text and their count reaches the
     * threshold. Mixed texts (the lobby case above) never satisfy this.
     */
    private boolean colourTopologyChurn(Entry entry, String variant) {
        if (entry.topologySeenAt.size() < variantThreshold) return false;
        for (String owner : entry.topologyVariant.values()) {
            if (!owner.equals(variant)) return false;
        }
        return true;
    }

    private void slideOut(Map<String, Long> seenAt, long now, Map<String, String> companion) {
        Iterator<Map.Entry<String, Long>> it = seenAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> seen = it.next();
            if (now - seen.getValue() > windowMs) {
                if (companion != null) companion.remove(seen.getKey());
                it.remove();
            }
        }
    }

    private void keepNewest(Map<String, Long> seenAt, Map<String, String> companion) {
        while (seenAt.size() > variantThreshold) {
            String oldest = seenAt.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) break;
            seenAt.remove(oldest);
            if (companion != null) companion.remove(oldest);
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
        return SIGNATURES.get(key, ChurnGuard::computeSignature);
    }

    private static String computeSignature(String key) {
        String noTokens = key.indexOf('\u27E6') < 0 ? key : ANY_TOKEN.matcher(key).replaceAll("");
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
