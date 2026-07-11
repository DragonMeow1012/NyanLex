package com.dragonmeow.mctranslator.translate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Real {@link HttpTransport} backed by {@link java.net.http.HttpClient}. */
public final class UrlHttpTransport implements HttpTransport {

    private final HttpClient client;
    private final Duration timeout;

    public UrlHttpTransport(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                // A browser-like UA reduces the chance of the free endpoint blocking us.
                .header("User-Agent", "Mozilla/5.0 (Minecraft Translator Mod)")
                .GET()
                .build();
        try {
            HttpResponse<String> resp =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }

    @Override
    public String post(String url, String body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // AI completions are slower than the free GET endpoint; allow more time.
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        try {
            HttpResponse<String> resp =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code / 100 != 2) {
                String snippet = resp.body() == null ? "" : resp.body();
                if (snippet.length() > 200) snippet = snippet.substring(0, 200);
                throw new IOException("HTTP " + code + ": " + snippet);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }
}
