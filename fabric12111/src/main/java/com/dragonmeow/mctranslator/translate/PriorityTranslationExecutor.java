package com.dragonmeow.mctranslator.translate;

import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Translation worker pool with an explicit interactive/background split.
 * Interactive work is inserted at the front; container-name prewarming is
 * appended at the back, so hovering/clicking/chat can overtake queued crawling.
 */
public final class PriorityTranslationExecutor extends ThreadPoolExecutor {

    private final LinkedBlockingDeque<Runnable> deque;

    public PriorityTranslationExecutor(int workers, ThreadFactory factory) {
        this(Math.max(1, workers), factory, new LinkedBlockingDeque<>());
    }

    private PriorityTranslationExecutor(int workers, ThreadFactory factory,
                                        LinkedBlockingDeque<Runnable> deque) {
        super(workers, workers, 0L, TimeUnit.MILLISECONDS, deque, factory);
        this.deque = deque;
        prestartAllCoreThreads();
    }

    /** Default submissions are interactive and therefore jump ahead of warmups. */
    @Override
    public void execute(Runnable command) {
        executeHigh(command);
    }

    public void executeHigh(Runnable command) {
        enqueue(command, true);
    }

    public void executeLow(Runnable command) {
        enqueue(command, false);
    }

    private void enqueue(Runnable command, boolean high) {
        if (command == null) throw new NullPointerException("command");
        if (isShutdown()) throw new RejectedExecutionException("translation executor is shut down");
        boolean accepted = high ? deque.offerFirst(command) : deque.offerLast(command);
        if (!accepted) throw new RejectedExecutionException("translation queue rejected task");
    }

    /** Visible for deterministic unit tests and diagnostics. */
    public List<Runnable> queuedSnapshot() {
        return List.copyOf(deque);
    }
}
