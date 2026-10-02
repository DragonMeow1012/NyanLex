package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.hub.HubLocalCache;
import com.dragonmeow.nyanlex.translate.TooltipSegmentPlanner;
import com.dragonmeow.nyanlex.translate.TranslationException;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import com.dragonmeow.nyanlex.translate.TrimTranslation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What hovering an item costs per frame while the AI cache is (almost) empty, so every line
 * misses: a 40-line SkyBlock-style tooltip drawn for 600 consecutive frames against a
 * translation hub holding 55 000 rows. The service does the same work the loader glue asks of
 * it each frame ({@code warmTooltipBatch} once, {@code translateItemLine} per line), with an
 * engine that never answers (the requests stay queued, like a slow or rate-limited AI).
 *
 * <p>It prints the mean and the 95th percentile per frame and a breakdown, and asserts only a
 * generous ceiling so a loaded build machine does not make it flaky; the figure to read is
 * the printed one.</p>
 */
class TooltipFrameCostTest {
    private static final int HUB_ROWS = 55_000;
    private static final int FRAMES = 600;
    private static final int WARMUP_FRAMES = 200;
    /** environment variable {@code NYANLEX_FRAMECOST_NOPREWARM} (any value) measures the first frame as it was before the warm-up. */
    private static final boolean WITH_PREWARM = System.getenv("NYANLEX_FRAMECOST_NOPREWARM") == null;

    /** Never completes: tasks are dropped, so every request stays queued or in flight. */
    private static final Executor NEVER_RUNS = task -> { };

    private static final Translator SILENT = new Translator() {
        @Override
        public TranslationResult translate(String text, String targetLang) throws TranslationException {
            throw new TranslationException("never answers");
        }
    };

    /** A Hypixel-style weapon tooltip as the glue hands it to the service: one string per
     *  visible non-blank line. */
    static List<String> skyBlockTooltip() {
        List<String> lines = new ArrayList<>(Arrays.asList(
                "Hyperion ✪✪✪✪✪➍",
                "Gear Score: 912 (1,243)",
                "Damage: +319 (+30) (+120)",
                "Strength: +281 (+30) (+60)",
                "Crit Chance: +35% (+10%)",
                "Crit Damage: +142% (+25%)",
                "Bonus Attack Speed: +7%",
                "Intelligence: +350 (+100)",
                "Ferocity: +12 (+4)",
                "Ability Damage: +10%",
                "Ultimate Wise V, One For All I, Sharpness VI, Giant Killer VII, Cleave VI",
                "Critical VII, Execute VI, Experience IV, Fire Aspect III, First Strike V",
                "Looting IV, Life Steal IV, Impaling III, Smite VII, Thunderlord VII",
                "Vampirism VI, Venomous VI, Vicious V, Champion X, Divine Gift III",
                "Dungeon Item Bonus: Your Hyperion gains stats from your Catacombs level.",
                "Item Ability: Wither Impact RIGHT CLICK",
                "Teleport 10 blocks ahead of you. Then implode dealing 10,000 damage to nearby enemies.",
                "Also applies the wither shield scroll ability reducing damage taken and granting",
                "an absorption shield for 5 seconds.",
                "Mana Cost: 300",
                "Cooldown: 5s",
                "Shadow Warp: Teleport and Implode (Scroll Ability)",
                "Wither Shield: Reduces damage taken by 10% for 5 seconds (Scroll Ability)",
                "Implosion: Deals damage to nearby enemies when you right click (Scroll Ability)",
                "Reforge: Fabled (+8% Crit Damage)",
                "Reforge Ability: Deal +8% extra damage on Critical Hits",
                "Requires Catacombs Dungeon Level 12",
                "Requires Combat Skill 24",
                "Item Rating: 912",
                "This item can be reforged",
                "This item can be upgraded",
                "Dungeon Item Quality: 50/50",
                "Soulbound Item - Do not trade this item",
                "Right-click to view recipes",
                "Shift-click to compare",
                "Sell Price: 1,000,000 Coins",
                "Item ID: HYPERION",
                "Obtained from: Catacombs Floor 7",
                "MYTHIC DUNGEON SWORD",
                "Seller: DragonMeow"));
        assertEquals(40, lines.size());
        return lines;
    }

