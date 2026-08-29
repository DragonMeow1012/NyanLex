package com.dragonmeow.mctranslator.translate;

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
