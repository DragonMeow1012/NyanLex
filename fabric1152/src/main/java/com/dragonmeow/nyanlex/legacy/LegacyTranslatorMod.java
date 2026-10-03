package com.dragonmeow.nyanlex.legacy;

import com.dragonmeow.nyanlex.translate.HookGuard;
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
import net.minecraft.network.chat.TranslatableComponent;
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
    /** AI engine only (see {@link #warmVisibleItemNames}); machine engine never auto-scans. */
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
    private static KeyMapping screenScanKey;
    private static KeyMapping itemRetranslateKey;
    private boolean keybindMigrationChecked;
    private boolean firstRunChecked;
    private static final com.dragonmeow.nyanlex.translate.ScreenTranslationCapture SCREEN_CAPTURE =
            new com.dragonmeow.nyanlex.translate.ScreenTranslationCapture();
    private final LegacyChatDeliveryQueue<PendingChat> pendingChats =
            new LegacyChatDeliveryQueue<PendingChat>();
    private Object chatConnection;
    private Object chatWorld;
    private long chatEpoch;
    private LegacyChatRequestProfile chatRequestProfile;
    // 1.0.7 UI round 4: the manual "re-translate" key (R) and the tooltip hint/translating row
    // only apply to the machine-translation engine. AI engine restores the pre-round-3 fully
    // automatic behavior below (item warm scan, tooltip/UI auto-prefetch) with no hint shown.
    private java.util.List<String> pointedTooltipSources = java.util.Collections.emptyList();
    private String pointedTooltipTarget;
    private final java.util.Set<String> pointedTooltipAwaiting = new java.util.HashSet<String>();
    private boolean pointedTooltipTranslating;
    // Item warm scan (AI engine only): periodically prefetch hotbar/offhand/open-container
    // item names so tooltips are already cached by the time the player hovers them.
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
        NyanLexHooks.register(null, null);
        instance = this;
        configPath = FabricLoader.getInstance().getConfigDir().resolve("nyanlex-legacy.json");
        Path configDir = configPath.getParent();
        LegacyDataMigration.migrate(configDir, message -> System.out.println("[NyanLex] " + message));
        config = loadConfig();
        codexClient = new LegacyCodexClient(
                configDir.resolve("nyanlex-codex-home"),
                configDir.resolve("nyanlex-codex-workspace"));
        TRANSLATOR.setCodexClient(codexClient);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { if (codexClient != null) codexClient.close(); }
        }, "nyanlex-codex-shutdown"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "category.nyanlex"));
        screenScanKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.screenscan", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P,
                "category.nyanlex"));
        itemRetranslateKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.nyanlex.retranslate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R,
                "category.nyanlex"));
        TRANSLATOR.loadSharedTranslations(configDir, currentTarget(Minecraft.getInstance()), config);
        ClientTickEvents.END_CLIENT_TICK.register(client -> HookGuard.run("event.clientTick", () -> {
            instance.maybeMigrateKeybinds(client);
            instance.maybeShowFirstRun(client);
            syncLanguage(client);
            SCREEN_CAPTURE.cancelUnless(client.screen);
            instance.syncChatSession(client);
            instance.syncChatRequestProfile(client);
            if (config != null && config.enabled) TRANSLATOR.flushBatch();
            else TRANSLATOR.cancelPending();
            instance.flushPendingChats(client);
            instance.warmVisibleItemNames(client);
            while (settingsKey.consumeClick()) {
                // Only pass-events screens can let a click through; never act while the player types.
                if (!LegacyTextInput.focused(client.screen)) client.setScreen(new LegacySettingsScreen(client.screen));
            }
            while (itemRetranslateKey.consumeClick()) {
                if (!LegacyTextInput.focused(client.screen)) instance.handleRetranslateItemKey(client);
            }
        }));
        ItemTooltipCallback.EVENT.register((stack, context, lines) ->
                HookGuard.run("event.itemTooltip", () -> translateTooltip(stack, lines)));
    }

    /** First launch only: the quick setup opens once over the title screen. */
    private void maybeShowFirstRun(Minecraft mc) {
        if (firstRunChecked || mc == null || config == null) return;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        firstRunChecked = true;
        if (config.firstRunDone) return;
        if (config.translationRequestsEnabled) {
            config.firstRunDone = true;
            saveConfig();
            return;
        }
        mc.setScreen(new LegacySetupScreen(mc.screen, false));
    }

    /** Once per launch, the first time the title screen appears: carry any saved
     *  pre-rename ("mctranslator") keybinding over to its "nyanlex" equivalent,
     *  when the player has not already set (or had migrated) the new one. */
    private void maybeMigrateKeybinds(Minecraft mc) {
        if (keybindMigrationChecked || mc == null || mc.screen == null) return;
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) return;
        keybindMigrationChecked = true;
        java.util.Map<String, KeyMapping> bySuffix = new java.util.LinkedHashMap<String, KeyMapping>();
        if (settingsKey != null) bySuffix.put("mode", settingsKey);
        if (itemRetranslateKey != null) bySuffix.put("retranslate", itemRetranslateKey);
        if (screenScanKey != null) bySuffix.put("screenscan", screenScanKey);
        if (bySuffix.isEmpty()) return;
        Path optionsTxt = FabricLoader.getInstance().getGameDir().resolve("options.txt");
        java.util.Map<String, String> legacy =
                KeybindMigration.findUnmigratedBindings(optionsTxt, bySuffix.keySet());
        if (legacy.isEmpty()) return;
        boolean changed = false;
        for (java.util.Map.Entry<String, String> e : legacy.entrySet()) {
            KeyMapping mapping = bySuffix.get(e.getKey());
            if (mapping == null) continue;
            try {
                mapping.setKey(InputConstants.getKey(e.getValue()));
                changed = true;
            } catch (RuntimeException ignored) {
                // Malformed/unknown stored key name: leave the default binding alone.
            }
        }
        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
        }
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
        boolean keepInFlight = chatRequestProfile.differsOnlyInRequestSwitch(current);
        chatRequestProfile = current;
        chatEpoch++;
        if (minecraft != null && minecraft.gui != null) flushAllOriginals(minecraft);
        else pendingChats.clear();
        // Request-switch-only toggle: keep on-the-wire requests (results still cached);
        // queued work is dropped by the translator itself while the switch is off.
        if (!keepInFlight) TRANSLATOR.cancelPending();
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

    /**
     * 1.0.7 UI round 4: pointed-item tooltips are cache-only lookups always, but the active
     * engine decides whether missing lines are sent automatically. AI engine: every frame,
     * same as before round 3 (plus item warm scan, see {@link #warmVisibleItemNames}), no
     * hint row. Machine engine: only the re-translate key ({@link #itemRetranslateKey},
     * default R) sends anything, with a hint/status row — unchanged from round 3 (free-tier
     * rate limits). Chat ({@link #interceptChat}) and entity name tags ({@link #nameTag}) are
     * always automatic regardless of engine.
     */
    private static void translateTooltip(ItemStack stack, List<Component> lines) {
        Minecraft minecraft = Minecraft.getInstance();
        if (config == null || !config.enabled || stack == null || stack.isEmpty()
                || lines == null || minecraft == null || minecraft.level == null
                || !minecraft.isSameThread() || !renderingCurrentScreen(minecraft)) {
            if (instance != null) instance.clearPointedTooltip();
            return;
        }
        String target = currentTarget(minecraft);
        boolean ai = config.aiEnabled;
        java.util.List<String> sources = new java.util.ArrayList<String>();
        boolean missing = false;
        for (int i = 0; i < lines.size(); i++) {
            Component line = lines.get(i);
            String source = line.getString();
            if (!shouldTranslate(source)) continue;
            sources.add(source);
            String translated = TRANSLATOR.cached(source, target, ai, config);
            if (translated == null) {
                missing = true;
                if (ai) TRANSLATOR.prefetch(source, target, true, true, config);
            } else if (!translated.equals(source)) {
                lines.set(i, new TextComponent(translated).setStyle(line.getStyle()));
            }
        }
        instance.updatePointedTooltip(sources, target);
        if (!config.translationRequestsEnabled) {
            // Online translation is off: the only useful thing a key press can do is ask to start it.
            if (missing && itemRetranslateKey != null) {
                lines.add(new TextComponent("§7" + new TranslatableComponent(
                        "screen.nyanlex.tooltip.start_hint",
                        itemRetranslateKey.getTranslatedKeyMessage()).getString()));
            }
            return;
        }
        if (ai) return;
        // Hint/status row (machine engine only): never itself a translation candidate.
        if (instance.pointedTooltipTranslating) {
            lines.add(new TextComponent("§7" + new TranslatableComponent(
                    "screen.nyanlex.tooltip.translating").getString()));
        } else if (missing && itemRetranslateKey != null) {
            lines.add(new TextComponent("§7" + new TranslatableComponent(
                    "screen.nyanlex.tooltip.hint",
                    itemRetranslateKey.getTranslatedKeyMessage()).getString()));
        }
    }

    /** Tracks the tooltip currently under the mouse so the R key knows what to retranslate. */
    private void updatePointedTooltip(java.util.List<String> sources, String target) {
        if (!sources.equals(pointedTooltipSources) || !target.equals(pointedTooltipTarget)) {
            pointedTooltipSources = sources;
            pointedTooltipTarget = target;
            pointedTooltipAwaiting.clear();
            pointedTooltipTranslating = false;
            return;
        }
        if (pointedTooltipTranslating) {
            LegacyConfig cfg = config;
            java.util.Iterator<String> it = pointedTooltipAwaiting.iterator();
            while (it.hasNext()) {
                if (TRANSLATOR.cached(it.next(), target, cfg.aiEnabled, cfg) != null) it.remove();
            }
            if (pointedTooltipAwaiting.isEmpty()) pointedTooltipTranslating = false;
        }
    }

    private void clearPointedTooltip() {
        if (pointedTooltipSources.isEmpty() && !pointedTooltipTranslating) return;
        pointedTooltipSources = java.util.Collections.emptyList();
        pointedTooltipTarget = null;
        pointedTooltipAwaiting.clear();
        pointedTooltipTranslating = false;
    }

    /**
     * R key: while hovering an item (tooltip visible), retranslate that tooltip. Otherwise
     * (no screen / nothing hovered), retranslate the held main-hand item's name so the HUD
     * "selected item" popup has a cached translation next time it shows. AI engine: always a
     * forced full retranslate (invalidate + resend — it already auto-translates, so the key's
     * only job is "I don't like this result, try again"). Machine engine: missing lines only,
     * or a forced full retranslate if every line is already cached (unchanged from round 3).
     */
    private void handleRetranslateItemKey(final Minecraft client) {
        if (config == null || !config.enabled) return;
        if (!config.translationRequestsEnabled) {
            boolean pointed = !pointedTooltipSources.isEmpty() && pointedTooltipTarget != null;
            if (!pointed && !(client.screen == null && client.player != null)) return;
            final java.util.List<String> sources = new java.util.ArrayList<String>(pointedTooltipSources);
            final String target = pointedTooltipTarget;
            LegacyConsentScreen.open(client, false, new Runnable() {
                @Override public void run() {
                    pointedTooltipSources = sources;
                    pointedTooltipTarget = target;
                    handleRetranslateItemKey(client);
                }
            });
            return;
        }
        if (!pointedTooltipSources.isEmpty() && pointedTooltipTarget != null) {
            retranslatePointedTooltip();
            return;
        }
        if (client.screen == null && client.player != null) retranslateHeldItem(client);
    }

    private void retranslatePointedTooltip() {
        LegacyConfig cfg = config;
        String target = pointedTooltipTarget;
        java.util.List<String> sources = new java.util.ArrayList<String>(pointedTooltipSources);
        if (sources.isEmpty()) return;
        if (cfg.aiEnabled) {
            TRANSLATOR.retranslateScreen(sources, target, cfg);
            return;
        }
        boolean anyMissing = false;
        for (String source : sources) {
            if (TRANSLATOR.cached(source, target, cfg.aiEnabled, cfg) == null) { anyMissing = true; break; }
        }
        pointedTooltipAwaiting.clear();
        pointedTooltipAwaiting.addAll(sources);
        pointedTooltipTranslating = true;
        if (anyMissing) {
            for (String source : sources) TRANSLATOR.prefetch(source, target, cfg.aiEnabled, true, cfg);
        } else {
            TRANSLATOR.retranslateScreen(sources, target, cfg);
        }
    }

    private void retranslateHeldItem(Minecraft client) {
        ItemStack stack = client.player.getMainHandItem();
        if (stack == null || stack.isEmpty()) return;
        String name = stack.getHoverName().getString();
        if (!shouldTranslate(name)) return;
        LegacyConfig cfg = config;
        String target = currentTarget(client);
        java.util.List<String> sources = java.util.Collections.singletonList(name);
        if (cfg.aiEnabled) {
            TRANSLATOR.retranslateScreen(sources, target, cfg);
            return;
        }
        if (TRANSLATOR.cached(name, target, cfg.aiEnabled, cfg) == null) {
            TRANSLATOR.prefetch(name, target, cfg.aiEnabled, true, cfg);
        } else {
            TRANSLATOR.retranslateScreen(sources, target, cfg);
        }
    }

    /**
     * AI engine only: periodically prefetch hotbar/offhand/open-container item names so
     * tooltips are already cached when hovered (restored pre-1.0.7-round-3 behavior). The
     * machine engine never scans — {@link #clearItemWarmState} keeps it fully manual.
     */
    private void warmVisibleItemNames(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || config == null
                || !config.enabled || !config.aiEnabled) {
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
            // Machine engine: only chat translates on its own; a hotbar/container name is shown from
            // the cache and bought by the item key (R) or the screen key (P), never by merely being seen.
            if (config.aiEnabled && !warmedItemNames.contains(name)
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

    private static void addInternal(Minecraft minecraft, Component component) {
        boolean previous = INTERNAL_CHAT.get();
        INTERNAL_CHAT.set(Boolean.TRUE);
        try { minecraft.gui.getChat().addMessage(component); }
        finally { INTERNAL_CHAT.set(previous); }
    }

    static final int KEY_ITEM = 0;
    static final int KEY_SCREEN = 1;
    static final int KEY_SETTINGS = 2;

    /** Name of a hotkey as the player has it bound (for the quick setup and hints). */
    static String keyLabel(int which) {
        KeyMapping key = which == KEY_ITEM ? itemRetranslateKey
                : which == KEY_SCREEN ? screenScanKey
                : instance == null ? null : instance.settingsKey;
        if (key == null) return "?";
        String name = key.getTranslatedKeyMessage();
        return name.length() == 1 ? name.toUpperCase(java.util.Locale.ROOT) : name;
    }

    static String version() {
        try {
            return FabricLoader.getInstance().getModContainer("nyanlex").get()
                    .getMetadata().getVersion().getFriendlyString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Opens a web link after the player confirmed it. */
    static void openLink(final Screen parent, final String url) {
        final Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new net.minecraft.client.gui.screens.ConfirmLinkScreen(yes -> {
            if (yes) net.minecraft.Util.getPlatform().openUri(url);
            mc.setScreen(parent);
        }, url, true));
    }

    /** The "Translation settings..." button on the Options screen. */
    public static void openSettings(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && config != null) mc.setScreen(new LegacySettingsScreen(parent));
    }

    public static void translateDraft(String text, String target, java.util.function.BiConsumer<String, String> callback) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) for (net.minecraft.client.multiplayer.PlayerInfo info : connection.getOnlinePlayers()) {
            if (info != null && info.getProfile() != null) names.add(info.getProfile().getName());
        }
        TRANSLATOR.translateDraft(text, target, config, names, callback);
    }

    public static LegacyConfig config() { return config; }
    static LegacyCodexClient codexClient() { return codexClient; }
    static LegacySessionTokenUsage.Snapshot tokenUsageSnapshot() {
        return TRANSLATOR.tokenUsageSnapshot();
    }
    public static String tokenUsageLine() {
        LegacySessionTokenUsage.Snapshot tokens = tokenUsageSnapshot();
        return "TOKENS total " + tokens.totalTokens()
                + " | in " + tokens.inputTokens() + " (cached " + tokens.cachedInputTokens() + ")"
                + " | out " + tokens.outputTokens() + " (reason " + tokens.reasoningOutputTokens() + ")"
                + " | req " + tokens.requests() + " | " + com.dragonmeow.nyanlex.translate.HookHealth.shortSummary();
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
    /**
     * Generic UI/HUD text (menus, scoreboard, boss bar, held-item HUD popup, etc. — legacy has
     * no per-surface split, this single hook covers all of it besides chat and name tags). AI
     * engine: auto-prefetch on a cache miss, same as before round 3. Machine engine: cache-only
     * (round 3, unchanged) — a miss just shows the original; the player gets it translated via
     * the "retranslate current screen" key (P) or, for a hovered/held item, the item
     * retranslate key (R).
     */
    public static Component translateVisible(Component source) {
        if (source != null && captureScreenText(source.getString())) return source;
        if (source == null || config == null || !config.enabled || INTERNAL_RENDER.get()) return source;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || minecraft.screen != null && !screenTranslationAllowed(minecraft.screen)) return source;
        String plain = source.getString();
        if (!shouldTranslate(plain)) return source;
        String target = currentTarget(minecraft);
        boolean ai = config.aiEnabled;
        String translated = TRANSLATOR.cached(plain, target, ai, config);
        if (translated == null) {
            if (ai) TRANSLATOR.prefetch(plain, target, true, false, config);
            return source;
        }
        return translated.equals(plain) ? source : new TextComponent(translated).setStyle(source.getStyle());
    }
    public static String translateVisibleString(String source) {
        if (source != null && captureScreenText(source)) return source;
        if (source == null || config == null || !config.enabled || INTERNAL_RENDER.get()) return source;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || minecraft.screen != null && !screenTranslationAllowed(minecraft.screen)
                || !shouldTranslate(source)) return source;
        String target = currentTarget(minecraft);
        boolean ai = config.aiEnabled;
        String translated = TRANSLATOR.cached(source, target, ai, config);
        if (translated == null) {
            if (ai) TRANSLATOR.prefetch(source, target, true, false, config);
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
    public static boolean handleScreenKey(int key, int scanCode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null || config == null || !config.enabled
                || screenScanKey == null || !screenScanKey.matches(key, scanCode)
                || LegacyTextInput.focused(mc.screen)
                || !screenTranslationAllowed(mc.screen)) return false;
        if (!config.translationRequestsEnabled) {
            LegacyConsentScreen.open(mc, true, new Runnable() {
                @Override public void run() { beginScreenCapture(Minecraft.getInstance()); }
            });
            return true;
        }
        beginScreenCapture(mc);
        return true;
    }

    private static void beginScreenCapture(Minecraft mc) {
        if (mc == null || mc.screen == null || !screenTranslationAllowed(mc.screen)) return;
        SCREEN_CAPTURE.begin(mc.screen);
        SCREEN_CAPTURE.record(mc.screen, mc.screen.getTitle().getString());
    }

    private static boolean captureScreenText(String source) {
        Minecraft mc = Minecraft.getInstance();
        if (INTERNAL_RENDER.get() || mc == null || !SCREEN_CAPTURE.active(mc.screen)
                || !renderingCurrentScreen(mc)) return false;
        SCREEN_CAPTURE.record(mc.screen, source);
        return true;
    }

    /** Result messages of the mod: the action bar in game, a toast on menu screens - never the chat. */
    static void notifyUser(final String message) {
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(new Runnable() {
            @Override public void run() {
                if (mc.level != null && mc.screen == null && mc.gui != null) {
                    mc.gui.setOverlayMessage(new TextComponent(message), false);
                } else {
                    net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToasts(),
                            net.minecraft.client.gui.components.toasts.SystemToast.SystemToastIds.WORLD_BACKUP,
                            new TextComponent("NyanLex Translator"), new TextComponent(message));
                }
            }
        });
    }

    static void translationFile(boolean importing) {
        Minecraft mc = Minecraft.getInstance();
        final String target = currentTarget(mc);
        final LegacyConfig snapshot = config.snapshotForRequest();
        com.dragonmeow.nyanlex.translate.TranslationFileDialog.open(importing,
                () -> TRANSLATOR.exportTranslations(target, snapshot),
                file -> TRANSLATOR.importTranslations(file, target, snapshot),
                message -> notifyUser(message));
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
        if (!stack.isEmpty()) {
            Screen screen = stack.pop();
            java.util.List<String> sources = SCREEN_CAPTURE.finish(screen);
            if (sources != null) TRANSLATOR.retranslateScreen(sources, currentTarget(Minecraft.getInstance()), config);
        }
        if (stack.isEmpty()) SCREEN_RENDER_STACK.remove();
    }
    private static boolean renderingCurrentScreen(Minecraft minecraft) {
        Screen screen = minecraft.screen;
        if (!screenTranslationAllowed(screen)) return false;
        java.util.ArrayDeque<Screen> stack = SCREEN_RENDER_STACK.get();
        return !stack.isEmpty() && stack.peek() == screen;
    }
    private static boolean screenTranslationAllowed(Screen screen) {
        if (screen == null || screen instanceof net.minecraft.client.gui.screens.ChatScreen
                || screen.getClass().getName().startsWith("com.dragonmeow.nyanlex.")) return false;
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
    public static void saveConfig() { saveConfig(config); }
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
                boolean hadRequests = false;
                boolean hadFirstRun = false;
                try {
                    com.google.gson.JsonElement tree = new com.google.gson.JsonParser().parse(reader);
                    if (tree != null && tree.isJsonObject()) {
                        hadRequests = tree.getAsJsonObject().has("translationRequestsEnabled");
                        hadFirstRun = tree.getAsJsonObject().has("firstRunDone");
                    }
                    loaded = GSON.fromJson(tree, LegacyConfig.class);
                } finally { reader.close(); }
                loaded = LegacyConfig.applyUpgradeDefaults(LegacyConfig.normalizeLoaded(loaded),
                        hadRequests, hadFirstRun);
                if (loaded != null) {
                    saveConfig(loaded);
                    return loaded;
                }
            } catch (Exception ignored) {}
        }
        LegacyConfig created = new LegacyConfig();
        if (!Files.isRegularFile(configPath)) saveConfig(created);
        return created;
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
