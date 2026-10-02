package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.translate.PriorityTranslationExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityTranslationExecutorTest {

    @Test
    void interactiveTaskOvertakesQueuedBackgroundWarmup() throws Exception {
        PriorityTranslationExecutor executor = new PriorityTranslationExecutor(1, r -> {
            Thread thread = new Thread(r, "priority-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        List<String> order = new CopyOnWriteArrayList<>();

        executor.executeHigh(() -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        executor.executeLow(() -> { order.add("background"); finished.countDown(); });
        executor.executeHigh(() -> { order.add("interactive"); finished.countDown(); });
        release.countDown();

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("interactive", "background"), order);
        executor.shutdownNow();
    }

    @Test
    void interactiveLaneIsFifoInsteadOfOfferFirstLifo() throws Exception {
        PriorityTranslationExecutor executor = executor(1, 8);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        List<String> order = new CopyOnWriteArrayList<>();

        try {
            executor.executeHigh(() -> {
                occupied.countDown();
                await(release);
            });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            executor.executeLow(() -> { order.add("background"); finished.countDown(); });
            executor.executeHigh(() -> { order.add("interactive-1"); finished.countDown(); });
            executor.executeHigh(() -> { order.add("interactive-2"); finished.countDown(); });
            release.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("interactive-1", "interactive-2", "background"), order);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void boundedQueueRejectsWithoutThrowingOnCallerThread() throws Exception {
        PriorityTranslationExecutor executor = executor(1, 2);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try {
            assertTrue(executor.tryExecuteHigh(() -> {
                occupied.countDown();
                await(release);
            }));
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            assertTrue(executor.tryExecuteLow(() -> { }));
            assertTrue(executor.tryExecuteHigh(() -> { }));
            assertFalse(executor.tryExecuteHigh(() -> { }), "shared queue capacity is hard");
            assertEquals(2, executor.queuedSnapshot().size());
            assertDoesNotThrow(() -> executor.executeHigh(() -> { }),
                    "legacy void submission must also be a safe drop");
            assertEquals(2, executor.queuedSnapshot().size());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sustainedInteractiveTrafficStillLetsBackgroundAdvance() throws Exception {
        PriorityTranslationExecutor executor = executor(1, 16);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(10);
        List<String> order = new CopyOnWriteArrayList<>();

        try {
            executor.executeHigh(() -> {
                occupied.countDown();
                await(release);
            });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            executor.executeLow(() -> { order.add("background"); finished.countDown(); });
            for (int i = 1; i <= 9; i++) {
                int sequence = i;
                executor.executeHigh(() -> {
                    order.add("interactive-" + sequence);
                    finished.countDown();
                });
            }
            release.countDown();

            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals("background", order.get(8),
                    "one low task advances after the bounded high-priority burst");
            assertEquals("interactive-9", order.get(9));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static PriorityTranslationExecutor executor(int workers, int capacity) {
        return new PriorityTranslationExecutor(workers, r -> {
            Thread thread = new Thread(r, "priority-test");
            thread.setDaemon(true);
            return thread;
        }, capacity);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
