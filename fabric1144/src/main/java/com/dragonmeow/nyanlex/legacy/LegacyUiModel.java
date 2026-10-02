package com.dragonmeow.nyanlex.legacy;

import java.util.ArrayList;
import java.util.List;

/**
 * What the settings categories and the quick-setup pages contain, and what a click on them
 * changes. It holds no GUI types, so the same file serves every Java-8 target; each loader only
 * keeps a thin screen that lays the rows out and reacts to the navigation actions.
 */
final class LegacyUiModel {
    /** Looks up a language key (and formats it) for the running game. */
    interface Text {
        String get(String key, Object... args);
    }

    static final String GITHUB_URL = "https://github.com/DragonMeow1012/NyanLex";
    static final String GITHUB_DISPLAY = "github.com/DragonMeow1012/NyanLex";

    // ---- row kinds ----
    static final int TEXT = 0;
    static final int BUTTON = 1;
    /** Two half-width buttons share one line: a LEFT row is followed by its RIGHT row. */
    static final int HALF_LEFT = 2;
    static final int HALF_RIGHT = 3;
    static final int GAP = 4;
    /** Where the hint of the hovered / selected option is drawn (quick setup). */
    static final int HINT = 5;

    static final int GRAY = 0x909090;
    static final int NORMAL = 0xE0E0E0;
    static final int HEADING = 0xFFFFFF;

    static final class Row {
        final int kind;
        final String label;
        final int action;
        boolean enabled = true;
        boolean selected;
        boolean focusable = true;
        int color = NORMAL;
        /** Shown in the hint area while this button is hovered. */
        String hint;

        Row(int kind, String label, int action) {
            this.kind = kind;
            this.label = label;
            this.action = action;
        }
    }

    // ---- settings categories ----
    static final int CAT_GENERAL = 0;
    static final int CAT_DISPLAY = 1;
    static final int CAT_SERVICE = 2;
    static final int CAT_MINE = 3;
    static final int CAT_ADVANCED = 4;
    static final int CAT_ABOUT = 5;
    static final int CATEGORY_COUNT = 6;
    static final String[] CATEGORY_KEYS = {
        "screen.nyanlex.cat.general", "screen.nyanlex.cat.display", "screen.nyanlex.cat.service",
        "screen.nyanlex.cat.mine", "screen.nyanlex.cat.advanced", "screen.nyanlex.cat.about"
    };

    // ---- actions (settings) ----
    static final int A_DONE = 1;
    static final int A_QUICK_SETUP = 2;
    static final int A_HELP = 3;
    static final int A_ONLINE = 4;
    static final int A_LANGUAGE = 5;
    static final int A_ENABLED = 6;
    static final int A_KEYS = 7;
    static final int A_DISPLAY = 8;
    static final int A_TERMS = 9;
    static final int A_SERVICE = 10;
    static final int A_AI = 11;
    static final int A_FALLBACK = 12;
    static final int A_EXPORT = 13;
    static final int A_IMPORT = 14;
    static final int A_COOLDOWN = 15;
    static final int A_CHAT_ORDER = 16;
    static final int A_DEBUG = 17;
    static final int A_GITHUB = 18;
    static final int A_CATEGORY = 100;

    private LegacyUiModel() {}

    static String onOff(Text t, boolean on) {
        return t.get(on ? "config.nyanlex.state.on" : "config.nyanlex.state.off");
    }

    static String serviceName(Text t, LegacyConfig cfg) {
        switch (cfg.serviceKind()) {
            case LegacyConfig.SERVICE_CODEX:
                return t.get("screen.nyanlex.service.codex");
            case LegacyConfig.SERVICE_AI:
                return t.get("screen.nyanlex.service.ai", cfg.aiHost());
            default:
                return t.get("screen.nyanlex.provider.google");
        }
    }

