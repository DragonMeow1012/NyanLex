package com.dragonmeow.nyanslate.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.0.7 P1.5 token savings of the AI request body, as golden strings captured from an inline
 * fake transport: Q1 (context rows that are units of the same request are not sent twice),
 * Q2 (placeholder/CS/WS/PB clauses only when the request carries such tokens) and the
 * multi-context user message of a window-collected batch, including its bisect retry.
 */
class OpenAiTokenSavingTest {

    /** The unconditional zh-TW system prompt sent before Q2 (HEAD 33c220f), verbatim,
     *  updated for the AiWireCodec wire format (ASCII {tag}/{csn}/{wsn}/{pbn}; the
     *  internal/cache format stays ⟦...⟧ — only the text the MODEL reads changed). */
    private static final String LEGACY_ZH_TW_PROMPT = ""
            + "Translate Minecraft Java/mod in-game text into Traditional Chinese (zh-TW). "
            + "Use official Minecraft translations as the terminology baseline for vanilla concepts, not as a rigid word-for-word template. "
            + "Adapt naturally to the detected server/mod genre and keep wording coherent across lines. "
            + "The source may be vanilla Minecraft or any server/mod genre, including RPG/MMO equipment, stats, abilities, quests and economy. "
            + "Infer ambiguous terms from the entire visible-block context, never as isolated dictionary labels. "
            + "Each input unit begins and ends with a unique five-digit boundary token. Return the same boundary tokens exactly once, in the same order, with only that unit's translation between its pair and no commentary outside the pairs. "
            + "Never merge, split, add, remove or reorder anchored units; the program owns all sections, PB line breaks and blank lines. "
            + "Keep numbers, symbols and formatting codes intact. "
            + "Translate ordinary UI, item and location terms completely; do not leave a source-language location word unchanged while translating the rest. "
            + "Translate each word as a WHOLE: NEVER mix the original script and the target script inside a single word. "
            + "For names, translate/transliterate the WHOLE name or keep it unchanged; never turn \"jacob\" into \"傑cob\". "
            + "Copy every {tag} placeholder verbatim, including its braces and any leading /. Treat each {csn}...{/csn} pair like a BBCode style tag: keep the complete pair around the translation of the same semantic phrase even when target grammar reorders phrases. Never drop, nest incorrectly, or duplicate cs tags or {wsn} layout slots. "
            + "Treat each {pbn} as an immutable line break inside one semantic paragraph: keep all pb tokens in the same order while translating coherently across them."
            + " Prefer established Minecraft and Traditional-Chinese gaming wording (for example, Enchant → 附魔). In an RPG stat or combat block, Damage means 傷害, not 損壞; interpret equipment and character stat labels by their gameplay meaning likewise. For server/mod-specific content, write concise, natural Taiwan player-facing RPG/MMO/mod text instead of stiff dictionary translations. Keep established proper names unchanged when translating them would be awkward or ambiguous.";

    private static final String BASE = LEGACY_ZH_TW_PROMPT.substring(0,
            LEGACY_ZH_TW_PROMPT.indexOf(" Copy every"));
    private static final String ZH_TW = LEGACY_ZH_TW_PROMPT.substring(
            LEGACY_ZH_TW_PROMPT.indexOf(" Prefer established"));
    private static final String ANY = " Copy every {tag} placeholder verbatim, including its braces and any leading /.";
    private static final String CS = " Treat each {csn}...{/csn} pair like a BBCode style tag: keep the complete pair around the translation of the same semantic phrase even when target grammar reorders phrases.";
    private static final String CS_AND_WS = " Never drop, nest incorrectly, or duplicate cs tags or {wsn} layout slots.";
    private static final String CS_ONLY = " Never drop, nest incorrectly, or duplicate cs tags.";
    private static final String WS_ONLY = " Never drop or duplicate {wsn} layout slots.";
    private static final String PB = " Treat each {pbn} as an immutable line break inside one semantic paragraph: keep all pb tokens in the same order while translating coherently across them.";

    // ---- Q2: conditional system prompt ----

