package com.dragonmeow.nyanslate.hub;

import java.util.Locale;

/**
 * Provenance tag of one hub-imported translation row or download plan item: which
 * repository file it came from. The encoded form ({@code server:<host>} /
 * {@code modpack:<slug>} / {@code mod:<modId>}) is what {@link HubLocalCache} persists
 * per row, so a later "clear by source" can select rows without re-parsing anything
 * else.
 */
public final class HubSource {
    public enum Kind { SERVER, MODPACK, MOD }

    private final Kind kind;
    private final String identifier;

    private HubSource(Kind kind, String identifier) {
        this.kind = kind;
        this.identifier = identifier;
    }

    public static HubSource server(String host) {
        return new HubSource(Kind.SERVER, host);
    }

    public static HubSource modpack(String slug) {
        return new HubSource(Kind.MODPACK, slug);
    }

    public static HubSource mod(String modId) {
        return new HubSource(Kind.MOD, modId);
    }

    public Kind kind() {
        return kind;
    }

    public String identifier() {
        return identifier;
    }

    /** Download/merge priority: server beats modpack beats mod. Lower sorts first. */
    public int priority() {
        switch (kind) {
            case SERVER: return 0;
            case MODPACK: return 1;
            default: return 2;
        }
    }

    /** Stable text form stored alongside each imported row. */
    public String encode() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + identifier;
    }

    public static HubSource decode(String text) {
        if (text == null) return null;
        int at = text.indexOf(':');
        if (at < 0) return null;
        String kindText = text.substring(0, at);
        String identifier = text.substring(at + 1);
        try {
            Kind kind = Kind.valueOf(kindText.toUpperCase(Locale.ROOT));
            return new HubSource(kind, identifier);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HubSource)) return false;
        HubSource other = (HubSource) o;
        return kind == other.kind && identifier.equals(other.identifier);
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31 + identifier.hashCode();
    }

    @Override
    public String toString() {
        return encode();
    }
}
