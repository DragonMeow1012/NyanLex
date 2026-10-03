package com.dragonmeow.nyanlex.warmup;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ContentWarmupSourceTest {
    public static final class LegacyClientQuestFile {
        public static Object INSTANCE = "legacy";
        public static Object getInstance() { return "modern-should-not-win"; }
    }

    public static final class ModernClientQuestFile {
        @SuppressWarnings("unused")
        private static final Object INSTANCE = "private-modern";
        public static Object getInstance() { return "modern"; }
    }

    public static final class TransitionalClientQuestFile {
        public static Object INSTANCE;
        public static Object getInstance() { return "accessor-fallback"; }
    }

    public static final class UnsupportedClientQuestFile {
    }

    public record Quest(String title, String subtitle, List<String> description, List<Quest> children) {
        public String getTitle() { return title; }
        public String getSubtitle() { return subtitle; }
        public List<String> getDescription() { return description; }
        public List<Quest> getChildren() { return children; }
    }

    @Test
    void loadedQuestsAreProbedWithinBudgetUsingTheDisplayRequestPlanner() {
        Quest task = new Quest("Find the portal", "Into the sky", List.of("First paragraph", "Second paragraph"), List.of());
        Quest chapter = new Quest("A new world", "", List.of(), List.of(task));
        AtomicReference<Object> loaded = new AtomicReference<>(chapter);
        List<String> planned = new ArrayList<>();
        QuestWarmupSource source = new QuestWarmupSource(loaded::get, value -> {
            planned.add((String) value);
            return List.of((String) value);
        });
        source.reset();
        assertEquals(2, source.totalItemCount());
        assertTrue(planned.isEmpty(), "reset enumerates objects without building their text");
        assertEquals(List.of("A new world"), source.probeNext(1).get(0).sources());
        assertFalse(source.isExhausted());
        ItemWarmupTarget next = source.probeNext(1).get(0);
        assertEquals(WarmupCategory.SCREEN, next.category());
        assertEquals(List.of("Find the portal", "Into the sky", "First paragraph", "Second paragraph"), next.sources());
        assertTrue(source.isExhausted());
        loaded.set(null);
        assertFalse(source.isAvailable(), "leaving a world must not submit its stale quest content");
        source.reset();
        assertEquals(0, source.totalItemCount());
        assertTrue(source.isExhausted());
    }

    @Test
    void ftbQuestFileLookupSupportsLegacyAndModernApis() {
        assertEquals("legacy", QuestWarmupSource.loadedClientFile(LegacyClientQuestFile.class));
        assertEquals("modern", QuestWarmupSource.loadedClientFile(ModernClientQuestFile.class));
        assertEquals("accessor-fallback",
                QuestWarmupSource.loadedClientFile(TransitionalClientQuestFile.class));
        assertNull(QuestWarmupSource.loadedClientFile(UnsupportedClientQuestFile.class));
    }

    @Test
    void selectionsDefaultToAllAndAreSnapshottedUntilTheNextExplicitRun() {
        TranslatorConfig cfg = new TranslatorConfig();
        assertTrue(cfg.warmupItems && cfg.warmupScreenText);
        Quest screenRoot = new Quest("Interface source", "", List.of(), List.of());
        QuestWarmupSource screen = new QuestWarmupSource(() -> screenRoot, value -> List.of((String) value));
        // Stable identities represent the loaded data, as in the real client integration.
        Quest itemRoot = new Quest("Item source", "", List.of(), List.of());
        QuestWarmupSource items = new QuestWarmupSource(() -> itemRoot, value -> List.of((String) value));
        ContentWarmupSource source = new ContentWarmupSource(items, screen, () -> cfg);
        source.reset();
        assertEquals(2, source.totalItemCount());
        cfg.warmupItems = false;
        assertEquals(2, source.totalItemCount());
        source.reset();
        assertEquals(1, source.totalItemCount());
        assertEquals(List.of("Interface source"), source.probeNext(1).get(0).sources());
        assertTrue(source.isExhausted());
        cfg.warmupScreenText = false;
        source.reset();
        assertEquals(0, source.totalItemCount());
        assertTrue(source.isExhausted());
    }
}
