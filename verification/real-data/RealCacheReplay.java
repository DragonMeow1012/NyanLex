package com.dragonmeow.nyanlex.translate;

import com.dragonmeow.nyanlex.cache.DynamicNamespacedStore;
import com.dragonmeow.nyanlex.cache.FileStore;
import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.cache.NamespacedStore;
import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.ProviderLanguageFileStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 實機快取重放 (real-cache replay) for the 1.0.7 MODERN core: the master switch
 * ({@code translationRequestsEnabled}) and do-not-translate terms ({@code doNotTranslateTerms}).
 *
 * <p>Every key of a COPY of a player's real caches (AI cache, GT cache, failure ledger) is turned
 * back into the string the loader glue hands to {@link TranslationService} (MT slots get a value
 * whose re-templating reproduces the key, {@code ⟦n⟧} gets a synthetic TAB name, CS/PB markers are
 * kept, WS slots become a real column gap) and replayed through the production TranslationService /
 * TranslationCache / FileStore / NameMasker / DoNotTranslateMatcher classes. Translators are inline
 * fakes; nothing touches the network. Scenarios:</p>
 * <ul>
 *   <li>A  switch OFF on the real files (every AI surface on, GT fallback allowed): cached rows are shown,
 *       misses stay original, 0 translator calls, 0 pending, no failure/session state, files unchanged.
 *       The expected display is read independently from the copied journals. Render lookups need a
 *       colour-marked line's plain semantic row OR (1.0.7 F5) its own final colour projection: a
 *       projection that outlived its semantic row (write-order eviction, or the plain copy never
 *       validated) is shown at once on every surface while a worker rebuilds the semantic row in session
 *       memory only, zero requests (a projection that is itself only a provisional/GT stand-in row is
 *       discarded on read instead, so it is not a usable hit). One-shot chat also accepts the exact
 *       projection, and the chat result is judged the way the glue keeps it (first final callback wins).
 *       A cached row a production guard refuses on every switch state (source not translatable,
 *       decide() partial-transliteration/R17, not meaningful) is counted as explained.</li>
 *   <li>A+ (informational) keys an older build stored untemplated, replayed verbatim with the switch OFF.</li>
 *   <li>C  switch back ON: the A misses are requested (fake called) and then displayed; plus two
 *       informational probes with the switch ON: the F5 projection-only inputs (already shown in A with
 *       0 requests; this probe checks whether switching on ever buys them a redundant request) and a
 *       control run of every chat input that A flagged.</li>
 *   <li>B  do-not-translate terms on fresh inline stores with a verifiable pseudo translator: no term is
 *       ever sent (texts and surface context), every masked occurrence is displayed in its original
 *       spelling, all-term lines send nothing, term-free inputs mask exactly as without terms, no
 *       exception. Extra inputs: real sentences whose slot holds a synthetic hypixel URL (URL exception).</li>
 *   <li>P  DoNotTranslateMatcher timing over every replayed input (catastrophic-backtracking check).</li>
 * </ul>
 * <p>MT slot types are not recorded in a key; each slot gets the first value type (number first) whose
 * re-templating reproduces the key. Keys the current templater cannot produce at all (older builds) and
 * marked keys whose slot lies outside every colour run are counted as unreplayable, not asserted.</p>
 *
 * <p>Build (JDK 21, no Gradle; only the MC-free core packages are needed):</p>
 * <pre>
 *   GSON=~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/&lt;hash&gt;/gson-2.10.1.jar
 *   javac -encoding UTF-8 -cp "$GSON" -d CORE  (every *.java under
 *         src/main/java/com/dragonmeow/nyanlex/{config,cache,service,style,translate})
 *   javac -encoding UTF-8 -cp "CORE;$GSON" -d HARNESS verification/real-data/RealCacheReplay.java
 * </pre>
 * <p>Run (Windows classpath separator shown):</p>
 * <pre>
 *   java -Xmx4g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "HARNESS;CORE;$GSON" \
 *        com.dragonmeow.nyanlex.translate.RealCacheReplay &lt;cache-copy-dir&gt; &lt;out-dir&gt;
 * </pre>
 * <p>{@code <cache-copy-dir>} holds COPIES of {@code nyanlex-ai-cache-zh-tw.json},
 * {@code nyanlex-cache[-provider]-zh-tw.json} and {@code nyanlex-failures-zh-tw.json}. A
 * directory containing {@code nyanlex.json} or {@code nyanlex-codex-home} (a live config
 * directory) is refused; the config file is never opened. The harness copies the three cache files
 * into a fresh {@code <out-dir>/work-*} directory and mutates only that copy. {@code <out-dir>} also
 * receives {@code samples-*.txt} (at most 30 samples per category) which contains the player's cached
 * text: keep {@code <out-dir>} OUTSIDE the repository. stdout carries aggregate counts only.
 * Exit codes: 0 every asserted scenario passed (a scenario with nothing to check in this corpus
 * prints N/A, never PASS), 1 a scenario failed, 2 usage, 3 refused (live config directory),
 * 4 nothing to replay (no zh-tw AI cache copy or no replayable key).</p>
 */
public final class RealCacheReplay {

    // ------------------------------------------------------------------ parameters
    private static final String LANG = "zh-TW";
    private static final String LANG_TAG = "zh-tw";
    private static final Executor DIRECT = Runnable::run;
    private static final int SAMPLE_CAP = 30;
    private static final int CHUNK = 32;
    private static final int MT_PROBE_BUDGET = 4_000;
    private static final int URL_VARIANT_CAP = 3_000;
    private static final double SLOW_MS = 1.0;

    /** Scenario B term list: the user's example plus the most frequent Hypixel proper nouns of the
     *  real corpus (distinct-key whole-word counts); two overlapping pairs are included on purpose
     *  (Hub / Dungeon Hub, Catacombs / The Catacombs) and Hypixel exercises the URL exception. */
    static final List<String> DNT_TERMS = List.of(
            "skyblock", "Accessory Bag", "Bazaar", "Museum", "Slayer", "Heart of the Mountain",
            "Hub", "Kat", "Rift", "Catacombs", "Garden", "Magic Find", "Hypixel", "Dungeon Hub",
            "The Catacombs");

    private static final String KEEP_ORIGINAL = "\u0000MT_KEEP_ORIGINAL2";
    private static final String LEGACY_KEEP_ORIGINAL = "\u0000MT_KEEP_ORIGINAL";
    private static final String STYLE_FAILURE_PREFIX = "\u0000MT_STYLE_FAILURE:";
    private static final String STYLE_FALLBACK_PREFIX = "\u0000MT_STYLE_FALLBACK\u0000";
    private static final String NAMESPACE_SEPARATOR = "\u001F";

    private static final Pattern ANY_TOKEN = Pattern.compile("⟦[^⟦⟧]*⟧");
    private static final Pattern KNOWN_TOKEN =
            Pattern.compile("⟦(?:MT\\d{1,6}|WS\\d{1,6}|/?CS\\d{1,6}|PB\\d{1,6}|\\d{1,6})⟧");
    private static final Pattern MT_TOKEN = Pattern.compile("⟦MT(\\d+)⟧");
    private static final Pattern WS_SPACED = Pattern.compile(" ⟦WS(\\d+)⟧ ");
    private static final Pattern NAME_TOKEN = Pattern.compile("⟦(\\d+)⟧");
    private static final Pattern NAME_AT_END = Pattern.compile("⟦\\d+⟧$");
    private static final Pattern NAME_AT_START = Pattern.compile("^⟦\\d+⟧");
    private static final Pattern CS_MARKER = Pattern.compile("⟦\\s*/?\\s*CS\\s*\\d+\\s*⟧");
    private static final Pattern CS_OPEN = Pattern.compile("⟦CS\\d+⟧");
    private static final Pattern PB_TOKEN = Pattern.compile("⟦\\s*PB\\s*\\d+\\s*⟧");
    private static final Pattern WS_ANY = Pattern.compile("⟦\\s*WS\\s*\\d+\\s*⟧");
    private static final Pattern MT_LOOSE = Pattern.compile("⟦\\s*MT\\s*(\\d+)\\s*⟧");
    private static final Pattern NAME_LOOSE = Pattern.compile("⟦\\s*(\\d+)\\s*⟧");
    private static final Pattern FW_COMMA = Pattern.compile("(?<=[0-9])，(?=[0-9])");
    private static final Pattern FW_DOT = Pattern.compile("(?<=[0-9])．(?=[0-9])");

    /** Synthetic TAB-list names for {@code ⟦n⟧} placeholders (letters only, never real words). */
    private static final List<String> NAMES;
    private static final Set<String> NAME_SET;
    static {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 64; i++) names.add("Qz" + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        NAMES = List.copyOf(names);
        NAME_SET = Set.copyOf(names);
    }

    /** Pseudo translation alphabet: every ASCII letter becomes a distinct CJK ideograph. */
    private static final String CJK_LOWER = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥天地玄黃";
    private static final String CJK_UPPER = "宇宙洪荒日月盈昃宿列張寒來暑往秋收冬藏閏餘成歲律呂調";

    private static final TranslationTemplate TEMPLATES = new TranslationTemplate();
    private static final Method CACHE_USABLE2 =
            privateStatic(TranslationCache.class, "usable", String.class, String.class);
    private static final Method CACHE_USABLE1 = privateStatic(TranslationCache.class, "usable", String.class);
    private static final Method CACHE_CS_SHAPE =
            privateStatic(TranslationCache.class, "matchingCsShape", String.class, String.class);
    private static final List<String> SESSION_TABLES = List.of("failedUntil", "contentFailures",
            "contentRetryAttempts", "retrySnapshots", "provisionalRetryAttempts", "provisionalRetrying",
            "sessionRetryDemand", "queue", "flights", "finalWaiters");

    private final Path work;
    private final Samples samples;
    private final Map<String, Long> counters = new TreeMap<>();

    private RealCacheReplay(Path work, Samples samples) {
        this.work = work;
        this.samples = samples;
    }

