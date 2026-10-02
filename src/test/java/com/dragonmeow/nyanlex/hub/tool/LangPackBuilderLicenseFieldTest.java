package com.dragonmeow.nyanlex.hub.tool;

import com.dragonmeow.nyanlex.TestConfigs;
import com.dragonmeow.nyanlex.cache.TranslationCache;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.fabric.FabricTextStyle;
import com.dragonmeow.nyanlex.hub.HubDownloadResult;
import com.dragonmeow.nyanlex.hub.HubDownloadState;
import com.dragonmeow.nyanlex.hub.HubDownloader;
import com.dragonmeow.nyanlex.hub.HubFile;
import com.dragonmeow.nyanlex.hub.HubIndex;
import com.dragonmeow.nyanlex.hub.HubLocalCache;
import com.dragonmeow.nyanlex.hub.HubPlan;
import com.dragonmeow.nyanlex.hub.HubRepository;
import com.dragonmeow.nyanlex.hub.HubSource;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.HttpTransport;
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
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An LGPL source keeps its license: the file and its index entry carry a {@code license}, a file under a
 * simple license carries none (it stays byte-for-byte what it was), and a file with the field goes through
 * the player's download, merge and lookup exactly like any other. Every fake here is inline.
 */
class LangPackBuilderLicenseFieldTest {
    private static final String BASE = "https://example.com/hub";

