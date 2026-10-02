package com.dragonmeow.nyanslate.translate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TooltipTraceWriter}: the per-segment companion to {@link ExchangeDumpWriter},
 * gated the same way (only while {@code enabled} reports {@code true} at call time),
 * rotated the same way (oldest-by-filename pruning under {@code maxFiles}).
 */
class TooltipTraceWriterTest {

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanslate-tooltip-trace-test");
    }

    @Test
    void disabledNeverWritesAnything() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        TooltipTraceWriter writer = new TooltipTraceWriter(dir, () -> false, 20);
        writer.record("original", List.of(
                new TooltipTraceWriter.SegmentTrace("TRADE", "raw", "key", "ok")), "composed");
        writer.awaitIdleForTest();
        assertFalse(Files.exists(dir), "nothing should ever be written while disabled");
    }

    @Test
    void enabledWritesOriginalSegmentsAndFinalDisplay() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        TooltipTraceWriter writer = new TooltipTraceWriter(dir, () -> true, 20);
        writer.record("⟦CS0⟧Crit Chance:⟦/CS0⟧ ⟦CS1⟧50%⟦/CS1⟧", List.of(
                new TooltipTraceWriter.SegmentTrace("STATS",
                        "⟦CS0⟧Crit Chance:⟦/CS0⟧ ⟦CS1⟧50%⟦/CS1⟧",
                        "⟦CS0⟧Crit Chance:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧", "ok")),
                "⟦CS0⟧暴擊率：⟦/CS0⟧ ⟦CS1⟧50%⟦/CS1⟧");
        writer.awaitIdleForTest();

        String content = soleFileContent(dir);
        assertTrue(content.contains("Crit Chance"), "original must be present: " + content);
        assertTrue(content.contains("kind=STATS"), "segment kind must be present: " + content);
        assertTrue(content.contains("verdict=ok"), "verdict must be present: " + content);
        assertTrue(content.contains("⟦MT0⟧"), "the real masked/templated key must be present: " + content);
        assertTrue(content.contains("暴擊率"), "the final display must be present: " + content);
    }

    @Test
    void missingSegmentHasNoKeyLineButStillRecordsTheVerdict() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        TooltipTraceWriter writer = new TooltipTraceWriter(dir, () -> true, 20);
        writer.record("original", List.of(
                new TooltipTraceWriter.SegmentTrace("RARITY", "LEGENDARY", null, "missing")), null);
        writer.awaitIdleForTest();

        String content = soleFileContent(dir);
        assertTrue(content.contains("verdict=missing"));
        assertFalse(content.contains("key=null"), "a null key must be omitted, not printed literally");
    }

    @Test
    void rotationKeepsOnlyTheNewestMaxFilesEntries() throws Exception {
        Path dir = tempDir().resolve("nyanslate-debug");
        TooltipTraceWriter writer = new TooltipTraceWriter(dir, () -> true, 3);
        for (int i = 0; i < 5; i++) {
            writer.record("original " + i, List.of(), "display " + i);
            writer.awaitIdleForTest();
        }
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.toList();
            assertEquals(3, files.size(), "only the newest maxFiles entries must survive");
        }
    }

    private static String soleFileContent(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.toList();
            assertTrue(files.size() >= 1, "expected at least one trace file under " + dir);
            return Files.readString(files.get(0));
        }
    }
}
