package com.dragonmeow.mctranslator.forgelegacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class LegacyTranslator {
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mctranslator-forge-legacy");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    String cached(String source, String target) { return cache.get(target + '\n' + source); }

    void translate(final String source, final String target, final Consumer<String> callback) {
        final String key = target + '\n' + source;
        String hit = cache.get(key);
        if (hit != null) { callback.accept(hit); return; }
        executor.execute(() -> {
            String result;
            try { result = request(source, target); }
            catch (Exception ignored) { result = source; }
            if (result == null || result.trim().isEmpty()) result = source;
            cache.put(key, result);
            callback.accept(result);
        });
    }

    private static String request(String text, String target) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=auto&tl="
                + enc(target) + "&q=" + enc(text);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            JsonArray chunks = new JsonParser().parse(body.toString()).getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder output = new StringBuilder();
            for (JsonElement element : chunks) {
                JsonArray chunk = element.getAsJsonArray();
                if (chunk.size() > 0 && !chunk.get(0).isJsonNull()) output.append(chunk.get(0).getAsString());
            }
            return output.toString();
        } finally { connection.disconnect(); }
    }

    private static String enc(String text) throws Exception { return URLEncoder.encode(text, "UTF-8"); }
}
