package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.hub.HubFile;
import com.dragonmeow.nyanlex.hub.HubIndex;
import com.dragonmeow.nyanlex.hub.HubKeyHash;
import com.dragonmeow.nyanlex.hub.HubLocalCache;
import com.dragonmeow.nyanlex.hub.HubSource;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.TranslationResult;
import com.dragonmeow.nyanlex.translate.Translator;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every fake here is inline; nothing touches the network or a real cache. */
class LangPackBuilderTest {
    private static final String S = "§";

    private static LangPackBuilder.Converter converter() {
        return new LangPackBuilder.Converter("zh-TW");
    }

    private static LangPackBuilder.Row single(String en, String zh) {
        LangPackBuilder.Outcome outcome = converter().convert(en, zh);
        assertEquals(List.of(), outcome.skipped(), "unexpected skip for " + en);
        assertEquals(1, outcome.rows().size());
        return outcome.rows().get(0);
    }

    /** The keys a live service asks its hub lookup for when a widget draws {@code displayed}. */
    private static List<String> runtimeKeys(String displayed) {
        Executor direct = Runnable::run;
        Translator inert = (text, target) -> new TranslationResult(text, "en");
        TranslatorConfig config = TestConfigs.translating();
        config.translationRequestsEnabled = false;
        config.aiScreenText = true;
        TranslationService service = new TranslationService(config,
                new TranslationCache(inert, config.targetLang, direct, 64),
                new TranslationCache(inert, config.targetLang, direct, 64));
        List<String> keys = new ArrayList<>();
        service.setHubLookup(key -> {
            keys.add(key);
            return null;
        });
        FabricTextStyle.renderTranslated("screenText", Component.literal(displayed), service::translateScreenText);
        return keys;
    }

    @Test
    void plainTextKeyIsTheKeyTheRunningServiceLooksUp() {
        LangPackBuilder.Row row = single("Render distance for terrain", "地形的渲染距離");
        assertEquals(List.of(row.key()), runtimeKeys("Render distance for terrain"));
        assertEquals("Render distance for terrain", row.key());
        assertEquals("地形的渲染距離", row.value());
    }

    @Test
    void edgeWhitespaceIsPartOfTheKeyExactlyAsDisplayed() {
        LangPackBuilder.Row row = single("Distance: ", "距離：");
        assertEquals("Distance: ", row.key());
        assertEquals("Distance: ", runtimeKeys("Distance: ").get(0), "the exact text is asked first, its trimmed form second");
    }

    @Test
    void multiRunColourLineUsesColourMarkers() {
        String en = S + "cRed " + S + "aGreen text here";
        String zh = S + "c紅色 " + S + "a綠色的文字";
        LangPackBuilder.Row row = single(en, zh);
        assertEquals("⟦CS0⟧Red⟦/CS0⟧ ⟦CS1⟧Green text here⟦/CS1⟧", row.key());
        assertEquals("⟦CS0⟧紅色⟦/CS0⟧ ⟦CS1⟧綠色的文字⟦/CS1⟧", row.value());
        assertEquals(List.of(row.key()), runtimeKeys(en));
    }

    @Test
    void wholeLineSingleColourCarriesNoMarker() {
        LangPackBuilder.Row row = single(S + "cWhole red line", S + "c整行紅色");
        assertEquals("Whole red line", row.key());
        assertEquals("整行紅色", row.value());
    }

    @Test
    void wrappedLinesOfOneParagraphShareOneKeyAndBlankLinesSplitParagraphs() {
        LangPackBuilder.Outcome joined = converter().convert("First line here\nSecond line here", "第一行在這\n第二行在這");
        assertEquals(1, joined.rows().size());
        assertTrue(joined.rows().get(0).key().contains("⟦PB0⟧"));
        assertEquals(List.of(joined.rows().get(0).key()), runtimeKeys("First line here\nSecond line here"));

        LangPackBuilder.Outcome split = converter().convert("Para one is here\n\nPara two is here", "段落一在這\n\n段落二在這");
        assertEquals(2, split.rows().size());
        assertEquals("Para one is here", split.rows().get(0).key());
        assertEquals("Para two is here", split.rows().get(1).key());
        assertEquals("段落二在這", split.rows().get(1).value());
    }

