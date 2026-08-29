package com.dragonmeow.mctranslator.legacy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Java-8 receive-order/ready-order queue shared by legacy chat integration. */
final class LegacyChatDeliveryQueue<T> {
    private final Deque<T> received = new ArrayDeque<T>();
    private final Deque<T> ready = new ArrayDeque<T>();
    private final Set<T> queuedEntries = identitySet();
    private final Set<T> readyEntries = identitySet();

    synchronized void addLast(T entry) {
        if (entry == null) throw new NullPointerException("entry");
        if (!queuedEntries.add(entry)) throw new IllegalArgumentException("Entry is already queued");
        received.addLast(entry);
    }

    synchronized void markReady(T entry) {
        if (!queuedEntries.contains(entry) || !readyEntries.add(entry)) return;
        ready.addLast(entry);
    }

    synchronized List<T> drainReady(boolean preserveReceiveOrder) {
        List<T> drained = new ArrayList<T>();
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

    synchronized boolean contains(T entry) { return queuedEntries.contains(entry); }
    synchronized boolean isEmpty() { return received.isEmpty(); }
    synchronized int size() { return received.size(); }
    synchronized T peekFirst() { return received.peekFirst(); }

    synchronized T removeFirst() {
        T entry = received.removeFirst();
        retire(entry);
        return entry;
    }

    synchronized void clear() {
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
        return Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
    }
}
