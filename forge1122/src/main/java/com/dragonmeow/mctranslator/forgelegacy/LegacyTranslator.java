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

/** Java-8 translation core used by MC 1.14-1.16. */
final class LegacyTranslator {
    static final class DebugEntry {
        final String engine, source, status;
        DebugEntry(String engine, String source, String status) {
            this.engine = engine; this.source = source; this.status = status;
        }
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
    private final AtomicInteger keyCursor = new AtomicInteger();
    private final List<DebugEntry> debug = Collections.synchronizedList(new ArrayList<DebugEntry>());
    private final Object paceLock = new Object();
    private long lastGtRequest, lastAiRequest;

    LegacyTranslator() { executor.prestartAllCoreThreads(); }

    String cached(String source, String target, boolean ai) {
        return cache.get(cacheKey(source, target, ai));
    }

    void translate(final String source, final String target, final boolean ai, final boolean highPriority,
                   final LegacyConfig config, final Consumer<String> callback) {
        final String key = cacheKey(source, target, ai);
        String hit = cache.get(key);
        if (hit != null) { callback.accept(hit); return; }
        Long blocked = failedUntil.get(key);
        if (blocked != null && blocked.longValue() > System.currentTimeMillis()) return;
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) return;
        Runnable task = new Runnable() {
            @Override public void run() {
                String result = null;
                String engine = ai ? "AI" : "GT";
                log(config, engine, source, "...");
                try {
                    if (ai) {
                        try { result = requestAi(source, target, config); }
                        catch (Exception aiFailure) {
                            if (!config.disableGoogleFallbackForAi) {
                                engine = "GT";
                                result = requestGoogle(source, config.sourceLang, target, config.requestCooldownMs);
                            } else {
                                throw aiFailure;
                            }
                        }
                    } else {
                        result = requestGoogle(source, config.sourceLang, target, config.requestCooldownMs);
                    }
                    if (result == null || result.trim().isEmpty() || result.trim().equals(source.trim()))
                        throw new IllegalStateException("empty/identity response");
                    cache.put(key, result);
                    failedUntil.remove(key);
                    log(config, engine, source, "OK");
                    callback.accept(result);
                } catch (Exception failure) {
                    final long retryDelay = Math.max(250L, config.failureBackoffMs);
                    failedUntil.put(key, System.currentTimeMillis() + retryDelay);
                    log(config, engine, source, "FAIL");
                    if (ai && config.disableGoogleFallbackForAi) {
                        retryScheduler.schedule(new Runnable() {
                            @Override public void run() {
                                failedUntil.remove(key);
                                translate(source, target, true, highPriority, config, callback);
                            }
                        }, retryDelay, TimeUnit.MILLISECONDS);
                    }
                } finally {
                    inFlight.remove(key);
                }
            }
        };
        if (highPriority) queue.offerFirst(task); else queue.offerLast(task);
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

    private String requestAi(String text, String target, LegacyConfig config) throws Exception {
        List<String> keys = new ArrayList<String>();
        if (config.aiApiKeys != null) for (String key : config.aiApiKeys)
            if (key != null && !key.trim().isEmpty()) keys.add(key.trim());
        if (keys.isEmpty() || config.aiModel == null || config.aiModel.trim().isEmpty())
            throw new IllegalStateException("AI not configured");
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
                + ". Preserve names, numbers, formatting codes and line breaks. Return translation only.");
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
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output = connection.getOutputStream();
        try { output.write(body); } finally { output.close(); }
        try {
            int code = connection.getResponseCode();
            String response = read(connection, code >= 400);
            if (code >= 400) throw new HttpStatusException(code, response);
            JsonObject parsed = new JsonParser().parse(response).getAsJsonObject();
            return parsed.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString().trim();
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
