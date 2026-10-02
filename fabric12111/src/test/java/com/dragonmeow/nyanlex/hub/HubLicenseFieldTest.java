package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.translate.HttpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional {@code license} of a repository file and of its index entry (set for files whose source
 * is under an LGPL license): the player-side readers must take it, keep it when it is well formed, and
 * never fail or change any row because of it. Every transport is an inline fake.
 */
class HubLicenseFieldTest {
    private static final String BASE = "https://example.com/hub";

    private static String fileJson(String licenseMember, String key, String value) {
        return "{\"schema\":2,\"format\":\"hub-hash-v1\",\"hash\":\"sha256\",\"language\":\"zh-tw\""
                + licenseMember + ",\"rows\":1,\"entries\":{\"" + HubKeyHash.of(key) + "\":\"" + value + "\"}}";
    }

    @Test
    void aFileWithALicenseReadsExactlyLikeOneWithout() throws IOException {
        HubFile plain = HubFile.read(fileJson("", "Diamond Sword", "鑽石劍"));
        HubFile licensed = HubFile.read(fileJson(",\"license\":\"LGPL-3.0-only\"", "Diamond Sword", "鑽石劍"));

        assertNull(plain.license());
        assertEquals("LGPL-3.0-only", licensed.license());
        assertEquals(plain.entries(), licensed.entries());
        assertEquals(plain.language(), licensed.language());
    }

    @Test
    void theLicenseMayStandAnywhereInTheObjectAndNextToOtherUnknownFields() throws IOException {
        String json = "{\"license\":\"LGPL-2.1-or-later\",\"schema\":2,\"note\":{\"a\":[1,2]},\"format\":\"hub-hash-v1\","
                + "\"hash\":\"sha256\",\"language\":\"zh-tw\",\"rows\":1,\"entries\":{\"" + HubKeyHash.of("Ender Pearl")
                + "\":\"終界珍珠\"},\"extra\":null}";

        HubFile file = HubFile.read(json);

        assertEquals("LGPL-2.1-or-later", file.license());
        assertEquals("終界珍珠", file.entries().get(HubKeyHash.of("Ender Pearl")));
    }

    @Test
    void aMalformedLicenseIsNoLicenseAndNeverFailsTheFile() throws IOException {
        for (String member : new String[] {",\"license\":null", ",\"license\":\"\"", ",\"license\":\"   \"",
                ",\"license\":42", ",\"license\":true", ",\"license\":{\"id\":\"LGPL-3.0-only\"}",
                ",\"license\":[\"LGPL-3.0-only\"]", ",\"license\":\"" + "L".repeat(257) + "\""}) {
            HubFile file = HubFile.read(fileJson(member, "Diamond Sword", "鑽石劍"));
            assertNull(file.license(), member);
            assertEquals("鑽石劍", file.entries().get(HubKeyHash.of("Diamond Sword")), member);
        }
    }

    @Test
    void theLicenseDoesNotLoosenAnyOtherCheck() {
        // the schema, the format and the row shape are validated as before, license or not
        assertThrows(IOException.class, () -> HubFile.read(
                "{\"schema\":1,\"format\":\"hub-hash-v1\",\"language\":\"zh-tw\",\"license\":\"LGPL-3.0-only\","
                        + "\"entries\":{}}"));
        assertThrows(IOException.class, () -> HubFile.read(
                "{\"schema\":2,\"format\":\"hub-hash-v1\",\"language\":\"zh-tw\",\"license\":\"LGPL-3.0-only\","
                        + "\"entries\":{\"Diamond Sword\":\"鑽石劍\"}}"));
    }

    @Test
    void writingKeepsTheLicenseBetweenLanguageAndRowsAndAFileWithoutOneIsByteForByteTheOldFormat() throws IOException {
        String hash = HubKeyHash.of("Diamond Sword");
        StringWriter licensed = new StringWriter();
        new HubFile("zh-TW", Map.of(hash, "鑽石劍"), "LGPL-3.0-only").writeTo(licensed);
        StringWriter plain = new StringWriter();
        new HubFile("zh-TW", Map.of(hash, "鑽石劍")).writeTo(plain);
        StringWriter blank = new StringWriter();
        new HubFile("zh-TW", Map.of(hash, "鑽石劍"), "  ").writeTo(blank);

        assertEquals("{\"schema\":2,\"format\":\"hub-hash-v1\",\"hash\":\"sha256\",\"language\":\"zh-tw\","
                + "\"license\":\"LGPL-3.0-only\",\"rows\":1,\"entries\":{\n\"" + hash + "\":\"鑽石劍\"}}",
                licensed.toString());
        assertEquals("{\"schema\":2,\"format\":\"hub-hash-v1\",\"hash\":\"sha256\",\"language\":\"zh-tw\","
                + "\"rows\":1,\"entries\":{\n\"" + hash + "\":\"鑽石劍\"}}", plain.toString());
        assertEquals(plain.toString(), blank.toString());
        assertEquals("LGPL-3.0-only", HubFile.read(licensed.toString()).license());
        assertNull(HubFile.read(plain.toString()).license());
    }

