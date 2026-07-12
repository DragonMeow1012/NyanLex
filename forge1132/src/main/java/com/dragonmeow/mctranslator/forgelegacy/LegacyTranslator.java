package com.dragonmeow.mctranslator.forgelegacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java-8 translation core used by MC 1.14-1.16. */
final class LegacyTranslator {
    private static final int MAX_BATCH_CHARS = 1400;
    private static final int BATCH_ITEM_OVERHEAD = 16;
    private static final Pattern FORMAT_TOKEN = Pattern.compile(
            "(?i)(?:\\u00a7[0-9A-FK-ORX]|%(?:\\d+\\$)?[A-Z%]|\\{\\d+\\})");

    static final class DebugEntry {
        final String engine, source, status;
        DebugEntry(String engine, String source, String status) {
            this.engine = engine; this.source = source; this.status = status;
        }
    }

    private static final class Pending {
        final String key, source, target, sourceLang, machineProvider;
        final boolean ai;
        final LegacyConfig config;
        final Consumer<String> callback;
        boolean highPriority;

        Pending(String key, String source, String target, String sourceLang,
                String machineProvider, boolean ai,
                boolean highPriority, LegacyConfig config, Consumer<String> callback) {
            this.key = key; this.source = source; this.target = target; this.sourceLang = sourceLang;
            this.machineProvider = machineProvider;
            this.ai = ai; this.highPriority = highPriority; this.config = config; this.callback = callback;
        }
    }

    private static final class BatchWire {
        final String text; final int anchorBase;
        BatchWire(String text, int anchorBase) { this.text = text; this.anchorBase = anchorBase; }
    }

    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<Runnable>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            queue, new ThreadFactory() {
        private final AtomicInteger sequence = new AtomicInteger();
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mctranslator-legacy-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Map<String, String> cache = new ConcurrentHashMap<String, String>();
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "mctranslator-legacy-retry");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<String, Boolean>();
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> keyUnavailableUntil = new ConcurrentHashMap<String, Long>();
    private final LegacyMachineProvider experimentalProviders = new LegacyMachineProvider();
    private final AtomicInteger keyCursor = new AtomicInteger();
    private final List<DebugEntry> debug = Collections.synchronizedList(new ArrayList<DebugEntry>());
    private final Object batchLock = new Object();
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<String, Pending>();
    private final Object paceLock = new Object();
    private long batchStartedAt = -1L;
    private long lastGtRequest, lastAiRequest;

    LegacyTranslator() { executor.prestartAllCoreThreads(); }

    String cached(String source, String target, boolean ai) {
        return cache.get(cacheKey(source, target, ai, "google"));
    }

    String cached(String source, String target, boolean ai, LegacyConfig config) {
        String provider = LegacyConfig.normalizeMachineProvider(
                config == null ? null : config.machineTranslationProvider);
        return cache.get(cacheKey(source, target, ai, provider));
    }