    private static void input(Path file, String kind, String modId, String license, String... pairs)
            throws IOException {
        StringBuilder json = new StringBuilder("{\"kind\":\"" + kind + "\",\"modId\":"
                + (modId == null ? "null" : "\"" + modId + "\"") + ",\"license\":" + quote(license) + ",\"entries\":[");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) json.append(',');
            json.append("{\"ns\":\"x\",\"key\":\"k").append(i).append("\",\"en\":").append(quote(pairs[i]))
                    .append(",\"zh_tw\":").append(quote(pairs[i + 1])).append('}');
        }
        Files.writeString(file, json.append("]}"), StandardCharsets.UTF_8);
    }

    private static String quote(String text) {
        return new com.google.gson.Gson().toJson(text);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ which sources need a license

    @Test
    void onlyAnInputAcceptedThanksToAnLgplIdPassesALicenseOn() {
        assertEquals("LGPL-3.0-only", LangPackBuilder.copyleftLicense("LGPL-3.0-only"));
        assertEquals("LGPL-3.0-or-later", LangPackBuilder.copyleftLicense("LGPL-3.0-or-later"));
        assertEquals("LGPL-2.1-only", LangPackBuilder.copyleftLicense("LGPL-2.1-only"));
        assertEquals("LGPL-2.1-or-later", LangPackBuilder.copyleftLicense("LGPL-2.1-or-later"));
        // letter case and the deprecated SPDX spellings are written out canonically
        assertEquals("LGPL-3.0-only", LangPackBuilder.copyleftLicense("lgpl-3.0-only"));
        assertEquals("LGPL-3.0-only", LangPackBuilder.copyleftLicense("LGPL-3.0"));
        assertEquals("LGPL-3.0-or-later", LangPackBuilder.copyleftLicense("LGPL-3.0+"));
        assertEquals("LGPL-2.1-only", LangPackBuilder.copyleftLicense("  LGPL-2.1  "));
        assertEquals("LGPL-2.1-or-later", LangPackBuilder.copyleftLicense("LGPL-2.1+"));
        assertEquals("LGPL-3.0-only AND mit", LangPackBuilder.copyleftLicense("lgpl-3.0 and mit"));
        assertEquals("(LGPL-2.1-only OR GPL-2.0-only)", LangPackBuilder.copyleftLicense("(LGPL-2.1 or GPL-2.0-only)"));
        // a simple license carries itself: no field, the repository default applies
        assertNull(LangPackBuilder.copyleftLicense("MIT"));
        assertNull(LangPackBuilder.copyleftLicense("Apache-2.0 AND BSD-3-Clause"));
        assertNull(LangPackBuilder.copyleftLicense("LicenseRef-Polyform-Shield-1.0.0"));
        assertNull(LangPackBuilder.copyleftLicense("MIT OR LGPL-3.0-only"));
        // refused or missing licenses produce nothing at all
        assertNull(LangPackBuilder.copyleftLicense("GPL-3.0-only"));
        assertNull(LangPackBuilder.copyleftLicense("LGPL-3.0-only AND GPL-3.0-only"));
        assertNull(LangPackBuilder.copyleftLicense("LGPL-2.1 AND CC-BY-NC-SA-4.0"));
        assertNull(LangPackBuilder.copyleftLicense("LGPL-2.0-only"));
        assertNull(LangPackBuilder.copyleftLicense(null));
        assertNull(LangPackBuilder.copyleftLicense(""));
    }

    @Test
    void theSimpleLicenseCheckIgnoresLgplSoItSeparatesTheTwoKinds() {
        assertTrue(LangPackBuilder.simpleLicenseAccepted("MIT"));
        assertTrue(LangPackBuilder.simpleLicenseAccepted("MIT OR LGPL-3.0-only"));
        assertFalse(LangPackBuilder.simpleLicenseAccepted("LGPL-3.0-only"));
        assertFalse(LangPackBuilder.simpleLicenseAccepted("MIT AND LGPL-3.0-only"));
        assertFalse(LangPackBuilder.simpleLicenseAccepted("GPL-3.0-only"));
    }

    // ------------------------------------------------------------------ what gets written

    @Test
    void lgplFilesCarryTheirLicenseInTheFileAndTheIndexAndSimpleFilesNone(@TempDir Path dir) throws IOException {
        Path mit = dir.resolve("mit.json");
        Path lgpl = dir.resolve("lgpl.json");
        Path later = dir.resolve("later.json");
        Path oldName = dir.resolve("old.json");
        Path either = dir.resolve("either.json");
        Path both = dir.resolve("both.json");
        input(mit, "mod", "mitmod", "MIT", "Render distance for terrain", "地形的渲染距離");
        input(lgpl, "mod", "lgplmod", "LGPL-3.0-only", "Open the menu", "開啟選單");
        input(later, "mod", "latermod", "LGPL-2.1-or-later", "Close the menu", "關閉選單");
        input(oldName, "mod", "oldmod", "LGPL-3.0", "Open the list", "開啟清單");
        input(either, "mod", "eithermod", "MIT OR LGPL-3.0-only", "Close the list", "關閉清單");
        input(both, "mod", "bothmod", "MIT AND LGPL-2.1", "Open the page", "開啟頁面");
        Path out = dir.resolve("hub");

        LangPackBuilder.Result result = LangPackBuilder.build(List.of(mit, lgpl, later, oldName, either, both), out,
                "zh-TW", null, true);

        assertEquals(6, result.written().size());
        String header = "{\"schema\":2,\"format\":\"hub-hash-v1\",\"hash\":\"sha256\",\"language\":\"zh-tw\",";
        assertTrue(read(out.resolve("mods/mitmod/zh-tw.json")).startsWith(header + "\"rows\":1,\"entries\":{"));
        assertTrue(read(out.resolve("mods/eithermod/zh-tw.json")).startsWith(header + "\"rows\":1,\"entries\":{"),
                "an author who offers a simple license too leaves the file under the repository default");
        assertTrue(read(out.resolve("mods/lgplmod/zh-tw.json"))
                .startsWith(header + "\"license\":\"LGPL-3.0-only\",\"rows\":1,\"entries\":{"));
        assertTrue(read(out.resolve("mods/latermod/zh-tw.json"))
                .startsWith(header + "\"license\":\"LGPL-2.1-or-later\",\"rows\":1,\"entries\":{"));
        assertTrue(read(out.resolve("mods/oldmod/zh-tw.json"))
                .startsWith(header + "\"license\":\"LGPL-3.0-only\",\"rows\":1,\"entries\":{"));
        assertTrue(read(out.resolve("mods/bothmod/zh-tw.json"))
                .startsWith(header + "\"license\":\"MIT AND LGPL-2.1-only\",\"rows\":1,\"entries\":{"));

        assertNull(HubFile.read(read(out.resolve("mods/mitmod/zh-tw.json"))).license());
        assertEquals("LGPL-3.0-only", HubFile.read(read(out.resolve("mods/lgplmod/zh-tw.json"))).license());
        try (Reader reader = Files.newBufferedReader(out.resolve("index.json"), StandardCharsets.UTF_8)) {
            HubIndex index = HubIndex.read(reader);
            assertNull(index.mod("mitmod", "zh-TW").license());
            assertNull(index.mod("eithermod", "zh-TW").license());
            assertEquals("LGPL-3.0-only", index.mod("lgplmod", "zh-TW").license());
            assertEquals("LGPL-2.1-or-later", index.mod("latermod", "zh-TW").license());
            assertEquals("LGPL-3.0-only", index.mod("oldmod", "zh-TW").license());
            assertEquals(Files.size(out.resolve("mods/lgplmod/zh-tw.json")), index.mod("lgplmod", "zh-TW").bytes());
        }
        String indexText = read(out.resolve("index.json"));
        assertEquals(4, indexText.split("\"license\"", -1).length - 1, "exactly the four LGPL entries carry the field");
    }

    @Test
    void aFileMergedFromSeveralSourcesCarriesEveryLgplLicenseOnce(@TempDir Path dir) throws IOException {
        Path host = dir.resolve("host.json");
        Path shaderLater = dir.resolve("shader-later.json");
        Path shaderOnly = dir.resolve("shader-only.json");
        Path shaderClosed = dir.resolve("shader-closed.json");
        Path shaderMit = dir.resolve("shader-mit.json");
        input(host, "mod", "hostmod", "LGPL-3.0-only", "Shader settings", "光影設定");
        input(shaderLater, "shaderpack", null, "LGPL-3.0-or-later", "Sun brightness", "太陽亮度");
        input(shaderOnly, "shaderpack", null, "LGPL-3.0", "Moon brightness", "月亮亮度");
        input(shaderClosed, "shaderpack", null, "All Rights Reserved", "Star brightness", "星星亮度");
        input(shaderMit, "shaderpack", null, "MIT", "Cloud brightness", "雲朵亮度");
        Path out = dir.resolve("hub");

        LangPackBuilder.Result result = LangPackBuilder.build(
                List.of(host, shaderLater, shaderOnly, shaderClosed, shaderMit), out, "zh-TW", "hostmod", true);

        LangPackBuilder.Target target = result.targets().get("hostmod");
        assertEquals("LGPL-3.0-only AND LGPL-3.0-or-later", target.licenseField(),
                "the same license twice is listed once; the refused and the simple sources add nothing");
        assertEquals("LGPL-3.0-only AND LGPL-3.0-or-later", HubFile.read(read(out.resolve("mods/hostmod/zh-tw.json"))).license());
        try (Reader reader = Files.newBufferedReader(out.resolve("index.json"), StandardCharsets.UTF_8)) {
            assertEquals("LGPL-3.0-only AND LGPL-3.0-or-later", HubIndex.read(reader).mod("hostmod", "zh-TW").license());
        }
        assertTrue(LangPackBuilder.describe(result).contains("license=LGPL-3.0-only AND LGPL-3.0-or-later"));
        assertTrue(target.rows.containsValue("月亮亮度") && target.rows.containsValue("太陽亮度"));
        assertFalse(target.rows.containsValue("星星亮度"), "the refused shader pack contributes no row");
    }

    @Test
    void aSimpleSourceMergedIntoAnLgplFileFollowsTheFileAndAnOrExpressionIsParenthesisedWithAnotherLicense(
            @TempDir Path dir) throws IOException {
        Path mit = dir.resolve("mit.json");
        Path lgpl = dir.resolve("lgpl.json");
        input(mit, "mod", "hostmod", "MIT", "Shader settings", "光影設定");
        input(lgpl, "shaderpack", null, "LGPL-3.0-or-later", "Sun brightness", "太陽亮度");
        LangPackBuilder.Result mixed = LangPackBuilder.build(List.of(mit, lgpl), dir.resolve("hub1"), "zh-TW", "hostmod",
                false);
        assertEquals("LGPL-3.0-or-later", mixed.targets().get("hostmod").licenseField());

        Path choice = dir.resolve("choice.json");
        Path fixed = dir.resolve("fixed.json");
        input(choice, "mod", "hostmod", "LGPL-2.1 OR LGPL-3.0-only", "Shader settings", "光影設定");
        input(fixed, "shaderpack", null, "LGPL-3.0-only", "Sun brightness", "太陽亮度");
        LangPackBuilder.Result both = LangPackBuilder.build(List.of(choice, fixed), dir.resolve("hub2"), "zh-TW",
                "hostmod", false);
        assertEquals("(LGPL-2.1-only OR LGPL-3.0-only) AND LGPL-3.0-only", both.targets().get("hostmod").licenseField());
    }

    @Test
    void rebuildingTheSameInputIsDeterministicAndAnIndexRefreshKeepsTheOtherLicenses(@TempDir Path dir)
            throws IOException {
        Path lgpl = dir.resolve("lgpl.json");
        Path mit = dir.resolve("mit.json");
        input(lgpl, "mod", "lgplmod", "LGPL-3.0-only", "Open the menu", "開啟選單");
        input(mit, "mod", "mitmod", "MIT", "Render distance for terrain", "地形的渲染距離");
        Path out = dir.resolve("hub");

        LangPackBuilder.build(List.of(lgpl), out, "zh-TW", null, true);
        String first = read(out.resolve("mods/lgplmod/zh-tw.json"));
        // a later build of another file rewrites index.json: the licensed entry must survive it
        LangPackBuilder.build(List.of(mit), out, "zh-TW", null, true);
        LangPackBuilder.build(List.of(lgpl), out, "zh-TW", null, false);

        assertEquals(first, read(out.resolve("mods/lgplmod/zh-tw.json")));
        try (Reader reader = Files.newBufferedReader(out.resolve("index.json"), StandardCharsets.UTF_8)) {
            HubIndex index = HubIndex.read(reader);
            assertEquals("LGPL-3.0-only", index.mod("lgplmod", "zh-TW").license());
            assertNull(index.mod("mitmod", "zh-TW").license());
        }
    }

    // ------------------------------------------------------------------ the player's whole flow

    @Test
    void builtLicensedFilesGoThroughPlanDownloadMergeAndTheLiveLookup(@TempDir Path dir) throws IOException {
        Path lgpl = dir.resolve("lgpl.json");
        Path mit = dir.resolve("mit.json");
        input(lgpl, "mod", "lgplmod", "LGPL-3.0-only", "Open the menu", "開啟選單", "Chunks: %d", "區塊：%d");
        input(mit, "mod", "mitmod", "MIT", "Render distance for terrain", "地形的渲染距離");
        Path hub = dir.resolve("hub");
        LangPackBuilder.build(List.of(lgpl, mit), hub, "zh-TW", null, true);
        assertTrue(read(hub.resolve("mods/lgplmod/zh-tw.json")).contains("\"license\":\"LGPL-3.0-only\""));

        // the repository, served from the built folder through an inline transport
        HttpTransport transport = url -> {
            String relative = url.substring(BASE.length() + 1);
            Path file = hub.resolve(relative);
            if (!Files.isRegularFile(file)) throw new IOException("404: " + url);
            return Files.readString(file, StandardCharsets.UTF_8);
        };
        HubDownloader downloader = new HubDownloader(new HubRepository(transport, BASE));
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubLocalCache cache = new HubLocalCache(dir.resolve("cache"), "zh-TW");

        HubPlan plan = downloader.plan(null, null, List.of("lgplmod", "mitmod", "othermod"), "zh-TW", state);
        assertEquals(2, plan.downloadable().size());
        HubIndex built;
        try (Reader reader = Files.newBufferedReader(hub.resolve("index.json"), StandardCharsets.UTF_8)) {
            built = HubIndex.read(reader);
        }
        HubIndex.LanguageStats licensed = built.mod("lgplmod", "zh-TW");
        assertEquals("LGPL-3.0-only", licensed.license());
        assertEquals(licensed.bytes() + built.mod("mitmod", "zh-TW").bytes(), plan.totalDownloadBytes());

        HubDownloadResult result = downloader.download(plan, cache, state, null, () -> false);

        assertEquals(3, result.added());
        assertEquals(licensed.sha256(), state.sha256(HubSource.mod("lgplmod"), "zh-tw"));
        assertEquals("開啟選單", cache.get("Open the menu"));
        assertEquals("區塊：⟦MT0⟧", cache.get("Chunks: ⟦MT0⟧"));

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
                Component.literal("Open the menu"), service::translateScreenText);
        assertNotNull(shown);
        assertEquals("開啟選單", shown.getString());
        assertEquals("區塊：21", service.translateScreenText("Chunks: 21").translated());

        // up to date afterwards, with the license field changing nothing about that
        assertTrue(downloader.plan(null, null, List.of("lgplmod", "mitmod"), "zh-TW", state).downloadable().isEmpty());
    }
}