    @Test
    void legacyEntryPointsStillBuildTheFullPromptVerbatim() {
        assertEquals(LEGACY_ZH_TW_PROMPT, OpenAiTranslator.buildSystemPrompt("zh-TW", List.of()));
        assertEquals(LEGACY_ZH_TW_PROMPT, OpenAiTranslator.buildSystemPrompt("zh-TW", null, "Hello"));
        assertEquals(LEGACY_ZH_TW_PROMPT, OpenAiTranslator.buildSystemPrompt("zh-TW", null, null,
                OpenAiTranslator.PROMPT_ALL_TOKENS));
    }

    @Test
    void placeholderClausesAppearOnlyForTokensPresentInTheRequest() throws Exception {
        int checked = 0;
        for (int mask = 0; mask < 16; mask++) {
            boolean number = (mask & 1) != 0, cs = (mask & 2) != 0, ws = (mask & 4) != 0, pb = (mask & 8) != 0;
            StringBuilder unit = new StringBuilder("Hello");
            if (number) unit.append(" ⟦0⟧");
            if (cs) unit.append(" ⟦CS0⟧red⟦/CS0⟧");
            if (ws) unit.append(" ⟦WS0⟧ right");
            if (pb) unit.append("⟦PB0⟧next line");

            String expected = BASE
                    + (number || cs || ws || pb ? ANY : "")
                    + (cs ? CS : "")
                    + (cs && ws ? CS_AND_WS : cs ? CS_ONLY : ws ? WS_ONLY : "")
                    + (pb ? PB : "")
                    + ZH_TW;
            String sent = systemPrompt(send(List.of(unit.toString()), null));
            assertEquals(expected, sent, "golden prompt for " + unit);
            assertTrue(sent.length() <= LEGACY_ZH_TW_PROMPT.length(),
                    "no token combination may lengthen the prompt: " + unit);
            if (mask == 15) assertEquals(LEGACY_ZH_TW_PROMPT, sent, "all tokens: byte-identical to before");
            if (mask == 0) assertFalse(sent.contains("⟦"), "plain text needs no placeholder clause");
            checked++;
        }
        assertEquals(16, checked);
    }

    @Test
    void otherPlaceholderKindsOnlyNeedTheCopyClause() throws Exception {
        String templated = systemPrompt(send(List.of("Damage: ⟦MT0⟧"), null));
        assertEquals(BASE + ANY + ZH_TW, templated, "a template slot needs the copy clause only");

        String hardLine = systemPrompt(send(List.of("First paragraph\nSecond paragraph"), null));
        assertEquals(BASE + ANY + ZH_TW, hardLine, "a hard line break travels as a ⟦AI_LINE⟧ slot");

        String contextOnly = systemPrompt(send(List.of("Recipes"),
                List.of("⟦CS0⟧Iron Pickaxe⟦/CS0⟧", "⟦WS0⟧Recipes")));
        assertEquals(BASE + ZH_TW, contextOnly,
                "tokens that appear only in reference context never have to be copied");

        assertEquals(OpenAiTranslator.PROMPT_TOKEN_ANY | OpenAiTranslator.PROMPT_TOKEN_CS,
                OpenAiTranslator.promptTokens(List.of("⟦ /CS 3 ⟧x⟦ CS3 ⟧")),
                "loosely spaced closing tags still count as CS");
    }

    @Test
    void nonChineseTargetWithoutTokensEndsCleanly() throws Exception {
        String sent = systemPrompt(send(List.of("Melon"), null, "fr"));
        assertTrue(sent.endsWith("never turn \"jacob\" into \"傑cob\"."), sent);
        assertFalse(sent.contains("  "), "no double space where clauses were left out: " + sent);
    }

    // ---- Q1: context rows that are already units are not repeated ----

    @Test
    void contextNoLongerRepeatsTheRowsBeingTranslated() throws Exception {
        String user = userMessage(send(List.of("Recipes"),
                List.of("Iron Pickaxe", "", "Recipes", "Gear Score: 558", "Used in smelting")));
        assertEquals(""
                + "Minecraft visible block context (semantic reference for domain and terminology; layout is program-owned):\n"
                + "[L0:TEXT] Iron Pickaxe\n"
                + "[SECTION]\n"
                + "[L3:STAT] Gear Score: 558\n"
                + "[L4:TEXT] Used in smelting\n"
                + "Translate ONLY the strictly anchored units below; do not output the visible-block context.\n"
                + "\n"
                + "86001 Recipes 86002\n", user);
    }

