package com.dragonmeow.nyanslate.service;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.translate.ParagraphModel;
import com.dragonmeow.nyanslate.translate.TooltipTraceWriter;
import com.dragonmeow.nyanslate.translate.TranslationException;
import com.dragonmeow.nyanslate.translate.TranslationResult;
import com.dragonmeow.nyanslate.translate.Translator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TranslationService#setTooltipTraceWriter} actually fires from {@code
 *  composeStructuredTooltip} (the real render path, via {@link
 *  TranslationService#translateItemLine}), writes nothing while disabled, and includes the
 *  REAL localized/masked segment key — not just the raw text — once enabled. */
class TranslationServiceTooltipTraceWiringTest {

    private static final Executor DIRECT = Runnable::run;

    private static class StubTranslator implements Translator {
        @Override
        public TranslationResult translate(String text, String targetLang) {
            return new TranslationResult("[" + text + "]", "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                        List<String> surfaceContext) {
            List<TranslationResult> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(new TranslationResult("[" + t + "]", "en"));
            return out;
        }

        @Override
        public List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                                    List<List<String>> itemContexts)
                throws TranslationException {
            return translateBatch(texts, targetLang, null);
        }
    }

    private static TranslatorConfig aiTooltipConfig() {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        return cfg;
    }

    private static String tooltip(String scroll1, String scroll2, String statsRow) {
        return ParagraphModel.join(List.of("● " + scroll1, "● " + scroll2, statsRow));
    }

    @Test
    void disabledOverlayWritesNothing() throws IOException {
        TranslatorConfig cfg = aiTooltipConfig();
        TranslationCache ai = new TranslationCache(new StubTranslator(), cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(new StubTranslator(), cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, google, ai);

        Path dir = Files.createTempDirectory("nyanslate-trace-wiring-test").resolve("nyanslate-debug");
        TooltipTraceWriter trace = new TooltipTraceWriter(dir, () -> false, 20);
        s.setTooltipTraceWriter(trace);

        cfg.translationRequestsEnabled = false;
        s.translateItemLine(tooltip("Implosion", "Wither Shield",
                "⟦CS7⟧Crit Chance:⟦/CS7⟧ ⟦CS8⟧50%⟦/CS8⟧"));

        assertFalse(Files.exists(dir), "overlay off must never write a trace file");
    }

    @Test
    void enabledOverlayWritesTheRealMaskedKeyNotJustTheRawGloballyNumberedText() throws Exception {
        TranslatorConfig cfg = aiTooltipConfig();
        TranslationCache ai = new TranslationCache(new StubTranslator(), cfg.targetLang, DIRECT, 100);
        TranslationCache google = new TranslationCache(new StubTranslator(), cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, google, ai);

        Path dir = Files.createTempDirectory("nyanslate-trace-wiring-test").resolve("nyanslate-debug");
        AtomicBoolean overlayOn = new AtomicBoolean(true);
        TooltipTraceWriter trace = new TooltipTraceWriter(dir, overlayOn::get, 20);
        s.setTooltipTraceWriter(trace);

        cfg.translationRequestsEnabled = false;
        String item = tooltip("Implosion", "Wither Shield",
                "⟦CS7⟧Crit Chance:⟦/CS7⟧ ⟦CS8⟧50%⟦/CS8⟧");
        s.translateItemLine(item);

        String content = soleFileContent(dir);
        assertTrue(content.contains("kind=STATS"), "the STATS segment must be traced: " + content);
        // The RAW segment text still shows the item's OWN global CS7/CS8 offset...
        assertTrue(content.contains("⟦CS7⟧Crit Chance:⟦/CS7⟧"), "raw text present: " + content);
        // ...but the real cache KEY the trace reports must be the position-independent,
        // LOCALLY renumbered + masked form (CS0/CS1) resolveEnchantName/requestEnchantName
        // actually look up/request under (TranslationTemplate's own further MT-slot
        // templating happens one layer deeper, inside TranslationCache itself).
        assertTrue(content.contains("⟦CS0⟧Crit Chance:⟦/CS0⟧ ⟦CS1⟧50%⟦/CS1⟧"),
                "localized+masked key must be present, independent of the item's own CS7/CS8 "
                        + "offset: " + content);
    }

    /** The trace write happens on {@link TooltipTraceWriter}'s own background thread
     *  (never a package-private test hook reachable from this package) -- poll briefly
     *  instead of sleeping a fixed amount. */
    private static String soleFileContent(Path dir) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    List<Path> files = stream
                            .filter(p -> p.getFileName().toString().startsWith("tooltip-trace-"))
                            .toList();
                    if (!files.isEmpty()) return Files.readString(files.get(0));
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("expected at least one tooltip trace file under " + dir);
    }
}
