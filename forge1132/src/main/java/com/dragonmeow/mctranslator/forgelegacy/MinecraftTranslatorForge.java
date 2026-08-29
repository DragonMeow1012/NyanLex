package com.dragonmeow.mctranslator.forgelegacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ChatType;
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
import java.util.LinkedHashMap;
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
    private final LegacyChatDeliveryQueue<PendingChat> pendingChats = new LegacyChatDeliveryQueue<PendingChat>();
    private final Map<Long, PendingChat> pendingChatById = new LinkedHashMap<Long, PendingChat>();
    private LegacyConfig config;
    private LegacyCodexClient codexClient;
    private long nextChatId = 1L;
    private Object chatConnection;
    private Object chatWorld;
    private long chatSessionEpoch;
    private LegacyChatRequestProfile chatRequestProfile;
    private final java.util.Set<String> warmedItemNames = new java.util.HashSet<String>();
    private Object warmedContainerScreen;
    private LegacyChatRequestProfile itemWarmProfile;
    private long nextItemWarmScanAtNanos;

    private static final int MAX_PENDING_CHATS = 512;
    private static final long CHAT_MAX_WAIT_NANOS = 15L * 1000L * 1000L * 1000L;
    private static final long ITEM_WARM_SCAN_INTERVAL_NANOS = 350L * 1000L * 1000L;

    private static final class PendingChat {
        final long id;
        final ChatType type;
        final ITextComponent original;
        final String source;
        final boolean showOriginal;
        final long queuedAtNanos;
        final Object connection;
        final Object world;
        final long sessionEpoch;
        final LegacyChatRequestProfile requestProfile;
        String translated;
        boolean displayed;

        PendingChat(long id, ChatType type, ITextComponent original, String source,
                    boolean showOriginal, long queuedAtNanos, Object connection,
                    Object world, long sessionEpoch, LegacyChatRequestProfile requestProfile) {
            this.id = id;
            this.type = type;
            this.original = original;
            this.source = source;
            this.showOriginal = showOriginal;
            this.queuedAtNanos = queuedAtNanos;
            this.connection = connection;
            this.world = world;
            this.sessionEpoch = sessionEpoch;
            this.requestProfile = requestProfile;
        }
    }

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
        Minecraft minecraft = Minecraft.getInstance();
        syncChatSession(minecraft);
        boolean toggled = false;
        while (toggleKey.isPressed()) {
            config.enabled = !config.enabled;
            toggled = true;
        }
        if (toggled) saveConfig();
        syncChatRequestProfile(minecraft);
        if (minecraft == null || minecraft.ingameGUI == null) {
            clearPendingChatState();
            TRANSLATOR.cancelPending();
            clearItemWarmState();
        } else if (config != null && config.enabled) {
            TRANSLATOR.flushBatch();
            flushPendingChats(minecraft);
            warmVisibleItemNames(minecraft);
        } else {
            TRANSLATOR.cancelPending();
            flushPendingChatOriginals(minecraft);
            clearItemWarmState();
        }
        while (minecraft != null && settingsKey.isPressed()) {
            minecraft.displayGuiScreen(new ForgeSettingsScreen(minecraft.currentScreen));
        }
    }

    @SubscribeEvent public void onChat(ClientChatReceivedEvent event) {
        if (event.getMessage() == null || event.getType() == ChatType.GAME_INFO) return;
        if (event.getType() != ChatType.CHAT && event.getType() != ChatType.SYSTEM) return;
        final Minecraft minecraft = Minecraft.getInstance();
        syncChatSession(minecraft);
        syncChatRequestProfile(minecraft);
        if (config == null || minecraft == null || minecraft.ingameGUI == null) {
            clearPendingChatState();
            TRANSLATOR.cancelPending();
            return;
        }
        if (!config.enabled) {
            TRANSLATOR.cancelPending();
            flushPendingChatOriginals(minecraft);
            return;
        }
        final String text = event.getMessage().getString();
        final boolean shouldTranslate = hasLetters(text);
        if (!shouldTranslate) {
            if (pendingChats.isEmpty()) return;
            event.setCanceled(true);
            PendingChat passThrough = queueChat(minecraft, event.getType(), event.getMessage(), text);
            pendingChats.markReady(passThrough);
            flushReadyChats(minecraft);
            return;
        }
        event.setCanceled(true);
        final PendingChat pending = queueChat(minecraft, event.getType(), event.getMessage(), text);
        try {
            TRANSLATOR.translate(text, currentTarget(), config.aiEnabled, false, config,
                    translated -> completeChat(pending, translated));
        } catch (RuntimeException failure) {
            completeChat(pending, null);
        }
    }

    private PendingChat queueChat(Minecraft minecraft, ChatType type,
                                  ITextComponent original, String source) {
        makeRoomForPendingChat(minecraft);
        PendingChat pending = new PendingChat(allocateChatId(), type, copyComponent(original), source,
                config.showOriginal, System.nanoTime(), minecraft.getConnection(), minecraft.world,
                chatSessionEpoch, chatRequestProfile);
        pendingChats.addLast(pending);
        pendingChatById.put(pending.id, pending);
        return pending;
    }

    private void makeRoomForPendingChat(Minecraft minecraft) {
        while (pendingChats.size() >= MAX_PENDING_CHATS) {
            PendingChat oldest = pendingChats.removeFirst();
            retireAndDeliver(minecraft, oldest, oldest.original);
            flushReadyChats(minecraft);
        }
    }

    private void completeChat(final PendingChat completed, final String translated) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        minecraft.addScheduledTask(() -> {
            syncChatSession(minecraft);
            syncChatRequestProfile(minecraft);
            PendingChat pending = pendingChatById.get(completed.id);
            if (pending != completed || pending.displayed) return;
            if (pending.sessionEpoch != chatSessionEpoch || pending.connection != chatConnection
                    || pending.world != chatWorld
                    || !pending.requestProfile.equals(chatRequestProfile)) return;
            if (config == null || minecraft.ingameGUI == null) {
                clearPendingChatState();
                TRANSLATOR.cancelPending();
                return;
            }
            if (!config.enabled) {
                TRANSLATOR.cancelPending();
                flushPendingChatOriginals(minecraft);
                return;
            }
            pending.translated = translated;
            if (!pendingChats.contains(pending)) return;
            pendingChats.markReady(pending);
            flushReadyChats(minecraft);
        });
    }

    private ITextComponent translatedChatMessage(PendingChat pending, String translated) {
        if (translated == null || translated.trim().isEmpty() || translated.equals(pending.source)) {
            return pending.original;
        }
        ITextComponent translatedComponent = new TextComponentString(translated)
                .setStyle(pending.original.getStyle().createShallowCopy());
        if (!pending.showOriginal) return translatedComponent;
        ITextComponent output = copyComponent(pending.original);
        output.appendSibling(new TextComponentString("\n"));
        output.appendSibling(translatedComponent);
        return output;
    }

    /* 1.13 createCopy() copies only the concrete node, not style or siblings. */
    private static ITextComponent copyComponent(ITextComponent source) {
        ITextComponent copy = source.createCopy()
                .setStyle(source.getStyle().createShallowCopy());
        for (ITextComponent sibling : source.getSiblings()) {
            copy.appendSibling(copyComponent(sibling));
        }
        return copy;
    }

    private void flushPendingChats(Minecraft minecraft) {
        if (minecraft == null || minecraft.ingameGUI == null) {
            clearPendingChatState();
            TRANSLATOR.cancelPending();
            return;
        }
        flushReadyChats(minecraft);
        expireTimedOutChats(minecraft, System.nanoTime());
    }

    private void expireTimedOutChats(Minecraft minecraft, long now) {
        while (!pendingChats.isEmpty()) {
            PendingChat head = pendingChats.peekFirst();
            if (now - head.queuedAtNanos < CHAT_MAX_WAIT_NANOS) break;
            pendingChats.removeFirst();
            retireAndDeliver(minecraft, head, head.original);
            flushReadyChats(minecraft);
        }
    }

    private void flushReadyChats(Minecraft minecraft) {
        if (minecraft == null || minecraft.ingameGUI == null) return;
        for (PendingChat pending : pendingChats.drainReady(config.deliverChatTranslationsInOrder)) {
            retireAndDeliver(minecraft, pending, translatedChatMessage(pending, pending.translated));
        }
    }

    private void flushPendingChatOriginals(Minecraft minecraft) {
        while (!pendingChats.isEmpty()) {
            PendingChat pending = pendingChats.removeFirst();
            retireAndDeliver(minecraft, pending, pending.original);
        }
        pendingChatById.clear();
    }

    private void retireAndDeliver(Minecraft minecraft, PendingChat pending, ITextComponent message) {
        if (pending.displayed || pendingChatById.get(pending.id) != pending) return;
        pendingChatById.remove(pending.id);
        pending.displayed = true;
        if (minecraft != null && minecraft.ingameGUI != null) {
            minecraft.ingameGUI.addChatMessage(pending.type, message);
        }
    }

    private void syncChatSession(Minecraft minecraft) {
        Object connection = minecraft == null ? null : minecraft.getConnection();
        Object world = minecraft == null ? null : minecraft.world;
        if (connection == chatConnection && world == chatWorld) return;
        clearPendingChatState();
        chatConnection = connection;
        chatWorld = world;
        chatSessionEpoch = nextEpoch(chatSessionEpoch);
        chatRequestProfile = null;
        TRANSLATOR.cancelPending();
    }

    private void syncChatRequestProfile(Minecraft minecraft) {
        String target = config == null ? "" : currentTarget();
        LegacyChatRequestProfile current = LegacyChatRequestProfile.capture(config, target);
        if (chatRequestProfile == null) {
            chatRequestProfile = current;
            return;
        }
        if (chatRequestProfile.equals(current)) return;
        chatRequestProfile = current;
        chatSessionEpoch = nextEpoch(chatSessionEpoch);
        if (minecraft != null && minecraft.ingameGUI != null) flushPendingChatOriginals(minecraft);
        else clearPendingChatState();
        TRANSLATOR.cancelPending();
    }

    private void clearPendingChatState() {
        pendingChats.clear();
        pendingChatById.clear();
    }

    private long allocateChatId() {
        long candidate = nextChatId;
        do {
            nextChatId = candidate == Long.MAX_VALUE ? 1L : candidate + 1L;
            if (!pendingChatById.containsKey(candidate)) return candidate;
            candidate = nextChatId;
        } while (true);
    }

    private static long nextEpoch(long epoch) {
        return epoch == Long.MAX_VALUE ? 1L : epoch + 1L;
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
        if (translated == null) TRANSLATOR.prefetch(source, currentTarget(), config.aiEnabled, false, config);
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
        if (minecraft == null || minecraft.player == null || config == null || !config.enabled) {
            clearItemWarmState();
            return;
        }
        final String target = currentTarget();
        LegacyChatRequestProfile profile = LegacyChatRequestProfile.capture(config, target);
        Object containerScreen = minecraft.currentScreen instanceof
                net.minecraft.client.gui.inventory.GuiContainer ? minecraft.currentScreen : null;
        if (containerScreen != warmedContainerScreen || !profile.equals(itemWarmProfile)) {
            warmedContainerScreen = containerScreen;
            itemWarmProfile = profile;
            warmedItemNames.clear();
            nextItemWarmScanAtNanos = 0L;
        }
        long now = System.nanoTime();
        if (now < nextItemWarmScanAtNanos) return;
        nextItemWarmScanAtNanos = now + ITEM_WARM_SCAN_INTERVAL_NANOS;

        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        for (int slot = 0; slot < 9; slot++) addWarmName(names, minecraft.player.inventory.getStackInSlot(slot));
        addWarmName(names, minecraft.player.getHeldItemOffhand());
        if (containerScreen != null) {
            net.minecraft.client.gui.inventory.GuiContainer screen =
                    (net.minecraft.client.gui.inventory.GuiContainer) containerScreen;
            for (net.minecraft.inventory.Slot slot : screen.inventorySlots.inventorySlots)
                if (slot != null && slot.getHasStack()) addWarmName(names, slot.getStack());
        }
        for (String name : names) {
            if (!warmedItemNames.contains(name)
                    && TRANSLATOR.cached(name, target, config.aiEnabled, config) == null) {
                TRANSLATOR.prefetch(name, target, config.aiEnabled, false, config);
            }
        }
        warmedItemNames.clear();
        warmedItemNames.addAll(names);
    }

    private void clearItemWarmState() {
        warmedItemNames.clear();
        warmedContainerScreen = null;
        itemWarmProfile = null;
        nextItemWarmScanAtNanos = 0L;
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
            if (translated == null) TRANSLATOR.prefetch(source, target, config.aiEnabled, highPriority, config);
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
        return text != null && text.trim().length() >= 2
                && LegacyTemplateText.prepare(text).hasTranslatableContent();
    }
}
