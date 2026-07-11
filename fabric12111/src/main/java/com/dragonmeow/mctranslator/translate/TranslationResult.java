package com.dragonmeow.mctranslator.translate;

/**
 * Result of a translation request.
 *
 * @param translatedText     the translated text
 * @param detectedSourceLang the language Google detected for the source, or {@code null} if unknown
 * @param fromFallback       true when a {@link DispatchingTranslator} produced this via its
 *                           FALLBACK backend (Google standing in for the AI engine) — the
 *                           cache stores such values as PROVISIONAL and re-asks the AI once
 *                           it recovers. Plain backends always report {@code false}.
 */
public record TranslationResult(String translatedText, String detectedSourceLang, boolean fromFallback) {

    /** Canonical two-arg form used by every plain backend: not a fallback product. */
    public TranslationResult(String translatedText, String detectedSourceLang) {
        this(translatedText, detectedSourceLang, false);
    }
}
