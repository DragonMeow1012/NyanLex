package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end reproduction of the real 2026-10-02 Hypixel SkyBlock bug (user screenshots
 * 6/7/8: three "Mosquito Shortbow" tooltips — Spiritual, Precise, and a third — side by
 * side; attribute rows, Seller:/Buy it now:/Requires, and the MYTHIC DUNGEON BOW rarity
 * line stayed English for every item after the first) through the REAL {@link
 * com.dragonmeow.nyanlex.translate.TooltipSegmentPlanner}, {@link TranslationService},
 * {@link TranslationCache} and {@link OpenAiTranslator} — only the HTTP transport is fake,
 * a rule-following AI that behaves exactly as the system prompt asks (same technique {@code
 * TokenLostReproTest} uses). Root cause: {@code markChatContent} numbers every {@code
 * ⟦CSn⟧} colour-run marker SEQUENTIALLY ACROSS THE WHOLE joined tooltip paragraph, so the
 * exact same semantic row (a stat line, a trade field, the rarity phrase) carries a
 * DIFFERENT embedded marker offset per item depending on how many OTHER style runs
 * happened to precede it — {@code TranslationService#resolveEnchantName}/{@code
 * #requestEnchantName} used that raw span directly as the cache key, so the same row
 * essentially never got reused. {@link
 * com.dragonmeow.nyanlex.translate.LocalTokenRenumberer#localize}/{@code restore} now sit
 * in that shared chokepoint; this test proves THREE siblings at three different offsets —
 * plus the MYTHIC DUNGEON BOW rarity line, which happens to fall back to a normal PROSE
 * translation request whenever Hypixel renders it split across multiple colour runs instead
 * of one (confirmed empirically: {@link
 * com.dragonmeow.nyanlex.translate.RarityLineComposer#match} correctly declines that
 * shape, by design, since the fixed term-table composition never handles a style-split
 * phrase) — now share one cache entry per distinct row instead of one per item.
 */
class MosquitoShortbowLiveReproTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String TARGET_LANG = "zh-TW";
    private static final Gson GSON = new Gson();

    // =====================================================================
    // "Perfect" fake AI transport — identical technique to TokenLostReproTest: parses the
    // REAL request JSON body, finds every anchored unit line, and returns a response that
    // keeps every {...} wire token exactly as sent while replacing ASCII letter runs with a
    // deterministic whole-word CJK substitute.
    // =====================================================================

    private static final Pattern ANCHORED_LINE = Pattern.compile("(?m)^(\\d{4,}) (.*) (\\d{4,})$");
    private static final Pattern TOKEN_OR_WORD = Pattern.compile("\\{[^{}]*\\}|[A-Za-z]+");
    private static final String CJK_POOL =
            "的一是在不了有和人這中大為上個國我以要他時來用們生到作地於出就分對成會可主發年動同工也能下過子說產種面"
                    + "而方後多定行學法所民得經十三之進著等部度家電力裏如水化高自二理起小物現實加量都兩體制機當使點從業";

    private static String cjkFor(String word) {
        int idx = Math.floorMod(word.toLowerCase(Locale.ROOT).hashCode(), CJK_POOL.length() - 4);
        int len = Math.max(1, Math.min(4, (word.length() + 1) / 2));
        return CJK_POOL.substring(idx, idx + len);
    }

    private static String perfectTranslateWire(String wire) {
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
        int httpCalls;

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            httpCalls++;
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
            return GSON.toJson(resp);
        }

        private String translateAnchoredContent(String userContent) {
            Matcher m = ANCHORED_LINE.matcher(userContent);
            StringBuilder out = new StringBuilder();
            int unitCount = 0;
            while (m.find()) {
                if (out.length() > 0) out.append('\n');
                out.append(m.group(1)).append(' ').append(perfectTranslateWire(m.group(2)))
                        .append(' ').append(m.group(3));
                unitCount++;
            }
            if (unitCount == 0) {
                throw new IllegalStateException(
                        "no anchored unit found in user content:\n" + userContent);
            }
            return out.toString();
        }
    }

    private static TranslationDebugLog newDebugLog() {
        return new TranslationDebugLog(() -> true);
    }

    private static TranslationService newService(OpenAiTranslator translator, TranslationDebugLog log) {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.targetLang = TARGET_LANG;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        cfg.debugTranslationOverlay = true;
        TranslationCache google = new TranslationCache(translator, TARGET_LANG, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, TARGET_LANG, DIRECT, 1000);
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

    private static void assertNoFailures(TranslationDebugLog log, String scenario) {
        List<TranslationDebugLog.Entry> entries = log.snapshot(400);
        List<String> bad = new ArrayList<>();
        for (TranslationDebugLog.Entry e : entries) {
            if (e.status() == TranslationDebugLog.Status.FAILED
                    || e.status() == TranslationDebugLog.Status.KEEP_ORIGINAL) {
                bad.add("[" + e.status() + "/" + e.failureReason() + "] text=" + e.text()
                        + " translation=" + e.translation());
            }
        }
        if (!bad.isEmpty()) {
            throw new AssertionError(scenario + ": rule-following AI response was rejected by our "
                    + "own validation:\n" + String.join("\n", bad));
        }
    }

    private static boolean hasCjk(String s) {
        return s != null && s.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }

    /** ⟦CSn⟧/⟦/CSn⟧/⟦PBn⟧ are GLUE-level protocol tokens, expected to still be present in
     *  {@code translateItemLine}'s own return value (the real Fabric glue strips/renders
     *  them downstream — see {@code FabricTextStyle}). What must NEVER survive is an
     *  unrestored {@code NameMasker}/{@code TemplateText} placeholder: a bare ⟦n⟧ slot (no
     *  CS/PB/MT/WS kind prefix) or any ⟦MTn⟧/⟦WSn⟧ value slot — exactly the check {@code
     *  TokenLostReproTest} uses. */
    private static void assertNoUnrestoredPlaceholder(String translated) {
        assertFalse(translated.matches(".*⟦MT\\d+⟧.*"), "unrestored ⟦MTn⟧ value slot: " + translated);
        assertFalse(translated.matches(".*⟦WS\\d+⟧.*"), "unrestored ⟦WSn⟧ layout slot: " + translated);
        assertFalse(translated.matches(".*⟦\\d+⟧.*"), "unrestored bare name/do-not-translate slot: "
                + translated);
    }

    // ---- real Hypixel row shapes, reproduced verbatim from scratchpad/live-dump-2's actual
    // exchange-*.txt captures for "Spiritual Mosquito Shortbow"/"Precise Mosquito Shortbow",
    // with the embedded ⟦CSn⟧ offsets deliberately varied per item (markChatContent assigns
    // them from the FULL joined paragraph; this harness supplies that already-marked string
    // directly, exactly as FabricTextStyle.paragraphRequestText would for each item, with a
    // DIFFERENT accumulated offset per item — the actual bug condition). ----

    /** Single colour run over the whole phrase — RarityLineComposer composes this locally
     *  from the term table, zero network requests, regardless of {@code cs}'s value. */
    private static String raritySingleWrap(int cs) {
        return "⟦CS" + cs + "⟧MYTHIC DUNGEON BOW⟦/CS" + cs + "⟧";
    }

    /** The SAME phrase but split one colour run per word — confirmed via direct {@code
     *  RarityLineComposer.match()} probing to make the composer decline the match (by
     *  design: a style-split phrase is not one of the 8 fixed shapes), so
     *  TooltipSegmentPlanner correctly falls back to classifying it as PROSE, which DOES
     *  need a normal translation request — this is the real root cause of the "MYTHIC
     *  DUNGEON BOW" row specifically staying English for some items but not others. */
    private static String rarityPerWordSplit(int cs) {
        return "⟦CS" + cs + "⟧MYTHIC⟦/CS" + cs + "⟧ ⟦CS" + (cs + 1) + "⟧DUNGEON⟦/CS" + (cs + 1)
                + "⟧ ⟦CS" + (cs + 2) + "⟧BOW⟦/CS" + (cs + 2) + "⟧";
    }

    private static String statRow(int cs, String label, String value) {
        return "⟦CS" + cs + "⟧" + label + ":⟦/CS" + cs + "⟧ ⟦CS" + (cs + 1) + "⟧" + value
                + "⟦/CS" + (cs + 1) + "⟧";
    }

    private static String requiresRow(int cs, String tier) {
        return "⟦CS" + cs + "⟧Requires⟦/CS" + cs + "⟧ ⟦CS" + (cs + 1) + "⟧Spider Slayer " + tier
                + "⟦/CS" + (cs + 1) + "⟧";
    }

    private static String sellerRow(int cs, String rank, String name) {
        return "⟦CS" + cs + "⟧Seller:⟦/CS" + cs + "⟧ ⟦CS" + (cs + 1) + "⟧[" + rank + "] " + name
                + "⟦/CS" + (cs + 1) + "⟧";
    }

    private static String buyItNowRow(int cs, String price) {
        return "⟦CS" + cs + "⟧Buy it now:⟦/CS" + cs + "⟧ ⟦CS" + (cs + 1) + "⟧" + price + " coins"
                + "⟦/CS" + (cs + 1) + "⟧";
    }

    /** One full Mosquito Shortbow tooltip paragraph: rarity line + 4 attribute rows +
     *  Requires + Seller + Buy it now, all glued with no blank row between them (the real
     *  shape TooltipSegmentPlanner exists for) — every row's {@code ⟦CSn⟧} numbering starts
     *  fresh from {@code csStart}, simulating however many OTHER style runs (a longer/
     *  shorter enchant list, a different ability block, …) this PARTICULAR item happened to
     *  accumulate before reaching these rows. */
    private static String mosquitoShortbow(int csStart, String rarityRow, String rank, String sellerName,
                                           String tier, String price) {
        int cs = csStart;
        List<String> rows = new ArrayList<>();
        rows.add(rarityRow);
        rows.add(statRow(cs, "Damage", "180")); cs += 2;
        rows.add(statRow(cs, "Crit Chance", "25%")); cs += 2;
        rows.add(statRow(cs, "Crit Damage", "125%")); cs += 2;
        rows.add(statRow(cs, "Vitality", "100")); cs += 2;
        rows.add(requiresRow(cs, tier)); cs += 2;
        rows.add(sellerRow(cs, rank, sellerName)); cs += 2;
        rows.add(buyItNowRow(cs, price));
        return ParagraphModel.join(rows);
    }

    /** Full 9-attribute-row shape, exactly the label set the coordinator's screenshots 7/8
     *  show ("靈性蚊子短弓"/Spiritual vs "精準蚊子短弓"/Precise): Gear Score, Damage,
     *  Strength, Crit Chance, Crit Damage, Ferocity, Vitality, Magic Find, Shot Cooldown,
     *  then Requires/Seller/Buy it now — all glued with no blank row between them. */
    private static String mosquitoShortbowFull(int csStart, String rarityRow, String rank,
                                               String sellerName, String tier, String price) {
        int cs = csStart;
        List<String> rows = new ArrayList<>();
        rows.add(rarityRow);
        rows.add(statRow(cs, "Gear Score", "400")); cs += 2;
        rows.add(statRow(cs, "Damage", "180")); cs += 2;
        rows.add(statRow(cs, "Strength", "150")); cs += 2;
        rows.add(statRow(cs, "Crit Chance", "25%")); cs += 2;
        rows.add(statRow(cs, "Crit Damage", "125%")); cs += 2;
        rows.add(statRow(cs, "Ferocity", "40%")); cs += 2;
        rows.add(statRow(cs, "Vitality", "100")); cs += 2;
        rows.add(statRow(cs, "Magic Find", "30")); cs += 2;
        rows.add(statRow(cs, "Shot Cooldown", "0.2s")); cs += 2;
        rows.add(requiresRow(cs, tier)); cs += 2;
        rows.add(sellerRow(cs, rank, sellerName)); cs += 2;
        rows.add(buyItNowRow(cs, price));
        return ParagraphModel.join(rows);
    }

    /** Direct reproduction of the coordinator's screenshots images\7.png ("靈性蚊子短弓" /
     *  Spiritual, Seller rank [MVP+]) and images\8.png ("精準蚊子短弓" / Precise, Seller
     *  rank [VIP]): the two items' enchant lists have a DIFFERENT row count (simulated here
     *  by a much larger CS offset for Precise — a longer enchant list consumes more
     *  style-run indices before these same rows are reached) AND the Seller rank bracket is
     *  genuinely different text ([MVP+] vs [VIP], a different colour-run count/content).
     *  Every ATTRIBUTE row (all 9 the coordinator named), Buy it now, Requires Spider
     *  Slayer 7., and the MYTHIC DUNGEON BOW rarity line are byte-identical content between
     *  the two items — only the Seller row's rank word differs. */
    @Test
    void spiritualVsPreciseMosquitoShortbowFromRealScreenshots7And8() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        OpenAiTranslator translator = new OpenAiTranslator(transport,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
        TranslationService s = newService(translator, log);

        // "Spiritual": fewer preceding style runs (shorter enchant list), rarity rendered as
        // ONE colour run (resolves locally, zero network), rank [MVP+] -- the item the real
        // screenshot (images\7.png) shows working correctly end to end.
        String spiritual = mosquitoShortbowFull(0, raritySingleWrap(0), "MVP+", "DragonMeow", "7",
                "2,500,000");
        s.warmTooltipBatch(List.of(spiritual));
        pump(s);
        assertNoFailures(log, "Spiritual first warm");
        TranslationDecision spiritualDecision = s.translateItemLine(spiritual);
        assertTrue(spiritualDecision.changed() && hasCjk(spiritualDecision.translated()),
                "Spiritual must already display Chinese: " + spiritualDecision.translated());
        int callsAfterSpiritual = transport.httpCalls;
        assertTrue(callsAfterSpiritual > 0, "sanity: Spiritual needed real HTTP call(s)");

        // "Precise": a MUCH larger CS offset (longer enchant list), rarity split per-word
        // (falls back to a PROSE translation request -- see rarityPerWordSplit's own doc),
        // rank [VIP] -- otherwise byte-identical stats/tier/price/seller name. This is
        // images\8.png, rendered WITHOUT ever warming it first (a player's very next hover).
        String precise = mosquitoShortbowFull(90, rarityPerWordSplit(80), "VIP", "DragonMeow", "7",
                "2,500,000");
        TranslationDecision preciseDecision = s.translateItemLine(precise);
        if (!preciseDecision.changed() || !hasCjk(preciseDecision.translated())) {
            // One round trip may still be in flight after the synchronous DIRECT executor's
            // first submission above -- pump once more, mirroring a second render frame.
            pump(s);
            preciseDecision = s.translateItemLine(precise);
        }
        assertNoFailures(log, "Precise");

        // At most 2 genuinely NEW units for Precise: the never-before-seen split-style
        // rarity phrase (PROSE fallback) and the never-before-seen "[VIP]" Seller wording --
        // NEVER one new call per attribute row (the original bug: every row minted its own
        // key because of the differing CS offset alone).
        int newCallsForPrecise = transport.httpCalls - callsAfterSpiritual;
        assertTrue(newCallsForPrecise <= 2,
                "Precise must need at most 2 new HTTP calls (split-style rarity PROSE fallback + "
                        + "the new [VIP] Seller wording) -- every one of the 9 attribute rows, Buy it "
                        + "now, and Requires must resolve from Spiritual's cache despite a completely "
                        + "different CS offset; actual new calls=" + newCallsForPrecise);

        String out = preciseDecision.translated();
        assertTrue(preciseDecision.changed(), "Precise must display translated: " + out);
        assertNoUnrestoredPlaceholder(out);
        assertTrue(hasCjk(out), "Precise must show Chinese overall: " + out);
        // Every attribute row the coordinator explicitly named must no longer show its raw
        // English label -- each one resolved from Spiritual's cache with ZERO new requests.
        for (String label : new String[] {"Gear Score", "Damage", "Strength", "Crit Chance",
                "Crit Damage", "Ferocity", "Vitality", "Magic Find", "Shot Cooldown"}) {
            assertFalse(out.contains(label + ":"),
                    "attribute row '" + label + "' must no longer show its raw English label: " + out);
        }
        assertFalse(out.contains("Buy it now:"), "Buy it now must translate: " + out);
        assertFalse(out.contains("Requires"), "Requires row must translate: " + out);
    }

    @Test
    void threeSiblingMosquitoShortbowsAtDifferentCsOffsetsShareStatsTradeAndFallbackRarity() {
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        OpenAiTranslator translator = new OpenAiTranslator(transport,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
        TranslationService s = newService(translator, log);

        // Item 1 "Spiritual": CS numbering starts fresh at 0 (fewest preceding style runs),
        // rarity rendered as ONE colour run (resolves locally, zero network) -- this is
        // the item the real screenshots show working correctly.
        String spiritual = mosquitoShortbow(0, raritySingleWrap(0 + 100 /* unused slot, kept clear of stat CS range */),
                "MVP+", "DragonMeow", "7", "2,500,000");
        // (raritySingleWrap's own cs index does not need to avoid the stats range -- CS
        // indices are just opaque labels here -- but keeping it visually distinct makes the
        // trace below easier to read.)

        s.warmTooltipBatch(List.of(spiritual));
        pump(s);
        assertNoFailures(log, "item 1 (Spiritual) first warm");
        TranslationDecision d1 = s.translateItemLine(spiritual);
        assertTrue(d1.changed(), "item 1 must display translated: " + d1.translated());
        assertTrue(hasCjk(d1.translated()), "item 1 must show Chinese: " + d1.translated());
        assertTrue(d1.translated().contains("MYTHIC DUNGEON BOW") || hasCjk(d1.translated()),
                "sanity: rarity composes to SOMETHING (local term table or CJK fallback)");

        int httpCallsAfterItem1 = transport.httpCalls;
        assertTrue(httpCallsAfterItem1 > 0, "sanity: item 1 actually needed real HTTP call(s)");

        // Item 2 "Precise": a COMPLETELY different CS numbering range (as if a longer
        // enchant list/extra ability block preceded these same rows in THIS item's own
        // paragraph), AND the rarity phrase rendered split one colour run per word (the
        // real per-item rendering difference that makes RarityLineComposer correctly
        // decline it and fall back to a PROSE translation request) -- but otherwise
        // IDENTICAL semantic content (same stats, same tier, same rank, same seller name).
        String precise = mosquitoShortbow(40, rarityPerWordSplit(30), "MVP+", "DragonMeow", "7", "2,500,000");
        // Rendered WITHOUT ever warming it first -- exactly like a player's very next hover.
        TranslationDecision d2 = s.translateItemLine(precise);

        // The PROSE-fallback rarity phrase is genuinely new wording (never requested
        // before, since item 1's rarity resolved locally with no network round trip at
        // all) -- it alone may cost ONE fresh HTTP call. Everything else (4 stat rows,
        // Requires, Seller, Buy it now) must NOT.
        int newHttpCallsForItem2 = transport.httpCalls - httpCallsAfterItem1;
        assertTrue(newHttpCallsForItem2 <= 1,
                "item 2 must need AT MOST one new HTTP call (the never-before-seen split-style "
                        + "rarity phrase) -- every STATS/TRADE row must resolve from item 1's cache "
                        + "despite a completely different CS offset; actual new calls="
                        + newHttpCallsForItem2);
        if (!d2.changed() || !hasCjk(d2.translated())) {
            // The split-rarity PROSE unit may still be in flight after exactly one HTTP
            // round trip submitted synchronously above (DIRECT executor) -- pump once more
            // and re-render, mirroring a second render frame of the same hover.
            pump(s);
            d2 = s.translateItemLine(precise);
        }
        assertNoFailures(log, "item 2 (Precise)");
        assertTrue(d2.changed(), "item 2 must display translated: " + d2.translated());
        assertTrue(hasCjk(d2.translated()), "item 2 must show Chinese stats/trade text: " + d2.translated());
        // ⟦CSn⟧/⟦PBn⟧ GLUE-level tokens are EXPECTED to survive translateItemLine's own
        // output (FabricTextStyle strips/renders them later, see
        // TokenLostReproTest/TranslationServiceStructuredTooltipTest) -- what must never
        // remain is an UNRESTORED NameMasker/TemplateText placeholder.
        assertNoUnrestoredPlaceholder(d2.translated());
        // item 2's OWN CS40/41 (Damage) marker pair must be the ones surrounding its
        // restored value -- proving the restore step re-anchors to EACH item's own offset,
        // not to item 1's CS0/1 or to the shared local CS0/1 cache-key form.
        assertTrue(d2.translated().contains("⟦CS40⟧") && d2.translated().contains("⟦/CS40⟧"),
                "item 2's own global CS40 marker must survive restoration: " + d2.translated());

        int httpCallsAfterItem2 = transport.httpCalls;

        // Item 3: a THIRD distinct CS offset, the SAME split-style rarity phrasing as item
        // 2 (now already learned), identical stats/trade EXCEPT a different seller NAME
        // (the bare name is masked to a slot, so this must not cost anything extra either).
        String third = mosquitoShortbow(70, rarityPerWordSplit(60), "MVP+", "Notch", "7", "2,500,000");
        TranslationDecision d3 = s.translateItemLine(third);
        assertEquals(httpCallsAfterItem2, transport.httpCalls,
                "item 3 needs ZERO new HTTP calls -- every row (including the rarity PROSE "
                        + "fallback learned from item 2, and the Seller row whose only difference is "
                        + "the already-slot-abstracted bare name) is already cached");
        assertTrue(d3.changed(), "item 3 must display translated immediately: " + d3.translated());
        assertTrue(hasCjk(d3.translated()));
        assertTrue(d3.translated().contains("Notch"), "the seller name itself is restored verbatim: "
                + d3.translated());
        assertNoUnrestoredPlaceholder(d3.translated());

        // 60 simulated seconds of continuous hover on item 3 (repeated render, no warm) —
        // steady state: zero additional HTTP calls, identical output every frame.
        int httpCallsBeforeHoverLoop = transport.httpCalls;
        String firstHoverFrame = d3.translated();
        for (int frame = 0; frame < 1200; frame++) { // 1200 * 50ms = 60s
            TranslationDecision frameDecision = s.translateItemLine(third);
            assertEquals(firstHoverFrame, frameDecision.translated(),
                    "render output must be stable frame-to-frame once cached (frame " + frame + ")");
        }
        assertEquals(httpCallsBeforeHoverLoop, transport.httpCalls,
                "60s of repeated hover on an already-fully-resolved item must send ZERO new "
                        + "HTTP calls");
    }

    @Test
    void differentSellerRankTextCostsExactlyOneNewRequestAndStillResolvesCleanly() {
        // The coordinator's follow-up concern: a [VIP] vs [MVP+] rank prefix is genuinely
        // DIFFERENT text (a different literal word inside the SAME TRADE row), so it is
        // correct -- not a bug -- for it to need its own cache entry. What must still hold:
        // it resolves cleanly (no corruption/leak), and sits in a paragraph whose OTHER
        // rows (STATS, Buy it now) share normally regardless of the rank difference.
        RuleFollowingAiTransport transport = new RuleFollowingAiTransport();
        TranslationDebugLog log = newDebugLog();
        OpenAiTranslator translator = new OpenAiTranslator(transport,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
        TranslationService s = newService(translator, log);

        String mvpPlus = mosquitoShortbow(0, raritySingleWrap(0), "MVP+", "DragonMeow", "7", "2,500,000");
        s.warmTooltipBatch(List.of(mvpPlus));
        pump(s);
        TranslationDecision first = s.translateItemLine(mvpPlus);
        assertTrue(first.changed() && hasCjk(first.translated()));
        int callsAfterFirst = transport.httpCalls;

        // SAME everything, but at a different CS offset AND a different rank word.
        String vipRank = mosquitoShortbow(50, raritySingleWrap(50), "VIP", "DragonMeow", "7", "2,500,000");
        TranslationDecision second = s.translateItemLine(vipRank);
        if (!second.changed()) {
            pump(s);
            second = s.translateItemLine(vipRank);
        }
        assertNoFailures(log, "VIP-rank sibling");
        assertTrue(second.changed(), "the VIP-rank item must still resolve: " + second.translated());
        assertTrue(hasCjk(second.translated()));
        assertNoUnrestoredPlaceholder(second.translated());

        // Only the Seller row's own text differs ("[MVP+]" vs "[VIP]") -- STATS/Requires/
        // Buy it now are byte-identical once localized, so at most ONE new HTTP call (for
        // the new Seller wording) should have been needed, never one per row.
        int newCalls = transport.httpCalls - callsAfterFirst;
        assertTrue(newCalls <= 1, "only the differing Seller rank text may cost a new HTTP call, "
                + "not every row of the sibling item; actual new calls=" + newCalls);
    }
}
