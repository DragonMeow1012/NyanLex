package com.dragonmeow.mctranslator.forgelegacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;

import java.util.List;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

@Mod(modid = "mctranslator", name = "Minecraft Translator", version = "1.0.2", clientSideOnly = true)
public final class MinecraftTranslatorForge {
    private final LegacyTranslator translator = new LegacyTranslator();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final File configFile = new File("config", "mctranslator-forge-legacy.json");
    private LegacyConfig config;
    private final KeyBinding toggle = new KeyBinding("key.mctranslator.toggle", Keyboard.KEY_G,
            "category.mctranslator");
    private volatile boolean enabled = true;
    private volatile boolean internal;

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        config = loadConfig();
        ClientRegistry.registerKeyBinding(toggle);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void onInput(InputUpdateEvent event) {
        if (toggle.isPressed()) { enabled = !enabled; config.enabled = enabled; saveConfig(); }
    }

    @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) translator.flushBatch();
    }

    @SubscribeEvent public void onChat(ClientChatReceivedEvent event) {
        if (!enabled || internal || event.getMessage() == null) return;
        final ITextComponent source = event.getMessage();
        final String text = source.getUnformattedText();
        if (!hasLetters(text)) return;
        event.setCanceled(true);
        final Minecraft minecraft = Minecraft.getMinecraft();
        translator.translate(text, target(minecraft), config.aiEnabled, false, config,
                translated -> minecraft.addScheduledTask(() -> {
            internal = true;
            try {
                minecraft.ingameGUI.getChatGUI().printChatMessage(
                        new TextComponentString(text + "\n" + translated));
            } finally { internal = false; }
        }));
    }

    @SubscribeEvent public void onTooltipRender(RenderTooltipEvent.Pre event) {
        // ItemTooltipEvent is also fired by background tooltip/index builders.  Pre is
        // reached only when Forge is actually about to draw this tooltip on screen.
        if (!enabled || config == null || event.getStack() == null
                || event.getStack().isEmpty() || event.getLines().isEmpty()) return;
        List<String> lines = event.getLines();
        String target = target(Minecraft.getMinecraft());
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i);
            if (!hasLetters(source)) continue;
            String translated = translator.cached(source, target, config.aiEnabled, config);
            if (translated == null) translator.translate(source, target, config.aiEnabled, true, config, ignored -> {});
            else if (!translated.equals(source)) lines.set(i, translated);
        }
    }

    @SubscribeEvent public void onOverlayText(RenderGameOverlayEvent.Text event) {
        if (!enabled || config == null) return;
        translateOverlay(event.getLeft());
        translateOverlay(event.getRight());
    }

    @SubscribeEvent public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (config == null || !config.debugTranslationOverlay
                || event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        List<LegacyTranslator.DebugEntry> entries = translator.debugSnapshot();
        int start = Math.max(0, entries.size() - 8), y = 6;
        for (int i = start; i < entries.size(); i++) {
            LegacyTranslator.DebugEntry entry = entries.get(i);
            minecraft.fontRenderer.drawStringWithShadow("[" + entry.engine + " " + entry.status + "] " + entry.source,
                    6, y, 0x80FF80);
            y += 10;
        }
    }

    private void translateOverlay(List<String> lines) {
        String target = target(Minecraft.getMinecraft());
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i);
            if (!hasLetters(source)) continue;
            String translated = translator.cached(source, target, config.aiEnabled, config);
            if (translated == null) translator.translate(source, target, config.aiEnabled, false, config, ignored -> {});
            else lines.set(i, translated);
        }
    }

    private LegacyConfig loadConfig() {
        if (configFile.isFile()) try {
            FileReader reader = new FileReader(configFile);
            try {
                LegacyConfig loaded = GSON.fromJson(reader, LegacyConfig.class);
                if (loaded != null) {
                    if (loaded.aiApiKeys == null) loaded.aiApiKeys = new java.util.ArrayList<String>();
                    loaded.machineTranslationProvider = LegacyConfig.normalizeMachineProvider(
                            loaded.machineTranslationProvider);
                    if (loaded.requestCooldownMs < 0) loaded.requestCooldownMs = 6000;
                    if (loaded.batchWindowMs < 0) loaded.batchWindowMs = 5000;
                    if (loaded.failureBackoffMs < 0) loaded.failureBackoffMs = 10000;
                    enabled = loaded.enabled;
                    return loaded;
                }
            } finally { reader.close(); }
        } catch (Exception ignored) {}
        LegacyConfig created = new LegacyConfig();
        saveConfig(created);
        return created;
    }

    private void saveConfig() { saveConfig(config); }
    private void saveConfig(LegacyConfig value) {
        try {
            value.machineTranslationProvider = LegacyConfig.normalizeMachineProvider(
                    value.machineTranslationProvider);
            File parent = configFile.getParentFile(); if (parent != null) parent.mkdirs();
            FileWriter writer = new FileWriter(configFile);
            try { GSON.toJson(value, writer); } finally { writer.close(); }
        } catch (Exception ignored) {}
    }

    private static String target(Minecraft minecraft) {
        String code = minecraft.gameSettings.language;
        if ("zh_tw".equals(code) || "zh_hk".equals(code)) return "zh-TW";
        if ("zh_cn".equals(code)) return "zh-CN";
        int split = code.indexOf('_');
        return split > 0 ? code.substring(0, split) : code;
    }

    private static boolean hasLetters(String text) {
        if (text == null || text.trim().length() < 2) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) return true;
        return false;
    }
}
