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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
            while (settingsKey.consumeClick()) client.setScreen(new LegacySettingsScreen(client.screen));
        });
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> translateTooltip(lines));
    }

    public static boolean interceptChat(final Component message) {
        if (instance == null || config == null || !config.enabled || INTERNAL_CHAT.get()
                || message == null || !shouldTranslate(message.getString())) return false;
        final Minecraft minecraft = Minecraft.getInstance();
        final String source = message.getString();
        final String target = currentTarget(minecraft);
        TRANSLATOR.translate(source, target, config.aiEnabled, true, config, translated -> minecraft.execute(() -> {
            MutableComponent output = new TextComponent(translated).setStyle(message.getStyle());
            if (config.showOriginal && !translated.equals(source)) {
                output = message.copy().append(new TextComponent("\n")).append(output);
            }
            addInternal(minecraft, output);
        }));
        return true;
    }

    private static void translateTooltip(List<Component> lines) {
        if (config == null || !config.enabled || lines == null) return;
        String target = currentTarget(Minecraft.getInstance());
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            String source = line.getString();
            if (!shouldTranslate(source)) continue;
            String translated = TRANSLATOR.cached(source, target, config.aiEnabled);
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
                || minecraft.screen instanceof LegacySettingsScreen
                || minecraft.screen instanceof LegacyLanguageScreen) return source;
        String plain = source.getString();
        if (!shouldTranslate(plain)) return source;
        String target = currentTarget(minecraft);
        String translated = TRANSLATOR.cached(plain, target, config.aiEnabled);
        if (translated == null) {
            TRANSLATOR.translate(plain, target, config.aiEnabled, true, config, ignored -> {});
            return source;
        }
        return translated.equals(plain) ? source : new TextComponent(translated).setStyle(source.getStyle());
    }

    public static boolean beginInternalRender() {
        boolean previous = INTERNAL_RENDER.get();
        INTERNAL_RENDER.set(Boolean.TRUE);
        return previous;
    }
    public static void endInternalRender(boolean previous) { INTERNAL_RENDER.set(previous); }
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
    static void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            Writer writer = Files.newBufferedWriter(configPath);
            try { GSON.toJson(config, writer); } finally { writer.close(); }
        } catch (Exception ignored) {}
    }

    private static LegacyConfig loadConfig() {
        if (Files.isRegularFile(configPath)) {
            try {
                Reader reader = Files.newBufferedReader(configPath);
                try {
                    LegacyConfig loaded = GSON.fromJson(reader, LegacyConfig.class);
                    if (loaded != null) {
                        if (loaded.aiApiKeys == null) loaded.aiApiKeys = new java.util.ArrayList<String>();
                        if (loaded.requestCooldownMs < 0) loaded.requestCooldownMs = 2000;
                        if (loaded.failureBackoffMs < 0) loaded.failureBackoffMs = 10000;
                        return loaded;
                    }
                } finally { reader.close(); }
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
