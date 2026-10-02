package com.dragonmeow.nyanslate.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LangProbeTest {
    private static LangProbe.Node tr(String key, String... args) {
        LangProbe.Node n = new LangProbe.Node(key, false);
        n.argTypes.addAll(List.of(args));
        return n;
    }

    private static LangProbe.Node lit() {
        return new LangProbe.Node(null, true);
    }

    @Test
    void classifiesShapes() {
        assertEquals(LangProbe.Shape.PURE_TRANSLATABLE_ROOT, LangProbe.classify(tr("item.minecraft.bow")));
        assertEquals(LangProbe.Shape.LITERAL, LangProbe.classify(lit()));

        LangProbe.Node mixedRoot = new LangProbe.Node(null, false);
        mixedRoot.children.add(lit());
        mixedRoot.children.add(tr("item.durability", "number", "number"));
        assertEquals(LangProbe.Shape.MIXED, LangProbe.classify(mixedRoot));

        LangProbe.Node trWithSibling = tr("a.b");
        trWithSibling.siblingCount = 1;
        trWithSibling.children.add(lit());
        assertEquals(LangProbe.Shape.MIXED, LangProbe.classify(trWithSibling));

        // translatable hidden in an arg of a literal root still counts as mixed
        LangProbe.Node literalWithArg = lit();
        literalWithArg.children.add(tr("x.y"));
        assertEquals(LangProbe.Shape.MIXED, LangProbe.classify(literalWithArg));
    }

    @Test
    void zeroCostWhenDisabledAndNeverStartsThread(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("lang-probe.json");
        LangProbe probe = new LangProbe(out, () -> false, 10);
        probe.observe("tooltip", 1, tr("item.minecraft.bow"));
        probe.flush();
        assertFalse(Files.exists(out));
        assertTrue(probe.buildReport().get("surfaces") instanceof Map<?, ?> m && m.isEmpty());
    }

    @Test
    void countsDistinctLinesAndKeysWithoutValues(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("sub/lang-probe.json");
        AtomicBoolean on = new AtomicBoolean(true);
        LangProbe probe = new LangProbe(out, on::get, 60_000);
        probe.setLookup(new LangProbe.LangLookup() {
            @Override public Boolean targetHas(String key) { return key.startsWith("item."); }
            @Override public Boolean englishHas(String key) { return true; }
        });
        probe.observe("tooltip", 11, tr("item.minecraft.bow"));
        probe.observe("tooltip", 11, tr("item.minecraft.bow")); // per-frame repeat
        probe.observe("tooltip", 12, lit());
        LangProbe.Node mixed = new LangProbe.Node(null, false);
        mixed.children.add(tr("custom.key", "string", "component"));
        probe.observe("chat", 13, mixed);
        probe.flush();

        String json = Files.readString(out);
        assertTrue(json.contains("\"item.minecraft.bow\""));
        assertTrue(json.contains("\"custom.key\""));
        assertTrue(json.contains("\"inTargetLang\": true"));
        assertTrue(json.contains("\"inTargetLang\": false"));
        assertTrue(json.contains("\"pureTranslatableRoot\": 1"));
        assertTrue(json.contains("\"observations\": 3"));
        assertTrue(json.contains("\"mixedWithTranslatableChild\": 1"));
        assertFalse(json.contains(".tmp"));
    }

    @Test
    void capsKeys(@TempDir Path dir) {
        LangProbe probe = new LangProbe(dir.resolve("p.json"), () -> true, 60_000);
        for (int i = 0; i < LangProbe.MAX_KEYS + 50; i++) probe.observe("s", i, tr("k." + i));
        Map<String, Object> summary = (Map<String, Object>) probe.buildReport().get("summary");
        assertEquals(LangProbe.MAX_KEYS, summary.get("distinctKeysRecorded"));
        assertEquals(50L, summary.get("keysOverCap"));
    }
}
