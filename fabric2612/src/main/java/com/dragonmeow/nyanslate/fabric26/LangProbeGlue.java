package com.dragonmeow.nyanslate.fabric26;

import com.dragonmeow.nyanslate.translate.LangProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measurement-only glue for {@link LangProbe} (1.21.1 tree). Never alters a Component and is
 * a single volatile read + boolean check while the debug overlay is off.
 */
public final class LangProbeGlue {
    private static final String TARGET_CODE = "zh_tw";
    private static volatile LangProbe probe;

    private LangProbeGlue() {
    }

    public static void install(LangProbe p) {
        probe = p;
        p.setLookup(new Lookup());
    }

    /** Observe one Component about to be flattened for translation on {@code surface}. */
    public static void observe(String surface, Component c) {
        LangProbe p = probe;
        if (p == null || c == null || !p.isEnabled()) return;
        try {
            String text = c.getString();
            if (text == null || text.isBlank()) return;
            p.observe(surface, text.hashCode(), toNode(c, 0, true));
        } catch (RuntimeException ignored) {
            // measurement only
        }
    }

    private static LangProbe.Node toNode(Component c, int depth, boolean isRoot) {
        LangProbe.Node node;
        if (c.getContents() instanceof TranslatableContents t) {
            node = new LangProbe.Node(t.getKey(), false);
            for (Object arg : t.getArgs()) {
                if (arg instanceof Component ac) {
                    node.argTypes.add("component");
                    if (depth < 8) node.children.add(toNode(ac, depth + 1, false));
                } else if (arg == null) node.argTypes.add("null");
                else if (arg instanceof CharSequence) node.argTypes.add("string");
                else if (arg instanceof Number) node.argTypes.add("number");
                else if (arg instanceof Boolean) node.argTypes.add("boolean");
                else node.argTypes.add("other");
            }
        } else {
            String own = c.getContents().toString();
            boolean hasText = false;
            if (c.getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents pt) {
                hasText = !pt.text().isEmpty();
            } else {
                hasText = !own.isEmpty();
            }
            node = new LangProbe.Node(null, hasText);
        }
        List<Component> siblings = c.getSiblings();
        node.siblingCount = siblings.size();
        if (depth < 8) {
            for (Component s : siblings) node.children.add(toNode(s, depth + 1, false));
        }
        return node;
    }

    /**
     * Reads the built-in target-language table by loading {@code zh_tw} through the live
     * ResourceManager (the same call LanguageManager uses for the selected language), so it
     * works while the game language is English and includes mod/modpack lang files.
     */
    private static final class Lookup implements LangProbe.LangLookup {
        private ResourceManager loadedFor;
        private ClientLanguage target;
        private String error;

        private synchronized ClientLanguage target() {
            Minecraft mc = Minecraft.getInstance();
            ResourceManager rm = mc == null ? null : mc.getResourceManager();
            if (rm == null) return null;
            if (rm != loadedFor || target == null) {
                try {
                    target = ClientLanguage.loadFrom(rm, List.of(TARGET_CODE), false);
                    error = null;
                } catch (RuntimeException e) {
                    target = null;
                    error = e.toString();
                }
                loadedFor = rm;
            }
            return target;
        }

        @Override
        public Boolean targetHas(String key) {
            ClientLanguage t = target();
            return t == null ? null : t.has(key);
        }

        @Override
        public Boolean englishHas(String key) {
            // The currently selected language table (en_us fallback included by vanilla).
            return Language.getInstance().has(key);
        }

        @Override
        public Map<String, Object> info() {
            Map<String, Object> m = new LinkedHashMap<>();
            Minecraft mc = Minecraft.getInstance();
            m.put("method", "ClientLanguage.loadFrom(resourceManager, [zh_tw])");
            m.put("selectedLanguage", mc == null || mc.options == null ? null : mc.options.languageCode);
            ClientLanguage t = target();
            m.put("targetLoaded", t != null);
            m.put("sanityItemBow", t == null ? null : t.has("item.minecraft.bow"));
            m.put("sanityGuiDone", t == null ? null : t.has("gui.done"));
            if (error != null) m.put("error", error);
            return m;
        }
    }
}
