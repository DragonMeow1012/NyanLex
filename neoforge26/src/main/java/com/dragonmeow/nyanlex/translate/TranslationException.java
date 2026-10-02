package com.dragonmeow.nyanlex.translate;

/** Thrown when a translation request fails (network, HTTP, or parse error). */
public class TranslationException extends Exception {

    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
