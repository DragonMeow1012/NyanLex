package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverlayTextGateTest {

    @Test
    void growingTextIsTypingUntilItSettles() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("b", "A", 0);
        gate.observe("b", "Ah", 20);
        gate.observe("b", "Ah.", 40);
        assertTrue(gate.typing("b", 40));
        assertFalse(gate.due("b", 300, 500));
        assertTrue(gate.typing("b", 539));
        assertFalse(gate.typing("b", 540));
        assertTrue(gate.due("b", 540, 500));
    }

    @Test
    void aTextThatIsNotAnExtensionIsNotTyping() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("b", "Ah. Does this mean", 0);
        gate.observe("b", "Hm... I swear", 10);
        assertFalse(gate.typing("b", 10));
        // a shorter text that happens to be a prefix is a rewind, not typing
        gate.observe("b", "Hm...", 20);
        assertFalse(gate.typing("b", 20));
    }

    @Test
    void askingIsThrottledAndResetByAChange() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("b", "Hello there", 0);
        assertTrue(gate.due("b", 600, 500));
        gate.asked("b", 600);
        assertFalse(gate.due("b", 700, 500));
        assertFalse(gate.due("b", 600 + OverlayTextGate.REPEAT_MS - 1, 500));
        assertTrue(gate.due("b", 600 + OverlayTextGate.REPEAT_MS, 500));
        gate.observe("b", "Hello there!", 5000);
        assertFalse(gate.due("b", 5100, 500));
        assertTrue(gate.due("b", 5500, 500));
    }

    @Test
    void aRequestTheBackendDidNotTakeIsDueAgainAtOnce() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("b", "Hello there", 0);
        assertTrue(gate.due("b", 600, 500));
        // not reported as asked: nothing was queued, so the very next check must ask again
        assertTrue(gate.due("b", 601, 500));
        assertTrue(gate.due("b", 650, 500));
    }

    @Test
    void zeroSettleAsksAtOnceAndUnknownBlocksNever() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("n", "Mira", 100);
        assertTrue(gate.due("n", 100, 0));
        assertFalse(gate.due("missing", 100, 0));
        assertFalse(gate.typing("missing", 100));
    }

    @Test
    void forgettingABlockDropsItsHistory() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("b", "A", 0);
        gate.observe("b", "Ab", 10);
        gate.observe("b", null, 20);
        assertFalse(gate.typing("b", 20));
        gate.observe("b", "Abc", 30);
        assertFalse(gate.typing("b", 30));
    }

    @Test
    void clearForgetsEverything() {
        OverlayTextGate gate = new OverlayTextGate(500);
        gate.observe("b", "A", 0);
        gate.clear();
        assertFalse(gate.due("b", 10_000, 0));
    }
}
