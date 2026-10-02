package com.dragonmeow.nyanlex.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Owns the loader-independent lifetime of intercepted chat messages.
 *
 * <p>The session epoch invalidates callbacks across server/world and request
 * profile boundaries. The insertion-ordered index and delivery queue are
 * changed under the same monitor, so the configured capacity is a hard bound,
 * including while every request is unfinished.</p>
 */
public final class ChatDeliverySession<T> {

    /** Small loader-independent guard for compound chat entries such as announcements. */
    public static final class BatchBudget {
        private final int maxItems;
        private final int maxChars;
        private int items;
        private int chars;

        public BatchBudget(int maxItems, int maxChars) {
            if (maxItems < 1 || maxChars < 1) {
                throw new IllegalArgumentException("batch limits must be positive");
            }
            this.maxItems = maxItems;
            this.maxChars = maxChars;
        }

        public synchronized boolean tryReserve(int itemChars) {
            if (itemChars < 0 || items >= maxItems || itemChars > maxChars - chars) return false;
            items++;
            chars += itemChars;
            return true;
        }

        public synchronized void release(int releasedItems, int releasedChars) {
            if (releasedItems < 0 || releasedChars < 0
                    || releasedItems > items || releasedChars > chars) {
                throw new IllegalArgumentException("invalid batch reservation release");
            }
            items -= releasedItems;
            chars -= releasedChars;
        }

        public synchronized int items() { return items; }
        public synchronized int chars() { return chars; }
    }

    public enum TransitionKind {
        NONE,
        SILENT_CLEAR,
        FLUSH_ORIGINALS
    }

    public static final class Admission<T> {
        private final long epoch;
        private final T evicted;
        private final boolean evictedWasDisplayed;

        private Admission(long epoch, T evicted, boolean evictedWasDisplayed) {
            this.epoch = epoch;
            this.evicted = evicted;
            this.evictedWasDisplayed = evictedWasDisplayed;
        }

        public long epoch() { return epoch; }
        public T evicted() { return evicted; }
        public boolean evictedWasDisplayed() { return evictedWasDisplayed; }
    }

    public static final class Transition<T> {
        private final TransitionKind kind;
        private final long epoch;
        private final List<T> retired;
        private final List<T> originals;

        private Transition(TransitionKind kind, long epoch, List<T> retired,
                           List<T> originals) {
            this.kind = kind;
            this.epoch = epoch;
            this.retired = retired;
            this.originals = originals;
        }

        public TransitionKind kind() { return kind; }
        public long epoch() { return epoch; }
        public List<T> retired() { return retired; }
        public List<T> originals() { return originals; }
    }

    private final ChatDeliveryQueue<T> queue = new ChatDeliveryQueue<>();
    private final LinkedHashMap<Long, T> tracked = new LinkedHashMap<>();
    private final ToLongFunction<T> idOf;
    private final Predicate<T> displayed;
    private final int maxEntries;

    private long epoch = 1L;
    private boolean contextObserved;
    private Object connectionIdentity;
    private Object worldIdentity;
    private Object requestProfile;
    private boolean originalOnly;

