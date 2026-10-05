package com.dragonmeow.nyanlex.translate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** One account's send pacing and service-reported limits, shared by both official CLI clients. */
public final class CliRequestLimits {
    public enum Kind { RATE_LIMIT, QUOTA_EXHAUSTED }

    public static final class LimitedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final Kind kind;
        private final long retryAt;
        LimitedException(Kind kind, long retryAt, String message) {
            super(message);
            this.kind = kind;
            this.retryAt = retryAt;
        }
        public Kind kind() { return kind; }
        public long retryAt() { return retryAt; }
    }

    private final LongSupplier clock;
    private volatile RequestPacer pacer;
    private volatile LongSupplier cooldown = () -> 1_000L;
    private long blockedUntil;
    private long penalty = 30_000;
    private Kind blockedKind;

    public CliRequestLimits() {
        clock = System::currentTimeMillis;
        pacer = new RequestPacer(() -> Math.max(1_000L, cooldown.getAsLong()));
    }

    CliRequestLimits(LongSupplier clock, RequestPacer pacer) {
        this.clock = clock;
        this.pacer = pacer;
    }

    public void setCooldown(LongSupplier cooldown) {
        this.cooldown = cooldown;
    }

    /** Includes connection tests and warm-up; no warm-up exemption for account allowances. */
    public void acquire() throws IOException {
        check();
        pacer.acquire();
        check();
    }

    public synchronized void check() throws LimitedException {
        if (clock.getAsLong() < blockedUntil) throw limited(blockedKind, blockedUntil);
    }

    public synchronized long blockedUntil() { return blockedUntil; }

    public synchronized IOException failure(JsonElement error, String fallback) {
        String detail = error == null || error.isJsonNull() ? fallback
                : error.isJsonPrimitive() ? error.getAsString() : error.toString();
        String lower = detail.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("[^a-z0-9]", "");
        boolean quota = compact.contains("usagelimitexceeded") || compact.contains("quotaexceeded")
                || compact.contains("quotaexhausted") || compact.contains("insufficientquota")
                || compact.contains("creditsexhausted") || compact.contains("creditsdepleted")
                || compact.contains("creditbalanceexhausted") || compact.contains("billinghardlimit");
        boolean rate = compact.contains("ratelimit") || compact.contains("toomanyrequests")
                || compact.contains("resourceexhausted") || lower.matches("(?s).*\\b429\\b.*");
        if (!quota && !rate) return new IOException(fallback == null ? "CLI request failed" : fallback);
        Kind kind = quota ? Kind.QUOTA_EXHAUSTED : Kind.RATE_LIMIT;
        long reported = retryAt(error, clock.getAsLong(), 0);
        long until = reported > clock.getAsLong() ? reported
                : quota ? Long.MAX_VALUE : clock.getAsLong() + penalty;
        penalty = Math.min(900_000L, penalty * 2);
        if (until >= blockedUntil) { blockedUntil = until; blockedKind = kind; }
        return limited(blockedKind, blockedUntil);
    }

    public synchronized void success() { penalty = 30_000; }

    /** Consume the Codex account snapshot; unrelated model buckets must not stop this account. */
    public synchronized void updateCodex(JsonObject params) {
        if (params == null) return;
        JsonObject limits = object(params, "rateLimits");
        JsonObject buckets = object(params, "rateLimitsByLimitId");
        if (buckets != null && object(buckets, "codex") != null) limits = object(buckets, "codex");
        if (limits == null) return;
        long reset = 0;
        boolean known = false;
        boolean depleted = false;
        for (String name : new String[] { "primary", "secondary" }) {
            JsonObject window = object(limits, name);
            if (window == null || !window.has("usedPercent") || window.get("usedPercent").isJsonNull()) continue;
            try {
                double percent = window.get("usedPercent").getAsDouble();
                if (!Double.isFinite(percent) || percent < 0) continue;
                known = true;
                if (percent >= 100) {
                    depleted = true;
                    long seconds = number(window, "resetsAt");
                    reset = Math.max(reset, seconds > 0 && seconds < Long.MAX_VALUE / 1000
                            ? seconds * 1000 : Long.MAX_VALUE);
                }
            } catch (RuntimeException ignored) { }
        }
        if (depleted) {
            blockedKind = Kind.QUOTA_EXHAUSTED;
            blockedUntil = reset > clock.getAsLong() ? reset : Long.MAX_VALUE;
        } else if (known && blockedKind == Kind.QUOTA_EXHAUSTED) {
            blockedUntil = 0;
            blockedKind = null;
        }
    }

    private static LimitedException limited(Kind kind, long until) {
        String reason = kind == Kind.QUOTA_EXHAUSTED ? "CLI account allowance exhausted" : "CLI rate-limited (429)";
        return new LimitedException(kind, until, reason + (until == Long.MAX_VALUE
                ? "; translation paused until the official account reports available allowance or you explicitly reconnect"
                : "; translation paused until " + java.time.Instant.ofEpochMilli(until)));
    }

    /** Only an explicit account reconnect clears an unknown reset, never a process restart. */
    public synchronized void reconnect() { blockedUntil = 0; blockedKind = null; penalty = 30_000; }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static long number(JsonObject object, String key) {
        try { return object.has(key) ? object.get(key).getAsLong() : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    private static long retryAt(JsonElement element, long now, int depth) {
        if (element == null || depth > 8) return 0;
        long result = 0;
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String key = entry.getKey().replace("_", "").toLowerCase(Locale.ROOT);
                JsonElement value = entry.getValue();
                try {
                    if (value.isJsonPrimitive()) {
                        double seconds = 0;
                        if (key.equals("resetsat") || key.equals("resetat")) {
                            long timestamp = value.getAsLong();
                            if (timestamp > 0 && timestamp < Long.MAX_VALUE / 1000) result = Math.max(result, timestamp * 1000);
                        } else if (key.equals("retryafter") || key.equals("retryafterseconds")) {
                            seconds = value.getAsDouble();
                        } else if (key.equals("retrydelay")) {
                            String delay = value.getAsString();
                            if (delay.endsWith("s")) seconds = Double.parseDouble(delay.substring(0, delay.length() - 1));
                        }
                        if (Double.isFinite(seconds) && seconds > 0 && seconds < (Long.MAX_VALUE - now) / 1000.0)
                            result = Math.max(result, now + (long) Math.ceil(seconds * 1000));
                    }
                } catch (RuntimeException ignored) { }
                result = Math.max(result, retryAt(value, now, depth + 1));
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) result = Math.max(result, retryAt(value, now, depth + 1));
        }
        return result;
    }
}