    void translate(final String source, final String target, final boolean ai, final boolean highPriority,
                   final LegacyConfig config, final Consumer<String> callback) {
        final String provider = LegacyConfig.normalizeMachineProvider(
                config == null ? null : config.machineTranslationProvider);
        final String key = cacheKey(source, target, ai, provider);
        String hit = cache.get(key);
        if (hit != null) { callback.accept(hit); return; }
        Long blocked = failedUntil.get(key);
        if (blocked != null && blocked.longValue() > System.currentTimeMillis()) return;
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            if (highPriority) {
                synchronized (batchLock) {
                    Pending existing = pending.get(key);
                    if (existing != null) existing.highPriority = true;
                }
            }
            return;
        }
        synchronized (batchLock) {
            if (pending.isEmpty()) batchStartedAt = System.currentTimeMillis();
            pending.put(key, new Pending(key, source, target, config.sourceLang, provider, ai,
                    highPriority, config, callback));
        }
    }

    /** Called once at the end of each client tick. Zero window therefore means next tick. */
    void flushBatch() {
        final List<Pending> batch = new ArrayList<Pending>();
        boolean high = false;
        synchronized (batchLock) {
            if (pending.isEmpty()) {
                batchStartedAt = -1L;
                return;
            }
            long now = System.currentTimeMillis();
            Pending seed = null;
            int totalChars = 0;
            for (Pending item : pending.values()) {
                totalChars += batchChars(item.source);
                if (seed == null || (!seed.highPriority && item.highPriority)) seed = item;
            }
            int window = Math.max(0, seed.config.batchWindowMs);
            boolean full = totalChars >= MAX_BATCH_CHARS;
            if (!seed.highPriority && !full && window > 0 && now - batchStartedAt < window) return;

            int chars = 0;
            boolean budgetFull = false;
            for (int pass = 0; pass < 2 && !budgetFull; pass++) {
                boolean highPass = pass == 0;
                java.util.Iterator<Map.Entry<String, Pending>> iterator = pending.entrySet().iterator();
                while (iterator.hasNext()) {
                    Pending item = iterator.next().getValue();
                    if (item.highPriority != highPass || !sameBatch(seed, item)) continue;
                    int next = batchChars(item.source);
                    if (!batch.isEmpty() && chars + next > MAX_BATCH_CHARS) {
                        budgetFull = true;
                        break;
                    }
                    batch.add(item);
                    chars += next;
                    high |= item.highPriority;
                    iterator.remove();
                    if (chars >= MAX_BATCH_CHARS) {
                        budgetFull = true;
                        break;
                    }
                }
            }
            batchStartedAt = pending.isEmpty() ? -1L : now;
        }
        if (batch.isEmpty()) return;
        Runnable task = new Runnable() {
            @Override public void run() { processBatch(batch); }
        };
        if (high) queue.offerFirst(task); else queue.offerLast(task);
    }

    private void processBatch(List<Pending> batch) {
        Pending first = batch.get(0);
        String engine = first.ai ? "AI" : "GT";
        for (Pending item : batch) log(item.config, engine, item.source, "...");
        try {
            List<String> translated;
            if (first.ai) {
                try {
                    translated = requestAiBatch(batch, first.target, first.config);
                } catch (Exception aiFailure) {
                    if (first.config.disableGoogleFallbackForAi) throw aiFailure;
                    engine = "GT";
                    translated = requestMachineBatch(batch, first.sourceLang, first.target,
                            first.machineProvider, first.config.requestCooldownMs);
                }
            } else {
                translated = requestMachineBatch(batch, first.sourceLang, first.target,
                        first.machineProvider, first.config.requestCooldownMs);
            }
            if (translated.size() != batch.size())
                throw new IllegalStateException("paragraph lost: batch size mismatch");
            for (int i = 0; i < batch.size(); i++) {
                Pending item = batch.get(i);
                String result = translated.get(i);
                String validationFailure = validationFailureFor(item.source, result);
                if (validationFailure != null || result.trim().equals(item.source.trim())) {
                    fail(item, engine, validationFailure == null ? "unknown" : validationFailure);
                    continue;
                }
                cache.put(item.key, result);
                failedUntil.remove(item.key);
                log(item.config, engine, item.source, "OK");
                try { item.callback.accept(result); }
                catch (RuntimeException ignored) {}
                finally { inFlight.remove(item.key); }
            }
        } catch (Exception failure) {
            for (Pending item : batch) fail(item, engine, failure);
        }
    }

    private void fail(final Pending item, String engine, Throwable failure) {
        fail(item, engine, failureReason(failure));
    }

    private void fail(final Pending item, String engine, String reason) {
        final long retryDelay = Math.max(250L, item.config.failureBackoffMs);
        failedUntil.put(item.key, System.currentTimeMillis() + retryDelay);
        log(item.config, engine, item.source, "failed (" + normalizeFailureReason(reason) + ")");
        inFlight.remove(item.key);
        if (item.ai && item.config.disableGoogleFallbackForAi) {
            retryScheduler.schedule(new Runnable() {
                @Override public void run() {
                    failedUntil.remove(item.key);
                    translate(item.source, item.target, true, item.highPriority,
                            item.config, item.callback);
                }
            }, retryDelay, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean sameBatch(Pending first, Pending other) {
        return first.ai == other.ai && first.target.equals(other.target)
                && safe(first.sourceLang).equals(safe(other.sourceLang))
                && first.machineProvider.equals(other.machineProvider)
                && first.config == other.config;
    }

    private static int batchChars(String source) {
        return (source == null ? 0 : source.length()) + BATCH_ITEM_OVERHEAD;
    }

    private static String validationFailureFor(String source, String translated) {
        if (translated == null || translated.trim().isEmpty()) return "empty response";
        if (lineBreakCount(translated) < lineBreakCount(source)) return "paragraph lost";
        if (!formatTokens(source).equals(formatTokens(translated))) return "format/token lost";
        return null;
    }

    private static int lineBreakCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\r') {
                count++;
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') i++;
            } else if (current == '\n') {
                count++;
            }
        }
        return count;
    }

    private static List<String> formatTokens(String value) {
        List<String> tokens = new ArrayList<String>();
        Matcher matcher = FORMAT_TOKEN.matcher(value == null ? "" : value);
        while (matcher.find()) tokens.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
        Collections.sort(tokens);
        return tokens;
    }

    static String failureReason(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        boolean serverError = false;
        boolean authentication = false;
        boolean network = false;
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 32; depth++) {
            if (cursor instanceof HttpStatusException) {
                int code = ((HttpStatusException) cursor).code;
                if (code == 429) return "429 rate limit";
                if (code >= 500 && code <= 599) serverError = true;
                if (code == 401 || code == 403) authentication = true;
            }
            if (isNetworkFailure(cursor)) network = true;
            String message = cursor.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                if (messages.length() > 0) messages.append(" | ");
                messages.append(message);
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) break;
            cursor = cause;
        }
        String combined = messages.toString().toLowerCase(java.util.Locale.ROOT);
        if (containsAny(combined, "http 429", "rate limit", "rate_limit",
                "rate-limit", "too many requests")) return "429 rate limit";
        if (serverError || combined.matches("(?s).*\\bhttp\\s*5\\d\\d\\b.*")) return "HTTP 5xx";
        if (authentication || containsAny(combined, "http 401", "http 403", "authentication",
                "authorization", "unauthorized", "unauthorised", "forbidden",
                "invalid api key", "invalid key")) return "authentication";
        if (network || containsAny(combined, "timed out", "timeout", "network", "connection",
                "connect reset", "connect refused", "connect failed", "unknown host",
                "dns", "no route", "socket")) return "timeout/network";
        if (containsAny(combined, "anchor", "order")
                && containsAny(combined, "damage", "damaged", "missing", "invalid",
                "mismatch", "reorder", "out-of-order", "unexpected")) return "anchor/order damaged";
        if (containsAny(combined, "paragraph", "hard line", "hard_line", "line break", "line-break")
                && containsAny(combined, "lost", "missing", "damage", "damaged",
                "mismatch", "invalid")) return "paragraph lost";
        if (containsAny(combined, "format", "token", "marker", "placeholder", "sentinel")
                && containsAny(combined, "lost", "missing", "damage", "damaged",
                "mismatch", "invalid")) return "format/token lost";
        if (containsAny(combined, "empty response", "empty body", "empty translation", "empty result",
                "blank response", "blank body", "blank translation", "blank result",
                "no choice", "no content", "no translation", "no result")) return "empty response";
        return "unknown";
    }

    private static boolean isNetworkFailure(Throwable error) {
        return error instanceof java.net.SocketTimeoutException
                || error instanceof java.net.ConnectException
                || error instanceof java.net.UnknownHostException
                || error instanceof java.net.NoRouteToHostException
                || error instanceof java.net.SocketException
                || error instanceof java.io.InterruptedIOException
                || error instanceof java.util.concurrent.TimeoutException;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String normalizeFailureReason(String reason) {
        if ("429 rate limit".equals(reason) || "HTTP 5xx".equals(reason)
                || "authentication".equals(reason) || "timeout/network".equals(reason)
                || "anchor/order damaged".equals(reason) || "paragraph lost".equals(reason)
                || "format/token lost".equals(reason) || "empty response".equals(reason)) {
            return reason;
        }
        return "unknown";
    }

    List<DebugEntry> debugSnapshot() {
        synchronized (debug) { return new ArrayList<DebugEntry>(debug); }
    }

    void clearDebug() { debug.clear(); }

    private void log(LegacyConfig config, String engine, String source, String status) {
        if (!config.debugTranslationOverlay) return;
        synchronized (debug) {
            debug.add(new DebugEntry(engine, compact(source), status));
            while (debug.size() > 24) debug.remove(0);
        }
    }

    private List<String> requestAiBatch(List<Pending> batch, String target,
                                        LegacyConfig config) throws Exception {
        if (batch.size() == 1) {
            return Collections.singletonList(requestAi(batch.get(0).source, target, config));
        }
        BatchWire wire = buildBatchWire(batch);
        return splitBatch(requestAi(wire.text, target, config), batch.size(), wire.anchorBase);
    }

    private List<String> requestGoogleBatch(List<Pending> batch, String sourceLang,
                                            String target, int cooldown) throws Exception {
        if (batch.size() == 1) {
            return Collections.singletonList(requestGoogle(batch.get(0).source,
                    sourceLang, target, cooldown));
        }
        BatchWire wire = buildBatchWire(batch);
        return splitBatch(requestGoogle(wire.text, sourceLang, target, cooldown),
                batch.size(), wire.anchorBase);
    }

    private List<String> requestMachineBatch(List<Pending> batch, String sourceLang,
                                             String target, String provider,
                                             int cooldown) throws Exception {
        String selected = LegacyConfig.normalizeMachineProvider(provider);
        if ("google".equals(selected)) {
            // Keep the historical Google path byte-for-byte equivalent.
            return requestGoogleBatch(batch, sourceLang, target, cooldown);
        }
        // Experimental sources always carry anchors, including a one-item batch. A
        // malformed/error-shaped response therefore cannot be accepted as cache data.
        BatchWire wire = buildBatchWire(batch);
        pace(false, cooldown);
        String translated = experimentalProviders.translate(
                selected, wire.text, sourceLang, target);
        return splitBatch(translated, batch.size(), wire.anchorBase);
    }

    private static BatchWire buildBatchWire(List<Pending> batch) {
        int anchorCount = batch.size() * 2;
        int base = 70001;
        outer:
        while (true) {
            for (Pending item : batch) {
                for (int i = 0; i < anchorCount; i++) {
                    if (item.source.contains(Integer.toString(base + i))) {
                        base += 2000;
                        continue outer;
                    }
                }
            }
            break;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(base + i * 2).append(batch.get(i).source).append(base + i * 2 + 1);
        }
        return new BatchWire(joined.toString(), base);
    }

    private static List<String> splitBatch(String translated, int count, int base) {
        if (translated == null || translated.trim().isEmpty())
            throw new IllegalStateException("empty response");
        List<String> out = new ArrayList<String>(count);
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            String open = Integer.toString(base + i * 2);
            String close = Integer.toString(base + i * 2 + 1);
            int start = translated.indexOf(open, cursor);
            if (start < 0 || translated.indexOf(open, start + open.length()) >= 0)
                throw new IllegalStateException("missing batch start anchor");
            if (!translated.substring(cursor, start).trim().isEmpty())
                throw new IllegalStateException("out-of-order batch anchor");
            start += open.length();
            int end = translated.indexOf(close, start);
            if (end < start || translated.indexOf(close, end + close.length()) >= 0)
                throw new IllegalStateException("missing batch end anchor");
            out.add(translated.substring(start, end).trim());
            cursor = end + close.length();
        }
        if (!translated.substring(cursor).trim().isEmpty())
            throw new IllegalStateException("unexpected text outside batch anchors");
        return out;
    }

    private String requestAi(String text, String target, LegacyConfig config) throws Exception {
        String baseUrl = config.aiBaseUrl == null ? "" : config.aiBaseUrl.trim();
        String model = config.aiModel == null ? "" : config.aiModel.trim();
        if (baseUrl.isEmpty() || model.isEmpty())
            throw new IllegalStateException("AI not configured");
        List<String> keys = new ArrayList<String>();
        if (config.aiApiKeys != null) for (String key : config.aiApiKeys)
            if (key != null && !key.trim().isEmpty()) keys.add(key.trim());
        if (keys.isEmpty()) {
            pace(true, config.requestCooldownMs);
            return postAi(text, target, config, null);
        }
        Exception last = null;
        int start = keyCursor.getAndIncrement() & Integer.MAX_VALUE;
        for (int attempt = 0; attempt < keys.size(); attempt++) {
            String key = keys.get((start + attempt) % keys.size());
            Long until = keyUnavailableUntil.get(key);
            if (until != null && until.longValue() > System.currentTimeMillis()) continue;
            try {
                pace(true, config.requestCooldownMs);
                return postAi(text, target, config, key);
            } catch (HttpStatusException status) {
                last = status;
                if (status.code == 429) keyUnavailableUntil.put(key, System.currentTimeMillis() + 60000L);
                else if (status.code == 401 || status.code == 403) keyUnavailableUntil.put(key, Long.MAX_VALUE);
                else keyUnavailableUntil.put(key, System.currentTimeMillis() + 10000L);
            } catch (Exception transientFailure) {
                last = transientFailure;
                keyUnavailableUntil.put(key, System.currentTimeMillis() + 10000L);
            }
        }
        throw last == null ? new IllegalStateException("all AI keys cooling down") : last;
    }

    private String postAi(String text, String target, LegacyConfig config, String apiKey) throws Exception {
        String base = config.aiBaseUrl == null ? "" : config.aiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String endpoint = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        JsonObject root = new JsonObject();
        root.addProperty("model", config.aiModel);
        root.addProperty("temperature", 0.1);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "Translate Minecraft text to " + target
                + ". Preserve names, numbers, formatting codes, line breaks, and numeric boundary markers exactly."
                + " Return translation only.");
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", text);
        messages.add(user);
        root.add("messages", messages);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(45000);
        if (apiKey != null && !apiKey.trim().isEmpty())
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output = connection.getOutputStream();
        try { output.write(body); } finally { output.close(); }
        try {
            int code = connection.getResponseCode();
            String response = read(connection, code >= 400);
            if (code >= 400) throw new HttpStatusException(code, response);
            if (response == null || response.trim().isEmpty())
                throw new IllegalStateException("empty response");
            JsonObject parsed = new JsonParser().parse(response).getAsJsonObject();
            JsonArray choices = parsed.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0)
                throw new IllegalStateException("no choices in response");
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull())
                throw new IllegalStateException("no content in response");
            String content = message.get("content").getAsString().trim();
            if (content.isEmpty()) throw new IllegalStateException("empty response");
            return content;
        } finally { connection.disconnect(); }
    }

    private String requestGoogle(String text, String sourceLang, String target, int cooldown) throws Exception {
        pace(false, cooldown);
        String endpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl="
                + enc(sourceLang) + "&tl=" + enc(target) + "&q=" + enc(text);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "MinecraftTranslator/1.0.2");
        try {
            int code = connection.getResponseCode();
            String body = read(connection, code >= 400);
            if (code >= 400) throw new HttpStatusException(code, body);
            if (body == null || body.trim().isEmpty())
                throw new IllegalStateException("empty response");
            JsonArray chunks = new JsonParser().parse(body).getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder translated = new StringBuilder();
            for (JsonElement element : chunks) {
                JsonArray chunk = element.getAsJsonArray();
                if (chunk.size() > 0 && !chunk.get(0).isJsonNull()) translated.append(chunk.get(0).getAsString());
            }
            return translated.toString();
        } finally { connection.disconnect(); }
    }

    private void pace(boolean ai, int cooldown) throws InterruptedException {
        synchronized (paceLock) {
            long now = System.currentTimeMillis();
            long last = ai ? lastAiRequest : lastGtRequest;
            long wait = Math.max(0L, Math.max(0, cooldown) - (now - last));
            if (wait > 0L) Thread.sleep(wait);
            if (ai) lastAiRequest = System.currentTimeMillis(); else lastGtRequest = System.currentTimeMillis();
        }
    }

    private static String read(HttpURLConnection connection, boolean error) throws Exception {
        InputStream stream = error ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder body = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        } finally { reader.close(); }
    }

    private static String cacheKey(String source, String target, boolean ai) {
        return (ai ? "AI\n" : "GT\n") + target + '\n' + source;
    }
    private static String cacheKey(String source, String target, boolean ai, String provider) {
        String selected = LegacyConfig.normalizeMachineProvider(provider);
        if ("google".equals(selected)) return cacheKey(source, target, ai);
        return (ai ? "AI\n" : "GT\n")
                + selected + '\n' + target + '\n' + source;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static String compact(String value) {
        String flat = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 64 ? flat : flat.substring(0, 61) + "...";
    }
    private static final class HttpStatusException extends Exception {
        final int code;
        HttpStatusException(int code, String body) { super("HTTP " + code + ": " + compact(body)); this.code = code; }
    }
}
