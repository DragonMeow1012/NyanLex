package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.service.ChatDeliveryQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDeliveryQueueTest {

    @Test
    void bothPoliciesSurviveEveryCompletionPermutationOfEightEntries() {
        Entry[] entries = new Entry[8];
        for (int i = 0; i < entries.length; i++) entries[i] = new Entry("entry-" + i);

        int[] order = new int[entries.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        int permutations = 0;
        int switchScenarios = 0;
        do {
            assertPermutation(entries, order, true);
            assertPermutation(entries, order, false);
            for (int split = 0; split <= entries.length; split++) {
                assertModeSwitchPermutation(entries, order, split, true);
                assertModeSwitchPermutation(entries, order, split, false);
                switchScenarios += 2;
            }
            permutations++;
        } while (nextPermutation(order));

        assertEquals(40_320, permutations);
        assertEquals(725_760, switchScenarios);
    }

    @Test
    void receiveOrderHoldsLaterCompletedEntries() {
        ChatDeliveryQueue<String> queue = queue("first", "second", "third");

        queue.markReady("second");
        queue.markReady("third");
        assertEquals(List.of(), queue.drainReady(true));
        assertEquals("first", queue.peekFirst());

        queue.markReady("first");
        assertEquals(List.of("first", "second", "third"), queue.drainReady(true));
        assertTrue(queue.isEmpty());
    }

    @Test
    void readyFirstUsesCompletionOrderAndLeavesUnfinishedEntriesQueued() {
        ChatDeliveryQueue<String> queue = queue("first", "second", "third");

        queue.markReady("third");
        queue.markReady("second");
        assertEquals(List.of("third", "second"), queue.drainReady(false));
        assertEquals("first", queue.peekFirst());

        queue.markReady("first");
        assertEquals(List.of("first"), queue.drainReady(false));
    }

    @Test
    void switchingToReadyFirstReleasesBacklogInCompletionOrder() {
        ChatDeliveryQueue<String> queue = queue("slow", "later", "latest");
        queue.markReady("latest");
        queue.markReady("later");

        assertEquals(List.of(), queue.drainReady(true));
        assertEquals(List.of("latest", "later"), queue.drainReady(false));
        assertEquals("slow", queue.peekFirst());
    }

    @Test
    void staleRemovalAlsoRemovesItsReadyRecord() {
        ChatDeliveryQueue<String> queue = queue("first", "second");
        queue.markReady("first");

        assertEquals("first", queue.removeFirst());
        assertEquals(List.of(), queue.drainReady(false));
        assertEquals("second", queue.peekFirst());
    }

    @Test
    void arbitraryRemovalUsesIdentityAndClearsReadyState() {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<>();
        Entry first = new Entry("same");
        Entry second = new Entry("same");
        queue.addLast(first);
        queue.addLast(second);
        queue.markReady(second);

        assertTrue(queue.remove(second));
        assertFalse(queue.remove(second));
        assertEquals(1, queue.size());
        assertEquals(List.of(), queue.drainReady(false));
        assertSame(first, queue.peekFirst());
    }

    @Test
    void duplicateCompletionAndLateCompletionDoNotDuplicateDelivery() {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<>();
        Entry entry = new Entry("same");
        queue.addLast(entry);
        queue.markReady(entry);
        queue.markReady(entry);

        assertEquals(List.of(entry), queue.drainReady(false));
        queue.markReady(entry);
        assertEquals(List.of(), queue.drainReady(false));
        assertTrue(queue.isEmpty());
    }

    @Test
    void tracksEqualEntriesByIdentity() {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<>();
        Entry first = new Entry("same");
        Entry second = new Entry("same");
        queue.addLast(first);
        queue.addLast(second);
        queue.markReady(second);

        List<Entry> drained = queue.drainReady(false);
        assertEquals(1, drained.size());
        assertSame(second, drained.get(0));
        assertFalse(queue.isEmpty());
        assertSame(first, queue.peekFirst());
        assertSame(first, queue.removeFirst());
        assertTrue(queue.isEmpty());
        assertNull(queue.peekFirst());
    }

    private static ChatDeliveryQueue<String> queue(String... entries) {
        ChatDeliveryQueue<String> queue = new ChatDeliveryQueue<>();
        for (String entry : entries) queue.addLast(entry);
        return queue;
    }

    private static void assertPermutation(Entry[] entries, int[] order,
                                          boolean preserveReceiveOrder) {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<>();
        for (Entry entry : entries) queue.addLast(entry);

        List<Entry> delivered = new ArrayList<>();
        for (int index : order) {
            queue.markReady(entries[index]);
            delivered.addAll(queue.drainReady(preserveReceiveOrder));
        }

        List<Entry> expected = new ArrayList<>();
        if (preserveReceiveOrder) {
            java.util.Collections.addAll(expected, entries);
        } else {
            for (int index : order) expected.add(entries[index]);
        }
        assertEquals(expected, delivered);
        assertTrue(queue.isEmpty());
    }

    private static void assertModeSwitchPermutation(Entry[] entries, int[] order,
                                                    int split, boolean initialOrdered) {
        ChatDeliveryQueue<Entry> queue = new ChatDeliveryQueue<>();
        List<Entry> remaining = new ArrayList<>();
        List<Entry> readyOrder = new ArrayList<>();
        Map<Entry, Boolean> ready = new IdentityHashMap<>();
        Map<Entry, Boolean> delivered = new IdentityHashMap<>();
        for (Entry entry : entries) {
            queue.addLast(entry);
            remaining.add(entry);
        }

        for (int step = 0; step < order.length; step++) {
            Entry completed = entries[order[step]];
            queue.markReady(completed);
            if (containsIdentity(remaining, completed) && !ready.containsKey(completed)) {
                ready.put(completed, Boolean.TRUE);
                readyOrder.add(completed);
            }
            boolean ordered = step < split ? initialOrdered : !initialOrdered;
            List<Entry> actual = queue.drainReady(ordered);
            List<Entry> expected = modelDrain(remaining, readyOrder, ready, ordered);
            assertEquals(expected, actual,
                    "split=" + split + " initialOrdered=" + initialOrdered + " step=" + step);
            for (Entry entry : actual) {
                assertNull(delivered.put(entry, Boolean.TRUE), "entry delivered twice");
            }
        }
        assertTrue(queue.isEmpty());
        assertTrue(remaining.isEmpty());
        assertTrue(readyOrder.isEmpty());
        assertEquals(entries.length, delivered.size());
    }

    private static List<Entry> modelDrain(List<Entry> remaining, List<Entry> readyOrder,
                                          Map<Entry, Boolean> ready, boolean ordered) {
        List<Entry> drained = new ArrayList<>();
        if (ordered) {
            while (!remaining.isEmpty() && ready.containsKey(remaining.get(0))) {
                Entry entry = remaining.remove(0);
                ready.remove(entry);
                removeIdentity(readyOrder, entry);
                drained.add(entry);
            }
            return drained;
        }
        while (!readyOrder.isEmpty()) {
            Entry entry = readyOrder.remove(0);
            if (!ready.containsKey(entry) || !removeIdentity(remaining, entry)) continue;
            ready.remove(entry);
            drained.add(entry);
        }
        return drained;
    }

    private static boolean containsIdentity(List<Entry> entries, Entry target) {
        for (Entry entry : entries) if (entry == target) return true;
        return false;
    }

    private static boolean removeIdentity(List<Entry> entries, Entry target) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != target) continue;
            entries.remove(i);
            return true;
        }
        return false;
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) pivot--;
        if (pivot < 0) return false;

        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) successor--;
        int swap = values[pivot];
        values[pivot] = values[successor];
        values[successor] = swap;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
        return true;
    }

    private static final class Entry {
        private final String value;

        private Entry(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry && value.equals(entry.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
