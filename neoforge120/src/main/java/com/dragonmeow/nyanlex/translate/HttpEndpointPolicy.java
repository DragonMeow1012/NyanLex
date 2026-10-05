package com.dragonmeow.nyanlex.translate;

import java.net.URI;

/** Shared URL boundary for modern and Java 8 translation clients. No DNS lookup. */
public final class HttpEndpointPolicy {
    private HttpEndpointPolicy() { }

    public static URI validate(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
            throw new IllegalArgumentException("Translation URL must have a host and no embedded credentials or fragment");
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !("http".equalsIgnoreCase(uri.getScheme()) && loopback))
            throw new IllegalArgumentException("Use HTTPS for remote services; HTTP is allowed only on localhost");
        return uri;
    }
}
