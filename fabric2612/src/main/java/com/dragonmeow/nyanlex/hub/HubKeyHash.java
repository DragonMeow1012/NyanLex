package com.dragonmeow.nyanlex.hub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one identity a hub row has in a public repository file: the lowercase hex SHA-256 of
 * the normalized cache key (the masked, in-segment-normalized text exactly as the
 * translation service looks it up in its own cache — never position, item or player
 * dependent) encoded as UTF-8. The repository stores only this hash, never the key.
 */
public final class HubKeyHash {
    private HubKeyHash() {
    }

    public static String of(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isHash(String text) {
        if (text == null || text.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