    @Test
    void surplusSoftBreaksInTheTranslationAreFlattened() {
        LangPackBuilder.Outcome outcome = converter().convert(
                "Larger numbers will improve how distant terrain looks\nbut will increase memory and GPU usage.",
                "數值越大，遠處地形看起來越好，\n但會增加記憶體與 GPU 用量。");
        assertEquals(List.of(), outcome.skipped());
        assertEquals(1, outcome.rows().size());
        assertEquals(0, com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(outcome.rows().get(0).value()));
    }

    @Test
    void missingSoftBreaksAreRecreatedInProportionToTheEnglishRows() {
        String en = "First sentence ends here.\nSecond sentence ends here.\nThird sentence ends here.";
        String zh = "第一句在這裡結束。第二句在這裡結束。第三句在這裡結束。";
        LangPackBuilder.Outcome outcome = converter().convert(en, zh);
        assertEquals(List.of(), outcome.skipped());
        LangPackBuilder.Row row = outcome.rows().get(0);
        assertEquals(com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(row.key()),
                com.dragonmeow.nyanlex.translate.ParagraphModel.countBreakTokens(row.value()));
        assertEquals(zh, com.dragonmeow.nyanlex.translate.ParagraphModel.flattenBreakTokens(row.value()));
        assertTrue(row.value().contains("。 ⟦PB0⟧ 第二"));
    }

    @Test
    void paragraphCountMismatchIsSkipped() {
        LangPackBuilder.Outcome outcome = converter().convert("Para one is here\n\nPara two is here", "只有一段");
        assertEquals(List.of(), outcome.rows());
        assertEquals(List.of(LangPackBuilder.Skip.PARAGRAPH_MISMATCH), outcome.skipped());
    }

    @Test
    void numericArgumentUsesTheNumberNormalizedKey() {
        LangPackBuilder.Row row = single("Render distance: %d chunks", "渲染距離：%d 區塊");
        assertEquals("Render distance: ⟦MT0⟧ chunks", row.key());
        assertEquals("渲染距離：⟦MT0⟧ 區塊", row.value());
    }

    @Test
    void reorderedNumberedArgumentsStayAlignedToTheKeySlots() {
        LangPackBuilder.Row row = single("Use %1$d of %2$d slots", "共 %2$d 個欄位中使用 %1$d 個");
        assertEquals("Use ⟦MT0⟧ of ⟦MT1⟧ slots", row.key());
        assertEquals("共 ⟦MT1⟧ 個欄位中使用 ⟦MT0⟧ 個", row.value());
    }

    @Test
    void percentAndDecimalArguments() {
        LangPackBuilder.Row percent = single("Brightness: %d%%", "亮度：%d%%");
        assertEquals("Brightness: ⟦MT0⟧", percent.key());
        LangPackBuilder.Row decimal = single("Scale: %.1f times", "縮放：%.1f 倍");
        assertEquals("Scale: ⟦MT0⟧ times", decimal.key());
    }

    @Test
    void aPercentSignThatIsNotAFormatSpecifierStaysLiteral() {
        LangPackBuilder.Row row = single("Runtime % for threads", "執行緒的執行時間 %");
        assertEquals("Runtime % for threads", row.key());
        assertEquals("執行緒的執行時間 %", row.value());
        LangPackBuilder.Row doubled = single("Charge 100%% of the time", "一律充電 100%%");
        assertEquals("Charge 100% of the time", doubled.key());
    }

    @Test
    void nonNumericArgumentIsSkippedAndCounted() {
        LangPackBuilder.Outcome outcome = converter().convert("Selected %s profile", "已選取 %s 設定檔");
        assertEquals(List.of(), outcome.rows());
        assertEquals(List.of(LangPackBuilder.Skip.NON_NUMERIC_ARG), outcome.skipped());
        assertEquals(List.of(LangPackBuilder.Skip.NON_NUMERIC_ARG),
                converter().convert("Selected %1$s profile", "已選取 %1$s 設定檔").skipped());
    }

