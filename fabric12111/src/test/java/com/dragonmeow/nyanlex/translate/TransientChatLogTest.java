package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransientChatLogTest {

    private final TransientChatLog log = new TransientChatLog();

    @Test
    void onlyWhenTheSwitchIsOnAndTheSurfaceShowsTranslations() {
        assertFalse(TransientChatLog.enabled(false, false));
        assertTrue(TransientChatLog.enabled(true, false));
        assertFalse(TransientChatLog.enabled(true, true), "original-only surface or global show-original");
    }

    @Test
    void aTitleOnScreenForSeveralSecondsIsRecordedOnce() {
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Entering the Harbor", 0));
        for (long now = 50; now < 5_000; now += 50) {
            assertFalse(log.acceptTitle(TransientChatLog.Kind.TITLE, "Entering the Harbor", now), "t=" + now);
        }
    }

    @Test
    void aCountdownIsOneEntryWhateverTheNumber() {
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Starts in 10", 0));
        assertFalse(log.acceptTitle(TransientChatLog.Kind.TITLE, "Starts in 9", 1_000));
        assertFalse(log.acceptTitle(TransientChatLog.Kind.TITLE, "§cStarts in  8", 2_000));
        assertFalse(log.acceptTitle(TransientChatLog.Kind.TITLE, "Starts in 7", 9_000));
        // every sighting extends the window, so a long countdown stays quiet
        assertFalse(log.acceptTitle(TransientChatLog.Kind.TITLE, "Starts in 1", 15_000));
    }

    @Test
    void theSameTitleIsRecordedAgainAfterTheWindowPassed() {
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round over", 0));
        assertFalse(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round over", TransientChatLog.REPEAT_WINDOW_MS - 1));
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round over",
                2 * TransientChatLog.REPEAT_WINDOW_MS));
    }

    @Test
    void aTitleAndASubtitleWithTheSameTextAreSeparateEntries() {
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Welcome", 0));
        assertTrue(log.acceptTitle(TransientChatLog.Kind.SUBTITLE, "Welcome", 10));
        assertFalse(log.acceptTitle(TransientChatLog.Kind.SUBTITLE, "Welcome", 20));
    }

    @Test
    void aDifferentTitleIsRecorded() {
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round one", 0));
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round two", 10));
    }

    @Test
    void theRecentTitleListIsBounded() {
        for (int i = 0; i < TransientChatLog.RECENT_TITLES + 1; i++) {
            assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Title " + (char) ('a' + i % 26) + (char) ('a' + i / 26), i));
        }
        // the oldest fell out of the list: it counts as new (inside the window, but forgotten)
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Title aa", 100));
    }

    @Test
    void aTakenBackTitleCanBeRecordedAgain() {
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round over", 0));
        log.forgetTitle(TransientChatLog.Kind.TITLE, "Round over");
        assertTrue(log.acceptTitle(TransientChatLog.Kind.TITLE, "Round over", 10));
    }

    @Test
    void aDialoguePageIsRecordedOncePerSpeakerAndText() {
        assertTrue(log.acceptDialogue("Mira", "Ah. Does this mean we walk?"));
        assertFalse(log.acceptDialogue("Mira", "Ah. Does this mean we walk?"));
        assertTrue(log.acceptDialogue("Road Warden", "Ah. Does this mean we walk?"), "another speaker");
        assertTrue(log.acceptDialogue("Mira", "Another page."));
        assertTrue(log.acceptDialogue(null, "No speaker."));
        assertFalse(log.acceptDialogue(null, "No speaker."));
    }

    @Test
    void theRecentDialogueListIsBoundedAndAnOldPageComesBack() {
        for (int i = 0; i < TransientChatLog.RECENT_DIALOGUES; i++) {
            assertTrue(log.acceptDialogue("Mira", "Page " + i));
        }
        assertFalse(log.acceptDialogue("Mira", "Page 0"), "still among the last sixteen");
        assertTrue(log.acceptDialogue("Mira", "Page 99"));
        assertTrue(log.acceptDialogue("Mira", "Page 1"), "pushed out by the newest ones");
    }

    @Test
    void aTakenBackDialogueCanBeRecordedAgain() {
        assertTrue(log.acceptDialogue("Mira", "Page"));
        log.forgetDialogue("Mira", "Page");
        assertTrue(log.acceptDialogue("Mira", "Page"));
    }

    @Test
    void theKeyIgnoresDigitsCodesAndSpacing() {
        assertEquals(TransientChatLog.titleKey(TransientChatLog.Kind.TITLE, "Wave 3 of 10"),
                TransientChatLog.titleKey(TransientChatLog.Kind.TITLE, "§6Wave  7 of 5 "));
    }

    @Test
    void theLineReadsPrefixSpaceText() {
        assertEquals("[Title] Entering", TransientChatLog.line("[Title]", "Entering"));
        assertEquals("Hello", TransientChatLog.line("", "Hello"));
        assertEquals("Hello", TransientChatLog.line(null, "Hello"));
    }

    @Test
    void theSpeakerIsTheTranslatedNameElseTheOriginalElseNothing() {
        assertEquals("[米拉]", TransientChatLog.speakerPrefix("米拉", "Mira"));
        assertEquals("[Mira]", TransientChatLog.speakerPrefix(null, "Mira"));
        assertEquals("[Mira]", TransientChatLog.speakerPrefix("  ", "Mira"));
        assertEquals("", TransientChatLog.speakerPrefix(null, null));
        assertEquals("", TransientChatLog.speakerPrefix(null, "  "));
    }
}