    @Test
    void anIndexEntryWithALicenseIsReadAndUnknownEntryFieldsAreIgnored() throws IOException {
        String json = "{\"schema\":1,\"generatedAt\":\"\",\"servers\":{},\"modpacks\":{},\"mods\":{"
                + "\"lgplmod\":{\"zh-tw\":{\"rows\":3,\"bytes\":300,\"sha256\":\"sha-l\",\"updatedAt\":\"t\","
                + "\"license\":\"LGPL-3.0-only AND LGPL-3.0-or-later\",\"future\":{\"x\":1}}},"
                + "\"plainmod\":{\"zh-tw\":{\"rows\":2,\"bytes\":200,\"sha256\":\"sha-p\"}}}}";

        HubIndex index = HubIndex.read(new StringReader(json));

        HubIndex.LanguageStats lgpl = index.mod("lgplmod", "zh-TW");
        assertEquals(3, lgpl.rows());
        assertEquals(300, lgpl.bytes());
        assertEquals("sha-l", lgpl.sha256());
        assertEquals("LGPL-3.0-only AND LGPL-3.0-or-later", lgpl.license());
        HubIndex.LanguageStats plain = index.mod("plainmod", "zh-TW");
        assertEquals(2, plain.rows());
        assertNull(plain.license());
    }

    @Test
    void aMalformedIndexLicenseIsNoLicenseAndKeepsTheRestOfTheEntry() throws IOException {
        for (String member : new String[] {"null", "\"\"", "\"  \"", "7", "{\"id\":\"LGPL-3.0-only\"}", "[\"x\"]",
                "\"" + "L".repeat(257) + "\""}) {
            String json = "{\"schema\":1,\"mods\":{\"somemod\":{\"zh-tw\":{\"rows\":3,\"bytes\":30,\"sha256\":\"s\","
                    + "\"license\":" + member + "}}}}";
            HubIndex.LanguageStats stats = HubIndex.read(new StringReader(json)).mod("somemod", "zh-tw");
            assertNotNull(stats, member);
            assertEquals(3, stats.rows(), member);
            assertEquals("s", stats.sha256(), member);
            assertNull(stats.license(), member);
        }
    }

    @Test
    void theIndexWritesTheLicenseOnlyWhereThereIsOneAndRoundTripsIt() throws IOException {
        HubIndex index = HubIndex.empty()
                .withModEntry("lgplmod", "zh-tw", new HubIndex.LanguageStats(5, 50, "sha-a", "t", "LGPL-2.1-only"))
                .withModEntry("plainmod", "zh-tw", new HubIndex.LanguageStats(6, 60, "sha-b", "t"));

        StringWriter writer = new StringWriter();
        index.write(writer);
        String text = writer.toString();
        HubIndex back = HubIndex.read(new StringReader(text));

        assertTrue(text.contains("\"license\":\"LGPL-2.1-only\""));
        assertEquals(1, text.split("\"license\"", -1).length - 1, "only the licensed entry carries the field");
        assertEquals("LGPL-2.1-only", back.mod("lgplmod", "zh-tw").license());
        assertNull(back.mod("plainmod", "zh-tw").license());
        assertEquals(new HubIndex.LanguageStats(6, 60, "sha-b", "t"), back.mod("plainmod", "zh-tw"));
    }

    /** Full flow with hand-written JSON: index, plan, download, merge, lookup. */
    @Test
    void licensedFilesGoThroughPlanDownloadMergeAndLookupUnchanged(@TempDir Path dir) throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put(BASE + "/index.json", "{\"schema\":1,\"generatedAt\":\"\",\"servers\":{},\"modpacks\":{},\"mods\":{"
                + "\"lgplmod\":{\"zh-tw\":{\"rows\":1,\"bytes\":40,\"sha256\":\"sha-l\",\"updatedAt\":\"t\","
                + "\"license\":\"LGPL-3.0-only\"}},"
                + "\"plainmod\":{\"zh-tw\":{\"rows\":1,\"bytes\":30,\"sha256\":\"sha-p\",\"updatedAt\":\"t\"}}}}");
        responses.put(BASE + "/mods/lgplmod/zh-tw.json",
                fileJson(",\"license\":\"LGPL-3.0-only\"", "Open the menu", "開啟選單"));
        responses.put(BASE + "/mods/plainmod/zh-tw.json", fileJson("", "Close the menu", "關閉選單"));
        HttpTransport transport = url -> {
            String body = responses.get(url);
            if (body == null) throw new IOException("404: " + url);
            return body;
        };
        HubDownloader downloader = new HubDownloader(new HubRepository(transport, BASE));
        HubDownloadState state = new HubDownloadState(dir.resolve("state.json"));
        HubLocalCache cache = new HubLocalCache(dir, "zh-TW");

        HubPlan plan = downloader.plan(null, null, List.of("lgplmod", "plainmod", "unknownmod"), "zh-TW", state);
        assertEquals(2, plan.downloadable().size());
        assertEquals(70, plan.totalDownloadBytes());
        HubDownloadResult result = downloader.download(plan, cache, state, null, () -> false);

        assertEquals(2, result.added());
        assertEquals("開啟選單", cache.get("Open the menu"));
        assertEquals("關閉選單", cache.get("Close the menu"));
        assertEquals("sha-l", state.sha256(HubSource.mod("lgplmod"), "zh-tw"));
        assertFalse(plan.items().get(2).hasContent());
        // and a second plan sees both as up to date, exactly as for a file without a license
        HubPlan again = downloader.plan(null, null, List.of("lgplmod", "plainmod"), "zh-TW", state);
        assertTrue(again.downloadable().isEmpty());
    }
}
