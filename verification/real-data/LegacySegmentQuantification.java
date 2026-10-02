package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.cache.FileStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real-cache quantification for the "舊快取懶轉換" / "倉庫段級匯出" work order's task 3(a)/3(c):
 * how many old whole-paragraph AI-cache rows can be segment-split
 * ({@link HubExportTool#splitLegacyWholeRows}), how many TRADE/STATS segments that
 * produces, how many distinct segment keys survive dedup, and a RETROSPECTIVE, clearly
 * bounded estimate of how many of those whole-paragraph rows' segment content was ALREADY
 * knowable from an earlier-iterated row (a proxy for "would this historical request have
 * been free under the segment-cache regime").
 *
 * <p>Safety rules mirror {@code RealCacheReplay.java}/{@code HubExportImportReplay.java} in
 * this same directory: a directory holding {@code nyanlex.json} or
 * {@code nyanlex-codex-home} is refused outright; the config file is NEVER opened.
 * Only {@code nyanlex-ai-cache-<lang>.json} is ever read, and only via
 * {@link HubExportTool}'s own copy-before-read ({@code classify}/internal helpers) or a
 * single read-only {@link FileStore} load of a caller-supplied COPY. stdout carries
 * aggregate counts only, never row content.</p>
 *
 * <p>Build (JDK 21, no Gradle — reuses the already-compiled {@code build/classes/java/main}
 * from an ordinary {@code gradle compileJava}, plus gson):</p>
 * <pre>
 *   GSON=~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/&lt;hash&gt;/gson-2.10.1.jar
 *   javac -encoding UTF-8 -cp "build/classes/java/main;$GSON" -d HARNESS \
 *         verification/real-data/LegacySegmentQuantification.java
 * </pre>
 * <p>Run:</p>
 * <pre>
 *   java -Xmx2g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
 *        -cp "HARNESS;build/classes/java/main;$GSON" \
 *        com.dragonmeow.nyanlex.hub.tool.LegacySegmentQuantification &lt;cache-copy-dir&gt;
 * </pre>
 * <p>{@code <cache-copy-dir>} must hold a COPY of {@code nyanlex-ai-cache-zh-tw.json}
 * only (never the live config directory). Exit codes: 0 ok, 2 usage, 3 refused (looks like a
 * live config directory), 4 nothing to analyse (no zh-tw AI cache copy found).</p>
 */
public final class LegacySegmentQuantification {

    private static final String LANG_TAG = "zh-tw";

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: LegacySegmentQuantification <cache-copy-dir> [new-segment-samples-file]");
            System.exit(2);
            return;
        }
        Path dataDir = Paths.get(args[0]).toAbsolutePath().normalize();
        if (Files.exists(dataDir.resolve("nyanlex.json"))
                || Files.exists(dataDir.resolve("nyanlex-codex-home"))) {
            System.err.println("REFUSED: the directory looks like a live Minecraft config directory."
                    + " Pass a directory holding a COPY of nyanlex-ai-cache-" + LANG_TAG
                    + ".json only.");
            System.exit(3);
            return;
        }
        Path cacheFile = dataDir.resolve("nyanlex-ai-cache-" + LANG_TAG + ".json");
        if (!Files.isRegularFile(cacheFile)) {
            System.err.println("NOTHING_TO_ANALYSE: no " + cacheFile.getFileName() + " in " + dataDir);
            System.exit(4);
            return;
        }

        // One private copy, exactly like HubExportTool's own copyAiCache: the caller-
        // supplied directory's file is opened read-only exactly once, for this copy; every
        // later parse (including a SECOND internal copy FileStore itself may make while
        // loading) runs against the copy.
        Path tempDir = Files.createTempDirectory("nyanlex-segment-quant-");
        try {
            Path copy = tempDir.resolve(cacheFile.getFileName());
            Files.copy(cacheFile, copy);
            FileStore store = new FileStore(copy, false, Integer.MAX_VALUE);
            Map<String, String> rawRows = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : store.entries().entrySet()) {
                if (store.isProvisional(entry.getKey())) continue;
                if (HubExportTool.isSentinel(entry.getKey()) || HubExportTool.isSentinel(entry.getValue())) {
                    continue;
                }
                rawRows.put(entry.getKey(), entry.getValue());
            }

            System.out.println("RAW_ROWS total=" + rawRows.size());

            // Diagnostic context for the splittable-row yield below: how many raw rows are
            // even MULTI-ROW (⟦PBn⟧-joined) candidates at all, before TooltipSegmentPlanner
            // ever gets a chance to accept or decline one -- most of a real cache is
            // single-line rows (plain item names, chat, single tooltip lines) that were
            // never eligible for this feature in the first place, with or without splitting.
            int multiRowCandidates = 0;
            for (String key : rawRows.keySet()) {
                if (com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(key) > 0) {
                    multiRowCandidates++;
                }
            }
            System.out.println("MULTI_ROW_CANDIDATES count=" + multiRowCandidates
                    + " (contain >=1 ⟦PBn⟧ token; TooltipSegmentPlanner only ever "
                    + "considers these)");

            // ---- 3(a): splittable rows / segments produced / distinct segment keys ----
            HubExportTool.SplitResult split = HubExportTool.splitLegacyWholeRows(rawRows);
            Set<String> newSegmentKeys = new LinkedHashSet<>(split.rows().keySet());
            newSegmentKeys.removeAll(rawRows.keySet());
            System.out.println("SPLIT_STATS"
                    + " splittableWholeRows=" + split.splittableRows()
                    + " segmentsProduced=" + split.segmentsProduced()
                    + " distinctSegmentKeysInThisSplitPass=" + split.distinctSegmentKeys()
                    + " segmentKeysNotAlreadyPresentAsTheirOwnRawRow=" + newSegmentKeys.size()
                    + " expandedRowCount=" + split.rows().size());

            // ---- Segment kind breakdown (TRADE vs STATS), by the same label vocabulary
            // TradeLineComposer/StatsLineComposer recognise, over the NEW segment keys only
            // (a key equal to an already-raw row is not newly introduced by splitting) ----
            int tradeLike = 0, statsLike = 0;
            for (String key : newSegmentKeys) {
                if (looksLikeTradeLabel(key)) tradeLike++;
                else statsLike++;
            }
            System.out.println("SEGMENT_KIND_BREAKDOWN tradeLike=" + tradeLike + " statsLike=" + statsLike);

            // ---- 3(c): retrospective, order-dependent "would this have been free" proxy.
            // Map iteration order here is FileStore's own write-order (oldest write first,
            // per its own append-only journal semantics) -- the closest available proxy for
            // "chronological order" a static snapshot offers, NOT a true timestamped replay.
            // For each splittable row, in that order: if EVERY one of its own TRADE/STATS
            // segment keys was already produced by an EARLIER splittable row (or already
            // existed as its own independent raw row before any splitting at all), this
            // row's segment content would have cost ZERO additional requests under a
            // segment-cache regime that existed from the start -- it is counted as
            // "fully pre-answerable". A row with at least one but not all such segments
            // already known is "partially pre-answerable" (the OLD regime bought the whole
            // paragraph as one combinatorially-unique unit regardless; the NEW regime would
            // only have needed the genuinely new segment(s)). Rows with every segment new
            // contribute no savings yet (they are themselves the FIRST sighting).
            Set<String> seenSegmentKeys = new LinkedHashSet<>(rawRows.keySet());
            int fullyPreAnswerable = 0, partiallyPreAnswerable = 0, allNew = 0;
            int oldRegimeRequestUnits = 0; // 1 per splittable whole paragraph (today's reality)
            int newRegimeRequestUnits = 0; // number of genuinely-new segments it would have needed
            for (Map.Entry<String, String> row : rawRows.entrySet()) {
                List<Map.Entry<String, String>> segments = splitOneRowForQuantification(row.getKey(), row.getValue());
                if (segments.isEmpty()) continue;
                oldRegimeRequestUnits++;
                int known = 0;
                int newOnes = 0;
                for (Map.Entry<String, String> segment : segments) {
                    if (seenSegmentKeys.contains(segment.getKey())) known++;
                    else newOnes++;
                }
                newRegimeRequestUnits += newOnes > 0 ? 1 : 0; // still 1 combined request if ANY segment is new (windowed batching)
                if (known == segments.size()) fullyPreAnswerable++;
                else if (known > 0) partiallyPreAnswerable++;
                else allNew++;
                for (Map.Entry<String, String> segment : segments) seenSegmentKeys.add(segment.getKey());
            }
            System.out.println("RETROSPECTIVE_REQUEST_ESTIMATE"
                    + " splittableWholeParagraphs=" + oldRegimeRequestUnits
                    + " oldRegimeRequestUnits=" + oldRegimeRequestUnits
                    + " newRegimeRequestUnitsIfSegmentCacheExistedFromTheStart=" + newRegimeRequestUnits
                    + " fullyPreAnswerable(0 new requests needed)=" + fullyPreAnswerable
                    + " partiallyPreAnswerable(fewer units than the whole paragraph)=" + partiallyPreAnswerable
                    + " firstSightingNoSavingsYet=" + allNew);
            System.out.println("CAVEAT: FileStore write order is the closest available chronological proxy"
                    + " in a static snapshot, not a true timestamped replay; ENCHANT/SCROLL segment content"
                    + " is NOT modelled here at all (never split, by design -- see HubExportTool's own"
                    + " javadoc), so this estimate is scoped to TRADE/STATS content only and is a"
                    + " conservative LOWER bound on potential savings, not a full accounting.");

            // The handful of genuinely NEW segment rows are written to a FILE (never
            // stdout), same convention as HubExportImportReplay's samples-*.txt. IMPORTANT:
            // splitLegacyWholeRows() on its own does NOT run name conversion -- that is a
            // SEPARATE, LATER pipeline stage (HubExportTool.classifyOneRow, see
            // splitLegacyWholeRows' own javadoc: "再走既有流程（名字轉換...）"). A raw
            // split-out "Bidder: <real name>" row is exactly what the production classify()
            // pipeline ALSO sees at this exact stage, before its OWN name-conversion step
            // runs on it immediately after -- so every sample here is additionally run
            // through that SAME classifyOneRow step before being written, matching exactly
            // what classify()/the published export would actually contain, never the
            // pre-conversion intermediate.
            if (args.length >= 2) {
                Path samplesFile = Paths.get(args[1]).toAbsolutePath().normalize();
                List<String> lines = new ArrayList<>();
                for (String key : newSegmentKeys) {
                    String value = split.rows().get(key);
                    HubExportTool.ClassifiedRow classified = HubExportTool.classifyOneRow(key, value, true);
                    lines.add("[new-segment] disposition=" + classified.disposition()
                            + " nameConverted=" + classified.nameConverted()
                            + " " + classified.source() + " -> " + classified.translated());
                }
                Files.write(samplesFile, lines, java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("WROTE_NEW_SEGMENT_SAMPLES file=" + samplesFile + " count=" + lines.size());
            }
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /** Same shape test {@link HubExportTool#splitLegacyWholeRows} uses internally, exposed
     *  here only because that method's own per-row splitter is private; duplicated at the
     *  CALL level only (delegates entirely to the production class for the actual logic via
     *  its public {@code splitLegacyWholeRows}, called with a singleton map) so this harness
     *  can iterate row-by-row in a caller-controlled order for the retrospective estimate. */
    private static List<Map.Entry<String, String>> splitOneRowForQuantification(String key, String value) {
        HubExportTool.SplitResult one = HubExportTool.splitLegacyWholeRows(Map.of(key, value));
        if (one.segmentsProduced() == 0) return List.of();
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : one.rows().entrySet()) {
            if (!entry.getKey().equals(key)) out.add(entry);
        }
        return out;
    }

    private static boolean looksLikeTradeLabel(String key) {
        String k = key.stripLeading();
        return k.regionMatches(true, 0, "Seller:", 0, 7)
                || k.regionMatches(true, 0, "Buyer:", 0, 6)
                || k.regionMatches(true, 0, "Bidder:", 0, 7)
                || k.regionMatches(true, 0, "Buy it now:", 0, 11)
                || k.regionMatches(true, 0, "Starting bid:", 0, 13)
                || k.regionMatches(true, 0, "Top bid:", 0, 8)
                || k.regionMatches(true, 0, "Current bid:", 0, 12)
                || k.regionMatches(true, 0, "Ends in:", 0, 8)
                || k.regionMatches(true, 0, "Time left:", 0, 10);
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
