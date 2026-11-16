package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the text blocks of an overlay dialogue box inside a flattened component and rebuilds
 * the component with those blocks replaced, in place, without moving anything else.
 *
 * <p>The component is given as a list of {@link Run}s (text + font id); styles stay with the
 * caller and are referred to by run index. An overlay is drawn centred on its total advance and
 * every visual element positions itself with move characters ({@link OverlaySpace}), so the
 * rewrite replaces only the cells of the translatable text and compensates the difference with
 * extra moves: the net advance of everything it touches is unchanged by construction.</p>
 *
 * <p>Blocks: the spoken text (rows {@code body_n}, joined into one sentence), the speaker name,
 * the continue hint and the answer rows. Artwork, HUD and icon characters are never read or
 * changed.</p>
 */
public final class OverlayDialogue {

    public static final String BODY_ID = "body";
    public static final String NAMEPLATE_ID = "nameplate";
    public static final String CONTROL_ID = "control";

    private static final int C_OTHER = 0;
    private static final int C_MOVE = 1;
    private static final int C_ZERO = 2;
    private static final int C_ICON = 3;
    private static final int C_TEXT = 4;

    private OverlayDialogue() {
    }

    /** Block id of answer row {@code row}. */
    public static String choiceId(int row) {
        return "choice" + row;
    }

    /** One flattened piece of the component: its text and the font id it is drawn with. */
    public record Run(String text, String font) {
    }

    /**
     * One piece of the rebuilt component. {@code font == null} keeps the font of the style of
     * input run {@code styleSource}; otherwise the style of that run is used with {@code font}.
     */
    public record OutRun(String text, String font, int styleSource) {
    }

    /** Width in px of {@code text} drawn in {@code font} with the style of input run {@code styleSource}. */
    @FunctionalInterface
    public interface Measure {
        int width(String font, int styleSource, String text);
    }

    /**
     * A piece of the spoken text with the run it came from; {@code styleSource} is the input run
     * whose style it carries, or {@code -1} for the single space that joins two rows.
     * {@code icon} marks the characters of an inline icon (its own font and style: the glue must
     * not change them); its place in the sentence is where it stood in the line.
     */
    public record BodyPiece(String text, int styleSource, boolean icon) {
        public BodyPiece(String text, int styleSource) {
            this(text, styleSource, false);
        }
    }

    /**
     * A piece of a translated spoken text; {@code styleSource} indexes the caller's style table
     * ({@code -1} = the style of the row it lands on, as in a single-style text).
     */
    public record Segment(String text, int styleSource) {
    }

    /** Line breaking, supplied by the caller so it can memoise. */
    @FunctionalInterface
    public interface Wrapper {
        List<String> wrap(String font, int styleSource, String text, int limit);

        /**
         * Same, where {@code fixedWidths} gives the width of characters that are not text of the
         * row font (the inline icons, each with the moves around it); the default ignores it.
         */
        default List<String> wrap(String font, int styleSource, String text, int limit,
                                  Map<Integer, Integer> fixedWidths) {
            return wrap(font, styleSource, text, limit);
        }
    }

    /** An icon in the middle of a row with the moves around it: cells {@code [from, to)}. */
    private record Inline(int from, int to, String content) {
    }

    private static final class Body {
        int first;
        int last;
        String sampleFont;
        int firstRun;
        /** Run of the move character just before the text (a style that does not stretch moves). */
        int moveRun = -1;
        final List<BodyPiece> pieces = new ArrayList<>();
        final List<Inline> inlines = new ArrayList<>();
        final int[] lineRun = new int[OverlayDialogueFonts.BODY_LINES];

        Body() {
            Arrays.fill(lineRun, -1);
        }
    }

    private static final class Single {
        int start;
        int end;
        int elemStart;
        int elemEnd;
        String font;
        int firstRun;
        boolean broken;
        String raw;
    }

    /** Result of {@link #analyze(List)}. */
    public static final class Analysis {
        static final Analysis NONE = new Analysis(false);

        private final boolean dialogue;
        private final LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        private List<Run> runs;
        private int[] cps;
        private int[] runOf;
        private int[] runStart;
        private byte[] cls;
        private Body body;
        private final Map<String, Single> singles = new LinkedHashMap<>();

        private Analysis(boolean dialogue) {
            this.dialogue = dialogue;
        }

        /** True when the component contains any dialogue box font (artwork included). */
        public boolean dialogue() {
            return dialogue;
        }