    @Test
    void argumentListsThatDifferAreSkipped() {
        LangPackBuilder.Outcome outcome = converter().convert("Chunks: %d", "區塊");
        assertEquals(List.of(), outcome.rows());
        assertEquals(List.of(LangPackBuilder.Skip.FORMAT_MISMATCH), outcome.skipped());
    }

    @Test
    void untranslatedAndEmptyTextIsSkipped() {
        assertEquals(List.of(LangPackBuilder.Skip.UNCHANGED),
                converter().convert("Quality settings", "Quality settings").skipped());
        assertEquals(List.of(LangPackBuilder.Skip.EMPTY), converter().convert("", "x").skipped());
        assertEquals(List.of(LangPackBuilder.Skip.NOT_LOOKED_UP), converter().convert("12345", "12345").skipped());
    }

    @Test
    void valueWithUrlIsRejected() {
        LangPackBuilder.Outcome outcome = converter().convert("Open the page", "開啟 https://example.com/page");
        assertEquals(List.of(), outcome.rows());
        assertEquals(List.of(LangPackBuilder.Skip.REJECTED_VALIDATION), outcome.skipped());
    }

    @Test
    void colourStructureThatDiffersIsRejected() {
        LangPackBuilder.Outcome outcome = converter().convert(
                S + "cRed " + S + "aGreen text here", "全部同一個顏色的文字");
        assertEquals(List.of(), outcome.rows());
        assertEquals(1, outcome.skipped().size());
    }

    // ------------------------------------------------------------------ batch + repository files

    private static void writeInput(Path file, String kind, String modId, String... pairs) throws IOException {
        writeLicensed(file, kind, modId, "MIT", null, pairs);
    }

    private static void writeLicensed(Path file, String kind, String modId, String license, String excluded,
            String... pairs) throws IOException {
        StringBuilder json = new StringBuilder("{\"kind\":\"" + kind + "\",\"modId\":"
                + (modId == null ? "null" : "\"" + modId + "\"")
                + (license == null ? "" : ",\"license\":" + quote(license))
                + (excluded == null ? "" : ",\"excluded\":" + quote(excluded)) + ",\"entries\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) json.append(',');
            json.append("{\"ns\":\"x\",\"key\":\"option.k").append(i).append("\",\"en\":")
                    .append(quote(pairs[i])).append(",\"zh_tw\":").append(quote(pairs[i + 1])).append('}');
        }
        json.append("]}");
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private static String quote(String text) {
        return new com.google.gson.Gson().toJson(text);
    }

    @Test
    void writtenFileReadsBackAndHitsThroughTheLocalCache(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Files.createDirectories(in);
        writeInput(in.resolve("a.json"), "mod", "examplemod",
                "Render distance for terrain", "地形的渲染距離",
                "Chunks: %d", "區塊：%d",
                "Selected %s profile", "已選取 %s 設定檔",
                S + "cRed " + S + "aGreen text here", S + "c紅色 " + S + "a綠色的文字");
        Path out = dir.resolve("hub");

        LangPackBuilder.Result result = LangPackBuilder.build(List.of(in.resolve("a.json")), out, "zh-TW", "hostmod", true);

        Path file = out.resolve("mods/examplemod/zh-tw.json");
        assertTrue(Files.isRegularFile(file));
        assertEquals(file, result.written().get("examplemod"));
        LangPackBuilder.Target target = result.targets().get("examplemod");
        assertEquals(4, target.entries);
        assertEquals(3, target.rows.size());
        assertEquals(1, target.skipped.get(LangPackBuilder.Skip.NON_NUMERIC_ARG));

        HubFile read = HubFile.read(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(3, read.entries().size());
        assertEquals("地形的渲染距離", read.entries().get(HubKeyHash.of("Render distance for terrain")));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).indexOf("Render distance") < 0,
                "the file must carry no source text");

        HubLocalCache cache = new HubLocalCache(dir.resolve("cache"), "zh-TW");
        cache.mergeFromFile(read, HubSource.mod("examplemod"));
        assertEquals("地形的渲染距離", cache.get("Render distance for terrain"));
        assertEquals("區塊：⟦MT0⟧", cache.get("Chunks: ⟦MT0⟧"));
        assertEquals("⟦CS0⟧紅色⟦/CS0⟧ ⟦CS1⟧綠色的文字⟦/CS1⟧",
                cache.get("⟦CS0⟧Red⟦/CS0⟧ ⟦CS1⟧Green text here⟦/CS1⟧"));

        try (Reader reader = Files.newBufferedReader(out.resolve("index.json"), StandardCharsets.UTF_8)) {
            HubIndex.LanguageStats stats = HubIndex.read(reader).mod("examplemod", "zh-TW");
            assertNotNull(stats);
            assertEquals(3, stats.rows());
            assertEquals(Files.size(file), stats.bytes());
            assertEquals(64, stats.sha256().length());
            assertNotNull(stats.updatedAt());
        }
    }