    @Test
    void freshlyHoveredTooltipSendsNoContextBlockAtAll() throws Exception {
        List<String> tooltip = List.of("Aspect of the End", "", "Ability: Instant Transmission");
        String user = userMessage(send(List.of("Aspect of the End", "Ability: Instant Transmission"), tooltip));
        assertEquals(""
                + "86001 Aspect of the End 86002\n"
                + "86003 Ability: Instant Transmission 86004\n", user,
                "every row is a unit: the context would only repeat them (a section marker alone says nothing)");
    }

    @Test
    void contextLimitsAreUnchanged() throws Exception {
        List<String> context = new ArrayList<>();
        context.add("Translate me");
        for (int i = 0; i < 80; i++) context.add("tooltip context line " + i + " xxxxxxxxxxxxxxxxxxxx");
        String user = userMessage(send(List.of("Translate me"), context));
        String legacyStyle = "[L1:TEXT] tooltip context line 0";
        assertTrue(user.contains(legacyStyle), user);
        assertTrue(user.contains(
                        "[L24:TEXT] tooltip context line 23 xxxxxxxxxxxxxxxxxxxx\n[remaining context omitted]\n"),
                "still 24 rows per context; the deduplicated unit row does not count: " + user);
        assertFalse(user.contains("[L0:TEXT] Translate me"), user);
    }

    // ---- window batches: several contexts in one request ----

    @Test
    void mixedSurfacesShareOneRequestAndEachContextNamesItsUnits() throws Exception {
        List<String> tooltipA = List.of("Hyperion", "", "Ability: Wither Impact", "Teleports forward");
        List<String> tooltipB = List.of("Enchanted Diamond", "Collection item");
        FakeModel model = new FakeModel();
        OpenAiTranslator ai = translator(model);

        List<TranslationResult> results = ai.translateBatchWithContexts(
                List.of("Ability: Wither Impact", "Hello there", "Collection item", "Hyperion"), "zh-TW",
                Arrays.asList(tooltipA, null, tooltipB, tooltipA));

        assertEquals(1, model.bodies.size());
        assertEquals(""
                + "Minecraft visible block contexts (semantic reference for domain and terminology; layout is program-owned):\n"
                + "[Context for units 86001-86004]\n"
                + "[SECTION]\n"
                + "[L3:TEXT] Teleports forward\n"
                + "[Context for units 86007-86008]\n"
                + "[L0:TEXT] Enchanted Diamond\n"
                + "Translate ONLY the strictly anchored units below; do not output the visible-block context.\n"
                + "\n"
                + "86001 Ability: Wither Impact 86002\n"
                + "86003 Hyperion 86004\n"
                + "86005 Hello there 86006\n"
                + "86007 Collection item 86008\n", userMessage(model.bodies.get(0)),
                "units of one surface are contiguous; the chat line carries no context");
        assertEquals(List.of("T:Ability: Wither Impact", "T:Hello there", "T:Collection item", "T:Hyperion"),
                texts(results), "results come back in the caller's order");
        assertEquals(BASE + ZH_TW, systemPrompt(model.bodies.get(0)), "one system prompt per request");
    }

