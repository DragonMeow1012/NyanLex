package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.TranslationDebugLog;
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
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the findings of the live OpenAI-compatible end-to-end run
 * (R1 fragmented colour units, R4 leading icon token, R5 seller names, R6 partial
 * display, R7 non-zero based colour numbering). Every fake here is inline: a tiny
 * rule-following AI transport behind the REAL {@link OpenAiTranslator}.
 */
class LiveE2eFindingsTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String TARGET = "zh-TW";
    private static final Gson GSON = new Gson();
    private static final Pattern ANCHORED = Pattern.compile("(?m)^(\\d{4,}) (.*) (\\d{4,})$");
    private static final Pattern WORD = Pattern.compile("\\{[^{}]*\\}|[A-Za-z]+");
    private static final String POOL =
            "的一是在不了有和人這中大為上個國我以要他時來用們生到作地於出就分對成會可主發年動同工也能下過";

    /** Fake provider: records every request body, answers each anchored unit. */
    private static final class FakeAi implements HttpTransport {
        final List<String> bodies = new ArrayList<>();
        /** Wire texts of every unit ever sent (one entry per unit, in order). */
        final List<String> sentUnits = new ArrayList<>();
        UnaryOperator<String> transform = FakeAi::translateWords;

        static String translateWords(String wire) {
            Matcher m = WORD.matcher(wire);
            StringBuilder out = new StringBuilder();
            int cursor = 0;
            while (m.find()) {
                out.append(wire, cursor, m.start());
                String piece = m.group();
                if (piece.startsWith("{")) {
                    out.append(piece);
                } else {
                    int idx = Math.floorMod(piece.toLowerCase(Locale.ROOT).hashCode(), POOL.length() - 3);
                    out.append(POOL, idx, idx + Math.max(1, Math.min(3, (piece.length() + 1) / 2)));
                }
                cursor = m.end();
            }
            out.append(wire, cursor, wire.length());
            return out.toString();
        }

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            bodies.add(body);
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            String user = null;
            for (JsonElement el : root.getAsJsonArray("messages")) {
                JsonObject m = el.getAsJsonObject();
                if ("user".equals(m.get("role").getAsString())) user = m.get("content").getAsString();
            }
            Matcher m = ANCHORED.matcher(user == null ? "" : user);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                if (out.length() > 0) out.append('\n');
                sentUnits.add(m.group(2));
                out.append(m.group(1)).append(' ').append(transform.apply(m.group(2)))
                        .append(' ').append(m.group(3));
            }
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", out.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", msg);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject resp = new JsonObject();
            resp.add("choices", choices);
            return GSON.toJson(resp);
        }
    }

    private static TranslationService service(FakeAi ai, TranslationDebugLog log) {
        return service(ai, log, DisplayMode.TRANSLATION);
    }

    private static TranslationService service(FakeAi ai, TranslationDebugLog log, DisplayMode tooltipMode) {
        OpenAiTranslator translator = new OpenAiTranslator(ai,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = TARGET;
        cfg.tooltipMode = tooltipMode;
        cfg.aiTooltip = true;
        TranslationCache google = new TranslationCache(translator, TARGET, DIRECT, 100);
        TranslationCache aiCache = new TranslationCache(translator, TARGET, DIRECT, 100);
        if (log != null) aiCache.setDebugLog("AI", log);
        TranslationService s = new TranslationService(cfg, google, aiCache);
        s.setBatchWindowMs(() -> 0);
        return s;
    }

    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
        s.flushBatches();
    }

    // ------------------------------------------------------------------ R7

    @Test
    void r7NonZeroBasedColourRowIsQueuedOrExplained() {
        FakeAi ai = new FakeAi();
        TranslationDebugLog log = new TranslationDebugLog(() -> true);
        TranslationService s = service(ai, log);
        String row = "⟦CS6⟧Crit Damage:⟦/CS6⟧ ⟦CS7⟧+50%⟦/CS7⟧";
        s.warmTooltipBatch(List.of(row));
        pump(s);
        s.translateItemLine(row);
        pump(s);
        assertFalse(ai.sentUnits.isEmpty(), "a stat row with CS6/CS7 numbering must be requested");
        TranslationDecision d = s.translateItemLine(row);
        assertTrue(d.changed(), "and must display once answered: " + d.translated());
        assertTrue(d.translated().contains("+50%"), d.translated());
    }

    @Test
    void r7RowWithLiteralMtSlotInSourceIsQueuedToo() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        // The dump replay shape: the "source" already carries a templated MT token.
        String row = "⟦CS6⟧Crit Damage:⟦/CS6⟧ ⟦CS7⟧⟦MT0⟧⟦/CS7⟧";
        s.warmTooltipBatch(List.of(row));
        pump(s);
        s.translateItemLine(row);
        pump(s);
        // Either it was sent, or the service must have a recorded reason (debug log entry).
        boolean sent = !ai.sentUnits.isEmpty();
        assertTrue(sent, "literal-MT row was silently dropped; sent=" + ai.sentUnits);
    }

    // ------------------------------------------------------------------ R5

    private static String paragraph(String... rows) {
        return com.dragonmeow.nyanlex.translate.ParagraphModel.join(List.of(rows));
    }

    private static long sellerUnits(FakeAi ai) {
        return ai.sentUnits.stream().filter(u -> u.contains("Seller")).count();
    }

    @Test
    void r5SellerNamesShareOneUnitPerRankShapeNotPerPlayer() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        // Four different players: three carry a rank badge (any rank text, any colour), one has
        // none. The player name and the badge are slots, so exactly TWO Seller templates
        // exist ("Seller: <badge> <name>" / "Seller: <name>"), never one per player.
        String[] sellers = {
                "Seller: [MVP+] lightning0611", "Seller: [VIP] Steve_Rogers",
                "Seller: [MVP++] xX_Cat_Xx", "Seller: DragonMeow", "Seller: Notch"};
        for (String seller : sellers) {
            s.warmTooltipBatch(List.of(paragraph("LEGENDARY", seller, "Buy it now: 1,000 coins")));
            pump(s);
        }
        assertEquals(2, sellerUnits(ai), "per rank shape, not per player: " + ai.sentUnits);
        for (String name : List.of("lightning0611", "Steve_Rogers", "xX_Cat_Xx", "DragonMeow", "Notch")) {
            assertTrue(ai.sentUnits.stream().noneMatch(u -> u.contains(name)),
                    "player name leaked into a request: " + ai.sentUnits);
        }
    }

    // ------------------------------------------------------------------ R1

    /** One word per colour pair: the "Duplex I" shape (20+ pairs in the live run). */
    private static String fragmented(int pairs) {
        String[] words = {"Shoot", "an", "extra", "arrow", "dealing", "damage", "to", "targets", "that",
                "are", "hit", "by", "the", "first", "arrow", "and", "more", "enemies", "nearby", "now"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs; i++) {
            if (i > 0) sb.append(' ');
            sb.append("⟦CS").append(i).append('⟧').append(words[i % words.length])
                    .append("⟦/CS").append(i).append('⟧');
        }
        return sb.toString();
    }

    @Test
    void r1FragmentedColourUnitIsSentColourFreeAndStillDisplays() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        String unit = fragmented(20);
        s.warmTooltipBatch(List.of(unit));
        pump(s);
        assertEquals(1, ai.sentUnits.size(), ai.sentUnits.toString());
        assertFalse(ai.sentUnits.get(0).contains("{cs"), "no colour pairs on the wire: " + ai.sentUnits);
        TranslationDecision d = s.translateItemLine(unit);
        assertTrue(d.changed(), "must not stay English forever");
        assertTrue(com.dragonmeow.nyanlex.translate.TextFilter.isStyleFallback(d.translated()),
                "displayed through the colour-insensitive path");
    }

    @Test
    void r1LightlyColouredUnitStillKeepsItsColourPairsOnTheWire() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        s.warmTooltipBatch(List.of(fragmented(4)));
        pump(s);
        assertEquals(1, ai.sentUnits.size());
        assertTrue(ai.sentUnits.get(0).contains("{cs0}"), ai.sentUnits.toString());
    }

    @Test
    void r1FragmentedSegmentInsideAStructuredParagraphDisplaysWithDominantColour() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        String para = paragraph("LEGENDARY", "Seller: Notch", fragmented(12), "Buy it now: 1,000 coins");
        s.warmTooltipBatch(List.of(para));
        pump(s);
        assertTrue(ai.sentUnits.stream().noneMatch(u -> u.contains("{cs")),
                "heavy segment must not carry colour pairs: " + ai.sentUnits);
        TranslationDecision d = s.translateItemLine(para);
        assertTrue(d.changed(), "composed=" + d.translated());
        for (int i = 0; i < 12; i++) {
            assertTrue(d.translated().contains("⟦CS" + i + "⟧"), "pair " + i + " kept: " + d.translated());
            assertTrue(d.translated().contains("⟦/CS" + i + "⟧"));
        }
        assertFalse(com.dragonmeow.nyanlex.translate.TextFilter.isStyleFallback(d.translated()));
    }

    // ------------------------------------------------------------------ R6

    /** A provider that mangles exactly the "Griffin" unit (drops its value token) every time. */
    private static FakeAi aiThatBreaksGriffin() {
        FakeAi ai = new FakeAi();
        ai.transform = wire -> wire.contains("Griffin")
                ? wire.replaceAll("\\{mt\\d+\\}", "") : FakeAi.translateWords(wire);
        return ai;
    }

    private static final String R6_PARAGRAPH = com.dragonmeow.nyanlex.translate.ParagraphModel.join(
            List.of("LEGENDARY", "Seller: Notch", "Gain 5 Coins from Griffin Burrows",
                    "Buy it now: 1,000 coins"));

    @Test
    void r6FinishedSegmentsShowWhileAFailedOneStaysOriginal() {
        FakeAi ai = aiThatBreaksGriffin();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        s.warmTooltipBatch(List.of(R6_PARAGRAPH));
        pump(s);
        assertTrue(s.isTooltipTranslationReady(R6_PARAGRAPH),
                "finished segments must be displayable although one segment failed");
        TranslationDecision d = s.translateItemLine(R6_PARAGRAPH);
        assertTrue(d.changed(), "partial composition expected");
        assertTrue(d.translated().contains("Gain 5 Coins from Griffin Burrows"),
                "the failed segment keeps its original wording: " + d.translated());
        assertFalse(d.translated().contains("Seller"), "finished trade row is translated: " + d.translated());
        assertFalse(d.translated().contains("Buy it now"), d.translated());
        assertTrue(d.translated().contains("Notch"));
        assertEquals(4 - 1, com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(d.translated()),
                "row structure is untouched: " + d.translated());
    }

    @Test
    void r6NothingFinishedYetStaysFullyOriginal() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        assertFalse(s.isTooltipTranslationReady(R6_PARAGRAPH));
        assertFalse(s.translateItemLine(R6_PARAGRAPH).changed());
    }

    @Test
    void r6BothModeKeepsItsCompleteMirrorRule() {
        FakeAi ai = aiThatBreaksGriffin();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true), DisplayMode.BOTH);
        s.warmTooltipBatch(List.of(R6_PARAGRAPH));
        pump(s);
        assertFalse(s.isTooltipTranslationReady(R6_PARAGRAPH));
    }

    // ------------------------------------------------------------------ R3

    @Test
    void r3HalfWidthColonAfterChineseIsFullWidthOnDisplayOnly() {
        FakeAi ai = new FakeAi(); // answers "Crit Damage: {mt0}" with CJK + a half-width ':'
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        String line = "Crit Damage: +50%";
        s.warmTooltipBatch(List.of(line));
        pump(s);
        TranslationDecision d = s.translateItemLine(line);
        assertTrue(d.changed());
        assertTrue(d.translated().contains("："), d.translated());
        assertFalse(d.translated().matches(".*\\p{IsHan}:.*"), d.translated());
    }

    // ------------------------------------------------------------------ R4

    @Test
    void r4LeadingIconIsNotSentAndComesBackInFront() {
        FakeAi ai = new FakeAi();
        TranslationService s = service(ai, new TranslationDebugLog(() -> true));
        String line = " Requires Enderman Slayer 7.";
        s.warmTooltipBatch(List.of(line));
        pump(s);
        assertEquals(1, ai.sentUnits.size());
        assertFalse(ai.sentUnits.get(0).startsWith("{mt0}"), "icon must not lead the wire unit: " + ai.sentUnits);
        TranslationDecision d = s.translateItemLine(line);
        assertTrue(d.changed());
        assertTrue(d.translated().startsWith(" "), d.translated());
        assertTrue(d.translated().contains("7"), d.translated());
    }
}