    @Test
    void liveServiceDisplaysTheStoredRowThroughTheRealHubLookup(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("a.json");
        writeInput(in, "mod", "examplemod", "Render distance for terrain", "地形的渲染距離");
        Path out = dir.resolve("hub");
        LangPackBuilder.build(List.of(in), out, "zh-TW", "hostmod", false);

        HubLocalCache cache = new HubLocalCache(dir.resolve("cache"), "zh-TW");
        cache.mergeFromFile(HubFile.read(Files.readString(out.resolve("mods/examplemod/zh-tw.json"),
                StandardCharsets.UTF_8)), HubSource.mod("examplemod"));

        Executor direct = Runnable::run;
        Translator inert = (text, target) -> new TranslationResult(text, "en");
        TranslatorConfig config = TestConfigs.translating();
        config.translationRequestsEnabled = false;
        config.aiScreenText = true;
        TranslationService service = new TranslationService(config,
                new TranslationCache(inert, config.targetLang, direct, 64),
                new TranslationCache(inert, config.targetLang, direct, 64));
        service.setHubLookup(cache::get);
        Component shown = FabricTextStyle.renderTranslated("screenText",
                Component.literal("Render distance for terrain"), service::translateScreenText);
        assertNotNull(shown);
        assertEquals("地形的渲染距離", shown.getString());
    }

    @Test
    void shaderPacksMergeIntoTheConfiguredTargetAndConflictsKeepTheFirst(@TempDir Path dir) throws IOException {
        Path a = dir.resolve("a.json");
        Path b = dir.resolve("b.json");
        Path c = dir.resolve("c.json");
        writeInput(a, "mod", "hostmod", "Shader settings", "光影設定");
        writeInput(b, "shaderpack", null, "Sun brightness", "太陽亮度", "Shader settings", "光影選項");
        writeInput(c, "shaderpack", null, "Moon brightness", "月亮亮度");
        Path out = dir.resolve("hub");

        LangPackBuilder.Result result = LangPackBuilder.build(List.of(a, b, c), out, "zh-TW", "hostmod", true);

        assertEquals(List.of("hostmod"), new ArrayList<>(result.targets().keySet()));
        LangPackBuilder.Target host = result.targets().get("hostmod");
        assertEquals(5, host.rows.size());
        assertEquals("光影設定", host.rows.get(HubKeyHash.of("Shader settings")));
        assertEquals(1, host.skipped.get(LangPackBuilder.Skip.CONFLICT));
        assertTrue(Files.isRegularFile(out.resolve("mods/hostmod/zh-tw.json")));
    }

    @Test
    void indexKeepsEntriesOfOtherSourcesWhenMerging(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("hub");
        Files.createDirectories(out);
        Files.writeString(out.resolve("index.json"),
                "{\"schema\":1,\"generatedAt\":\"\",\"servers\":{\"example.net\":{\"zh-tw\":{\"rows\":5,\"bytes\":9,"
                        + "\"sha256\":\"abc\",\"updatedAt\":\"t\"}}},\"modpacks\":{},\"mods\":{}}");
        Path in = dir.resolve("a.json");
        writeInput(in, "mod", "examplemod", "Render distance for terrain", "地形的渲染距離");

        LangPackBuilder.build(List.of(in), out, "zh-TW", "hostmod", true);

        try (Reader reader = Files.newBufferedReader(out.resolve("index.json"), StandardCharsets.UTF_8)) {
            HubIndex index = HubIndex.read(reader);
            assertEquals(5, index.server("example.net", "zh-TW").rows());
            assertEquals(1, index.mod("examplemod", "zh-TW").rows());
        }
    }

