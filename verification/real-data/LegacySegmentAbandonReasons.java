package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.cache.FileStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Diagnostic (NOT production code, never shipped): for every real-cache row that is a
 * {@code ⟦PBn⟧}-joined multi-row candidate but {@link TooltipSegmentPlanner#plan} declines
 * (returns {@code null}), classifies WHY — which row shape first caused the all-or-nothing
 * bail — so the "大部分可切段舊列目前卡在哪一類" quantification work order task 1 has real
 * numbers instead of a guess. Run BEFORE and AFTER the planner/composer fix (same tool, same
 * cache copy) to see the category distribution shrink as rows move from "abandoned" to
 * "already splittable".
 *
 * <p>Deliberately lives in the {@code translate} package (not {@code hub.tool}) so it can
 * call the row-shape package-private test hooks ({@link TradeLineComposer#matchesRow},
 * {@link StatsLineComposer#matchesRow}) the same way {@link TooltipSegmentPlanner} itself
 * does, without reflection. Same safety rules as the other {@code verification/real-data}
 * tools in this directory: refuses a directory holding {@code nyanlex.json}/{@code
 * nyanlex-codex-home}; only ever opens a COPY of {@code nyanlex-ai-cache-<lang>
 * .json}; stdout carries aggregate counts only; row-text SAMPLES go to a file (never
 * stdout), filtered through {@link PlayerNamePatterns#nameSpans} as an extra belt-and-braces
 * check even though an abandoned/prose row is not expected to carry a raw player name (those
 * live in TRADE rows, which are never "abandoned" for THIS reason — they are what causes the
 * bail on an unrelated neighbour row).</p>
 *
 * <p>Build/run: same javac/java invocation as {@code LegacySegmentQuantification} in this
 * same directory (JDK 21, {@code build/classes/java/main} + gson on the classpath).</p>
 */
public final class LegacySegmentAbandonReasons {

    private static final String LANG_TAG = "zh-tw";

    /** Mirrors {@link TooltipSegmentPlanner}'s own private ABILITY_BLOCK pattern exactly
     *  (diagnostic duplication only — the production field is private, and this tool must
     *  observe the CURRENT behaviour of whichever build it is run against, before or after
     *  the fix, not call into a method that will itself change shape across that fix). */
    private static final Pattern ABILITY_BLOCK = Pattern.compile("(?i)\\b(?:Item )?Ability:\\s*\\S");

    /** A relaxed STATS shape that accepts a templated {@code ⟦MTn⟧} value token in place of
     *  a literal digit (what {@link StatsLineComposer#matchesRow} is EXPECTED to look like
     *  after task 3's calibration) — used here only to measure, on TODAY's (pre-fix) real
     *  data, how many abandoned rows are "a stats row, just blocked by the literal-digit
     *  requirement" versus genuinely unclassifiable prose. */
    private static final Pattern RELAXED_STAT_ROW = Pattern.compile(
            "^\\s*[A-Z][A-Za-z '()/-]{1,30}:\\s*[+-]?(?:[0-9]\\S*"
                    + "|\\u27E6\\s*MT\\s*\\d+\\s*\\u27E7\\S*).*$");

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: LegacySegmentAbandonReasons <cache-copy-dir> [samples-file]");
            System.exit(2);
            return;
        }
        Path dataDir = Paths.get(args[0]).toAbsolutePath().normalize();
        if (Files.exists(dataDir.resolve("nyanlex.json"))
                || Files.exists(dataDir.resolve("nyanlex-codex-home"))) {
            System.err.println("REFUSED: looks like a live Minecraft config directory.");
            System.exit(3);
            return;
        }
        Path cacheFile = dataDir.resolve("nyanlex-ai-cache-" + LANG_TAG + ".json");
        if (!Files.isRegularFile(cacheFile)) {
            System.err.println("NOTHING_TO_ANALYSE: no " + cacheFile.getFileName() + " in " + dataDir);
            System.exit(4);
            return;
        }

        Path tempDir = Files.createTempDirectory("nyanlex-abandon-reasons-");
        try {
            Path copy = tempDir.resolve(cacheFile.getFileName());
            Files.copy(cacheFile, copy);
            FileStore store = new FileStore(copy, false, Integer.MAX_VALUE);

            int totalRows = 0;
            int multiRowCandidates = 0;
            int alreadySplittableToday = 0;
            int wouldBecomeSplittableUnderFix = 0;
            Map<String, Integer> abandonReasonCounts = new TreeMap<>();
            Map<String, List<String>> samplesByReason = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : store.entries().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (store.isProvisional(key)) continue;
                if (isSentinel(key) || isSentinel(value)) continue;
                totalRows++;
                if (ParagraphModel.countBreakTokens(key) == 0) continue;
                multiRowCandidates++;

                if (TooltipSegmentPlanner.plan(key, true) != null) {
                    alreadySplittableToday++;
                    continue;
                }

                String reason = classifyAbandonReason(key);
                abandonReasonCounts.merge(reason, 1, Integer::sum);
                if (hasAnyClassifiableRow(key)) wouldBecomeSplittableUnderFix++;

                List<String> samples = samplesByReason.computeIfAbsent(reason, k -> new ArrayList<>());
                if (samples.size() < 20) {
                    String sampleRow = offendingRowText(key, reason);
                    if (sampleRow != null && PlayerNamePatterns.nameSpans(sampleRow).isEmpty()) {
                        samples.add(sampleRow);
                    }
                }
            }

            System.out.println("RAW_ROWS total=" + totalRows);
            System.out.println("MULTI_ROW_CANDIDATES count=" + multiRowCandidates);
            System.out.println("ALREADY_SPLITTABLE_TODAY count=" + alreadySplittableToday);
            int abandoned = multiRowCandidates - alreadySplittableToday;
            System.out.println("ABANDONED count=" + abandoned);
            System.out.println("WOULD_BECOME_SPLITTABLE_UNDER_FIX count=" + wouldBecomeSplittableUnderFix
                    + " (has >=1 row independently matching RARITY/ENCHANT/TRADE/SCROLL/relaxed-STATS;"
                    + " an upper-bound estimate of what partial planning could recover)");
            System.out.println("ABANDON_REASON_BREAKDOWN:");
            for (Map.Entry<String, Integer> e : abandonReasonCounts.entrySet()) {
                double pct = abandoned == 0 ? 0.0 : 100.0 * e.getValue() / abandoned;
                System.out.printf("  %-40s count=%-8d pct_of_abandoned=%.1f%%%n", e.getKey(), e.getValue(), pct);
            }

            if (args.length >= 2) {
                Path samplesFile = Paths.get(args[1]).toAbsolutePath().normalize();
                List<String> lines = new ArrayList<>();
                for (Map.Entry<String, List<String>> e : samplesByReason.entrySet()) {
                    lines.add("=== " + e.getKey() + " (" + e.getValue().size() + " samples) ===");
                    lines.addAll(e.getValue());
                }
                Files.write(samplesFile, lines, StandardCharsets.UTF_8);
                System.out.println("WROTE_SAMPLES file=" + samplesFile);
            }
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /** Mirrors {@link TooltipSegmentPlanner#plan}'s exact row-walk order so the FIRST row
     *  that would cause a real bail is the one classified here too. */
    private static String classifyAbandonReason(String text) {
        if (ABILITY_BLOCK.matcher(text).find()) return "ABILITY_BLOCK_PRESENT";
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null || rows.size() < 2) return "FEWER_THAN_2_ROWS";

        Map<Integer, Integer> rowIndexAfterEnd = new java.util.HashMap<>(rows.size() * 2);
        for (int k = 0; k < rows.size(); k++) rowIndexAfterEnd.put(rows.get(k)[1], k + 1);
        Map<Integer, RarityLineComposer.Run> rarityByStart = indexByStart(RarityLineComposer.matchRuns(text));
        Map<Integer, EnchantListComposer.Run> enchantByStart = indexByStartEnchant(EnchantListComposer.matchRuns(text));
        Map<Integer, ScrollNameListComposer.Run> scrollByStart = indexByStartScroll(ScrollNameListComposer.matchRuns(text));

        int i = 0;
        while (i < rows.size()) {
            int[] row = rows.get(i);
            if (rarityByStart.containsKey(row[0])) {
                i = rowIndexAfterEnd.get(rarityByStart.get(row[0]).end());
                continue;
            }
            if (enchantByStart.containsKey(row[0])) {
                i = rowIndexAfterEnd.get(enchantByStart.get(row[0]).end());
                continue;
            }
            if (scrollByStart.containsKey(row[0])) {
                i = rowIndexAfterEnd.get(scrollByStart.get(row[0]).end());
                continue;
            }
            String rowText = text.substring(row[0], row[1]);
            if (TradeLineComposer.matchesRow(rowText)) {
                i++;
                continue;
            }
            if (StatsLineComposer.matchesRow(rowText)) {
                i++;
                continue;
            }
            if (isInert(rowText)) {
                i++;
                continue;
            }
            // First genuinely unclassified row: sub-classify its own shape.
            String projected = TextFilter.stripFormatting(rowText).strip();
            if (RELAXED_STAT_ROW.matcher(projected).matches()) {
                return "STATS_SHAPE_NEEDS_MT_TOKEN_SUPPORT";
            }
            return "GENERIC_PROSE_OR_UNRECOGNIZED";
        }
        return "UNREACHABLE_ALL_ROWS_CLASSIFIED"; // plan() would not have returned null
    }

    /** Whether {@code text} has at least one row independently matching ANY of the five
     *  classifiable shapes (RARITY/ENCHANT/SCROLL/TRADE/relaxed-STATS) — an upper-bound
     *  estimate of "would partial planning find something to split out of this paragraph". */
    private static boolean hasAnyClassifiableRow(String text) {
        if (!RarityLineComposer.matchRuns(text).isEmpty()) return true;
        if (!EnchantListComposer.matchRuns(text).isEmpty()) return true;
        if (!ScrollNameListComposer.matchRuns(text).isEmpty()) return true;
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null) return false;
        for (int[] row : rows) {
            String rowText = text.substring(row[0], row[1]);
            if (TradeLineComposer.matchesRow(rowText)) return true;
            String projected = TextFilter.stripFormatting(rowText).strip();
            if (RELAXED_STAT_ROW.matcher(projected).matches()) return true;
        }
        return false;
    }

    private static String offendingRowText(String text, String reason) {
        if ("ABILITY_BLOCK_PRESENT".equals(reason) || "FEWER_THAN_2_ROWS".equals(reason)
                || "UNREACHABLE_ALL_ROWS_CLASSIFIED".equals(reason)) {
            return text.length() > 160 ? text.substring(0, 160) + "…" : text;
        }
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null) return null;
        Map<Integer, Integer> rowIndexAfterEnd = new java.util.HashMap<>(rows.size() * 2);
        for (int k = 0; k < rows.size(); k++) rowIndexAfterEnd.put(rows.get(k)[1], k + 1);
        Map<Integer, RarityLineComposer.Run> rarityByStart = indexByStart(RarityLineComposer.matchRuns(text));
        Map<Integer, EnchantListComposer.Run> enchantByStart = indexByStartEnchant(EnchantListComposer.matchRuns(text));
        Map<Integer, ScrollNameListComposer.Run> scrollByStart = indexByStartScroll(ScrollNameListComposer.matchRuns(text));
        int i = 0;
        while (i < rows.size()) {
            int[] row = rows.get(i);
            if (rarityByStart.containsKey(row[0])) {
                i = rowIndexAfterEnd.get(rarityByStart.get(row[0]).end());
                continue;
            }
            if (enchantByStart.containsKey(row[0])) {
                i = rowIndexAfterEnd.get(enchantByStart.get(row[0]).end());
                continue;
            }
            if (scrollByStart.containsKey(row[0])) {
                i = rowIndexAfterEnd.get(scrollByStart.get(row[0]).end());
                continue;
            }
            String rowText = text.substring(row[0], row[1]);
            if (TradeLineComposer.matchesRow(rowText) || StatsLineComposer.matchesRow(rowText) || isInert(rowText)) {
                i++;
                continue;
            }
            return rowText.length() > 160 ? rowText.substring(0, 160) + "…" : rowText;
        }
        return null;
    }

    private static boolean isInert(String rowText) {
        String projected = TextFilter.stripFormatting(rowText);
        for (int i = 0; i < projected.length(); ) {
            int cp = projected.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) return false;
        }
        return true;
    }

    private static Map<Integer, RarityLineComposer.Run> indexByStart(List<RarityLineComposer.Run> runs) {
        Map<Integer, RarityLineComposer.Run> m = new java.util.HashMap<>();
        for (RarityLineComposer.Run r : runs) m.put(r.start(), r);
        return m;
    }

    private static Map<Integer, EnchantListComposer.Run> indexByStartEnchant(List<EnchantListComposer.Run> runs) {
        Map<Integer, EnchantListComposer.Run> m = new java.util.HashMap<>();
        for (EnchantListComposer.Run r : runs) m.put(r.start(), r);
        return m;
    }

    private static Map<Integer, ScrollNameListComposer.Run> indexByStartScroll(List<ScrollNameListComposer.Run> runs) {
        Map<Integer, ScrollNameListComposer.Run> m = new java.util.HashMap<>();
        for (ScrollNameListComposer.Run r : runs) m.put(r.start(), r);
        return m;
    }

    private static boolean isSentinel(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\u0000';
    }

    private static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
