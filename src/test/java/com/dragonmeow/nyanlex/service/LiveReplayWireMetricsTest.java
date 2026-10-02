package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.translate.AiSettings;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.OpenAiTranslator;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline replay of the failing units of the live OpenAI run (units reconstructed from
 * the request files, whose OLD wire text is quoted verbatim): measures the NEW wire the
 * current pipeline sends for the same tooltip rows and compares the failure-driving
 * features the token-loss analysis found (PB count, unit-leading MT, CS pairs, length).
 * The numbers are printed (see INTEGRATE-REPORT.md); the assertions pin the improvement.
 */
class LiveReplayWireMetricsTest {

    private static final Executor DIRECT = Runnable::run;
    private static final Gson GSON = new Gson();
    private static final Pattern ANCHORED = Pattern.compile("(?m)^(\\d{4,}) (.*) (\\d{4,})$");

    private static final class Recorder implements HttpTransport {
        final List<String> units = new ArrayList<>();

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            String user = "";
            for (JsonElement el : root.getAsJsonArray("messages")) {
                JsonObject m = el.getAsJsonObject();
                if ("user".equals(m.get("role").getAsString())) user = m.get("content").getAsString();
            }
            Matcher m = ANCHORED.matcher(user);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                if (out.length() > 0) out.append('\n');
                units.add(m.group(2));
                out.append(m.group(1)).append(' ').append(m.group(2)).append(' ').append(m.group(3));
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

    private static List<String> newWire(String request) {
        Recorder recorder = new Recorder();
        OpenAiTranslator translator = new OpenAiTranslator(recorder,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        TranslationService s = new TranslationService(cfg,
                new TranslationCache(translator, "zh-TW", DIRECT, 100),
                new TranslationCache(translator, "zh-TW", DIRECT, 100));
        s.setBatchWindowMs(() -> 0);
        s.warmTooltipBatch(List.of(request));
        s.flushBatches();
        s.flushBatches();
        return recorder.units;
    }

    private static int count(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static int csPairs(String wire) {
        return count(wire, "\\{cs\\d+\\}");
    }

    private static int pb(String wire) {
        return count(wire, "\\{pb\\d+\\}");
    }

    private static boolean leadingMt(String wire) {
        return wire.startsWith("{mt0}") || wire.matches("^(\\{ws\\d+\\}|\\{pb\\d+\\}|\\s)+\\{mt\\d+\\}.*");
    }

    private static MutableComponent coloured(String text, int rgb) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    private static void row(String label, String oldWire, List<String> newWires) {
        String fresh = newWires.size() == 1 ? newWires.get(0) : String.join(" || ", newWires);
        int newPb = newWires.stream().mapToInt(LiveReplayWireMetricsTest::pb).sum();
        int newCs = newWires.stream().mapToInt(LiveReplayWireMetricsTest::csPairs).sum();
        int newLen = newWires.stream().mapToInt(String::length).sum();
        System.out.println("REPLAY|" + label + "|old len=" + oldWire.length() + " pb=" + pb(oldWire)
                + " cs=" + csPairs(oldWire) + " leadMt=" + leadingMt(oldWire)
                + "|new units=" + newWires.size() + " len=" + newLen + " pb=" + newPb + " cs=" + newCs
                + " leadMt=" + newWires.stream().anyMatch(LiveReplayWireMetricsTest::leadingMt));
        System.out.println("REPLAY-NEW|" + label + "|" + fresh);
    }

    @Test
    void indulgenceSoftWrappedAbilityLosesItsMidSentenceBreaks() {
        String oldWire = "Ability: Indulgence {ws0} {pb0} Gain {mt0} Coins from Griffin Burrows and {pb1} "
                + "{mt1}{mt2} Magic Find on {mt3} Mythological mobs, {pb2} but you take {mt4} damage from them.";
        List<Component> rows = List.of(
                Component.literal("Ability: Indulgence"),
                Component.literal("Gain 5 Coins from Griffin Burrows and"),
                Component.literal("+10 +5% Magic Find on 6 Mythological mobs,"),
                Component.literal("but you take 40% damage from them."));
        List<String> wire = newWire(FabricTextStyle.paragraphRequestText(rows));
        row("Indulgence (F1/F2/F5/F6)", oldWire, wire);
        assertEquals(3, pb(oldWire));
        assertTrue(wire.stream().mapToInt(LiveReplayWireMetricsTest::pb).sum() <= 1, wire.toString());
    }

    @Test
    void fragmentedDuplexUnitIsRequestedColourFree() {
        String[] words = {"Shoot an extra arrow dealing", "50%", "of the", "first arrow's damage.",
                "Targets hit take", "25%", "fire damage", "for", "3s", ".", "Infinite Quiver X",
                "Saves arrows", "10%", "of the time when", "you fire your bow.", "Piercing I",
                "Arrows travel through enemies. The", "extra targets hit take", "30%", "of the", "damage."};
        MutableComponent line = Component.empty();
        StringBuilder oldWire = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            line.append(coloured(words[i] + (i + 1 < words.length ? " " : ""), 0x100000 * ((i % 14) + 1)));
            oldWire.append("{cs").append(i).append('}').append(words[i]).append("{/cs").append(i).append("} ");
        }
        List<String> wire = newWire(FabricTextStyle.paragraphRequestText(List.of(line)));
        row("Duplex I (F-dump, 21 colour runs)", oldWire.toString(), wire);
        assertTrue(csPairs(oldWire.toString()) > 8);
        assertEquals(0, wire.stream().mapToInt(LiveReplayWireMetricsTest::csPairs).sum(), wire.toString());
    }

    @Test
    void leadingIconRequirementRowNoLongerLeadsWithAToken() {
        String oldWire = "{mt0} Requires Enderman Slayer {mt1}.";
        List<String> wire = newWire(" Requires Enderman Slayer 7.");
        row("Requires Enderman Slayer (retry29)", oldWire, wire);
        assertTrue(leadingMt(oldWire));
        assertTrue(wire.stream().noneMatch(LiveReplayWireMetricsTest::leadingMt), wire.toString());
    }

    @Test
    void unchangedShapesAreReportedHonestly() {
        // A pure row break between two independent rows stays a PB; nothing to gain.
        List<Component> rows = List.of(
                Component.literal("Item sold to [MVP+] lightning0611"),
                Component.literal("for a marvelous 5,000 coins, gg!"));
        String oldWire = "Item sold to {0} {1} {pb0} for a marvelous {mt0} coins, gg!";
        List<String> wire = newWire(FabricTextStyle.paragraphRequestText(rows));
        row("Item sold to <name> (F4, unchanged)", oldWire, wire);
        assertEquals(1, wire.stream().mapToInt(LiveReplayWireMetricsTest::pb).sum());
    }
}
