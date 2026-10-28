package com.dragonmeow.nyanlex.config;

/** Small Java 8-compatible helper for rendering a secret without changing its stored value. */
public final class SecretText {
    private SecretText() { }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder masked = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) masked.append('*');
        return masked.toString();
    }
}
