package com.dragonmeow.mctranslator.translate;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Translation worker pool with bounded, FIFO high/background lanes.
 *
 * <p>Interactive work overtakes queued warmups, but remains FIFO with other
 * interactive work. A short high-priority burst is followed by one background
 * task when both lanes are populated, so neither repeated hovering nor a large
 * crawl can permanently starve the other lane.</p>
 */
public final class PriorityTranslationExecutor extends ThreadPoolExecutor {

    public static final int DEFAULT_QUEUE_CAPACITY = 512;
    private static final int MAX_HIGH_BURST = 8;

    private final BoundedPriorityQueue queue;

    public PriorityTranslationExecutor(int workers, ThreadFactory factory) {
        this(workers, factory, DEFAULT_QUEUE_CAPACITY);
    }

    /** Public for deterministic saturation tests and constrained embedders. */
    public PriorityTranslationExecutor(int workers, ThreadFactory factory, int queueCapacity) {
        this(Math.max(1, workers), factory,
                new BoundedPriorityQueue(Math.max(1, queueCapacity)));
    }

    private PriorityTranslationExecutor(int workers, ThreadFactory factory,
                                        BoundedPriorityQueue queue) {
        super(workers, workers, 0L, TimeUnit.MILLISECONDS, queue, factory);
        this.queue = queue;
        prestartAllCoreThreads();
    }

    /** Default submissions are interactive. Saturation is a safe drop, not a caller crash. */
    @Override
    public void execute(Runnable command) {
        tryExecuteHigh(command);
    }

    public void executeHigh(Runnable command) {
        tryExecuteHigh(command);
    }

    public void executeLow(Runnable command) {
        tryExecuteLow(command);
    }

    /**
     * Attempts to submit interactive work without throwing on shutdown or saturation.
     * Callers that own request state can use the result to close it immediately.
     */
    public boolean tryExecuteHigh(Runnable command) {
        return enqueue(command, true);
    }

    /** Attempts to submit background work without throwing on shutdown or saturation. */
    public boolean tryExecuteLow(Runnable command) {
        return enqueue(command, false);
    }

    private boolean enqueue(Runnable command, boolean high) {
        Objects.requireNonNull(command, "command");
        if (isShutdown()) return false;
        boolean accepted = high ? queue.offerHigh(command) : queue.offerLow(command);
        if (!accepted) return false;
        // Close the race with shutdownNow(): work accepted after its drain must not
        // remain stranded in a stopped executor.
        if (isShutdown() && queue.remove(command)) return false;
        return true;
    }

    /** Visible for deterministic unit tests and diagnostics. */
    public List<Runnable> queuedSnapshot() {
        return queue.snapshot();
    }

    /** One shared hard capacity with separate FIFO lanes. */
    private static final class BoundedPriorityQueue extends AbstractQueue<Runnable>
            implements BlockingQueue<Runnable> {
        private final int capacity;
        private final ArrayDeque<Runnable> high = new ArrayDeque<>();
        private final ArrayDeque<Runnable> low = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition notEmpty = lock.newCondition();
        private final Condition notFull = lock.newCondition();
        private int highBurst;

        private BoundedPriorityQueue(int capacity) {
            this.capacity = capacity;
        }

        boolean offerHigh(Runnable command) {
            return offerLane(command, high);
        }

        boolean offerLow(Runnable command) {
            return offerLane(command, low);
        }

        private boolean offerLane(Runnable command, ArrayDeque<Runnable> lane) {
            Objects.requireNonNull(command, "command");
            lock.lock();
            try {
                if (sizeLocked() >= capacity) return false;
                lane.addLast(command);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean offer(Runnable command) {
            return offerLow(command);
        }

        @Override
        public void put(Runnable command) throws InterruptedException {
            Objects.requireNonNull(command, "command");
            lock.lockInterruptibly();
            try {
                while (sizeLocked() >= capacity) notFull.await();
                low.addLast(command);
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean offer(Runnable command, long timeout, TimeUnit unit)
                throws InterruptedException {
            Objects.requireNonNull(command, "command");
            long nanos = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (sizeLocked() >= capacity) {
                    if (nanos <= 0L) return false;
                    nanos = notFull.awaitNanos(nanos);
                }
                low.addLast(command);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (sizeLocked() == 0) notEmpty.await();
                return removeNextLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (sizeLocked() == 0) {
                    if (nanos <= 0L) return null;
                    nanos = notEmpty.awaitNanos(nanos);
                }
                return removeNextLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable poll() {
            lock.lock();
            try {
                return sizeLocked() == 0 ? null : removeNextLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable peek() {
            lock.lock();
            try {
                if (high.isEmpty()) return low.peekFirst();
                if (low.isEmpty() || highBurst < MAX_HIGH_BURST) return high.peekFirst();
                return low.peekFirst();
            } finally {
                lock.unlock();
            }
        }

        private Runnable removeNextLocked() {
            Runnable next;
            if (!high.isEmpty() && (low.isEmpty() || highBurst < MAX_HIGH_BURST)) {
                next = high.removeFirst();
                highBurst = low.isEmpty() ? 0 : highBurst + 1;
            } else {
                next = low.removeFirst();
                highBurst = 0;
            }
            notFull.signal();
            return next;
        }

        @Override
        public int remainingCapacity() {
            lock.lock();
            try {
                return capacity - sizeLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int drainTo(java.util.Collection<? super Runnable> target) {
            return drainTo(target, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(java.util.Collection<? super Runnable> target, int maxElements) {
            Objects.requireNonNull(target, "target");
            if (target == this) throw new IllegalArgumentException("cannot drain to self");
            if (maxElements <= 0) return 0;
            lock.lock();
            try {
                int drained = 0;
                while (drained < maxElements && sizeLocked() > 0) {
                    target.add(removeNextLocked());
                    drained++;
                }
                if (drained > 0) notFull.signalAll();
                return drained;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean remove(Object candidate) {
            lock.lock();
            try {
                boolean removed = high.remove(candidate) || low.remove(candidate);
                if (removed) notFull.signal();
                return removed;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void clear() {
            lock.lock();
            try {
                if (sizeLocked() == 0) return;
                high.clear();
                low.clear();
                highBurst = 0;
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Iterator<Runnable> iterator() {
            return snapshot().iterator();
        }

        @Override
        public int size() {
            lock.lock();
            try {
                return sizeLocked();
            } finally {
                lock.unlock();
            }
        }

        private int sizeLocked() {
            return high.size() + low.size();
        }

        List<Runnable> snapshot() {
            lock.lock();
            try {
                List<Runnable> out = new ArrayList<>(sizeLocked());
                out.addAll(high);
                out.addAll(low);
                return List.copyOf(out);
            } finally {
                lock.unlock();
            }
        }
    }
}
