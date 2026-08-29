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
import java.util.concurrent.TimeUnit;

public final class LegacyTranslatorMod implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_PENDING_CHATS = 512;
    private static final long CHAT_TRANSLATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long ITEM_WARM_SCAN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(350L);
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
    private static LegacyCodexClient codexClient;
    private KeyMapping settingsKey;
    private final LegacyChatDeliveryQueue<PendingChat> pendingChats =
            new LegacyChatDeliveryQueue<PendingChat>();
    private Object chatConnection;
    private Object chatWorld;
    private long chatEpoch;
    private LegacyChatRequestProfile chatRequestProfile;
    private final java.util.Set<String> warmedItemNames = new java.util.HashSet<String>();
    private Object warmedContainerScreen;
    private LegacyChatRequestProfile itemWarmProfile;
    private long nextItemWarmScanAtNanos;

    private static final class PendingChat {
        final long epoch;
        final Object connection;
        final Object world;
        final LegacyChatRequestProfile requestProfile;
        final Component original;
        final String source;
        final boolean showOriginal;
        final long queuedAtNanos = System.nanoTime();
        String translated;
        boolean displayed;

        PendingChat(long epoch, Object connection, Object world,
                    LegacyChatRequestProfile requestProfile, Component original,
                    String source, boolean showOriginal) {
            this.epoch = epoch;
            this.connection = connection;
            this.world = world;
            this.requestProfile = requestProfile;
            this.original = original;
            this.source = source;
            this.showOriginal = showOriginal;
        }
    }

    @Override public void onInitializeClient() {
        instance = this;
        configPath = FabricLoader.getInstance().getConfigDir().resolve("mctranslator-legacy.json");
        config = loadConfig();
        Path configDir = configPath.getParent();
        codexClient = new LegacyCodexClient(
                configDir.resolve("mctranslator-codex-home"),
                configDir.resolve("mctranslator-codex-workspace"));
        TRANSLATOR.setCodexClient(codexClient);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { if (codexClient != null) codexClient.close(); }
        }, "mctranslator-codex-shutdown"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mctranslator.mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "category.mctranslator"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            syncLanguage(client);
            instance.syncChatSession(client);
            instance.syncChatRequestProfile(client);
            if (config != null && config.enabled) TRANSLATOR.flushBatch();
            else TRANSLATOR.cancelPending();
            instance.flushPendingChats(client);
            warmVisibleItemNames(client);
            while (settingsKey.consumeClick()) client.setScreen(new LegacySettingsScreen(client.screen));
        });
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> translateTooltip(stack, lines));
    }

    public static boolean interceptChat(final Component message) {
        if (instance == null || INTERNAL_CHAT.get()) return false;
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            instance.pendingChats.clear();
            return false;
        }
        instance.syncChatSession(minecraft);
        instance.syncChatRequestProfile(minecraft);
        if (config == null || minecraft.gui == null || message == null) {
            instance.pendingChats.clear();
            return false;
        }
        if (!config.enabled) {
            instance.flushAllOriginals(minecraft);
            return false;
        }
        final String source = message.getString();
        final boolean shouldTranslate = shouldTranslate(source);
        if (!shouldTranslate && instance.pendingChats.isEmpty()) return false;
        final PendingChat chat = new PendingChat(instance.chatEpoch, instance.chatConnection,
                instance.chatWorld, instance.chatRequestProfile, message, source,
                config.showOriginal);
        instance.enqueueChat(minecraft, chat);
        if (!shouldTranslate) {
            instance.pendingChats.markReady(chat);
            instance.drainReadyChats(minecraft);
            return true;
        }
        final String target = currentTarget(minecraft);
        TRANSLATOR.translate(source, target, config.aiEnabled, false, config,
                translated -> minecraft.execute(() -> instance.completeChat(minecraft, chat, translated)));
        return true;
    }

    private void enqueueChat(Minecraft minecraft, PendingChat chat) {
        while (pendingChats.size() >= MAX_PENDING_CHATS) {
            PendingChat evicted = pendingChats.removeFirst();
            displayOriginal(minecraft, evicted);
        }
        pendingChats.addLast(chat);
    }

    private void completeChat(Minecraft minecraft, PendingChat chat, String translated) {
        syncChatSession(minecraft);
        syncChatRequestProfile(minecraft);
        if (chat.epoch != chatEpoch || chat.connection != chatConnection || chat.world != chatWorld) return;
        if (!chat.requestProfile.equals(chatRequestProfile)) return;
        if (config == null || minecraft == null || minecraft.gui == null) {
            pendingChats.clear();
            return;
        }
        if (!config.enabled) {
            flushAllOriginals(minecraft);
            return;
        }
        chat.translated = translated;
        if (chat.displayed) return;
        if (!pendingChats.contains(chat)) return;
        pendingChats.markReady(chat);
        drainReadyChats(minecraft);
    }

    private void flushPendingChats(Minecraft minecraft) {
        if (minecraft == null) {
            pendingChats.clear();
            return;
        }
        syncChatSession(minecraft);
        syncChatRequestProfile(minecraft);
        if (config == null || minecraft.gui == null) {
            pendingChats.clear();
            return;
        }
        if (!config.enabled) {
            flushAllOriginals(minecraft);
            return;
        }
        drainReadyChats(minecraft);
        long now = System.nanoTime();
        PendingChat oldest = pendingChats.peekFirst();
        while (oldest != null
                && now - oldest.queuedAtNanos >= CHAT_TRANSLATION_TIMEOUT_NANOS) {
            pendingChats.removeFirst();
            displayOriginal(minecraft, oldest);
            drainReadyChats(minecraft);
            oldest = pendingChats.peekFirst();
        }
    }

    private void syncChatSession(Minecraft minecraft) {
        Object connection = minecraft == null ? null : minecraft.getConnection();
        Object world = minecraft == null ? null : minecraft.level;
        if (connection == chatConnection && world == chatWorld) return;
        pendingChats.clear();
        chatConnection = connection;
        chatWorld = world;
        chatEpoch++;
        TRANSLATOR.cancelPending();
    }

    private void syncChatRequestProfile(Minecraft minecraft) {
        String target = config == null ? "" : currentTarget(minecraft);
        LegacyChatRequestProfile current = LegacyChatRequestProfile.capture(config, target);
        if (chatRequestProfile == null) {
            chatRequestProfile = current;
            return;
        }
        if (chatRequestProfile.equals(current)) return;
        chatRequestProfile = current;
        chatEpoch++;
        if (minecraft != null && minecraft.gui != null) flushAllOriginals(minecraft);
        else pendingChats.clear();
        TRANSLATOR.cancelPending();
    }

    private void drainReadyChats(Minecraft minecraft) {
        boolean ordered = config == null || config.deliverChatTranslationsInOrder;
        for (PendingChat chat : pendingChats.drainReady(ordered)) {
            chat.displayed = true;
            addInternal(minecraft, output(chat));
        }
    }

    private void flushAllOriginals(Minecraft minecraft) {
        while (!pendingChats.isEmpty()) {
            PendingChat chat = pendingChats.removeFirst();
            displayOriginal(minecraft, chat);
        }
    }

    private static void displayOriginal(Minecraft minecraft, PendingChat chat) {
        chat.displayed = true;
        addInternal(minecraft, chat.original);
    }

    private static Component output(PendingChat chat) {
        if (chat.translated == null || chat.translated.equals(chat.source)) return chat.original;
        Component translated = new TextComponent(chat.translated).setStyle(chat.original.getStyle());
        return chat.showOriginal
                ? chat.original.copy().append(new TextComponent("\n")).append(translated)
                : translated;
    }

    private void warmVisibleItemNames(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || config == null || !config.enabled) {
            clearItemWarmState();
            return;
        }
        final String target = currentTarget(minecraft);
        LegacyChatRequestProfile profile = LegacyChatRequestProfile.capture(config, target);
        Object containerScreen = minecraft.screen instanceof
                net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                ? minecraft.screen : null;
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
        for (int slot = 0; slot < 9; slot++) addWarmName(names, minecraft.player.inventory.getItem(slot));
        addWarmName(names, minecraft.player.getOffhandItem());
        if (containerScreen != null) {
            net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen =
                    (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) containerScreen;
            for (net.minecraft.world.inventory.Slot slot : screen.getMenu().slots)
                if (slot != null && slot.isActive() && slot.hasItem()) addWarmName(names, slot.getItem());
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

    private static void addWarmName(java.util.Set<String> names, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String name = stack.getHoverName().getString();
        if (shouldTranslate(name)) names.add(name);
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
                TRANSLATOR.prefetch(source, target, config.aiEnabled, true, config);
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
    static LegacyCodexClient codexClient() { return codexClient; }
    static LegacySessionTokenUsage.Snapshot tokenUsageSnapshot() {
        return TRANSLATOR.tokenUsageSnapshot();
    }
    public static String tokenUsageLine() {
        LegacySessionTokenUsage.Snapshot tokens = tokenUsageSnapshot();
        return "TOKENS total " + tokens.totalTokens()
                + " | in " + tokens.inputTokens() + " (cached " + tokens.cachedInputTokens() + ")"
                + " | out " + tokens.outputTokens() + " (reason " + tokens.reasoningOutputTokens() + ")"
                + " | req " + tokens.requests();
    }
    static void testAi(final java.util.function.Consumer<String> callback) {
        final Minecraft client = Minecraft.getInstance();
        TRANSLATOR.testAi(currentTarget(client), config, new java.util.function.Consumer<String>() {
            @Override public void accept(final String result) {
                if (client != null) client.execute(new Runnable() {
                    @Override public void run() { callback.accept(result); }
                });
                else callback.accept(result);
            }
        });
    }
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
            TRANSLATOR.prefetch(plain, target, config.aiEnabled, false, config);
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
            TRANSLATOR.prefetch(source, target, config.aiEnabled, false, config);
            return source;
        }
        return translated;
    }

    public static String nameTag(net.minecraft.world.entity.Entity entity, String source) {
        if (source == null || config == null || !config.enabled) return source;
        if (entity instanceof net.minecraft.world.entity.player.Player || nameTagMatchesListedPlayer(source))
            return source;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || !shouldTranslate(source)) return source;
        String target = currentTarget(minecraft);
        String translated = TRANSLATOR.cached(source, target, config.aiEnabled, config);
        if (translated == null) {
            TRANSLATOR.prefetch(source, target, config.aiEnabled, false, config);
            return source;
        }
        return translated;
    }

    private static boolean nameTagMatchesListedPlayer(String plain) {
        if (plain == null || plain.isEmpty()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientPacketListener connection = minecraft == null
                ? null : minecraft.getConnection();
        if (connection == null) return false;
        for (net.minecraft.client.multiplayer.PlayerInfo info : connection.getOnlinePlayers()) {
            String name = info == null || info.getProfile() == null ? null : info.getProfile().getName();
            if (name == null || name.isEmpty()) continue;
            int at = plain.indexOf(name);
            while (at >= 0) {
                boolean left = at == 0 || !isNameTokenChar(plain.charAt(at - 1));
                int end = at + name.length();
                boolean right = end >= plain.length() || !isNameTokenChar(plain.charAt(end));
                if (left && right) return true;
                at = plain.indexOf(name, at + 1);
            }
        }
        return false;
    }

    private static boolean isNameTokenChar(char ch) {
        return ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z'
                || ch >= '0' && ch <= '9' || ch == '_';
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
