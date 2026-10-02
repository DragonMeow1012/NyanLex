package com.dragonmeow.nyanslate.translate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Real {@link HttpTransport} backed by {@link java.net.http.HttpClient}. */
public final class UrlHttpTransport implements HttpTransport {

    private static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;
    private static final int ERROR_SNIPPET_BYTES = 800;

    private final HttpClient client;
    private final Duration timeout;
    private final int maxResponseBytes;

    public UrlHttpTransport(Duration timeout) {
        this(timeout, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Explicit response-byte cap, for a transport instance whose expected payloads are
     * larger than the {@value #DEFAULT_MAX_RESPONSE_BYTES}-byte default (e.g. the hub
     * repository's dedicated transport, sized via
     * {@code HubPaths.downloadResponseByteCap()} — never the translation-API instance,
     * which keeps the smaller default since AI/GT responses are always small).
     */
    public UrlHttpTransport(Duration timeout, int maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookies)
                .build();
    }

    @Override
    public String get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                // A browser-like UA reduces the chance of the free endpoint blocking us.
                .header("User-Agent", "Mozilla/5.0 (Nyanslate Mod)")
                .GET()
                .build();
        return send(request, false);
    }

    @Override
    public String post(String url, String body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // AI completions are slower than the free GET endpoint; allow more time.
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers == null || headers.keySet().stream().noneMatch(
                name -> "content-type".equalsIgnoreCase(name))) {
            builder.setHeader("Content-Type", "application/json");
        }
        if (headers != null) headers.forEach(builder::setHeader);
        return send(builder.build(), true);
    }

    private String send(HttpRequest request, boolean includeErrorSnippet) throws IOException {
        try {
            HttpResponse<String> response = client.send(request, info -> {
                if (info.statusCode() / 100 != 2) {
                    // Preserve a small POST diagnostic. GET historically exposed only
                    // the status code, so its response body is discarded as it arrives.
                    return new LimitedUtf8Subscriber(
                            includeErrorSnippet ? ERROR_SNIPPET_BYTES : 0,
                            false,
                            null);
                }
                long declaredBytes = contentLength(info);
                IOException initialFailure = declaredBytes > maxResponseBytes
                        ? responseTooLarge() : null;
                return new LimitedUtf8Subscriber(
                        maxResponseBytes,
                        true,
                        initialFailure);
            });
            int code = response.statusCode();
            if (code / 100 != 2) {
                if (!includeErrorSnippet) throw new IOException("HTTP " + code);
                String snippet = response.body();
                if (snippet.length() > 200) snippet = snippet.substring(0, 200);
                throw new IOException("HTTP " + code + ": " + snippet);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }

    private static long contentLength(HttpResponse.ResponseInfo response) {
        try {
            return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        } catch (NumberFormatException ignored) {
            // A malformed declaration cannot bypass the streaming byte counter below.
            return -1L;
        }
    }

    private IOException responseTooLarge() {
        return new IOException("HTTP response exceeds " + maxResponseBytes + " bytes");
    }

    /**
     * Back-pressure-aware body collector. It never copies more than {@code limit}
     * bytes, and successful responses fail as soon as the next network buffer would
     * cross that limit. Error responses retain only a prefix while draining the rest,
     * so the request timeout and normal HttpClient lifecycle remain intact.
     */
    private static final class LimitedUtf8Subscriber
            implements HttpResponse.BodySubscriber<String> {
        private final int limit;
        private final boolean rejectOverflow;
        private final IOException initialFailure;
        private final ByteArrayOutputStream output;
        private final byte[] copyBuffer;
        private final CompletableFuture<String> result = new CompletableFuture<>();

        private Flow.Subscription subscription;
        private boolean done;

        private LimitedUtf8Subscriber(int limit, boolean rejectOverflow,
                IOException initialFailure) {
            this.limit = Math.max(0, limit);
            this.rejectOverflow = rejectOverflow;
            this.initialFailure = initialFailure;
            this.output = new ByteArrayOutputStream(Math.min(this.limit, READ_BUFFER_BYTES));
            this.copyBuffer = new byte[Math.min(Math.max(1, this.limit), READ_BUFFER_BYTES)];
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            if (subscription != null) {
                next.cancel();
                return;
            }
            subscription = next;
            if (initialFailure != null) {
                fail(initialFailure);
            } else {
                next.request(1L);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    int available = limit - output.size();
                    if (rejectOverflow && buffer.remaining() > available) {
                        fail(new IOException("HTTP response exceeds " + limit + " bytes"));
                        return;
                    }
                    copy(buffer, Math.min(buffer.remaining(), Math.max(0, available)));
                }
                subscription.request(1L);
            } catch (RuntimeException error) {
                fail(error);
            }
        }

        private void copy(ByteBuffer source, int bytes) {
            int remaining = bytes;
            while (remaining > 0) {
                int count = Math.min(remaining, copyBuffer.length);
                source.get(copyBuffer, 0, count);
                output.write(copyBuffer, 0, count);
                remaining -= count;
            }
        }

        @Override
        public void onError(Throwable error) {
            if (done) return;
            done = true;
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            if (done) return;
            done = true;
            result.complete(output.toString(StandardCharsets.UTF_8));
        }

        private void fail(Throwable error) {
            if (done) return;
            done = true;
            Flow.Subscription current = subscription;
            if (current != null) current.cancel();
            result.completeExceptionally(error);
        }
    }
}