    /** The category index page: six categories in two columns, then the quick setup. */
    static List<Row> indexRows(Text t) {
        List<Row> rows = new ArrayList<Row>();
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            rows.add(new Row(i % 2 == 0 ? HALF_LEFT : HALF_RIGHT, t.get(CATEGORY_KEYS[i]), A_CATEGORY + i));
        }
        rows.add(new Row(GAP, "", 0));
        rows.add(new Row(BUTTON, t.get("config.nyanlex.quick_setup.open"), A_QUICK_SETUP));
        return rows;
    }

    static List<Row> categoryRows(int category, LegacyConfig cfg, Text t, String languageLabel,
                                  String version) {
        List<Row> rows = new ArrayList<Row>();
        switch (category) {
            case CAT_GENERAL: {
                rows.add(new Row(BUTTON, t.get("config.nyanlex.quick_setup.open"), A_QUICK_SETUP));
                rows.add(new Row(BUTTON, t.get("config.nyanlex.online",
                        onOff(t, cfg.translationRequestsEnabled)), A_ONLINE));
                Row desc = new Row(TEXT, t.get("config.nyanlex.online.desc", serviceName(t, cfg)), 0);
                desc.color = GRAY;
                rows.add(desc);
                rows.add(new Row(BUTTON, languageLabel, A_LANGUAGE));
                rows.add(new Row(BUTTON, t.get(cfg.enabled ? "config.nyanlex.enabled"
                        : "config.nyanlex.disabled"), A_ENABLED));
                rows.add(new Row(BUTTON, t.get("config.nyanlex.keys.open"), A_KEYS));
                break;
            }
            case CAT_DISPLAY: {
                rows.add(new Row(BUTTON, t.get("config.nyanlex.display", t.get(cfg.showOriginal
                        ? "config.nyanlex.mode.both" : "config.nyanlex.mode.translation")), A_DISPLAY));
                Row hint = new Row(TEXT, t.get("config.nyanlex.surface.hint"), 0);
                hint.color = GRAY;
                rows.add(hint);
                rows.add(new Row(BUTTON, t.get("config.nyanlex.requests.open"), A_TERMS));
                break;
            }
            case CAT_SERVICE: {
                rows.add(new Row(BUTTON, t.get("config.nyanlex.service", t.get(cfg.aiEnabled
                        ? "config.nyanlex.engine.ai" : "config.nyanlex.engine.machine")), A_SERVICE));
                if (!cfg.aiEnabled) {
                    Row title = new Row(TEXT, t.get("config.nyanlex.machine.title"), 0);
                    title.color = HEADING;
                    rows.add(title);
                    Row desc = new Row(TEXT, t.get("config.nyanlex.machine.desc"), 0);
                    desc.color = GRAY;
                    rows.add(desc);
                }
                rows.add(new Row(BUTTON, t.get("screen.nyanlex.ai.title"), A_AI));
                rows.add(new Row(BUTTON, t.get("config.nyanlex.ai.machine_fallback",
                        onOff(t, !cfg.disableGoogleFallbackForAi)), A_FALLBACK));
                break;
            }
            case CAT_MINE: {
                Row hint = new Row(TEXT, t.get("config.nyanlex.mine.hint"), 0);
                hint.color = GRAY;
                rows.add(hint);
                rows.add(new Row(HALF_LEFT, t.get("config.nyanlex.translations.export"), A_EXPORT));
                rows.add(new Row(HALF_RIGHT, t.get("config.nyanlex.translations.import"), A_IMPORT));
                break;
            }
            case CAT_ADVANCED: {
                rows.add(new Row(BUTTON, t.get("config.nyanlex.request_cooldown.open"), A_COOLDOWN));
                rows.add(new Row(BUTTON, t.get("config.nyanlex.chat_delivery.short", t.get(
                        cfg.deliverChatTranslationsInOrder ? "config.nyanlex.chat_delivery.ordered"
                                : "config.nyanlex.chat_delivery.ready_first")), A_CHAT_ORDER));
                rows.add(new Row(BUTTON, t.get("config.nyanlex.debug.short",
                        onOff(t, cfg.debugTranslationOverlay)), A_DEBUG));
                break;
            }
            default: {
                Row ver = new Row(TEXT, t.get("config.nyanlex.about.version", version), 0);
                ver.color = HEADING;
                rows.add(ver);
                rows.add(new Row(BUTTON, t.get("config.nyanlex.about.help"), A_HELP));
                rows.add(new Row(GAP, "", 0));
                Row desc = new Row(TEXT, t.get("config.nyanlex.about.github.desc"), 0);
                desc.color = GRAY;
                rows.add(desc);
                Row url = new Row(TEXT, GITHUB_DISPLAY, 0);
                url.color = NORMAL;
                rows.add(url);
                rows.add(new Row(BUTTON, t.get("config.nyanlex.about.github"), A_GITHUB));
                break;
            }
        }
        return rows;
    }

    /**
     * Applies the actions that only flip a setting; returns true when the rows need rebuilding.
     * Navigation actions (opening another screen, the language and export/import actions) are the
     * screen's job and return false here.
     */
    static boolean perform(int action, LegacyConfig cfg) {
        switch (action) {
            case A_ONLINE:
                cfg.translationRequestsEnabled = !cfg.translationRequestsEnabled;
                return true;
            case A_ENABLED:
                cfg.enabled = !cfg.enabled;
                return true;
            case A_DISPLAY:
                cfg.showOriginal = !cfg.showOriginal;
                return true;
            case A_SERVICE:
                cfg.aiEnabled = !cfg.aiEnabled;
                return true;
            case A_FALLBACK:
                cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi;
                return true;
            case A_CHAT_ORDER:
                cfg.deliverChatTranslationsInOrder = !cfg.deliverChatTranslationsInOrder;
                return true;
            case A_DEBUG:
                cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
                return true;
            default:
                return false;
        }
    }

    // ================= quick setup =================

    static final int PAGE_WELCOME = 0;
    static final int PAGE_METHOD = 1;
    static final int PAGE_DONE = 2;

    static final int CHOICE_NONE = -1;
    static final int CHOICE_MACHINE = 0;
    static final int CHOICE_AI = 1;
    static final int CHOICE_OFF = 2;

    static final int S_LATER = 200;
    static final int S_START = 201;
    static final int S_BACK = 202;
    static final int S_NEXT = 203;
    static final int S_FINISH = 204;
    static final int S_KEYS = 205;
    static final int S_CHOICE = 210;

    /** Quick setup state: the page and the (not yet applied) translation method. */
    static final class Setup {
        int page = PAGE_WELCOME;
        int choice = CHOICE_NONE;
        boolean aiMissing;

        /** Opened from the settings: pre-fill the current method, except while online translation is off. */
        Setup(LegacyConfig cfg) {
            if (cfg != null && cfg.translationRequestsEnabled) {
                choice = cfg.aiEnabled ? CHOICE_AI : CHOICE_MACHINE;
            }
        }

        String title(Text t) {
            switch (page) {
                case PAGE_WELCOME: return t.get("screen.nyanlex.setup.welcome.title");
                case PAGE_METHOD: return t.get("screen.nyanlex.setup.method.title");
                default: return t.get("screen.nyanlex.setup.done.title");
            }
        }

        List<Row> rows(Text t, String keyItem, String keyScreen, String keySettings) {
            List<Row> rows = new ArrayList<Row>();
            if (page == PAGE_WELCOME) {
                rows.add(new Row(TEXT, t.get("screen.nyanlex.setup.welcome.body"), 0));
                Row hint = new Row(TEXT, t.get("screen.nyanlex.setup.welcome.hint"), 0);
                hint.color = GRAY;
                rows.add(hint);
                rows.add(new Row(GAP, "", 0));
                rows.add(new Row(HALF_LEFT, t.get("screen.nyanlex.setup.later"), S_LATER));
                rows.add(new Row(HALF_RIGHT, t.get("screen.nyanlex.setup.start"), S_START));
            } else if (page == PAGE_METHOD) {
                String[] names = {"machine", "ai", "off"};
                for (int i = 0; i < names.length; i++) {
                    Row r = new Row(BUTTON, (choice == i ? "> " : "") + t.get(
                            "screen.nyanlex.setup.method." + names[i]) + (choice == i ? " <" : ""),
                            S_CHOICE + i);
                    r.selected = choice == i;
                    r.hint = t.get("screen.nyanlex.setup.method." + names[i] + ".desc");
                    rows.add(r);
                }
                Row area = new Row(HINT, aiMissing ? t.get("screen.nyanlex.setup.method.ai_missing")
                        : t.get("screen.nyanlex.setup.method.hint"), 0);
                area.color = GRAY;
                if (choice >= 0 && choice < names.length) {
                    area.hint = t.get("screen.nyanlex.setup.method." + names[choice] + ".desc");
                }
                rows.add(area);
                rows.add(new Row(GAP, "", 0));
                rows.add(new Row(HALF_LEFT, t.get("screen.nyanlex.setup.back"), S_BACK));
                Row next = new Row(HALF_RIGHT, t.get("screen.nyanlex.setup.next"), S_NEXT);
                next.enabled = choice != CHOICE_NONE;
                rows.add(next);
            } else {
                rows.add(new Row(TEXT, t.get("screen.nyanlex.setup.done.intro"), 0));
                rows.add(new Row(TEXT, t.get("screen.nyanlex.setup.done.key.item", keyItem), 0));
                rows.add(new Row(TEXT, t.get("screen.nyanlex.setup.done.key.screen", keyScreen), 0));
                rows.add(new Row(TEXT, t.get("screen.nyanlex.setup.done.key.settings", keySettings), 0));
                Row hint = new Row(TEXT, t.get("screen.nyanlex.setup.done.keys_hint"), 0);
                hint.color = GRAY;
                rows.add(hint);
                rows.add(new Row(BUTTON, t.get("screen.nyanlex.setup.done.keys_button"), S_KEYS));
                Row footer = new Row(TEXT, t.get("screen.nyanlex.setup.done.footer"), 0);
                footer.color = GRAY;
                rows.add(footer);
                rows.add(new Row(GAP, "", 0));
                rows.add(new Row(HALF_LEFT, t.get("screen.nyanlex.setup.back"), S_BACK));
                rows.add(new Row(HALF_RIGHT, t.get("screen.nyanlex.setup.finish"), S_FINISH));
            }
            return rows;
        }

        /** Nothing is sent before this: finishing is the only place the choice becomes settings. */
        void apply(LegacyConfig cfg) {
            if (choice == CHOICE_MACHINE) {
                cfg.aiEnabled = false;
                cfg.translationRequestsEnabled = true;
            } else if (choice == CHOICE_AI) {
                cfg.aiEnabled = true;
                cfg.translationRequestsEnabled = true;
            } else if (choice == CHOICE_OFF) {
                cfg.translationRequestsEnabled = false;
            }
            cfg.firstRunDone = true;
        }
    }
}
