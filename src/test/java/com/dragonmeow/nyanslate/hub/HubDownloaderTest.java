package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.translate.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Every transport here is an inline fake; no test in this file ever touches the
 *  real network. */
class HubDownloaderTest {

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("nyanslate-hub-downloader-test");
    }

    private static final String BASE = "https://example.com/hub";
    private static final String INDEX_URL = BASE + "/index.json";

    private static String translationFileJson(String language, Map<String, String> rows) {
        StringBuilder ai = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> row : rows.entrySet()) {
            if (!first) ai.append(',');
            first = false;
            ai.append('"').append(row.getKey()).append("\":\"").append(row.getValue()).append('"');
        }
        ai.append('}');
        return "{\"schema\":1,\"format\":\"modern-template-v1\",\"language\":\"" + language
                + "\",\"provider\":\"none\",\"machine\":{},\"ai\":" + ai + "}";
    }

    private static HttpTransport recordingTransport(Map<String, String> responses, List<String> requested) {
        return url -> {
            requested.add(url);
            String body = responses.get(url);
            if (body == null) throw new IOException("404: " + url);
            return body;
        };
    }

    @Test
    void planReportsContentSizeAndUpToDateFlag() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{\"hypixel.net\":{\"zh-tw\":"
                + "{\"rows\":5,\"bytes\":123,\"sha256\":\"sha-server\"}}},"
                + "\"modpacks\":{},\"mods\":{\"somemod\":{\"zh-tw\":"
                + "{\"rows\":2,\"bytes\":45,\"sha256\":\"sha-mod\"}}}}");
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, new ArrayList<>()), BASE));
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));

        HubPlan plan = downloader.plan("hypixel.net", null, List.of("somemod", "unknownmod"), "zh-TW", state);

        // Every installed mod gets its own item now, even one the index has never heard
        // of (2026-10-01: the confirmation screen must list ALL installed mods with a
        // status, including "倉庫沒有" for "unknownmod" — not silently drop it).
        assertEquals(3, plan.items().size());
        HubPlanItem server = plan.items().get(0);
        assertEquals(HubSource.server("hypixel.net"), server.source());
        assertTrue(server.hasContent());
        assertFalse(server.upToDate());
        assertEquals(123, server.bytes());
        HubPlanItem mod = plan.items().get(1);
        assertEquals(HubSource.mod("somemod"), mod.source());
        assertTrue(mod.hasContent());
        HubPlanItem unknownMod = plan.items().get(2);
        assertEquals(HubSource.mod("unknownmod"), unknownMod.source());
        assertFalse(unknownMod.hasContent(), "a mod unknown to the index must still appear, as \"倉庫沒有\"");
        assertEquals(0, unknownMod.rows());
        assertEquals(0, unknownMod.bytes());
        assertFalse(unknownMod.upToDate());
        // The total only ever counts items that actually have something to download: the
        // unknown mod contributes nothing even though it is listed.
        assertEquals(123 + 45, plan.totalDownloadBytes());
        assertEquals(2, plan.downloadable().size());
    }

    @Test
    void planMarksAnItemUpToDateWhenShaMatchesRecordedState() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{\"hypixel.net\":{\"zh-tw\":"
                + "{\"rows\":5,\"bytes\":123,\"sha256\":\"sha-server\"}}},\"modpacks\":{},\"mods\":{}}");
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, new ArrayList<>()), BASE));
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));
        state.record(HubSource.server("hypixel.net"), "zh-tw", "sha-server");

        HubPlan plan = downloader.plan("hypixel.net", null, List.of(), "zh-TW", state);

        assertTrue(plan.items().get(0).upToDate());
        assertEquals(0, plan.totalDownloadBytes(), "an up-to-date item does not count toward the total");
        assertTrue(plan.downloadable().isEmpty());
    }

    @Test
    void downloadMergesEachItemAndReportsProgressAfterEveryFile() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{\"hypixel.net\":{\"zh-tw\":"
                + "{\"rows\":1,\"bytes\":50,\"sha256\":\"sha-server\"}}},"
                + "\"modpacks\":{},\"mods\":{\"somemod\":{\"zh-tw\":"
                + "{\"rows\":1,\"bytes\":30,\"sha256\":\"sha-mod\"}}}}");
        responses.put(BASE + "/servers/hypixel.net/zh-tw.json",
                translationFileJson("zh-tw", Map.of("Diamond Sword", "鑽石劍")));
        responses.put(BASE + "/mods/somemod/zh-tw.json",
                translationFileJson("zh-tw", Map.of("Somemod Item", "某物品")));
        List<String> requested = new ArrayList<>();
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, requested), BASE));
        Path dir = tempDir();
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");

        HubPlan plan = downloader.plan("hypixel.net", null, List.of("somemod"), "zh-TW", state);
        List<String> progressFiles = new ArrayList<>();
        HubDownloadResult result = downloader.download(plan, cache, state,
                (downloaded, total, fileName, completed, totalFiles) -> progressFiles.add(fileName),
                () -> false);

        assertEquals(2, result.added());
        assertEquals("鑽石劍", cache.get("Diamond Sword"));
        assertEquals("某物品", cache.get("Somemod Item"));
        assertEquals(List.of("hypixel.net", "somemod"), progressFiles, "server downloads before mod (priority order)");
        assertEquals("sha-server", state.sha256(HubSource.server("hypixel.net"), "zh-tw"));
    }

    @Test
    void downloadKeepsRecordingTheLanguageItStartedWithEvenIfTheTargetLanguageChangesMidDownload()
            throws IOException {
        // A4 audit finding: the player can switch the target language (settings screen) while
        // a background HubDownloadJob is still running. The sha256 ledger entry for an
        // already-fetched file must be recorded under the language the DOWNLOAD started with —
        // never silently re-targeted to whatever language the live HubLocalCache switched to
        // mid-loop, or that language's throttling ledger gets corrupted with a mismatched sha.
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{\"hypixel.net\":{\"zh-tw\":"
                + "{\"rows\":1,\"bytes\":50,\"sha256\":\"sha-server\"}}},"
                + "\"modpacks\":{},\"mods\":{\"somemod\":{\"zh-tw\":"
                + "{\"rows\":1,\"bytes\":30,\"sha256\":\"sha-mod\"}}}}");
        responses.put(BASE + "/servers/hypixel.net/zh-tw.json",
                translationFileJson("zh-tw", Map.of("Diamond Sword", "鑽石劍")));
        responses.put(BASE + "/mods/somemod/zh-tw.json",
                translationFileJson("zh-tw", Map.of("Somemod Item", "某物品")));
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, new ArrayList<>()), BASE));
        Path dir = tempDir();
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubPlan plan = downloader.plan("hypixel.net", null, List.of("somemod"), "zh-TW", state);

        // Simulate the player opening settings and switching target language right after the
        // first (server) file finishes, exactly like onTargetLanguageChanged would mid-download.
        HubDownloadResult result = downloader.download(plan, cache, state,
                (downloaded, total, fileName, completed, totalFiles) -> {
                    if ("hypixel.net".equals(fileName)) cache.setLanguage("en-US");
                },
                () -> false);

        assertEquals(1, result.added(), "only the server file merged before the language switched");
        // The merge happened (and was persisted) under zh-tw BEFORE the switch; `cache` itself
        // now shows en-US's (freshly loaded, empty) rows since it switched live mid-download —
        // switching back to zh-TW re-loads the persisted file and shows the merged content.
        cache.setLanguage("zh-TW");
        assertEquals("鑽石劍", cache.get("Diamond Sword"));
        // The mod file was still fetched and its sha recorded under zh-tw (the language the
        // download started with) — not silently dropped, and not attributed to en-us.
        assertEquals("sha-mod", state.sha256(HubSource.mod("somemod"), "zh-tw"));
        assertNull(state.sha256(HubSource.mod("somemod"), "en-US"),
                "the new language's throttling ledger must not be polluted by a stale zh-tw sha");
    }

    @Test
    void downloadStopsAtCancellationAndKeepsAlreadyMergedFiles() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{\"hypixel.net\":{\"zh-tw\":"
                + "{\"rows\":1,\"bytes\":50,\"sha256\":\"sha-server\"}}},"
                + "\"modpacks\":{},\"mods\":{\"somemod\":{\"zh-tw\":"
                + "{\"rows\":1,\"bytes\":30,\"sha256\":\"sha-mod\"}}}}");
        responses.put(BASE + "/servers/hypixel.net/zh-tw.json",
                translationFileJson("zh-tw", Map.of("Diamond Sword", "鑽石劍")));
        responses.put(BASE + "/mods/somemod/zh-tw.json",
                translationFileJson("zh-tw", Map.of("Somemod Item", "某物品")));
        List<String> requested = new ArrayList<>();
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, requested), BASE));
        Path dir = tempDir();
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");
        HubPlan plan = downloader.plan("hypixel.net", null, List.of("somemod"), "zh-TW", state);

        HubDownloadResult result = downloader.download(plan, cache, state,
                (downloaded, total, fileName, completed, totalFiles) -> { }, () -> true);

        assertTrue(result.cancelled());
        assertEquals(0, result.added());
        assertTrue(requested.stream().noneMatch(url -> url.contains("servers/hypixel.net")),
                "cancellation before the first file means nothing is fetched");
    }

    @Test
    void startupPlanMakesNoNetworkCallWhenDisabled() throws IOException {
        HttpTransport neverCalled = url -> {
            fail("must not call the network when hubStartupPromptDisabled is true: " + url);
            return null;
        };
        HubDownloader downloader = new HubDownloader(new HubRepository(neverCalled, BASE));
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));

        HubPlan plan = downloader.planStartupMods(true, null, List.of("somemod"), "zh-TW", state);

        assertTrue(plan.isEmpty());
    }

    @Test
    void startupPlanFetchesIndexAndListsEveryModWhenEnabled() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{},\"modpacks\":{},"
                + "\"mods\":{\"somemod\":{\"zh-tw\":{\"rows\":1,\"bytes\":30,\"sha256\":\"sha-mod\"}}}}");
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, new ArrayList<>()), BASE));
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));

        HubPlan plan = downloader.planStartupMods(false, null, List.of("somemod", "unknownmod"), "zh-TW", state);

        // Both the known and the unknown mod appear; only the known one counts toward a
        // download (the startup popup itself further filters to plan.downloadable()).
        assertEquals(2, plan.items().size());
        assertEquals(HubSource.mod("somemod"), plan.items().get(0).source());
        assertEquals(HubSource.mod("unknownmod"), plan.items().get(1).source());
        assertFalse(plan.items().get(1).hasContent());
        assertEquals(1, plan.downloadable().size());
    }

    @Test
    void startupPlanIncludesADetectedModpack() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(INDEX_URL, "{\"schema\":1,\"servers\":{},"
                + "\"modpacks\":{\"my-pack\":{\"zh-tw\":{\"rows\":4,\"bytes\":80,\"sha256\":\"sha-pack\"}}},"
                + "\"mods\":{}}");
        HubDownloader downloader = new HubDownloader(new HubRepository(
                recordingTransport(responses, new ArrayList<>()), BASE));
        HubDownloadState state = new HubDownloadState(tempDir().resolve("state.json"));
        ModpackIdentity modpack = new ModpackIdentity("my-pack", "My Pack", "1.21.1",
                ModpackIdentity.Source.CURSEFORGE);

        HubPlan plan = downloader.planStartupMods(false, modpack, List.of(), "zh-TW", state);

        assertEquals(1, plan.items().size());
        HubPlanItem item = plan.items().get(0);
        assertEquals(HubSource.modpack("my-pack"), item.source());
        assertTrue(item.hasContent());
        assertEquals(80, item.bytes());
    }
}
