package com.dragonmeow.nyanlex.translate;

import java.net.URI;

/** Shared URL boundary for modern and Java 8 translation clients. No DNS lookup. */
public final class HttpEndpointPolicy {
    private HttpEndpointPolicy() { }

    /** Only the official OpenAI endpoint has the documented OpenAI billing tiers. */
    public static boolean isOpenAi(String url) {
        return isHttpsHost(url, "api.openai.com");
    }

    /** Only the official Anthropic endpoint receives an Anthropic API key. */
    public static boolean isAnthropic(String url) {
        return isHttpsHost(url, "api.anthropic.com");
    }

    /** The official Gemini API (its OpenAI-compatible layer included). */
    public static boolean isGemini(String url) {
        return isHttpsHost(url, "generativelanguage.googleapis.com");
    }

    /** The official DeepSeek API, which spells its thinking switch differently from OpenAI. */
    public static boolean isDeepSeek(String url) {
        return isHttpsHost(url, "api.deepseek.com");
    }

    private static boolean isHttpsHost(String url, String host) {
        try {
            URI uri = validate(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host.equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

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
