package com.dragonmeow.nyanlex.translate;

import java.io.IOException;
import java.util.Map;

/**
 * Minimal HTTP abstraction.
 *
 * <p>Having this as an interface is what makes the translators testable with an
 * <em>inline</em> fake transport — the unit tests never touch the real network.</p>
 */
public interface HttpTransport {

    /**
     * Perform an HTTP GET and return the response body as a UTF-8 string.
     *
     * @throws IOException on connection failure or non-2xx status
     */
    String get(String url) throws IOException;

    /**
     * Perform an HTTP POST with the given body and headers; return the UTF-8 body.
     * Default throws — only real / AI-capable transports implement it.
     *
     * @throws IOException on connection failure or non-2xx status
     */
    default String post(String url, String body, Map<String, String> headers) throws IOException {
        throw new IOException("POST not supported by this transport");
    }
}
