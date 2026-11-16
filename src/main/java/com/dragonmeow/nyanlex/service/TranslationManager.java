package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.ManualTranslations;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.translate.ManualTextCodec;
import com.dragonmeow.nyanlex.translate.TextFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The translation manager screen's model over the translation caches and the manual store (see
 * {@link TranslationManagerModel} for the contract).
 *
 * <p>All scanning (reading every stored row, merging the engines, searching) runs on this class's own
 * single daemon thread; the client thread only reads the published {@link Page} and does in-memory work.
 * A newer request replaces an older one: every request takes a token, and a scan that is no longer
 * wanted stops at its next check (a search over the list checks every {@value #CHECK_EVERY} rows).</p>
 *
 * <p>What is listed: one line per sentence. Its translation comes from the manual rows first, then the
 * AI cache, then the machine cache (the order the live lookup uses). Hub rows are not listed (the
 * repository stores hashes, not text).</p>
 */
public final class TranslationManager implements TranslationManagerModel {
    private static final int CHECK_EVERY = 1024;

    private final TranslationService service;
    private final TranslationCache ai;
    /** The machine-translation cache; the same object as {@link #ai} when there is only one cache. */
    private final TranslationCache machine;
    private final ManualTranslations manual;
    private final Executor executor;
    private final ExecutorService ownExecutor;

    private final Object pageLock = new Object();
    private volatile Page page = Page.LOADING;
    private volatile String query = "";
    private volatile boolean manualOnly;
    /** The newest request; an older one stops (or does not publish) when it sees another value. */
    private final AtomicLong token = new AtomicLong();
    /** Counts this model's own changes and external ones; a snapshot of another value is stale. */
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong refreshSeq = new AtomicLong();
    private volatile Snapshot snapshot;

    /** Everything one scan read: the rows in display order and the colour rows of the caches. */
    private record Snapshot(long version, long refreshSeq, Row[] rows, int manualCount,
                            Map<String, List<String>> aiColors, Map<String, List<String>> machineColors) {
    }

    TranslationManager(TranslationService service, TranslationCache ai, TranslationCache machine,
                       ManualTranslations manual) {
        this(service, ai, machine, manual, newExecutor(), true);
    }

    /** @param executor runs the background work; tests pass a direct one */
    TranslationManager(TranslationService service, TranslationCache ai, TranslationCache machine,
                       ManualTranslations manual, Executor executor) {
        this(service, ai, machine, manual, executor, false);
    }

    private TranslationManager(TranslationService service, TranslationCache ai, TranslationCache machine,
                               ManualTranslations manual, Executor executor, boolean owned) {
        this.service = service;
        this.ai = ai != null ? ai : machine;
        this.machine = machine != null ? machine : ai;
        this.manual = manual;
        this.executor = executor;
        this.ownExecutor = owned ? (ExecutorService) executor : null;
    }

    private static ExecutorService newExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "nyanlex-manager");
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Stops the background thread (the service replaced this model). */
    void close() {
        token.incrementAndGet();
        if (ownExecutor != null) ownExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ the list

    @Override
    public Page page() {
        return page;
    }

    @Override
    public void searchAsync(String query, boolean manualOnly) {
        this.query = query == null ? "" : query.trim();
        this.manualOnly = manualOnly;
        submit(token.incrementAndGet());
    }

    @Override
    public void refreshAsync() {
        refreshSeq.incrementAndGet();
        submit(token.incrementAndGet());
    }

    /** The cached rows changed under this model (cleared, language or provider switched): read again on next use. */
    void sourcesChanged() {
        synchronized (pageLock) {
            token.incrementAndGet();
            version.incrementAndGet();
            snapshot = null;
            page = Page.LOADING;
        }
    }

    private void submit(long mine) {
        try {
            executor.execute(() -> run(mine));
        } catch (RejectedExecutionException ignored) {
            // The service replaced this model; nobody reads it any more.
        }
    }

    private void run(long mine) {
        if (token.get() != mine) return;
        try {
            Snapshot current = snapshot;
            if (current == null || current.version() != version.get()
                    || current.refreshSeq() != refreshSeq.get()) {
                markLoading(mine);
                current = read();
                snapshot = current;
                if (token.get() != mine) return; // replaced meanwhile: the newer request publishes
            }
            search(current, mine);
        } catch (RuntimeException failed) {
            // A damaged cache row must never take the screen down: show what there is, no spinner.
            synchronized (pageLock) {
                Page old = page;
                if (token.get() == mine && old.loading()) {
                    page = new Page(old.rows(), old.matched(), old.totalSaved(), old.manualCount(),
                            old.truncated(), false, old.query(), old.manualOnly());
                }
            }
        }
    }

    private void markLoading(long mine) {
        synchronized (pageLock) {
            Page old = page;
            if (token.get() != mine || old.loading()) return;
            page = new Page(old.rows(), old.matched(), old.totalSaved(), old.manualCount(),
                    old.truncated(), true, old.query(), old.manualOnly());
        }
    }

    /** One pass over the manual rows and both caches into the display order: manual, AI, machine; newest first. */
    private Snapshot read() {
        long builtVersion = version.get();
        long builtRefresh = refreshSeq.get();
        TranslationCache.Browse aiRows = ai.browse();
        TranslationCache.Browse machineRows = machine == ai ? null : machine.browse();
        Map<String, String> manualRows = manual.entries();

        List<Row> ordered = new ArrayList<>(manualRows.size() + aiRows.rows().size()
                + (machineRows == null ? 0 : machineRows.rows().size()));
        Set<String> seen = new HashSet<>(Math.max(16, ordered.size() * 2));
        Set<String> manualColoured = new HashSet<>();
        List<String> manualKeys = new ArrayList<>();
        for (String key : manualRows.keySet()) {
            String semantic = TranslationCache.semanticOfColorKey(key);
            if (semantic == null) manualKeys.add(key);
            else manualColoured.add(semantic);
        }
        Map<String, List<String>> aiColors = aiRows.colorKeys();
        Map<String, List<String>> machineColors = machineRows == null ? Map.of() : machineRows.colorKeys();

        for (int i = manualKeys.size() - 1; i >= 0; i--) {
            String key = manualKeys.get(i);
            if (!seen.add(key)) continue;
            boolean colours = manualColoured.contains(key) || aiColors.containsKey(key)
                    || machineColors.containsKey(key);
            ordered.add(new Row(key, manualRows.get(key), Source.MANUAL, colours));
        }
        int manualCount = ordered.size();
        addNewestFirst(ordered, seen, aiRows.rows(), Source.AI, manualColoured, aiColors, machineColors);
        if (machineRows != null) {
            addNewestFirst(ordered, seen, machineRows.rows(), Source.MACHINE, manualColoured, aiColors,
                    machineColors);
        }
        return new Snapshot(builtVersion, builtRefresh, ordered.toArray(new Row[0]), manualCount,
                aiColors, machineColors);
    }

    private static void addNewestFirst(List<Row> out, Set<String> seen, Map<String, String> rows,
                                       Source source, Set<String> manualColoured,
                                       Map<String, List<String>> aiColors,
                                       Map<String, List<String>> machineColors) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(rows.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<String, String> entry = entries.get(i);
            String key = entry.getKey();
            if (!seen.add(key)) continue;
            boolean colours = manualColoured.contains(key) || aiColors.containsKey(key)
                    || machineColors.containsKey(key);
            out.add(new Row(key, entry.getValue(), source, colours));
        }
    }

    private void search(Snapshot current, long mine) {
        String text = query;
        boolean onlyManual = manualOnly;
        String[] terms = ManualTextCodec.searchTerms(text);
        Row[] rows = current.rows();
        int end = onlyManual ? current.manualCount() : rows.length; // manual rows come first
        List<Row> shown = new ArrayList<>(Math.min(end, MAX_PAGE_ROWS));
        int matched = 0;
        for (int i = 0; i < end; i++) {
            if ((i & (CHECK_EVERY - 1)) == 0 && token.get() != mine) return;
            Row row = rows[i];
            if (!ManualTextCodec.matchesAll(row.key(), row.translation(), terms)) continue;
            matched++;
            if (shown.size() < MAX_PAGE_ROWS) shown.add(row);
        }
        synchronized (pageLock) {
            if (token.get() != mine) return;
            page = new Page(shown, matched, rows.length, current.manualCount(), matched > shown.size(),
                    false, text, onlyManual);
        }
    }

    // ------------------------------------------------------------------ one sentence

    @Override
    public EditData editData(String key) {
        if (key == null) return null;
        String manualText = manual.get(key);
        String aiText = ai.rawRow(key);
        String machineText = machine == ai ? null : machine.rawRow(key);
        Source source;
        String translation;
        if (manualText != null) {
            source = Source.MANUAL;
            translation = manualText;
        } else if (aiText != null) {
            source = Source.AI;
            translation = aiText;
        } else if (machineText != null) {
            source = Source.MACHINE;
            translation = machineText;
        } else {
            return null;
        }
        String automatic = null;
        Source automaticSource = null;
        if (source == Source.MANUAL) {
            if (aiText != null) {
                automatic = aiText;
                automaticSource = Source.AI;
            } else if (machineText != null) {
                automatic = machineText;
                automaticSource = Source.MACHINE;
            }
        }
        return new EditData(key, key, translation, source, automatic, automaticSource, colorVersions(key));
    }

    /** The colour rows of a sentence with the wording each shows: manual first, else the newest automatic one. */
    private List<ColorVersion> colorVersions(String key) {
        Set<String> keys = new LinkedHashSet<>(manual.colorKeysOf(key));
        Snapshot current = snapshot;
        if (current != null) {
            keys.addAll(current.aiColors().getOrDefault(key, List.of()));
            keys.addAll(current.machineColors().getOrDefault(key, List.of()));
        }
        List<ColorVersion> out = new ArrayList<>(keys.size());
        for (String colorKey : keys) {
            String text = manual.get(colorKey);
            if (text == null) text = ai.rawRow(colorKey);
            if (text == null && machine != ai) text = machine.rawRow(colorKey);
            if (text != null) out.add(new ColorVersion(colorKey, text));
        }
        return out;
    }

    /** The key the manual rows of this sentence are stored under. */
    private String manualKey(String key) {
        return manual.contains(key) ? key : ai.semanticKey(key);
    }

    @Override
    public SaveOutcome save(String key, String translation, String colorKey, String colorTranslation) {
        if (key == null) return SaveOutcome.of(SaveResult.EMPTY);
        String original = ai.semanticKey(key);
        boolean coloured = colorKey != null && colorTranslation != null;
        // The plain row is always derived from the colour version when there is one, so the two
        // can never disagree about the wording.
        String text = (coloured ? ManualTextCodec.stripColorTags(colorTranslation) : translation);
        text = text == null ? "" : text.strip();
        String colourText = coloured ? colorTranslation.strip() : null;

        SaveOutcome refused = validate(original, text, coloured ? colorKey : null, colourText);
        if (refused != null) return refused;

        Map<String, String> rows = new LinkedHashMap<>();
        rows.put(original, text);
        if (coloured) rows.put(colorKey, colourText);
        boolean wasManual = manual.contains(original);
        ManualTranslations.Result written = manual.putAll(rows);
        if (written == ManualTranslations.Result.FULL) {
            return SaveOutcome.number(SaveResult.FULL, ManualTranslations.MAX_ROWS);
        }
        if (written != ManualTranslations.Result.OK) return SaveOutcome.of(SaveResult.IO_ERROR);

        long mine = supersede();
        Row old = pageRow(key);
        patchPage(key, new Row(key, text, Source.MANUAL, coloured || old != null && old.hasColorVersions()),
                wasManual ? 0 : 1, 0);
        service.onManualChanged();
        ai.notifyManualChanged(original);
        if (machine != ai) machine.notifyManualChanged(original);
        submit(mine);
        return SaveOutcome.OK;
    }

    /** The checks of {@link TranslationManagerModel#save}, in the documented order; null when the text may be stored. */
    private static SaveOutcome validate(String original, String text, String colorKey, String colourText) {
        if (text.isEmpty()) return SaveOutcome.of(SaveResult.EMPTY);
        if (text.equals(original.strip())
                || colourText != null && colourText.equals(colorKey.strip())) {
            return SaveOutcome.of(SaveResult.SAME_AS_SOURCE);
        }
        if (hasInternalCharacter(text) || colourText != null && hasInternalCharacter(colourText)) {
            return SaveOutcome.of(SaveResult.CONTROL_CHARS);
        }
        if (text.length() > MAX_TRANSLATION_LENGTH
                || colourText != null && colourText.length() > MAX_TRANSLATION_LENGTH) {
            return SaveOutcome.number(SaveResult.TOO_LONG, MAX_TRANSLATION_LENGTH);
        }
        SaveOutcome tags = tagDifference(original, text);
        if (tags == null && colourText != null) tags = tagDifference(colorKey, colourText);
        if (tags != null) return tags;
        if (colourText != null) {
            String linked = TranslationCache.semanticOfColorKey(colorKey);
            if (linked == null || !linked.equals(original)
                    || !ManualTextCodec.unpairedColorTags(colourText).isEmpty()
                    || !TranslationCache.manualTagShapeMatches(colorKey, colourText)) {
                return SaveOutcome.of(SaveResult.TAG_ORDER);
            }
        }
        if (!ManualTextCodec.breakOrderMatches(original, text)
                || !TranslationCache.manualTagShapeMatches(original, text)
                || nameGluedToWord(text) || colourText != null && nameGluedToWord(colourText)) {
            return SaveOutcome.of(SaveResult.TAG_ORDER);
        }
        int newlines = ManualTextCodec.newlineCount(original);
        if (ManualTextCodec.newlineCount(text) != newlines) {
            return SaveOutcome.number(SaveResult.NEWLINES, newlines);
        }
        if (TextFilter.isPartialTransliteration(original, text)
                || colourText != null && TextFilter.isPartialTransliteration(colorKey, colourText)) {
            return SaveOutcome.of(SaveResult.PARTIAL);
        }
        return null;
    }

    /**
     * A name or term marker directly against a letter, a digit or another name or value marker. The
     * display check ({@code TranslationService#decide}) wants every protected name to stand as a whole
     * word in the finished text, and a name or value is made of such characters ("Steve5" is not a
     * "Steve"), so a text like that would be stored and then never shown.
     */
    private static boolean nameGluedToWord(String text) {
        List<ManualTextCodec.Span> spans = ManualTextCodec.rawSpans(text);
        for (ManualTextCodec.Span span : spans) {
            if (span.tag().kind() != ManualTextCodec.Kind.NAME) continue;
            if (span.start() > 0 && wordLike(text, span.start() - 1, spans, true)) return true;
            if (span.end() < text.length() && wordLike(text, span.end(), spans, false)) return true;
        }
        return false;
    }

    /** Whether the character at {@code at} belongs to a word: a Latin letter, digit or underscore, or the edge of a name or value marker. */
    private static boolean wordLike(String text, int at, List<ManualTextCodec.Span> spans, boolean before) {
        char c = text.charAt(at);
        if (c == '_' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9') return true;
        for (ManualTextCodec.Span other : spans) {
            boolean touches = before ? other.end() == at + 1 : other.start() == at;
            ManualTextCodec.Kind kind = other.tag().kind();
            if (touches && (kind == ManualTextCodec.Kind.NAME || kind == ManualTextCodec.Kind.VALUE)) return true;
        }
        return false;
    }

    /** NUL and the low control characters the caches use internally, and the section sign (a style code). */
    private static boolean hasInternalCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= '\u0003' || c == '§') return true;
        }
        return false;
    }

    private static SaveOutcome tagDifference(String original, String translated) {
        ManualTextCodec.Diff diff = ManualTextCodec.compare(original, translated);
        if (!diff.missing().isEmpty()) {
            return SaveOutcome.tags(SaveResult.MISSING_TAGS, diff.missingDisplay());
        }
        if (!diff.extra().isEmpty()) {
            return SaveOutcome.tags(SaveResult.EXTRA_TAGS, diff.extraDisplay());
        }
        return null;
    }

    @Override
    public boolean revertToAutomatic(String key) {
        if (key == null) return true;
        String semantic = manualKey(key);
        boolean wasManual = manual.contains(semantic);
        List<String> rows = new ArrayList<>(manual.colorKeysOf(semantic));
        rows.add(semantic);
        if (!manual.removeAll(rows)) return false;

        long mine = supersede();
        Row old = pageRow(key);
        boolean colours = old != null && old.hasColorVersions();
        String aiText = ai.rawRow(key);
        String machineText = machine == ai ? null : machine.rawRow(key);
        Row automatic = aiText != null ? new Row(key, aiText, Source.AI, colours)
                : machineText != null ? new Row(key, machineText, Source.MACHINE, colours) : null;
        patchPage(key, automatic, wasManual ? -1 : 0, automatic == null ? -1 : 0);
        service.onManualChanged();
        submit(mine);
        return true;
    }

    @Override
    public boolean deleteAndRetranslate(String key) {
        if (key == null) return ai.requestsAllowed();
        String semantic = manualKey(key);
        boolean wasManual = manual.contains(semantic);
        Set<String> known = new LinkedHashSet<>(manual.colorKeysOf(semantic));
        Snapshot current = snapshot;
        if (current != null) {
            known.addAll(current.aiColors().getOrDefault(semantic, List.of()));
            known.addAll(current.machineColors().getOrDefault(semantic, List.of()));
        }
        // The colour rows the last scan knew are removed together with the sentence; the manual
        // rows go with them. Nothing is requested.
        service.discardTranslation(semantic, known);

        long mine = supersede();
        patchPage(key, null, wasManual ? -1 : 0, -1);
        submit(mine);
        // A colour row written since the last scan is found by a scan of the stores, off this thread.
        runLater(() -> removeLateColorRows(semantic, known));
        return ai.requestsAllowed();
    }

    private void removeLateColorRows(String semantic, Set<String> known) {
        List<String> late = new ArrayList<>();
        for (String colorKey : ai.colorProjectionKeys(semantic)) {
            if (!known.contains(colorKey)) late.add(colorKey);
        }
        List<String> lateMachine = new ArrayList<>();
        if (machine != ai) {
            for (String colorKey : machine.colorProjectionKeys(semantic)) {
                if (!known.contains(colorKey)) lateMachine.add(colorKey);
            }
        }
        if (late.isEmpty() && lateMachine.isEmpty()) return;
        if (!late.isEmpty()) ai.invalidateFamily(semantic, late);
        if (!lateMachine.isEmpty()) machine.invalidateFamily(semantic, lateMachine);
        service.onManualChanged();
    }

    private void runLater(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Replaced model: the next scan of the stores starts clean anyway.
        }
    }

    // ------------------------------------------------------------------ the page after a change

    /** A change of the manual rows or the stores: the snapshot is stale and any running scan is obsolete. */
    private long supersede() {
        synchronized (pageLock) {
            version.incrementAndGet();
            return token.incrementAndGet();
        }
    }

    private Row pageRow(String key) {
        for (Row row : page.rows()) {
            if (row.key().equals(key)) return row;
        }
        return null;
    }

    /**
     * Updates the published page at once after a change, so the list is right before the background
     * scan finishes: the row is replaced (or removed when {@code replacement} is null), and the
     * counts are adjusted. A row that is not on the page stays off it.
     */
    private void patchPage(String key, Row replacement, int manualDelta, int totalDelta) {
        synchronized (pageLock) {
            Page old = page;
            List<Row> rows = new ArrayList<>(old.rows());
            int at = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).key().equals(key)) {
                    at = i;
                    break;
                }
            }
            int matched = old.matched();
            if (at >= 0) {
                rows.remove(at);
                matched--;
                if (replacement != null && (!old.manualOnly() || replacement.source() == Source.MANUAL)) {
                    int to = 0;
                    if (replacement.source() != Source.MANUAL) {
                        while (to < rows.size() && rows.get(to).source() == Source.MANUAL) to++;
                    }
                    rows.add(to, replacement);
                    matched++;
                }
            }
            matched = Math.max(matched, rows.size());
            page = new Page(rows, matched, Math.max(0, old.totalSaved() + totalDelta),
                    Math.max(0, old.manualCount() + manualDelta), matched > rows.size(), old.loading(),
                    old.query(), old.manualOnly());
        }
    }
}
