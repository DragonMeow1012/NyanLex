package com.dragonmeow.mctranslator.legacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class LegacyTranslatorMod implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ThreadLocal<Boolean> INTERNAL_CHAT = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };
    static final LegacyTranslator TRANSLATOR = new LegacyTranslator();
    private static final ThreadLocal<Boolean> INTERNAL_RENDER = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };
    private static final ThreadLocal<java.util.ArrayDeque<Screen>> SCREEN_RENDER_STACK =
            new ThreadLocal<java.util.ArrayDeque<Screen>>() {
                @Override protected java.util.ArrayDeque<Screen> initialValue() {
                    return new java.util.ArrayDeque<Screen>();
                }
            };
    private static LegacyTranslatorMod instance;
    private static LegacyConfig config;
    private static Path configPath;
    private KeyMapping settingsKey;

    @Override public void onInitializeClient() {
        instance = this;
        configPath = FabricLoader.getInstance().getConfigDir().resolve("mctranslator-legacy.json");
        config = loadConfig();
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mctranslator.mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "category.mctranslator"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            syncLanguage(client);
            TRANSLATOR.flushBatch();
            while (settingsKey.consumeClick()) client.setScreen(new LegacySettingsScreen(client.screen));
        });
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> translateTooltip(stack, lines));
    }

    public static boolean interceptChat(final Component message) {
        if (instance == null || config == null || !config.enabled || INTERNAL_CHAT.get()
                || message == null || !shouldTranslate(message.getString())) return false;
        final Minecraft minecraft = Minecraft.getInstance();
        final String source = message.getString();
        final String target = currentTarget(minecraft);
        TRANSLATOR.translate(source, target, config.aiEnabled, false, config, translated -> minecraft.execute(() -> {
            Component output = new TextComponent(translated).setStyle(message.getStyle());
            if (config.showOriginal && !translated.equals(source)) {
                output = message.copy().append(new TextComponent("\n")).append(output);
            }
            addInternal(minecraft, output);
        }));
        return true;
    }

    private static void translateTooltip(ItemStack stack, List<Component> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        if (config == null || !config.enabled || stack == null || stack.isEmpty()
                || lines == null || minecraft == null || minecraft.level == null
                || !minecraft.isSameThread() || !renderingCurrentScreen(minecraft)) return;
        String target = currentTarget(minecraft);
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            String source = line.getString();
            if (!shouldTranslate(source)) continue;
            String translated = TRANSLATOR.cached(source, target, config.aiEnabled, config);
            if (translated == null) {
                TRANSLATOR.translate(source, target, config.aiEnabled, true, config, ignored -> {});
            } else if (!translated.equals(source)) {
                lines.set(i, new TextComponent(translated).setStyle(line.getStyle()));
            }
        }
    }

    private static void addInternal(Minecraft minecraft, Component component) {
        boolean previous = INTERNAL_CHAT.get();
        INTERNAL_CHAT.set(Boolean.TRUE);
        try { minecraft.gui.getChat().addMessage(component); }
        finally { INTERNAL_CHAT.set(previous); }
    }

    static LegacyConfig config() { return config; }
    public static Component translateVisible(Component source) {
        if (source == null || config == null || !config.enabled || INTERNAL_RENDER.get()) return source;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || minecraft.screen != null && !screenTranslationAllowed(minecraft.screen)) return source;
        String plain = source.getString();
        if (!shouldTranslate(plain)) return source;
        String target = currentTarget(minecraft);
        String translated = TRANSLATOR.cached(plain, target, config.aiEnabled, config);
        if (translated == null) {
            TRANSLATOR.translate(plain, target, config.aiEnabled, false, config, ignored -> {});
            return source;
        }
        return translated.equals(plain) ? source : new TextComponent(translated).setStyle(source.getStyle());
    }
    public static String translateVisibleString(String source) {
        if (source == null || config == null || !config.enabled || INTERNAL_RENDER.get()) return source;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || minecraft.screen != null && !screenTranslationAllowed(minecraft.screen)
                || !shouldTranslate(source)) return source;
        String target = currentTarget(minecraft);
        String translated = TRANSLATOR.cached(source, target, config.aiEnabled, config);
        if (translated == null) {
            TRANSLATOR.translate(source, target, config.aiEnabled, false, config, ignored -> {});
            return source;
        }
        return translated;
    }

    public static boolean beginInternalRender() {
        boolean previous = INTERNAL_RENDER.get();
        INTERNAL_RENDER.set(Boolean.TRUE);
        return previous;
    }
    public static void endInternalRender(boolean previous) { INTERNAL_RENDER.set(previous); }
    public static void beginScreenRender(Screen screen) {
        if (screen != null) SCREEN_RENDER_STACK.get().push(screen);
    }
    public static void endScreenRender() {
        java.util.ArrayDeque<Screen> stack = SCREEN_RENDER_STACK.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) SCREEN_RENDER_STACK.remove();
    }
    private static boolean renderingCurrentScreen(Minecraft minecraft) {
        Screen screen = minecraft.screen;
        if (!screenTranslationAllowed(screen)) return false;
        java.util.ArrayDeque<Screen> stack = SCREEN_RENDER_STACK.get();
        return !stack.isEmpty() && stack.peek() == screen;
    }
    private static boolean screenTranslationAllowed(Screen screen) {
        if (screen == null || screen.getClass().getName().startsWith("com.dragonmeow.mctranslator.")) return false;
        Component title = screen.getTitle();
        String key = title instanceof net.minecraft.network.chat.TranslatableComponent
                ? ((net.minecraft.network.chat.TranslatableComponent) title).getKey() : null;
        return !blockedVanillaSettingsTitle(key);
    }
    private static boolean blockedVanillaSettingsTitle(String key) {
        return "options.title".equals(key)
                || "options.language".equals(key) || "options.language.title".equals(key)
                || "options.skinCustomisation".equals(key) || "options.skinCustomisation.title".equals(key)
                || "options.sounds".equals(key) || "options.sounds.title".equals(key)
                || "options.controls".equals(key) || "controls.title".equals(key)
                || "controls.keybinds".equals(key) || "controls.keybinds.title".equals(key)
                || "options.mouse_settings".equals(key) || "options.mouse_settings.title".equals(key)
                || "options.chat".equals(key) || "options.chat.title".equals(key)
                || "options.resourcepack".equals(key) || "resourcePack.title".equals(key)
                || "options.accessibility".equals(key) || "options.accessibility.title".equals(key)
                || "options.font".equals(key) || "options.font.title".equals(key)
                || "options.telemetry".equals(key) || "telemetry_info.screen.title".equals(key)
                || "options.credits_and_attribution".equals(key)
                || "credits_and_attribution.screen.title".equals(key)
                || "options.multiplayer.title".equals(key) || "options.online.title".equals(key)
                || "debug.options.title".equals(key)
                || "accessibility.onboarding.screen.title".equals(key);
    }
    public static List<String> debugLines() {
        List<LegacyTranslator.DebugEntry> entries = TRANSLATOR.debugSnapshot();
        java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
        int start = Math.max(0, entries.size() - 8);
        for (int i = start; i < entries.size(); i++) {
            LegacyTranslator.DebugEntry entry = entries.get(i);
            lines.add("[" + entry.engine + " " + entry.status + "] " + entry.source);
        }
        return lines;
    }
    public static boolean debugEnabled() { return config != null && config.debugTranslationOverlay; }
    static void saveConfig() { saveConfig(config); }
    private static void saveConfig(LegacyConfig value) {
        if (value == null) return;
        try {
            value.machineTranslationProvider = LegacyConfig.normalizeMachineProvider(
                    value.machineTranslationProvider);
            Files.createDirectories(configPath.getParent());
            Writer writer = Files.newBufferedWriter(configPath);
            try { GSON.toJson(value, writer); } finally { writer.close(); }
        } catch (Exception ignored) {}
    }

    private static LegacyConfig loadConfig() {
        if (Files.isRegularFile(configPath)) {
            try {
                Reader reader = Files.newBufferedReader(configPath);
                LegacyConfig loaded;
                try {
                    loaded = GSON.fromJson(reader, LegacyConfig.class);
                } finally { reader.close(); }
                loaded = LegacyConfig.normalizeLoaded(loaded);
                if (loaded != null) {
                    saveConfig(loaded);
                    return loaded;
                }
            } catch (Exception ignored) {}
        }
        return new LegacyConfig();
    }

    private static void syncLanguage(Minecraft minecraft) {
        if (config != null && config.followGameLanguage && minecraft != null && minecraft.options != null) {
            String desired = mapLanguage(minecraft.options.languageCode);
            if (!desired.equals(config.targetLang)) { config.targetLang = desired; saveConfig(); }
        }
    }

    static String currentTarget(Minecraft minecraft) {
        if (config.followGameLanguage && minecraft != null && minecraft.options != null)
            return mapLanguage(minecraft.options.languageCode);
        return config.targetLang;
    }

    static String mapLanguage(String code) {
        if (code == null) return "en";
        String normalized = code.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("zh_tw") || normalized.equals("zh_hk")) return "zh-TW";
        if (normalized.equals("zh_cn")) return "zh-CN";
        int split = normalized.indexOf('_');
        return split > 0 ? normalized.substring(0, split) : normalized;
    }

    private static boolean shouldTranslate(String text) {
        if (text == null || text.trim().length() < 2) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) return true;
        return false;
    }
}
