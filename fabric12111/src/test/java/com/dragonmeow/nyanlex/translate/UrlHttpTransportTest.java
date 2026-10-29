package com.dragonmeow.nyanlex.translate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlHttpTransportTest {

    private static final int TEST_LIMIT = 64;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void theGoogleMachineTranslationRequestUsesHttp1() {
        for (String host : new String[] {"translate.googleapis.com", "translate.google.com"}) {
            java.net.http.HttpRequest request = UrlHttpTransport.buildGetRequest(
                    "https://" + host + "/translate_a/single?client=gtx&sl=auto&tl=zh-TW&dt=t&q=hello%20world",
                    Duration.ofSeconds(5));
            assertEquals(java.util.Optional.of(java.net.http.HttpClient.Version.HTTP_1_1), request.version(),
                    host + " uses the supported HTTP/1.1 compatibility path");
            assertEquals("GET", request.method());
        }
    }

    @Test
    void otherRequestsKeepTheClientsDefaultVersion() {
        for (String url : new String[] {
                "https://generativelanguage.googleapis.com/v1beta/openai/models",
                "https://api.openai.com/v1/models",
                "https://raw.githubusercontent.com/x/y/index.json",
                "http://127.0.0.1:11434/v1/models"}) {
            assertEquals(java.util.Optional.empty(),
                    UrlHttpTransport.buildGetRequest(url, Duration.ofSeconds(5)).version(), url);
        }
    }

    @Test
    void readsNormalFixedLengthUtf8Response() throws Exception {
        String expected = "{\"translation\":\"繁體中文\"}";
        String url = serve("/fixed", exchange -> sendFixed(exchange, 200,
                expected.getBytes(StandardCharsets.UTF_8)));

        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(2), TEST_LIMIT);

        assertEquals(expected, transport.get(url));
    }

    @Test
    void readsNormalChunkedPostResponse() throws Exception {
        String expected = "chunked 正常";
        String url = serve("/chunked-normal", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendChunked(exchange, 200, expected.getBytes(StandardCharsets.UTF_8));
        });

        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(2), TEST_LIMIT);

        assertEquals(expected, transport.post(url, "{}", Map.of()));
    }

    @Test
    void rejectsDeclaredContentLengthAboveLimit() throws Exception {
        byte[] oversized = bytes(TEST_LIMIT + 1);
        String url = serve("/declared-too-large",
                exchange -> sendFixed(exchange, 200, oversized));
        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(2), TEST_LIMIT);

        IOException error = assertThrows(IOException.class, () -> transport.get(url));

        assertTrue(error.getMessage().contains("exceeds " + TEST_LIMIT + " bytes"));
    }

    @Test
    void rejectsChunkedResponseAsSoonAsStreamCrossesLimit() throws Exception {
        byte[] oversized = bytes(TEST_LIMIT + 1);
        String url = serve("/chunked-too-large",
                exchange -> sendChunked(exchange, 200, oversized));
        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(2), TEST_LIMIT);

        IOException error = assertThrows(IOException.class, () -> transport.get(url));

        assertTrue(error.getMessage().contains("exceeds " + TEST_LIMIT + " bytes"));
    }

    @Test
    void redirectNeverForwardsCredentialsOrText() throws Exception {
        java.util.concurrent.atomic.AtomicInteger redirected = new java.util.concurrent.atomic.AtomicInteger();
        String url = serve("/source", exchange -> {
            exchange.getResponseHeaders().add("Location", "/other");
            exchange.sendResponseHeaders(307, -1);
        });
        server.createContext("/other", exchange -> {
            redirected.incrementAndGet();
            sendFixed(exchange, 200, bytes(2));
            exchange.close();
        });
        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(3));
        IOException error = assertThrows(IOException.class, () ->
                transport.post(url, "SYNTHETIC_GAME_TEXT", Map.of("Authorization", "Bearer SYNTHETIC_ONLY")));
        assertTrue(error.getMessage().contains("307"));
        assertEquals(0, redirected.get());
    }

    @Test
    void statusErrorsCarryTheCodeTheBodyAndRetryAfterWhileTheMessageKeepsItsShape() throws Exception {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"" + "x".repeat(300) + "\"}}";
        String url = serve("/limited", exchange -> {
            exchange.getResponseHeaders().add("retry-after", "42");
            sendFixed(exchange, 429, body.getBytes(StandardCharsets.UTF_8));
        });
        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(3));

        UrlHttpTransport.HttpStatusException post = assertThrows(UrlHttpTransport.HttpStatusException.class,
                () -> transport.post(url, "{}", Map.of()));
        assertEquals(429, post.statusCode());
        assertEquals(42_000L, post.retryAfterMs());
        assertEquals("HTTP 429: " + body.substring(0, 200), post.getMessage(), "the historic message shape is unchanged");
        assertEquals(body, post.bodySnippet(), "the longer body prefix is kept for classification");

        UrlHttpTransport.HttpStatusException get = assertThrows(UrlHttpTransport.HttpStatusException.class,
                () -> transport.get(url));
        assertEquals("HTTP 429", get.getMessage());
        assertEquals("", get.bodySnippet());
        assertEquals(42_000L, get.retryAfterMs());
    }

    @Test
    void aMissingOrUnusableRetryAfterIsZero() throws Exception {
        String missing = serve("/none", exchange -> sendFixed(exchange, 500, "boom".getBytes(StandardCharsets.UTF_8)));
        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(3));
        UrlHttpTransport.HttpStatusException error = assertThrows(UrlHttpTransport.HttpStatusException.class,
                () -> transport.post(missing, "{}", Map.of()));
        assertEquals(0L, error.retryAfterMs());
        assertEquals("HTTP 500: boom", error.getMessage());
        server.stop(0);
        String date = serve("/date", exchange -> {
            exchange.getResponseHeaders().add("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT");
            sendFixed(exchange, 503, new byte[0]);
        });
        assertEquals(0L, assertThrows(UrlHttpTransport.HttpStatusException.class,
                () -> transport.post(date, "{}", Map.of())).retryAfterMs());
    }

    @Test
    void aPostWaitsExactlyAsLongAsItIsToldAndDefaultsToThirtySeconds() throws Exception {
        // Every provider gets the historic 30 s unless its transport asks for more.
        assertEquals(Duration.ofSeconds(30), UrlHttpTransport.POST_TIMEOUT);
        String endpoint = "https://api.example.test/v1/chat/completions";
        assertEquals(java.util.Optional.of(Duration.ofSeconds(30)),
                UrlHttpTransport.buildPostRequest(endpoint, "{}", Map.of(), UrlHttpTransport.POST_TIMEOUT).timeout());
        assertEquals(java.util.Optional.of(Duration.ofSeconds(120)),
                UrlHttpTransport.buildPostRequest(endpoint, "{}", Map.of(), Duration.ofSeconds(120)).timeout());
        assertEquals("application/json", UrlHttpTransport.buildPostRequest(endpoint, "{}", null, Duration.ofSeconds(5))
                .headers().firstValue("Content-Type").orElse(""));

        // The same slow answer fails a short wait and succeeds with enough room, through the real client.
        String url = serve("/slow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1_500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            sendFixed(exchange, 200, "late".getBytes(StandardCharsets.UTF_8));
        });
        UrlHttpTransport transport = new UrlHttpTransport(Duration.ofSeconds(3));
        assertThrows(IOException.class, () -> transport.post(url, "{}", Map.of(), Duration.ofMillis(300)));
        assertEquals("late", transport.post(url, "{}", Map.of(), Duration.ofSeconds(10)));
    }

    private String serve(String path, ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void sendFixed(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try {
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // A Content-Length rejection deliberately closes before consuming the body.
        }
    }

    private static void sendChunked(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, 0L);
        try {
            for (int offset = 0; offset < body.length; offset += 7) {
                exchange.getResponseBody().write(body, offset, Math.min(7, body.length - offset));
                exchange.getResponseBody().flush();
            }
        } catch (IOException ignored) {
            // The client closes the stream immediately after observing byte limit + 1.
        }
    }

    private static byte[] bytes(int size) {
        byte[] value = new byte[size];
        java.util.Arrays.fill(value, (byte) 'x');
        return value;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
