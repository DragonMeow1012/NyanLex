package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Investigation harness for the "譯: failed (format/token lost)" debug-overlay symptom the
 * user sees ALMOST EVERY TIME on a real Hypixel SkyBlock session (OpenAI-compatible engine),
 * since the 2026-10-01 tooltip segment-cache feature landed (TooltipSegmentPlanner,
 * Trade/Stats/ScrollNameList composers, OpenAiTranslator context dedup, legacy lazy
 * conversion).
 *
 * <p>Unlike every existing structured-tooltip test ({@code TranslationServiceStructuredTooltipTest}),
 * which drives {@link TranslationService} with a simplified {@code Translator} fake that
 * resolves a dictionary lookup directly (bypassing {@link OpenAiTranslator}'s real anchor
 * numbering, JSON request/response envelope, and {@code tokensMatch}/{@code usable}
 * validation entirely), this harness drives the REAL {@link OpenAiTranslator} through an
 * inline fake {@link HttpTransport} that behaves like a rule-following AI: every {@code
 * ⟦...⟧} protocol token is copied back VERBATIM, in the same order, and only the
 * surrounding plain-English wording is "translated" (deterministically mapped to whole-word
 * CJK substitutes, never glued/partial). If segments still fail validation against this
 * provably rule-following responder, the bug is in OUR composition/templating/validation
 * pipeline, not in real-world AI imperfection.</p>
 */
class TokenLostReproTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String TARGET_LANG = "zh-TW";
    private static final Gson GSON = new Gson();

    // =====================================================================
    // "Perfect" fake AI transport: parses the REAL request JSON body (system+user
    // messages, exactly as OpenAiTranslator.buildRequestBody constructs them), finds
    // every anchored unit line ("<open> <wire> <close>"), and returns a response that
    // keeps every ⟦...⟧ token exactly as sent while replacing each run of ASCII
    // letters with a deterministic WHOLE-WORD CJK placeholder (never a half-kept
    // fragment, so TextFilter#isPartialTransliteration never fires on our own fake
    // output).
    // =====================================================================

    private static final Pattern ANCHORED_LINE = Pattern.compile("(?m)^(\\d{4,}) (.*) (\\d{4,})$");
    // AiWireCodec.encode() replaces every internal ⟦...⟧ span with a compact ASCII wire
    // token ({kind#} / {/kind#} / bare {#}) before the request ever leaves
    // OpenAiTranslator#translateChunk, so the REAL request body this fake transport reads
    // from the "user" message now contains that wire shape, not ⟦...⟧. Matching "{...}"
    // here (instead of the old "⟦...⟧") keeps this fake AI's token-vs-word split in sync
    // with what OpenAiTranslator actually sends over HTTP.
    private static final Pattern TOKEN_OR_WORD = Pattern.compile("\\{[^{}]*\\}|[A-Za-z]+");
    private static final String CJK_POOL =
            "的一是在不了有和人這中大為上個國我以要他時來用們生到作地於出就分對成會可主發年動同工也能下過子說產種面"
                    + "而方後多定行學法所民得經十三之進著等部度家電力裏如水化高自二理起小物現實加量都兩體制機當使點從業";

    private static String cjkFor(String word) {
        int idx = Math.floorMod(word.toLowerCase(Locale.ROOT).hashCode(), CJK_POOL.length() - 4);
        int len = Math.max(1, Math.min(4, (word.length() + 1) / 2));
        return CJK_POOL.substring(idx, idx + len);
    }

    /** Pluggable per-unit transform so deformation scenarios can reuse the same envelope/
     *  anchor-parsing plumbing while only changing what comes back for the unit body. */
    interface WireTransform {
        String transform(String wire);
    }

    static String perfectTranslateWire(String wire) {
        Matcher m = TOKEN_OR_WORD.matcher(wire);
        StringBuilder out = new StringBuilder(wire.length());
        int cursor = 0;
        while (m.find()) {
            out.append(wire, cursor, m.start());
            String piece = m.group();
            out.append(piece.startsWith("{") ? piece : cjkFor(piece));
            cursor = m.end();
        }
        out.append(wire, cursor, wire.length());
        return out.toString();
    }

    private static final class RuleFollowingAiTransport implements HttpTransport {
        final List<String> requestBodies = new ArrayList<>();
        final List<String> responseBodies = new ArrayList<>();
        final WireTransform transform;

        RuleFollowingAiTransport() {
            this(TokenLostReproTest::perfectTranslateWire);
        }

        RuleFollowingAiTransport(WireTransform transform) {
            this.transform = transform;
        }

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            requestBodies.add(body);
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            JsonArray messages = root.getAsJsonArray("messages");
            String userContent = null;
            for (JsonElement el : messages) {
                JsonObject m = el.getAsJsonObject();
                if ("user".equals(m.get("role").getAsString())) {
                    userContent = m.get("content").getAsString();
                }
            }
            if (userContent == null) throw new IOException("no user message in request");
            String translatedContent = translateAnchoredContent(userContent);
            JsonObject respMsg = new JsonObject();
            respMsg.addProperty("role", "assistant");
            respMsg.addProperty("content", translatedContent);
            JsonObject choice = new JsonObject();
            choice.add("message", respMsg);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject resp = new JsonObject();
            resp.add("choices", choices);
            String out = GSON.toJson(resp);
            responseBodies.add(out);
            return out;
        }

        private String translateAnchoredContent(String userContent) {
            Matcher m = ANCHORED_LINE.matcher(userContent);
            StringBuilder out = new StringBuilder();
            int unitCount = 0;
            while (m.find()) {
                if (out.length() > 0) out.append('\n');
                out.append(m.group(1)).append(' ').append(transform.transform(m.group(2)))
                        .append(' ').append(m.group(3));
                unitCount++;
            }
            if (unitCount == 0) {
                throw new IllegalStateException(
                        "RuleFollowingAiTransport found no anchored unit in user content:\n" + userContent);
            }
            return out.toString();
        }
    }

    private static OpenAiTranslator realTranslator(HttpTransport transport) {
        return new OpenAiTranslator(transport,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
    }

    private static TranslationDebugLog newDebugLog() {
        return new TranslationDebugLog(() -> true);
    }

    private static TranslationService newService(OpenAiTranslator translator, TranslationDebugLog log) {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = TARGET_LANG;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        cfg.aiScoreboard = true;
        cfg.debugTranslationOverlay = true;
        TranslationCache google = new TranslationCache(translator, TARGET_LANG, DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, TARGET_LANG, DIRECT, 100);
        ai.setDebugLog("AI", log);
        TranslationService service = new TranslationService(cfg, google, ai);
        service.setBatchWindowMs(() -> 0);
        return service;
    }

    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
        s.flushBatches();
    }

    /** Fails loudly with every FAILED/KEEP_ORIGINAL debug entry's reason, so a reproduction
     *  shows exactly which validation rejected the rule-following AI's response. */
    private static void assertNoFailures(TranslationDebugLog log, String scenario) {
        List<TranslationDebugLog.Entry> entries = log.snapshot(200);
        List<String> bad = new ArrayList<>();
        for (TranslationDebugLog.Entry e : entries) {
            if (e.status() == TranslationDebugLog.Status.FAILED
                    || e.status() == TranslationDebugLog.Status.KEEP_ORIGINAL) {
                bad.add("[" + e.status() + "/" + e.failureReason() + "] text=" + e.text()
                        + " translation=" + e.translation());
            }
        }
        if (!bad.isEmpty()) {
            fail(scenario + ": rule-following AI response was rejected by our own validation:\n"
                    + String.join("\n", bad));
        }
    }

    // =====================================================================
    // Scenario A: Hyperion — 3 scroll names (SCROLL) + Ability block (ABILITY) +
    // Seller/Buy it now (TRADE), all PB-joined with no blank row.
    // =====================================================================
    @Test
    void hyperionScrollsAbilityAndTradeAllSurviveARuleFollowingAiRoundTrip() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String hyperion = ParagraphModel.join(List.of(
                "LEGENDARY",
                "● Implosion", "● Wither Shield", "● Shadow Warp",
                "Ability: Wither Impact",
                "Teleports you forward.",
                "Cooldown: 20s",
                "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));

        s.warmTooltipBatch(List.of(hyperion));
        pump(s);

        assertNoFailures(log, "Hyperion first view");

        TranslationDecision d = s.translateItemLine(hyperion);
        assertTrue(d.changed(), "every segment should now be resolved and cached: "
                + "composed=" + d.translated());
        String out = d.translated();
        // PBn/CSn GLUE-level tokens are expected to survive translateItemLine's own output
        // (FabricTextStyle strips/renders them later, see
        // TranslationServiceStructuredTooltipTest#firstViewSendsEverythingMissingAsOneRequest).
        // What must NEVER remain is an unrestored NameMasker/TemplateText placeholder.
        assertFalse(out.contains("⟦0⟧") || out.matches(".*⟦MT\\d+⟧.*"),
                "no unrestored name/value placeholder should reach display: " + out);
        assertTrue(out.contains("DragonMeow"), "seller name restored");
        assertTrue(out.contains("1,000,000"), "price restored");
    }

    // =====================================================================
    // Scenario B: TRADE only — "Seller: ⟦0⟧" / "Buy it now: ⟦MT0⟧ coins".
    // =====================================================================
    @Test
    void tradeFieldsSurviveARuleFollowingAiRoundTrip() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String text = ParagraphModel.join(List.of(
                "LEGENDARY", "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        s.warmTooltipBatch(List.of(text));
        pump(s);

        assertNoFailures(log, "TRADE fields");
        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        assertTrue(d.translated().contains("DragonMeow"));
        assertTrue(d.translated().contains("1,000,000"));
    }

    // =====================================================================
    // Scenario C: STATS only — "Strength: +⟦MT0⟧" style rows.
    // =====================================================================
    @Test
    void statsRowsSurviveARuleFollowingAiRoundTrip() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String text = ParagraphModel.join(List.of(
                "LEGENDARY", "Strength: +10", "Defense: +20", "Seller: DragonMeow"));
        s.warmTooltipBatch(List.of(text));
        pump(s);

        assertNoFailures(log, "STATS rows");
        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        assertTrue(d.translated().contains("+10"));
        assertTrue(d.translated().contains("+20"));
    }

    // =====================================================================
    // Scenario D: enchant list — "Sharpness VII, Mending I" (composeEnchantList, the
    // ORIGINAL P2 path the new segment composers all reuse via resolveEnchantName).
    // =====================================================================
    @Test
    void enchantListSurvivesARuleFollowingAiRoundTrip() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String text = "Sharpness VII, Mending I, Unbreaking III";
        s.warmTooltipBatch(List.of(text));
        pump(s);

        assertNoFailures(log, "enchant list");
        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        assertTrue(d.translated().contains("VII"));
        assertTrue(d.translated().contains("III"));
    }

    // =====================================================================
    // Scenario E: action bar / fixed-column HUD row with multiple ⟦MTn⟧ + ⟦WSn⟧ slots
    // (two-or-more-space gaps become WS layout tokens; numbers become MT tokens). This
    // drives TranslationCache directly with the real OpenAiTranslator, the same way a
    // scoreboard/actionbar line reaches it via TranslationService.warmScoreboardBatch.
    // =====================================================================
    @Test
    void actionBarRowWithMultipleMtAndWsSlotsSurvivesARuleFollowingAiRoundTrip() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        // Two fixed columns: "Mana" value and "Health" value, separated by a wide gap.
        String text = "Mana: 1200/1500    Health: 800/800";
        s.warmScoreboardBatch(List.of(text));
        pump(s);

        assertNoFailures(log, "action bar WS+MT row");
        TranslationDecision d = s.translateScoreboardLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        assertTrue(d.translated().contains("1200/1500"));
        assertTrue(d.translated().contains("800/800"));
    }

    // =====================================================================
    // Scenario F: multiple colour runs (⟦CSn⟧...⟦/CSn⟧) spanning a rarity line AND a
    // scroll name, per-letter-coloured names (gradient_name_shredding memory).
    // =====================================================================
    @Test
    void multipleColourRunsSurviveARuleFollowingAiRoundTrip() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String text = ParagraphModel.join(List.of(
                "⟦CS0⟧LEGENDARY⟦/CS0⟧",
                "● ⟦CS1⟧Implosion⟦/CS1⟧",
                "● ⟦CS2⟧Wither⟦/CS2⟧⟦CS3⟧Shield⟦/CS3⟧",
                "Seller: DragonMeow"));
        s.warmTooltipBatch(List.of(text));
        pump(s);

        assertNoFailures(log, "multi-CS colour runs");
        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        String out = d.translated();
        assertTrue(out.contains("⟦CS0⟧"));
        assertTrue(out.contains("⟦CS1⟧"));
        assertTrue(out.contains("⟦CS2⟧"));
        assertTrue(out.contains("⟦CS3⟧"));
        assertFalse(out.contains("MT_STYLE_FALLBACK"),
                "TextFilter's internal style-fallback sentinel must never leak into displayed text");
    }

    // =====================================================================
    // Root-cause regression: an icon character (e.g. Hypixel's "●" scroll/ability bullet)
    // sitting OUTSIDE a ⟦CSn⟧ colour run, on a segment that also carries an ⟦MTn⟧ value
    // slot, used to permanently fail TranslationCache#writeStyleProjection's matchingCsShape
    // check (TextFilter#isLayoutOrPunctuationOnly did not treat a decorative symbol as
    // "layout only", so csShape() rejected the row as an unparseable shape and the EXACT
    // colour-topology row was never cached) -- forcing TranslationService#decide's
    // style-fallback branch FOREVER for that exact text, not just on the first frame. A
    // single top-level consumer (FabricTextStyle#markedChat's own isStyleFallback branch)
    // re-anchors that safely, but composeStructuredTooltip's resolveEnchantName spliced the
    // raw \u0000-prefixed value into the MIDDLE of a larger composed paragraph, where no
    // startsWith()-based consumer could ever detect/strip it: the literal "MT_STYLE_FALLBACK"
    // sentinel leaked onto the player's screen AND the segment's own colour markers were
    // silently dropped. Fixed in two places (see TextFilter#isLayoutOrPunctuationOnly and
    // TranslationService#resolveEnchantName): the root cause (decorative symbols now count
    // as layout-only, so the exact projection is cached and composes WITH colour intact) and
    // a defence in depth (a still-style-fallback value is treated as "not ready yet" instead
    // of ever being spliced into a larger structure).
    // =====================================================================
    @Test
    void iconPrefixedColouredNameRowComposesWithColourIntactNotAStyleFallbackLeak() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);
        String text = ParagraphModel.join(List.of(
                "⟦CS0⟧LEGENDARY⟦/CS0⟧",
                "● ⟦CS1⟧Implosion⟦/CS1⟧",
                "Seller: DragonMeow"));
        s.warmTooltipBatch(List.of(text));
        pump(s);
        assertNoFailures(log, "icon-prefixed coloured name row");

        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        String out = d.translated();
        assertFalse(out.contains("MT_STYLE_FALLBACK"),
                "the style-fallback sentinel must never leak into displayed text: " + out);
        assertTrue(out.contains("⟦CS0⟧") && out.contains("⟦/CS0⟧"), "rarity colour run survives");
        assertTrue(out.contains("⟦CS1⟧") && out.contains("⟦/CS1⟧"),
                "the icon-prefixed name's OWN colour run survives: " + out);
        assertTrue(out.contains("●"), "the decorative icon itself is preserved: " + out);
        assertTrue(out.contains("DragonMeow"));

        // Persistence check: this must not be a lucky first-frame result -- repeat renders
        // (and more cache-flush ticks) must keep returning the SAME, correctly composed,
        // colour-intact value, never regressing back to a style-fallback leak.
        for (int i = 0; i < 10; i++) {
            pump(s);
            TranslationDecision again = s.translateItemLine(text);
            assertTrue(again.changed());
            assertFalse(again.translated().contains("MT_STYLE_FALLBACK"));
            assertTrue(again.translated().contains("⟦CS1⟧"));
        }
    }

    @Test
    void twoAdjacentColourRunsGluedWithNoSpaceComposeCorrectly() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);
        // Per-word colour split with no space between the two CS runs (gradient-name style).
        String text = ParagraphModel.join(List.of(
                "● ⟦CS2⟧Wither⟦/CS2⟧⟦CS3⟧Shield⟦/CS3⟧",
                "Seller: DragonMeow"));
        s.warmTooltipBatch(List.of(text));
        pump(s);
        assertNoFailures(log, "two adjacent colour runs glued with no space");

        TranslationDecision d = s.translateItemLine(text);
        assertTrue(d.changed(), "composed=" + d.translated());
        String out = d.translated();
        assertFalse(out.contains("MT_STYLE_FALLBACK"));
        assertTrue(out.contains("⟦CS2⟧") && out.contains("⟦CS3⟧"));
    }

    // =====================================================================
    // Common AI deformations: confirm the EXISTING tolerance/rejection behaviour so a fix
    // (if any) does not change intended rejections into silent corruption.
    // =====================================================================

    /** Extra internal whitespace inside a wire token ("{ mt0 }") — AiWireCodec's
     *  WIRE_TOKEN_LOOSE decode pattern already allows \s* inside, so this must ALREADY be
     *  tolerated. */
    @Test
    void whitespaceInsideTokensIsAlreadyTolerated() {
        WireTransform withSpaces = wire -> {
            String translated = perfectTranslateWire(wire);
            // Re-inject internal whitespace into every {...} wire token the AI echoed back.
            return translated.replaceAll(
                    "\\{(/?)\\s*([a-z_]*)\\s*(\\d+)\\}",
                    "{ $1$2$3 }");
        };
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport(withSpaces);
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String text = ParagraphModel.join(List.of(
                "LEGENDARY", "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        s.warmTooltipBatch(List.of(text));
        pump(s);

        assertNoFailures(log, "whitespace-padded tokens");
        assertTrue(s.translateItemLine(text).changed());
    }

    /** A wire token reshaped into a CJK look-alike bracket ("『mt0』" instead of
     *  "{mt0}") must be REJECTED, never silently accepted as a match. AiWireCodec#decode
     *  only resolves the real "{...}"/"｛...｝" wire shapes, so a CJK-bracket mangling is
     *  left as literal text in the decoded internal-format string, which then fails
     *  OpenAiTranslator#tokensMatch's ⟦MT0⟧ multiset comparison exactly like the old
     *  direct ⟦...⟧ reshaping used to. Confirms the correct behaviour is "fail safely and
     *  keep the original", not corruption. */
    @Test
    void reshapedCjkBracketTokenIsRejectedNotSilentlyAccepted() {
        WireTransform reshape = wire -> {
            String translated = perfectTranslateWire(wire);
            // Only reshape the FIRST mt-style wire token found, leaving the rest of the
            // protocol intact, mirroring a plausible partial-corruption AI slip.
            return translated.replaceFirst(
                    "\\{(/?)(mt)(\\d+)\\}", "『$1$2$3』");
        };
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport(reshape);
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String text = ParagraphModel.join(List.of(
                "LEGENDARY", "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        s.warmTooltipBatch(List.of(text));
        pump(s);

        boolean sawFormatTokenLost = log.snapshot(200).stream()
                .anyMatch(e -> e.status() == TranslationDebugLog.Status.FAILED
                        && e.failureReason() != null
                        && e.failureReason().contains("format/token lost"));
        assertTrue(sawFormatTokenLost,
                "a reshaped protocol token must be classified as format/token lost, not accepted");
        // Original must still display (fail-safe), never a corrupted half-translation with
        // the reshaped debris baked in. TranslationDecision.translated() is null/unchanged
        // when nothing resolved -- the fail-safe property is "it is never the reshaped
        // debris", which an unchanged (null-translated) decision already satisfies.
        TranslationDecision after = s.translateItemLine(text);
        if (after.changed()) {
            assertFalse(after.translated().contains("『"));
        }
    }

    // =====================================================================
    // Candidate (c): several DIFFERENT tooltips' segments landing in the SAME windowed
    // HTTP request (rapid multi-hover / auction-house grid rendering many rows per tick).
    // Checks that OpenAiTranslator's context-grouping/reordering never cross-assigns one
    // item's segment value to a DIFFERENT item.
    // =====================================================================
    @Test
    void multipleDifferentItemsInOneWindowedBatchNeverCrossAssignSegments() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        String hyperion = ParagraphModel.join(List.of(
                "LEGENDARY", "● Implosion", "● Wither Shield", "● Shadow Warp",
                "Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        String midas = ParagraphModel.join(List.of(
                "LEGENDARY", "Strength: +25", "Defense: +10", "Seller: Steve",
                "Buy it now: 50,000,000 coins"));
        String terminator = ParagraphModel.join(List.of(
                "LEGENDARY", "● Ender Slayer", "● Smoldering",
                "Seller: Alex", "Buy it now: 2,000,000 coins"));

        // All three hovered within the same tick, before any flush drains the queue --
        // exactly the "auction house grid" scenario candidate (c) describes.
        s.warmTooltipBatch(List.of(hyperion));
        s.warmTooltipBatch(List.of(midas));
        s.warmTooltipBatch(List.of(terminator));
        pump(s);

        assertNoFailures(log, "three different items in one windowed batch");
        assertEquals(1, transport.requestBodies.size(),
                "the windowed collector should merge all three items' segments into ONE HTTP request");

        TranslationDecision dh = s.translateItemLine(hyperion);
        TranslationDecision dm = s.translateItemLine(midas);
        TranslationDecision dt = s.translateItemLine(terminator);
        assertTrue(dh.changed(), "hyperion composed=" + dh.translated());
        assertTrue(dm.changed(), "midas composed=" + dm.translated());
        assertTrue(dt.changed(), "terminator composed=" + dt.translated());

        assertTrue(dh.translated().contains("DragonMeow"), "hyperion keeps its OWN seller");
        assertFalse(dh.translated().contains("Steve") || dh.translated().contains("Alex"),
                "hyperion must not pick up another item's seller: " + dh.translated());
        assertTrue(dm.translated().contains("Steve") && dm.translated().contains("+25")
                && dm.translated().contains("+10"), "midas composed=" + dm.translated());
        assertFalse(dm.translated().contains("DragonMeow") || dm.translated().contains("Alex"),
                "midas must not pick up another item's seller: " + dm.translated());
        assertTrue(dt.translated().contains("Alex"), "terminator composed=" + dt.translated());
        assertFalse(dt.translated().contains("DragonMeow") || dt.translated().contains("Steve"),
                "terminator must not pick up another item's seller: " + dt.translated());
    }

    // =====================================================================
    // 2026-10-02 real-traffic regression (scratchpad/live-ai/LIVE-RESULT.md, run 4,
    // gpt-5.4-mini against the user's real API key): a genuine, reproducible failure —
    // a 7-display-line "Ability:" block whose English word-wrap falls mid-phrase made the
    // model reorder for natural Chinese word order and silently drop/renumber ONE ⟦PBn⟧
    // boundary (6 PB tokens in the source, only 5 came back) while every ⟦MTn⟧ value slot
    // round-tripped perfectly. OpenAiTranslator correctly REJECTS this (never a silent
    // corruption), but — before the 2026-10-02 fix — stamped the generic "format/token
    // lost" reason because tokensMatch's ANY_TOKEN multiset check is what actually caught
    // it, pre-empting TranslationCache#failureReasonFor's own more precise
    // "paragraph lost" diagnosis (which never even ran, since that method prefers an
    // already-set failureReason). This test replays the EXACT captured source/response
    // pair and asserts the per-unit reason is now "paragraph lost", not the generic one.
    // =====================================================================
    @Test
    void droppedParagraphBreakInAnAbilityBlockIsClassifiedAsParagraphLostNotFormatTokenLost() {
        // Exact internal-format source from the live capture (6 PB tokens: pb0..pb5).
        String source = "Ability: Reaper Strike RIGHT CLICK ⟦PB0⟧ Deal a devastating blow, dealing "
                + "⟦PB1⟧ ⟦MT0⟧ damage to all nearby ⟦PB2⟧ enemies. ⟦PB3⟧ Mark struck enemies for "
                + "⟦MT1⟧. ⟦PB4⟧ Mana cost: ⟦MT2⟧ ⟦PB5⟧ Cooldown: ⟦MT3⟧";
        // Exact garbled wire-format response body the live model actually returned (5 PB
        // tokens: pb0..pb4 -- {pb5} is simply gone and everything after the gap was
        // renumbered down by one instead of keeping the original indices).
        String garbledWireBody = "技能：收割者猛擊 右鍵 {pb0} 造成毀滅性一擊，對附近所有 {pb1} 個敵人造成 {mt0} "
                + "點傷害。 {pb2} 標記被擊中的敵人 {mt1}。 {pb3} 魔力消耗：{mt2} {pb4} 冷卻時間：{mt3}";

        HttpTransport transport = new ExactGarbledResponseTransport(garbledWireBody);
        OpenAiTranslator translator = realTranslator(transport);

        List<TranslationResult> results;
        try {
            results = translator.translateBatch(List.of(source), TARGET_LANG);
        } catch (Exception e) {
            throw new AssertionError("real OpenAiTranslator round trip failed unexpectedly", e);
        }

        assertEquals(1, results.size());
        TranslationResult result = results.get(0);
        assertFalse(result.translatedText() != null && !result.translatedText().isEmpty()
                && result.failureReason() == null,
                "a dropped PB boundary must never be silently accepted");
        assertEquals("paragraph lost", result.failureReason(),
                "a dropped/renumbered ⟦PBn⟧ must be classified 'paragraph lost', not the "
                        + "generic 'format/token lost' -- actual=" + result.failureReason());
    }

    // =====================================================================
    // 2026-10-02: reproduction of the user's exact SCREENSHOT SHAPE ("[AI #2]"/"[AI #15]":
    // unit 0 of a multi-unit request succeeds, every later unit in the SAME physical
    // response fails) using a plausible, data-grounded degradation -- scratchpad/live-ai's
    // one real captured failure was exactly one silently-dropped placeholder token on an
    // otherwise-correct response (see the test above); this scenario applies that same
    // "drop the last placeholder" degradation to every unit EXCEPT the first one in a
    // physical batch, modelling a weaker/cheaper model's attention degrading after the
    // first unit (scratchpad/live-ai's hypothesis for why gpt-5.4-mini could not reproduce
    // it live: the user's shipped DEFAULT engine is a much smaller model). Confirms
    // OpenAiTranslator#retryIsolatedFailures (the 2026-10-02 defence-in-depth fix) actually
    // recovers this exact failure shape: a degraded unit's isolated single-item retry
    // request has it at position 0 of ITS OWN request, so the SAME transport logic no
    // longer degrades it.
    // =====================================================================
    @Test
    void positionZeroSucceedsRestDegradeInALongBatchIsRecoveredByIsolatedRetry() {
        DegradingAfterFirstPositionTransport transport = new DegradingAfterFirstPositionTransport();
        TranslationDebugLog log = newDebugLog();
        TranslationService s = newService(realTranslator(transport), log);

        // Position 0: a plain item name with NO protocol token at all, exactly like the
        // user's "Mosquito Shortbow" (request #15) and "Precise Bonus [...]" (request #2)
        // unit-0 successes.
        String line0 = "Mosquito Shortbow";
        // Positions 1-4: a mix of MT-only and PB+MT content, exactly the heterogeneous
        // segment shapes TooltipSegmentPlanner produces from one real tooltip.
        String line1 = "Strength: +10";
        String line2 = ParagraphModel.join(List.of("Seller: DragonMeow", "Buy it now: 1,000,000 coins"));
        String line3 = "Defense: +20";
        String line4 = "Crit Chance: +15%";

        // Five independent segment-level requests queued inside the SAME windowed batch,
        // exactly like TooltipSegmentPlanner decomposing one hovered tooltip into several
        // independent cache units that all flush together as ONE physical HTTP request.
        s.warmTooltipBatch(List.of(line0));
        s.warmTooltipBatch(List.of(line1));
        s.warmTooltipBatch(List.of(line2));
        s.warmTooltipBatch(List.of(line3));
        s.warmTooltipBatch(List.of(line4));
        pump(s);

        assertTrue(transport.physicalRequestCount > 1,
                "the original 5-unit batch plus at least one isolated retry for a degraded "
                        + "unit; physicalRequestCount=" + transport.physicalRequestCount);

        TranslationDecision d0 = s.translateItemLine(line0);
        TranslationDecision d1 = s.translateItemLine(line1);
        TranslationDecision d2 = s.translateItemLine(line2);
        TranslationDecision d3 = s.translateItemLine(line3);
        TranslationDecision d4 = s.translateItemLine(line4);

        assertTrue(d0.changed(), "position 0 was never degraded: composed=" + d0.translated());
        assertTrue(d1.changed(), "degraded position recovered via isolated retry: composed=" + d1.translated());
        assertTrue(d2.changed(), "degraded position recovered via isolated retry: composed=" + d2.translated());
        assertTrue(d3.changed(), "degraded position recovered via isolated retry: composed=" + d3.translated());
        assertTrue(d4.changed(), "degraded position recovered via isolated retry: composed=" + d4.translated());
    }

    /** Within EVERY physical HTTP call, anchored unit 0 translates perfectly; every later
     *  unit has its LAST {@code {kind#}} wire token silently dropped (a token-free unit at
     *  a later position, if any, is therefore left perfect too -- there is nothing to
     *  drop, mirroring that a genuinely empty/trivial line has no placeholder for a real
     *  model to lose either). Degradation is keyed purely by POSITION WITHIN THIS CALL, so
     *  a unit that becomes position 0 of its own later isolated-retry request is no longer
     *  degraded -- that is the whole point of this scenario. */
    private static final class DegradingAfterFirstPositionTransport implements HttpTransport {
        final List<String> requestBodies = new ArrayList<>();
        int physicalRequestCount = 0;
        private static final Pattern WIRE_TOKEN = Pattern.compile("\\{[^{}]*\\}");

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            requestBodies.add(body);
            physicalRequestCount++;
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            JsonArray messages = root.getAsJsonArray("messages");
            String userContent = null;
            for (JsonElement el : messages) {
                JsonObject m = el.getAsJsonObject();
                if ("user".equals(m.get("role").getAsString())) {
                    userContent = m.get("content").getAsString();
                }
            }
            if (userContent == null) throw new IOException("no user message in request");
            Matcher m = ANCHORED_LINE.matcher(userContent);
            StringBuilder out = new StringBuilder();
            int position = 0;
            while (m.find()) {
                if (out.length() > 0) out.append('\n');
                String translated = perfectTranslateWire(m.group(2));
                if (position > 0) translated = dropLastToken(translated);
                out.append(m.group(1)).append(' ').append(translated).append(' ').append(m.group(3));
                position++;
            }
            if (position == 0) {
                throw new IllegalStateException("no anchored unit found:\n" + userContent);
            }
            JsonObject respMsg = new JsonObject();
            respMsg.addProperty("role", "assistant");
            respMsg.addProperty("content", out.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", respMsg);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject resp = new JsonObject();
            resp.add("choices", choices);
            return GSON.toJson(resp);
        }

        private static String dropLastToken(String wire) {
            Matcher tm = WIRE_TOKEN.matcher(wire);
            int lastStart = -1;
            int lastEnd = -1;
            while (tm.find()) {
                lastStart = tm.start();
                lastEnd = tm.end();
            }
            if (lastStart < 0) return wire; // nothing to drop
            return wire.substring(0, lastStart) + wire.substring(lastEnd);
        }
    }

    /** Parses the real anchored request (exactly like {@link RuleFollowingAiTransport}) and
     *  echoes back {@code garbledWireBody} wrapped in the SAME anchor numbers the real
     *  request used, regardless of what anchor OpenAiTranslator happened to pick. */
    private static final class ExactGarbledResponseTransport implements HttpTransport {
        private final String garbledWireBody;

        ExactGarbledResponseTransport(String garbledWireBody) {
            this.garbledWireBody = garbledWireBody;
        }

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            JsonArray messages = root.getAsJsonArray("messages");
            String userContent = null;
            for (JsonElement el : messages) {
                JsonObject m = el.getAsJsonObject();
                if ("user".equals(m.get("role").getAsString())) {
                    userContent = m.get("content").getAsString();
                }
            }
            if (userContent == null) throw new IOException("no user message in request");
            Matcher m = ANCHORED_LINE.matcher(userContent);
            if (!m.find()) {
                throw new IllegalStateException("no anchored unit found:\n" + userContent);
            }
            String translatedContent = m.group(1) + ' ' + garbledWireBody + ' ' + m.group(3);
            JsonObject respMsg = new JsonObject();
            respMsg.addProperty("role", "assistant");
            respMsg.addProperty("content", translatedContent);
            JsonObject choice = new JsonObject();
            choice.add("message", respMsg);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject resp = new JsonObject();
            resp.add("choices", choices);
            return GSON.toJson(resp);
        }
    }
}