    // ================================================================== entry point
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: RealCacheReplay <cache-copy-dir> <out-dir>");
            System.exit(2);
        }
        Path dataDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outDir = Paths.get(args[1]).toAbsolutePath().normalize();
        if (Files.exists(dataDir.resolve("nyanlex.json"))
                || Files.exists(dataDir.resolve("nyanlex-codex-home"))) {
            System.err.println("REFUSED: the directory looks like a live Minecraft config directory."
                    + " Pass a directory holding COPIES of the cache files only.");
            System.exit(3);
        }
        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String label = dataDir.getFileName() == null ? "data" : dataDir.getFileName().toString();
        Path work = outDir.resolve("work-" + label + "-" + stamp);
        int copied = copyCacheFiles(dataDir, work);
        System.out.println("RUN dataset=" + label + " workCopyFiles=" + copied);
        if (!Files.isRegularFile(work.resolve("nyanlex-ai-cache-" + LANG_TAG + ".json"))) {
            // Never report success for a run that had nothing to replay (wrong/empty dir).
            System.out.println("REAL_CACHE_REPLAY_NO_DATA (no nyanlex-ai-cache-" + LANG_TAG + ".json)");
            System.exit(4);
        }
        Path samplesFile = outDir.resolve("samples-" + label + "-" + stamp + ".txt");
        int status;
        try (Samples samples = new Samples(samplesFile)) {
            status = new RealCacheReplay(work, samples).execute();
        }
        System.out.println("SAMPLES_FILE " + samplesFile.getFileName());
        System.out.println(status == 0 ? "REAL_CACHE_REPLAY_OK"
                : status == 4 ? "REAL_CACHE_REPLAY_NO_DATA" : "REAL_CACHE_REPLAY_FAILED");
        System.exit(status);
    }

    private static int copyCacheFiles(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Pattern allowed = Pattern.compile(
                "nyanlex-(?:ai-cache|cache|failures)-" + LANG_TAG + "\\.json");
        int copied = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(from)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !allowed.matcher(name).matches()) continue;
                Files.copy(file, to.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            }
        }
        return copied;
    }

    // ================================================================== orchestration
    /** Known failure-ledger namespaces; anything else is printed as "(other)", never verbatim. */
    private static final Set<String> KNOWN_NAMESPACES = Set.of("ai", "gt", "gt-google");

    /** @return 0 every asserted scenario passed, 1 a scenario failed, 4 nothing to replay */
    private int execute() throws Exception {
        String provider = detectProvider(work);
        Path aiFile = work.resolve("nyanlex-ai-cache-" + LANG_TAG + ".json");
        Path gtFile = work.resolve("google".equals(provider)
                ? "nyanlex-cache-" + LANG_TAG + ".json"
                : "nyanlex-cache-" + provider + "-" + LANG_TAG + ".json");
        Path failFile = work.resolve("nyanlex-failures-" + LANG_TAG + ".json");
        Journal ai0 = Journal.read(aiFile);
        Journal gt0 = Journal.read(gtFile);
        Journal fail0 = Journal.read(failFile);
        stat("DATA", "gtProvider", provider);
        stat("DATA", "ai.schema", ai0.schema);
        stat("DATA", "ai.live", ai0.live.size());
        stat("DATA", "ai.keepOriginalRows", ai0.live.values().stream().filter(KEEP_ORIGINAL::equals).count());
        stat("DATA", "ai.provisional", ai0.provisional.size());
        stat("DATA", "ai.badLines", ai0.badLines);
        stat("DATA", "gt.schema", gt0.schema);
        stat("DATA", "gt.live", gt0.live.size());
        stat("DATA", "failures.live", fail0.live.size());

        // ---- 1. inputs ---------------------------------------------------------------
        LinkedHashMap<String, Integer> origins = new LinkedHashMap<>();
        for (String key : ai0.live.keySet()) origins.merge(key, 1, (a, b) -> a | b);
        for (String key : gt0.live.keySet()) origins.merge(key, 2, (a, b) -> a | b);
        Map<String, Integer> namespaces = new TreeMap<>();
        for (String scoped : fail0.live.keySet()) {
            int sep = scoped.indexOf(NAMESPACE_SEPARATOR);
            String namespace = sep < 0 ? "(none)" : scoped.substring(0, sep);
            namespaces.merge(KNOWN_NAMESPACES.contains(namespace) || sep < 0 ? namespace : "(other)", 1, Integer::sum);
            String bare = sep < 0 ? scoped : scoped.substring(sep + 1);
            if (bare.startsWith(STYLE_FAILURE_PREFIX)) bare = bare.substring(STYLE_FAILURE_PREFIX.length());
            if (!bare.isEmpty()) origins.merge(bare, 4, (a, b) -> a | b);
        }
        namespaces.forEach((ns, n) -> stat("DATA", "failures.namespace." + ns, n));

        List<Replay> replays = new ArrayList<>();
        List<String> rawLegacy = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        long[] famTotal = new long[FAMILIES.length];
        long[] famReplay = new long[FAMILIES.length];
        long[] originTotal = new long[3];
        long[] originReplay = new long[3];
        long started = System.nanoTime();
        for (Map.Entry<String, Integer> entry : origins.entrySet()) {
            String key = entry.getKey();
            int fam = families(key);
            String[] why = new String[1];
            Replay replay = reconstruct(key, why);
            for (int f = 0; f < FAMILIES.length; f++) {
                if ((fam & (1 << f)) == 0) continue;
                famTotal[f]++;
                if (replay != null) famReplay[f]++;
            }
            for (int o = 0; o < 3; o++) {
                if ((entry.getValue() & (1 << o)) == 0) continue;
                originTotal[o]++;
                if (replay != null) originReplay[o]++;
            }
            if (replay == null) {
                reasons.merge(why[0], 1, Integer::sum);
                samples.add("RECON.unreplayable." + why[0], show(key));
                if (why[0].startsWith("verify-key(raw")) rawLegacy.add(key);
                continue;
            }
            replay.origins = entry.getValue();
            replays.add(replay);
        }
        stat("RECON", "uniqueKeys", origins.size());
        stat("RECON", "replayable", replays.size());
        stat("RECON", "replayableRatio", ratio(replays.size(), origins.size()));
        stat("RECON", "seconds", seconds(started));
        for (int f = 0; f < FAMILIES.length; f++) {
            stat("RECON", "family." + FAMILIES[f] + ".keys", famTotal[f]);
            stat("RECON", "family." + FAMILIES[f] + ".replayable", famReplay[f]);
            stat("RECON", "family." + FAMILIES[f] + ".ratio", ratio(famReplay[f], famTotal[f]));
        }
        String[] originNames = {"aiCache", "gtCache", "failureLedger"};
        for (int o = 0; o < 3; o++) {
            stat("RECON", "origin." + originNames[o] + ".keys", originTotal[o]);
            stat("RECON", "origin." + originNames[o] + ".replayable", originReplay[o]);
        }
        reasons.forEach((reason, n) -> stat("RECON", "unreplayable." + reason, n));
        if (replays.isEmpty()) {
            stat("VERDICT", "all", "NO_DATA (no replayable input)");
            return 4;
        }

        // ---- 2. scenarios A / A+ / C on the production file-backed wiring ----------
        Rig rig = new Rig(provider);
        boolean passA = scenarioA(rig, replays, ai0, gt0, fail0, aiFile, gtFile, failFile);
        scenarioLegacyProbe(rig, rawLegacy, aiFile, gtFile, failFile);
        boolean passC = scenarioC(rig, replays, fail0, aiFile);

        // ---- 3. scenario B on fresh inline stores; P timing --------------------------
        List<Replay> bInputs = new ArrayList<>(replays);
        bInputs.addAll(urlVariants(replays));
        boolean passB = scenarioB(bInputs);
        timing(bInputs);

        // ---- 4. scenario D: 1.0.7 P1.1/P1.2/P1.4/P1.7 key-shape audit (D1,D2,D5,D7) ----
        scenarioD(origins.keySet(), ai0);

        counters.forEach((name, n) -> stat("COUNT", name, n));
        // N/A = nothing of that kind in this corpus: not a failure, but never reported as PASS.
        stat("VERDICT", "A_switchOff", passA ? "PASS" : "FAIL");
        stat("VERDICT", "C_switchBackOn", !passC ? "FAIL" : coldMisses.isEmpty() ? "N/A (no cold miss)" : "PASS");
        stat("VERDICT", "B_doNotTranslate", !passB ? "FAIL" : termInputs == 0 ? "N/A (no term occurrence)" : "PASS");
        return passA && passC && passB ? 0 : 1;
    }

    private long termInputs;

    private static String detectProvider(Path dir) {
        // Google is the only machine source.
        return "google";
    }

    // ================================================================== reconstruction
    private static final String[] FAMILIES = {"MT", "WS", "CS", "PB", "N"};

    private static int families(String key) {
        int out = 0;
        if (MT_TOKEN.matcher(key).find()) out |= 1;
        if (key.contains("⟦WS")) out |= 2;
        if (CS_MARKER.matcher(key).find()) out |= 4;
        if (PB_TOKEN.matcher(key).find()) out |= 8;
        if (NAME_TOKEN.matcher(key).find()) out |= 16;
        return out;
    }

    static final class Replay {
        final String key;      // cache key K (masked + templated)
        final String input;    // X: what the glue hands to TranslationService
        final String masked;   // M: X with player names masked (what the cache receives)
        final List<String> names;
        final TranslationTemplate.Snapshot snap; // production snapshot of M (key == K)
        final boolean urlVariant;
        int origins;

        Replay(String key, String input, String masked, List<String> names,
               TranslationTemplate.Snapshot snap, boolean urlVariant) {
            this.key = key;
            this.input = input;
            this.masked = masked;
            this.names = names;
            this.snap = snap;
            this.urlVariant = urlVariant;
        }
    }

    /** @return the replay, or null with {@code why[0]} set to the reason. */
    private Replay reconstruct(String key, String[] why) {
        Matcher any = ANY_TOKEN.matcher(key);
        int tokens = 0;
        while (any.find()) {
            tokens++;
            if (!KNOWN_TOKEN.matcher(any.group()).matches()) return fail(why, "unknown-token");
        }
        if (countChar(key, '⟦') != tokens || countChar(key, '⟧') != tokens) {
            return fail(why, "stray-bracket");
        }
        Matcher nm = NAME_TOKEN.matcher(key);
        int nextName = 0;
        while (nm.find()) {
            int index = Integer.parseInt(nm.group(1));
            if (index == nextName) nextName++;
            else if (index > nextName) return fail(why, "name-order");
        }
        if (nextName > NAMES.size()) return fail(why, "name-pool");
        List<String> names = NAMES.subList(0, nextName);

        List<int[]> slots = new ArrayList<>();
        Matcher mt = MT_TOKEN.matcher(key);
        while (mt.find()) {
            if (Integer.parseInt(mt.group(1)) != slots.size()) return fail(why, "mt-order");
            slots.add(new int[] {mt.start(), mt.end()});
        }
        int n = slots.size();
        String[] texts = new String[n + 1];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            texts[i] = key.substring(pos, slots.get(i)[0]);
            pos = slots.get(i)[1];
        }
        texts[n] = key.substring(pos);
        String[] chosen = new String[n];
        if (n > 0) {
            int[] budget = {MT_PROBE_BUDGET};
            if (!searchSlots(0, texts, chosen, key, budget)) {
                return fail(why, budget[0] <= 0 ? "mt-budget" : "mt-no-candidate");
            }
        }
        return finish(key, texts, chosen, names, false, why);
    }

    private Replay finish(String key, String[] texts, String[] chosen, List<String> names,
                          boolean urlVariant, String[] why) {
        String layout = partial(texts, chosen, chosen.length - 1);
        String masked = WS_SPACED.matcher(layout).replaceAll("   ");
        if (masked.contains("⟦WS")) return fail(why, "ws-shape");
        String input = replaceNames(masked, names);
        NameMasker.Masked remasked = NameMasker.mask(input, NAME_SET, DoNotTranslateMatcher.EMPTY);
        if (!remasked.text().equals(masked)) return fail(why, "verify-mask");
        TranslationTemplate.Snapshot snap = TEMPLATES.prepare(masked);
        if (!snap.key().equals(key)) {
            boolean raw = !MT_TOKEN.matcher(key).find() && !key.contains("⟦WS")
                    && !NAME_TOKEN.matcher(key).find();
            return fail(why, raw ? "verify-key(raw-legacy)" : "verify-key");
        }
        // The glue wraps every non-blank run in a colour pair, so a real marked input always
        // has a valid CS topology. A key whose slot sits outside every pair (an older build
        // swallowed "[MVP+] Name" into one slot) has no glue-producible input.
        if (CS_MARKER.matcher(masked).find() && !call(CACHE_CS_SHAPE, masked, masked)) {
            return fail(why, "cs-topology-not-glue-producible");
        }
        return new Replay(key, input, masked, names, snap, urlVariant);
    }

    private static Replay fail(String[] why, String reason) {
        why[0] = reason;
        return null;
    }

    /** Depth-first search for slot values; each probe re-templates the partially filled key. */
    private static boolean searchSlots(int slot, String[] texts, String[] chosen, String key, int[] budget) {
        if (slot == chosen.length) return true;
        for (String value : slotCandidates(slot)) {
            if (!nameAdjacencyOk(texts[slot], value, texts[slot + 1])) continue;
            if (budget[0]-- <= 0) return false;
            chosen[slot] = value;
            if (TemplateText.prepare(partial(texts, chosen, slot)).text().equals(key)
                    && searchSlots(slot + 1, texts, chosen, key, budget)) {
                return true;
            }
            if (budget[0] <= 0) return false;
        }
        chosen[slot] = null;
        return false;
    }

    /** Values of the TemplateText slot types, most common (a number) first. */
    private static List<String> slotCandidates(int slot) {
        String n = Integer.toString(7 + slot);
        return List.of(n, "✦", "[MVP+]", "x" + n, n + "th", "12:" + (10 + slot % 50), n + "m",
                "-----", "hypixel.net", "123e4567-e89b-12d3-a456-4266141740" + String.format("%02d", slot % 100),
                "mega" + n + "A", "07/10/26 m6GA5", n + "天", n + "%", "+" + n, "-" + n, n + ".5",
                n + "k", "✦✦", "☘");
    }

    /** A value touching a {@code ⟦n⟧} placeholder must not glue itself onto the player name. */
    private static boolean nameAdjacencyOk(String before, String value, String after) {
        if (NAME_AT_END.matcher(before).find() && isNameChar(value.charAt(0))) return false;
        return !(NAME_AT_START.matcher(after).find() && isNameChar(value.charAt(value.length() - 1)));
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Key text with slots 0..upTo filled and every later slot left as its literal token. */
    private static String partial(String[] texts, String[] chosen, int upTo) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < texts.length; i++) {
            out.append(texts[i]);
            if (i < chosen.length) out.append(i <= upTo ? chosen[i] : "⟦MT" + i + "⟧");
        }
        return out.toString();
    }

    private static String replaceNames(String text, List<String> names) {
        Matcher m = NAME_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(index < names.size() ? names.get(index) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Scenario B/P extra inputs: a real sentence with one slot filled by a URL value instead. */
    private List<Replay> urlVariants(List<Replay> replays) {
        String[] urls = {"hypixel.net", "www.hypixel.net", "store.hypixel.net/skyblock"};
        List<Replay> out = new ArrayList<>();
        for (Replay r : replays) {
            if (out.size() >= URL_VARIANT_CAP) break;
            List<String> values = r.snap.base().values();
            if (values.isEmpty()) continue;
            Matcher mt = MT_TOKEN.matcher(r.key);
            List<int[]> slots = new ArrayList<>();
            while (mt.find()) slots.add(new int[] {mt.start(), mt.end()});
            if (slots.size() != values.size()) continue;
            String[] texts = new String[slots.size() + 1];
            int pos = 0;
            for (int i = 0; i < slots.size(); i++) {
                texts[i] = r.key.substring(pos, slots.get(i)[0]);
                pos = slots.get(i)[1];
            }
            texts[slots.size()] = r.key.substring(pos);
            String url = urls[out.size() % urls.length];
            for (int slot = 0; slot < slots.size(); slot++) {
                String[] chosen = values.toArray(new String[0]);
                chosen[slot] = url;
                if (!nameAdjacencyOk(texts[slot], url, texts[slot + 1])) continue;
                if (!TemplateText.prepare(partial(texts, chosen, chosen.length - 1)).text().equals(r.key)) continue;
                Replay variant = finish(r.key, texts, chosen, r.names, true, new String[1]);
                if (variant != null) {
                    variant.origins = r.origins;
                    out.add(variant);
                    break;
                }
            }
        }
        stat("RECON", "urlVariants", out.size());
        return out;
    }

    // ================================================================== scenario D (key/pattern audit)
    /**
     * Reconstructs the glue-facing input text for a raw origin key WITHOUT requiring the
     * new core's own masking+templating to reproduce that same key (unlike {@link
     * #reconstruct}, whose stricter "replayable" definition is used by A/B/C/P and by
     * construction excludes every key P1.1/P1.2/P1.4 would now compute differently — the
     * exact population D1 needs to measure). Returns {@code null} only when the key
     * itself cannot be parsed into slots at all (same early structural failures as
     * {@link #reconstruct}: unknown/stray tokens, non-sequential name or MT indices, or
     * no MT candidate value round-trips) — those keys are equally untestable under either
     * core and are excluded from D1/D2/D5/D7, not counted as "unchanged".
     */
    private String keyDriftInput(String key) {
        Matcher any = ANY_TOKEN.matcher(key);
        int tokens = 0;
        while (any.find()) {
            tokens++;
            if (!KNOWN_TOKEN.matcher(any.group()).matches()) return null;
        }
        if (countChar(key, '⟦') != tokens || countChar(key, '⟧') != tokens) return null;
        Matcher nm = NAME_TOKEN.matcher(key);
        int nextName = 0;
        while (nm.find()) {
            int index = Integer.parseInt(nm.group(1));
            if (index == nextName) nextName++;
            else if (index > nextName) return null;
        }
        if (nextName > NAMES.size()) return null;
        List<String> names = NAMES.subList(0, nextName);

        List<int[]> slots = new ArrayList<>();
        Matcher mt = MT_TOKEN.matcher(key);
        while (mt.find()) {
            if (Integer.parseInt(mt.group(1)) != slots.size()) return null;
            slots.add(new int[] {mt.start(), mt.end()});
        }
        int n = slots.size();
        String[] texts = new String[n + 1];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            texts[i] = key.substring(pos, slots.get(i)[0]);
            pos = slots.get(i)[1];
        }
        texts[n] = key.substring(pos);
        String[] chosen = new String[n];
        if (n > 0) {
            int[] budget = {MT_PROBE_BUDGET};
            if (!searchSlots(0, texts, chosen, key, budget)) return null;
        }
        String layout = partial(texts, chosen, chosen.length - 1);
        String masked = WS_SPACED.matcher(layout).replaceAll("   ");
        if (masked.contains("⟦WS")) return null;
        return replaceNames(masked, names);
    }

    private static final Pattern D1_CALENDAR_DATE = Pattern.compile("(?i)\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul"
            + "|Aug|Sept?|Oct|Nov|Dec|January|February|March|April|June|July|August|September"
            + "|October|November|December)\\.?\\s+\\d{1,2},\\s+\\d{4}(?![A-Za-z0-9])");
    private static final Pattern D1_SYMBOL = Pattern.compile("[①-⓿❶-➓]");
    private static final Pattern D1_SERVER_ID = Pattern.compile("(?i)sending\\s+to\\s+server\\s+\\S+");
    private static final Pattern D7_ADJACENT_SENTINEL = Pattern.compile(
            "⟦\\d+⟧⟦\\s*/?\\s*(?:CS|MT)\\s*\\d+\\s*⟧|⟦\\s*/?\\s*(?:CS|MT)\\s*\\d+\\s*⟧⟦\\d+⟧");

    /** Best-effort attribution of why the new core's key differs from the key on disk,
     *  in the same priority the 1.0.7 phase 1 steps were implemented in (step2's player
     *  name slots first, since a line can incidentally also contain a date/symbol/server
     *  token that did not itself move). Not exhaustive by construction — "other" is
     *  reported with samples rather than guessed at. */
    private static String classifyKeyChangeReason(String input) {
        if (!PlayerNamePatterns.nameSpans(input).isEmpty()) return "player_name_pattern";
        if (D1_CALENDAR_DATE.matcher(input).find()) return "date";
        if (D1_SYMBOL.matcher(input).find()) return "master_star_or_symbol";
        if (D1_SERVER_ID.matcher(input).find()) return "server_id";
        return "other";
    }

    /** Nouns a strong sentence frame could grab that are not already excluded by {@code
     *  PlayerNamePatterns}' own stopword list (mob/NPC names, common item nouns, staff
     *  roles) — used only to flag candidates for D2's manual review, never to change
     *  behaviour. Deliberately independent of the production stopword list. */
    private static final Set<String> D2_DICTIONARY = new HashSet<>(Arrays.asList(
            "TEST", "SAMPLE", "GUEST", "FRIEND", "BUDDY", "RANDOM", "UNKNOWN", "ANONYMOUS",
            "SOMETHING", "NOTHING", "ANYONE", "ADMIN", "STAFF", "HELPER", "MODERATOR", "OWNER",
            "DEV", "SUPPORT", "BUILD", "CLAN",
            "ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "ENDERMAN", "SLIME", "WITCH", "BLAZE",
            "GHAST", "GUARDIAN", "WOLF", "WATCHER", "SENTRY", "ZEALOT", "WITHER", "GHOST",
            "PHANTOM", "ENDER", "DRAGON", "GOLEM", "VOIDLING", "REVENANT", "TARANTULA",
            "BROODMOTHER", "SVEN", "WEREWOLF", "YETI", "GOLDEN", "FROZEN", "SCARED", "BABY",
            "MASTER", "SKELETOR", "MAGMA", "CUBE", "RAT", "BAT", "SILVERFISH", "VOID",
            "SWORD", "BOW", "AXE", "PICKAXE", "HOE", "SHOVEL", "HELMET", "CHESTPLATE",
            "LEGGINGS", "BOOTS", "RING", "ARTIFACT", "TALISMAN", "WAND", "STAFF", "BOOK",
            "SCROLL", "POTION", "CAKE", "BUNDLE", "PACKAGE", "TICKET", "CHIP", "FRAGMENT",
            "SHARD", "ROD", "GAUNTLET", "SACK", "BAG"));

    private void scenarioD(Set<String> originKeys, Journal ai0) throws IOException {
        long started = System.nanoTime();
        int tested = 0;
        int untestable = 0;
        int changed = 0;
        int whitespaceOnlyChanged = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        List<String> otherSamples = new ArrayList<>();
        Map<String, Integer> nameOccurrences = new TreeMap<>();
        Map<String, List<String>> familyValues = new LinkedHashMap<>();
        int d7NewKeyHits = 0;
        int d7OldKeyHits = 0;

        // Dump every reconstructible input, in journal (write-order) iteration order, for
        // the separate dual-core tools (D3 request/token replay, D6 mask()/lookup() perf)
        // that must run this same population against a SECOND, HEAD-compiled core in a
        // separate process (a single JVM cannot load two versions of the same class).
        Path dumpFile = work.resolve("scenario-d-inputs.txt");
        try (BufferedWriter dump = Files.newBufferedWriter(dumpFile, StandardCharsets.UTF_8)) {
        for (String key : originKeys) {
            String input = keyDriftInput(key);
            if (input == null) {
                untestable++;
                continue;
            }
            tested++;
            dump.write(input);
            dump.newLine();
            NameMasker.Masked masked = NameMasker.mask(input, NAME_SET, DoNotTranslateMatcher.EMPTY);
            String newKey = TEMPLATES.prepare(masked.text()).key();

            if (D7_ADJACENT_SENTINEL.matcher(newKey).find()) d7NewKeyHits++;
            if (D7_ADJACENT_SENTINEL.matcher(key).find()) d7OldKeyHits++;

            for (int[] span : PlayerNamePatterns.nameSpans(input)) {
                nameOccurrences.merge(input.substring(span[0], span[1]), 1, Integer::sum);
            }

            String aiValue = ai0.live.get(key);
            if (aiValue != null && !aiValue.startsWith("\u0000") && !KEEP_ORIGINAL.equals(aiValue)) {
                String normalized = aiValue.replaceAll("[A-Za-z0-9_]+", "\u0001").replaceAll("\\s+", "");
                familyValues.computeIfAbsent(newKey, k -> new ArrayList<>()).add(normalized);
            }

            if (newKey.equals(key)) continue;
            changed++;
            if (key.replaceAll("\\s+", "").equals(newKey.replaceAll("\\s+", ""))) whitespaceOnlyChanged++;
            String reason = classifyKeyChangeReason(input);
            reasons.merge(reason, 1, Integer::sum);
            if (reason.equals("other") && otherSamples.size() < 20) {
                otherSamples.add(show(key) + "\n    newKey=" + show(newKey));
            }
        }
        }
        stat("D", "inputsDumpFile", dumpFile.getFileName());

        // ---- D1: key-shape drift vs the key already on disk (INFORMATIONAL ONLY) -------
        // CAUTION: the disk key mixes many historical builds, some older than HEAD itself
        // (e.g. a literal "x2" quantity that BOTH HEAD and the worktree already template
        // identically to ⟦MTn⟧ — confirmed by a HEAD-vs-worktree probe — still counts as
        // "changed" here purely because it predates HEAD). This block is a cheap, free
        // by-product of the D1 loop, not the authoritative HEAD-vs-worktree count: that
        // comparison needs the SAME core version on both sides of the diff, which a single
        // JVM cannot give two copies of the same class, so it is done by the companion
        // KeyDumpOld.java / KeyDumpNew.java tools (each compiled once against HEAD 33c220f
        // and once against this worktree) against the {@code scenario-d-inputs.txt} dump
        // below — see impl-P1-step6-replay.md D1 for that run and its numbers.
        stat("D1", "uniqueOriginKeys", originKeys.size());
        stat("D1", "tested", tested);
        stat("D1", "untestable_structural", untestable);
        stat("D1", "vsOnDiskKey.changed_includesPreHeadHistory", changed);
        stat("D1", "vsOnDiskKey.changedRatio", ratio(changed, tested));
        stat("D1", "vsOnDiskKey.whitespaceOnly_notCountedAsRegression", whitespaceOnlyChanged);
        reasons.forEach((reason, n) -> stat("D1", "vsOnDiskKey.reason." + reason, n));
        for (String sample : otherSamples) samples.add("D1.vsOnDiskKey.other_examples", sample);

        // ---- D2: pattern precision (names vs. common English/item/mob words) -----------
        Map<String, Integer> suspicious = new TreeMap<>();
        for (Map.Entry<String, Integer> e : nameOccurrences.entrySet()) {
            if (D2_DICTIONARY.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                suspicious.put(e.getKey(), e.getValue());
            }
        }
        stat("D2", "distinctNamesExtracted", nameOccurrences.size());
        stat("D2", "totalNameOccurrences", nameOccurrences.values().stream().mapToLong(Integer::longValue).sum());
        stat("D2", "dictionaryOverlap.distinctWords", suspicious.size());
        stat("D2", "dictionaryOverlap.occurrences", suspicious.values().stream().mapToLong(Integer::longValue).sum());
        suspicious.forEach((word, n) -> samples.add("D2.dictionary_overlap_word", word + " x" + n));

        // ---- D5: family consistency (old scattered wordings -> one new key) ------------
        int familiesMulti = 0;
        int familiesFragmented = 0;
        long originalRowsInFragmentedFamilies = 0;
        Map<Integer, Integer> distinctHistogram = new TreeMap<>();
        for (List<String> values : familyValues.values()) {
            if (values.size() < 2) continue;
            familiesMulti++;
            long distinct = values.stream().distinct().count();
            distinctHistogram.merge((int) distinct, 1, Integer::sum);
            if (distinct > 1) {
                familiesFragmented++;
                originalRowsInFragmentedFamilies += values.size();
            }
        }
        stat("D5", "note", "value normalization for wording comparison: every [A-Za-z0-9_]+ run "
                + "(covers the name AND any numeric slot) collapsed to one marker, all whitespace "
                + "removed (so the known name+digit/k/m/b CJK-spacing-only difference is never "
                + "counted as a distinct wording)");
        stat("D5", "familiesWithMultipleOriginalRows", familiesMulti);
        stat("D5", "familiesFragmented_2plusDistinctWordings", familiesFragmented);
        stat("D5", "familiesFragmentedRatio", ratio(familiesFragmented, familiesMulti));
        stat("D5", "originalRowsInFragmentedFamilies", originalRowsInFragmentedFamilies);
        distinctHistogram.forEach((distinct, n) -> stat("D5", "distinctWordingCount[" + distinct + "].families", n));

        // ---- D7: sentinels immediately touching a CS/MT token --------------------------
        stat("D7", "adjacentSentinelInputs_newKey", d7NewKeyHits);
        stat("D7", "adjacentSentinelInputs_onDiskKey_mixedHistory", d7OldKeyHits);
        stat("D7", "seconds", seconds(started));
    }

    // ================================================================== production wiring
    private static TranslatorConfig userLikeConfig(String provider) {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = LANG;
        cfg.machineTranslationProvider = provider;
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.aiChat = cfg.aiTooltip = cfg.aiScoreboard = cfg.aiName = cfg.aiBossBar = true;
        cfg.aiTitle = cfg.aiActionBar = cfg.aiBook = cfg.aiScreenScan = cfg.aiScreenText = true;
        cfg.disableGoogleFallbackForAi = false;
        cfg.protectPlayerNames = true;
        return cfg.normalized();
    }

    /** The fabric26 (player's build) wiring over the work copy, with inline fake translators. */
    private final class Rig {
        final TranslatorConfig cfg;
        final FakeTranslator gtFake = new FakeTranslator();
        final FakeTranslator aiFake = new FakeTranslator();
        final TranslationCache gt;
        final TranslationCache ai;
        final TranslationService service;
        final ChurnGuard guard;

        Rig(String provider) {
            cfg = userLikeConfig(provider);
            PersistentStore googleStore = new ProviderLanguageFileStore(work, "nyanlex-cache", LANG,
                    () -> provider, cfg.persistentCacheMaxEntries);
            PersistentStore aiStore = new LanguageFileStore(work, "nyanlex-ai-cache", LANG,
                    cfg.persistentCacheMaxEntries);
            PersistentStore failureStore = new LanguageFileStore(work, "nyanlex-failures", LANG,
                    cfg.persistentCacheMaxEntries);
            gt = new TranslationCache(gtFake, LANG, DIRECT, cfg.cacheMaxSize, cfg.failureBackoffMs,
                    System::currentTimeMillis, googleStore);
            ai = new TranslationCache(aiFake, LANG, DIRECT, cfg.cacheMaxSize, cfg.failureBackoffMs,
                    System::currentTimeMillis, aiStore);
            gt.setFailureStore(new DynamicNamespacedStore(failureStore, () -> "gt-" + provider));
            ai.setFailureStore(new NamespacedStore(failureStore, "ai"));
            ai.setProvisionalStore(googleStore);
            ai.setProvisionalRetryGate(() -> true);
            service = new TranslationService(cfg, gt, ai);
            service.setBatchWindowMs(() -> 0);
            service.setProtectedNames(() -> NAME_SET);
            guard = new ChurnGuard(cfg.churnVariantThreshold, cfg.churnWindowSeconds * 1000L,
                    cfg.churnCooldownSeconds * 1000L, System::currentTimeMillis);
            gt.setChurnGuard(guard);
            ai.setChurnGuard(guard);
        }
    }

    // ================================================================== scenario A
    /**
     * What the production rules should show for one input, read independently from the
     * copied journals (row validity uses the cache's own validators). A colour-marked line
     * whose only usable row is its own final projection (no plain semantic row on either
     * engine) is a 1.0.7 F5 hit on both render lookups and chat: the projection is shown at
     * once, zero requests, and a worker rebuilds the semantic row in session memory only.
     */
    private static final class Expect {
        String lookupTier = "MISS";   // HIT_AI HIT_GT KEEP_AI KEEP_GT MISS
        String chatTier = "MISS";     // HIT_AI HIT_GT KEEP_AI KEEP_GT MISS
        final List<String> lookupNorms = new ArrayList<>();
        final List<String> lookupRaw = new ArrayList<>();
        final List<TranslationTemplate.Snapshot> lookupSnaps = new ArrayList<>();
        final List<String> chatNorms = new ArrayList<>();
        final List<String> chatRaw = new ArrayList<>();
        final List<TranslationTemplate.Snapshot> chatSnaps = new ArrayList<>();
        /** AI exact colour projection when it AND a plain semantic row are valid. */
        String exactNorm;
        String exactRaw;
        /** The single row production serves (used to explain a legitimately hidden hit). */
        String lookupServedRaw;
        TranslationTemplate.Snapshot lookupServedSnap;
        String chatServedRaw;
        TranslationTemplate.Snapshot chatServedSnap;
        int invalidRows;
        /** F5: the winning engine's hit is its projection alone (no plain row anywhere). */
        boolean f5ProjectionOnly;
    }

    /** Valid rows one engine holds for one input. */
    private static final class EngineRows {
        boolean keep;
        boolean styleOk;
        boolean plainOk;
        String styleRaw;
        String plainRaw;
        TranslationTemplate.Snapshot styleSnap;
        TranslationTemplate.Snapshot plainSnap;
        int invalid;
    }

    private final Map<Replay, Boolean> coldMisses = new LinkedHashMap<>();
    private final List<Replay> styleOnly = new ArrayList<>();
    private final List<Replay> doubleFinal = new ArrayList<>();

    private boolean scenarioA(Rig rig, List<Replay> replays, Journal ai0, Journal gt0, Journal fail0,
                              Path aiFile, Path gtFile, Path failFile) throws IOException {
        rig.cfg.translationRequestsEnabled = false;
        TranslationService s = rig.service;
        long started = System.nanoTime();
        int maxPending = 0;
        AtomicInteger asyncCallbacks = new AtomicInteger();
        List<String> chunk = new ArrayList<>();
        for (Replay r : replays) {
            Expect e = expect(r, ai0, gt0);
            count("A.tier.lookup." + e.lookupTier);
            count("A.tier.chat." + e.chatTier);
            if (e.invalidRows > 0) {
                count("A.info.rowsInvalidUnderCurrentValidation", e.invalidRows);
                samples.add("A.info.row_invalid_under_current_validation", show(r.key));
            }
            TranslationDecision chatLookup = guarded("A.exception.translateChat", r,
                    () -> s.translateChat(r.input));
            judgeLookup("A.lookup.chat", chatLookup, r, e, false);
            TranslationDecision itemLookup = guarded("A.exception.translateItemLine", r,
                    () -> s.translateItemLine(r.input));
            // P1.7: translateItemLine (unlike translateChat) composes an isolated rarity/type
            // line locally from the built-in term table BEFORE any cache lookup, regardless of
            // the master switch or cache content (RarityLineTranslationTest). This harness's
            // config carries no user term overrides, so the same table this predicts is what
            // production actually resolves against.
            String rarityExpected = composeRarityExpectation(r.input);
            if (rarityExpected != null) {
                judgeRarityLookup("A.lookup.item", itemLookup, r, rarityExpected);
            } else {
                judgeLookup("A.lookup.item", itemLookup, r, e, true);
            }
            judgeChat(s, r, e);

            guardedRun("A.exception.requestActionBarAsync", r,
                    () -> s.requestActionBarAsync(r.input, t -> asyncCallbacks.incrementAndGet()));
            guardedRun("A.exception.requestLiveScreenTextAsync", r,
                    () -> s.requestLiveScreenTextAsync(r.input, t -> asyncCallbacks.incrementAndGet()));
            guardedRun("A.exception.requestScreenTextAsync", r,
                    () -> s.requestScreenTextAsync(r.input, t -> asyncCallbacks.incrementAndGet()));
            guardedRun("A.exception.requestChatAsync", r,
                    () -> s.requestChatAsync(r.input, t -> asyncCallbacks.incrementAndGet()));
            Boolean ready = guarded("A.exception.isTooltipTranslationReady", r,
                    () -> s.isTooltipTranslationReady(r.input));
            if (ready != null) count("A.tooltipReady." + ready + "." + e.lookupTier);
            if (e.f5ProjectionOnly && !Boolean.TRUE.equals(ready)) {
                // F5: a projection-only hit is a real, zero-request display; the tooltip
                // readiness surface should agree it is ready, same as any other hit tier.
                count("A.FAIL_tooltipReady_false_for_f5_projection_only");
                samples.add("A.FAIL_tooltipReady_false_for_f5_projection_only", show(r.input));
            }
            guardedRun("A.exception.warmUp", r, () -> s.warmUp(r.input));
            if (e.lookupTier.equals("MISS") && e.chatTier.equals("MISS")
                    && chatLookup != null && !chatLookup.changed()) {
                coldMisses.put(r, true);
            }
            if (e.f5ProjectionOnly) styleOnly.add(r);

            chunk.add(r.input);
            if (chunk.size() == CHUNK) {
                maxPending = Math.max(maxPending, batchEntriesSwitchedOff(s, chunk, asyncCallbacks));
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) maxPending = Math.max(maxPending, batchEntriesSwitchedOff(s, chunk, asyncCallbacks));
        for (int i = 0; i < 5; i++) s.flushBatches();
        stat("A", "seconds", seconds(started));
        stat("A", "asyncCallbacks", asyncCallbacks.get());
        stat("A", "coldMisses", coldMisses.size());

        boolean pass = true;
        int calls = rig.gtFake.calls.get() + rig.aiFake.calls.get();
        stat("A", "translatorCalls", calls);
        pass &= check("A.translatorCalls==0", calls == 0);
        stat("A", "pendingMax", maxPending);
        stat("A", "pendingEnd", s.pendingCount());
        pass &= check("A.pending==0", maxPending == 0 && s.pendingCount() == 0);
        stat("A", "churnSignatures", rig.guard.signatureCount());
        pass &= check("A.churnGuardNotFed", rig.guard.signatureCount() == 0);
        for (String table : SESSION_TABLES) {
            int gtSize = tracked(rig.gt, table);
            int aiSize = tracked(rig.ai, table);
            if (gtSize + aiSize > 0) stat("A", "sessionTable." + table, gtSize + aiSize);
            pass &= check("A.sessionTable." + table + "==0", gtSize + aiSize == 0);
        }
        for (String name : new ArrayList<>(counters.keySet())) {
            if (name.startsWith("A.") && name.contains(".FAIL")) pass &= check(name, false);
            if (name.startsWith("A.exception")) pass &= check(name, false);
        }

        Journal ai1 = Journal.read(aiFile);
        Journal gt1 = Journal.read(gtFile);
        Journal fail1 = Journal.read(failFile);
        boolean same = reportDiff("A.diff.aiCache", ai0, ai1)
                & reportDiff("A.diff.gtCache", gt0, gt1)
                & reportDiff("A.diff.failureLedger", fail0, fail1);
        pass &= check("A.filesSemanticallyUnchanged", same);
        stat("A", "verdict", pass ? "PASS" : "FAIL");
        return pass;
    }

    /** Surface batch entries that only ever queue work, then one client tick; returns pending. */
    private int batchEntriesSwitchedOff(TranslationService s, List<String> chunk, AtomicInteger callbacks) {
        List<String> copy = new ArrayList<>(chunk);
        guardedRun("A.exception.warmTooltipBatch", null, () -> s.warmTooltipBatch(copy));
        guardedRun("A.exception.warmScoreboardBatch", null, () -> s.warmScoreboardBatch(copy));
        guardedRun("A.exception.warmNamesBatch", null, () -> s.warmNamesBatch(copy));
        guardedRun("A.exception.warmBookBatch", null, () -> s.warmBookBatch(copy));
        AtomicInteger segmentsDone = new AtomicInteger();
        guardedRun("A.exception.translateChatSegmentsAsync", null,
                () -> s.translateChatSegmentsAsync(copy, all -> segmentsDone.incrementAndGet()));
        if (segmentsDone.get() != 1) count("A.chatSegments.FAIL_not_completed_synchronously");
        callbacks.addAndGet(segmentsDone.get());
        guardedRun("A.exception.retranslate", null, () -> s.retranslate(copy));
        guardedRun("A.exception.retranslateScreen", null, () -> s.retranslateScreen(copy));
        int pending = s.pendingCount();
        s.flushBatches();
        return Math.max(pending, s.pendingCount());
    }

    private Expect expect(Replay r, Journal ai, Journal gt) {
        boolean marked = CS_MARKER.matcher(r.key).find();
        TranslationTemplate.Snapshot plainSnap = marked ? TEMPLATES.prepare(stripCs(r.masked)) : r.snap;
        EngineRows a = rows(ai, r, marked, plainSnap);
        EngineRows g = rows(gt, r, marked, plainSnap);
        Expect e = new Expect();
        e.invalidRows = a.invalid + g.invalid;
        // Render lookups: AI first; GT only when AI has neither a plain semantic row nor a
        // final style projection of its own (switch off: mayUseFallback no longer needs an
        // AI failure). 1.0.7 F5: a marked line whose colour projection outlived its plain
        // semantic row (write-order eviction, or the plain copy never validated) is no
        // longer hidden — TranslationCache#getCached notes the missing semantic row for a
        // background worker rebuild and returns the projection immediately on every render
        // surface, with zero requests (ProjectionSemanticRebuildTest#projectionOnlyRowIsDisplayedWithoutRequest).
        // A style row is only good for this while it is not itself a provisional (GT
        // stand-in) row: getCached() discards a provisional projection on read instead of
        // showing it, so such a row is not a usable hit here either (EngineRows below
        // excludes it via the journal's own provisional flag).
        if (a.keep) {
            e.lookupTier = "KEEP_AI";
        } else if (a.plainOk || a.styleOk) {
            e.lookupTier = "HIT_AI";
            addRows(e.lookupNorms, e.lookupRaw, e.lookupSnaps, a, r);
            servedLookup(e, a);
            if (a.styleOk && !a.plainOk) e.f5ProjectionOnly = true;
        } else if (g.keep) {
            e.lookupTier = "KEEP_GT";
        } else if (g.plainOk || g.styleOk) {
            e.lookupTier = "HIT_GT";
            addRows(e.lookupNorms, e.lookupRaw, e.lookupSnaps, g, r);
            servedLookup(e, g);
            if (g.styleOk && !g.plainOk) e.f5ProjectionOnly = true;
        }
        // One-shot chat also accepts the exact colour projection on its own.
        if (a.keep) {
            e.chatTier = "KEEP_AI";
        } else if (a.plainOk || a.styleOk) {
            e.chatTier = "HIT_AI";
            addRows(e.chatNorms, e.chatRaw, e.chatSnaps, a, r);
            servedChat(e, a);
            if (a.styleOk && a.plainOk) {
                e.exactRaw = a.styleRaw;
                e.exactNorm = norm(render(a.styleRaw, a.styleSnap, r.names));
            }
        } else if (g.keep) {
            e.chatTier = "KEEP_GT";
        } else if (g.plainOk || g.styleOk) {
            e.chatTier = "HIT_GT";
            addRows(e.chatNorms, e.chatRaw, e.chatSnaps, g, r);
            servedChat(e, g);
        }
        return e;
    }

    /** getCached: the exact projection when there is no plain row at all (F5: the projection
     *  is shown while a worker rebuilds the semantic row) or when it states the same wording
     *  as the plain row (TranslationCache#sameSemanticText); otherwise the plain row (as style
     *  fallback, when the two disagree and a render lookup allows the style fallback). */
    private static void servedLookup(Expect e, EngineRows x) {
        boolean style = x.styleOk && (!x.plainOk
                || stripCs(x.styleSnap.restore(x.styleRaw)).equals(stripCs(x.plainSnap.restore(x.plainRaw))));
        e.lookupServedRaw = style ? x.styleRaw : x.plainRaw;
        e.lookupServedSnap = style ? x.styleSnap : x.plainSnap;
    }

    /** Exact-style chat: the projection when present, else the plain row as style fallback. */
    private static void servedChat(Expect e, EngineRows x) {
        e.chatServedRaw = x.styleOk ? x.styleRaw : x.plainRaw;
        e.chatServedSnap = x.styleOk ? x.styleSnap : x.plainSnap;
    }

    /** One engine's usable rows, judged with the cache's own validators (read() and
     *  lookupStyleProjection() delete what these reject). */
    private static EngineRows rows(Journal j, Replay r, boolean marked, TranslationTemplate.Snapshot plainSnap) {
        EngineRows out = new EngineRows();
        String k = r.key;
        String p = plainSnap.key();
        String vk = j.live.get(k);
        String vp = marked ? j.live.get(p) : vk;
        if (KEEP_ORIGINAL.equals(vp)) {
            out.keep = true;
            return out;
        }
        if (!marked) {
            if (vk != null && !vk.startsWith("\u0000")) {
                if (call(CACHE_USABLE2, k, vk)) {
                    out.plainOk = true;
                    out.plainRaw = vk;
                    out.plainSnap = r.snap;
                } else {
                    out.invalid++;
                }
            }
            return out;
        }
        if (vk != null && !vk.startsWith("\u0000")) {
            // F5 (TranslationCache#getCached): a style-projection row that is itself only a
            // provisional (GT stand-in) result is discarded on read rather than shown — only
            // a final projection qualifies for the zero-request display path.
            boolean provisionalStyle = j.provisional.contains(k);
            boolean ok = !provisionalStyle && call(CACHE_USABLE2, k, vk);
            if (ok) {
                String restored = r.snap.restore(vk);
                ok = call(CACHE_USABLE1, restored) && call(CACHE_CS_SHAPE, r.masked, restored);
            }
            if (ok) {
                out.styleOk = true;
                out.styleRaw = vk;
                out.styleSnap = r.snap;
            } else if (!provisionalStyle) {
                out.invalid++;
            }
        }
        if (vp != null && !vp.startsWith("\u0000")) {
            if (call(CACHE_USABLE2, p, vp)) {
                out.plainOk = true;
                out.plainRaw = vp;
                out.plainSnap = plainSnap;
            } else {
                out.invalid++;
            }
        } else {
            // TranslationCache#lookupSnapshot's raw-legacy migration fallback: when the
            // stable ⟦MTn⟧-templated plain key has no row, an older build may still hold
            // one exact row per literal plain-text variant, keyed by the un-templated
            // source text itself (read() validates and evicts it exactly like any other
            // row; a successful retokenize re-stores it under the templated key on a
            // later request, but this very lookup already returns the raw value as-is).
            String rawKey = plainSnap.source();
            String vRaw = j.live.get(rawKey);
            if (vRaw == null && !plainSnap.normalized().equals(rawKey)) {
                rawKey = plainSnap.normalized();
                vRaw = j.live.get(rawKey);
            }
            if (vRaw != null && !vRaw.startsWith("\u0000") && !KEEP_ORIGINAL.equals(vRaw)) {
                if (call(CACHE_USABLE2, rawKey, vRaw)) {
                    out.plainOk = true;
                    out.plainRaw = vRaw;
                    out.plainSnap = plainSnap;
                } else {
                    out.invalid++;
                }
            }
        }
        return out;
    }

    private static void addRows(List<String> norms, List<String> raw, List<TranslationTemplate.Snapshot> snaps,
                                EngineRows rows, Replay r) {
        if (rows.styleOk) {
            norms.add(norm(render(rows.styleRaw, rows.styleSnap, r.names)));
            raw.add(rows.styleRaw);
            snaps.add(rows.styleSnap);
        }
        if (rows.plainOk) {
            norms.add(norm(render(rows.plainRaw, rows.plainSnap, r.names)));
            raw.add(rows.plainRaw);
            snaps.add(rows.plainSnap);
        }
    }

    /** P1.7 term table used to predict {@link RarityLineComposer}'s local composition —
     *  same empty-override table {@link #userLikeConfig} implies (no {@code termOverrides}
     *  configured), so this always resolves exactly what production's own {@code
     *  new TermTable(config.termOverrides)} would. */
    private static final TermTable RARITY_TERMS = new TermTable(java.util.Map.of());

    /** What {@code TranslationService.lookup()} shows for an item-line surface (never
     *  chat/scoreboard/etc., which never pass {@code itemText=true}) before any cache
     *  lookup happens, when the whole line is a recognised isolated rarity/type line and
     *  every word it needs already has a built-in table entry. {@code null} when the line
     *  is not a rarity line, or when a type word is not in the table (production would
     *  fall through to the ordinary cache-based lookup and passively queue a one-off
     *  learning request for that word instead — out of scope for this corpus, where the
     *  fixed 55-word table already covers every case observed). */
    private static String composeRarityExpectation(String input) {
        RarityLineComposer.Match match = RarityLineComposer.match(input);
        if (match == null) return null;
        return match.compose(input, (kind, word) ->
                kind == RarityLineComposer.Kind.RARITY ? RARITY_TERMS.rarity(word) : RARITY_TERMS.type(word));
    }

    private void judgeRarityLookup(String scope, TranslationDecision d, Replay r, String expected) {
        if (d == null) return;
        if (d.changed() && norm(d.translated()).equals(norm(expected))) {
            count(scope + ".ok_rarityTable");
        } else {
            count(scope + ".FAIL_rarityTable_mismatch");
            samples.add(scope + ".FAIL_rarityTable_mismatch", show(r.input) + "\n    shown="
                    + (d.changed() ? show(d.translated()) : "(original)") + "\n    expectedTable=" + show(expected));
        }
    }

    private void judgeLookup(String scope, TranslationDecision d, Replay r, Expect e, boolean item) {
        if (d == null) return;
        boolean hit = e.lookupTier.startsWith("HIT");
        if (hit && d.changed()) {
            if (e.lookupNorms.contains(norm(d.translated()))) {
                count(scope + ".ok_hit");
            } else {
                count(scope + ".FAIL_text_not_from_cache");
                samples.add(scope + ".FAIL_text_not_from_cache", show(r.input) + "\n    shown=" + show(d.translated())
                        + "\n    cached=" + show(String.join(" || ", e.lookupRaw)));
            }
        } else if (hit) {
            String why = classifyNotShown(r, List.of(e.lookupServedRaw), List.of(e.lookupServedSnap), true);
            if (why.equals("UNEXPLAINED")) {
                count(scope + ".FAIL_cached_row_not_shown");
                samples.add(scope + ".FAIL_cached_row_not_shown", show(r.input)
                        + "\n    cached=" + show(String.join(" || ", e.lookupRaw)));
            } else {
                count(scope + ".explained_not_shown." + why);
                samples.add(scope + ".explained_not_shown." + why, show(r.input)
                        + "\n    cached=" + show(String.join(" || ", e.lookupRaw)));
            }
        } else if (d.changed()) {
            count(scope + ".FAIL_unexpected_translation." + e.lookupTier);
            samples.add(scope + ".FAIL_unexpected_translation", e.lookupTier + " " + show(r.input)
                    + "\n    shown=" + show(d.translated()));
        } else {
            count(scope + ".ok_" + e.lookupTier.toLowerCase());
        }
    }

    private void judgeChat(TranslationService s, Replay r, Expect e) {
        List<TranslationService.ChatTranslationResult> results = new ArrayList<>();
        if (!guardedRun("A.exception.translateChatAsyncDetailed", r,
                () -> s.translateChatAsyncDetailed(r.input, results::add))) return;
        if (results.isEmpty()) {
            count("A.chat.FAIL_not_completed_synchronously");
            samples.add("A.chat.FAIL_not_completed_synchronously", show(r.input));
            return;
        }
        String visible = glueVisible(results);
        int finals = 0;
        for (TranslationService.ChatTranslationResult result : results) {
            if (result.finalResult()) finals++;
        }
        if (results.size() > 1) count("A.chat.info.multipleCallbacks");
        if (finals > 1) {
            count("A.chat.FAIL_more_than_one_final_callback");
            samples.add("A.chat.FAIL_more_than_one_final_callback", show(r.input) + "\n    callbacks="
                    + results.size() + " finals=" + finals + " first=" + show(results.get(0).text())
                    + "\n    last=" + show(results.get(results.size() - 1).text()));
        }
        TranslationService.ChatTranslationResult last = results.get(results.size() - 1);
        if (!last.finalResult()) {
            count("A.chat.FAIL_not_final");
            samples.add("A.chat.FAIL_not_final", show(r.input));
        }
        boolean hit = e.chatTier.startsWith("HIT");
        if (hit && visible != null) {
            if (!e.chatNorms.contains(norm(visible))) {
                count("A.chat.FAIL_text_not_from_cache");
                samples.add("A.chat.FAIL_text_not_from_cache", show(r.input) + "\n    shown=" + show(visible)
                        + "\n    cached=" + show(String.join(" || ", e.chatRaw)));
            } else if (e.exactNorm != null && TextFilter.isStyleFallback(visible)) {
                // The exact colour projection is cached, yet the chat line keeps the
                // approximate-colour semantic fallback (switch ON shows the exact one).
                doubleFinal.add(r);
                count("A.chat.FAIL_exact_colour_projection_cached_but_not_shown");
                samples.add("A.chat.FAIL_exact_colour_projection_cached_but_not_shown", show(r.input)
                        + "\n    shown(fallback)=" + show(visible)
                        + "\n    exact(cached)=" + show(e.exactRaw));
            } else {
                count("A.chat.ok_hit");
            }
        } else if (hit) {
            String why = classifyNotShown(r, List.of(e.chatServedRaw), List.of(e.chatServedSnap), false);
            if (why.equals("UNEXPLAINED")) {
                count("A.chat.FAIL_cached_row_not_shown");
                samples.add("A.chat.FAIL_cached_row_not_shown", show(r.input)
                        + "\n    cached=" + show(String.join(" || ", e.chatRaw)));
            } else {
                count("A.chat.explained_not_shown." + why);
            }
        } else if (visible != null) {
            count("A.chat.FAIL_unexpected_translation." + e.chatTier);
            samples.add("A.chat.FAIL_unexpected_translation", e.chatTier + " " + show(r.input)
                    + "\n    shown=" + show(visible));
        } else {
            count("A.chat.ok_" + e.chatTier.toLowerCase());
        }
    }

    /**
     * The chat text the loader glue ends up showing (RecoveryAssembly.ResultProgress): the first
     * provisional and the first final result are accepted, anything after a final one is
     * ignored, and a null never erases an accepted value.
     */
    private static String glueVisible(List<TranslationService.ChatTranslationResult> results) {
        boolean provisionalSeen = false;
        boolean finalSeen = false;
        String visible = null;
        for (TranslationService.ChatTranslationResult result : results) {
            boolean accepted = result.finalResult() ? !finalSeen : !provisionalSeen && !finalSeen;
            if (result.finalResult()) finalSeen = true;
            else provisionalSeen = true;
            if (accepted && result.text() != null) visible = result.text();
        }
        return visible;
    }

    /** Why a surface legitimately shows the original although a valid cached row exists
     *  (production guards that apply with the switch on as well). {@code decide} = render
     *  lookup (TranslationService#decide runs); chat only checks meaningfulness. */
    private static String classifyNotShown(Replay r, List<String> raw, List<TranslationTemplate.Snapshot> snaps,
                                           boolean decide) {
        if (!TextFilter.shouldTranslate(r.input, LANG)) return "source_not_translatable";
        if (!r.names.isEmpty()
                && !TextFilter.shouldTranslate(TextFilter.stripTranslationMarkers(r.masked), LANG)) {
            return "masked_not_translatable";
        }
        if (!r.snap.hasTranslatableContent() && !CS_MARKER.matcher(r.key).find()) {
            return "cache_content_not_translatable";
        }
        for (int i = 0; i < raw.size(); i++) {
            String maskedSemantic = snaps.get(i).restore(raw.get(i));
            String semantic = NameMasker.unmask(maskedSemantic, r.names);
            if (!meaningful(r.input, semantic)) return "decide_not_meaningful";
            if (!decide) continue;
            if (TextFilter.isPartialTransliteration(r.masked, maskedSemantic)) return "decide_partial_transliteration";
            for (String name : r.names) {
                if (!containsWholeWord(semantic, name)) return "decide_R17_name_lost";
            }
        }
        return "UNEXPLAINED";
    }

    private static boolean meaningful(String source, String translated) {
        return translated != null && !translated.isEmpty() && !translated.equals(source)
                && !translated.trim().equals(source == null ? "" : source.trim());
    }

    // ================================================================== scenario A+ (informational)
    private void scenarioLegacyProbe(Rig rig, List<String> rawKeys, Path aiFile, Path gtFile, Path failFile)
            throws IOException {
        stat("A+", "rawLegacyKeys", rawKeys.size());
        if (rawKeys.isEmpty()) return;
        Journal ai0 = Journal.read(aiFile);
        Journal gt0 = Journal.read(gtFile);
        Journal fail0 = Journal.read(failFile);
        int before = rig.gtFake.calls.get() + rig.aiFake.calls.get();
        int shown = 0;
        for (String key : rawKeys) {
            TranslationDecision d = guarded("A+.exception.translateChat", null, () -> rig.service.translateChat(key));
            if (d != null && d.changed()) shown++;
            guarded("A+.exception.translateItemLine", null, () -> rig.service.translateItemLine(key));
            guardedRun("A+.exception.translateChatAsyncDetailed", null,
                    () -> rig.service.translateChatAsyncDetailed(key, ignored -> { }));
        }
        rig.service.flushBatches();
        stat("A+", "shownFromLegacyRows", shown);
        stat("A+", "translatorCalls", rig.gtFake.calls.get() + rig.aiFake.calls.get() - before);
        stat("A+", "pendingEnd", rig.service.pendingCount());
        reportDiff("A+.diff.aiCache", ai0, Journal.read(aiFile));
        reportDiff("A+.diff.gtCache", gt0, Journal.read(gtFile));
        reportDiff("A+.diff.failureLedger", fail0, Journal.read(failFile));
    }

    // ================================================================== scenario C
    private boolean scenarioC(Rig rig, List<Replay> replays, Journal fail0, Path aiFile) throws IOException {
        rig.cfg.translationRequestsEnabled = true;
        TranslationService s = rig.service;
        long started = System.nanoTime();
        List<Replay> cold = new ArrayList<>(coldMisses.keySet());
        List<Replay> requestable = new ArrayList<>();
        for (Replay r : cold) if (wouldRequest(r.input, r.masked, r.snap)) requestable.add(r);
        stat("C", "coldMisses", cold.size());
        stat("C", "requestable", requestable.size());
        int before = rig.aiFake.calls.get();
        for (int i = 0; i < cold.size(); i += CHUNK) {
            for (Replay r : cold.subList(i, Math.min(cold.size(), i + CHUNK))) {
                guarded("C.exception.translateChat", r, () -> s.translateChat(r.input));
            }
            pump(s);
        }
        pump(s);
        stat("C", "aiTranslatorCalls", rig.aiFake.calls.get() - before);
        stat("C", "gtTranslatorCalls", rig.gtFake.calls.get());
        int pendingAfterPump = s.pendingCount();
        stat("C", "pendingAfterPump", pendingAfterPump);
        long now = System.currentTimeMillis();
        Journal aiAfter = Journal.read(aiFile);
        int sentOk = 0;
        int shownOk = 0;
        for (Replay r : requestable) {
            boolean sent = rig.aiFake.sent.contains(r.key);
            if (sent) sentOk++;
            else {
                String why = notSentReason(rig, r, fail0, now);
                count("C.notSent." + why);
                samples.add("C.notSent." + why, show(r.input));
            }
            TranslationDecision d = guarded("C.exception.translateChat", r, () -> s.translateChat(r.input));
            if (d == null) continue;
            String exact = render(pseudo(r.key), r.snap, r.names);
            boolean fromPseudo = d.changed() && (norm(d.translated()).equals(norm(exact))
                    || norm(d.translated()).equals(norm(stripCs(exact))));
            if (fromPseudo) {
                shownOk++;
            } else if (sent) {
                // Same pre-existing render rule as in A: a marked line whose plain semantic
                // row could not be written is never shown by render lookups. A reply the
                // cache's own validator rejects (e.g. half-width kana read as mojibake) is a
                // temporary failure with the switch on as well.
                boolean marked = CS_MARKER.matcher(r.key).find();
                String plainKey = marked ? TEMPLATES.prepare(stripCs(r.masked)).key() : r.key;
                boolean styleOnlyNow = marked && aiAfter.live.containsKey(r.key)
                        && !aiAfter.live.containsKey(plainKey);
                boolean rejected = !call(CACHE_USABLE2, r.key, pseudo(r.key));
                String category = styleOnlyNow ? "C.explained_not_shown.style_only_after_translation"
                        : rejected ? "C.explained_not_shown.reply_rejected_by_cache_validation"
                        : "C.FAIL_sent_but_not_shown";
                count(category);
                samples.add(category, show(r.input) + "\n    shown="
                        + (d.changed() ? show(d.translated()) : "(original)"));
            }
        }
        pump(s);
        stat("C", "requestedAfterSwitchOn", sentOk);
        stat("C", "shownAfterSwitchOn", shownOk);
        stat("C", "pendingEnd", s.pendingCount());

        // F5 projection-only informational probe: inputs whose only valid row is an exact
        // colour projection (already shown by A with 0 requests, switch state does not
        // matter to F5). With the switch ON this checks the projection keeps being shown
        // (informational: F5's session-memory rebuild does not count as cached on the
        // request side, so it is worth confirming nothing re-requests the same key here).
        int probeBefore = rig.aiFake.calls.get() + rig.gtFake.calls.get();
        Set<String> sentBeforeProbe = new HashSet<>(rig.aiFake.sent);
        sentBeforeProbe.addAll(rig.gtFake.sent);
        int probeShown = 0;
        int probeSent = 0;
        for (int i = 0; i < styleOnly.size(); i += CHUNK) {
            for (Replay r : styleOnly.subList(i, Math.min(styleOnly.size(), i + CHUNK))) {
                guarded("C.exception.styleOnlyProbe", r, () -> s.translateChat(r.input));
            }
            pump(s);
        }
        for (Replay r : styleOnly) {
            if (rig.aiFake.sent.contains(r.key) || rig.gtFake.sent.contains(r.key)) probeSent++;
            TranslationDecision d = guarded("C.exception.styleOnlyProbe", r, () -> s.translateChat(r.input));
            if (d != null && d.changed()) {
                probeShown++;
                samples.add("C.info.styleOnlyProbe_shown_after_switch_on", show(r.input)
                        + "\n    shown=" + show(d.translated()));
            }
        }
        pump(s);
        for (String key : rig.aiFake.sent) {
            if (!sentBeforeProbe.contains(key)) samples.add("C.info.styleOnlyProbe_sent_key", show(key));
        }
        stat("C", "styleOnlyProbe.inputs", styleOnly.size());
        // all calls in the window, including due retries of earlier failures (not probe keys)
        stat("C", "styleOnlyProbe.allTranslatorCallsInWindow",
                rig.aiFake.calls.get() + rig.gtFake.calls.get() - probeBefore);
        stat("C", "styleOnlyProbe.requestedOwnKey", probeSent);
        stat("C", "styleOnlyProbe.shownByRenderLookup", probeShown);

        // Control for the chat defect found in A: the same inputs with the switch ON.
        int controlExact = 0;
        int controlFallback = 0;
        int controlCalls = rig.aiFake.calls.get() + rig.gtFake.calls.get();
        for (Replay r : doubleFinal) {
            List<TranslationService.ChatTranslationResult> results = new ArrayList<>();
            guardedRun("C.exception.chatControl", r, () -> s.translateChatAsyncDetailed(r.input, results::add));
            String visible = glueVisible(results);
            if (visible != null && !TextFilter.isStyleFallback(visible)) controlExact++;
            else controlFallback++;
        }
        pump(s);
        stat("C", "chatControl.inputs", doubleFinal.size());
        stat("C", "chatControl.exactShownWithSwitchOn", controlExact);
        stat("C", "chatControl.fallbackShownWithSwitchOn", controlFallback);
        stat("C", "chatControl.allTranslatorCallsInWindow",
                rig.aiFake.calls.get() + rig.gtFake.calls.get() - controlCalls);
        stat("C", "seconds", seconds(started));
        boolean pass = check("C.someRequestSent", requestable.isEmpty() || rig.aiFake.calls.get() > before);
        pass &= check("C.pendingAfterPump==0", pendingAfterPump == 0);
        pass &= check("C.pendingEnd==0", s.pendingCount() == 0);
        for (String name : new ArrayList<>(counters.keySet())) {
            if (name.startsWith("C.") && (name.contains("FAIL") || name.startsWith("C.exception")
                    || name.equals("C.notSent.other"))) {
                pass &= check(name, false);
            }
        }
        stat("C", "verdict", pass ? "PASS" : "FAIL");
        return pass;
    }

    private String notSentReason(Rig rig, Replay r, Journal fail0, long now) {
        String plain = CS_MARKER.matcher(r.key).find()
                ? TEMPLATES.prepare(stripCs(r.masked)).key() : r.key;
        String row = fail0.live.get("ai" + NAMESPACE_SEPARATOR + plain);
        if (row != null && (row.startsWith("temporary:") || row.startsWith("identity:"))) {
            // P1.1 appends a failure-reason suffix after the timestamp (format becomes
            // "temporary:<attempt>:<until>:<reason>"); split(":", 4) keeps reading the
            // "until" field out of parts[2] for both the old 3-part and new 4-part shape,
            // matching TranslationCache#restoreTemporaryFailure's own split(":", 3).
            String[] parts = row.split(":", 4);
            if (parts.length >= 3) {
                try {
                    long until = Long.parseLong(parts[2]);
                    if (until > now) return "ledger_backoff";
                } catch (NumberFormatException ignored) {
                    // malformed rows are deleted by production; fall through
                }
            }
        }
        if (churnCooling(rig.guard, r.key, now)) return "churn_guard";
        return "other";
    }

    // ================================================================== scenario B
    private boolean scenarioB(List<Replay> inputs) {
        long started = System.nanoTime();
        TranslatorConfig cfg = userLikeConfig("google");
        cfg.translationRequestsEnabled = true;
        cfg.doNotTranslateTerms = TranslatorConfig.normalizedTerms(new ArrayList<>(DNT_TERMS));
        TermScanner scanner = new TermScanner(DNT_TERMS);
        DoNotTranslateMatcher matcher = DoNotTranslateMatcher.compile(cfg.doNotTranslateTerms);
        FakeTranslator gtFake = new FakeTranslator();
        FakeTranslator aiFake = new FakeTranslator();
        Consumer<String> sentCheck = text -> {
            if (scanner.firstLeak(text) != null) {
                count("B.FAIL_term_sent_to_translator");
                samples.add("B.FAIL_term_sent_to_translator", show(text));
            }
        };
        Consumer<String> contextCheck = text -> {
            if (scanner.firstLeak(text) != null) {
                count("B.FAIL_term_sent_as_context");
                samples.add("B.FAIL_term_sent_as_context", show(text));
            }
        };
        gtFake.onText = aiFake.onText = sentCheck;
        gtFake.onContext = aiFake.onContext = contextCheck;
        PersistentStore gtStore = inlineStore(new HashMap<>());
        Map<String, String> aiRows = new HashMap<>();
        TranslationCache gt = new TranslationCache(gtFake, LANG, DIRECT, cfg.cacheMaxSize, cfg.failureBackoffMs,
                System::currentTimeMillis, gtStore);
        TranslationCache ai = new TranslationCache(aiFake, LANG, DIRECT, cfg.cacheMaxSize, cfg.failureBackoffMs,
                System::currentTimeMillis, inlineStore(aiRows));
        gt.setFailureStore(inlineStore(new HashMap<>()));
        ai.setFailureStore(inlineStore(new HashMap<>()));
        ai.setProvisionalStore(gtStore);
        ai.setProvisionalRetryGate(() -> true);
        TranslationService s = new TranslationService(cfg, gt, ai);
        s.setBatchWindowMs(() -> 0);
        s.setProtectedNames(() -> NAME_SET);
        gt.setChurnGuard(null);   // B exercises every input; churn cooling is orthogonal
        ai.setChurnGuard(null);

        int[][] perTerm = new int[DNT_TERMS.size()][4]; // inputs, masked, urlSkipped, crossColour
        List<Replay> bulk = new ArrayList<>();
        Map<Replay, List<Occ>> expectedOf = new HashMap<>();
        Map<Replay, NameMasker.Masked> maskedOf = new HashMap<>();
        List<Replay> allProtected = new ArrayList<>();
        long dntInputs = 0;
        long totalMasked = 0;
        long urlSkipped = 0;
        long crossColour = 0;
        long nonDntChecked = 0;
        for (Replay r : inputs) {
            List<Occ> occurrences = scanner.occurrences(r.input);
            List<Occ> expected = new ArrayList<>();
            boolean[] seen = new boolean[DNT_TERMS.size()];
            for (Occ o : occurrences) {
                if (!seen[o.term]) {
                    seen[o.term] = true;
                    perTerm[o.term][0]++;
                }
                if (o.url) {
                    urlSkipped++;
                    perTerm[o.term][2]++;
                } else {
                    expected.add(o);
                    perTerm[o.term][1]++;
                }
            }
            int[] colourSplit = scanner.crossColour(r.input);
            for (int t = 0; t < colourSplit.length; t++) {
                perTerm[t][3] += colourSplit[t];
                crossColour += colourSplit[t];
            }
            NameMasker.Masked m;
            NameMasker.Masked m0;
            try {
                m = NameMasker.mask(r.input, NAME_SET, matcher);
                m0 = NameMasker.mask(r.input, NAME_SET, DoNotTranslateMatcher.EMPTY);
            } catch (RuntimeException ex) {
                count("B.FAIL_exception.mask");
                samples.add("B.FAIL_exception", ex + " @ " + show(r.input));
                continue;
            }
            if (expected.isEmpty()) {
                nonDntChecked++;
                if (!m.text().equals(m0.text()) || !m.names().equals(m0.names())) {
                    count("B.FAIL_term_free_input_masked_differently");
                    samples.add("B.FAIL_term_free_input_masked_differently",
                            show(r.input) + "\n    masked=" + show(m.text()));
                }
                continue;
            }
            dntInputs++;
            totalMasked += expected.size();
            int placeholders = 0;
            Matcher tokens = NAME_TOKEN.matcher(m.text());
            while (tokens.find()) {
                int index = Integer.parseInt(tokens.group(1));
                if (index < m.names().size() && !NAME_SET.contains(m.names().get(index))) placeholders++;
            }
            if (placeholders != expected.size()) {
                count("B.FAIL_mask_count_mismatch");
                samples.add("B.FAIL_mask_count_mismatch", show(r.input) + "\n    masked=" + show(m.text())
                        + "\n    expected=" + expected.size() + " placeholders=" + placeholders);
            }
            if (scanner.firstLeak(m.text()) != null) {
                count("B.FAIL_term_left_in_masked_text");
                samples.add("B.FAIL_term_left_in_masked_text", show(r.input) + "\n    masked=" + show(m.text()));
            }
            for (Occ o : expected) {
                if (!m.names().contains(o.spelling)) {
                    count("B.FAIL_original_spelling_not_kept");
                    samples.add("B.FAIL_original_spelling_not_kept", show(r.input) + "\n    spelling=" + show(o.spelling));
                    break;
                }
            }
            expectedOf.put(r, expected);
            maskedOf.put(r, m);
            if (onlyProtected(r.input, expected)) allProtected.add(r);
            else bulk.add(r);
        }
        stat("B", "inputs", inputs.size());
        stat("B", "urlVariantInputs", inputs.stream().filter(r -> r.urlVariant).count());
        stat("B", "inputsWithTerms", dntInputs);
        termInputs = dntInputs;
        stat("B", "termFreeInputsChecked", nonDntChecked);
        stat("B", "occurrencesMasked", totalMasked);
        stat("B", "skipped.url", urlSkipped);
        stat("B", "skipped.crossColour", crossColour);
        int termsHit = 0;
        for (int t = 0; t < DNT_TERMS.size(); t++) {
            if (perTerm[t][0] > 0) termsHit++;
            stat("B", "term[" + DNT_TERMS.get(t) + "]", "inputs=" + perTerm[t][0] + " masked=" + perTerm[t][1]
                    + " urlSkipped=" + perTerm[t][2] + " crossColour=" + perTerm[t][3]);
        }
        stat("B", "termsHit", termsHit + "/" + DNT_TERMS.size());
        stat("B", "allProtectedInputs", allProtected.size());

        // ---- lines made only of protected terms: nothing may be sent, nothing changes ------
        for (Replay r : allProtected) {
            int before = gtFake.calls.get() + aiFake.calls.get();
            List<TranslationDecision> decisions = new ArrayList<>();
            List<String> chat = new ArrayList<>();
            decisions.add(guarded("B.FAIL_exception.translateChat", r, () -> s.translateChat(r.input)));
            decisions.add(guarded("B.FAIL_exception.translateItemLine", r, () -> s.translateItemLine(r.input)));
            decisions.add(guarded("B.FAIL_exception.translateScoreboardLine", r,
                    () -> s.translateScoreboardLine(r.input)));
            guarded("B.FAIL_exception.isTooltipTranslationReady", r, () -> s.isTooltipTranslationReady(r.input));
            guardedRun("B.FAIL_exception.translateChatAsync", r,
                    () -> s.translateChatAsync(r.input, chat::add));
            guardedRun("B.FAIL_exception.requestActionBarAsync", r,
                    () -> s.requestActionBarAsync(r.input, chat::add));
            guardedRun("B.FAIL_exception.requestLiveScreenTextAsync", r,
                    () -> s.requestLiveScreenTextAsync(r.input, chat::add));
            guardedRun("B.FAIL_exception.warmTooltipBatch", r, () -> s.warmTooltipBatch(List.of(r.input)));
            guardedRun("B.FAIL_exception.warmScoreboardBatch", r, () -> s.warmScoreboardBatch(List.of(r.input)));
            pump(s);
            int after = gtFake.calls.get() + aiFake.calls.get();
            if (after != before) {
                count("B.FAIL_all_term_line_sent");
                samples.add("B.FAIL_all_term_line_sent", show(r.input));
            }
            boolean changed = decisions.stream().anyMatch(d -> d != null && d.changed())
                    || chat.stream().anyMatch(t -> t != null);
            if (changed) {
                count("B.FAIL_all_term_line_changed");
                samples.add("B.FAIL_all_term_line_changed", show(r.input));
            }
        }

        // ---- every other input with a term: translate through the pseudo translator --------
        int translated = 0;
        for (int i = 0; i < bulk.size(); i += CHUNK) {
            List<Replay> part = bulk.subList(i, Math.min(bulk.size(), i + CHUNK));
            Map<Replay, List<TranslationService.ChatTranslationResult>> chatResults = new HashMap<>();
            for (Replay r : part) {
                List<TranslationService.ChatTranslationResult> results = new ArrayList<>();
                chatResults.put(r, results);
                guarded("B.FAIL_exception.translateChat", r, () -> s.translateChat(r.input));
                guarded("B.FAIL_exception.translateItemLine", r, () -> s.translateItemLine(r.input));
                guardedRun("B.FAIL_exception.translateChatAsyncDetailed", r,
                        () -> s.translateChatAsyncDetailed(r.input, results::add));
            }
            List<String> surface = new ArrayList<>();
            for (Replay r : part) surface.add(r.input);
            guardedRun("B.FAIL_exception.warmTooltipBatch", null, () -> s.warmTooltipBatch(surface));
            pump(s);
            for (Replay r : part) {
                List<Occ> expected = expectedOf.get(r);
                NameMasker.Masked m = maskedOf.get(r);
                boolean request = wouldRequest(r.input, m.text(), TEMPLATES.prepare(m.text()));
                TranslationDecision chatLookup = guarded("B.FAIL_exception.translateChat", r,
                        () -> s.translateChat(r.input));
                TranslationDecision itemLookup = guarded("B.FAIL_exception.translateItemLine", r,
                        () -> s.translateItemLine(r.input));
                List<TranslationService.ChatTranslationResult> results = chatResults.get(r);
                String chatText = glueVisible(results);
                if (!request) {
                    count("B.info.notTranslatableAfterMasking");
                    if ((chatLookup != null && chatLookup.changed()) || chatText != null) {
                        count("B.FAIL_untranslatable_line_changed");
                        samples.add("B.FAIL_untranslatable_line_changed", show(r.input));
                    }
                    continue;
                }
                translated++;
                String lookupReason = (chatLookup != null && chatLookup.changed())
                        && (itemLookup != null && itemLookup.changed()) ? null : bLookupNotShownReason(aiRows, m);
                judgeTermDisplay("B.lookup.chat", chatLookup == null ? null
                        : chatLookup.changed() ? chatLookup.translated() : null, r, expected, lookupReason);
                judgeTermDisplay("B.lookup.item", itemLookup == null ? null
                        : itemLookup.changed() ? itemLookup.translated() : null, r, expected, lookupReason);
                judgeTermDisplay("B.chat", chatText, r, expected, null);
            }
        }
        stat("B", "translatedInputsChecked", translated);
        stat("B", "translatorTexts", gtFake.calls.get() + aiFake.calls.get());
        stat("B", "translatorBatches", gtFake.batches.get() + aiFake.batches.get());
        stat("B", "contextLinesSeen", gtFake.contextLines + aiFake.contextLines);
        stat("B", "pendingEnd", s.pendingCount());
        stat("B", "seconds", seconds(started));
        boolean pass = check("B.pending==0", s.pendingCount() == 0);
        if (termsHit < 8) stat("B", "note", "fewer than 8 terms occur in this corpus (informational)");
        for (String name : new ArrayList<>(counters.keySet())) {
            if (name.startsWith("B.") && name.contains("FAIL")) pass &= check(name, false);
        }
        stat("B", "verdict", pass ? "PASS" : "FAIL");
        return pass;
    }

    /**
     * Every masked occurrence must be displayed in its original spelling: the multiset of
     * expected spellings must be contained in the display (longest first, each match consumed).
     * The pseudo translator turns every other ASCII letter into CJK, so a Latin spelling in
     * the display can only come from a restored placeholder.
     */
    private void judgeTermDisplay(String scope, String display, Replay r, List<Occ> expected,
                                  String notShownReason) {
        if (display == null) {
            if (notShownReason != null) {
                count(scope + ".explained_not_shown." + notShownReason);
                samples.add(scope + ".explained_not_shown." + notShownReason, show(r.input));
            } else {
                count(scope + ".FAIL_not_translated");
                samples.add(scope + ".FAIL_not_translated", show(r.input));
            }
            return;
        }
        String shown = display.startsWith(STYLE_FALLBACK_PREFIX)
                ? display.substring(STYLE_FALLBACK_PREFIX.length()) : display;
        List<String> spellings = new ArrayList<>();
        for (Occ o : expected) spellings.add(o.spelling);
        spellings.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder remaining = new StringBuilder(shown);
        for (String spelling : spellings) {
            int at = remaining.indexOf(spelling);
            if (at < 0) {
                count(scope + ".FAIL_term_not_restored");
                samples.add(scope + ".FAIL_term_not_restored", show(r.input) + "\n    shown=" + show(shown)
                        + "\n    missing=" + show(spelling));
                return;
            }
            for (int i = at; i < at + spelling.length(); i++) remaining.setCharAt(i, '\u0001');
        }
        for (String spelling : spellings) {
            if (countWholeWord(shown, spelling) == 0) {
                count(scope + ".info.termGluedToNeighbour");
                samples.add(scope + ".info.termGluedToNeighbour", show(shown));
                break;
            }
        }
        count(scope + ".ok");
    }

    /** Why a render lookup keeps the original after the pseudo translation was stored. */
    private static String bLookupNotShownReason(Map<String, String> aiRows, NameMasker.Masked m) {
        String key = TEMPLATES.prepare(m.text()).key();
        boolean marked = CS_MARKER.matcher(key).find();
        TranslationTemplate.Snapshot plain = TEMPLATES.prepare(marked ? stripCs(m.text()) : m.text());
        if (marked && aiRows.containsKey(key) && !aiRows.containsKey(plain.key())) {
            return "style_only_after_translation";
        }
        String stored = aiRows.get(plain.key());
        if (stored == null) return null;
        String maskedSemantic = plain.restore(stored);
        if (TextFilter.isPartialTransliteration(m.text(), maskedSemantic)) return "decide_partial_transliteration";
        String semantic = NameMasker.unmask(maskedSemantic, m.names());
        for (String original : m.names()) {
            if (!containsWholeWord(semantic, original)) return "decide_R17_term_glued";
        }
        return null;
    }

    /** Nothing but CS/PB markers, masked terms, synthetic player names, spaces and punctuation. */
    private static boolean onlyProtected(String input, List<Occ> expected) {
        boolean[] covered = new boolean[input.length()];
        for (Occ o : expected) Arrays.fill(covered, o.start, o.end, true);
        for (Pattern p : List.of(CS_MARKER, PB_TOKEN)) {
            Matcher m = p.matcher(input);
            while (m.find()) Arrays.fill(covered, m.start(), m.end(), true);
        }
        for (String name : NAME_SET) {
            int at = input.indexOf(name);
            while (at >= 0) {
                Arrays.fill(covered, at, at + name.length(), true);
                at = input.indexOf(name, at + 1);
            }
        }
        for (int i = 0; i < input.length(); i++) {
            if (!covered[i] && Character.isLetterOrDigit(input.charAt(i))) return false;
        }
        return true;
    }

    /** Whether the service/cache pipeline would buy a request for this input (no backoff/churn). */
    private static boolean wouldRequest(String input, String masked, TranslationTemplate.Snapshot snap) {
        if (!TextFilter.shouldTranslate(input, LANG)) return false;
        if (!masked.equals(input)
                && !TextFilter.shouldTranslate(TextFilter.stripTranslationMarkers(masked), LANG)) return false;
        return snap.hasTranslatableContent();
    }

    // ================================================================== scenario P
    private void timing(List<Replay> inputs) {
        DoNotTranslateMatcher matcher = DoNotTranslateMatcher.compile(
                TranslatorConfig.normalizedTerms(new ArrayList<>(DNT_TERMS)));
        List<String> texts = new ArrayList<>(inputs.size());
        for (Replay r : inputs) texts.add(r.input);
        for (int pass = 0; pass < 2; pass++) {
            for (String t : texts) {
                matcher.candidates(t);
                NameMasker.mask(t, NAME_SET, matcher);
            }
        }
        timeOne("P.candidates", texts, t -> matcher.candidates(t));
        timeOne("P.maskNamesAndTerms", texts, t -> NameMasker.mask(t, NAME_SET, matcher));
    }

    private void timeOne(String scope, List<String> texts, Consumer<String> op) {
        long[] nanos = new long[texts.size()];
        long total = 0;
        for (int i = 0; i < texts.size(); i++) {
            long t0 = System.nanoTime();
            op.accept(texts.get(i));
            nanos[i] = System.nanoTime() - t0;
            total += nanos[i];
        }
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int slowFirst = 0;
        int slowRetimed = 0;
        long slowLimit = (long) (SLOW_MS * 1_000_000L);
        for (int i = 0; i < texts.size(); i++) {
            if (nanos[i] <= slowLimit) continue;
            slowFirst++;
            long best = Long.MAX_VALUE;
            for (int k = 0; k < 5; k++) {
                long t0 = System.nanoTime();
                op.accept(texts.get(i));
                best = Math.min(best, System.nanoTime() - t0);
            }
            if (best > slowLimit) slowRetimed++;
            samples.add(scope + ".slow_over_1ms", String.format("first=%.3fms best-of-5=%.3fms len=%d %s",
                    nanos[i] / 1e6, best / 1e6, texts.get(i).length(), show(texts.get(i))));
        }
        stat(scope, "inputs", texts.size());
        stat(scope, "totalMs", String.format("%.1f", total / 1e6));
        stat(scope, "avgUs", String.format("%.2f", texts.isEmpty() ? 0 : total / 1e3 / texts.size()));
        stat(scope, "p99Us", String.format("%.2f", sorted.length == 0 ? 0 : sorted[(int) (sorted.length * 0.99)] / 1e3));
        stat(scope, "maxUs", String.format("%.2f", sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e3));
        stat(scope, "over1msFirstRun", slowFirst);
        stat(scope, "over1msBestOf5", slowRetimed);
    }

    // ================================================================== term scanner (independent)
    static final class Occ {
        final int start;
        final int end;
        final int term;
        final String spelling;
        final boolean url;

        Occ(int start, int end, int term, String spelling, boolean url) {
            this.start = start;
            this.end = end;
            this.term = term;
            this.spelling = spelling;
            this.url = url;
        }
    }

    /** Reference matcher written from the spec (case-insensitive whole words, 1+ horizontal
     *  spaces inside multi-word terms, longest match wins, never inside ⟦…⟧, URL fragments
     *  reported separately) — deliberately not DoNotTranslateMatcher's own code. */
    static final class TermScanner {
        private final List<Pattern> patterns = new ArrayList<>();

        TermScanner(List<String> terms) {
            for (String term : terms) {
                String[] words = term.strip().split("\\s+");
                StringBuilder re = new StringBuilder("(?<![A-Za-z0-9_])");
                for (int i = 0; i < words.length; i++) {
                    if (i > 0) re.append("[\\t\\p{Zs}]+");
                    re.append(Pattern.quote(words[i]));
                }
                re.append("(?![A-Za-z0-9_])");
                patterns.add(Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
        }

        List<Occ> occurrences(String text) {
            String neutral = neutralizeTokens(text);
            List<int[]> urls = new ArrayList<>();
            Matcher u = TemplateText.URL.matcher(text);
            while (u.find()) urls.add(new int[] {u.start(), u.end()});
            List<Occ> urlHits = new ArrayList<>();
            List<Occ> found = new ArrayList<>();
            for (int t = 0; t < patterns.size(); t++) {
                Matcher m = patterns.get(t).matcher(neutral);
                while (m.find()) {
                    boolean inUrl = false;
                    for (int[] url : urls) {
                        if (m.start() < url[1] && url[0] < m.end()) inUrl = true;
                    }
                    Occ occ = new Occ(m.start(), m.end(), t, text.substring(m.start(), m.end()), inUrl);
                    (inUrl ? urlHits : found).add(occ);
                }
            }
            List<Occ> chosen = longestFirst(found);
            chosen.addAll(longestFirst(urlHits));
            return chosen;
        }

        /** Non-URL occurrences that only exist once CS colour markers are removed. */
        int[] crossColour(String text) {
            int[] out = new int[patterns.size()];
            if (!CS_MARKER.matcher(text).find()) return out;
            int[] marked = new int[patterns.size()];
            for (Occ o : occurrences(text)) if (!o.url) marked[o.term]++;
            for (Occ o : occurrences(CS_MARKER.matcher(text).replaceAll(""))) if (!o.url) out[o.term]++;
            for (int t = 0; t < out.length; t++) out[t] = Math.max(0, out[t] - marked[t]);
            return out;
        }

        String firstLeak(String text) {
            if (text == null) return null;
            for (Occ o : occurrences(text)) if (!o.url) return o.spelling;
            return null;
        }

        private static List<Occ> longestFirst(List<Occ> found) {
            List<Occ> byLength = new ArrayList<>(found);
            byLength.sort((a, b) -> a.end - a.start != b.end - b.start
                    ? Integer.compare(b.end - b.start, a.end - a.start) : Integer.compare(a.start, b.start));
            List<Occ> chosen = new ArrayList<>();
            for (Occ o : byLength) {
                boolean overlaps = false;
                for (Occ kept : chosen) {
                    if (o.start < kept.end && kept.start < o.end) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) chosen.add(o);
            }
            chosen.sort((a, b) -> Integer.compare(a.start, b.start));
            return chosen;
        }

        /** Protocol tokens and literal {@code §x} codes become non-word filler of the same
         *  length: nothing matches inside them and a code counts as a word boundary (spec §4). */
        private static String neutralizeTokens(String text) {
            StringBuilder out = new StringBuilder(text);
            Matcher m = ANY_TOKEN.matcher(text);
            while (m.find()) {
                for (int i = m.start(); i < m.end(); i++) out.setCharAt(i, '\u0001');
            }
            for (int i = 0; i < out.length(); i++) {
                if (out.charAt(i) != '§') continue;
                out.setCharAt(i, '\u0002');
                if (i + 1 < out.length() && out.charAt(i + 1) != '\u0001') out.setCharAt(++i, '\u0002');
            }
            return out.toString();
        }
    }

    private static int countWholeWord(String text, String word) {
        int count = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            int end = at + word.length();
            boolean left = at == 0 || !asciiWord(text.charAt(at - 1));
            boolean right = end == text.length() || !asciiWord(text.charAt(end));
            if (left && right) count++;
        }
        return count;
    }

    private static boolean containsWholeWord(String text, String word) {
        return text != null && countWholeWord(text, word) > 0;
    }

    private static boolean asciiWord(char c) {
        return c == '_' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }

    // ================================================================== fakes
    /** Inline translator: records what it receives and answers with {@link #pseudo}. */
    static final class FakeTranslator implements Translator {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger batches = new AtomicInteger();
        final Set<String> sent = new HashSet<>();
        int contextLines;
        Consumer<String> onText = text -> { };
        Consumer<String> onContext = text -> { };

        @Override
        public TranslationResult translate(String text, String targetLang) {
            calls.incrementAndGet();
            sent.add(text);
            onText.accept(text);
            return new TranslationResult(pseudo(text), "en");
        }

        @Override
        public List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                      List<String> surfaceContext) {
            batches.incrementAndGet();
            if (surfaceContext != null) {
                for (String line : surfaceContext) {
                    contextLines++;
                    onContext.accept(line);
                }
            }
            List<TranslationResult> out = new ArrayList<>(texts.size());
            for (String text : texts) out.add(translate(text, targetLang));
            return out;
        }
    }

    /**
     * Verifiable pseudo translation: every {@code ⟦…⟧} token is kept; ASCII letters become CJK
     * ideographs; inside each segment between structural tokens (CS/PB/WS) the movable tokens
     * ({@code ⟦n⟧}, plus {@code ⟦MTn⟧} when the row has no WS column) are reversed to imitate
     * Chinese word order. A text without ASCII letters gets a visible 譯 inside its first colour run.
     */
    static String pseudo(String text) {
        if (text == null) return null;
        List<String> parts = new ArrayList<>();
        List<Boolean> token = new ArrayList<>();
        Matcher m = ANY_TOKEN.matcher(text);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) {
                parts.add(text.substring(pos, m.start()));
                token.add(false);
            }
            parts.add(m.group());
            token.add(true);
            pos = m.end();
        }
        if (pos < text.length()) {
            parts.add(text.substring(pos));
            token.add(false);
        }
        boolean hasWs = WS_ANY.matcher(text).find();
        int segmentStart = 0;
        for (int i = 0; i <= parts.size(); i++) {
            boolean boundary = i == parts.size() || token.get(i) && structural(parts.get(i));
            if (!boundary) continue;
            List<Integer> movable = new ArrayList<>();
            for (int j = segmentStart; j < i; j++) {
                if (!token.get(j) || !freeStanding(parts, token, j)) continue;
                String t = parts.get(j);
                if (NAME_LOOSE.matcher(t).matches() || !hasWs && MT_LOOSE.matcher(t).matches()) movable.add(j);
            }
            for (int a = 0, b = movable.size() - 1; a < b; a++, b--) {
                String swap = parts.get(movable.get(a));
                parts.set(movable.get(a), parts.get(movable.get(b)));
                parts.set(movable.get(b), swap);
            }
            segmentStart = i + 1;
        }
        boolean changed = false;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (token.get(i)) {
                out.append(part);
                continue;
            }
            for (int k = 0; k < part.length(); k++) {
                char c = part.charAt(k);
                if (c >= 'a' && c <= 'z') {
                    out.append(CJK_LOWER.charAt(c - 'a'));
                    changed = true;
                } else if (c >= 'A' && c <= 'Z') {
                    out.append(CJK_UPPER.charAt(c - 'A'));
                    changed = true;
                } else {
                    out.append(c);
                }
            }
        }
        String result = out.toString();
        if (!changed) {
            Matcher cs = CS_OPEN.matcher(result);
            result = cs.find() ? result.substring(0, cs.end()) + "譯" + result.substring(cs.end()) : "譯" + result;
        }
        return result;
    }

    private static boolean structural(String token) {
        return CS_MARKER.matcher(token).matches() || PB_TOKEN.matcher(token).matches()
                || WS_ANY.matcher(token).matches();
    }

    /** A token whose neighbours are neither another value/name token nor a digit: moving it
     *  can never glue a restored name or term onto a number (which R17 would then reject). */
    private static boolean freeStanding(List<String> parts, List<Boolean> token, int j) {
        if (j > 0) {
            String prev = parts.get(j - 1);
            if (token.get(j - 1) ? !structural(prev) : Character.isDigit(prev.charAt(prev.length() - 1))) {
                return false;
            }
        }
        if (j + 1 < parts.size()) {
            String next = parts.get(j + 1);
            return token.get(j + 1) ? structural(next) : !Character.isDigit(next.charAt(0));
        }
        return true;
    }

    static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
            @Override public Map<String, String> entries() { return new LinkedHashMap<>(backing); }
        };
    }

    // ================================================================== journals & diffs
    /** Independent schema-2/3/4 journal reader (last write wins, tombstones delete). */
    static final class Journal {
        final Map<String, String> live = new LinkedHashMap<>();
        final Set<String> provisional = new HashSet<>();
        int schema = -1;
        int badLines;

        static Journal read(Path file) throws IOException {
            Journal j = new Journal();
            if (file == null || !Files.isRegularFile(file)) return j;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String header = reader.readLine();
                if (header == null || header.isBlank()) return j;
                JsonObject h;
                try {
                    h = JsonParser.parseString(header).getAsJsonObject();
                    j.schema = h.has("schema") ? h.get("schema").getAsInt() : -1;
                } catch (RuntimeException unreadable) {
                    j.badLines++; // counted only; the file content is never echoed
                    return j;
                }
                if (j.schema == 2 && h.has("entries") && h.get("entries").isJsonArray()) {
                    for (JsonElement e : h.getAsJsonArray("entries")) j.apply(e);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        j.apply(JsonParser.parseString(line));
                    } catch (RuntimeException bad) {
                        j.badLines++;
                    }
                }
            }
            Iterator<String> eldest = j.live.keySet().iterator();
            while (j.live.size() > FileStore.DEFAULT_MAX_ENTRIES && eldest.hasNext()) {
                j.provisional.remove(eldest.next());
                eldest.remove();
            }
            return j;
        }

        private void apply(JsonElement element) {
            if (!element.isJsonObject()) {
                badLines++;
                return;
            }
            JsonObject op = element.getAsJsonObject();
            if (!op.has("key") || op.get("key").isJsonNull()) {
                badLines++;
                return;
            }
            String key = op.get("key").getAsString();
            if (op.has("deleted") && op.get("deleted").getAsBoolean()) {
                live.remove(key);
                provisional.remove(key);
                return;
            }
            if (!op.has("translation") || op.get("translation").isJsonNull()) {
                badLines++;
                return;
            }
            live.remove(key);
            live.put(key, op.get("translation").getAsString());
            if (op.has("provisional") && op.get("provisional").getAsBoolean()) provisional.add(key);
            else provisional.remove(key);
        }
    }

    /** Semantic comparison (live key → value and provisional flags); byte layout is ignored. */
    private boolean reportDiff(String scope, Journal before, Journal after) {
        int added = 0;
        int removed = 0;
        int changed = 0;
        int flipped = 0;
        for (String key : after.live.keySet()) if (!before.live.containsKey(key)) added++;
        Set<String> gone = new LinkedHashSet<>();
        for (String key : before.live.keySet()) if (!after.live.containsKey(key)) gone.add(key);
        // FileStore#evictOverflow drops the OLDEST rows in write order once a write exceeds the
        // cap: removals that are exactly the first N rows while as many rows were added.
        boolean capacityEviction = !gone.isEmpty() && added >= gone.size();
        if (capacityEviction) {
            Iterator<String> oldest = before.live.keySet().iterator();
            for (int i = 0; i < gone.size() && capacityEviction; i++) {
                capacityEviction = gone.contains(oldest.next());
            }
        }
        for (Map.Entry<String, String> row : before.live.entrySet()) {
            String now = after.live.get(row.getKey());
            if (now == null) {
                removed++;
                String why = capacityEviction ? "capacity_eviction_of_oldest_rows"
                        : removalReason(row.getKey(), row.getValue());
                count(scope + ".removed." + why);
                samples.add(scope + ".removed." + why, show(row.getKey()) + " => " + show(row.getValue()));
            } else if (!now.equals(row.getValue())) {
                changed++;
                samples.add(scope + ".changed", show(row.getKey()) + "\n    before=" + show(row.getValue())
                        + "\n    after=" + show(now));
            }
            if (now != null && before.provisional.contains(row.getKey()) != after.provisional.contains(row.getKey())) {
                flipped++;
            }
        }
        for (Map.Entry<String, String> row : after.live.entrySet()) {
            if (!before.live.containsKey(row.getKey())) {
                samples.add(scope + ".added", show(row.getKey()) + " => " + show(row.getValue()));
            }
        }
        stat(scope, "liveBefore", before.live.size());
        stat(scope, "liveAfter", after.live.size());
        stat(scope, "added", added);
        stat(scope, "removed", removed);
        stat(scope, "changed", changed);
        stat(scope, "provisionalFlipped", flipped);
        return added + removed + changed + flipped == 0;
    }

    private static String removalReason(String key, String value) {
        if (value.equals(LEGACY_KEEP_ORIGINAL)) return "legacy_keep_sentinel";
        if (value.equals(KEEP_ORIGINAL) && CS_MARKER.matcher(key).find()) return "legacy_topology_keep";
        if (value.startsWith("\u0000") || key.contains(NAMESPACE_SEPARATOR)) return "ledger_or_sentinel";
        if (!call(CACHE_USABLE2, key, value)) return "row_invalid_under_current_validation";
        return "other";
    }

    // ================================================================== helpers
    private static String render(String value, TranslationTemplate.Snapshot snap, List<String> names) {
        List<String> values = snap.base().values();
        List<Integer> slots = snap.base().slotIndices();
        Map<Integer, String> bySlot = new HashMap<>();
        for (int i = 0; i < values.size(); i++) bySlot.put(slots.get(i), values.get(i));
        String out = replaceIndexed(MT_LOOSE, value, idx -> bySlot.get(idx));
        out = WS_ANY.matcher(out).replaceAll(" ");
        return replaceIndexed(NAME_LOOSE, out, idx -> idx < names.size() ? names.get(idx) : null);
    }

    private interface IndexLookup {
        String get(int index);
    }

    private static String replaceIndexed(Pattern pattern, String text, IndexLookup lookup) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = lookup.get(Integer.parseInt(m.group(1)));
            m.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? m.group() : replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Whitespace-insensitive comparison form (restore/layout/CJK-spacing only move whitespace). */
    static String norm(String s) {
        if (s == null) return null;
        if (s.startsWith(STYLE_FALLBACK_PREFIX)) s = s.substring(STYLE_FALLBACK_PREFIX.length());
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) continue;
            b.appendCodePoint(cp);
        }
        return FW_DOT.matcher(FW_COMMA.matcher(b).replaceAll(",")).replaceAll(".");
    }

    /** Same projection as TranslationCache#stripStyle: colour markers, literal §x codes, strip. */
    private static String stripCs(String text) {
        return TextFilter.stripSectionCodes(CS_MARKER.matcher(text).replaceAll("")).strip();
    }

    private static int countChar(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) n++;
        return n;
    }

    private static void pump(TranslationService s) {
        for (int i = 0; i < 200_000 && s.pendingCount() > 0; i++) s.flushBatches();
        s.flushBatches();
        s.flushBatches();
    }

    private <T> T guarded(String category, Replay r, Supplier<T> call) {
        try {
            return call.get();
        } catch (Throwable t) {
            count(category);
            samples.add(category, t + (r == null ? "" : " @ " + show(r.input)));
            return null;
        }
    }

    private boolean guardedRun(String category, Replay r, Runnable call) {
        try {
            call.run();
            return true;
        } catch (Throwable t) {
            count(category);
            samples.add(category, t + (r == null ? "" : " @ " + show(r.input)));
            return false;
        }
    }

    private static boolean churnCooling(ChurnGuard guard, String key, long now) {
        try {
            Field field = ChurnGuard.class.getDeclaredField("bySignature");
            field.setAccessible(true);
            Object entry = ((Map<?, ?>) field.get(guard)).get(ChurnGuard.signatureOf(key));
            if (entry == null) return false;
            Field until = entry.getClass().getDeclaredField("cooldownUntil");
            until.setAccessible(true);
            return until.getLong(entry) > now;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static int tracked(TranslationCache cache, String field) {
        try {
            Field declared = TranslationCache.class.getDeclaredField(field);
            declared.setAccessible(true);
            Object value = declared.get(cache);
            if (value instanceof Map<?, ?> map) return map.size();
            if (value instanceof Collection<?> collection) return collection.size();
            throw new IllegalStateException(field + " is not a map or collection");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method privateStatic(Class<?> owner, String name, Class<?>... types) {
        try {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean call(Method method, Object... args) {
        try {
            return (Boolean) method.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean check(String name, boolean ok) {
        if (!ok) System.out.println("CHECK_FAILED " + name);
        return ok;
    }

    private void count(String name) {
        counters.merge(name, 1L, Long::sum);
    }

    private void count(String name, long n) {
        counters.merge(name, n, Long::sum);
    }

    private static void stat(String scope, String name, Object value) {
        System.out.println(scope + "." + name + "=" + value);
    }

    private static String ratio(long part, long whole) {
        return whole == 0 ? "n/a" : String.format("%.4f", (double) part / whole);
    }

    private static String seconds(long startedNanos) {
        return String.format("%.1f", (System.nanoTime() - startedNanos) / 1e9);
    }

    /** Printable form for the samples file (control characters escaped). */
    private static String show(String text) {
        if (text == null) return "null";
        StringBuilder b = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) b.append(String.format("\\u%04X", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** Failure samples (player text): at most {@link #SAMPLE_CAP} per category. */
    static final class Samples implements AutoCloseable {
        private final Path file;
        private final Map<String, List<String>> byCategory = new TreeMap<>();
        private final Map<String, Integer> totals = new TreeMap<>();

        Samples(Path file) {
            this.file = file;
        }

        void add(String category, String detail) {
            totals.merge(category, 1, Integer::sum);
            List<String> list = byCategory.computeIfAbsent(category, ignored -> new ArrayList<>());
            if (list.size() < SAMPLE_CAP) list.add(detail);
        }

        @Override
        public void close() throws IOException {
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, List<String>> category : byCategory.entrySet()) {
                    out.write("### " + category.getKey() + " (total " + totals.get(category.getKey())
                            + ", showing " + category.getValue().size() + ")");
                    out.newLine();
                    for (String line : category.getValue()) {
                        out.write("  - " + line);
                        out.newLine();
                    }
                }
            }
        }
    }
}
