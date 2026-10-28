package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** One bounded, explicit scan of the current screen's original render inputs. */
public final class ScreenTranslationCapture {
    private static final int MAX_REQUEST_SOURCES = 512;
    private static final int MAX_ENTRY_CHARACTERS = 16_384;
    private static final int MAX_REQUEST_CHARACTERS = 256 * 1024;

    private Object screen;
    private final LinkedHashSet<String> requestSources = new LinkedHashSet<>();
    private int requestCharacters;

    public void begin(Object currentScreen) {
        requestSources.clear();
        requestCharacters = 0;
        screen = currentScreen;
    }

    public boolean active(Object currentScreen) {
        return screen != null && screen == currentScreen;
    }

    /** Records one backend translation key. Duplicate keys consume no additional budget. */
    public void recordRequest(Object currentScreen, String requestSource) {
        if (!active(currentScreen) || !acceptable(requestSource)
                || requestSources.contains(requestSource)
                || requestSources.size() >= MAX_REQUEST_SOURCES
                || requestCharacters > MAX_REQUEST_CHARACTERS - requestSource.length()) return;
        requestSources.add(requestSource);
        requestCharacters += requestSource.length();
    }

    public List<String> finish(Object currentScreen) {
        if (!active(currentScreen)) return null;
        List<String> result = new ArrayList<>(requestSources);
        begin(null);
        return result;
    }

    public void cancelUnless(Object currentScreen) {
        if (screen != currentScreen) begin(null);
    }

    private static boolean acceptable(String text) {
        return text != null && !text.trim().isEmpty()
                && text.length() <= MAX_ENTRY_CHARACTERS;
    }
}
