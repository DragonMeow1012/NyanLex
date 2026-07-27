package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.translate.PriorityTranslationExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriorityTranslationExecutorTest {

    @Test
    void interactiveTaskOvertakesQueuedBackgroundWork() throws Exception {
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
}
