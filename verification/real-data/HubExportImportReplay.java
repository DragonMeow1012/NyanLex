package com.dragonmeow.nyanslate.hub.tool;

import com.dragonmeow.nyanslate.hub.HubDownloadResult;
import com.dragonmeow.nyanslate.hub.HubDownloadState;
import com.dragonmeow.nyanslate.hub.HubDownloader;
import com.dragonmeow.nyanslate.hub.HubLocalCache;
import com.dragonmeow.nyanslate.hub.HubPlan;
import com.dragonmeow.nyanslate.hub.HubRepository;
import com.dragonmeow.nyanslate.translate.HttpTransport;
import com.dragonmeow.nyanslate.translate.PlayerNamePatterns;
import com.dragonmeow.nyanslate.translate.TranslationFile;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real-cache export/import round-trip replay for the GitHub AI translation-hub pipeline
 * (hub core + {@link HubExportTool} + {@link ChatLineClassifier}): exercises the exact
 * same export the author runs for the first hub publish ({@code --server hypixel.net
 * --drop-chat}) against a COPY of a real player's AI cache, writes a temporary repository,
 * imports it back through the production {@link HubDownloader}/{@link HubLocalCache}
 * classes with an inline fake {@link HttpTransport}, and checks the import is byte-for-byte
 * faithful to what was exported. It also prints an aggregate content-type audit and saves
 * up to 30 KEPT, 30 DROPPED-AS-CHAT, 30 REJECTED-UNMASKED-NAME (conversion-failed), 30
 * NAME-CONVERTED (successfully masked in place, see {@link UnmaskedNameConverter}) and 30
 * FINAL-KEPT (sampled from the file actually written, after dedup — see
 * {@link HubExportTool#run}) samples for the human-reviewed publish checklist.
 *
 * <p>Safety rules mirror {@code RealCacheReplay.java} in this same directory: a directory
 * holding {@code nyanslate.json} or {@code nyanslate-codex-home} (a live config
 * directory) is refused outright; the config file is never opened. Only
 * {@code nyanslate-ai-cache-<lang>.json} is ever copied, into a fresh
 * {@code <out-dir>/work-*} directory, and every later parse (including
 * {@link HubExportTool}'s own copy-before-read) runs against that copy or a further
 * temp copy of it — the real file is opened exactly once, read-only, for the first copy.
 * {@code <out-dir>} also receives one {@code samples-*.txt} file holding REDACTED sample
 * text (any raw "From/To NAME:" username is masked); keep {@code <out-dir>} OUTSIDE the
 * repository. stdout carries aggregate counts only, never row content.</p>
 *
 * <p>Build (JDK 21, no Gradle; same MC-free core as {@code RealCacheReplay.java}, plus the
 * hub + hub.tool packages this class and {@link HubExportTool} live in):</p>
 * <pre>
 *   GSON=~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/&lt;hash&gt;/gson-2.11.0.jar
 *   javac -encoding UTF-8 -cp "$GSON" -d CORE  (every *.java under
 *         src/main/java/com/dragonmeow/nyanslate/{config,cache,service,style,translate,hub,hub/tool})
 *   javac -encoding UTF-8 -cp "CORE;$GSON" -d HARNESS verification/real-data/HubExportImportReplay.java
 * </pre>
 * <p>Run (Windows classpath separator shown):</p>
 * <pre>
 *   java -Xmx2g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "HARNESS;CORE;$GSON" \
 *        com.dragonmeow.nyanslate.hub.tool.HubExportImportReplay &lt;cache-copy-dir&gt; &lt;out-dir&gt;
 * </pre>
 * <p>{@code <cache-copy-dir>} holds a COPY of {@code nyanslate-ai-cache-zh-tw.json} (or
 * is the player's live config directory itself — this harness never opens anything there
 * except that one file, and only to make its own private copy). Exit codes: 0 every check
 * passed, 1 a check failed, 2 usage, 3 refused (live config directory signature found
 * alongside a plain directory argument check), 4 nothing to replay (no zh-tw AI cache
 * copy found).</p>
 */
public final class HubExportImportReplay {

    private static final String LANG = "zh-TW";
    private static final String LANG_TAG = "zh-tw";
    private static final String SERVER_HOST = "hypixel.net";
    private static final String FAKE_BASE_URL = "replay://hub";
    private static final int SAMPLE_CAP = 30;

    private static final Pattern MASKED_NAME = Pattern.compile("⟦[0-9]+⟧");
    private static final Pattern URL_LIKE = Pattern.compile(
            "(?i)https?://[^\\s\"'<>]+|www\\.[^\\s\"'<>]+|(?:[a-z0-9-]+\\.)+[a-z]{2,}");
    /** Redacts the one chat shape ({@code WHISPER_PREFIX} in {@link ChatLineClassifier})
     *  whose player name is NOT already a {@code ⟦n⟧} mask, before any sample is written. */
    private static final Pattern RAW_WHISPER_NAME = Pattern.compile(
            "(?i)\\b(From|To)\\s+([A-Za-z0-9_]{2,16})(\\s*:)");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: HubExportImportReplay <cache-copy-dir> <out-dir>");
            System.exit(2);
            return;
        }
        Path dataDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outDir = Paths.get(args[1]).toAbsolutePath().normalize();
        if (Files.exists(dataDir.resolve("nyanslate.json"))
                || Files.exists(dataDir.resolve("nyanslate-codex-home"))) {
            // Same contract as RealCacheReplay.java: <cache-copy-dir> must already be a
            // COPY-ONLY staging directory (just the whitelisted cache file), never the
            // live config directory itself, even though copyAiCacheOnly() below would
            // only ever touch nyanslate-ai-cache-<lang>.json by exact name. Copying
            // that one file out of %APPDATA%\.minecraft\config\ into a staging directory
            // is a separate, manual first step, done before this harness ever runs.
            System.err.println("REFUSED: the directory looks like a live Minecraft config directory."
                    + " Pass a directory holding a COPY of nyanslate-ai-cache-" + LANG_TAG
                    + ".json only.");
            System.exit(3);
            return;
        }
        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = dataDir.getFileName() == null ? "data" : dataDir.getFileName().toString();
        Path work = outDir.resolve("work-" + label + "-" + stamp);
        int copied = copyAiCacheOnly(dataDir, work);
        System.out.println("RUN dataset=" + label + " workCopyFiles=" + copied);
        Path aiCacheCopy = work.resolve("nyanslate-ai-cache-" + LANG_TAG + ".json");
        if (!Files.isRegularFile(aiCacheCopy)) {
            System.out.println("HUB_REPLAY_NO_DATA (no nyanslate-ai-cache-" + LANG_TAG + ".json)");
            System.exit(4);
            return;
        }
        Path samplesFile = outDir.resolve("samples-" + label + "-" + stamp + ".txt");
        int status;
        try (Samples samples = new Samples(samplesFile)) {
            status = new HubExportImportReplay(work, outDir, samples).execute();
        }
        System.out.println("SAMPLES_FILE " + samplesFile.getFileName());
        System.out.println(status == 0 ? "HUB_REPLAY_OK" : "HUB_REPLAY_FAILED");
        System.exit(status);
    }

    /** Copies ONLY {@code nyanslate-ai-cache-<lang>.json}; every other file in
     *  {@code from} (including {@code nyanslate.json}, GT caches, failure ledgers) is
     *  ignored, so even pointing this harness at a live config directory never copies
     *  anything beyond the one file {@link HubExportTool} itself needs. */
    private static int copyAiCacheOnly(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        String wanted = "nyanslate-ai-cache-" + LANG_TAG + ".json";
        int copied = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(from)) {
            for (Path file : files) {
                if (!Files.isRegularFile(file) || !wanted.equals(file.getFileName().toString())) continue;
                Files.copy(file, to.resolve(wanted));
                copied++;
            }
        }
        return copied;
    }

    private final Path work;
    private final Path outDir;
    private final Samples samples;
    private final Map<String, Long> counters = new TreeMap<>();

    private HubExportImportReplay(Path work, Path outDir, Samples samples) {
        this.work = work;
        this.outDir = outDir;
        this.samples = samples;
    }

    private int execute() throws Exception {
        // ---- 1. classify() once (with --drop-chat semantics): corpus audit + samples ----
        List<HubExportTool.ClassifiedRow> withDropChat = HubExportTool.classify(work, LANG, true);
        auditContentTypes(withDropChat);
        collectSamples(withDropChat);

        // ---- 2. run the real export CLI into a temp repository, ONE attempt, no
        // --max-rows (2026-10-01 user decision: if the full kept set does not fit under
        // HubExportTool.MAX_BYTES, report the actual size and STOP — never silently/
        // automatically truncate; that decision is the main conversation's to make, not
        // this harness's). ----
        Path repo = work.resolve("repo");
        ByteArrayOutputStream exportLog = new ByteArrayOutputStream();
        HubExportTool.Result exportResult;
        try (PrintStream captured = new PrintStream(exportLog, true, "UTF-8")) {
            exportResult = HubExportTool.run(new String[] {
                    "--cache-dir", work.toString(), "--lang", LANG, "--server", SERVER_HOST,
                    "--out", repo.toString(), "--merge-index", "--drop-chat",
            }, captured);
        }
        System.out.print(exportLog.toString("UTF-8"));
        System.out.println("EXPORT exitCode=" + exportResult.exitCode()
                + " totalRows=" + exportResult.stats().totalRows()
                + " droppedChat=" + exportResult.stats().droppedChat()
                + " rejectedValidation=" + exportResult.stats().rejectedValidation()
                + " rejectedForeignUrl=" + exportResult.stats().rejectedForeignUrl()
                + " rejectedUnmaskedName(conversionFailed)=" + exportResult.stats().rejectedUnmaskedName()
                + " nameConversionSucceeded=" + exportResult.stats().nameConversionSucceeded()
                + " duplicateKeyGroups=" + exportResult.stats().duplicateKeyGroups()
                + " duplicateRowsDropped=" + exportResult.stats().duplicateRowsDropped()
                + " exportedRows=" + exportResult.stats().exportedRows()
                + " truncated=" + exportResult.stats().truncated()
                + " bytes=" + exportResult.stats().bytes());
        if (exportResult.exitCode() != 0) {
            System.out.println("HUB_REPLAY export step failed, nothing to import (see EXPORT_ATTEMPT lines above)");
            return exportResult.stats().totalRows() == 0 ? 4 : 1;
        }
        if (exportResult.writtenFile() == null) {
            System.out.println("HUB_REPLAY nothing exported (0 kept rows): import step skipped, OK");
            return 0;
        }
        // ---- 3. expected rows (ground truth): read back the file HubExportTool.run()
        // ACTUALLY wrote, rather than recomputing independently. Recomputing would need
        // to reimplement dedupeKept()'s "most common, tie-break latest" policy here too
        // (two rows can collapse onto the same key after name conversion — see
        // HubExportTool#dedupeKept) to agree with what is really on disk; reading the
        // file sidesteps that duplication entirely. ----
        Map<String, String> expected = TranslationFile.read(exportResult.writtenFile()).ai;
        collectFinalKeptSamples(expected);

        // ---- 4. import back through the production HubDownloader/HubLocalCache ----
        HttpTransport fakeTransport = url -> {
            if (!url.startsWith(FAKE_BASE_URL + "/")) throw new IOException("unexpected url: " + url);
            String relative = url.substring((FAKE_BASE_URL + "/").length());
            Path file = repo.resolve(relative);
            if (!Files.isRegularFile(file)) throw new IOException("404: " + url);
            return Files.readString(file, StandardCharsets.UTF_8);
        };
        HubRepository repository = new HubRepository(fakeTransport, FAKE_BASE_URL);
        HubDownloader downloader = new HubDownloader(repository);
        Path importDir = work.resolve("import");
        Files.createDirectories(importDir);
        HubDownloadState state = new HubDownloadState(importDir.resolve("nyanslate-hub-state.json"));
        HubPlan plan = downloader.plan(SERVER_HOST, null, List.of(), LANG, state);
        HubLocalCache importedCache = new HubLocalCache(importDir, LANG);
        HubDownloadResult downloadResult = downloader.download(plan, importedCache, state, null, () -> false);
        System.out.println("IMPORT added=" + downloadResult.added() + " rejected=" + downloadResult.rejected()
                + " alreadyPresent=" + downloadResult.alreadyPresent() + " cancelled=" + downloadResult.cancelled());

        boolean ok = true;
        ok &= check("import.addedMatchesExported", downloadResult.added() == expected.size());
        ok &= check("import.noneRejectedOnReimport", downloadResult.rejected() == 0);
        ok &= check("import.cacheSizeMatchesExpected", importedCache.size() == expected.size());

        // ---- 5. byte-for-byte comparison ----
        int mismatches = 0;
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String got = importedCache.get(entry.getKey());
            if (!entry.getValue().equals(got)) mismatches++;
        }
        System.out.println("COMPARE exactValueMismatches=" + mismatches + " of " + expected.size());
        ok &= check("import.everyValueExactMatch", mismatches == 0);

        // ---- 6. reopen and compare persistence ----
        HubLocalCache reopened = new HubLocalCache(importDir, LANG);
        int reopenMismatches = 0;
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(reopened.get(entry.getKey()))) reopenMismatches++;
        }
        ok &= check("reopen.sizeMatches", reopened.size() == expected.size());
        ok &= check("reopen.everyValueExactMatch", reopenMismatches == 0);

        System.out.println("CHECKS " + checksSummary());
        return ok ? 0 : 1;
    }

    private boolean check(String name, boolean passed) {
        counters.put("check." + name, passed ? 1L : 0L);
        return passed;
    }

    private String checksSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : counters.entrySet()) {
            if (!entry.getKey().startsWith("check.")) continue;
            sb.append(entry.getKey().substring("check.".length())).append('=')
                    .append(entry.getValue() == 1L ? "PASS" : "FAIL").append(' ');
        }
        return sb.toString().strip();
    }

    /** Prints ONLY aggregate numbers (never row text): total rows, disposition counts and
     *  percentages, rows carrying a masked player name, rows carrying a URL-like substring. */
    private void auditContentTypes(List<HubExportTool.ClassifiedRow> rows) {
        int total = rows.size();
        int kept = 0, droppedChat = 0, rejectedValidation = 0, rejectedForeignUrl = 0, rejectedUnmaskedName = 0;
        int maskedNameRows = 0, urlLikeRows = 0;
        int nameConversionSucceeded = 0, nameConversionSucceededAndKept = 0;
        Map<String, Integer> chatReasonCounts = new TreeMap<>();
        Map<String, Integer> rejectionReasonCounts = new TreeMap<>();
        Map<String, Integer> conversionFailureReasonCounts = new TreeMap<>();
        Map<String, Integer> convertedRowFinalDisposition = new TreeMap<>();
        for (HubExportTool.ClassifiedRow row : rows) {
            if (row.nameConverted()) {
                nameConversionSucceeded++;
                convertedRowFinalDisposition.merge(row.disposition().name(), 1, Integer::sum);
                if (row.disposition() == HubExportTool.Disposition.KEPT) nameConversionSucceededAndKept++;
            }
            switch (row.disposition()) {
                case KEPT: kept++; break;
                case DROPPED_CHAT:
                    droppedChat++;
                    chatReasonCounts.merge(row.reason(), 1, Integer::sum);
                    break;
                case REJECTED_VALIDATION:
                    rejectedValidation++;
                    rejectionReasonCounts.merge(row.reason(), 1, Integer::sum);
                    break;
                case REJECTED_FOREIGN_URL: rejectedForeignUrl++; break;
                case REJECTED_UNMASKED_NAME:
                    rejectedUnmaskedName++;
                    conversionFailureReasonCounts.merge(row.reason(), 1, Integer::sum);
                    break;
            }
            if (MASKED_NAME.matcher(row.source()).find()) maskedNameRows++;
            if (URL_LIKE.matcher(row.source()).find()) urlLikeRows++;
        }
        System.out.println("AUDIT totalRows=" + total);
        System.out.println("AUDIT kept=" + pct(kept, total) + " droppedChat=" + pct(droppedChat, total)
                + " rejectedValidation=" + pct(rejectedValidation, total)
                + " rejectedForeignUrl=" + pct(rejectedForeignUrl, total)
                + " rejectedUnmaskedName(conversionFailed)=" + pct(rejectedUnmaskedName, total));
        System.out.println("AUDIT nameConversionSucceeded=" + pct(nameConversionSucceeded, total)
                + " (of which still KEPT after downstream checks="
                + pct(nameConversionSucceededAndKept, nameConversionSucceeded) + ")"
                + " nameConversionFailed=" + pct(rejectedUnmaskedName, total));
        System.out.println("AUDIT nameConversionSucceeded.finalDisposition=" + convertedRowFinalDisposition);
        System.out.println("AUDIT maskedNameRows=" + pct(maskedNameRows, total)
                + " urlLikeRows=" + pct(urlLikeRows, total));
        System.out.println("AUDIT chatReasons=" + chatReasonCounts);
        System.out.println("AUDIT rejectionReasons=" + rejectionReasonCounts);
        System.out.println("AUDIT nameConversionFailureReasons=" + conversionFailureReasonCounts);
    }

    private static String pct(int part, int total) {
        double percent = total == 0 ? 0.0 : (100.0 * part / total);
        return part + "(" + String.format("%.1f", percent) + "%)";
    }

    /** Up to {@value #SAMPLE_CAP} samples each of KEPT, DROPPED-AS-CHAT and
     *  REJECTED-UNMASKED-NAME rows, written to the samples file only (never stdout). Every
     *  sample is redacted: a raw "From/To NAME:" username and any span
     *  {@link PlayerNamePatterns#nameSpans} recognises are both masked before writing —
     *  belt-and-suspenders, since a KEPT row should never carry either in the first place
     *  (the unmasked-name gate already rejects that case) but a sample file is exactly the
     *  place to not trust that silently. */
    private void collectSamples(List<HubExportTool.ClassifiedRow> rows) throws IOException {
        List<HubExportTool.ClassifiedRow> kept = new ArrayList<>();
        List<HubExportTool.ClassifiedRow> dropped = new ArrayList<>();
        List<HubExportTool.ClassifiedRow> unmaskedName = new ArrayList<>();
        List<HubExportTool.ClassifiedRow> nameConverted = new ArrayList<>();
        for (HubExportTool.ClassifiedRow row : rows) {
            if (row.disposition() == HubExportTool.Disposition.KEPT && kept.size() < SAMPLE_CAP) {
                kept.add(row);
            } else if (row.disposition() == HubExportTool.Disposition.DROPPED_CHAT
                    && dropped.size() < SAMPLE_CAP) {
                dropped.add(row);
            } else if (row.disposition() == HubExportTool.Disposition.REJECTED_UNMASKED_NAME
                    && unmaskedName.size() < SAMPLE_CAP) {
                unmaskedName.add(row);
            }
            // Independent of the switch above: a successfully name-converted row is
            // exactly what a human must verify carries no raw name anymore (2026-10-01
            // item 5 requirement: "30 筆名字轉換後樣本（確認沒有真名）"). Sampled regardless
            // of final disposition — real data shows most of these ALSO get dropped as
            // chat afterward (see AUDIT nameConversionSucceeded vs "still KEPT"), but the
            // thing to verify here is the conversion step's OWN output, not its eventual
            // publish fate (that is what "final-kept" below is for).
            if (row.nameConverted() && nameConverted.size() < SAMPLE_CAP) {
                nameConverted.add(row);
            }
            if (kept.size() >= SAMPLE_CAP && dropped.size() >= SAMPLE_CAP
                    && unmaskedName.size() >= SAMPLE_CAP && nameConverted.size() >= SAMPLE_CAP) break;
        }
        for (HubExportTool.ClassifiedRow row : kept) {
            samples.add("kept", redact(row.source()) + " -> " + redact(row.translated())
                    + "  [" + row.reason() + "]");
        }
        for (HubExportTool.ClassifiedRow row : dropped) {
            samples.add("dropped-chat", redact(row.source()) + "  [" + row.reason() + "]");
        }
        for (HubExportTool.ClassifiedRow row : unmaskedName) {
            samples.add("rejected-unmasked-name", redact(row.source()) + "  [" + row.reason() + "]");
        }
        for (HubExportTool.ClassifiedRow row : nameConverted) {
            samples.add("name-converted", redact(row.source()) + " -> " + redact(row.translated())
                    + "  [then:" + row.disposition() + "]");
        }
    }

    /** "另抽 30 筆最終保留樣本" (2026-10-01 item 5): sampled from the file
     *  {@code HubExportTool.run()} ACTUALLY wrote — after dedup and any truncation — not
     *  from the pre-dedup {@code classify()} output {@link #collectSamples} uses, so this
     *  reflects exactly what would be published. Evenly spaced across the file so a small
     *  sample still spans the whole key range rather than clustering at one alphabetic end. */
    private void collectFinalKeptSamples(Map<String, String> finalRows) throws IOException {
        if (finalRows.isEmpty()) return;
        List<Map.Entry<String, String>> entries = new ArrayList<>(finalRows.entrySet());
        int stride = Math.max(1, entries.size() / SAMPLE_CAP);
        int written = 0;
        for (int i = 0; i < entries.size() && written < SAMPLE_CAP; i += stride) {
            Map.Entry<String, String> entry = entries.get(i);
            samples.add("final-kept", redact(entry.getKey()) + " -> " + redact(entry.getValue()));
            written++;
        }
    }

    private static String redact(String text) {
        if (text == null) return "";
        String spansRedacted = redactKnownNameSpans(text);
        Matcher matcher = RAW_WHISPER_NAME.matcher(spansRedacted);
        return matcher.replaceAll("$1 ⟦redacted⟧$3");
    }

    /** Replaces every {@link PlayerNamePatterns#nameSpans} span (a raw, unmasked player
     *  name a current frame recognises) with a literal {@code ⟦redacted⟧} marker. */
    private static String redactKnownNameSpans(String text) {
        List<int[]> spans = PlayerNamePatterns.nameSpans(text);
        if (spans.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (int[] span : spans) {
            out.append(text, cursor, span[0]).append("⟦redacted⟧");
            cursor = span[1];
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /** Append-only, capped-per-category samples file; mirrors {@code RealCacheReplay}'s
     *  {@code Samples} helper (kept independent here since this file is compiled and run
     *  standalone, without access to that one's package-private class). */
    private static final class Samples implements AutoCloseable {
        private final BufferedWriter writer;
        private final Map<String, Integer> perCategory = new TreeMap<>();

        Samples(Path file) throws IOException {
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        }

        synchronized void add(String category, String line) throws IOException {
            int count = perCategory.merge(category, 1, Integer::sum);
            if (count > SAMPLE_CAP) return;
            writer.write("[" + category + " #" + count + "] " + line);
            writer.newLine();
        }

        @Override
        public void close() throws IOException {
            writer.flush();
            writer.close();
        }
    }
}