    public ChatDeliverySession(ToLongFunction<T> idOf, Predicate<T> displayed,
                               int maxEntries) {
        this.idOf = Objects.requireNonNull(idOf, "idOf");
        this.displayed = Objects.requireNonNull(displayed, "displayed");
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    /** Adds a new intercepted entry and atomically enforces the hard cap. */
    public synchronized Admission<T> add(T entry) {
        Objects.requireNonNull(entry, "entry");
        long id = idOf.applyAsLong(entry);
        if (tracked.containsKey(id)) throw new IllegalArgumentException("Duplicate chat id: " + id);

        T evicted = null;
        boolean wasDisplayed = false;
        if (tracked.size() >= maxEntries) {
            // A displayed record is only retained for a possible late recovery,
            // so prefer retiring one silently before forcing an intercepted line out.
            for (T candidate : tracked.values()) {
                if (displayed.test(candidate)) {
                    evicted = candidate;
                    wasDisplayed = true;
                    break;
                }
            }
            if (evicted == null) evicted = tracked.values().iterator().next();
            tracked.remove(idOf.applyAsLong(evicted));
            queue.remove(evicted);
        }

        queue.addLast(entry);
        tracked.put(id, entry);
        return new Admission<>(epoch, evicted, wasDisplayed);
    }

    /**
     * Observes a tick boundary. Connection and world identity use reference
     * equality because a new Minecraft object denotes a new callback lifetime.
     */
    public synchronized Transition<T> observe(Object connection, Object world,
                                               Object profile, boolean isOriginalOnly) {
        Objects.requireNonNull(profile, "profile");
        if (!contextObserved) {
            contextObserved = true;
            connectionIdentity = connection;
            worldIdentity = world;
            requestProfile = profile;
            originalOnly = isOriginalOnly;
            return none();
        }

        if (connectionIdentity != connection || worldIdentity != world) {
            connectionIdentity = connection;
            worldIdentity = world;
            requestProfile = profile;
            originalOnly = isOriginalOnly;
            return clear(TransitionKind.SILENT_CLEAR);
        }

        boolean flush = !requestProfile.equals(profile) || (!originalOnly && isOriginalOnly);
        requestProfile = profile;
        originalOnly = isOriginalOnly;
        return flush ? clear(TransitionKind.FLUSH_ORIGINALS) : none();
    }

    /** Immediately applies the global original-only hotkey on the client thread. */
    public synchronized Transition<T> forceOriginalOnly() {
        originalOnly = true;
        return clear(TransitionKind.FLUSH_ORIGINALS);
    }

    public synchronized T get(long id, long expectedEpoch) {
        if (expectedEpoch != epoch) return null;
        return tracked.get(id);
    }

    public synchronized T retire(long id, long expectedEpoch) {
        if (expectedEpoch != epoch) return null;
        T entry = tracked.remove(id);
        if (entry != null) queue.remove(entry);
        return entry;
    }

    public synchronized void markReady(T entry) {
        queue.markReady(entry);
    }

    public synchronized List<T> drainReady(boolean preserveReceiveOrder) {
        return queue.drainReady(preserveReceiveOrder);
    }

    public synchronized boolean isQueueEmpty() {
        return queue.isEmpty();
    }

    public synchronized T peekFirstQueued() {
        return queue.peekFirst();
    }

    /** Removes a timed-out entry from delivery ordering but retains it for one late result. */
    public synchronized T timeoutFirstQueued() {
        return queue.removeFirst();
    }

    public synchronized List<T> retireIf(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        List<T> removed = new ArrayList<>();
        Iterator<Map.Entry<Long, T>> iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            T entry = iterator.next().getValue();
            if (!predicate.test(entry)) continue;
            iterator.remove();
            queue.remove(entry);
            removed.add(entry);
        }
        return removed;
    }

    public synchronized int trackedSize() { return tracked.size(); }
    public synchronized int queuedSize() { return queue.size(); }
    public synchronized long epoch() { return epoch; }

    private Transition<T> clear(TransitionKind kind) {
        List<T> retired = new ArrayList<>(tracked.values());
        List<T> originals = new ArrayList<>();
        if (kind == TransitionKind.FLUSH_ORIGINALS) {
            for (T entry : retired) {
                if (!displayed.test(entry)) originals.add(entry);
            }
        }
        tracked.clear();
        queue.clear();
        epoch = epoch == Long.MAX_VALUE ? 1L : epoch + 1L;
        return new Transition<>(kind, epoch,
                Collections.unmodifiableList(retired),
                Collections.unmodifiableList(originals));
    }

    private Transition<T> none() {
        return new Transition<>(TransitionKind.NONE, epoch,
                Collections.emptyList(), Collections.emptyList());
    }
}
