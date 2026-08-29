package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.ChatDeliverySession;
import com.dragonmeow.mctranslator.service.ChatRequestProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDeliverySessionTest {

    @Test
    void capOf512EvictsOldestUndisplayedOriginalWithoutLosingBlockLines() {
        ChatDeliverySession<Entry> session = session();
        for (int i = 1; i <= 512; i++) session.add(new Entry(i, false, "line-" + i));

        Entry newest = new Entry(513, false, "line-513");
        ChatDeliverySession.Admission<Entry> admission = session.add(newest);

        assertEquals(512, session.trackedSize());
        assertEquals(512, session.queuedSize());
        assertEquals(1, admission.evicted().id);
        assertFalse(admission.evictedWasDisplayed());
        assertEquals(List.of("frame-open", "frame-body", "frame-close"),
                admission.evicted().richOriginalLines);
        assertNull(session.get(1, admission.epoch()));
        assertSame(newest, session.get(513, admission.epoch()));
    }

    @Test
    void capPrefersADisplayedLateRecoveryRecord() {
        ChatDeliverySession<Entry> session = session();
        Entry displayed = new Entry(1, true, "shown");
        session.add(displayed);
        session.timeoutFirstQueued();
        for (int i = 2; i <= 512; i++) session.add(new Entry(i, false, "line-" + i));

        ChatDeliverySession.Admission<Entry> admission =
                session.add(new Entry(513, false, "line-513"));

        assertSame(displayed, admission.evicted());
        assertTrue(admission.evictedWasDisplayed());
        assertEquals(512, session.trackedSize());
        assertEquals(512, session.queuedSize());
    }

    @Test
    void disconnectClearsSilentlyAndInvalidatesOldCallbackEpoch() {
        ChatDeliverySession<Entry> session = session();
        TranslatorConfig config = new TranslatorConfig();
        Object connection = new Object();
        Object world = new Object();
        session.observe(connection, world, profile(config), false);
        Entry pending = new Entry(1, false, "pending");
        long requestEpoch = session.add(pending).epoch();

        ChatDeliverySession.Transition<Entry> transition = session.observe(
                null, null, profile(config), false);

        assertEquals(ChatDeliverySession.TransitionKind.SILENT_CLEAR, transition.kind());
        assertEquals(List.of(pending), transition.retired());
        assertTrue(transition.originals().isEmpty());
        assertNull(session.get(pending.id, requestEpoch));
        assertEquals(0, session.trackedSize());
        assertEquals(0, session.queuedSize());
    }

    @Test
    void profileSwitchFlushesOnlyUndisplayedInReceiveOrderAndRetiresEverything() {
        ChatDeliverySession<Entry> session = session();
        TranslatorConfig before = new TranslatorConfig();
        Object connection = new Object();
        Object world = new Object();
        session.observe(connection, world, profile(before), false);

        Entry first = new Entry(1, false, "first");
        Entry shown = new Entry(2, true, "shown");
        Entry third = new Entry(3, false, "third");
        long oldEpoch = session.add(first).epoch();
        session.add(shown);
        session.add(third);

        TranslatorConfig after = new TranslatorConfig();
        after.targetLang = "ja-JP";
        ChatDeliverySession.Transition<Entry> transition = session.observe(
                connection, world, profile(after), false);

        assertEquals(ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS, transition.kind());
        assertEquals(List.of(first, shown, third), transition.retired());
        assertEquals(List.of(first, third), transition.originals());
        assertNull(session.get(first.id, oldEpoch));
        assertEquals(0, session.trackedSize());
    }

    @Test
    void deliveryModeIsOutsideRequestProfileButOriginalOnlyFlushesImmediately() {
        ChatDeliverySession<Entry> session = session();
        TranslatorConfig config = new TranslatorConfig();
        Object connection = new Object();
        Object world = new Object();
        session.observe(connection, world, profile(config), false);
        Entry pending = new Entry(1, false, "pending");
        session.add(pending);

        config.deliverChatTranslationsInOrder = false;
        assertEquals(ChatDeliverySession.TransitionKind.NONE,
                session.observe(connection, world, profile(config), false).kind());
        assertEquals(1, session.trackedSize());

        ChatDeliverySession.Transition<Entry> transition = session.forceOriginalOnly();
        assertEquals(ChatDeliverySession.TransitionKind.FLUSH_ORIGINALS, transition.kind());
        assertEquals(List.of(pending), transition.originals());
        assertEquals(0, session.trackedSize());
    }

    @Test
    void timeoutRetainsExactlyOneLateReplacementOpportunity() {
        ChatDeliverySession<Entry> session = session();
        Entry pending = new Entry(1, false, "pending");
        long epoch = session.add(pending).epoch();

        assertSame(pending, session.timeoutFirstQueued());
        pending.displayed = true;
        assertSame(pending, session.get(pending.id, epoch));
        assertSame(pending, session.retire(pending.id, epoch));
        assertNull(session.get(pending.id, epoch));
        assertNull(session.retire(pending.id, epoch));
        assertEquals(0, session.trackedSize());
        assertEquals(0, session.queuedSize());
    }

    @Test
    void inactiveBackendSettingsDoNotInvalidateTheLiveRequestProfile() {
        TranslatorConfig machine = new TranslatorConfig();
        ChatRequestProfile machineBefore = profile(machine);
        machine.aiBaseUrl = "https://unused.invalid";
        machine.aiModel = "unused-api";
        machine.aiUseCodex = true;
        machine.codexModel = "unused-codex";
        machine.codexReasoningEffort = "high";
        machine.aiGlossary.add("unused=unused");
        assertEquals(machineBefore, profile(machine));

        TranslatorConfig apiAi = new TranslatorConfig();
        apiAi.aiChat = true;
        ChatRequestProfile apiBefore = profile(apiAi);
        apiAi.codexModel = "inactive-codex";
        apiAi.codexReasoningEffort = "high";
        assertEquals(apiBefore, profile(apiAi));

        TranslatorConfig codexAi = new TranslatorConfig();
        codexAi.aiChat = true;
        codexAi.aiUseCodex = true;
        codexAi.disableGoogleFallbackForAi = true;
        ChatRequestProfile codexBefore = profile(codexAi);
        codexAi.aiBaseUrl = "https://inactive.invalid";
        codexAi.aiModel = "inactive-api";
        codexAi.sourceLang = "fr";
        codexAi.machineTranslationProvider = "inactive-machine";
        assertEquals(codexBefore, profile(codexAi));

        codexAi.codexModel = "active-codex-change";
        assertFalse(codexBefore.equals(profile(codexAi)));
    }

    @Test
    void captureUsesTheServicesActiveTargetInsteadOfPossiblyStaleConfig() {
        TranslatorConfig config = new TranslatorConfig();
        config.targetLang = "stale-config";
        assertEquals(ChatRequestProfile.capture(config, "ja-JP"),
                ChatRequestProfile.capture(config, "ja-JP"));
        assertFalse(ChatRequestProfile.capture(config, "ja-JP").equals(
                ChatRequestProfile.capture(config, "ko-KR")));
    }

    @Test
    void sharedAnnouncementBudgetIsGlobalBoundedAndReusableAfterRetire() {
        ChatDeliverySession.BatchBudget budget =
                new ChatDeliverySession.BatchBudget(512, 1_000_000);
        for (int i = 0; i < 512; i++) assertTrue(budget.tryReserve(1));
        assertFalse(budget.tryReserve(0), "513th retained line bypassed the global cap");
        assertEquals(512, budget.items());

        budget.release(511, 511);
        assertTrue(budget.tryReserve(999_999));
        assertFalse(budget.tryReserve(1), "character cap was not global");
        budget.release(2, 1_000_000);
        assertEquals(0, budget.items());
        assertEquals(0, budget.chars());
        assertTrue(budget.tryReserve(1), "released capacity was not reusable");
    }

    private static ChatDeliverySession<Entry> session() {
        return new ChatDeliverySession<>(entry -> entry.id, entry -> entry.displayed, 512);
    }

    private static ChatRequestProfile profile(TranslatorConfig config) {
        return ChatRequestProfile.capture(config, config.targetLang);
    }

    private static final class Entry {
        final long id;
        boolean displayed;
        final List<String> richOriginalLines = new ArrayList<>();

        Entry(long id, boolean displayed, String line) {
            this.id = id;
            this.displayed = displayed;
            richOriginalLines.add(line);
            if (id == 1 && !displayed) {
                richOriginalLines.clear();
                richOriginalLines.add("frame-open");
                richOriginalLines.add("frame-body");
                richOriginalLines.add("frame-close");
            }
        }
    }
}
