package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Width-trim translation: the whole label is translated before the trim cuts it. */
class TrimTranslationTest {
    private static final Executor DIRECT = Runnable::run;

    private static UnaryOperator<String> screenText(TranslationService s) {
        return str -> {
            TranslationDecision d = s.translateScreenString(str);
            return d.changed() ? d.translated() : str;
        };
    }

    private static TranslationService service(TranslatorConfig cfg, AtomicInteger requests,
                                              Map<String, String> repo) {
        Translator counting = (text, target) -> {
            requests.incrementAndGet();
            return new TranslationResult("T:" + text, "en");
        };
        TranslationService service = new TranslationService(cfg,
                new TranslationCache(counting, cfg.targetLang, DIRECT, 1000),
                new TranslationCache(counting, cfg.targetLang, DIRECT, 1000));
        service.setHubLookup(repo::get);
        return service;
    }

    private static TranslatorConfig config() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiScreenText = true;
        return cfg;
    }

    @Test
    void translationIsWhatTheTrimCuts() {
        AtomicInteger requests = new AtomicInteger();
        TranslationService s = service(config(), requests, Map.of("Color scheme", "色彩配置"));
        assertEquals("色彩配置", TrimTranslation.resolve("Color scheme", screenText(s), () -> false));
        assertEquals(0, requests.get());
    }

    @Test
    void untranslatedTextIsReturnedUntouched() {
        AtomicInteger requests = new AtomicInteger();
        TranslationService s = service(config(), requests, Map.of());
        String text = "Unknown label";
        assertSame(text, TrimTranslation.resolve(text, screenText(s), () -> false));
    }

    @Test
    void textInputCallerKeepsTheOriginal() {
        AtomicInteger requests = new AtomicInteger();
        TranslationService s = service(config(), requests, Map.of("Color scheme", "色彩配置"));
        String text = "Color scheme";
        assertSame(text, TrimTranslation.resolve(text, screenText(s), () -> true));
    }

    @Test
    void callerIsNotInspectedWhenThereIsNothingToReplace() {
        AtomicInteger inspected = new AtomicInteger();
        String text = "Plain";
        assertSame(text, TrimTranslation.resolve(text, str -> str, () -> {
            inspected.incrementAndGet();
            return true;
        }));
        assertEquals(0, inspected.get());
    }

    @Test
    void multiLineAndTinyStringsSkipThePipeline() {
        AtomicInteger calls = new AtomicInteger();
        UnaryOperator<String> counting = str -> {
            calls.incrementAndGet();
            return "x" + str;
        };
        assertEquals("a\nb", TrimTranslation.resolve("a\nb", counting, () -> false));
        assertEquals("a", TrimTranslation.resolve("a", counting, () -> false));
        assertEquals(null, TrimTranslation.resolve(null, counting, () -> false));
        assertEquals(0, calls.get());
    }

    @Test
    void interfaceTextDisplayOffLeavesTheStringAlone() {
        AtomicInteger requests = new AtomicInteger();
        TranslatorConfig cfg = config();
        cfg.screenTextMode = DisplayMode.ORIGINAL_ONLY;
        TranslationService s = service(cfg, requests, Map.of("Color scheme", "色彩配置"));
        String text = "Color scheme";
        assertSame(text, TrimTranslation.resolve(text, screenText(s), () -> false));
        assertEquals(0, requests.get());
    }

    @Test
    void onlineTranslationOffSendsNoRequestForAMiss() {
        for (boolean ai : new boolean[] {false, true}) {
            AtomicInteger requests = new AtomicInteger();
            TranslatorConfig cfg = config();
            cfg.translationRequestsEnabled = false;
            cfg.aiScreenText = ai;
            TranslationService s = service(cfg, requests, Map.of("Clouds", "雲"));
            assertEquals("Vol. clouds reflection",
                    TrimTranslation.resolve("Vol. clouds reflection", screenText(s), () -> false));
            assertEquals("雲層", TrimTranslation.resolve("Clouds", str -> "雲層", () -> false));
            s.flushBatches();
            assertEquals(0, requests.get(), "ai=" + ai);
        }
    }

    // ------------------------------------------------------------------ the text-input caller check

    /** Stands in for a vanilla edit box: matched by its (injected) class name. */
    static class FakeEditBox {
        static boolean askTrimCaller(Set<String> names) {
            return TrimTranslation.callerIsTextInput(c -> false, names);
        }
    }

    static class FakeEditBoxSubclass extends FakeEditBox {
        static boolean askFromSubclass(Set<String> names) {
            return TrimTranslation.callerIsTextInput(c -> false, names);
        }
    }

    static class FakeLabel {
        static boolean askTrimCaller(Set<String> names) {
            return TrimTranslation.callerIsTextInput(c -> false, names);
        }
    }

    @Test
    void theCallerCheckSeesThroughThisClassToTheRealCaller() {
        Set<String> names = Set.of(FakeEditBox.class.getName());
        assertTrue(FakeEditBox.askTrimCaller(names), "a text input asked: it is recognised");
        assertTrue(FakeEditBoxSubclass.askFromSubclass(names), "a subclass of a text input too");
        assertFalse(FakeLabel.askTrimCaller(names), "an ordinary label is not a text input");
    }

    @Test
    void theGluesOwnFramesAreSkippedToo() {
        Set<String> names = Set.of(FakeEditBox.class.getName());
        // The glue class between this class and the caller is named by the skip predicate.
        assertTrue(TrimTranslationTest.viaGlue(names));
    }

    /** Plays the loader glue: it calls the check and is skipped by name. */
    static boolean viaGlue(Set<String> names) {
        return FakeEditBoxCallsThroughGlue.ask(names);
    }

    static class Glue {
        static boolean ask(Set<String> names) {
            return TrimTranslation.callerIsTextInput(c -> c == Glue.class, names);
        }
    }

    static class FakeEditBoxCallsThroughGlue extends FakeEditBox {
        static boolean ask(Set<String> names) {
            return Glue.ask(names);
        }
    }

    @Test
    void vanillaTextInputClassesAreRecognisedByName() throws ClassNotFoundException {
        for (String name : TrimTranslation.TEXT_INPUT_CLASS_NAMES) {
            assertTrue(name.startsWith("net.minecraft.client.gui.components."), name);
        }
        assertFalse(TrimTranslation.isTextInputClass(String.class));
        assertFalse(TrimTranslation.isTextInputClass(null));
    }

    @Test
    void aScreenWithoutATextInputNeverReachesTheStackWalk() {
        TrimTranslation.TextInputGate gate = new TrimTranslation.TextInputGate();
        Set<String> names = Set.of(FakeEditBox.class.getName());
        Object screen = new Object();
        List<Object> widgets = new java.util.ArrayList<>(List.of("button", "slider", new FakeLabel()));
        assertFalse(gate.hasTextInput(screen, widgets, names));

        // A text input appears among the widgets: the answer follows when the count changes.
        widgets.add(new FakeEditBox());
        assertTrue(gate.hasTextInput(screen, widgets, names));
        // Another screen object is asked again from scratch.
        assertFalse(gate.hasTextInput(new Object(), List.of("button"), names));
        assertFalse(gate.hasTextInput(null, widgets, names));
        assertFalse(gate.hasTextInput(screen, null, names));
    }

    @Test
    void theGateIsAskedOncePerScreenNotOncePerLabel() {
        TrimTranslation.TextInputGate gate = new TrimTranslation.TextInputGate();
        Set<String> names = Set.of(FakeEditBox.class.getName());
        Object screen = new Object();
        int[] scanned = {0};
        List<Object> widgets = new java.util.ArrayList<>() {
            @Override
            public java.util.Iterator<Object> iterator() {
                scanned[0]++;
                return super.iterator();
            }
        };
        widgets.add("button");
        widgets.add(new FakeLabel());
        for (int frame = 0; frame < 1_000; frame++) {
            assertFalse(gate.hasTextInput(screen, widgets, names));
        }
        assertEquals(1, scanned[0], "the widget list is scanned once, then remembered");
    }
}
