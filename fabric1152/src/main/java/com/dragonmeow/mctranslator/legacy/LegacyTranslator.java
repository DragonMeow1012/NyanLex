package com.dragonmeow.mctranslator.legacy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
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
        Thread thread = new Thread(runnable, "mctranslator-legacy");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, String> cache = new ConcurrentHashMap<String, String>();

    String cached(String source, String target) {
        return cache.get(target + '\n' + source);
    }

    void translate(final String source, final String sourceLang, final String target,
                   final Consumer<String> callback) {
        final String key = target + '\n' + source;
        String hit = cache.get(key);
        if (hit != null) {
            callback.accept(hit);
            return;
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    result = request(source, sourceLang, target);
                    if (result == null || result.trim().isEmpty()) result = source;
                } catch (Exception ignored) {
                    result = source;
                }
                cache.put(key, result);
                callback.accept(result);
            }
        });
    }

    private static String request(String text, String sourceLang, String target) throws Exception {
        String endpoint = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl="
                + enc(sourceLang) + "&tl=" + enc(target) + "&q=" + enc(text);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "MinecraftTranslator/1.0.2");
        try {
            InputStream stream = connection.getResponseCode() >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return text;
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            JsonArray root = new JsonParser().parse(body.toString()).getAsJsonArray();
            JsonArray chunks = root.get(0).getAsJsonArray();
            StringBuilder translated = new StringBuilder();
            for (JsonElement element : chunks) {
                JsonArray chunk = element.getAsJsonArray();
                if (chunk.size() > 0 && !chunk.get(0).isJsonNull()) translated.append(chunk.get(0).getAsString());
            }
            return translated.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }
}
