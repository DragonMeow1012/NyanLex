package com.dragonmeow.mctranslator;
import com.dragonmeow.mctranslator.translate.ScreenTranslationCapture;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ScreenTranslationCaptureTest {
    @Test void capturesOnlyRequestedScreenAndDeduplicatesOriginalText() {
        var capture = new ScreenTranslationCapture();
        Object screen = new Object();
        capture.begin(screen);
        capture.record(screen, "Quest paragraph");
        capture.record(screen, "Quest paragraph");
        capture.record(new Object(), "Unrelated screen");
        assertNull(capture.finish(new Object()));
        assertEquals(List.of("Quest paragraph"), capture.finish(screen));
        assertFalse(capture.active(screen));
    }
    @Test void navigationCancelsAndRepeatedHotkeyStartsFresh() {
        var capture = new ScreenTranslationCapture(); Object screen = new Object();
        capture.begin(screen); capture.record(screen,"Old"); capture.begin(screen);
        capture.record(screen,"New"); assertEquals(List.of("New"),capture.finish(screen));
        capture.begin(screen); capture.record(screen,"Closed"); capture.cancelUnless(null);
        assertNull(capture.finish(screen));
    }
    @Test void boundsOneFrameOfMaliciousOrVeryLargeGuiText() {
        var capture = new ScreenTranslationCapture(); Object screen = new Object(); capture.begin(screen);
        capture.record(screen, "x".repeat(16385));
        for(int i=0;i<1000;i++) capture.record(screen,"Label "+i);
        assertEquals(512,capture.finish(screen).size());
    }
}
