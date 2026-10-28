package com.dragonmeow.nyanlex.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SecretTextTest {

    @Test
    void nullAndEmptySecretsStayEmpty() {
        assertEquals("", SecretText.mask(null));
        assertEquals("", SecretText.mask(""));
    }

    @Test
    void everyCharacterIsMaskedWithoutChangingLength() {
        String secret = "sk-first, second key";
        String masked = SecretText.mask(secret);

        assertEquals(secret.length(), masked.length());
        assertEquals("********************", masked);
        assertFalse(masked.contains("sk-first"));
        assertFalse(masked.contains("second"));
    }
}
