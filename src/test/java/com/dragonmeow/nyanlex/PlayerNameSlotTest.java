package com.dragonmeow.nyanlex;

import com.dragonmeow.nyanlex.cache.PersistentStore;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.GoogleFreeTranslator;
import com.dragonmeow.nyanlex.translate.HttpTransport;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.2 player-name slot (strong frames) through the real service and cache stack.
 * Every translator, HTTP transport, clock and store is an inline fake.
 */
class PlayerNameSlotTest {

    private static final Executor DIRECT = Runnable::run;

    private static TranslatorConfig allSurfaces() {
        TranslatorConfig cfg = TestConfigs.translating();
        cfg.aiTooltip = true; // AI engine: automatic item hover (see TranslationService#isManualItemTranslation)
        cfg.aiScoreboard = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.aiActionBar = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.aiScreenText = true; // AI engine: this surface only translates on its own under the AI engine (see TranslationService#translateScoreboardLine)
        cfg.targetLang = "zh-TW";
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.chatMode = DisplayMode.TRANSLATION;
        cfg.actionBarMode = DisplayMode.TRANSLATION;
        cfg.screenTextMode = DisplayMode.TRANSLATION;
        cfg.scoreboardMode = DisplayMode.TRANSLATION;
        return cfg;
    }

    private static TranslationService service(TranslatorConfig cfg, Translator translator) {
        TranslationCache gt = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        TranslationCache ai = new TranslationCache(translator, cfg.targetLang, DIRECT, 1000);
        return new TranslationService(cfg, gt, ai);
    }

    /** Two client ticks: the coalescer holds one tick after growth, then sends. */
    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    /** Renders the fixed English of an auction tooltip and echoes every ⟦…⟧ token. */
    private static Translator auctionTranslator(List<String> sent) {
        return (text, target) -> {
            synchronized (sent) {
                sent.add(text);
            }
            return new TranslationResult(text
                    .replace("LEGENDARY SWORD", "傳說劍")
                    .replace("Buy it now:", "直購價：")
                    .replace("Seller:", "賣家：")
                    .replace("coins", "硬幣"), "en");
        };
    }

    private static PersistentStore inlineStore(Map<String, String> backing) {
        return new PersistentStore() {
            @Override public String get(String key) { return backing.get(key); }
            @Override public void put(String key, String value) { backing.put(key, value); }
            @Override public void clear() { backing.clear(); }
            @Override public void remove(String key) { backing.remove(key); }
        };
    }

    // ---- B1: one masking choke point, one key for every entry point ----

    @Test
    void everyEntryPointDerivesTheSameMaskedKeyForAFrameName() {
        String input = "Seller: [MVP+] Seller_42";
        String family = "Seller: ⟦0⟧ ⟦1⟧";
        Map<String, String> keyByEntry = new LinkedHashMap<>();
        keyByEntry.put("lookup", firstKey(s -> {
            s.translateItemLine(input);
            pump(s);
        }));
        keyByEntry.put("warm", firstKey(s -> s.warmTooltipBatch(List.of(input))));
        keyByEntry.put("chat", firstKey(s -> {
            s.translateChatAsync(input, ignored -> { });
            pump(s);
        }));
        keyByEntry.put("actionbar", firstKey(s -> {
            s.requestActionBarAsync(input, ignored -> { });
            pump(s);
        }));
        keyByEntry.put("liveScreen", firstKey(s -> {
            s.requestLiveScreenTextAsync(input, ignored -> { });
            pump(s);
        }));
        for (Map.Entry<String, String> entry : keyByEntry.entrySet()) {
            assertEquals(family, entry.getValue(), entry.getKey() + " must send the family key");
        }

        // ready + invalidate: another seller of the same shape fills the family row; the
        // readiness check of THIS input consults that row, and retranslating THIS input
        // evicts and re-buys exactly that row.
        List<String> sent = new ArrayList<>();
        TranslationService s = service(allSurfaces(), auctionTranslator(sent));
        assertFalse(s.isTooltipTranslationReady(input));
        s.warmTooltipBatch(List.of("Seller: [VIP] Other_9"));
        assertTrue(s.isTooltipTranslationReady(input), "ready reads the same family key");
        s.retranslate(List.of(input));
        pump(s);
        assertEquals(List.of(family, family), sent, "invalidate hit the same key and re-bought it");
        TranslationDecision shown = s.translateItemLine(input);
        assertTrue(shown.changed());
        assertEquals("賣家： [MVP+] Seller_42", shown.translated());
    }

