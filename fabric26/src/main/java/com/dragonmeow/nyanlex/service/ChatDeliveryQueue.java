package com.dragonmeow.nyanlex.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks chat entries in both receive order and ready order.
 *
 * <p>The loader owns rendering and timeout behavior; this class owns only the
 * ordering policy so every supported loader drains completed translations in
 * the same way. All state transitions use the queue monitor: a provider
 * callback may complete on a worker while the client thread is adding or
 * draining entries without exposing a half-added record.</p>
 */
public final class ChatDeliveryQueue<T> {

    private final Deque<T> received = new ArrayDeque<>();
    private final Deque<T> ready = new ArrayDeque<>();
    private final Set<T> queuedEntries = identitySet();
    private final Set<T> readyEntries = identitySet();

    public synchronized void addLast(T entry) {
        Objects.requireNonNull(entry, "entry");
        if (!queuedEntries.add(entry)) {
            throw new IllegalArgumentException("Entry is already queued");
        }
        received.addLast(entry);
    }

    /** Records the first transition to ready; repeated completion callbacks are ignored. */
    public synchronized void markReady(T entry) {
        if (!queuedEntries.contains(entry) || !readyEntries.add(entry)) return;
        ready.addLast(entry);
    }

    /**
     * Removes entries that can now be displayed.
     *
     * @param preserveReceiveOrder true to drain only the ready receive-order prefix;
     *                             false to drain in actual ready order
     */
    public synchronized List<T> drainReady(boolean preserveReceiveOrder) {
        List<T> drained = new ArrayList<>();
        if (preserveReceiveOrder) {
            while (!received.isEmpty() && readyEntries.contains(received.peekFirst())) {
                T entry = received.removeFirst();
                retire(entry);
                drained.add(entry);
            }
            return drained;
        }

        while (!ready.isEmpty()) {
            T entry = ready.removeFirst();
            if (!readyEntries.remove(entry) || !queuedEntries.remove(entry)) continue;
            removeIdentity(received, entry);
            drained.add(entry);
        }
        return drained;
    }

    public synchronized boolean isEmpty() {
        return received.isEmpty();
    }

    public synchronized T peekFirst() {
        return received.peekFirst();
    }

    /** Removes the oldest received entry, including any pending ready-order record. */
    public synchronized T removeFirst() {
        T entry = received.removeFirst();
        retire(entry);
        return entry;
    }

    /**
     * Removes one exact queue entry. Equality is deliberately ignored: chat
     * records may carry equal text while still representing different messages.
     */
    public synchronized boolean remove(T entry) {
        if (!queuedEntries.remove(entry)) return false;
        removeIdentity(received, entry);
        if (readyEntries.remove(entry)) removeIdentity(ready, entry);
        return true;
    }

    public synchronized int size() {
        return received.size();
    }

    public synchronized void clear() {
        received.clear();
        ready.clear();
        queuedEntries.clear();
        readyEntries.clear();
    }

    private void retire(T entry) {
        queuedEntries.remove(entry);
        if (readyEntries.remove(entry)) removeIdentity(ready, entry);
    }

    private static <T> void removeIdentity(Deque<T> entries, T target) {
        Iterator<T> iterator = entries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() != target) continue;
            iterator.remove();
            return;
        }
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
