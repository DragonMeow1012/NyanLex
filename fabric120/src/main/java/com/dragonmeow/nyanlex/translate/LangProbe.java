package com.dragonmeow.nyanlex.translate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * MEASUREMENT-ONLY probe (never changes a translation result or request): how much of the
 * text each surface shows is built from real lang-key ({@code TranslatableContents})
 * nodes, vs pure literals, and for the keys that do appear, whether the game's own
 * target-language lang table already carries a translation.
 *
 * <p>Hard rule: only translatable nodes that EXIST in the observed Component tree are
 * recorded -- keys are never derived from item ids / descriptionIds (Hypixel items are
 * vanilla ids with literal names).</p>
 *
 * <p>Zero cost while {@code enabled} reports false: {@link #observe} returns before
 * touching the tree (glue builds the {@link Node} only after {@link #isEnabled()}) and
 * no thread is ever started. When enabled, a single daemon thread resolves the lang
 * tables and writes the JSON report at most every {@code intervalMs} when something
 * changed. Args are recorded by TYPE only, never by value (no player names).</p>
 */
public final class LangProbe {
    public static final int MAX_KEYS = 300;
    public static final int MAX_KEY_LENGTH = 160;
    public static final int MAX_LINE_HASHES_PER_SURFACE = 5000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Gate-free description of one Component node, built by glue from the real tree. */
    public static final class Node {
        /** Lang key, or {@code null} for a literal/other node. */
        public final String key;
        /** Arg kinds for a translatable node: string, number, boolean, null, other, component. */
        public final List<String> argTypes = new ArrayList<>();
        /** Siblings AND component-valued args. */
        public final List<Node> children = new ArrayList<>();
        /** Literal text carried by this node itself (non-empty). */
        public final boolean hasOwnText;
        /** Number of siblings of this node (used for the root check). */
        public int siblingCount;

        public Node(String key, boolean hasOwnText) {
            this.key = key;
            this.hasOwnText = hasOwnText;
        }
    }

    /** Resolves whether a lang table can supply a key. {@code null} = could not be determined. */
    public interface LangLookup {
        Boolean targetHas(String key);

        Boolean englishHas(String key);

        /** Free-form facts about how the lookup was obtained (language code, load errors...). */
        default Map<String, Object> info() {
            return Map.of();
        }
    }

    public enum Shape { PURE_TRANSLATABLE_ROOT, MIXED, LITERAL }

    private static final class SurfaceStats {
        long observations;
        final long[] observationsByShape = new long[3];
        final long[] distinctLinesByShape = new long[3];
        final Set<Integer> seen = new HashSet<>();
        long hashOverflow;
    }

    private static final class KeyStats {
        long count;
        final Set<String> surfaces = new TreeSet<>();
        List<String> argTypes = List.of();
        Boolean targetHas;
        Boolean englishHas;
        boolean resolved;
        boolean inPureRoot;
    }

    private final BooleanSupplier enabled;
    private final Path outputFile;
    private final long intervalMs;
    private final Object lock = new Object();
    private final Map<String, SurfaceStats> surfaces = new TreeMap<>();
    private final Map<String, KeyStats> keys = new LinkedHashMap<>();
    private final Map<String, Long> argTypeCounts = new TreeMap<>();
    private long keyOverflow;
    private long nonKeyLiterals;
    private long nodesTotal;
    private boolean dirty;
    private ScheduledExecutorService scheduler;
    private volatile LangLookup lookup;

    public LangProbe(Path outputFile, BooleanSupplier enabled, long intervalMs) {
        this.outputFile = outputFile;
        this.enabled = enabled == null ? () -> false : enabled;
        this.intervalMs = Math.max(1L, intervalMs);
    }

    public void setLookup(LangLookup lookup) {
        this.lookup = lookup;
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /**
     * Record one line. {@code textHash} identifies the rendered line (hash only is kept,
     * never the text) so a per-frame re-render counts as ONE distinct line.
     */
    public void observe(String surface, int textHash, Node root) {
        if (root == null || surface == null || !enabled.getAsBoolean()) return;
        Shape shape = classify(root);
        List<Node> translatable = new ArrayList<>();
        collectTranslatable(root, translatable);
        synchronized (lock) {
            SurfaceStats s = surfaces.computeIfAbsent(surface, k -> new SurfaceStats());
            s.observations++;
            s.observationsByShape[shape.ordinal()]++;
            boolean fresh;
            if (s.seen.size() >= MAX_LINE_HASHES_PER_SURFACE) {
                s.hashOverflow++;
                fresh = false;
            } else {
                fresh = s.seen.add(textHash);
            }
            if (fresh) {
                s.distinctLinesByShape[shape.ordinal()]++;
                dirty = true;
                for (Node n : translatable) {
                    recordKey(surface, n, n == root && shape == Shape.PURE_TRANSLATABLE_ROOT);
                }
            }
        }
        ensureScheduler();
    }

    /** Stand-in recorded for translate "keys" that are not shaped like a lang key (privacy). */
    public static final String NON_KEY = "<non-key>";

    /** A real lang key: lowercase letters, digits, underscore, dot, dash (and bounded length). */
    static boolean looksLikeLangKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    private void recordKey(String surface, Node n, boolean pureRoot) {
        nodesTotal++;
        for (String t : n.argTypes) argTypeCounts.merge(t, 1L, Long::sum);
        String key = n.key;
        if (!looksLikeLangKey(key)) {
            // Servers/plugins sometimes put literal text (player names, chat) in "translate":
            // never keep it, only count it.
            nonKeyLiterals++;
            key = NON_KEY;
        }
        KeyStats ks = keys.get(key);
        if (ks == null) {
            if (keys.size() >= MAX_KEYS) {
                keyOverflow++;
                return;
            }
            ks = new KeyStats();
            ks.argTypes = NON_KEY.equals(key) ? List.of() : List.copyOf(n.argTypes);
            if (NON_KEY.equals(key)) ks.resolved = true; // nothing to look up
            keys.put(key, ks);
        }
        ks.count++;
        ks.surfaces.add(surface);
        if (pureRoot) ks.inPureRoot = true;
    }

    /** Shape of a whole line from its root node. */
    public static Shape classify(Node root) {
        if (root.key != null && root.siblingCount == 0 && !root.hasOwnText) return Shape.PURE_TRANSLATABLE_ROOT;
        List<Node> found = new ArrayList<>();
        collectTranslatable(root, found);
        return found.isEmpty() ? Shape.LITERAL : Shape.MIXED;
    }

    private static void collectTranslatable(Node n, List<Node> out) {
        if (n.key != null) out.add(n);
        for (Node c : n.children) collectTranslatable(c, out);
    }

    private synchronized void ensureScheduler() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nyanlex-lang-probe");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flushQuietly, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void flushQuietly() {
        try {
            if (enabled.getAsBoolean()) flush();
        } catch (RuntimeException | IOException ignored) {
            // debug tooling must never break anything
        }
    }

    /** Resolve pending key lookups and write the report if something changed. */
    public void flush() throws IOException {
        List<String> toResolve = new ArrayList<>();
        synchronized (lock) {
            if (!dirty) return;
            for (Map.Entry<String, KeyStats> e : keys.entrySet()) {
                if (!e.getValue().resolved) toResolve.add(e.getKey());
            }
        }
        LangLookup l = lookup;
        // info() may trigger a heavy resource load: never call it while holding the lock that
        // observe() (render thread) contends on.
        Map<String, Object> lookupInfo = l == null ? null : safeInfo(l);
        Map<String, Boolean[]> resolved = new LinkedHashMap<>();
        if (l != null) {
            for (String k : toResolve) {
                Boolean t = null;
                Boolean en = null;
                try { t = l.targetHas(k); } catch (RuntimeException ignored) { }
                try { en = l.englishHas(k); } catch (RuntimeException ignored) { }
                resolved.put(k, new Boolean[] {t, en});
            }
        }
        Map<String, Object> report;
        synchronized (lock) {
            for (Map.Entry<String, Boolean[]> e : resolved.entrySet()) {
                KeyStats ks = keys.get(e.getKey());
                if (ks == null) continue;
                ks.targetHas = e.getValue()[0];
                ks.englishHas = e.getValue()[1];
                ks.resolved = ks.targetHas != null;
            }
            report = buildReport(lookupInfo);
            dirty = false;
        }
        write(report);
    }

    /** Package-visible for tests: builds the JSON-shaped report map. */
    Map<String, Object> buildReport() {
        LangLookup l = lookup;
        Map<String, Object> info = l == null ? null : safeInfo(l);
        synchronized (lock) {
            return buildReport(info);
        }
    }

    private static Map<String, Object> safeInfo(LangLookup l) {
        try {
            return l.info();
        } catch (RuntimeException e) {
            return Map.of("infoError", String.valueOf(e.getClass().getSimpleName()));
        }
    }

    private Map<String, Object> buildReport(Map<String, Object> lookupInfo) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("note", "Measurement only. Args recorded by type, never value. Keys come only from "
                + "TranslatableContents nodes present in observed Component trees.");
        if (lookupInfo != null) root.put("lookup", lookupInfo);
        Map<String, Object> surf = new LinkedHashMap<>();
        for (Map.Entry<String, SurfaceStats> e : surfaces.entrySet()) {
            SurfaceStats s = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("observations", s.observations);
            m.put("distinctLines",
                    s.distinctLinesByShape[0] + s.distinctLinesByShape[1] + s.distinctLinesByShape[2]);
            m.put("pureTranslatableRoot", s.distinctLinesByShape[Shape.PURE_TRANSLATABLE_ROOT.ordinal()]);
            m.put("mixedWithTranslatableChild", s.distinctLinesByShape[Shape.MIXED.ordinal()]);
            m.put("pureLiteral", s.distinctLinesByShape[Shape.LITERAL.ordinal()]);
            m.put("observationsPureTranslatableRoot", s.observationsByShape[0]);
            m.put("observationsMixed", s.observationsByShape[1]);
            m.put("observationsPureLiteral", s.observationsByShape[2]);
            if (s.hashOverflow > 0) m.put("lineHashOverflow", s.hashOverflow);
            surf.put(e.getKey(), m);
        }
        root.put("surfaces", surf);
        long targetYes = 0;
        long targetNo = 0;
        long targetUnknown = 0;
        List<Object> keyList = new ArrayList<>();
        for (Map.Entry<String, KeyStats> e : keys.entrySet()) {
            KeyStats ks = e.getValue();
            if (ks.targetHas == null) targetUnknown++;
            else if (ks.targetHas) targetYes++;
            else targetNo++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("count", ks.count);
            m.put("surfaces", ks.surfaces);
            m.put("argTypes", ks.argTypes);
            m.put("inPureTranslatableRoot", ks.inPureRoot);
            m.put("inTargetLang", ks.targetHas);
            m.put("inEnglishLang", ks.englishHas);
            keyList.add(m);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("distinctKeysRecorded", keys.size());
        summary.put("keysOverCap", keyOverflow);
        summary.put("nonKeyLiterals", nonKeyLiterals);
        summary.put("translatableNodesRecorded", nodesTotal);
        summary.put("keysInTargetLang", targetYes);
        summary.put("keysMissingFromTargetLang", targetNo);
        summary.put("keysTargetUnknown", targetUnknown);
        summary.put("argTypeCounts", new TreeMap<>(argTypeCounts));
        root.put("summary", summary);
        root.put("keys", keyList);
        return root;
    }

    private void write(Map<String, Object> report) throws IOException {
        Path dir = outputFile.getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(report), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