    private static String firstKey(Consumer<TranslationService> entry) {
        List<String> sent = new ArrayList<>();
        TranslationService s = service(allSurfaces(), auctionTranslator(sent));
        entry.accept(s);
        assertEquals(1, sent.size(), "exactly one request: " + sent);
        return sent.get(0);
    }

    // ---- family reuse: the whole point of P1.2 ----

    @Test
    void fiftyDifferentSellersOfOneTooltipShapeBuyExactlyOneTranslation() {
        List<String> sent = new ArrayList<>();
        TranslationService s = service(allSurfaces(), auctionTranslator(sent));
        String[] ranks = {"[VIP]", "[VIP+]", "[MVP]", "[MVP+]", "[MVP++]"};
        List<String> sellers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sellers.add(i % 3 == 0 ? "Seller_" + i : i % 3 == 1 ? "xX" + i + "Xx" : "shop" + (1000 + i));
        }
        for (int i = 0; i < sellers.size(); i++) {
            String seller = sellers.get(i);
            String tooltip = "LEGENDARY SWORD ⟦PB0⟧ Seller: " + ranks[i % ranks.length] + " " + seller
                    + " ⟦PB1⟧ Buy it now: " + (1_000_000 + i * 7_919) + " coins";
            s.warmTooltipBatch(List.of(tooltip));
            TranslationDecision d = s.translateItemLine(tooltip);
            assertTrue(d.changed(), tooltip);
            assertTrue(d.translated().contains("賣家： " + ranks[i % ranks.length] + " " + seller + " "),
                    "each tooltip shows its own seller: " + d.translated());
            for (String other : sellers) {
                if (!other.equals(seller)) {
                    assertFalse(d.translated().contains(" " + other + " "), "no seller leaks: " + other);
                }
            }
        }
        // Segment cache (design-segment-cache.md): the rarity line ("LEGENDARY SWORD")
        // resolves locally from P1.7's term table (0 requests, never reaches the
        // translator at all — it no longer needs to be glued to the trade fields to do
        // so), and the Seller/Buy-it-now rows are now independent, reusable units instead
        // of one opaque multi-row blob; each is still bought exactly ONCE for all 50
        // sellers/prices (the point this test protects), just as two small units rather
        // than one big one.
        assertEquals(2, sent.size(), "two reusable segments serve every seller/price: " + sent);
        assertEquals(Set.of("Seller: ⟦0⟧ ⟦1⟧", "Buy it now: ⟦MT0⟧ coins"),
                Set.copyOf(sent));
    }

    // ---- S2: a frame name glued to a value must not evict the shared family ----

    @Test
    void frameNameGluedToANumberStillDisplaysAndNeverEvictsTheSharedFamily() {
        AtomicInteger calls = new AtomicInteger();
        Translator glue = (text, target) -> {
            calls.incrementAndGet();
            // The translation puts the rival's name straight against the number.
            return new TranslationResult(text.replace("⟦MT0⟧ behind ⟦0⟧", "落後⟦0⟧⟦MT0⟧"), "en");
        };
        TranslationService s = service(allSurfaces(), glue);
        for (String rival : List.of("wi11", "Rival_2", "zz9_top")) {
            String row = "5 behind " + rival;
            s.translateScoreboardLine(row);
            pump(s);
            TranslationDecision d = s.translateScoreboardLine(row);
            assertTrue(d.changed(), row);
            assertEquals("落後" + rival + "5", d.translated());
        }
        assertEquals(1, calls.get(), "no R17 eviction/re-buy loop across different rivals");

        // A TAB-listed name keeps the strict whole-word rule exactly as before.
        TranslationService strict = service(allSurfaces(), glue);
        strict.setProtectedNames(() -> Set.of("wi11"));
        strict.translateScoreboardLine("5 behind wi11");
        pump(strict);
        assertFalse(strict.translateScoreboardLine("5 behind wi11").changed(),
                "TAB names still require a whole-word survivor");
    }

    @Test
    void aTranslationThatLostTheFrameNameNeverDisplaysAndSelfHealsOnce() {
        AtomicInteger calls = new AtomicInteger();
        Translator eater = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace(
                    "You passed ⟦0⟧ in the Nether Wart Collection Leaderboard!",
                    "你在地獄疙瘩收集排行榜超越了某人！"), "en");
        };
        TranslationService s = service(allSurfaces(), eater);
        String row = "You passed Rival_1 in the Nether Wart Collection Leaderboard!";

        s.translateChat(row);
        pump(s);
        assertEquals(1, calls.get());
        assertFalse(s.translateChat(row).changed(), "a translation without the name never shows");
        s.translateChat(row);
        pump(s);
        assertEquals(2, calls.get(), "one self-heal re-buy");
        assertFalse(s.translateChat(row).changed());
        s.translateChat(row);
        pump(s);
        assertEquals(2, calls.get(), "debounced: no per-frame eviction storm");
    }

    // ---- S4: Google wire with adjacent sentinels ----

    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{10,}");

    /** Formats every glued sentinel run the way a number-localising endpoint might. */
    private static String withThousandsSeparators(String q) {
        Matcher m = LONG_DIGIT_RUN.matcher(q);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < run.length(); i++) {
                if (i > 0 && (run.length() - i) % 3 == 0) grouped.append(',');
                grouped.append(run.charAt(i));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(grouped.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String googleResponse(String translated) {
        return "[[[\"" + translated.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"src\",null,null]],null,\"en\"]";
    }

    private static String qOf(String url) {
        return java.net.URLDecoder.decode(url.substring(url.indexOf("&q=") + 3),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void gluedGoogleSentinelsAroundAMaskedSellerRestoreAndServeEverySeller() {
        List<String> wire = new ArrayList<>();
        HttpTransport transport = url -> {
            String q = qOf(url);
            wire.add(q);
            return googleResponse(q.replace("Seller: ", "賣家："));
        };
        TranslatorConfig cfg = allSurfaces();
        cfg.aiTooltip = false; // GT engine for tooltips: manual (translate-key-driven) mode
        GoogleFreeTranslator gtTranslator = new GoogleFreeTranslator(transport, "auto");
        TranslationCache gt = new TranslationCache(gtTranslator, cfg.targetLang, DIRECT, 100);
        TranslationCache ai = new TranslationCache(gtTranslator, cfg.targetLang, DIRECT, 100);
        TranslationService s = new TranslationService(cfg, gt, ai);

        for (String seller : List.of("wi11", "Zed_42")) {
            String input = "⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧" + seller + "⟦/CS1⟧";
            // GT/manual mode: a plain hover sends nothing (see isManualItemTranslation());
            // requestItemLines is the explicit translate-key equivalent that still sends.
            s.requestItemLines(List.of(input));
            pump(s);
            TranslationDecision d = s.translateItemLine(input);
            assertTrue(d.changed(), input);
            assertEquals("⟦CS0⟧賣家：⟦/CS0⟧⟦CS1⟧" + seller + "⟦/CS1⟧", d.translated());
        }
        assertEquals(List.of("70001Seller: 70002700037000470005"), wire,
                "one glued GT request serves both sellers; the name itself never leaves the client");
    }

    @Test
    void separatorMangledGluedSentinelsFailTemporarilyAndNeverLearnKeepOriginal() {
        List<String> wire = new ArrayList<>();
        HttpTransport transport = url -> {
            String q = qOf(url);
            wire.add(q);
            return googleResponse(withThousandsSeparators(q.replace("Seller: ", "賣家：")));
        };
        long[] now = {0L};
        Map<String, String> disk = new HashMap<>();
        Map<String, String> failures = new HashMap<>();
        TranslationCache gt = new TranslationCache(new GoogleFreeTranslator(transport, "auto"),
                "zh-TW", DIRECT, 100, 1_000L, () -> now[0], inlineStore(disk));
        gt.setFailureStore(inlineStore(failures));
        String key = "⟦CS0⟧Seller: ⟦/CS0⟧⟦CS1⟧⟦0⟧⟦/CS1⟧";

        for (int attempt = 1; attempt <= 4; attempt++) {
            gt.requestAsync(key);
            now[0] += 3_600_000L; // well past any backoff
        }
        assertEquals(4, wire.size(), "every attempt is a real retry");
        assertEquals("70001賣家：70,002,700,037,000,470,005",
                withThousandsSeparators("70001賣家：70002700037000470005"));
        assertEquals(null, gt.getCached(key), "mangled sentinels never become a translation");
        for (String value : disk.values()) {
            assertFalse(value.contains("MT_KEEP_ORIGINAL"), "never learned keep-original: " + disk);
        }
        assertFalse(failures.isEmpty(), "the failure ledger holds the temporary state");
        for (String value : failures.values()) {
            assertTrue(value.startsWith("temporary:"), "temporary, not terminal: " + failures);
        }
    }

    // ---- S5: lobby colour topologies are not churn ----

    @Test
    void lobbyArrivalsWithDifferentRankColouringsAreNotSuppressedAsChurn() {
        TranslatorConfig cfg = allSurfaces();
        cfg.churnGuard = true;
        cfg.churnVariantThreshold = 4;
        AtomicInteger calls = new AtomicInteger();
        Translator lobby = (text, target) -> {
            calls.incrementAndGet();
            return new TranslationResult(text.replace("joined the lobby!", "加入了大廳！"), "en");
        };
        TranslationService s = service(cfg, lobby);
        List<String> arrivals = List.of(
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧+⟦/CS1⟧⟦CS0⟧] Arr_1⟦/CS0⟧⟦CS2⟧ joined the lobby!⟦/CS2⟧",
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧++⟦/CS1⟧⟦CS0⟧] Arr_2⟦/CS0⟧⟦CS2⟧ joined the lobby!⟦/CS2⟧",
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧+⟦/CS1⟧⟦CS2⟧] Arr_3⟦/CS2⟧⟦CS3⟧ joined the lobby!⟦/CS3⟧",
                "⟦CS0⟧[MVP⟦/CS0⟧⟦CS1⟧++⟦/CS1⟧⟦CS2⟧] Arr_4⟦/CS2⟧⟦CS3⟧ joined the lobby!⟦/CS3⟧");
        List<String> shown = new ArrayList<>();
        for (String arrival : arrivals) {
            s.translateChatAsync(arrival, shown::add);
            pump(s);
        }
        assertEquals(4, calls.get(), "four colour topologies of two texts: all translated");
        for (int i = 0; i < arrivals.size(); i++) {
            assertTrue(shown.get(i) != null && shown.get(i).contains("Arr_" + (i + 1))
                    && shown.get(i).contains("加入了大廳！"), "arrival " + i + ": " + shown);
        }
        // A fifth arrival of an already-bought topology is a plain cache hit.
        s.translateChatAsync(arrivals.get(0).replace("Arr_1", "Arr_5"), shown::add);
        pump(s);
        assertEquals(4, calls.get());
        assertTrue(shown.get(4).contains("Arr_5"));
    }
}