    static HubLocalCache loadedHub(Path dir) throws IOException {
        Path file = dir.resolve("nyanlex-hub-cache-zh-tw.json");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("{\"schema\":2,\"language\":\"zh-tw\",\"rows\":{");
            for (int i = 0; i < HUB_ROWS; i++) {
                if (i > 0) writer.write(',');
                writer.write("\"" + HubKeyHash.of("hub row source text " + i) + "\":{\"v\":\"中樞列 " + i + "\"}");
            }
            writer.write("}}");
        }
        HubLocalCache hub = new HubLocalCache(dir, "zh-TW");
        assertEquals(HUB_ROWS, hub.size());
        return hub;
    }

    static TranslationService newService(HubLocalCache hub) {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        TranslationCache google = new TranslationCache(SILENT, cfg.targetLang, NEVER_RUNS, 2_000);
        TranslationCache ai = new TranslationCache(SILENT, cfg.targetLang, NEVER_RUNS, 2_000);
        TranslationService service = new TranslationService(cfg, google, ai);
        service.setBatchWindowMs(() -> 0);
        service.setHubLookup(hub::get);
        return service;
    }

    /** What the loader glue does for one drawn frame of the tooltip. */
    static void frame(TranslationService service, List<String> lines) {
        service.warmTooltipBatch(lines);
        for (String line : lines) service.translateItemLine(line);
        service.flushBatches();
    }

    private static double[] stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long n : nanos) sum += n;
        return new double[] {sum / nanos.length / 1e6, sorted[(int) (sorted.length * 0.95)] / 1e6,
                sorted[sorted.length - 1] / 1e6};
    }

    private interface Step {
        void run(String line);
    }

    /** Mean milliseconds per frame (all 40 lines) of one isolated step. */
    private static double perFrameMillis(List<String> lines, Step step) {
        for (int i = 0; i < WARMUP_FRAMES; i++) for (String line : lines) step.run(line);
        long[] nanos = new long[FRAMES];
        for (int f = 0; f < FRAMES; f++) {
            long start = System.nanoTime();
            for (String line : lines) step.run(line);
            nanos[f] = System.nanoTime() - start;
        }
        return stats(nanos)[0];
    }

    /** The same tooltip with the colour-run markers the glue puts into every multi-colour line:
     *  grey label, coloured value. */
    static List<String> colouredSkyBlockTooltip() {
        List<String> out = new ArrayList<>();
        int run = 0;
        for (String line : skyBlockTooltip()) {
            int colon = line.indexOf(": ");
            if (colon > 0 && colon < 28) {
                out.add("⟦CS0⟧" + line.substring(0, colon + 2) + "⟦/CS0⟧"
                        + "⟦CS1⟧" + line.substring(colon + 2) + "⟦/CS1⟧");
            } else {
                out.add(line);
            }
            run++;
        }
        assertEquals(run, out.size());
        return out;
    }

    /** One full measurement; returns {mean, p95, max, firstFrame} in milliseconds. */
    private static double[] measureFrames(String label, List<String> lines, HubLocalCache hub) {
        // What the game does while it is still loading (see TooltipPrewarm); the "first frame"
        // below is then the first hover after startup.
        if (WITH_PREWARM) TooltipPrewarm.run();
        TranslationService service = newService(hub);
        long coldStart = System.nanoTime();
        frame(service, lines);
        double firstFrameMs = (System.nanoTime() - coldStart) / 1e6;

        for (int i = 0; i < WARMUP_FRAMES; i++) frame(service, lines);
        long[] nanos = new long[FRAMES];
        for (int f = 0; f < FRAMES; f++) {
            long start = System.nanoTime();
            frame(service, lines);
            nanos[f] = System.nanoTime() - start;
        }
        double[] total = stats(nanos);

        // Breakdown, each step on its own (so the parts need not sum to the total exactly).
        double hubMs = perFrameMillis(lines, line -> hub.get(line));
        double segmentMs = perFrameMillis(lines, line -> TooltipSegmentPlanner.plan(line, true));
        double trimMs = perFrameMillis(lines, line ->
                TrimTranslation.resolve(line, text -> {
                    TranslationDecision decision = service.translateScreenString(text);
                    return decision.changed() ? decision.translated() : text;
                }, () -> false));
        double warmMs = perFrameMillis(List.of(""), ignored -> service.warmTooltipBatch(lines));
        double lookupMs = perFrameMillis(lines, line -> service.translateItemLine(line));

        System.out.printf("[tooltip-frame] %s: 40 lines, %d frames, AI cache empty, hub %d rows%n",
                label, FRAMES, HUB_ROWS);
        System.out.printf("[tooltip-frame] %s: first (cold) frame %.2f ms; steady mean %.3f ms, p95 %.3f ms, "
                + "max %.3f ms%n", label, firstFrameMs, total[0], total[1], total[2]);
        System.out.printf("[tooltip-frame] %s: per frame, hub lookup %.3f ms, segmentation %.3f ms, "
                        + "trim hook %.3f ms, warm/queue %.3f ms, translateItemLine x40 %.3f ms%n",
                label, hubMs, segmentMs, trimMs, warmMs, lookupMs);
        return new double[] {total[0], total[1], total[2], firstFrameMs};
    }

    @Test
    void fortyLineTooltipOverSixHundredFramesWithAnEmptyAiCache(@TempDir Path dir) throws IOException {
        HubLocalCache hub = loadedHub(dir);
        double[] plain = measureFrames("plain", skyBlockTooltip(), hub);
        double[] coloured = measureFrames("coloured", colouredSkyBlockTooltip(), hub);

        // Ceiling, not the target (the target is < 1 ms): only guards against a regression of
        // the order of magnitude this test was written to catch.
        assertTrue(plain[0] < 8.0, "plain: mean per frame was " + plain[0] + " ms");
        assertTrue(coloured[0] < 8.0, "coloured: mean per frame was " + coloured[0] + " ms");
    }
}
