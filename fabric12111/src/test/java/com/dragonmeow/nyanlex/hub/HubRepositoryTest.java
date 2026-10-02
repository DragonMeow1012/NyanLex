package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.translate.HttpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every transport here is an inline fake backed by a fixed URL-&gt;body map; no test in
 *  this file ever touches the real network. */
class HubRepositoryTest {

    private static HttpTransport fakeTransport(Map<String, String> responses) {
        return url -> {
            String body = responses.get(url);
            if (body == null) throw new IOException("404: " + url);
            return body;
        };
    }

    @Test
    void fetchIndexParsesTheIndexJsonUrl() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/index.json",
                "{\"schema\":1,\"generatedAt\":\"now\",\"servers\":{\"hypixel.net\":{\"zh-tw\":"
                        + "{\"rows\":5,\"bytes\":50,\"sha256\":\"abc\"}}},\"modpacks\":{},\"mods\":{}}");
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        HubIndex index = repository.fetchIndex();

        assertEquals(5, index.server("hypixel.net", "zh-tw").rows());
    }

    @Test
    void baseUrlTrailingSlashIsNormalized() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/index.json", "{\"schema\":1}");
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub/");

        assertEquals(0, repository.fetchIndex().modIds().size());
    }

    private static String hubJson(int schema, String key, String value) {
        return "{\"schema\":" + schema + ",\"format\":\"hub-hash-v1\",\"hash\":\"sha256\","
                + "\"language\":\"zh-tw\",\"rows\":1,\"entries\":{\"" + HubKeyHash.of(key) + "\":\""
                + value + "\"}}";
    }

    @Test
    void fetchServerFileUsesServerPath() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/servers/hypixel.net/zh-tw.json", hubJson(2, "Diamond Sword", "鑽石劍"));
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        HubFile file = repository.fetchServerFile("hypixel.net", "zh-TW");

        assertEquals("鑽石劍", file.entries().get(HubKeyHash.of("Diamond Sword")));
    }

    @Test
    void fetchModpackFileUsesModpackPath() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/modpacks/my-pack/zh-tw.json", hubJson(2, "Ender Pearl", "終界珍珠"));
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        HubFile file = repository.fetchModpackFile("my-pack", "zh-TW");

        assertEquals("終界珍珠", file.entries().get(HubKeyHash.of("Ender Pearl")));
    }

    @Test
    void fetchModFileUsesModPath() throws IOException {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/mods/somemod/zh-tw.json", hubJson(2, "Somemod Item", "某模組物品"));
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        HubFile file = repository.fetchModFile("somemod", "zh-TW");

        assertEquals("某模組物品", file.entries().get(HubKeyHash.of("Somemod Item")));
    }

    @Test
    void schema1FileContainingSourceTextIsRejected() {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/servers/hypixel.net/zh-tw.json",
                "{\"schema\":1,\"format\":\"modern-template-v1\",\"language\":\"zh-tw\","
                        + "\"provider\":\"none\",\"machine\":{},\"ai\":{\"Diamond Sword\":\"鑽石劍\"}}");
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        assertThrows(IOException.class, () -> repository.fetchServerFile("hypixel.net", "zh-TW"));
    }

    @Test
    void entryWithANonHashKeyIsRejected() {
        Map<String, String> responses = new HashMap<>();
        responses.put("https://example.com/hub/servers/hypixel.net/zh-tw.json",
                "{\"schema\":2,\"format\":\"hub-hash-v1\",\"language\":\"zh-tw\","
                        + "\"entries\":{\"Diamond Sword\":\"鑽石劍\"}}");
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        assertThrows(IOException.class, () -> repository.fetchServerFile("hypixel.net", "zh-TW"));
    }

    @Test
    void missingFileThrowsIOException() {
        HubRepository repository = new HubRepository(fakeTransport(new HashMap<>()), "https://example.com/hub");
        assertThrows(IOException.class, () -> repository.fetchServerFile("nowhere.net", "zh-TW"));
    }
}
