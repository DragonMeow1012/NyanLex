package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.translate.HttpTransport;
import com.dragonmeow.nyanslate.translate.TranslationFile;
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

    @Test
    void fetchServerFileUsesServerPath() throws IOException {
        Map<String, String> responses = new HashMap<>();
        String json = "{\"schema\":1,\"format\":\"modern-template-v1\",\"language\":\"zh-tw\","
                + "\"provider\":\"none\",\"machine\":{},\"ai\":{\"Diamond Sword\":\"鑽石劍\"}}";
        responses.put("https://example.com/hub/servers/hypixel.net/zh-tw.json", json);
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        TranslationFile file = repository.fetchServerFile("hypixel.net", "zh-TW");

        assertEquals("鑽石劍", file.ai.get("Diamond Sword"));
    }

    @Test
    void fetchModpackFileUsesModpackPath() throws IOException {
        Map<String, String> responses = new HashMap<>();
        String json = "{\"schema\":1,\"format\":\"modern-template-v1\",\"language\":\"zh-tw\","
                + "\"provider\":\"none\",\"machine\":{},\"ai\":{\"Ender Pearl\":\"終界珍珠\"}}";
        responses.put("https://example.com/hub/modpacks/my-pack/zh-tw.json", json);
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        TranslationFile file = repository.fetchModpackFile("my-pack", "zh-TW");

        assertEquals("終界珍珠", file.ai.get("Ender Pearl"));
    }

    @Test
    void fetchModFileUsesModPath() throws IOException {
        Map<String, String> responses = new HashMap<>();
        String json = "{\"schema\":1,\"format\":\"modern-template-v1\",\"language\":\"zh-tw\","
                + "\"provider\":\"none\",\"machine\":{},\"ai\":{\"Somemod Item\":\"某模組物品\"}}";
        responses.put("https://example.com/hub/mods/somemod/zh-tw.json", json);
        HubRepository repository = new HubRepository(fakeTransport(responses), "https://example.com/hub");

        TranslationFile file = repository.fetchModFile("somemod", "zh-TW");

        assertEquals("某模組物品", file.ai.get("Somemod Item"));
    }

    @Test
    void missingFileThrowsIOException() {
        HubRepository repository = new HubRepository(fakeTransport(new HashMap<>()), "https://example.com/hub");
        assertThrows(IOException.class, () -> repository.fetchServerFile("nowhere.net", "zh-TW"));
    }
}
