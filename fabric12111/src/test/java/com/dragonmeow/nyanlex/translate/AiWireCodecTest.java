package com.dragonmeow.nyanlex.translate;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiWireCodec} round-trip, escaping and fault-tolerance tests, plus its
 * integration with the EXISTING internal-format validator ({@code
 * OpenAiTranslator#tokensMatch}, package-private, reachable here because this test
 * lives in the same {@code translate} package). All inline, no network.
 */
class AiWireCodecTest {

    // ---- plain round trip: every known ⟦...⟧ kind, individually and mixed ----

    @Test
    void plainTextWithNoTokenIsUntouched() {
        String text = "Hyperion deals massive damage to nearby enemies.";
        AiWireCodec.Encoded enc = AiWireCodec.encode(text);
        assertEquals(text, enc.wire(), "no ⟦...⟧ span: identity, zero overhead");
        assertEquals(0, enc.tokenCount());
        assertEquals(text, AiWireCodec.decode(enc.wire(), enc));
    }

    @Test
    void nullIsHandledForBothDirections() {
        AiWireCodec.Encoded enc = AiWireCodec.encode(null);
        assertEquals(0, enc.tokenCount());
        assertEquals(null, AiWireCodec.decode(null, enc));
    }

    @Test
    void mtValueSlotRoundTrips() {
        assertRoundTrip("You dealt ⟦MT0⟧ damage in ⟦MT1⟧ seconds.");
    }

    @Test
    void bareNameSlotRoundTrips() {
        assertRoundTrip("⟦0⟧: hello ⟦1⟧!");
    }

    @Test
    void paragraphBreakRoundTrips() {
        assertRoundTrip("First line⟦PB0⟧Second line⟦PB1⟧Third line");
    }

    @Test
    void columnGapRoundTrips() {
        assertRoundTrip("NPC Sell Price: ⟦WS0⟧ ⟦MT0⟧ coins");
    }

    @Test
    void colourRunPairRoundTrips() {
        assertRoundTrip("⟦CS0⟧Red⟦/CS0⟧ Skull guarded by ⟦CS1⟧Warden⟦/CS1⟧");
    }

    @Test
    void hardLineWireOnlySlotRoundTrips() {
        // Same shape OpenAiTranslator#maskHardLines produces for a literal \r\n inside
        // one anchored unit (two embedded digits joined by '_', not a single trailing
        // number) — the one existing ⟦...⟧ kind that does NOT fit "letters+digits".
        assertRoundTrip("First paragraph⟦AI_LINE_0_0⟧Second paragraph⟦AI_LINE_0_1⟧Third");
    }

    @Test
    void everyKindMixedInOneRealisticTooltipLine() {
        assertRoundTrip("⟦CS0⟧⟦MT0⟧/⟦MT1⟧⟦/CS0⟧⟦WS0⟧⟦CS1⟧⟦/CS1⟧ dealt to ⟦0⟧⟦PB0⟧next line");
    }

    @Test
    void openAndCloseCsShareTheSameWireKey() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦CS3⟧X⟦/CS3⟧");
        assertEquals("{cs3}X{/cs3}", enc.wire(),
                "open/close pair must differ ONLY by the leading slash, matching an "
                        + "XML-style open/close tag the model already recognises");
    }

    @Test
    void repeatedIdenticalTokenReusesOneWireKey() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦MT0⟧ and ⟦MT0⟧ again");
        assertEquals("{mt0} and {mt0} again", enc.wire());
        assertEquals(1, enc.tokenCount());
        assertEquals("⟦MT0⟧ and ⟦MT0⟧ again", AiWireCodec.decode(enc.wire(), enc));
    }

    @Test
    void wireFormatUsesAsciiBracesNotTheByteFragmentingMathBrackets() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦MT0⟧");
        assertFalse(enc.wire().contains("⟦") || enc.wire().contains("⟧"),
                "the whole point: the wire text must never contain U+27E6/U+27E7");
        assertTrue(enc.wire().chars().allMatch(c -> c < 128), "wire text must be pure ASCII");
    }

    // ---- real anchored-batch-shaped text (context block + boundary anchors + units) ----

    @Test
    void anchoredBatchShapedTextRoundTrips() {
        String anchored = "86001 Hit ⟦CS0⟧⟦MT0⟧⟦/CS0⟧ for ⟦MT1⟧ damage 86002\n"
                + "86003 ⟦0⟧ joined the game⟦PB0⟧Welcome! 86004\n";
        assertRoundTrip(anchored);
        AiWireCodec.Encoded enc = AiWireCodec.encode(anchored);
        // Five-digit boundary anchors are plain digits already; the codec must not
        // touch them at all.
        assertTrue(enc.wire().contains("86001") && enc.wire().contains("86004"));
    }

    // ---- literal brace safety: source text containing '{', '<', '[' ----

    @Test
    void plainBraceTextWithNoDigitIsNeverEscaped() {
        // "{hello}" never matches the wire grammar (no digit), so it must be byte-for-byte
        // untouched, not merely round-trippable.
        String text = "Use {hello} and {PLAYER} as placeholders.";
        AiWireCodec.Encoded enc = AiWireCodec.encode(text);
        assertEquals(text, enc.wire(), "no digit inside the braces: not ambiguous, left alone");
    }

    @Test
    void angleAndSquareBracketsAreNeverTouched() {
        // The chosen wire syntax uses only '{' '}': '<' and '[' can never collide with it.
        String text = "if (x < 10) use [slot 1] please⟦MT0⟧";
        assertRoundTrip(text);
        AiWireCodec.Encoded enc = AiWireCodec.encode(text);
        assertTrue(enc.wire().contains("if (x < 10) use [slot 1] please"));
    }

    @Test
    void literalBareNumberBraceCoexistsWithARealBareNameToken() {
        // Source text already contains a leaked "{0}"-shaped format-string artifact
        // (a realistic mod-localisation bug) IN THE SAME STRING as a real protected-name
        // placeholder that also happens to be index 0. Both must come back exactly as
        // they went in — the literal "{0}" must NEVER be replaced by the protected name.
        String text = "Raw key leaked: {0} — said by ⟦0⟧";
        AiWireCodec.Encoded enc = AiWireCodec.encode(text);
        // Only the OPENING brace needs a guard backslash: it is what the decode-side
        // negative lookbehind checks, so a lone escaped '{' is already enough to keep
        // WIRE_TOKEN_LOOSE from ever starting a match on this literal run.
        assertTrue(enc.wire().contains("\\{0}"), "the literal, pre-existing \"{0}\" must be escaped: " + enc.wire());
        assertTrue(enc.wire().endsWith("{0}"), "the REAL name token must still be a bare wire {0}: " + enc.wire());
        String decoded = AiWireCodec.decode(enc.wire(), enc);
        assertEquals(text, decoded);
    }

    @Test
    void literalKindShapedBraceCoexistsWithARealMatchingToken() {
        String text = "Debug dump: {mt1} should not change — real value is ⟦MT1⟧";
        AiWireCodec.Encoded enc = AiWireCodec.encode(text);
        String decoded = AiWireCodec.decode(enc.wire(), enc);
        assertEquals(text, decoded, "the literal \"{mt1}\" text must not be corrupted into the live value");
    }

    @Test
    void literalBraceWithNoMatchingRealTokenStillRoundTrips() {
        // Ambiguous-SHAPED literal text, but this request has no matching real token at
        // all (no ⟦CS2⟧ anywhere) — still must escape/restore correctly, not leak "\{cs2}"
        // into what the user sees.
        String text = "Config says {cs2} but nothing here is colour-coded. ⟦MT0⟧";
        assertRoundTrip(text);
    }

    // ---- model-response fault tolerance: superficial reshaping is repaired ----

    @Test
    void decodeToleratesFullWidthBraces() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦MT0⟧ damage");
        String modelReply = "｛mt0｝ damage"; // ｛mt0｝
        assertEquals("⟦MT0⟧ damage", AiWireCodec.decode(modelReply, enc));
    }

    @Test
    void decodeToleratesInternalWhitespaceAndMixedCase() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦CS0⟧X⟦/CS0⟧");
        String modelReply = "{ CS0 }X{ / Cs0 }";
        assertEquals("⟦CS0⟧X⟦/CS0⟧", AiWireCodec.decode(modelReply, enc));
    }

    @Test
    void decodeToleratesSpaceAroundTheClosingSlash() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦PB0⟧");
        assertEquals("⟦PB0⟧", AiWireCodec.decode("{ pb0 }", enc));
    }

    // ---- unrecoverable damage: must be left for the existing validator to reject ----

    @Test
    void decodeNeverGuessesAWrongDigit() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦MT0⟧");
        String damaged = "{mt9} damage"; // wrong index: no such key in this mapping
        String decoded = AiWireCodec.decode(damaged, enc);
        assertEquals(damaged, decoded, "an unknown wire token must be left untouched, never guessed");
        assertFalse(decoded.contains("⟦"), "nothing was restored for a token this mapping never issued");
    }

    @Test
    void decodeNeverGuessesAForeignKind() {
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦MT0⟧");
        String damaged = "{zz0}";
        assertEquals(damaged, AiWireCodec.decode(damaged, enc));
    }

    @Test
    void decodeLeavesADroppedTokenMissing() {
        // The model silently dropped the CS close tag — decode cannot invent it; the
        // resulting internal-format text is simply missing ⟦/CS0⟧, exactly as if the
        // model had dropped ⟦/CS0⟧ under the OLD format.
        AiWireCodec.Encoded enc = AiWireCodec.encode("⟦CS0⟧Red⟦/CS0⟧");
        String decoded = AiWireCodec.decode("{cs0}Red", enc);
        assertEquals("⟦CS0⟧Red", decoded);
    }

    // ---- integration with the EXISTING internal-format validator ----

    @Test
    void roundTrippedTextStillPassesTheExistingValidator() {
        String source = "⟦CS0⟧Hit⟦/CS0⟧ for ⟦MT0⟧ damage to ⟦0⟧";
        AiWireCodec.Encoded enc = AiWireCodec.encode(source);
        // Simulate the model faithfully echoing the wire tokens back, in a different
        // (grammatically reordered) position, exactly like a real translation would:
        String modelWireReply = "{0} 被 {cs0}打中{/cs0} 造成 {mt0} 炷害";
        String decoded = AiWireCodec.decode(modelWireReply, enc);
        assertTrue(OpenAiTranslator.tokensMatch(source, decoded),
                "a faithful, merely reordered echo must still satisfy the pre-existing "
                        + "⟦...⟧-domain validator after decoding: " + decoded);
    }

    @Test
    void reshapedWireResponseFailsTheExistingValidatorAfterDecode() {
        // The model dropped the CS pair entirely and just translated the words — exactly
        // the kind of loss tokensMatch existed to catch before this codec, and still must.
        String source = "⟦CS0⟧Hit⟦/CS0⟧ for ⟦MT0⟧ damage";
        AiWireCodec.Encoded enc = AiWireCodec.encode(source);
        String modelWireReply = "打中造成 {mt0} 炷害"; // CS pair missing
        String decoded = AiWireCodec.decode(modelWireReply, enc);
        assertFalse(OpenAiTranslator.tokensMatch(source, decoded),
                "a dropped CS pair must still be rejected by the existing validator: " + decoded);
    }

    @Test
    void duplicatedWireTokenFailsTheExistingValidatorAfterDecode() {
        String source = "⟦MT0⟧ and ⟦MT1⟧";
        AiWireCodec.Encoded enc = AiWireCodec.encode(source);
        String modelWireReply = "{mt0} and {mt0}"; // MT1 lost, MT0 duplicated
        String decoded = AiWireCodec.decode(modelWireReply, enc);
        assertFalse(OpenAiTranslator.tokensMatch(source, decoded));
    }

    // ---- randomised / realistic-sample round trip ----

    @Test
    void randomisedSamplesAlwaysRoundTrip() {
        Random random = new Random(20261001L);
        String[] kinds = {"MT", "CS", "WS", "PB", ""};
        for (int sample = 0; sample < 500; sample++) {
            StringBuilder sb = new StringBuilder();
            int parts = 1 + random.nextInt(8);
            for (int p = 0; p < parts; p++) {
                switch (random.nextInt(4)) {
                    case 0 -> sb.append(randomWord(random));
                    case 1 -> {
                        String kind = kinds[random.nextInt(kinds.length)];
                        int idx = random.nextInt(5);
                        sb.append('⟦').append(kind).append(idx).append('⟧');
                    }
                    case 2 -> {
                        int idx = random.nextInt(5);
                        sb.append('⟦').append("CS").append(idx).append('⟧')
                                .append(randomWord(random))
                                .append('⟦').append("/CS").append(idx).append('⟧');
                    }
                    default -> sb.append(random.nextBoolean() ? "{weird" + random.nextInt(9) + "}"
                            : " <" + random.nextInt(9) + "> ");
                }
                sb.append(' ');
            }
            String text = sb.toString();
            assertRoundTrip(text);
        }
    }

    @Test
    void hypixelStyleAuctionLineSample() {
        assertRoundTrip("⟦CS0⟧⟦0⟧⟦/CS0⟧'s ⟦CS1⟧Hyperion⟦/CS1⟧ ⟦WS0⟧ ⟦MT0⟧ coins⟦PB0⟧Ends in ⟦MT1⟧");
    }

    @Test
    void hypixelStyleActionBarSample() {
        assertRoundTrip("⟦CS0⟧+⟦MT0⟧ Mana⟦/CS0⟧ ⟦WS0⟧ ⟦CS1⟧-⟦MT1⟧ Health⟦/CS1⟧");
    }

    // ---- helpers ----

    private static String randomWord(Random random) {
        String[] words = {"Hello", "World", "damage", "coins", "guarded", "by",
                "night", "伺服器", "傷害", "商店", "{plain}", "a/b"};
        return words[random.nextInt(words.length)];
    }

    private static void assertRoundTrip(String internal) {
        AiWireCodec.Encoded enc = AiWireCodec.encode(internal);
        String decoded = AiWireCodec.decode(enc.wire(), enc);
        assertEquals(internal, decoded, "round trip failed for: " + internal
                + "\n  wire=" + enc.wire());
    }
}
