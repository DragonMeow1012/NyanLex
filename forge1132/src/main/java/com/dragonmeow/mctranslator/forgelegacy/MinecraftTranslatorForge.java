package com.dragonmeow.mctranslator.forgelegacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Mod("mctranslator")
public final class MinecraftTranslatorForge {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MinecraftTranslatorForge instance;
    static final LegacyTranslator TRANSLATOR = new LegacyTranslator();
    private final File configFile = new File("config", "mctranslator-forge-legacy.json");
    private final KeyBinding settingsKey = new KeyBinding("key.mctranslator.mode", GLFW.GLFW_KEY_G, "category.mctranslator");
    private final KeyBinding toggleKey = new KeyBinding("key.mctranslator.toggle", GLFW.GLFW_KEY_H, "category.mctranslator");
    private final Map<Integer, ITextComponent> renderedNames = new ConcurrentHashMap<Integer, ITextComponent>();
    private LegacyConfig config;
    private LegacyCodexClient codexClient;
    private volatile boolean internal;

    public MinecraftTranslatorForge() {
        instance = this;
        config = loadConfig();
        Path configDir = configFile.getAbsoluteFile().getParentFile().toPath();
        codexClient = new LegacyCodexClient(configDir.resolve("mctranslator-codex-home"), configDir.resolve("mctranslator-codex-workspace"));
        TRANSLATOR.setCodexClient(codexClient);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { if (codexClient != null) codexClient.close(); }
        }, "mctranslator-codex-shutdown"));
        ClientRegistry.registerKeyBinding(settingsKey);
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        TRANSLATOR.flushBatch();
        Minecraft minecraft = Minecraft.getInstance();
        warmVisibleItemNames(minecraft);
        while (settingsKey.isPressed()) minecraft.displayGuiScreen(new ForgeSettingsScreen(minecraft.currentScreen));
        while (toggleKey.isPressed()) { config.enabled = !config.enabled; saveConfig(); }
    }

    @SubscribeEvent public void onChat(ClientChatReceivedEvent event) {
        if (!config.enabled || internal || event.getMessage() == null) return;
        final String text = event.getMessage().getString();
        if (!hasLetters(text)) return;
        event.setCanceled(true);
        final Minecraft minecraft = Minecraft.getInstance();
        TRANSLATOR.translate(text, currentTarget(), config.aiEnabled, false, config, translated -> minecraft.addScheduledTask(() -> {
            internal = true;
            try {
                String output = config.showOriginal && !translated.equals(text) ? text + "\n" + translated : translated;
                minecraft.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(output));
            } finally { internal = false; }
        }));
    }

    @SubscribeEvent public void onTooltipRender(RenderTooltipEvent.Pre event) {
        if (!config.enabled || event.getStack() == null || event.getStack().isEmpty() || event.getLines().isEmpty()) return;
        translateVisibleLines(event.getLines(), true);
    }
    @SubscribeEvent public void onOverlayText(RenderGameOverlayEvent.Text event) {
        if (!config.enabled) return;
        translateVisibleLines(event.getLeft(), false);
        translateVisibleLines(event.getRight(), false);
    }

    @SubscribeEvent public void onNameTagPre(RenderLivingEvent.Specials.Pre event) {
        EntityLivingBase entity = event.getEntity();
        if (entity == null || entity instanceof EntityPlayer || !config.enabled) return;
        ITextComponent originalName = entity.getCustomName();
        String source = originalName == null ? "" : originalName.getString();
        if (!hasLetters(source) || nameTagMatchesListedPlayer(source)) return;
        String translated = TRANSLATOR.cached(source, currentTarget(), config.aiEnabled, config);
        if (translated == null) TRANSLATOR.translate(source, currentTarget(), config.aiEnabled, false, config, ignored -> {});
        else if (!translated.equals(source)) {
            renderedNames.put(entity.getEntityId(), originalName);
            entity.setCustomName(new TextComponentString(translated));
        }
    }
    @SubscribeEvent public void onNameTagPost(RenderLivingEvent.Specials.Post event) {
        EntityLivingBase entity = event.getEntity();
        if (entity == null) return;
        ITextComponent original = renderedNames.remove(entity.getEntityId());
        if (original != null) entity.setCustomName(original);
    }

    @SubscribeEvent public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (!config.debugTranslationOverlay || event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft minecraft = Minecraft.getInstance();
        int y = 6;
        minecraft.fontRenderer.drawStringWithShadow(tokenUsageLine(), 6, y, 0x80D8FF);
        y += 11;
        List<LegacyTranslator.DebugEntry> entries = TRANSLATOR.debugSnapshot();
        for (int i = Math.max(0, entries.size() - 8); i < entries.size(); i++) {
            LegacyTranslator.DebugEntry entry = entries.get(i);
            int color = entry.status.contains("failed (429 rate limit)") ? 0xFFFF40FF : entry.status.contains("failed (") ? 0xFFFF8080 : 0x80FF80;
            minecraft.fontRenderer.drawStringWithShadow("[" + entry.engine + " " + entry.status + "] " + entry.source, 6, y, color);
            y += 10;
        }
    }

    private void warmVisibleItemNames(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || config == null || !config.enabled) return;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        for (int slot = 0; slot < 9; slot++) addWarmName(names, minecraft.player.inventory.getStackInSlot(slot));
        addWarmName(names, minecraft.player.getHeldItemOffhand());
        if (minecraft.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer) {
            net.minecraft.client.gui.inventory.GuiContainer screen =
                    (net.minecraft.client.gui.inventory.GuiContainer) minecraft.currentScreen;
            for (net.minecraft.inventory.Slot slot : screen.inventorySlots.inventorySlots)
                if (slot != null && slot.getHasStack()) addWarmName(names, slot.getStack());
        }
        final String target = currentTarget();
        for (String name : names) if (TRANSLATOR.cached(name, target, config.aiEnabled, config) == null)
            TRANSLATOR.translate(name, target, config.aiEnabled, false, config, ignored -> {});
    }

    private static void addWarmName(java.util.Set<String> names, net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String name = stack.getDisplayName().getString();
        if (hasLetters(name)) names.add(name);
    }
    private void translateVisibleLines(List<String> lines, boolean highPriority) {
        String target = currentTarget();
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i);
            if (!hasLetters(source)) continue;
            String translated = TRANSLATOR.cached(source, target, config.aiEnabled, config);
            if (translated == null) TRANSLATOR.translate(source, target, config.aiEnabled, highPriority, config, ignored -> {});
            else if (!translated.equals(source)) lines.set(i, translated);
        }
    }
    private boolean nameTagMatchesListedPlayer(String plain) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) return false;
        for (NetworkPlayerInfo info : minecraft.getConnection().getPlayerInfoMap()) {
            String name = info == null || info.getGameProfile() == null ? null : info.getGameProfile().getName();
            if (name == null || name.isEmpty()) continue;
            for (int at = plain.indexOf(name); at >= 0; at = plain.indexOf(name, at + 1)) {
                int end = at + name.length();
                if ((at == 0 || !isNameTokenChar(plain.charAt(at - 1)))
                        && (end == plain.length() || !isNameTokenChar(plain.charAt(end)))) return true;
            }
        }
        return false;
    }

    static LegacyConfig config() { return instance.config; }
    static LegacyCodexClient codexClient() { return instance.codexClient; }
    static void save() { instance.saveConfig(); }
    static void testAi(final Consumer<String> callback) {
        final Minecraft minecraft = Minecraft.getInstance();
        TRANSLATOR.testAi(currentTarget(), config(), result -> minecraft.addScheduledTask(() -> callback.accept(result)));
    }
    static String currentTarget() {
        LegacyConfig cfg = config();
        if (!cfg.followGameLanguage) return cfg.targetLang;
        String code = Minecraft.getInstance().gameSettings.language;
        if ("zh_tw".equals(code) || "zh_hk".equals(code)) return "zh-TW";
        if ("zh_cn".equals(code)) return "zh-CN";
        int split = code.indexOf('_');
        return split > 0 ? code.substring(0, split) : code;
    }
    static String tokenUsageLine() {
        LegacySessionTokenUsage.Snapshot t = TRANSLATOR.tokenUsageSnapshot();
        return "TOKENS total " + t.totalTokens() + " | in " + t.inputTokens() + " (cached " + t.cachedInputTokens()
                + ") | out " + t.outputTokens() + " (reason " + t.reasoningOutputTokens() + ") | req " + t.requests();
    }

    private LegacyConfig loadConfig() {
        if (configFile.isFile()) try {
            FileReader reader = new FileReader(configFile);
            try {
                LegacyConfig loaded = LegacyConfig.normalizeLoaded(GSON.fromJson(reader, LegacyConfig.class));
                if (loaded != null) { saveConfig(loaded); return loaded; }
            } finally { reader.close(); }
        } catch (Exception ignored) {}
        LegacyConfig created = new LegacyConfig();
        saveConfig(created);
        return created;
    }
    private void saveConfig() { saveConfig(config); }
    private void saveConfig(LegacyConfig value) {
        try {
            value.machineTranslationProvider = LegacyConfig.normalizeMachineProvider(value.machineTranslationProvider);
            File parent = configFile.getParentFile(); if (parent != null) parent.mkdirs();
            FileWriter writer = new FileWriter(configFile);
            try { GSON.toJson(value, writer); } finally { writer.close(); }
        } catch (Exception ignored) {}
    }
    private static boolean isNameTokenChar(char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '_';
    }
    private static boolean hasLetters(String text) {
        if (text == null || text.trim().length() < 2) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) return true;
        return false;
    }
}
