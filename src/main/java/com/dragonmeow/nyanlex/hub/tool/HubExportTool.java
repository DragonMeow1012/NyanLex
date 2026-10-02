package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.cache.FileStore;
import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.hub.HubFile;
import com.dragonmeow.nyanlex.hub.HubImportValidator;
import com.dragonmeow.nyanlex.hub.HubIndex;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.hub.HubPaths;
import com.dragonmeow.nyanlex.hub.HubSlug;
import com.dragonmeow.nyanlex.hub.HubSource;
import com.dragonmeow.nyanlex.hub.ServerHostNormalizer;
import com.dragonmeow.nyanlex.translate.LocalTokenRenumberer;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.PlayerNamePatterns;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TooltipSegmentPlanner;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Author-only CLI: turns a (copy of a) player's AI-cache rows into one GitHub
 * translation-hub repository file plus an updated {@code index.json} entry.
 *
 * <p>This class lives in {@code hub.tool}, a sub-package {@code sync-core.ps1} does NOT
 * mirror into the other 16 source trees (see its {@code $corePackages} list): it is an
 * author-side build tool, never shipped inside the mod jar.</p>
 *
 * <p>Usage ({@code gradle hubExport -PcacheDir=... -Plang=... -Pserver=... -Pout=...}, or
 * run the class directly):</p>
 * <pre>
 *   --cache-dir &lt;dir&gt;   directory holding nyanlex-ai-cache-&lt;lang&gt;.json. A LIVE
 *                        Minecraft config directory is fine here: this tool copies the one
 *                        cache file it needs into a private temp directory before ever
 *                        parsing it and never opens the original again, so a FileStore
 *                        compaction on load can never rewrite the player's real file.
 *   --lang zh-TW         target language (normalized via LanguageFileStore.languageTag)
 *   --server &lt;host&gt;     exactly one of --server / --modpack / --mod
 *   --modpack &lt;slug&gt;
 *   --mod &lt;modid&gt;
 *   --out &lt;dir&gt;         repository root to write into (the translation-hub/ directory
 *                        itself: HubPaths' relative paths resolve under it directly)
 *   --merge-index        also update &lt;out&gt;/index.json with this file's new stats
 *   --drop-chat          run {@link ChatLineClassifier} and skip rows it calls chat
 *   --max-rows &lt;n&gt;       optional, 1..{@value #MAX_ROWS}: when the kept-row count still
 *                        exceeds it, deterministically truncate to the first {@code n} rows
 *                        sorted by key (never a silent/implicit truncation — the caller
 *                        explicitly opts in, and the omitted count is reported back)
 * </pre>
 *
 * <p>Row/byte caps ({@value #MAX_ROWS} rows, {@value #MAX_BYTES} bytes per file —
 * {@link HubPaths#MAX_FILE_ROWS} / {@link HubPaths#MAX_FILE_BYTES}, raised twice on
 * 2026-10-01 from the original 40,000 rows/3 MiB — first to 100,000/16 MiB, then to
 * 100,000/32 MiB once the hypixel.net initial seed's 54,281 kept rows still did not fit
 * at 16 MiB — once nothing in the download path turned out to be render-thread-bound:
 * see {@code HubLocalCache#get} (O(1) map lookup) and the glue's background-thread
 * download/merge) mirror the design doc's per-file limit. The repository
 * format has no multi-part convention (one file per source+language — see
 * {@link HubPaths}), so exceeding either cap is a hard error with guidance, never a silent
 * multi-file invention. With this much more headroom, {@code --max-rows} is rarely needed
 * in practice — it remains available as an explicit, reviewable opt-in for a source whose
 * kept content still does not fit.</p>
 *
 * <p>Every row is also run through a hard, always-on privacy gate: if {@link
 * PlayerNamePatterns#nameSpans} still recognises a raw (unmasked) player account name in
 * the row's key, {@link UnmaskedNameConverter} attempts to mask it in place (renumbering
 * every {@code ⟦n⟧} slot in the row by position — see that class's javadoc); only when
 * conversion itself fails (the exact name text cannot be located in the value, or a raw
 * name is somehow still present afterward) is the row rejected, regardless of
 * {@code --drop-chat}. Real data found this matters: a legacy cache entry written before a
 * given frame existed (or before {@code NameMasker} covered its shape) can carry an
 * unmasked name — e.g. an old Auction House "Seller: &lt;name&gt;" tooltip row — that
 * current masking would otherwise catch. (2026-10-01: originally this row was discarded
 * outright; the user decided a safe in-place conversion is preferable to losing the row.)</p>
 */
public final class HubExportTool {
    static final int MAX_ROWS = HubPaths.MAX_FILE_ROWS;
    static final long MAX_BYTES = HubPaths.MAX_FILE_BYTES;

    private HubExportTool() {
    }

    public static void main(String[] args) {
        try {
            Result result = run(args, System.out);
            System.exit(result.exitCode());
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ options

    public static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    public static final class Options {
        public final Path cacheDir;
        public final String language;
        public final HubSource source;
        public final Path out;
        public final boolean mergeIndex;
        public final boolean dropChat;
        /** Optional explicit cap below {@link #MAX_ROWS}; {@code null} means "no
         *  deliberate truncation" (the hard {@link #MAX_ROWS} error still applies). */
        public final Integer maxRows;

        private Options(Path cacheDir, String language, HubSource source, Path out,
                boolean mergeIndex, boolean dropChat, Integer maxRows) {
            this.cacheDir = cacheDir;
            this.language = language;
            this.source = source;
            this.out = out;
            this.mergeIndex = mergeIndex;
            this.dropChat = dropChat;
            this.maxRows = maxRows;
        }

        public static Options parse(String[] args) throws UsageException {
            String cacheDir = null, lang = null, server = null, modpack = null, mod = null, outDir = null;
            String maxRowsText = null;
            boolean mergeIndex = false, dropChat = false;
            int i = 0;
            while (i < args.length) {
                String arg = args[i];
                switch (arg) {
                    case "--cache-dir": cacheDir = value(args, ++i, arg); break;
                    case "--lang": lang = value(args, ++i, arg); break;
                    case "--server": server = value(args, ++i, arg); break;
                    case "--modpack": modpack = value(args, ++i, arg); break;
                    case "--mod": mod = value(args, ++i, arg); break;
                    case "--out": outDir = value(args, ++i, arg); break;
                    case "--merge-index": mergeIndex = true; break;
                    case "--drop-chat": dropChat = true; break;
                    case "--max-rows": maxRowsText = value(args, ++i, arg); break;
                    default: throw new UsageException("Unknown argument: " + arg);
                }
                i++;
            }
            if (cacheDir == null) throw new UsageException("Missing --cache-dir");
            if (lang == null) throw new UsageException("Missing --lang");
            if (outDir == null) throw new UsageException("Missing --out");
            int sourceCount = (server != null ? 1 : 0) + (modpack != null ? 1 : 0) + (mod != null ? 1 : 0);
            if (sourceCount != 1) {
                throw new UsageException("Exactly one of --server / --modpack / --mod is required");
            }
            Integer maxRows = null;
            if (maxRowsText != null) {
                try {
                    maxRows = Integer.valueOf(maxRowsText.strip());
                } catch (NumberFormatException e) {
                    throw new UsageException("--max-rows is not a number: " + maxRowsText);
                }
                if (maxRows < 1 || maxRows > MAX_ROWS) {
                    throw new UsageException("--max-rows must be between 1 and " + MAX_ROWS + ": " + maxRows);
                }
            }
            HubSource source = resolveSource(server, modpack, mod);
            return new Options(Path.of(cacheDir).toAbsolutePath().normalize(),
                    LanguageFileStore.languageTag(lang), source,
                    Path.of(outDir).toAbsolutePath().normalize(), mergeIndex, dropChat, maxRows);
        }

        private static HubSource resolveSource(String server, String modpack, String mod)
                throws UsageException {
            if (server != null) {
                Optional<String> normalized = ServerHostNormalizer.normalize(server);
                if (normalized.isEmpty()) {
                    throw new UsageException("--server is not a public registrable host: " + server);
                }
                return HubSource.server(normalized.get());
            }
            if (modpack != null) {
                String slug = HubSlug.of(modpack);
                if (slug.isEmpty()) throw new UsageException("--modpack produced an empty slug: " + modpack);
                return HubSource.modpack(slug);
            }
            // Mod ids are matched EXACTLY against FabricLoader.getAllMods() at download
            // time (HubDownloader checks index.modIds().contains(modId)); HubSlug would
            // corrupt a valid id such as "placeholder_api" by turning '_' into '-'. Only
            // the safe-path-segment character set is validated, never rewritten.
            String modId = mod.strip();
            if (!modId.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new UsageException("--mod is not a plausible mod id: " + mod);
            }
            return HubSource.mod(modId);
        }

        private static String value(String[] args, int index, String flag) throws UsageException {
            if (index >= args.length) throw new UsageException("Missing value after " + flag);
            return args[index];
        }
    }

    // ------------------------------------------------------------------ result types

    public record Result(int exitCode, ExportStats stats, Path writtenFile) {
    }

    /** Aggregate counts of one export; never row content (a caller that needs samples —
     *  the real-cache replay harness — calls {@link #classify} directly instead).
     *  {@code exportedRows} is the count actually written to the file (after dedup and any
     *  {@code --max-rows} truncation); {@code truncated} is how many otherwise-KEPT rows
     *  that truncation left out. {@code rejectedUnmaskedName} counts rows whose raw-name
     *  gate ({@link UnmaskedNameConverter}) FAILED to convert (the exact name text could
     *  not be located in the value, or a raw name was somehow still present after
     *  conversion) — this gate runs regardless of {@code --drop-chat}.
     *  {@code nameConversionSucceeded} counts rows where the gate instead found and
     *  successfully masked a raw name in place (these still flow through every normal
     *  downstream check — validation, foreign-url, chat — on their CONVERTED text, so a
     *  converted row can still end up {@link Disposition#REJECTED_VALIDATION} etc.;
     *  {@code nameConversionSucceeded} is independent of the row's final disposition).
     *  {@code duplicateKeyGroups}/{@code duplicateRowsDropped} count KEPT rows that
     *  collapsed onto the same key after conversion (distinct original rows whose only
     *  difference was the player name) — see {@link #dedupeKept}. */
    public record ExportStats(int totalRows, int droppedChat, int rejectedValidation,
            int rejectedForeignUrl, int rejectedUnmaskedName, int nameConversionSucceeded,
            int duplicateKeyGroups, int duplicateRowsDropped, int exportedRows, int truncated,
            long bytes, String sha256) {
        static ExportStats counted(List<ClassifiedRow> rows) {
            int total = rows.size(), chat = 0, rejectedValidation = 0, rejectedForeignUrl = 0;
            int rejectedUnmaskedName = 0, nameConversionSucceeded = 0, exported = 0;
            for (ClassifiedRow row : rows) {
                if (row.nameConverted()) nameConversionSucceeded++;
                switch (row.disposition()) {
                    case DROPPED_CHAT: chat++; break;
                    case REJECTED_VALIDATION: rejectedValidation++; break;
                    case REJECTED_FOREIGN_URL: rejectedForeignUrl++; break;
                    case REJECTED_UNMASKED_NAME: rejectedUnmaskedName++; break;
                    case KEPT: exported++; break;
                }
            }
            return new ExportStats(total, chat, rejectedValidation, rejectedForeignUrl,
                    rejectedUnmaskedName, nameConversionSucceeded, 0, 0, exported, 0, 0L, null);
        }

        ExportStats withDedup(int duplicateKeyGroups, int duplicateRowsDropped) {
            return new ExportStats(totalRows, droppedChat, rejectedValidation, rejectedForeignUrl,
                    rejectedUnmaskedName, nameConversionSucceeded, duplicateKeyGroups, duplicateRowsDropped,
                    exportedRows, truncated, bytes, sha256);
        }

        ExportStats withTruncation(int exportedRows, int truncated) {
            return new ExportStats(totalRows, droppedChat, rejectedValidation, rejectedForeignUrl,
                    rejectedUnmaskedName, nameConversionSucceeded, duplicateKeyGroups, duplicateRowsDropped,
                    exportedRows, truncated, bytes, sha256);
        }

        ExportStats withFile(long bytes, String sha256) {
            return new ExportStats(totalRows, droppedChat, rejectedValidation, rejectedForeignUrl,
                    rejectedUnmaskedName, nameConversionSucceeded, duplicateKeyGroups, duplicateRowsDropped,
                    exportedRows, truncated, bytes, sha256);
        }
    }

    public enum Disposition {
        KEPT, DROPPED_CHAT, REJECTED_VALIDATION, REJECTED_FOREIGN_URL, REJECTED_UNMASKED_NAME
    }

    /** One classified row, in memory only — never written anywhere by this class. The
     *  real-cache replay harness uses this to build its human-reviewed samples.
     *  {@code source}/{@code translated} are the CONVERTED key/value when
     *  {@code nameConverted} is {@code true} (every downstream check runs on the
     *  converted text, never the original raw-name text). */
    public record ClassifiedRow(String source, String translated, Disposition disposition, String reason,
            boolean nameConverted) {
        public ClassifiedRow(String source, String translated, Disposition disposition, String reason) {
            this(source, translated, disposition, reason, false);
        }
    }

    // ------------------------------------------------------------------ CLI entry point

    public static Result run(String[] args, PrintStream out) throws IOException, UsageException {
        Options options = Options.parse(args);
        List<ClassifiedRow> rows = classify(options.cacheDir, options.language, options.dropChat);
        ExportStats stats = ExportStats.counted(rows);
        if (stats.exportedRows() == 0) {
            out.println("NOTHING_TO_EXPORT totalRows=" + stats.totalRows()
                    + " droppedChat=" + stats.droppedChat()
                    + " rejectedValidation=" + stats.rejectedValidation()
                    + " rejectedForeignUrl=" + stats.rejectedForeignUrl()
                    + " rejectedUnmaskedName=" + stats.rejectedUnmaskedName()
                    + " nameConversionSucceeded=" + stats.nameConversionSucceeded());
            return new Result(0, stats, null);
        }
        List<ClassifiedRow> keptRows = new ArrayList<>();
        for (ClassifiedRow row : rows) if (row.disposition() == Disposition.KEPT) keptRows.add(row);
        DedupResult deduped = dedupeKept(keptRows);
        Map<String, String> kept = deduped.rows;
        stats = stats.withDedup(deduped.duplicateKeyGroups, deduped.duplicateRowsDropped);
        int keptBeforeTruncation = kept.size();
        if (options.maxRows != null && kept.size() > options.maxRows) {
            // Deterministic (sorted-by-key) truncation: an explicit, reviewable choice the
            // caller opted into, never a silent data loss. index.json and the printed stats
            // both record how many rows this left out.
            List<String> sortedKeys = new ArrayList<>(kept.keySet());
            sortedKeys.sort(null);
            Map<String, String> truncatedKept = new LinkedHashMap<>();
            for (int i = 0; i < options.maxRows; i++) truncatedKept.put(sortedKeys.get(i), kept.get(sortedKeys.get(i)));
            kept = truncatedKept;
        }
        int truncated = keptBeforeTruncation - kept.size();
        if (kept.size() > MAX_ROWS) {
            out.println("ERROR row cap exceeded: " + kept.size() + " > " + MAX_ROWS
                    + " rows (pass --max-rows <=" + MAX_ROWS + " for a deterministic, reviewable"
                    + " truncation instead). The repository has one file per source+language with no"
                    + " multi-part convention, so this tool refuses to invent one. Split by exporting a"
                    + " narrower source, or trim the source cache, then retry.");
            return new Result(1, stats, null);
        }
        stats = stats.withTruncation(kept.size(), truncated);
        // Schema 2: the public file stores only sha256(key) -> translation. The source text
        // (key) never leaves this method.
        Map<String, String> hashed = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : kept.entrySet()) {
            hashed.put(HubKeyHash.of(row.getKey()), row.getValue());
        }
        HubFile file = new HubFile(options.language, hashed);
        Path target = options.out.resolve(relativePath(options.source, options.language));
        Files.createDirectories(target.getParent());
        try {
            file.write(target);
        } catch (IOException writeFailure) {
            // HubFile write ceiling (a generic, mod-wide safety
            // limit, unrelated to the hub) happens to be exactly the same 32 MiB as
            // HubPaths.MAX_FILE_BYTES as of 2026-10-01's second cap raise, so a genuinely
            // oversized export can throw HERE, before the post-write size check below ever
            // runs. write() never moves anything to `target` on this failure path (it
            // throws before the move, and its own finally block deletes its temp file), so
            // there is nothing to clean up here — same graceful handling either way.
            out.println("ERROR file could not be written (likely exceeds " + MAX_BYTES + " bytes for "
                    + kept.size() + " rows): " + writeFailure.getMessage() + ". Same one-file-per-source"
                    + " limitation as the row cap above: split by exporting a narrower source, or trim"
                    + " the source cache, then retry.");
            return new Result(1, stats, null);
        }
        byte[] written = Files.readAllBytes(target);
        if (written.length > MAX_BYTES) {
            Files.deleteIfExists(target);
            out.println("ERROR byte cap exceeded: " + written.length + " > " + MAX_BYTES
                    + " bytes for " + kept.size() + " rows. Same one-file-per-source limitation as the"
                    + " row cap above: split by exporting a narrower source, or trim the source cache.");
            return new Result(1, stats, null);
        }
        String sha256 = sha256Hex(written);
        stats = stats.withFile(written.length, sha256);
        if (options.mergeIndex) {
            mergeIndex(options.out, options.source, options.language, kept.size(), written.length, sha256);
        }
        out.println("EXPORT_OK source=" + options.source.encode() + " lang=" + options.language
                + " totalRows=" + stats.totalRows() + " droppedChat=" + stats.droppedChat()
                + " rejectedValidation=" + stats.rejectedValidation()
                + " rejectedForeignUrl=" + stats.rejectedForeignUrl()
                + " rejectedUnmaskedName=" + stats.rejectedUnmaskedName()
                + " nameConversionSucceeded=" + stats.nameConversionSucceeded()
                + " duplicateKeyGroups=" + stats.duplicateKeyGroups()
                + " duplicateRowsDropped=" + stats.duplicateRowsDropped()
                + " exportedRows=" + stats.exportedRows() + " truncated=" + stats.truncated()
                + " bytes=" + stats.bytes() + " sha256=" + stats.sha256() + " file=" + target);
        return new Result(0, stats, target);
    }

    // ------------------------------------------------------------------ classification

    /**
     * Read the AI-cache rows for {@code language} from a PRIVATE TEMP COPY of
     * {@code cacheDir}'s cache file (see {@link #copyAiCache}: the real file is opened
     * read-only exactly once, for the copy, and never parsed directly), apply validation
     * (+ the chat classifier when {@code dropChat}), and return one verdict per row.
     *
     * <p>A row this mod itself marked provisional, or whose key/value is an internal
     * NUL-prefixed sentinel (see {@link #isSentinel}), is silently excluded from the
     * result entirely: it was never real candidate content, so it is neither kept nor
     * counted as rejected.</p>
     */
    public static List<ClassifiedRow> classify(Path cacheDir, String language, boolean dropChat)
            throws IOException {
        String tag = LanguageFileStore.languageTag(language);
        Path tempDir = Files.createTempDirectory("nyanlex-hub-export-");
        try {
            Path copy = copyAiCache(cacheDir, tag, tempDir);
            List<ClassifiedRow> result = new ArrayList<>();
            if (copy == null) return result;
            FileStore store = new FileStore(copy, false, Integer.MAX_VALUE);
            Map<String, String> rawRows = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : store.entries().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (store.isProvisional(key)) continue;
                if (isSentinel(key) || isSentinel(value)) continue;
                rawRows.put(key, value);
            }
            // Legacy whole-paragraph row splitting runs FIRST, on the raw rows exactly as
            // read from disk — before name conversion/validation/drop-chat/dedup, every one
            // of which then applies independently to EVERY candidate this produces (the
            // original whole row AND each extracted segment row alike). See
            // splitLegacyWholeRows.
            SplitResult split = splitLegacyWholeRows(rawRows);
            for (Map.Entry<String, String> row : split.rows().entrySet()) {
                result.add(classifyOneRow(row.getKey(), row.getValue(), dropChat));
            }
            return result;
        } finally {
            deleteRecursive(tempDir);
        }
    }

    /**
     * The existing per-row pipeline (unmasked-name conversion, bulk-transfer validation,
     * foreign-url rejection, chat classification) applied to exactly one (already
     * split-expanded) key/value pair. Pulled out of {@link #classify} so {@link
     * #splitLegacyWholeRows}'s output — the original whole row AND, when it split, its
     * independent segment rows — each runs through the SAME checks, not a special-cased
     * subset.
     */
    static ClassifiedRow classifyOneRow(String key, String value, boolean dropChat) {
        // Hard privacy gate, independent of --drop-chat: a legacy cache entry written
        // before a PlayerNamePatterns frame existed (or before NameMasker covered its
        // shape) can still carry a RAW, unmasked player account name (observed on real
        // data: old "Seller: <name>" Auction House tooltip rows). If a frame CURRENTLY
        // recognises a name here, attempt an in-place conversion (2026-10-01 user
        // decision) instead of discarding the row: every downstream check below then
        // runs on the CONVERTED text.
        boolean nameConverted = false;
        if (!PlayerNamePatterns.nameSpans(key).isEmpty()) {
            UnmaskedNameConverter.Result converted = UnmaskedNameConverter.convert(key, value);
            if (!converted.ok()) {
                return new ClassifiedRow(key, value, Disposition.REJECTED_UNMASKED_NAME, converted.reason());
            }
            key = converted.key();
            value = converted.value();
            nameConverted = true;
        }
        if (!TranslationCache.usableForBulkTransfer(key, value)) {
            return new ClassifiedRow(key, value, Disposition.REJECTED_VALIDATION,
                    rejectionReason(key, value), nameConverted);
        }
        if (TextFilter.hasForeignUrl(key, value)) {
            return new ClassifiedRow(key, value, Disposition.REJECTED_FOREIGN_URL, "foreign-url", nameConverted);
        }
        if (!HubImportValidator.acceptsOnMerge(value)) {
            // The schema 2 file has no source text, so the downloader can only accept a
            // value that carries no URL/domain at all and stays within the length bound.
            return new ClassifiedRow(key, value, Disposition.REJECTED_FOREIGN_URL, "value-not-publishable",
                    nameConverted);
        }
        ChatLineClassifier.Verdict verdict = ChatLineClassifier.classify(key);
        if (dropChat && verdict.chat()) {
            return new ClassifiedRow(key, value, Disposition.DROPPED_CHAT, verdict.reason(), nameConverted);
        }
        return new ClassifiedRow(key, value, Disposition.KEPT, verdict.reason(), nameConverted);
    }

    // ------------------------------------------------------------------ legacy whole-row split

    /** {@code rows}: the expanded row set (original whole rows AND every successfully
     *  extracted segment row, each a standalone candidate that still flows through the
     *  normal {@link #classifyOneRow} pipeline independently). {@code splittableRows}
     *  counts distinct INPUT rows that yielded at least one segment; {@code
     *  segmentsProduced} counts the segment rows actually added (before any dedup —
     *  two different whole rows can yield the SAME segment key, e.g. two different
     *  listings sharing one "Seller: ⟦0⟧" row); {@code distinctSegmentKeys} is that count
     *  AFTER folding duplicates, i.e. how many NEW distinct keys the split step actually
     *  contributed to {@code rows} (a key equal to an already-kept original row's own key
     *  does not count again here — {@link #dedupeKept} handles value agreement for ANY
     *  repeated key uniformly, downstream, same as it always did). */
    public record SplitResult(Map<String, String> rows, int splittableRows, int segmentsProduced,
            int distinctSegmentKeys) {
    }

    /**
     * Legacy whole-paragraph lazy conversion for hub export (design-segment-cache.md §3/§4;
     * same rule {@link com.dragonmeow.nyanlex.service.TranslationService}'s live-cache
     * {@code convertLegacyWholeCache} uses): a raw AI-cache row whose KEY is a multi-row
     * {@code ⟦PBn⟧}-joined paragraph is, when possible, expanded into its independent
     * TRADE/STATS row-level key/value pairs — the only segment kinds safely recoverable
     * from text that has already been translated as free-form prose (see that method's
     * javadoc for the full rationale: RARITY/INERT need no separate row; ENCHANT/SCROLL
     * have no reliable per-name boundary once translated). {@code
     * TooltipSegmentPlanner.plan} runs with {@code allowRarity=true} unconditionally here
     * (unlike the live render path, which only allows it for a zh-TW/zh-HK target): this
     * tool never COMPOSES a rarity translation from it, it only uses the plan to find row
     * BOUNDARIES, so being more permissive here can only ever find MORE safely-splittable
     * TRADE/STATS rows, never write anything incorrect.
     *
     * <p>A row that is not plan-eligible, fails the whole-text PB-alignment structural
     * check ({@link ParagraphModel#validBreakSequence}, all-or-nothing exactly like the
     * live-cache conversion), or yields zero TRADE/STATS segments is left exactly as it
     * was — "切不開的保留整段列". A row that DOES split keeps contributing its ORIGINAL
     * whole-paragraph entry too ("舊列保留不刪", the same policy the live cache uses) —
     * the segment rows are ADDED, never a replacement, so a combination identical to
     * another export's exact same paragraph still gets its one perfect whole-paragraph
     * reuse opportunity, while every OTHER export now also benefits from the much more
     * broadly reusable per-row segment keys.</p>
     *
     * <p><b>Known limitation — STATS rows are effectively never extracted here, only
     * TRADE:</b> {@link com.dragonmeow.nyanlex.translate.StatsLineComposer}'s row shape
     * requires a LITERAL digit right after the label's colon ({@code "Strength: +10"}) —
     * correct for the live render path, which plans the PRE-template raw text. An on-disk
     * cache KEY has already been through {@code TranslationTemplate}, so a stat row there
     * reads {@code "Strength: ⟦MTn⟧"}, which the composer does not recognise — and because
     * it then matches no OTHER composer either, {@link TooltipSegmentPlanner#plan}
     * conservatively declines the WHOLE paragraph (its normal "one genuinely unclassified
     * row" safety rule), blocking even an otherwise perfectly extractable TRADE row glued
     * to the same paragraph. This is a safe, documented reduction in yield — never
     * incorrect or partial output — not something this method special-cases around.</p>
     */
    public static SplitResult splitLegacyWholeRows(Map<String, String> rows) {
        Map<String, String> expanded = new LinkedHashMap<>(rows);
        int splittableRows = 0;
        int segmentsProduced = 0;
        int distinctSegmentKeys = 0;
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String key = row.getKey();
            String value = row.getValue();
            List<Map.Entry<String, String>> segments = splitOneLegacyRow(key, value);
            if (segments.isEmpty()) continue;
            splittableRows++;
            for (Map.Entry<String, String> segment : segments) {
                segmentsProduced++;
                if (!expanded.containsKey(segment.getKey())) distinctSegmentKeys++;
                // First-occurrence-wins for a key two different split sources agree to
                // disagree on; dedupeKept (downstream, on the KEPT subset only) applies
                // the real majority/tie-break policy uniformly across every repeated key,
                // split-derived or not — this is only about not overwriting here.
                expanded.putIfAbsent(segment.getKey(), segment.getValue());
            }
        }
        return new SplitResult(expanded, splittableRows, segmentsProduced, distinctSegmentKeys);
    }

    /** Empty when {@code key} is not plan-eligible, fails structural validation, or yields
     *  no TRADE/STATS segment — never {@code null}, so the caller can simply check {@code
     *  isEmpty()}. See {@link #splitLegacyWholeRows}. */
    private static List<Map.Entry<String, String>> splitOneLegacyRow(String key, String value) {
        TooltipSegmentPlanner.Plan plan = TooltipSegmentPlanner.plan(key, true);
        if (plan == null) return List.of();
        List<int[]> keyRows = ParagraphModel.splitRawRows(key);
        if (keyRows == null || keyRows.size() < 2) return List.of();
        int expectedBreaks = keyRows.size() - 1;
        if (!ParagraphModel.validBreakSequence(value, expectedBreaks)) return List.of();
        List<int[]> valueRows = ParagraphModel.splitRawRows(value);
        if (valueRows == null || valueRows.size() != keyRows.size()) return List.of();

        List<Map.Entry<String, String>> segments = null;
        for (TooltipSegmentPlanner.Segment segment : plan.segments()) {
            if (segment.kind() != TooltipSegmentPlanner.Kind.TRADE
                    && segment.kind() != TooltipSegmentPlanner.Kind.STATS) {
                continue;
            }
            int rowIndex = ParagraphModel.countBreakTokens(key.substring(0, segment.start()));
            if (rowIndex < 0 || rowIndex >= valueRows.size()) continue;
            int[] valueRow = valueRows.get(rowIndex);
            String valueRowText = value.substring(valueRow[0], valueRow[1]).strip();
            if (valueRowText.isEmpty()) continue;
            String keyRowText = key.substring(segment.start(), segment.end()).strip();
            if (keyRowText.isEmpty()) continue;
            LocalTokenRenumberer.Renumbered renumbered =
                    LocalTokenRenumberer.renumber(keyRowText, valueRowText);
            if (renumbered == null) continue;
            if (!TranslationCache.usableForBulkTransfer(renumbered.key(), renumbered.value())) continue;
            if (segments == null) segments = new ArrayList<>();
            segments.add(Map.entry(renumbered.key(), renumbered.value()));
        }
        return segments == null ? List.of() : segments;
    }

    // ------------------------------------------------------------------ dedup

    private static final class DedupResult {
        final Map<String, String> rows;
        final int duplicateKeyGroups;
        final int duplicateRowsDropped;

        DedupResult(Map<String, String> rows, int duplicateKeyGroups, int duplicateRowsDropped) {
            this.rows = rows;
            this.duplicateKeyGroups = duplicateKeyGroups;
            this.duplicateRowsDropped = duplicateRowsDropped;
        }
    }

    /**
     * Collapse KEPT rows that share a key after name conversion (distinct original cache
     * entries — e.g. two different Auction House listings of the same item template whose
     * ONLY difference was the seller's name — can legitimately produce the identical
     * converted key). Policy (2026-10-01 user instruction: "取最常見或最新"): the most
     * common value for that key wins; a tie is broken by whichever distinct value text
     * appeared LAST in {@code store.entries()} iteration order, treated as the more
     * recent one (the AI cache is an append-only log, so later-iterated entries
     * approximate "most recently (re)translated" — this is a deterministic, documented
     * choice, not a guarantee of true recency).
     */
    private static DedupResult dedupeKept(List<ClassifiedRow> keptRows) {
        Map<String, List<String>> valuesByKey = new LinkedHashMap<>();
        for (ClassifiedRow row : keptRows) {
            valuesByKey.computeIfAbsent(row.source(), k -> new ArrayList<>()).add(row.translated());
        }
        Map<String, String> result = new LinkedHashMap<>();
        int duplicateKeyGroups = 0, duplicateRowsDropped = 0;
        for (Map.Entry<String, List<String>> entry : valuesByKey.entrySet()) {
            List<String> values = entry.getValue();
            if (values.size() == 1) {
                result.put(entry.getKey(), values.get(0));
                continue;
            }
            duplicateKeyGroups++;
            duplicateRowsDropped += values.size() - 1;
            Map<String, Integer> frequency = new LinkedHashMap<>();
            Map<String, Integer> lastSeenAt = new java.util.HashMap<>();
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i);
                frequency.merge(value, 1, Integer::sum);
                lastSeenAt.put(value, i);
            }
            String best = null;
            for (String candidate : frequency.keySet()) {
                if (best == null) {
                    best = candidate;
                    continue;
                }
                int candidateCount = frequency.get(candidate);
                int bestCount = frequency.get(best);
                if (candidateCount > bestCount
                        || (candidateCount == bestCount && lastSeenAt.get(candidate) > lastSeenAt.get(best))) {
                    best = candidate;
                }
            }
            result.put(entry.getKey(), best);
        }
        return new DedupResult(result, duplicateKeyGroups, duplicateRowsDropped);
    }

    /** Internal mod-only sentinel values (keep-original marks, style-failure bookkeeping
     *  keys, style-fallback wrappers, …) are never real player-facing text. Every sentinel
     *  this codebase defines ({@code TranslationCache}'s {@code KEEP_ORIGINAL}/
     *  {@code LEGACY_KEEP_ORIGINAL}/{@code STYLE_FAILURE_PREFIX}, {@code TextFilter}'s
     *  {@code STYLE_FALLBACK_PREFIX}) starts with a NUL character, which no legitimate
     *  Minecraft source line or translator response ever contains; this check is a
     *  deliberately broad net rather than reflecting into those private constants. */
    static boolean isSentinel(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '\u0000';
    }

    /** Best-effort rejection reason using only PUBLIC shape checks (no reflection into
     *  {@code TranslationCache} internals): good enough for a human-reviewed checklist
     *  breakdown, not a byte-exact replay of {@code TranslationCache#usable}'s private
     *  CS/MT/paragraph/protected-placeholder shape checks (lumped into the last bucket). */
    static String rejectionReason(String source, String translated) {
        if (translated == null || translated.isEmpty()) return "empty";
        if (TextFilter.isLikelyMojibake(TextFilter.stripDecorativeSymbols(translated))) return "mojibake";
        if (translated.equals(source)
                || translated.trim().equals(source == null ? "" : source.trim())) return "untranslated-echo";
        if (TextFilter.hasReshapedProtocolToken(translated)) return "reshaped-protocol-token";
        if (countChar(source, '\n') != countChar(translated, '\n')) return "paragraph-break-mismatch";
        if (!TranslationTemplate.layoutSkeletonMatches(source, translated)) return "layout-skeleton-mismatch";
        if (!TranslationTemplate.styleSlotShapeMatches(source, translated)) return "style-slot-mismatch";
        if (TextFilter.isPartialTransliteration(source, translated)) return "partial-transliteration";
        if (TextFilter.hasUntranslatedAnchoredField(source, translated)) return "untranslated-anchored-field";
        return "protected-placeholder-or-marker-shape";
    }

    private static int countChar(String text, char c) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) count++;
        return count;
    }

    // ------------------------------------------------------------------ safe copy-before-read

    /** Copies ONLY {@code nyanlex-ai-cache-<tag>.json} into {@code tempDir}; the real
     *  file in {@code cacheDir} (which may be the player's live config directory) is
     *  opened read-only for this one copy and never touched again — every later parse,
     *  including any {@code FileStore} compaction on load, runs against the copy. Returns
     *  {@code null} when the source file does not exist (nothing to export). */
    private static Path copyAiCache(Path cacheDir, String tag, Path tempDir) throws IOException {
        Path source = cacheDir.resolve("nyanlex-ai-cache-" + tag + ".json");
        if (!Files.isRegularFile(source)) return null;
        Path target = tempDir.resolve(source.getFileName());
        Files.copy(source, target);
        return target;
    }

    private static void deleteRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
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

    // ------------------------------------------------------------------ repository writing

    private static Path relativePath(HubSource source, String language) {
        switch (source.kind()) {
            case SERVER: return Path.of(HubPaths.serverPath(source.identifier(), language));
            case MODPACK: return Path.of(HubPaths.modpackPath(source.identifier(), language));
            default: return Path.of(HubPaths.modPath(source.identifier(), language));
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void mergeIndex(Path out, HubSource source, String language, int rows, long bytes,
            String sha256) throws IOException {
        Path indexFile = out.resolve(HubPaths.indexPath());
        HubIndex index = HubIndex.empty();
        if (Files.isRegularFile(indexFile)) {
            try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
                index = HubIndex.read(reader);
            }
        }
        HubIndex.LanguageStats stats =
                new HubIndex.LanguageStats(rows, bytes, sha256, Instant.now().toString());
        HubIndex updated;
        switch (source.kind()) {
            case SERVER: updated = index.withServerEntry(source.identifier(), language, stats); break;
            case MODPACK: updated = index.withModpackEntry(source.identifier(), language, stats); break;
            default: updated = index.withModEntry(source.identifier(), language, stats); break;
        }
        Files.createDirectories(out);
        Path temp = Files.createTempFile(out, ".nyanlex-hub-index-", ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            updated.write(writer);
        }
        Files.move(temp, indexFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
