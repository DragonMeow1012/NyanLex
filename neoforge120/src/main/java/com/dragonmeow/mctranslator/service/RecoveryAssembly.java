package com.dragonmeow.mctranslator.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coordinates provisional/final results for a fixed group of independent slots.
 *
 * <p>Each slot accepts at most its first provisional and first final result. A final
 * result is monotonic: later provisional callbacks cannot overwrite it. Once every
 * slot has produced at least one result, each accepted update carries an immutable
 * snapshot so a queued client task never observes later mutations.</p>
 */
public final class RecoveryAssembly<T> {

    /**
     * Per-chat callback gate. It makes provisional/final delivery monotonic even when
     * client-thread tasks run in a different order from backend callbacks, while
     * separately tracking whether an AI recovery is still possible.
     */
    public static final class ResultProgress<T> {
        private boolean[] provisionalSeen = new boolean[0];
        private boolean[] finalSeen = new boolean[0];
        private int recoverySlots;
        private int finalCount;
        private T retainedValue;

        public synchronized void configure(int requestCount, boolean recoveryPossible) {
            int slots = Math.max(0, requestCount);
            provisionalSeen = new boolean[slots];
            finalSeen = new boolean[slots];
            recoverySlots = recoveryPossible ? slots : 0;
            finalCount = 0;
            retainedValue = null;
        }

        public synchronized boolean accept(int slot, boolean finalResult) {
            if (slot < 0) return true;
            if (slot >= finalSeen.length) return false;
            if (finalResult) {
                if (finalSeen[slot]) return false;
                finalSeen[slot] = true;
                finalCount++;
                return true;
            }
            if (provisionalSeen[slot] || finalSeen[slot]) return false;
            provisionalSeen[slot] = true;
            return true;
        }

        public synchronized boolean mayReceiveRecovery() {
            return finalCount < recoverySlots;
        }

        /** A failed final callback must not erase a useful provisional value. */
        public synchronized T retainNonNull(T candidate) {
            if (candidate != null) retainedValue = candidate;
            return retainedValue;
        }

        public synchronized boolean allTrackedSlotsFinal() {
            return finalSeen.length > 0 && finalCount == finalSeen.length;
        }
    }

    public static final class Update<T> {
        private final boolean accepted;
        private final boolean ready;
        private final boolean allFinal;
        private final List<T> values;

        private Update(boolean accepted, boolean ready, boolean allFinal, List<T> values) {
            this.accepted = accepted;
            this.ready = ready;
            this.allFinal = allFinal;
            this.values = values;
        }

        public boolean accepted() { return accepted; }
        public boolean ready() { return ready; }
        public boolean allFinal() { return allFinal; }
        public List<T> values() { return values; }
    }

    private final Object[] values;
    private final boolean[] seen;
    private final boolean[] provisionalSeen;
    private final boolean[] finalSeen;
    private int seenCount;
    private int finalCount;

    public RecoveryAssembly(int slots) {
        if (slots < 1) throw new IllegalArgumentException("slots must be positive");
        values = new Object[slots];
        seen = new boolean[slots];
        provisionalSeen = new boolean[slots];
        finalSeen = new boolean[slots];
    }

    public synchronized Update<T> accept(int slot, T value, boolean finalResult) {
        if (slot < 0 || slot >= values.length) return ignored();
        if (finalResult ? finalSeen[slot] : (provisionalSeen[slot] || finalSeen[slot])) {
            return ignored();
        }

        if (!seen[slot]) {
            seen[slot] = true;
            seenCount++;
        }
        if (value != null) values[slot] = value;
        if (finalResult) {
            finalSeen[slot] = true;
            finalCount++;
        } else {
            provisionalSeen[slot] = true;
        }

        boolean ready = seenCount == values.length;
        return new Update<>(true, ready, finalCount == values.length,
                ready ? snapshot() : Collections.emptyList());
    }

    public synchronized int size() { return values.length; }

    @SuppressWarnings("unchecked")
    private List<T> snapshot() {
        List<T> copy = new ArrayList<>(values.length);
        for (Object value : values) copy.add((T) value);
        return Collections.unmodifiableList(copy);
    }

    private Update<T> ignored() {
        return new Update<>(false, seenCount == values.length,
                finalCount == values.length, Collections.emptyList());
    }
}
