package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.hub.HubFile;
import com.dragonmeow.nyanlex.hub.HubImportValidator;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.hub.HubPaths;
import com.dragonmeow.nyanlex.hub.HubSource;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.DoNotTranslateMatcher;
import com.dragonmeow.nyanlex.translate.NameMasker;
import com.dragonmeow.nyanlex.translate.TextFilter;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.TranslationTemplate;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Author-only CLI: converts hand-made (English, translated) language-file pairs into
 * translation-hub repository files, with the key of every row computed by the SAME code
 * the running mod uses to look a displayed line up in the hub.
 *
 * <p>A hub row is {@code sha256(key) -> translation}, where {@code key} is exactly the
 * string {@code TranslationService} hands to its hub lookup for the line the player sees:
 * the displayed text (format arguments substituted, {@code §} codes resolved into style
 * runs, multi-run lines wrapped in {@code ⟦CS#⟧} markers, wrapped lines of one paragraph
 * joined with {@code ⟦PB#⟧}, blank lines splitting paragraphs) after name/term masking.
 * This tool does not re-implement any of that: it renders each English string through
 * {@link FabricTextStyle#renderTranslated} into a real {@link TranslationService} whose hub
 * lookup is a recorder, and renders the translation through the same style pipeline.</p>
 *
 * <p>Format arguments: Minecraft rewrites {@code %d}/{@code %f} to {@code %s} when it loads a
 * language file, so the displayed text depends on the runtime argument. A numeric
 * conversion ({@code %d}, {@code %.1f}, ...) is filled with a distinct example number and
 * the row is stored under the number-normalized key ({@link TranslationTemplate}, the form
 * the translation cache itself keys on). A {@code %s} argument can be anything (a name, an
 * item, a number), no stable key exists for it, so such strings are skipped and counted.</p>
 *
 * <p>Input files (one per mod or shader pack):</p>
 * <pre>
 * {"kind":"mod"|"shaderpack","modId":"...","entries":[{"ns":"..","key":"..","en":"..","zh_tw":".."}]}
 * </pre>
 *
 * <p>Usage:</p>
 * <pre>
 *   --in &lt;file|dir&gt;      conversion JSON file, or a directory searched recursively (repeatable)
 *   --out &lt;dir&gt;          repository root (the translation-hub/ directory)
 *   --lang zh-TW          target language (default zh-TW)
 *   --shader-target &lt;id&gt; mod id whose file receives shader-pack rows (the repository has no
 *                         shader-pack category, and a shader's options are only reachable
 *                         with the shader-loader mod installed, so pass that mod's id).
 *                         Without it, shader-pack inputs are ignored.
 *   --merge-index         update &lt;out&gt;/index.json for every written file
 *   --report &lt;file&gt;       also write the statistics as text
 *   --skips &lt;file&gt;        write every skipped string (contains source text: scratch use only)
 * </pre>
 */
public final class LangPackBuilder {
    /** Why a string (or one paragraph of it) produced no row. */
    public enum Skip {
        /** A {@code %s}-like argument: unknowable at build time. */
        NON_NUMERIC_ARG,
        /** English and translation use different argument lists. */
        FORMAT_MISMATCH,
        /** The format string is not valid / has an unsupported conversion. */
        FORMAT_ERROR,
        /** The mod never asks the hub about this line (nothing to translate, number-only, ...). */
        NOT_LOOKED_UP,
        /** The mod composes this line from several units instead of one whole-line key. */
        COMPOSED_AT_RUNTIME,
        /** English and translation render to a different number of paragraphs. */
        PARAGRAPH_MISMATCH,
        /** The translation's example numbers could not be mapped back onto the key's slots. */
        NUMBER_SLOT_MISMATCH,
        /** The row failed the same shape validation the mod applies when the row hits. */
        REJECTED_VALIDATION,
        /** The translation equals the English text. */
        UNCHANGED,
        /** Empty English or translation. */
        EMPTY,
        /** A different string already produced this key with another translation. */
        CONFLICT
    }

    public record Row(String key, String value) {
    }

    /** Result of converting one language-file entry. */
    public record Outcome(List<Row> rows, List<Skip> skipped) {
    }

    // ------------------------------------------------------------------ converter

    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([\\d.]*)([dfsS%])");
    private static final long[] EXAMPLE_INTS = {12, 34, 56, 78, 91, 23, 45, 67};

    /** A string with its numeric arguments substituted. */
    private record Rendered(String text, List<String> signature, boolean numericArgs, Skip failure) {
    }

    private static final Executor DIRECT = Runnable::run;

    /**
     * Turns (English, translation) pairs into hub rows. One instance owns one inline
     * {@link TranslationService} whose hub lookup only records the keys it is asked for;
     * no network engine is ever reachable (request switch off, translator fake).
     */
    public static final class Converter {
        private final TranslationService service;
        private final DoNotTranslateMatcher terms = DoNotTranslateMatcher.EMPTY;
        private final TranslationTemplate templates = new TranslationTemplate();
        private final List<String> recordedKeys = new ArrayList<>();

        public Converter(String targetLanguage) {
            TranslatorConfig config = new TranslatorConfig();
            config.targetLang = targetLanguage;
            config.translationRequestsEnabled = false;
            config.aiScreenText = true;
            com.dragonmeow.nyanlex.translate.Translator inert =
                    (text, target) -> new TranslationResult(text, "en");
            TranslationCache google = new TranslationCache(inert, targetLanguage, DIRECT, 64);
            TranslationCache ai = new TranslationCache(inert, targetLanguage, DIRECT, 64);
            this.service = new TranslationService(config, google, ai);
            this.service.setHubLookup(key -> {
                recordedKeys.add(key);
                return null;
            });
        }

        public Outcome convert(String en, String zh) {
            List<Row> rows = new ArrayList<>();
            List<Skip> skipped = new ArrayList<>();
            if (en == null || zh == null || en.isBlank() || zh.isBlank()) {
                skipped.add(Skip.EMPTY);
                return new Outcome(rows, skipped);
            }
            Rendered english = render(en);
            Rendered chinese = render(zh);
            Skip failure = english.failure != null ? english.failure : chinese.failure;
            if (failure == null && !english.signature.equals(chinese.signature)) failure = Skip.FORMAT_MISMATCH;
            if (failure != null) {
                skipped.add(failure);
                return new Outcome(rows, skipped);
            }
            List<String> sourceRequests = requests(english.text);
            List<String> targetRequests = requests(chinese.text);
            if (sourceRequests.isEmpty()) {
                skipped.add(Skip.EMPTY);
                return new Outcome(rows, skipped);
            }
            if (sourceRequests.size() != targetRequests.size()) {
                skipped.add(Skip.PARAGRAPH_MISMATCH);
                return new Outcome(rows, skipped);
            }
            for (int i = 0; i < sourceRequests.size(); i++) {
                convertUnit(sourceRequests.get(i), targetRequests.get(i), english.numericArgs, rows, skipped);
            }
            return new Outcome(rows, skipped);
        }

        private void convertUnit(String sourceRequest, String targetRequest, boolean numericArgs,
                List<Row> rows, List<Skip> skipped) {
            // The key the running mod looks up, recorded from a real TranslationService.
            recordedKeys.clear();
            service.translateScreenText(sourceRequest);
            List<String> keys = new ArrayList<>(recordedKeys);
            if (keys.isEmpty()) {
                skipped.add(Skip.NOT_LOOKED_UP);
                return;
            }
            String key = keys.get(0);
            // One whole-line lookup asks for the text itself, then (same cache machinery) its trimmed
            // and number-normalized forms. Any other key means the line is composed from several units.
            TranslationTemplate.Snapshot lookedUp = templates.prepare(key);
            for (String extra : keys.subList(1, keys.size())) {
                if (!extra.equals(key) && !extra.equals(lookedUp.normalized()) && !extra.equals(lookedUp.key())) {
                    skipped.add(Skip.COMPOSED_AT_RUNTIME);
                    return;
                }
            }
            String value = alignBreaks(key, NameMasker.mask(targetRequest, List.of(), terms).text());
            if (value == null) {
                skipped.add(Skip.PARAGRAPH_MISMATCH);
                return;
            }
            if (numericArgs) {
                TranslationTemplate.Snapshot snapshot = templates.prepare(key);
                String retokenized = snapshot.retokenize(value);
                if (retokenized == null) retokenized = remapReordered(snapshot, templates.prepare(value));
                if (retokenized == null || snapshot.key().isEmpty()) {
                    skipped.add(Skip.NUMBER_SLOT_MISMATCH);
                    return;
                }
                key = snapshot.key();
                value = retokenized;
            }
            if (value.equals(key) || value.trim().equals(key.trim())) {
                skipped.add(Skip.UNCHANGED);
                return;
            }
            if (!HubImportValidator.acceptsOnMerge(value) || !HubImportValidator.acceptsOnHit(key, value)
                    || TextFilter.hasForeignUrl(key, value)) {
                skipped.add(Skip.REJECTED_VALIDATION);
                return;
            }
            rows.add(new Row(key, value));
        }

        private static final String CUT_AFTER = "，。；：！？、,.;:!? ";

        /**
         * The paragraph-break tokens ({@code ⟦PBn⟧}, soft line breaks inside one paragraph) of the
         * translation must number exactly the key's. The wrap points of a language file are a
         * property of the English layout and the style pipeline also decides per language whether a
         * line continues a sentence, so the counts often differ. A surplus is flattened away;
         * a shortfall is re-created by cutting the flattened text in proportion to the English rows,
         * preferably after punctuation. Returns null when this cannot be done safely.
         */
        static String alignBreaks(String key, String value) {
            int want = com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(key);
            int have = com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(value);
            if (want == have) return value;
            String flat = com.dragonmeow.nyanlex.translate.ParagraphModel.flattenBreakTokens(value);
            if (want == 0) return flat;
            if (flat.indexOf('⟦') >= 0) return null;
            List<String> rows = com.dragonmeow.nyanlex.translate.ParagraphModel.split(key);
            int[] weights = new int[rows.size()];
            long totalWeight = 0;
            for (int i = 0; i < rows.size(); i++) {
                weights[i] = Math.max(1, rows.get(i).replaceAll("⟦[^⟧]*⟧", "").strip().length());
                totalWeight += weights[i];
            }
            int length = flat.length();
            if (length < rows.size() * 2) return null;
            List<String> cuts = new ArrayList<>();
            int previous = 0;
            long cumulative = 0;
            for (int i = 0; i < want; i++) {
                cumulative += weights[i];
                int target = (int) Math.round((double) cumulative / totalWeight * length);
                int window = Math.max(4, length / (rows.size() * 3));
                int best = -1;
                for (int distance = 0; distance <= window && best < 0; distance++) {
                    for (int candidate : new int[] {target - distance, target + distance}) {
                        if (candidate > previous && candidate < length
                                && CUT_AFTER.indexOf(flat.charAt(candidate - 1)) >= 0) {
                            best = candidate;
                            break;
                        }
                    }
                }
                if (best < 0) best = Math.max(target, previous + 1);
                if (best >= length || Character.isLowSurrogate(flat.charAt(best))) return null;
                String row = flat.substring(previous, best).strip();
                if (row.isEmpty()) return null;
                cuts.add(row);
                previous = best;
            }
            String last = flat.substring(previous).strip();
            if (last.isEmpty()) return null;
            cuts.add(last);
            return com.dragonmeow.nyanlex.translate.ParagraphModel.join(cuts);
        }

        /**
         * The translation put its numbers in a different order than the English text (numbered
         * arguments): template the translation on its own, then rename each of its number
         * slots to the English slot that holds the same example value.
         */
        private static String remapReordered(TranslationTemplate.Snapshot source, TranslationTemplate.Snapshot target) {
            if (!target.leadIcon().isEmpty() || !source.leadIcon().isEmpty()
                    || !target.layoutGaps().isEmpty() || !source.layoutGaps().isEmpty()) return null;
            List<String> sourceValues = source.base().values();
            List<String> targetValues = target.base().values();
            if (sourceValues.isEmpty() || sourceValues.size() != targetValues.size()) return null;
            String text = target.key();
            Map<Integer, Integer> rename = new LinkedHashMap<>();
            for (int i = 0; i < targetValues.size(); i++) {
                int match = -1;
                for (int j = 0; j < sourceValues.size(); j++) {
                    if (!sourceValues.get(j).equals(targetValues.get(i))) continue;
                    if (match >= 0) return null; // ambiguous example value
                    match = j;
                }
                if (match < 0) return null;
                rename.put(target.base().slotIndices().get(i), source.base().slotIndices().get(match));
            }
            // two-phase rename through a private marker so renamed tokens are never renamed again
            for (Map.Entry<Integer, Integer> entry : rename.entrySet()) {
                text = text.replace("⟦MT" + entry.getKey() + "⟧", "⟦MX" + entry.getValue() + "⟧");
            }
            return text.replace("⟦MX", "⟦MT");
        }

        /** Backend request strings the style pipeline produces for one displayed text. */
        private static List<String> requests(String displayed) {
            List<String> out = new ArrayList<>();
            FabricTextStyle.renderTranslated("screenText", Component.literal(displayed), request -> {
                out.add(request);
                return TranslationDecision.unchanged(request);
            });
            return out;
        }
    }

    /**
     * Mirrors how Minecraft reads a language value: {@code %d}/{@code %f} (also {@code %1$d},
     * {@code %.1f}) are rewritten to {@code %s} when the file is loaded, so the displayed text
     * carries the argument verbatim; {@code %%} is a literal percent sign; any other
     * {@code %} is plain text. Every numeric specifier is replaced by a distinct example
     * number, a {@code %s} makes the string unusable.
     */
    private static Rendered render(String format) {
        Matcher matcher = FORMAT.matcher(format);
        StringBuilder out = new StringBuilder(format.length() + 16);
        List<String> signature = new ArrayList<>();
        int cursor = 0;
        int sequential = 0;
        while (matcher.find()) {
            char conversion = matcher.group(3).charAt(0);
            boolean explicit = matcher.group(1) != null;
            boolean decorated = !matcher.group(2).isEmpty();
            out.append(format, cursor, matcher.start());
            cursor = matcher.end();
            if (conversion == '%') {
                if (!explicit && !decorated) {
                    out.append('%');
                } else {
                    out.append(matcher.group());
                }
                continue;
            }
            if (conversion == 's' || conversion == 'S') {
                if (decorated) {
                    out.append(matcher.group());
                    continue;
                }
                return new Rendered(format, List.of(), false, Skip.NON_NUMERIC_ARG);
            }
            int position = explicit ? Integer.parseInt(matcher.group(1)) : ++sequential;
            long base = EXAMPLE_INTS[(Math.max(position, 1) - 1) % EXAMPLE_INTS.length];
            out.append(conversion == 'd' ? Long.toString(base) : base + ".5");
            signature.add(position + ":" + conversion);
        }
        out.append(format, cursor, format.length());
        java.util.Collections.sort(signature);
        return new Rendered(out.toString(), signature, !signature.isEmpty(), null);
    }

    // ------------------------------------------------------------------ batch conversion

    /** Aggregate for one target mod id. */
    public static final class Target {
        public final String modId;
        public final Map<String, String> rows = new LinkedHashMap<>();
        public final List<String> sources = new ArrayList<>();
        public int entries;
        public int entriesWithRows;
        public final EnumMap<Skip, Integer> skipped = new EnumMap<>(Skip.class);

        Target(String modId) {
            this.modId = modId;
        }

        void skip(Skip reason) {
            skipped.merge(reason, 1, Integer::sum);
        }

        public int skippedTotal() {
            int total = 0;
            for (int count : skipped.values()) total += count;
            return total;
        }
    }

    /** The license decision taken for one input file. */
    public record Licensing(String source, String kind, String modId, String license, boolean accepted,
            String reason) {
    }

    public record Result(Map<String, Target> targets, Map<String, Path> written, List<Licensing> licensing) {
    }

    /** One parsed input file. */
    record Input(Path file, String kind, String modId, String license, String excluded,
            List<String[]> entries) {
    }

    // ------------------------------------------------------------------ license policy

    /**
     * The license gate: the {@code license} field of an input file is an SPDX expression, parsed with
     * {@code AND}, {@code OR} (any letter case; {@code AND} binds tighter than {@code OR}), parentheses,
     * {@code WITH <exception>} and a trailing {@code +}, and every license id in it is compared exactly
     * (never as a substring) with the whitelist: MIT, Apache-2.0, BSD-*, MPL-2.0, LGPL-*, GPL-* (AGPL is
     * not GPL-*), CC-BY-* (the NC, SA and ND variants too), Polyform-Shield, CC0-1.0 and Unlicense.
     * {@code A AND B} needs both sides accepted, {@code A OR B} either.
     *
     * <p>Every {@code LicenseRef-*} is refused, All Rights Reserved and custom licenses included, with one
     * exception: Modrinth reports Polyform Shield as {@code LicenseRef-Polyform-Shield-<version>}, which is
     * the whitelisted license under its other spelling. Anything that is not a well-formed expression
     * (a free-text license name, unbalanced parentheses, a dangling operator) is refused. For a dual-licensed
     * project, the author's ruling goes into the file's {@code license} field as a plain SPDX expression
     * such as {@code LGPL-2.1 AND CC-BY-NC-SA-4.0}; that string is then the decision.</p>
     */
    public static boolean licenseAccepted(String license) {
        if (license == null || license.isBlank()) return false;
        List<String> tokens = new ArrayList<>();
        Matcher token = Pattern.compile("[()]|[^\\s()]+").matcher(license);
        while (token.find()) tokens.add(token.group());
        return new SpdxExpression(tokens).accepted();
    }

    /** One license id against the whitelist (exact id, or the documented id family prefix). */
    static boolean licenseIdAccepted(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.endsWith("+")) s = s.substring(0, s.length() - 1); // "or later"
        if (s.startsWith("licenseref-")) {
            s = s.substring("licenseref-".length());
            return isPolyformShield(s); // every other custom reference is refused
        }
        return s.equals("mit") || s.equals("apache-2.0") || s.startsWith("bsd-") || s.equals("mpl-2.0")
                || s.startsWith("lgpl-") || s.startsWith("gpl-") || s.startsWith("cc-by-")
                || isPolyformShield(s) || s.equals("cc0-1.0") || s.equals("unlicense");
    }

    private static boolean isPolyformShield(String lowerCaseId) {
        return lowerCaseId.equals("polyform-shield") || lowerCaseId.startsWith("polyform-shield-");
    }

    /** Recursive-descent evaluation of an SPDX expression: {@code or := and (OR and)*}, {@code and := term (AND term)*}. */
    private static final class SpdxExpression {
        private final List<String> tokens;
        private int pos;
        private boolean malformed;

        SpdxExpression(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean accepted() {
            boolean value = or();
            return !malformed && pos == tokens.size() && value;
        }

        private boolean or() {
            boolean value = and();
            while (!malformed && isWord("or")) {
                pos++;
                boolean next = and(); // always parse the right side, even when the left already decided
                value = value || next;
            }
            return value;
        }

        private boolean and() {
            boolean value = term();
            while (!malformed && isWord("and")) {
                pos++;
                boolean next = term();
                value = value && next;
            }
            return value;
        }

        private boolean term() {
            if (pos >= tokens.size()) {
                malformed = true;
                return false;
            }
            String token = tokens.get(pos);
            if (token.equals("(")) {
                pos++;
                boolean inner = or();
                if (pos < tokens.size() && tokens.get(pos).equals(")")) pos++;
                else malformed = true;
                return inner;
            }
            if (token.equals(")") || isOperator(token)) {
                malformed = true;
                return false;
            }
            pos++;
            boolean ok = licenseIdAccepted(token);
            if (isWord("with")) { // "GPL-2.0-only WITH <exception>": the exception only adds permissions
                pos++;
                if (pos >= tokens.size() || tokens.get(pos).equals("(") || tokens.get(pos).equals(")")
                        || isOperator(tokens.get(pos))) {
                    malformed = true;
                    return false;
                }
                pos++;
            }
            return ok;
        }

        private boolean isWord(String word) {
            return pos < tokens.size() && tokens.get(pos).equalsIgnoreCase(word);
        }

        private static boolean isOperator(String token) {
            return token.equalsIgnoreCase("and") || token.equalsIgnoreCase("or") || token.equalsIgnoreCase("with");
        }
    }

    static Input readInput(Path file) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return null;
            root = parsed.getAsJsonObject();
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!root.has("entries") || !root.get("entries").isJsonArray()) return null;
        String kind = root.has("kind") && !root.get("kind").isJsonNull() ? root.get("kind").getAsString() : "mod";
        String modId = root.has("modId") && !root.get("modId").isJsonNull() ? root.get("modId").getAsString() : null;
        List<String[]> entries = new ArrayList<>();
        JsonArray array = root.getAsJsonArray("entries");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            entries.add(new String[] {text(entry, "en"), text(entry, "zh_tw"), text(entry, "key")});
        }
        String license = root.has("license") && !root.get("license").isJsonNull()
                ? root.get("license").getAsString() : null;
        String excluded = root.has("excluded") && !root.get("excluded").isJsonNull()
                ? root.get("excluded").getAsString() : null;
        return new Input(file, kind, modId, license, excluded, entries);
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    /** Convert every input and write one repository file per target mod id. */
    public static Result build(List<Path> inputs, Path outDir, String language, String shaderTarget,
            boolean mergeIndex) throws IOException {
        return build(inputs, outDir, language, shaderTarget, mergeIndex, null);
    }

    /** @param skipLog optional: one tab-separated line per skipped string (author scratch use only,
     *                 it contains the source text and must never be committed). */
    public static Result build(List<Path> inputs, Path outDir, String language, String shaderTarget,
            boolean mergeIndex, List<String> skipLog) throws IOException {
        Map<String, Target> targets = new TreeMap<>();
        List<Licensing> licensing = new ArrayList<>();
        Converter converter = new Converter(language);
        for (Path file : inputs) {
            Input input = readInput(file);
            if (input == null) continue;
            boolean shader = "shaderpack".equals(input.kind);
            String id = shader ? shaderTarget : input.modId;
            if (id == null || id.isBlank()) continue;
            // The file's own "excluded" hint is advisory only: the license text decides.
            boolean accepted = licenseAccepted(input.license);
            licensing.add(new Licensing(file.getFileName().toString(), input.kind, input.modId, input.license,
                    accepted, accepted ? "accepted" : "license not accepted"));
            if (!accepted) continue;
            Target target = targets.computeIfAbsent(id, Target::new);
            target.sources.add(file.getFileName().toString());
            for (String[] pair : input.entries) {
                target.entries++;
                Outcome outcome = converter.convert(pair[0], pair[1]);
                boolean any = false;
                for (Row row : outcome.rows) {
                    String hash = HubKeyHash.of(row.key());
                    String existing = target.rows.get(hash);
                    if (existing == null) {
                        target.rows.put(hash, row.value());
                        any = true;
                    } else if (!existing.equals(row.value())) {
                        target.skip(Skip.CONFLICT);
                    } else {
                        any = true;
                    }
                }
                if (shader && any && pair[2] != null && pair[2].startsWith("option.")
                        && !pair[0].strip().endsWith(":")) {
                    // The shader-settings screen draws an option label as one string with the colon
                    // attached ("Option name: "), so that exact text is what the hub is asked for.
                    for (Row row : converter.convert(pair[0] + ": ", pair[1] + "： ").rows()) {
                        target.rows.putIfAbsent(HubKeyHash.of(row.key()), row.value());
                    }
                }
                if (any) target.entriesWithRows++;
                for (Skip reason : outcome.skipped) {
                    target.skip(reason);
                    if (skipLog != null) {
                        skipLog.add(id + "\t" + reason + "\t" + oneLine(pair[0]) + "\t" + oneLine(pair[1]));
                    }
                }
            }
        }
        Map<String, Path> written = new TreeMap<>();
        for (Target target : targets.values()) {
            if (target.rows.isEmpty()) continue;
            Path file = outDir.resolve(HubPaths.modPath(target.modId, language));
            Files.createDirectories(file.getParent());
            new HubFile(language, target.rows).write(file);
            written.put(target.modId, file);
            if (mergeIndex) {
                byte[] bytes = Files.readAllBytes(file);
                HubExportTool.mergeIndex(outDir, HubSource.mod(target.modId), language, target.rows.size(),
                        bytes.length, HubExportTool.sha256Hex(bytes));
            }
        }
        return new Result(targets, written, licensing);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\n", "\\n");
    }

    static String describe(Result result) {
        StringBuilder out = new StringBuilder();
        int rows = 0;
        for (Target target : result.targets.values()) {
            rows += target.rows.size();
            out.append(target.modId).append(": entries=").append(target.entries)
                    .append(" entriesWithRows=").append(target.entriesWithRows)
                    .append(" rows=").append(target.rows.size())
                    .append(" skipped=").append(target.skippedTotal())
                    .append(" ").append(target.skipped)
                    .append(" sources=").append(target.sources).append('\n');
        }
        for (Licensing decision : result.licensing) {
            out.append("LICENSE ").append(decision.accepted() ? "ACCEPT " : "REFUSE ")
                    .append(decision.kind()).append(' ').append(decision.modId() == null ? "-" : decision.modId())
                    .append(" [").append(decision.license()).append("] ").append(decision.source()).append('\n');
        }
        out.append("TOTAL rows=").append(rows).append(" files=").append(result.written.size()).append('\n');
        return out.toString();
    }

    // ------------------------------------------------------------------ CLI

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (IOException | IllegalArgumentException failure) {
            System.out.println("ERROR " + failure.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args, PrintStream out) throws IOException {
        List<Path> inputs = new ArrayList<>();
        Path outDir = null;
        Path report = null;
        Path skips = null;
        String language = "zh-TW";
        String shaderTarget = null;
        boolean mergeIndex = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--in" -> inputs.add(Path.of(next(args, ++i, "--in")));
                case "--out" -> outDir = Path.of(next(args, ++i, "--out"));
                case "--lang" -> language = next(args, ++i, "--lang");
                case "--shader-target" -> shaderTarget = next(args, ++i, "--shader-target");
                case "--report" -> report = Path.of(next(args, ++i, "--report"));
                case "--skips" -> skips = Path.of(next(args, ++i, "--skips"));
                case "--merge-index" -> mergeIndex = true;
                default -> throw new IllegalArgumentException("Unknown flag " + args[i]);
            }
        }
        if (inputs.isEmpty() || outDir == null) {
            throw new IllegalArgumentException("--in and --out are required");
        }
        List<Path> files = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> walk = Files.walk(input)) {
                    walk.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p))
                            .sorted().forEach(files::add);
                }
            } else {
                files.add(input);
            }
        }
        List<String> skipLog = skips == null ? null : new ArrayList<>();
        Result result = build(files, outDir, language, shaderTarget, mergeIndex, skipLog);
        if (skipLog != null) Files.write(skips, skipLog, StandardCharsets.UTF_8);
        String text = describe(result);
        out.print(text);
        if (report != null) Files.writeString(report, text, StandardCharsets.UTF_8);
        return 0;
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[index];
    }
}
