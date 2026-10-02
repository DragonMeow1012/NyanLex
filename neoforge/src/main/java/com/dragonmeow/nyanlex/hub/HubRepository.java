package com.dragonmeow.nyanlex.hub;

import com.dragonmeow.nyanlex.translate.HttpTransport;

import java.io.IOException;
import java.io.StringReader;

/**
 * Thin GET-only client over {@link HttpTransport} for the shared repository. Every
 * fetch is a plain read: nothing here ever uploads or mutates remote state.
 */
public final class HubRepository {
    private final HttpTransport transport;
    private final String baseUrl;

    public HubRepository(HttpTransport transport, String baseUrl) {
        this.transport = transport;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public HubRepository(HttpTransport transport) {
        this(transport, HubPaths.DEFAULT_BASE_URL);
    }

    public HubIndex fetchIndex() throws IOException {
        String body = transport.get(baseUrl + "/" + HubPaths.indexPath());
        return HubIndex.read(new StringReader(body));
    }

    public HubFile fetchServerFile(String host, String language) throws IOException {
        return fetch(HubPaths.serverPath(host, language));
    }

    public HubFile fetchModpackFile(String slug, String language) throws IOException {
        return fetch(HubPaths.modpackPath(slug, language));
    }

    public HubFile fetchModFile(String modId, String language) throws IOException {
        return fetch(HubPaths.modPath(modId, language));
    }

    private HubFile fetch(String relativePath) throws IOException {
        String body = transport.get(baseUrl + "/" + relativePath);
        return HubFile.read(body);
    }
}
