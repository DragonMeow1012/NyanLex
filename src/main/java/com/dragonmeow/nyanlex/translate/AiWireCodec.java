package com.dragonmeow.nyanlex.translate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between the internal {@code ⟦...⟧} protocol-token format (every ⟦…⟧ span
 * produced anywhere in this package: {@link TemplateText}'s {@code ⟦MTn⟧} value slots,
 * {@link NameMasker}'s bare {@code ⟦n⟧} protected-name/do-not-translate placeholders,
 * {@link ParagraphModel}'s {@code ⟦PBn⟧} paragraph breaks, the {@code ⟦CSn⟧}/{@code
 * ⟦/CSn⟧} colour-run pair, the {@code ⟦WSn⟧} column-gap slot, and {@code
 * OpenAiTranslator}'s own wire-only {@code ⟦AI_LINE_i_j⟧} hard-line slots — AND any
 * future reserved {@code ⟦…⟧} kind, since the mapping is derived mechanically from each
 * span's own text rather than a hard-coded enum of known prefixes) and a compact ASCII
 * "wire" format used ONLY for the few seconds a request body spends travelling to and
 * from an OpenAI-compatible chat-completions endpoint.
 *
 * <h2>Why</h2>
 * {@code ⟦}/{@code ⟧} (U+27E6/U+27E7, MATHEMATICAL LEFT/RIGHT WHITE SQUARE BRACKET) have
 * no merged token in either the {@code o200k_base} (GPT-4o/4.1/4o-mini) or {@code
 * cl100k_base} (GPT-4/3.5) tokenizer vocabularies: they are encoded byte-by-byte (3
 * tokens each, 7-9 tokens for a whole {@code ⟦MT0⟧}-shaped span), and a model
 * occasionally drifts to a visually similar, high-frequency CJK bracket instead of
 * reproducing the rare byte sequence exactly (the "『PB0』" instead of "⟦PB0⟧" symptom
 * {@link TextFilter#hasReshapedProtocolToken} exists to catch). ASCII {@code {kind#}}
 * tokens are ordinary sub-word units in every modern tokenizer's training data (JSON,
 * Python format strings, HTML/XML attributes) and measure 3-4 tokens per span instead —
 * see {@code research-placeholder-format.md} and {@code token_count_wire.py} in the
 * project scratchpad for the full measurement table.
 *
 * <h2>Design</h2>
 * <p>{@link #encode} does not special-case CS/MT/WS/PB by name. For every {@code
 * ⟦...⟧} span found, it strips an optional leading {@code /}, splits the remaining body
 * into its leading letter/underscore "kind" run and its digit/underscore "key" run
 * (lower-cased), and builds the wire token {@code {kind-key}} (or {@code {/kind-key}}
 * for a span whose body started with {@code /}). A bare-digit span (no letters at all —
 * {@link NameMasker}'s protected-name placeholder) becomes the bare {@code {digits}}
 * form, matching the familiar ICU {@code {0}} shape. Because the wire key is derived
 * from the span's own text, an open/close pair such as {@code ⟦CS0⟧}/{@code ⟦/CS0⟧}
 * always lands on the SAME key ({@code cs0}) with only the leading slash differing —
 * exactly the open/close signal an XML-trained model already expects — with no separate
 * pairing bookkeeping required, and any future {@code ⟦XYZn⟧} kind round-trips
 * correctly with zero code changes here.</p>
 *
 * <p><b>Literal-text safety:</b> Minecraft/mod text occasionally already contains a
 * brace-and-digit run that LOOKS like a wire token (a leaked {@code "{0}"} format-string
 * bug is a known real-world case). {@link #encode} scans the text OUTSIDE of every
 * {@code ⟦...⟧} span for anything that would be ambiguous with the wire grammar once
 * real tokens are substituted in, and escapes only those specific runs with a leading
 * backslash (the same escaping convention the model already knows from JSON/regex);
 * {@link #decode} resolves real wire tokens first (a backslash-guarded negative
 * lookbehind keeps it from ever touching an escaped run) and removes exactly one
 * escaping backslash from what is left. Plain text such as {@code "{hello}"} that does
 * not look like any wire token is never touched at all.</p>
 *
 * <p><b>Fault tolerance (second line of defence):</b> {@link #decode} also recognises
 * full-width braces, stray internal whitespace and mixed case in the model's response
 * (the same class of superficial reshaping {@link TextFilter#hasReshapedProtocolToken}
 * has always had to tolerate for the old format) and repairs them back to the exact
 * original {@code ⟦...⟧} span. A wire token that cannot be matched to a known mapping
 * entry (wrong digits, a foreign/hallucinated kind, a duplicate…) is left exactly as the
 * model wrote it; {@code OpenAiTranslator#tokensMatch} then sees the resulting {@code
 * ⟦...⟧} multiset mismatch in the DECODED, internal-format text and rejects the unit as
 * "format/token lost" — precisely the same outcome as before this codec existed. No new
 * "guessing" is introduced anywhere in this class.</p>
 *
 * <p>Pure, side-effect-free, no Minecraft dependency: safe to unit test in isolation and
 * safe to call from any thread. Cache keys and every {@link TemplateText}/{@link
 * NameMasker}/{@link ParagraphModel} output are completely unaffected — {@link #encode}
 * is meant to be called only immediately before building the HTTP request body, and
 * {@link #decode} immediately after parsing the response, strictly inside {@code
 * OpenAiTranslator}'s request/response boundary.</p>
 */
public final class AiWireCodec {

    private AiWireCodec() {
    }

    /** A protocol-token span: {@code ⟦...⟧} with no nested bracket — exactly what every
     *  producer in this package emits ({@link TemplateText#prepare}, {@link
     *  NameMasker#mask}, {@link ParagraphModel#breakToken}, the CS colour-run markers,
     *  and OpenAiTranslator's own {@code ⟦AI_LINE_i_j⟧} hard-line slots). */
    private static final Pattern TOKEN = Pattern.compile("⟦[^⟦⟧]*⟧");

    /** Leading run of ASCII letters/underscore before the first digit. */
    private static final Pattern KIND_PREFIX = Pattern.compile("^[A-Za-z_]+");

    /** A wire-shaped run ANYWHERE in plain text, used only to find literal text that
     *  would be ambiguous with a real wire token once one is substituted in nearby.
     *  Deliberately the same shape {@link #WIRE_TOKEN_LOOSE} accepts, minus the
     *  full-width braces (those are a MODEL RESPONSE reshaping tolerance, not something
     *  literal source text needs protecting against). Requires at least one digit, so
     *  ordinary brace usage ("{hello}", "{PLAYER}") is never touched. */
    private static final Pattern AMBIGUOUS_LITERAL =
            Pattern.compile("(?<!\\\\)[{]\\s*(/?)\\s*([A-Za-z_]*)\\s*(\\d[\\d_\\s]*)\\s*[}]");

    /** Fault-tolerant scan over a MODEL RESPONSE: tolerates full-width braces, stray
     *  internal whitespace and mixed case. A backslash immediately before the opening
     *  brace means "this is an escaped literal, not a real token" (see {@link #encode}),
     *  so it is excluded here and cleaned up separately in {@link #decode}. */
    private static final Pattern WIRE_TOKEN_LOOSE = Pattern.compile(
            "(?<!\\\\)[{｛]\\s*(/?)\\s*([A-Za-z_]*)\\s*(\\d[\\d_\\s]*)\\s*[}｝]");

    /** One escaped literal brace left by {@link #encode}: a backslash directly before an
     *  ASCII '{' or '}' that survived {@link #decode}'s token-resolution pass untouched. */
    private static final Pattern ESCAPED_BRACE = Pattern.compile("\\\\([{}])");

    /**
     * Result of {@link #encode}. {@code wire()} is the ASCII-safe request text; keep the
     * whole {@code Encoded} (not just the string) and hand it back to {@link #decode}
     * for the response this exact request produced — the mapping is per-call, not
     * global.
     */
    public static final class Encoded {
        private static final Encoded EMPTY = new Encoded(null, Map.of(), 0);

        private final String wire;
        private final Map<String, String> byWireKey; // normalized "[/]kind+digits" -> original ⟦...⟧ span
        private final int tokenCount;

        private Encoded(String wire, Map<String, String> byWireKey, int tokenCount) {
            this.wire = wire;
            this.byWireKey = byWireKey;
            this.tokenCount = tokenCount;
        }

        /** The text to actually send to the AI endpoint. */
        public String wire() {
            return wire;
        }

        /** Number of distinct {@code ⟦...⟧} spans this mapping knows how to restore
         *  (an open/close pair counts as two: they share a key but differ by the
         *  leading slash). 0 means {@link #encode} found nothing to convert. */
        public int tokenCount() {
            return tokenCount;
        }
    }

    /**
     * Replace every {@code ⟦...⟧} span in {@code internal} with a short ASCII wire
     * token, and escape any pre-existing literal text that would otherwise be
     * ambiguous with one. Returns {@code internal} itself (wrapped, {@code tokenCount()
     * == 0}) unchanged when there is nothing to do, so calling this on plain prose is a
     * cheap no-op.
     */
    public static Encoded encode(String internal) {
        if (internal == null) return Encoded.EMPTY;
        if (internal.indexOf('⟦') < 0) {
            // No real token anywhere in this text: nothing could ever be confused with
            // one, so literal brace text (if any) is left completely untouched — zero
            // overhead and zero risk for the overwhelmingly common plain-prose case.
            return new Encoded(internal, Map.of(), 0);
        }
        Matcher m = TOKEN.matcher(internal);
        StringBuilder out = new StringBuilder(internal.length() + 16);
        Map<String, String> spanToKey = new LinkedHashMap<>();
        Map<String, String> keyToSpan = new LinkedHashMap<>();
        int fallback = 0;
        int last = 0;
        while (m.find()) {
            out.append(escapeAmbiguousLiteral(internal.substring(last, m.start())));
            String span = m.group();
            String key = spanToKey.get(span);
            if (key == null) {
                key = buildWireKey(span);
                if (keyToSpan.containsKey(key) && !span.equals(keyToSpan.get(key))) {
                    // A different span produced the same mechanical key (should not
                    // happen for any current producer; kept so encoding is ALWAYS
                    // reversible even for an unanticipated future ⟦…⟧ shape).
                    String retry;
                    do {
                        retry = "x" + (fallback++);
                    } while (keyToSpan.containsKey(retry));
                    key = retry;
                }
                spanToKey.put(span, key);
                keyToSpan.put(key, span);
            }
            out.append('{').append(key).append('}');
            last = m.end();
        }
        out.append(escapeAmbiguousLiteral(internal.substring(last)));
        return new Encoded(out.toString(), keyToSpan, keyToSpan.size());
    }

    /** Mechanical, prefix-free wire key for one {@code ⟦...⟧} span's body: optional
     *  leading '/', lower-cased leading letter/underscore run, then the digit/underscore
     *  run. Never guesses a "kind" from a fixed enum, so any current or future reserved
     *  token shape (including the two-number {@code AI_LINE_i_j} hard-line slot) round
     *  trips correctly. */
    private static String buildWireKey(String span) {
        String body = span.substring(1, span.length() - 1).strip(); // strip ⟦ ⟧
        boolean closing = body.startsWith("/");
        String rest = closing ? body.substring(1).strip() : body;
        Matcher km = KIND_PREFIX.matcher(rest);
        String kind = km.find() ? km.group().toLowerCase(Locale.ROOT) : "";
        String digits = rest.substring(kind.length());
        String key = kind + digits;
        if (key.isEmpty()) key = "x"; // ⟦⟧ with nothing inside at all: pathological but reversible
        return closing ? "/" + key : key;
    }

    /** Backslash-escape any run of literal text that would otherwise look exactly like
     *  a real wire token once this call's tokens are substituted in around it. A plain
     *  brace that is not immediately followed by a digit-bearing token shape ({@code
     *  "{hello}"}, a bare {@code "{"} or {@code "}"}) is never touched — this is the
     *  ONLY thing encode() does to text outside a {@code ⟦...⟧} span. */
    private static String escapeAmbiguousLiteral(String literal) {
        if (literal == null || literal.indexOf('{') < 0) return literal;
        Matcher m = AMBIGUOUS_LITERAL.matcher(literal);
        if (!m.find()) return literal;
        StringBuilder out = new StringBuilder(literal.length() + 8);
        int last = 0;
        do {
            out.append(literal, last, m.start()).append('\\').append(literal, m.start(), m.end());
            last = m.end();
        } while (m.find());
        out.append(literal, last, literal.length());
        return out.toString();
    }

    /**
     * Reverse {@link #encode}: resolve every recognisable wire token in {@code wire}
     * back to its original {@code ⟦...⟧} span using {@code mapping}, then remove the
     * escaping {@link #encode} added around ambiguous literal text. A wire-shaped run
     * that is NOT a key in {@code mapping} (wrong digit, unknown kind, a token the model
     * duplicated or hallucinated) is left exactly as written — this class never guesses;
     * the caller's existing ⟦...⟧-domain validation is the single source of truth for
     * detecting loss, operating on the result exactly as it did before this codec
     * existed. {@code null}-safe; returns {@code wire} unchanged when there is nothing
     * this mapping could have produced.
     */
    public static String decode(String wire, Encoded mapping) {
        if (wire == null) return null;
        if (mapping == null || mapping.tokenCount == 0) {
            // encode() never touched this text (no ⟦...⟧ span anywhere in the request),
            // so it never escaped anything either — mirror that symmetrically instead
            // of unescaping a backslash the request never added.
            return wire;
        }
        if (wire.indexOf('{') < 0 && wire.indexOf('｛') < 0) {
            return unescapeLiteral(wire);
        }
        Matcher m = WIRE_TOKEN_LOOSE.matcher(wire);
        StringBuilder out = new StringBuilder(wire.length());
        int last = 0;
        while (m.find()) {
            out.append(wire, last, m.start());
            String slash = m.group(1);
            String kind = m.group(2).toLowerCase(Locale.ROOT);
            String digits = m.group(3).replaceAll("\\s+", "");
            String key = (slash.isEmpty() ? "" : "/") + kind + digits;
            String original = mapping.byWireKey.get(key);
            out.append(original != null ? original : m.group());
            last = m.end();
        }
        out.append(wire, last, wire.length());
        return unescapeLiteral(out.toString());
    }

    /** Remove exactly one escaping backslash before every remaining literal brace. */
    private static String unescapeLiteral(String text) {
        if (text == null || text.indexOf('\\') < 0) return text;
        return ESCAPED_BRACE.matcher(text).replaceAll("$1");
    }
}
