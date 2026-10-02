package com.dragonmeow.nyanlex.hub;

/**
 * Detected modpack identity ({@link ModpackDetector}). {@code displayName} and
 * {@code gameVersion} are best-effort and may be {@code null}; {@code slug} is
 * always a valid {@link HubSlug}-normalized repository path segment.
 */
public record ModpackIdentity(String slug, String displayName, String gameVersion, Source source) {

    public enum Source {
        INSTANCE_MANIFEST, PACK_INDEX, INSTANCE_CFG, PACK_TOML, INSTANCE_JSON, MOD_LIST_FALLBACK
    }
}
