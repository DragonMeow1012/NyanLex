package com.dragonmeow.nyanslate.translate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** One bounded, explicit scan of the current screen's original render inputs. */
public final class ScreenTranslationCapture {
    private static final int MAX_SOURCES = 512;
    private Object screen;
    private final LinkedHashSet<String> sources = new LinkedHashSet<>();

    public void begin(Object currentScreen) {
        sources.clear();
        screen = currentScreen;
    }

    public boolean active(Object currentScreen) {
        return screen != null && screen == currentScreen;
    }

    public void record(Object currentScreen, String source) {
        if (active(currentScreen) && source != null && !source.trim().isEmpty()
                && source.length() <= 16384 && sources.size() < MAX_SOURCES) {
            sources.add(source);
        }
    }

    public List<String> finish(Object currentScreen) {
        if (!active(currentScreen)) return null;
        List<String> result = new ArrayList<>(sources);
        begin(null);
        return result;
    }

    public void cancelUnless(Object currentScreen) {
        if (screen != currentScreen) begin(null);
    }
}