        /**
         * The translatable text of every block present, by block id, in reading order. Block
         * texts never contain move characters, icons, or the text of any other block.
         */
        public Map<String, String> sources() {
            return sources;
        }

        /**
         * The spoken text as pieces that keep the run (style) they came from, rows in order and
         * joined by one space piece ({@code styleSource == -1}); their texts add up to
         * {@code sources().get("body")}. Empty when the page has no usable spoken text.
         */
        public List<BodyPiece> bodyPieces() {
            return body == null ? List.of() : body.pieces;
        }
    }

    /** Reads the blocks out of {@code runs}. Never null; check {@link Analysis#dialogue()}. */
    public static Analysis analyze(List<Run> runs) {
        boolean dialogue = false;
        for (Run run : runs) {
            if (OverlayDialogueFonts.isDialogue(run.font())) {
                dialogue = true;
                break;
            }
        }
        if (!dialogue) {
            return Analysis.NONE;
        }
        Analysis a = new Analysis(true);
        a.runs = runs;
        expand(a, runs);
        analyzeBody(a);
        analyzeSingles(a);
        return a;
    }

    private static void expand(Analysis a, List<Run> runs) {
        int total = 0;
        for (Run run : runs) {
            total += run.text().length();
        }
        int[] cps = new int[total];
        int[] runOf = new int[total];
        int[] runStart = new int[runs.size() + 1];
        byte[] cls = new byte[total];
        int n = 0;
        for (int r = 0; r < runs.size(); r++) {
            runStart[r] = n;
            Run run = runs.get(r);
            OverlayDialogueFonts.Kind kind = OverlayDialogueFonts.kind(run.font());
            String text = run.text();
            int i = 0;
            while (i < text.length()) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                cps[n] = cp;
                runOf[n] = r;
                cls[n] = (byte) classify(cp, kind);
                n++;
            }
        }
        runStart[runs.size()] = n;
        a.cps = Arrays.copyOf(cps, n);
        a.runOf = Arrays.copyOf(runOf, n);
        a.cls = Arrays.copyOf(cls, n);
        a.runStart = runStart;
    }

    private static int classify(int cp, OverlayDialogueFonts.Kind kind) {
        if (OverlaySpace.isMove(cp)) {
            return C_MOVE;
        }
        if (kind == OverlayDialogueFonts.Kind.OTHER) {
            return C_OTHER;
        }
        if (cp == 0x200C) {
            return C_ZERO;
        }
        if (cp < 0x20 || (cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000
                || (cp >= 0xD800 && cp <= 0xDFFF)) {
            return C_ICON;
        }
        return C_TEXT;
    }

    private static OverlayDialogueFonts.Kind kindOfCell(Analysis a, int cell) {
        return OverlayDialogueFonts.kind(a.runs.get(a.runOf[cell]).font());
    }

    private static String fontOfCell(Analysis a, int cell) {
        return a.runs.get(a.runOf[cell]).font();
    }

    // ---------------------------------------------------------------- analysis

    private static void analyzeBody(Analysis a) {
        int first = -1;
        int last = -1;
        String prefix = null;
        boolean bad = false;
        for (int i = 0; i < a.cps.length; i++) {
            if (a.cls[i] != C_TEXT || kindOfCell(a, i) != OverlayDialogueFonts.Kind.BODY) {
                continue;
            }
            String font = fontOfCell(a, i);
            String p = OverlayDialogueFonts.stylePrefix(font);
            if (prefix == null) {
                prefix = p;
            } else if (!prefix.equals(p)) {
                bad = true;
            }
            if (first < 0) {
                first = i;
            }
            last = i;
        }
        if (first < 0 || bad) {
            return;
        }
        Body body = new Body();
        body.first = first;
        body.last = last;
        body.sampleFont = fontOfCell(a, first);
        body.firstRun = a.runOf[first];
        if (first > 0 && a.cls[first - 1] == C_MOVE) {
            body.moveRun = a.runOf[first - 1];
        }
        @SuppressWarnings("unchecked")
        List<BodyPiece>[] lines = new List[OverlayDialogueFonts.BODY_LINES];
        int i = first;
        int row = -1;
        while (i <= last) {
            if (a.cls[i] == C_TEXT && kindOfCell(a, i) == OverlayDialogueFonts.Kind.BODY) {
                int run = a.runOf[i];
                int line = OverlayDialogueFonts.line(fontOfCell(a, i));
                StringBuilder text = new StringBuilder();
                while (i <= last && a.cls[i] == C_TEXT && a.runOf[i] == run) {
                    if (a.cps[i] == '§') {
                        return;
                    }
                    text.appendCodePoint(a.cps[i]);
                    i++;
                }
                if (lines[line] == null) {
                    lines[line] = new ArrayList<>();
                    body.lineRun[line] = run;
                }
                lines[line].add(new BodyPiece(text.toString(), run));
                row = line;
                continue;
            }
            // The cells up to the next spoken-text cell: only moves (a row change, a return to
            // the row start, spacing), or an inline icon with the moves around it.
            int j = i;
            boolean icon = false;
            while (j <= last && !(a.cls[j] == C_TEXT && kindOfCell(a, j) == OverlayDialogueFonts.Kind.BODY)) {
                int cell = a.cls[j];
                if (cell == C_MOVE) {
                    if (OverlaySpace.advance(a.cps[j]) == OverlaySpace.UNDEFINED) {
                        return;
                    }
                    if (body.moveRun < 0) {
                        body.moveRun = a.runOf[j];
                    }
                } else if (cell == C_TEXT) {
                    return; // text of another block in the middle of the spoken text
                } else if (cell != C_ZERO) {
                    int cp = a.cps[j];
                    if (Character.isLetterOrDigit(cp) || cp < 0x20 || Character.isWhitespace(cp)) {
                        return; // an icon has no letters; anything else is not understood
                    }
                    icon = true;
                }
                j++;
            }
            if (icon) {
                // only between two cells of the same row: an icon between rows, or before the first
                // text, has no row it clearly belongs to
                if (row < 0 || j > last || OverlayDialogueFonts.line(fontOfCell(a, j)) != row) {
                    return;
                }
                StringBuilder content = new StringBuilder();
                int k = i;
                while (k < j) {
                    if (a.cls[k] == C_MOVE || a.cls[k] == C_ZERO) {
                        k++;
                        continue;
                    }
                    int run = a.runOf[k];
                    StringBuilder part = new StringBuilder();
                    while (k < j && a.cls[k] != C_MOVE && a.cls[k] != C_ZERO && a.runOf[k] == run) {
                        part.appendCodePoint(a.cps[k]);
                        k++;
                    }
                    content.append(part);
                    lines[row].add(new BodyPiece(part.toString(), run, true));
                }
                body.inlines.add(new Inline(i, j, content.toString()));
            }
            i = j;
        }
        StringBuilder page = new StringBuilder();
        for (List<BodyPiece> line : lines) {
            if (line == null) {
                continue;
            }
            List<BodyPiece> trimmed = trimRow(line);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!body.pieces.isEmpty()) {
                body.pieces.add(new BodyPiece(" ", -1));
                page.append(' ');
            }
            for (BodyPiece piece : trimmed) {
                body.pieces.add(piece);
                page.append(piece.text());
            }
        }
        if (page.length() == 0) {
            return;
        }
        a.body = body;
        a.sources.put(BODY_ID, page.toString());
    }

    /** One row's pieces without the whitespace around the row (what {@code String.strip} would remove). */
    private static List<BodyPiece> trimRow(List<BodyPiece> row) {
        List<BodyPiece> out = new ArrayList<>(row);
        while (!out.isEmpty()) {
            BodyPiece head = out.get(0);
            if (head.icon()) {
                break;
            }
            String text = head.text().stripLeading();
            if (text.isEmpty()) {
                out.remove(0);
            } else {
                out.set(0, new BodyPiece(text, head.styleSource(), false));
                break;
            }
        }
        while (!out.isEmpty()) {
            int last = out.size() - 1;
            BodyPiece tail = out.get(last);
            if (tail.icon()) {
                break;
            }
            String text = tail.text().stripTrailing();
            if (text.isEmpty()) {
                out.remove(last);
            } else {
                out.set(last, new BodyPiece(text, tail.styleSource(), false));
                break;
            }
        }
        return out;
    }

    private static void analyzeSingles(Analysis a) {
        Map<String, Single> found = new LinkedHashMap<>();
        for (int i = 0; i < a.cps.length; i++) {
            if (a.cls[i] != C_TEXT) {
                continue;
            }
            OverlayDialogueFonts.Kind kind = kindOfCell(a, i);
            if (kind == OverlayDialogueFonts.Kind.BODY || kind == OverlayDialogueFonts.Kind.OTHER) {
                continue;
            }
            String font = fontOfCell(a, i);
            String id = kind == OverlayDialogueFonts.Kind.NAMEPLATE ? NAMEPLATE_ID
                    : kind == OverlayDialogueFonts.Kind.CONTROL ? CONTROL_ID
                    : choiceId(OverlayDialogueFonts.line(font));
            Single single = found.get(id);
            if (single == null) {
                single = new Single();
                single.start = i;
                single.end = i + 1;
                single.font = font;
                single.firstRun = a.runOf[i];
                found.put(id, single);
            } else if (single.end == i && single.font.equals(font)) {
                single.end = i + 1;
            } else {
                single.broken = true;
            }
        }
        for (Map.Entry<String, Single> entry : found.entrySet()) {
            Single s = entry.getValue();
            if (s.broken) {
                continue;
            }
            StringBuilder raw = new StringBuilder();
            boolean sectionSign = false;
            for (int i = s.start; i < s.end; i++) {
                raw.appendCodePoint(a.cps[i]);
                sectionSign |= a.cps[i] == '§';
            }
            s.raw = raw.toString();
            String text = s.raw.strip();
            if (sectionSign || text.isEmpty()) {
                continue;
            }
            s.elemStart = s.start;
            while (s.elemStart > 0 && isNeutral(a, s.elemStart - 1, s.font)) {
                s.elemStart--;
            }
            s.elemEnd = s.end;
            while (s.elemEnd < a.cps.length && isNeutral(a, s.elemEnd, s.font)) {
                s.elemEnd++;
            }
            a.singles.put(entry.getKey(), s);
        }
        // reading order: name, hint, answers (insertion order of `found` is cell order)
        for (String id : new String[] {NAMEPLATE_ID, CONTROL_ID}) {
            Single s = a.singles.get(id);
            if (s != null) {
                a.sources.put(id, s.raw.strip());
            }
        }
        for (int row = 0; row < OverlayDialogueFonts.CHOICE_ROWS; row++) {
            Single s = a.singles.get(choiceId(row));
            if (s != null) {
                a.sources.put(choiceId(row), s.raw.strip());
            }
        }
    }

    private static boolean isNeutral(Analysis a, int cell, String font) {
        int c = a.cls[cell];
        return (c == C_ICON || c == C_ZERO) && font.equals(fontOfCell(a, cell));
    }

    // ---------------------------------------------------------------- rewrite

    private static final class Piece {
        final String text;
        final String font;
        final int style;
        final int from;
        final int to;

        Piece(String text, String font, int style) {
            this.text = text;
            this.font = font;
            this.style = style;
            this.from = 0;
            this.to = 0;
        }

        Piece(int from, int to) {
            this.text = null;
            this.font = null;
            this.style = -1;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Edit {
        final int start;
        final int end;
        final List<Piece> pieces;

        Edit(int start, int end, List<Piece> pieces) {
            this.start = start;
            this.end = end;
            this.pieces = pieces;
        }
    }

    /**
     * Rebuilds the component with the translated blocks.
     *
     * @param translations block id to translated text; blocks absent from the map keep their original
     * @param baseLimit    widest allowed body line in px (raised to the widest original line)
     * @return the new runs, or {@code null} when nothing changes
     */
    public static List<OutRun> rewrite(Analysis a, Map<String, String> translations, Measure measure,
                                       int baseLimit, Wrapper wrapper) {
        return rewrite(a, translations, null, measure, baseLimit, wrapper);
    }

    /**
     * Like {@link #rewrite(Analysis, Map, Measure, int, Wrapper)}, with the spoken text given as
     * styled pieces: the whole text is wrapped once, then every row takes the piece of each
     * style that falls on it (a coloured phrase that crosses a row break keeps its colour on
     * both rows).
     *
     * @param bodySegments the translated spoken text, or {@code null} to use the plain
     *                     {@code "body"} entry of {@code translations}
     */
    public static List<OutRun> rewrite(Analysis a, Map<String, String> translations,
                                       List<Segment> bodySegments, Measure measure,
                                       int baseLimit, Wrapper wrapper) {
        if (!a.dialogue || (translations.isEmpty() && bodySegments == null)) {
            return null;
        }
        List<Edit> edits = new ArrayList<>(3);
        List<Segment> bodyText = bodySegments;
        if (bodyText == null && translations.get(BODY_ID) != null) {
            bodyText = List.of(new Segment(translations.get(BODY_ID), -1));
        }
        if (bodyText != null && a.body != null) {
            Edit edit = bodyEdit(a, bodyText, measure, baseLimit, wrapper);
            if (edit != null) {
                edits.add(edit);
            }
        }
        for (Map.Entry<String, Single> entry : a.singles.entrySet()) {
            String translated = translations.get(entry.getKey());
            if (translated == null) {
                continue;
            }
            Edit edit = singleEdit(a, entry.getValue(), translated, measure);
            if (edit != null) {
                edits.add(edit);
            }
        }
        if (edits.isEmpty()) {
            return null;
        }
        edits.sort(Comparator.comparingInt(e -> e.start));
        Emitter out = new Emitter();
        int cursor = 0;
        for (Edit edit : edits) {
            emitOriginal(a, out, cursor, edit.start);
            for (Piece piece : edit.pieces) {
                if (piece.text != null) {
                    out.add(piece.text, piece.font, piece.style);
                } else {
                    emitOriginal(a, out, piece.from, piece.to);
                }
            }
            cursor = edit.end;
        }
        emitOriginal(a, out, cursor, a.cps.length);
        return out.build();
    }

    private static Edit bodyEdit(Analysis a, List<Segment> segments, Measure measure, int baseLimit,
                                 Wrapper wrapper) {
        Body b = a.body;
        int net = 0;
        int widest = 0;
        int[] lineWidth = new int[OverlayDialogueFonts.BODY_LINES];
        int row0 = -1;
        int i = b.first;
        while (i <= b.last) {
            int c = a.cls[i];
            if (c == C_MOVE) {
                net += OverlaySpace.advance(a.cps[i]);
                i++;
            } else if (c == C_ZERO) {
                i++;
            } else {
                // spoken text, or an icon: one run at a time, measured in its own font
                boolean text = c == C_TEXT;
                int run = a.runOf[i];
                int j = i;
                StringBuilder sb = new StringBuilder();
                while (j <= b.last && a.cls[j] != C_MOVE && a.cls[j] != C_ZERO
                        && (a.cls[j] == C_TEXT) == text && a.runOf[j] == run) {
                    sb.appendCodePoint(a.cps[j]);
                    j++;
                }
                int w = measure.width(a.runs.get(run).font(), run, sb.toString());
                net += w;
                if (text) {
                    row0 = OverlayDialogueFonts.line(a.runs.get(run).font());
                }
                if (row0 >= 0) {
                    lineWidth[row0] += w;
                }
                i = j;
            }
        }
        for (int w : lineWidth) {
            widest = Math.max(widest, w);
        }
        int limit = Math.max(baseLimit, widest);

        // One flat text with one style per character; any run of whitespace becomes one space.
        StringBuilder flat = new StringBuilder();
        int[] charStyle = new int[16];
        boolean pendingSpace = false;
        int spaceStyle = -1;
        int firstStyle = -1;
        for (Segment segment : segments) {
            String text = segment.text();
            for (int k = 0; k < text.length(); k++) {
                char ch = text.charAt(k);
                if (Character.isWhitespace(ch)) {
                    if (!pendingSpace) {
                        spaceStyle = segment.styleSource();
                    }
                    pendingSpace = flat.length() > 0;
                    continue;
                }
                if (pendingSpace) {
                    charStyle = put(charStyle, flat.length(), spaceStyle);
                    flat.append(' ');
                    pendingSpace = false;
                }
                if (firstStyle < 0) {
                    firstStyle = segment.styleSource();
                }
                charStyle = put(charStyle, flat.length(), segment.styleSource());
                flat.append(ch);
            }
        }
        if (flat.length() == 0) {
            return null;
        }
        String flatText = flat.toString();

        // Inline icons: each stays an unbreakable unit with the moves around it, found in the
        // translation by its characters (same characters, same order of appearance).
        int[] inlineAt = null;
        int[] inlineWidth = new int[b.inlines.size()];
        Map<Integer, Integer> fixed = new HashMap<>();
        if (!b.inlines.isEmpty()) {
            inlineAt = new int[flatText.length()];
            Arrays.fill(inlineAt, -1);
            Map<String, Integer> used = new HashMap<>();
            for (int k = 0; k < b.inlines.size(); k++) {
                Inline inline = b.inlines.get(k);
                inlineWidth[k] = inlineWidth(a, inline, measure);
                int from = used.getOrDefault(inline.content(), 0);
                int at = flatText.indexOf(inline.content(), from);
                if (at < 0) {
                    return null; // the translation lost an icon: the page keeps its original text
                }
                used.put(inline.content(), at + inline.content().length());
                for (int p = at; p < at + inline.content().length(); p++) {
                    inlineAt[p] = k;
                }
                int index = 0;
                for (int p = 0; p < inline.content().length(); ) {
                    int cp = inline.content().codePointAt(p);
                    p += Character.charCount(cp);
                    int value = index++ == 0 ? inlineWidth[k] : 0;
                    Integer before = fixed.put(cp, value);
                    if (before != null && before != value) {
                        return null; // one icon character with two different widths
                    }
                }
            }
        }

        String firstFont = OverlayDialogueFonts.bodyFont(b.sampleFont, 0);
        List<String> lines = wrapper.wrap(firstFont, firstStyle >= 0 ? firstStyle : b.firstRun, flatText, limit,
                fixed);
        if (lines.isEmpty() || lines.size() > OverlayDialogueFonts.BODY_LINES) {
            return null;
        }
        int[] rowStart = new int[lines.size()];
        int pos = 0;
        boolean aligned = true;
        for (int row = 0; row < lines.size() && aligned; row++) {
            String line = lines.get(row);
            while (pos < flatText.length() && flatText.charAt(pos) == ' ' && !line.startsWith(" ")) {
                pos++;
            }
            if (!flatText.startsWith(line, pos)) {
                aligned = false;
            } else {
                rowStart[row] = pos;
                pos += line.length();
            }
        }
        if (!aligned && inlineAt != null) {
            return null; // the icons cannot be placed without the row boundaries
        }

        List<Piece> pieces = new ArrayList<>(lines.size() * 3);
        int rowStyle = b.firstRun;
        int moveStyle = b.moveRun >= 0 ? b.moveRun : b.firstRun;
        for (int row = 0; row < lines.size(); row++) {
            if (row < b.lineRun.length && b.lineRun[row] >= 0) {
                rowStyle = b.lineRun[row];
            }
            String font = OverlayDialogueFonts.bodyFont(b.sampleFont, row);
            String line = lines.get(row);
            // items of the row: styled text, or an icon (index into b.inlines, copied from the original)
            List<String> texts = new ArrayList<>();
            List<Integer> styles = new ArrayList<>();
            List<Integer> inlines = new ArrayList<>();
            if (aligned) {
                int from = rowStart[row];
                int end = from + line.length();
                int k = from;
                while (k < end) {
                    if (inlineAt != null && inlineAt[k] >= 0) {
                        Inline inline = b.inlines.get(inlineAt[k]);
                        if (k + inline.content().length() > end) {
                            return null; // a row break inside an icon
                        }
                        texts.add(null);
                        styles.add(-1);
                        inlines.add(inlineAt[k]);
                        k += inline.content().length();
                        continue;
                    }
                    int runStart = k;
                    int style = charStyle[k];
                    k++;
                    while (k < end && (inlineAt == null || inlineAt[k] < 0) && charStyle[k] == style) {
                        k++;
                    }
                    texts.add(flatText.substring(runStart, k));
                    styles.add(style < 0 ? rowStyle : style);
                    inlines.add(-1);
                }
            } else {
                texts.add(line);
                styles.add(firstStyle >= 0 ? firstStyle : rowStyle);
                inlines.add(-1);
            }
            int w = 0;
            for (int k = 0; k < texts.size(); k++) {
                w += texts.get(k) == null ? inlineWidth[inlines.get(k)]
                        : measure.width(font, styles.get(k), texts.get(k));
            }
            boolean lastRow = row == lines.size() - 1;
            String move = OverlaySpace.move(lastRow ? net - w : -w);
            int lastIndex = texts.size() - 1;
            for (int k = 0; k < texts.size(); k++) {
                String text = texts.get(k);
                if (text == null) {
                    Inline inline = b.inlines.get(inlines.get(k));
                    pieces.add(new Piece(inline.from(), inline.to()));
                    continue;
                }
                // A move written in the last piece's style is only safe when that style does not
                // stretch it (bold adds to every character); otherwise it gets its own run in the
                // style the original layout used for its moves.
                if (k == lastIndex && !move.isEmpty()
                        && measure.width(font, styles.get(k), OverlaySpace.move(1)) == 1) {
                    text = text + move;
                    move = "";
                }
                pieces.add(new Piece(text, font, styles.get(k)));
            }
            if (!move.isEmpty()) {
                pieces.add(new Piece(move, font, moveStyle));
            }
        }
        return new Edit(b.first, b.last + 1, pieces);
    }

    /** Width of an inline icon: its glyphs in their own font plus the moves around them. */
    private static int inlineWidth(Analysis a, Inline inline, Measure measure) {
        int width = 0;
        int i = inline.from();
        while (i < inline.to()) {
            int c = a.cls[i];
            if (c == C_MOVE) {
                width += OverlaySpace.advance(a.cps[i]);
                i++;
            } else if (c == C_ZERO) {
                i++;
            } else {
                int run = a.runOf[i];
                StringBuilder sb = new StringBuilder();
                while (i < inline.to() && a.cls[i] != C_MOVE && a.cls[i] != C_ZERO && a.runOf[i] == run) {
                    sb.appendCodePoint(a.cps[i]);
                    i++;
                }
                width += measure.width(a.runs.get(run).font(), run, sb.toString());
            }
        }
        return width;
    }

    private static int[] put(int[] array, int index, int value) {
        int[] out = index < array.length ? array : Arrays.copyOf(array, Math.max(index + 1, array.length * 2));
        out[index] = value;
        return out;
    }

    private static Edit singleEdit(Analysis a, Single s, String translated, Measure measure) {
        String core = translated.strip();
        if (core.isEmpty()) {
            return null;
        }
        String lead = s.raw.substring(0, s.raw.length() - s.raw.stripLeading().length());
        String trail = s.raw.substring(s.raw.stripTrailing().length());
        String text = lead + core + trail;
        int oldWidth = 0;
        int i = s.start;
        while (i < s.end) {
            int run = a.runOf[i];
            int j = i;
            StringBuilder sb = new StringBuilder();
            while (j < s.end && a.runOf[j] == run) {
                sb.appendCodePoint(a.cps[j]);
                j++;
            }
            oldWidth += measure.width(a.runs.get(run).font(), run, sb.toString());
            i = j;
        }
        int newWidth = measure.width(s.font, s.firstRun, text);
        int delta = oldWidth - newWidth;
        int before = delta >> 1;
        int after = delta - before;
        List<Piece> pieces = new ArrayList<>(6);
        if (before != 0) {
            pieces.add(new Piece(OverlaySpace.move(before), s.font, s.firstRun));
        }
        pieces.add(new Piece(s.elemStart, s.start));
        pieces.add(new Piece(text, s.font, s.firstRun));
        pieces.add(new Piece(s.end, s.elemEnd));
        if (after != 0) {
            pieces.add(new Piece(OverlaySpace.move(after), s.font, s.firstRun));
        }
        return new Edit(s.elemStart, s.elemEnd, pieces);
    }

    private static void emitOriginal(Analysis a, Emitter out, int from, int to) {
        int i = from;
        while (i < to) {
            int run = a.runOf[i];
            int runEnd = a.runStart[run + 1];
            int j = Math.min(to, runEnd);
            if (i == a.runStart[run] && j == runEnd) {
                out.add(a.runs.get(run).text(), null, run);
            } else {
                StringBuilder sb = new StringBuilder(j - i);
                for (int k = i; k < j; k++) {
                    sb.appendCodePoint(a.cps[k]);
                }
                out.add(sb.toString(), null, run);
            }
            i = j;
        }
    }

    /** Collects output pieces, merging neighbours that share a font and a style. */
    private static final class Emitter {
        private final List<StringBuilder> texts = new ArrayList<>();
        private final List<String> fonts = new ArrayList<>();
        private final List<Integer> styles = new ArrayList<>();

        void add(String text, String font, int style) {
            if (text.isEmpty()) {
                return;
            }
            int last = texts.size() - 1;
            if (last >= 0 && styles.get(last) == style && java.util.Objects.equals(fonts.get(last), font)) {
                texts.get(last).append(text);
                return;
            }
            texts.add(new StringBuilder(text));
            fonts.add(font);
            styles.add(style);
        }

        List<OutRun> build() {
            List<OutRun> runs = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                runs.add(new OutRun(texts.get(i).toString(), fonts.get(i), styles.get(i)));
            }
            return runs;
        }
    }
}
