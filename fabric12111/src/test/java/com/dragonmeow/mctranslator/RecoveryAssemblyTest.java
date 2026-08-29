package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.service.RecoveryAssembly;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryAssemblyTest {

    @Test
    void repeatedSlotCannotCompleteAnotherSlotOrRegressAfterFinal() {
        RecoveryAssembly<String> assembly = new RecoveryAssembly<>(2);

        assertFalse(assembly.accept(0, "slot0-provisional", false).ready());
        assertFalse(assembly.accept(0, "slot0-final", true).ready());
        assertFalse(assembly.accept(0, "slot0-regression", false).accepted());

        RecoveryAssembly.Update<String> firstReady =
                assembly.accept(1, "slot1-provisional", false);
        assertTrue(firstReady.ready());
        assertFalse(firstReady.allFinal());
        assertEquals(List.of("slot0-final", "slot1-provisional"), firstReady.values());

        RecoveryAssembly.Update<String> finalReady =
                assembly.accept(1, "slot1-final", true);
        assertTrue(finalReady.ready());
        assertTrue(finalReady.allFinal());
        assertEquals(List.of("slot0-final", "slot1-final"), finalReady.values());
        assertEquals(List.of("slot0-final", "slot1-provisional"), firstReady.values(),
                "an already queued snapshot observed a later callback");
        assertFalse(assembly.accept(1, "duplicate-final", true).accepted());
    }

    @Test
    void deliveryProgressRecordsEarlyFinalsAndKeepsUsefulProvisionalValue() {
        RecoveryAssembly.ResultProgress<String> progress =
                new RecoveryAssembly.ResultProgress<>();
        progress.configure(2, true);

        assertTrue(progress.accept(0, true));
        assertEquals("slot0-final", progress.retainNonNull("slot0-final"));
        assertFalse(progress.accept(0, false), "late provisional regressed a final slot");
        assertTrue(progress.mayReceiveRecovery(), "slot1 has not completed yet");

        assertTrue(progress.accept(1, false));
        assertEquals("slot1-provisional", progress.retainNonNull("slot1-provisional"));
        assertTrue(progress.accept(1, true));
        assertEquals("slot1-provisional", progress.retainNonNull(null),
                "a null final erased the useful provisional value");
        assertFalse(progress.mayReceiveRecovery(),
                "all finals were observed before the assembled message was emitted");
        assertTrue(progress.allTrackedSlotsFinal());
    }
}
