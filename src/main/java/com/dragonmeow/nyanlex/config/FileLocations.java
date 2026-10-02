package com.dragonmeow.nyanlex.config;

import com.dragonmeow.nyanlex.cache.LanguageFileStore;
import com.dragonmeow.nyanlex.hub.HubPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where every file the mod keeps lives, for the settings screen's "檔案位置" group. Pure
 * path arithmetic over the config directory; the names mirror what the loader glue's
 * stores actually open (see {@code NyanLexFabric}), so a change there is a change here.
 */
public final class FileLocations {
    /** Ids in display order. Each id has lang keys {@code nyanlex.files.<id>} (title) and
     *  {@code nyanlex.files.<id>.desc} (what the file is for). */
    public static final List<String> IDS = List.of(
            "config", "ai_cache", "gt_google", "gt_youdao", "gt_deepl", "gt_microsoft",
            "failures", "hub_cache", "hub_state", "debug_dir", "lang_probe");

    private static final String PREFIX = HubPaths.FILE_PREFIX;

    /** One file or folder: {@code id} (one of {@link #IDS}) and its absolute path. */
    public record Entry(String id, Path path) {
        public String titleKey() { return "nyanlex.files." + id; }
        public String descKey() { return "nyanlex.files." + id + ".desc"; }
    }

    private FileLocations() {}

    public static String titleKey(String id) { return "nyanlex.files." + id; }

    public static String descKey(String id) { return "nyanlex.files." + id + ".desc"; }

    public static List<Entry> entries(Path configDir, String language) {
        Path dir = configDir.toAbsolutePath().normalize();
        String tag = LanguageFileStore.languageTag(language);
        Path debug = dir.resolve(PREFIX + "-debug");
        List<Entry> out = new ArrayList<>();
        out.add(new Entry("config", dir.resolve(PREFIX + ".json")));
        out.add(new Entry("ai_cache", dir.resolve(PREFIX + "-ai-cache-" + tag + ".json")));
        // Google keeps the historical file name; every other provider has its own sibling file.
        out.add(new Entry("gt_google", dir.resolve(PREFIX + "-cache-" + tag + ".json")));
        for (MachineTranslationProvider provider : MachineTranslationProvider.values()) {
            if (provider == MachineTranslationProvider.GOOGLE) continue;
            out.add(new Entry("gt_" + provider.id(),
                    dir.resolve(PREFIX + "-cache-" + provider.id() + "-" + tag + ".json")));
        }
        out.add(new Entry("failures", dir.resolve(PREFIX + "-failures-" + tag + ".json")));
        out.add(new Entry("hub_cache", dir.resolve(HubPaths.hubCacheFileName(language))));
        out.add(new Entry("hub_state", dir.resolve(HubPaths.stateFileName())));
        out.add(new Entry("debug_dir", debug));
        out.add(new Entry("lang_probe", debug.resolve("lang-probe.json")));
        return List.copyOf(out);
    }
}