    @Test
    void onlyWhitelistedLicenseIdsAreAcceptedByExactComparison() {
        for (String ok : new String[] {"MIT", "mit", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "MPL-2.0",
                "LGPL-2.1", "LGPL-2.1-only", "LGPL-3.0-or-later", "GPL-2.0-only", "GPL-3.0-or-later", "GPL-3.0",
                "CC-BY-4.0", "CC-BY-SA-4.0", "CC-BY-NC-4.0", "CC-BY-NC-SA-4.0", "CC-BY-ND-4.0", "CC-BY-NC-ND-4.0",
                "Polyform-Shield-1.0.0", "LicenseRef-Polyform-Shield-1.0.0", "CC0-1.0", "Unlicense", "  MIT  "}) {
            assertTrue(LangPackBuilder.licenseAccepted(ok), ok);
        }
        for (String refused : new String[] {null, "", "  ", "All Rights Reserved", "ARR", "LicenseRef-All-Rights-Reserved",
                "LicenseRef-Custom (Modrinth: Custom)", "LicenseRef-", "LicenseRef-tr7zw-Protective-License",
                "Some Mod License (LicenseRef-Some-Mod-License)", "Create Mod License (LicenseRef-Create-Mod-License)",
                "AGPL-3.0", "AGPL-3.0-only", "AGPL-3.0-or-later", "LicenseRef-AGPL-3.0", "GPL", "Apache-1.1",
                "MPL-1.1", "MIT License", "Custom", "limited", "permit", "CC-BY", "CC0", "BSD", "Polyform-Noncommercial-1.0.0",
                "LicenseRef-Polyform-Noncommercial-1.0.0"}) {
            assertFalse(LangPackBuilder.licenseAccepted(refused), String.valueOf(refused));
        }
    }

    @Test
    void spdxExpressionsAreParsedWithAndOrParenthesesAndAnyLetterCase() {
        for (String ok : new String[] {"LGPL-2.1 AND CC-BY-NC-SA-4.0", "lgpl-2.1 and cc-by-nc-sa-4.0",
                "LGPL-2.1 and CC-BY-NC-SA-4.0", "MIT OR LicenseRef-Custom", "mit or licenseref-custom",
                "LicenseRef-Custom Or MIT", "(MIT OR Apache-2.0) AND BSD-3-Clause", "MIT AND (Apache-2.0 OR LicenseRef-Custom)",
                "((MIT))", "GPL-2.0-only WITH Classpath-exception-2.0", "GPL-2.0+", "LGPL-2.1+ AND MIT",
                "LicenseRef-Custom OR LicenseRef-Other OR MIT", "MIT AND MIT AND MIT"}) {
            assertTrue(LangPackBuilder.licenseAccepted(ok), ok);
        }
        for (String refused : new String[] {"LGPL-2.1 AND LicenseRef-All-Rights-Reserved",
                "lgpl-2.1 and licenseref-all-rights-reserved", "MIT AND AGPL-3.0", "mit and agpl-3.0-only",
                "LicenseRef-Custom OR LicenseRef-Other", "licenseref-custom or all rights reserved",
                "(MIT OR Apache-2.0) AND LicenseRef-Custom",
                // AND binds tighter than OR: this is  LicenseRef-Custom  OR  (MIT AND AGPL-3.0)
                "LicenseRef-Custom OR MIT AND AGPL-3.0",
                // malformed expressions are refused rather than guessed at
                "MIT AND", "AND MIT", "MIT OR OR MIT", "(MIT", "MIT)", "()", "MIT MIT", "MIT WITH", "MIT AND WITH",
                "MIT; AGPL-3.0"}) {
            assertFalse(LangPackBuilder.licenseAccepted(refused), refused);
        }
        // precedence the other way round: (A AND B) OR C
        assertTrue(LangPackBuilder.licenseAccepted("MIT AND AGPL-3.0 OR Apache-2.0"));
        assertTrue(LangPackBuilder.licenseAccepted("AGPL-3.0 OR MIT AND Apache-2.0"));
    }

