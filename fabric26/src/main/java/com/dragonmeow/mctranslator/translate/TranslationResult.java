package com.dragonmeow.mctranslator.translate;

/**
 * Result of a translation request.
 *
 * @param translatedText    the translated text
 * @param detectedSourceLang the language Google detected for the source, or {@code null} if unknown
 */
public record TranslationResult(String translatedText, String detectedSourceLang) {
}