    @Test
    void damagedWindowBatchIsBisectedWithEachHalfKeepingItsOwnContexts() throws Exception {
        List<String> tooltipA = List.of("Hyperion", "Teleports forward");
        List<String> sidebar = List.of("Spring Festival", "⟦CS0⟧Objective⟦/CS0⟧");
        FakeModel model = new FakeModel();
        model.damageWhen = user -> user.contains("86007");     // only the 4-unit request breaks
        OpenAiTranslator ai = translator(model);

        List<TranslationResult> results = ai.translateBatchWithContexts(
                List.of("Hyperion", "Spring Festival", "⟦CS0⟧Red⟦/CS0⟧ alert", "Hello there"), "zh-TW",
                Arrays.asList(tooltipA, sidebar, null, sidebar));

        assertEquals(3, model.bodies.size(), "one damaged request, then its two halves");
        assertEquals(""
                + "Minecraft visible block contexts (semantic reference for domain and terminology; layout is program-owned):\n"
                + "[Context for units 86001-86002]\n"
                + "[L1:TEXT] Teleports forward\n"
                + "[Context for units 86003-86004]\n"
                + "[L1:TEXT] Objective\n"
                + "Translate ONLY the strictly anchored units below; do not output the visible-block context.\n"
                + "\n"
                + "86001 Hyperion 86002\n"
                + "86003 Spring Festival 86004\n", userMessage(model.bodies.get(1)));
        assertEquals(""
                + "Minecraft visible block contexts (semantic reference for domain and terminology; layout is program-owned):\n"
                + "[Context for units 86001-86002]\n"
                + "[L0:TEXT] Spring Festival\n"
                + "[L1:TEXT] Objective\n"
                + "Translate ONLY the strictly anchored units below; do not output the visible-block context.\n"
                + "\n"
                + "86001 Hello there 86002\n"
                // AiWireCodec: the request body carries the wire form ({cs0}/{/cs0}),
                // never the raw ⟦CS0⟧/⟦/CS0⟧ internal/cache format.
                + "86003 {cs0}Red{/cs0} alert 86004\n", userMessage(model.bodies.get(2)),
                "the sidebar unit of the second half still gets its sidebar (its sibling row is no "
                        + "longer a unit of this request, so it is listed again); the chat unit none");
        assertEquals(BASE + ZH_TW, systemPrompt(model.bodies.get(1)), "each half judges its own tokens");
        assertEquals(BASE + ANY + CS + CS_ONLY + ZH_TW, systemPrompt(model.bodies.get(2)));
        assertEquals(List.of("T:Hyperion", "T:Spring Festival", "T:⟦CS0⟧Red⟦/CS0⟧ alert", "T:Hello there"),
                texts(results));
    }

    @Test
    void oneSharedContextKeepsTheSingleBlockFormat() throws Exception {
        List<String> tooltip = List.of("Hyperion", "Teleports forward", "Deals damage");
        FakeModel perItem = new FakeModel();
        translator(perItem).translateBatchWithContexts(List.of("Hyperion", "Deals damage"), "zh-TW",
                Arrays.asList(tooltip, tooltip));
        FakeModel shared = new FakeModel();
        translator(shared).translateBatch(List.of("Hyperion", "Deals damage"), "zh-TW", tooltip);
        assertEquals(shared.bodies, perItem.bodies, "per-item contexts that agree are the old request");
        assertTrue(userMessage(shared.bodies.get(0)).startsWith(
                "Minecraft visible block context (semantic"), shared.bodies.get(0));
    }

    // ---------------------------------------------------------------------------------

    private static OpenAiTranslator translator(FakeModel model) {
        return new OpenAiTranslator(model, () -> new AiSettings("https://x/v1", "m", List.of("k")));
    }

    private static String send(List<String> texts, List<String> context) throws Exception {
        return send(texts, context, "zh-TW");
    }

    private static String send(List<String> texts, List<String> context, String lang) throws Exception {
        FakeModel model = new FakeModel();
        translator(model).translateBatch(texts, lang, context);
        assertEquals(1, model.bodies.size());
        return model.bodies.get(0);
    }

    private static List<String> texts(List<TranslationResult> results) {
        List<String> out = new ArrayList<>();
        for (TranslationResult result : results) out.add(result.translatedText());
        return out;
    }

    private static String systemPrompt(String body) {
        return message(body, 0);
    }

    private static String userMessage(String body) {
        return message(body, 1);
    }

    private static String message(String body, int index) {
        JsonObject root = new Gson().fromJson(body, JsonObject.class);
        return root.getAsJsonArray("messages").get(index).getAsJsonObject().get("content").getAsString();
    }

    /** Inline OpenAI-compatible model: "T:" + every anchored unit, or a broken reply on demand. */
    private static final class FakeModel implements HttpTransport {
        private static final Pattern UNIT = Pattern.compile("(?m)^(\\d{5}) (.*) (\\d{5})$");
        final List<String> bodies = new ArrayList<>();
        Predicate<String> damageWhen = user -> false;

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) {
            bodies.add(body);
            String user = userMessage(body);
            StringBuilder reply = new StringBuilder();
            if (damageWhen.test(user)) {
                reply.append("sorry, I merged everything");
            } else {
                Matcher unit = UNIT.matcher(user);
                while (unit.find()) {
                    reply.append(unit.group(1)).append("T:").append(unit.group(2))
                            .append(unit.group(3)).append('\n');
                }
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.addProperty("content", reply.toString());
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject root = new JsonObject();
            root.add("choices", choices);
            return new Gson().toJson(root);
        }
    }
}
