package com.dragonmeow.nyanslate.hub;

import com.dragonmeow.nyanslate.cache.LanguageFileStore;

import java.util.regex.Pattern;

/**
 * Repository layout and local-cache file naming for the GitHub AI translation hub.
 * Every name funnels through {@link #FILE_PREFIX}, so renaming the mod only ever
 * touches this one constant.
 */
public final class HubPaths {
    /** A3 defense-in-depth: a server host or modpack slug is validated/lower-cased by its
     *  own upstream sanitizer ({@link ServerHostNormalizer}, {@link HubSlug}) before it
     *  ever reaches here, but {@code serverPath}/{@code modpackPath} whitelist it again
     *  right before building a repository URL path, so a caller that skips/changes that
     *  upstream step still cannot smuggle a {@code '/'}, {@code ".."}, or other
     *  path-breaking character into the request. */
    private static final Pattern SAFE_HOST_OR_SLUG =
            Pattern.compile("^[a-z0-9]+(?:[.-][a-z0-9]+)*$");
    /** Mod ids are matched exactly against the loader's own id strings (never rewritten —
     *  see {@code HubExportTool.Options#resolveSource}), which is why this allows the same
     *  {@code [A-Za-z0-9_-]} set that tool already validates against, not a lower-cased
     *  subset of it. */
    private static final Pattern SAFE_MOD_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SAFE_LANG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private static String requireSafe(Pattern pattern, String value, String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe hub " + label + " path segment");
        }
        return value;
    }

    private static String safeLanguage(String language) {
        return requireSafe(SAFE_LANG, LanguageFileStore.languageTag(language), "language");
    }
    /** Raw-file base URL of the shared repository. */
    public static final String DEFAULT_BASE_URL =
            "https://raw.githubusercontent.com/DragonMeow1012/Nyanslate/main/translation-hub";

    /** Local-cache / state file-name prefix (matches the mod id). */
    public static final String FILE_PREFIX = "nyanslate";

    /** Per-file cap for every hub repository file (server/modpack/mod), enforced by
     *  {@code HubExportTool} when writing. The user raised this from the original
     *  40,000 rows / 3 MiB (2026-10-01: "只要不造成卡頓，單檔大沒關係") since nothing in the
     *  download path is render-thread-bound or O(n) over the whole cache per lookup
     *  (see {@link #downloadResponseByteCap()} and {@link HubLocalCache#get}); raised a
     *  second time the same day to 32 MiB (row cap unchanged) so the hypixel.net initial
     *  seed's full 54,281 kept rows fit in one file without truncation. 32 MiB also
     *  happens to equal {@code TranslationFile}'s own read-side ceiling — a file at
     *  exactly this cap still reads fine, since that check is strictly-greater-than. */
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    public static final int MAX_FILE_ROWS = 100_000;

    private HubPaths() {
    }

    /** Byte cap for the dedicated hub-download {@code UrlHttpTransport} instance: enough
     *  headroom above {@link #MAX_FILE_BYTES} that a repository file sitting exactly at
     *  the per-file cap (plus its small JSON envelope: {@code schema}/{@code format}/
     *  {@code language}/{@code provider} fields) is never truncated by the transport
     *  layer before {@link com.dragonmeow.nyanslate.translate.TranslationFile} ever sees
     *  it. Deliberately never applied to the separate translation-API transport
     *  instance, which keeps its own smaller default (that instance talks to AI/GT
     *  endpoints, never to the hub repository). */
    public static int downloadResponseByteCap() {
        return (int) (MAX_FILE_BYTES + (2L * 1024 * 1024));
    }

    public static String indexPath() {
        return "index.json";
    }

    public static String serverPath(String host, String language) {
        return "servers/" + requireSafe(SAFE_HOST_OR_SLUG, host, "server host") + "/"
                + safeLanguage(language) + ".json";
    }

    public static String modpackPath(String slug, String language) {
        return "modpacks/" + requireSafe(SAFE_HOST_OR_SLUG, slug, "modpack slug") + "/"
                + safeLanguage(language) + ".json";
    }

    public static String modPath(String modId, String language) {
        return "mods/" + requireSafe(SAFE_MOD_ID, modId, "mod id") + "/"
                + safeLanguage(language) + ".json";
    }

    /** Local merged-cache file for one target language, distinct from the mod's own
     *  AI/GT cache files (e.g. {@code nyanslate-ai-cache-zh-tw.json}). */
    public static String hubCacheFileName(String language) {
        return FILE_PREFIX + "-hub-cache-" + LanguageFileStore.languageTag(language) + ".json";
    }

    /** Durable "last known sha256 per source+language" ledger file name. */
    public static String stateFileName() {
        return FILE_PREFIX + "-hub-state.json";
    }
}
