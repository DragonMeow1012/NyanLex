package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dragonmeow.nyanlex.translate.ScreenTranslationCapture;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenTranslationCaptureTest {
    @Test
    void capturesOnlyRequestedScreenAndDeduplicatesBackendRequests() {
        var capture = new ScreenTranslationCapture();
        Object screen = new Object();
        capture.begin(screen);
        capture.recordRequest(screen, "Quest paragraph");
        capture.recordRequest(screen, "Quest paragraph");
        capture.recordRequest(new Object(), "Unrelated screen");
        assertNull(capture.finish(new Object()));
        assertEquals(List.of("Quest paragraph"), capture.finish(screen));
        assertFalse(capture.active(screen));
    }

    @Test
    void navigationCancelsAndRepeatedHotkeyStartsFresh() {
        var capture = new ScreenTranslationCapture();
        Object screen = new Object();
        capture.begin(screen);
        capture.recordRequest(screen, "Old");
        capture.begin(screen);
        capture.recordRequest(screen, "New");
        assertEquals(List.of("New"), capture.finish(screen));
        capture.begin(screen);
        capture.recordRequest(screen, "Closed");
        capture.cancelUnless(null);
        assertNull(capture.finish(screen));
    }

    @Test
    void boundsOneFrameOfMaliciousOrVeryLargeRequestText() {
        var capture = new ScreenTranslationCapture();
        Object screen = new Object();
        capture.begin(screen);
        capture.recordRequest(screen, "x".repeat(16_385));
        for (int i = 0; i < 1_000; i++) capture.recordRequest(screen, "Label " + i);
        assertEquals(512, capture.finish(screen).size());
    }
}
