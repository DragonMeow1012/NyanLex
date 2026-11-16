package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.service.OriginalLanguageSwitch;
import java.util.IllegalFormatException;
import java.util.List;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Glue for "show original" on the game's own language files. While the key is on, every key the
 * game resolves through its language table (translatable components, {@code I18n}) is looked up
 * in an English-first table instead, so text that comes from the selected language's file shows
 * its English source as well.
 *
 * <p>The English-first table is the selected language's file loaded first and the English file
 * loaded over it: a key that has English text shows English, a key that only exists in the
 * selected language keeps that text (instead of turning into a bare key). It is rebuilt on every
 * resource reload by {@code LanguageReloadMixin} and used by {@code LanguageInstanceMixin} and
 * {@code LanguageLookupMixin}. With the key off none of this is consulted.</p>
 *
 * <p>This class must stay free of work at class-load time: the hooks reach it from inside the
 * game's own language class, very early.</p>
 */
public final class OriginalLanguage {
    private static final Logger LOG = LoggerFactory.getLogger("nyanlex");
    private static final OriginalLanguageSwitch<Language> SWITCH = new OriginalLanguageSwitch<>();

    private OriginalLanguage() {
    }

    /** The table to resolve keys with right now, or {@code null} when the game's own table applies. */
    public static Language effective() {
        return SWITCH.effective();
    }

    /**
     * Rebuilds the English-first table after the game has (re)loaded its language. Never throws:
     * on a failure the key simply has no effect on the language layer.
     */
    public static void reload(ResourceManager manager, String selectedLanguageCode) {
        Language table = null;
        if (OriginalLanguageSwitch.needsEnglishTable(selectedLanguageCode)) {
            try {
                // Later entries override earlier ones: the selected language first, English over it.
                table = ClientLanguage.loadFrom(manager,
                        List.of(selectedLanguageCode, OriginalLanguageSwitch.ORIGINAL_LANGUAGE_CODE), false);
            } catch (RuntimeException e) {
                LOG.warn("[NyanLex] could not load the English language table; the show-original key "
                        + "will leave game-language text unchanged: {}", e.toString());
            }
        }
        SWITCH.setTable(table);
    }

    /**
     * Applies the show-original key to the language layer.
     *
     * @return true when this changes how the game resolves keys (the game is not in English), so
     *         the screen that is open should be rebuilt to pick up text resolved at init time
     */
    public static boolean apply(boolean showOriginal) {
        SWITCH.setShowOriginal(showOriginal);
        return SWITCH.affectsDisplay();
    }

    /** {@code I18n.get} semantics against {@code table} (root-locale formatting, as this game version does), including its format-error fallback text. */
    public static String format(Language table, String key, Object[] args) {
        String text = table.getOrDefault(key);
        try {
            return String.format(java.util.Locale.ROOT, text, args);
        } catch (IllegalFormatException e) {
            return "Format error: " + text;
        }
    }
}