    @Test
    void refusedLicensesAndFlaggedFilesProduceNoRowsAndAreReported(@TempDir Path dir) throws IOException {
        Path ok = dir.resolve("ok.json");
        Path closed = dir.resolve("closed.json");
        Path flagged = dir.resolve("flagged.json");
        Path shader = dir.resolve("shader.json");
        writeLicensed(ok, "mod", "openmod", "MIT", null, "Render distance for terrain", "地形的渲染距離");
        writeLicensed(closed, "mod", "closedmod", "LicenseRef-All-Rights-Reserved", null, "Open the menu", "開啟選單");
        writeLicensed(flagged, "mod", "flaggedmod", "MIT", "license", "Open the list", "開啟清單"); // hint only
        writeLicensed(shader, "shaderpack", null, "All Rights Reserved", "license", "Sun brightness", "太陽亮度");
        Path out = dir.resolve("hub");

        LangPackBuilder.Result result = LangPackBuilder.build(List.of(ok, closed, flagged, shader), out, "zh-TW", "hostmod", true);

        assertEquals(List.of("flaggedmod", "openmod"), new ArrayList<>(result.written().keySet()));
        assertTrue(Files.isRegularFile(out.resolve("mods/openmod/zh-tw.json")));
        assertFalse(Files.exists(out.resolve("mods/closedmod")));
        assertTrue(Files.exists(out.resolve("mods/flaggedmod")), "the excluded hint is advisory, the license decides");
        assertFalse(Files.exists(out.resolve("mods/hostmod")));
        assertEquals(4, result.licensing().size());
        assertEquals(2, result.licensing().stream().filter(LangPackBuilder.Licensing::accepted).count());
        try (Reader reader = Files.newBufferedReader(out.resolve("index.json"), StandardCharsets.UTF_8)) {
            HubIndex index = HubIndex.read(reader);
            assertNotNull(index.mod("openmod", "zh-TW"));
            assertEquals(null, index.mod("closedmod", "zh-TW"));
        }
    }

    @Test
    void shaderOptionLabelsAlsoGetTheColonFormThatTheSettingsScreenDraws(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("s.json");
        writeInput(in, "shaderpack", null, "Waving plants", "波浪植物");
        LangPackBuilder.Result result = LangPackBuilder.build(List.of(in), dir.resolve("hub"), "zh-TW", "hostmod", false);
        LangPackBuilder.Target host = result.targets().get("hostmod");
        assertEquals("波浪植物", host.rows.get(HubKeyHash.of("Waving plants")));
        assertEquals("波浪植物： ", host.rows.get(HubKeyHash.of("Waving plants: ")));
    }

    @Test
    void builtNumberRowAnswersTheLiveLookupWithTheNumbersOnScreen(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("a.json");
        writeInput(in, "mod", "examplemod", "Render distance: %d chunks", "渲染距離：%d 區塊",
                S + "fUse fog occlusion", S + "f使用霧氣遮蔽");
        Path out = dir.resolve("hub");
        LangPackBuilder.build(List.of(in), out, "zh-TW", null, false);
        HubLocalCache cache = new HubLocalCache(dir.resolve("cache"), "zh-TW");
        cache.mergeFromFile(HubFile.read(Files.readString(out.resolve("mods/examplemod/zh-tw.json"),
                StandardCharsets.UTF_8)), HubSource.mod("examplemod"));
        Executor direct = Runnable::run;
        Translator inert = (text, target) -> new TranslationResult(text, "en");
        TranslatorConfig config = TestConfigs.translating();
        config.translationRequestsEnabled = false;
        config.aiScreenText = true;
        TranslationService service = new TranslationService(config,
                new TranslationCache(inert, config.targetLang, direct, 64),
                new TranslationCache(inert, config.targetLang, direct, 64));
        service.setHubLookup(cache::get);
        assertEquals("渲染距離：21區塊", service.translateScreenText("Render distance: 21 chunks").translated());
        assertEquals("渲染距離：4區塊", service.translateScreenText("Render distance: 4 chunks").translated());
        assertEquals(S + "f使用霧氣遮蔽", service.translateScreenString(S + "fUse fog occlusion").translated());
        assertEquals("使用霧氣遮蔽", service.translateScreenText("Use fog occlusion").translated());
    }
}
