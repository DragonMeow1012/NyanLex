package com.dragonmeow.nyanlex.forgelegacy;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.I18n;
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
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Mod(modid = "nyanlex", name = "NyanLex Translator", version = "1.0.0", clientSideOnly = true)
public final class NyanLexForge {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static NyanLexForge instance;
    static final LegacyTranslator TRANSLATOR = new LegacyTranslator();
    private final File configFile = new File("config", "nyanlex-forge-legacy.json");
    private final KeyBinding settingsKey = new KeyBinding("key.nyanlex.mode", Keyboard.KEY_G, "category.nyanlex");
    private final KeyBinding toggleKey = new KeyBinding("key.nyanlex.toggle", Keyboard.KEY_H, "category.nyanlex");
    private final KeyBinding screenScanKey = new KeyBinding("key.nyanlex.screenscan", Keyboard.KEY_P, "category.nyanlex");
    private final KeyBinding itemRetranslateKey = new KeyBinding("key.nyanlex.retranslate", Keyboard.KEY_R, "category.nyanlex");
    private static final com.dragonmeow.nyanlex.translate.ScreenTranslationCapture SCREEN_CAPTURE =
            new com.dragonmeow.nyanlex.translate.ScreenTranslationCapture();
    private static net.minecraft.client.gui.GuiScreen renderingScreen, translatedScreen;
    private static java.util.Set<String> screenSources = java.util.Collections.emptySet();
    private boolean scanKeyDown;
    private boolean keybindMigrationChecked;
    private boolean firstRunChecked;
    private final Map<Integer, String> renderedNames = new ConcurrentHashMap<Integer, String>();
    private final LegacyChatDeliveryQueue<PendingChat> pendingChats = new LegacyChatDeliveryQueue<PendingChat>();
    private final Map<Long, PendingChat> pendingChatById = new LinkedHashMap<Long, PendingChat>();
    private LegacyConfig config;
    private LegacyCodexClient codexClient;
    private long nextChatId = 1L;
    private Object chatConnection;
    private Object chatWorld;
    private long chatSessionEpoch;
    private LegacyChatRequestProfile chatRequestProfile;
    // 1.0.7 UI round 4: the manual "re-translate" key (R) and the tooltip hint/translating row
    // only apply to the machine-translation engine. AI engine restores the pre-round-3 fully
    // automatic behavior below (item warm scan, tooltip/UI auto-prefetch) with no hint shown.
    // "Seen this tick" latch: set by onTooltipRender, checked/reset in onClientTick.
    private java.util.List<String> pointedTooltipSources = java.util.Collections.emptyList();
    private String pointedTooltipTarget;
    private final java.util.Set<String> pointedTooltipAwaiting = new java.util.HashSet<String>();
    private boolean pointedTooltipTranslating;
    private volatile boolean tooltipRenderedThisTick;
    // Item warm scan (AI engine only): periodically prefetch hotbar/offhand/open-container
    // item names so tooltips are already cached by the time the player hovers them.
    private final java.util.Set<String> warmedItemNames = new java.util.HashSet<String>();
    private Object warmedContainerScreen;
    private LegacyChatRequestProfile itemWarmProfile;
    private long nextItemWarmScanAtNanos;
    private static final long ITEM_WARM_SCAN_INTERVAL_NANOS = 350L * 1000L * 1000L;

    private static final int MAX_PENDING_CHATS = 512;
    private static final long CHAT_MAX_WAIT_NANOS = 15L * 1000L * 1000L * 1000L;

    private boolean beginScreenScan(net.minecraft.client.gui.GuiScreen screen) {
        // Typing (chat, sign, book and quill, focused text field, recipe search): let the key through.
        if (screen == null || !config.enabled || ForgeTextInput.focused(screen)
                || screen instanceof net.minecraft.client.gui.GuiControls
                || screen.getClass().getName().startsWith("com.dragonmeow.nyanlex.")) return false;
        if (!config.translationRequestsEnabled) {
            ForgeConsentScreen.open(Minecraft.getMinecraft(), true, new Runnable() {
                @Override public void run() { beginScreenScan0(Minecraft.getMinecraft().currentScreen); }
            });
            return true;
        }
        beginScreenScan0(screen);
        return true;
    }

    private void beginScreenScan0(net.minecraft.client.gui.GuiScreen screen) {
        if (screen == null || screen.getClass().getName().startsWith("com.dragonmeow.nyanlex.")) return;
        SCREEN_CAPTURE.begin(screen);
        translatedScreen = screen;
        screenSources = java.util.Collections.emptySet();
    }

    private static final int OPTIONS_BUTTON_ID = 9137;

    /** Adds the "Translation settings..." button to the vanilla Options screen. */
    @SubscribeEvent public void onInitGui(net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent.Post event) {
        if (!HookGuard.enter("event.onInitGui")) return;
        try {
            if (!(event.getGui() instanceof net.minecraft.client.gui.GuiOptions)) return;
            event.getButtonList().add(new net.minecraft.client.gui.GuiButton(OPTIONS_BUTTON_ID, 6, 6, 110, 20,
                    I18n.format("screen.nyanlex.options")));
        } catch (Throwable guardError) {
            HookGuard.fail("event.onInitGui", guardError);
        }
    }

    @SubscribeEvent public void onOptionsButton(net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (!HookGuard.enter("event.onOptionsButton")) return;
        try {
            if (event.getGui() instanceof net.minecraft.client.gui.GuiOptions
                    && event.getButton().id == OPTIONS_BUTTON_ID) {
                Minecraft.getMinecraft().displayGuiScreen(new ForgeSettingsScreen(event.getGui()));
            }
        } catch (Throwable guardError) {
            HookGuard.fail("event.onOptionsButton", guardError);
        }
    }

    @SubscribeEvent public void beforeScreen(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Pre event) {
        HookGuard.enterSticky("event.beforeScreen");
        try {
            renderingScreen = event.getGui();
        } catch (Throwable guardError) {
            HookGuard.fail("event.beforeScreen", guardError);
        }
    }

    @SubscribeEvent public void afterScreen(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Post event) {
        HookGuard.enterSticky("event.afterScreen");
        try {
            java.util.List<String> sources = SCREEN_CAPTURE.finish(event.getGui());
            renderingScreen = null;
            if (sources != null) {
                screenSources = new java.util.HashSet<String>(sources);
                TRANSLATOR.retranslateScreen(sources, currentTarget(), config);
            }
        } catch (Throwable guardError) {
            HookGuard.fail("event.afterScreen", guardError);
        }
    }

    /** Called by the loader-specific FontRenderer hook before wrapping/drawing. */
    public static String translateScreenString(String source) {
        if (!HookGuard.enter("hook.translateScreenString")) return source;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (instance == null || source == null || mc == null || renderingScreen == null
                    || renderingScreen != mc.currentScreen || !instance.config.enabled) return source;
            if (SCREEN_CAPTURE.active(renderingScreen)) {
                SCREEN_CAPTURE.record(renderingScreen, source);
                return source;
            }
            if (translatedScreen != renderingScreen || !screenSources.contains(source)) return source;
            String translated = TRANSLATOR.cached(source, currentTarget(), instance.config.aiEnabled, instance.config);
            return translated == null ? source : translated;
        } catch (Throwable guardError) {
            HookGuard.fail("hook.translateScreenString", guardError);
            return source;
        }
    }

    /** Result messages of the mod: the action bar in game, a toast on menu screens - never the chat. */
    static void notifyUser(final String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(new Runnable() {
            @Override public void run() {
                if (mc.world != null && mc.currentScreen == null && mc.ingameGUI != null) {
                    mc.ingameGUI.setOverlayMessage(new TextComponentString(message), false);
                } else {
                    net.minecraft.client.gui.toasts.SystemToast.addOrUpdate(mc.getToastGui(),
                            net.minecraft.client.gui.toasts.SystemToast.Type.NARRATOR_TOGGLE,
                            new TextComponentString("NyanLex Translator"), new TextComponentString(message));
                }
            }
        });
    }

    static void translationFile(boolean importing) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String target = currentTarget();
        final LegacyConfig snapshot = instance.config.snapshotForRequest();
        com.dragonmeow.nyanlex.translate.TranslationFileDialog.open(importing,
                () -> TRANSLATOR.exportTranslations(target, snapshot),
                file -> TRANSLATOR.importTranslations(file, target, snapshot),
                message -> notifyUser(message));
    }

    @SubscribeEvent public void screenKey(net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!HookGuard.enter("event.screenKey")) return;
        try {
            if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()
                    && Keyboard.getEventKey() == screenScanKey.getKeyCode() && beginScreenScan(event.getGui())) event.setCanceled(true);
        } catch (Throwable guardError) {
            HookGuard.fail("event.screenKey", guardError);
        }
    }

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

    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        NyanLexHooks.register(null, null);
        instance = this;
        Path configDir = configFile.getAbsoluteFile().getParentFile().toPath();
        LegacyDataMigration.migrate(configDir, message -> System.out.println("[NyanLex] " + message));
        config = loadConfig();
        codexClient = new LegacyCodexClient(configDir.resolve("nyanlex-codex-home"), configDir.resolve("nyanlex-codex-workspace"));
        TRANSLATOR.setCodexClient(codexClient);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { if (codexClient != null) codexClient.close(); }
        }, "nyanlex-codex-shutdown"));
        ClientRegistry.registerKeyBinding(settingsKey);
        ClientRegistry.registerKeyBinding(toggleKey);
        ClientRegistry.registerKeyBinding(screenScanKey);
        ClientRegistry.registerKeyBinding(itemRetranslateKey);
        TRANSLATOR.loadSharedTranslations(configDir, currentTarget(), config);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!HookGuard.enter("event.onClientTick")) return;
        try {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft minecraft = Minecraft.getMinecraft();
            maybeMigrateKeybinds(minecraft);
            maybeShowFirstRun(minecraft);
            SCREEN_CAPTURE.cancelUnless(minecraft.currentScreen);
            if (translatedScreen != minecraft.currentScreen) {
                translatedScreen = null;
                screenSources = java.util.Collections.emptySet();
                scanKeyDown = false;
            }
            if (!tooltipRenderedThisTick) clearPointedTooltip();
            tooltipRenderedThisTick = false;
            syncChatSession(minecraft);
            boolean toggled = false;
            while (toggleKey.isPressed()) {
                if (minecraft != null && ForgeTextInput.focused(minecraft.currentScreen)) continue;
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
                if (ForgeTextInput.focused(minecraft.currentScreen)) continue;
                minecraft.displayGuiScreen(new ForgeSettingsScreen(minecraft.currentScreen));
            }
            while (minecraft != null && itemRetranslateKey.isPressed()) {
                if (ForgeTextInput.focused(minecraft.currentScreen)) continue;
                handleRetranslateItemKey(minecraft);
            }
        } catch (Throwable guardError) {
            HookGuard.fail("event.onClientTick", guardError);
        }
    }

    /** First launch only: the quick setup opens once over the title screen. */
    private void maybeShowFirstRun(Minecraft minecraft) {
        if (firstRunChecked || minecraft == null || config == null) return;
        if (!(minecraft.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu)) return;
        firstRunChecked = true;
        if (config.firstRunDone) return;
        if (config.translationRequestsEnabled) {
            config.firstRunDone = true;
            saveConfig();
            return;
        }
        minecraft.displayGuiScreen(new ForgeSetupScreen(minecraft.currentScreen, false));
    }

    /** Once per launch, the first time the title screen appears: carry any saved
     *  pre-rename ("mctranslator") keybinding over to its "nyanlex" equivalent,
     *  when the player has not already set (or had migrated) the new one. */
    private void maybeMigrateKeybinds(Minecraft minecraft) {
        if (keybindMigrationChecked || minecraft == null || minecraft.currentScreen == null) return;
        if (!(minecraft.currentScreen instanceof net.minecraft.client.gui.GuiMainMenu)) return;
        keybindMigrationChecked = true;
        Map<String, KeyBinding> bySuffix = new LinkedHashMap<String, KeyBinding>();
        bySuffix.put("mode", settingsKey);
        bySuffix.put("toggle", toggleKey);
        bySuffix.put("screenscan", screenScanKey);
        bySuffix.put("retranslate", itemRetranslateKey);
        Path optionsTxt = minecraft.mcDataDir.toPath().resolve("options.txt");
        Map<String, String> legacy = KeybindMigration.findUnmigratedBindings(optionsTxt, bySuffix.keySet());
        if (legacy.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<String, String> e : legacy.entrySet()) {
            KeyBinding binding = bySuffix.get(e.getKey());
            if (binding == null) continue;
            try {
                binding.setKeyCode(Integer.parseInt(e.getValue().trim()));
                changed = true;
            } catch (RuntimeException ignored) {
                // Malformed/unknown stored key code: leave the default binding alone.
            }
        }
        if (changed) {
            KeyBinding.resetKeyBindingArrayAndHash();
            minecraft.gameSettings.saveOptions();
        }
    }

    @SubscribeEvent public void onChat(ClientChatReceivedEvent event) {
        if (!HookGuard.enter("event.onChat")) return;
        try {
            if (event.getMessage() == null || event.getType() == ChatType.GAME_INFO) return;
            if (event.getType() != ChatType.CHAT && event.getType() != ChatType.SYSTEM) return;
            final Minecraft minecraft = Minecraft.getMinecraft();
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
            final String text = event.getMessage().getUnformattedText();
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
        } catch (Throwable guardError) {
            HookGuard.fail("event.onChat", guardError);
            // Never lose a line: if we cancelled the vanilla message before failing, let it through.
            if (event.isCanceled()) event.setCanceled(false);
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
        final Minecraft minecraft = Minecraft.getMinecraft();
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

    private static ITextComponent copyComponent(ITextComponent source) {
        return source.createCopy();
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
        boolean keepInFlight = chatRequestProfile.differsOnlyInRequestSwitch(current);
        chatRequestProfile = current;
        chatSessionEpoch = nextEpoch(chatSessionEpoch);
        if (minecraft != null && minecraft.ingameGUI != null) flushPendingChatOriginals(minecraft);
        else clearPendingChatState();
        // Request-switch-only toggle: keep on-the-wire requests (results still cached);
        // queued work is dropped by the translator itself while the switch is off.
        if (!keepInFlight) TRANSLATOR.cancelPending();
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

    /**
     * 1.0.7 UI round 4: pointed-item tooltips are cache-only lookups always, but the active
     * engine decides whether missing lines are sent automatically. AI engine: every frame,
     * same as before round 3 (plus item warm scan, see {@link #warmVisibleItemNames}), no hint
     * row. Machine engine: only the re-translate key ({@link #itemRetranslateKey}, default R)
     * sends anything, with a hint/status row — unchanged from round 3 (free-tier rate limits).
     * Chat ({@link #onChat}) and entity name tags ({@link #onNameTagPre}) are always automatic
     * regardless of engine.
     */
    @SubscribeEvent public void onTooltipRender(RenderTooltipEvent.Pre event) {
        if (!HookGuard.enter("event.onTooltipRender")) return;
        try {
            if (!config.enabled || event.getStack() == null || event.getStack().isEmpty() || event.getLines().isEmpty()) return;
            tooltipRenderedThisTick = true;
            if (SCREEN_CAPTURE.active(renderingScreen)) {
                for (String source : event.getLines()) SCREEN_CAPTURE.record(renderingScreen, source);
                return;
            }
            String target = currentTarget();
            boolean ai = config.aiEnabled;
            List<String> lines = event.getLines();
            java.util.List<String> sources = new java.util.ArrayList<String>();
            boolean missing = false;
            for (int i = 0; i < lines.size(); i++) {
                String source = lines.get(i);
                if (!hasLetters(source)) continue;
                sources.add(source);
                String translated = TRANSLATOR.cached(source, target, ai, config);
                if (translated == null) {
                    missing = true;
                    if (ai) TRANSLATOR.prefetch(source, target, true, true, config);
                } else if (!translated.equals(source)) {
                    lines.set(i, translated);
                }
            }
            updatePointedTooltip(sources, target);
            if (!config.translationRequestsEnabled) {
                // Online translation is off: the only useful key press is the one that asks to start it.
                if (missing) {
                    lines.add("\u00a77" + I18n.format("screen.nyanlex.tooltip.start_hint",
                            Keyboard.getKeyName(itemRetranslateKey.getKeyCode())));
                }
                return;
            }
            if (ai) return;
            if (pointedTooltipTranslating) {
                lines.add("§7" + I18n.format("screen.nyanlex.tooltip.translating"));
            } else if (missing) {
                lines.add("§7" + I18n.format("screen.nyanlex.tooltip.hint",
                        Keyboard.getKeyName(itemRetranslateKey.getKeyCode())));
            }
        } catch (Throwable guardError) {
            HookGuard.fail("event.onTooltipRender", guardError);
        }
    }
    /**
     * F3 debug overlay text. AI engine: auto-prefetch on a cache miss, same as before round 3.
     * Machine engine: cache-only (round 3, unchanged).
     */
    @SubscribeEvent public void onOverlayText(RenderGameOverlayEvent.Text event) {
        if (!HookGuard.enter("event.onOverlayText")) return;
        try {
            if (!config.enabled) return;
            translateVisibleLines(event.getLeft(), false);
            translateVisibleLines(event.getRight(), false);
        } catch (Throwable guardError) {
            HookGuard.fail("event.onOverlayText", guardError);
        }
    }

    @SubscribeEvent public void onNameTagPre(RenderLivingEvent.Specials.Pre event) {
        HookGuard.enterSticky("event.onNameTagPre");
        try {
            EntityLivingBase entity = event.getEntity();
            if (entity == null || entity instanceof EntityPlayer || !config.enabled) return;
            String source = entity.getCustomNameTag();
            if (!hasLetters(source) || nameTagMatchesListedPlayer(source)) return;
            String translated = TRANSLATOR.cached(source, currentTarget(), config.aiEnabled, config);
            if (translated == null) TRANSLATOR.prefetch(source, currentTarget(), config.aiEnabled, false, config);
            else if (!translated.equals(source)) {
                renderedNames.put(entity.getEntityId(), source);
                entity.setCustomNameTag(translated);
            }
        } catch (Throwable guardError) {
            HookGuard.fail("event.onNameTagPre", guardError);
        }
    }
    @SubscribeEvent public void onNameTagPost(RenderLivingEvent.Specials.Post event) {
        HookGuard.enterSticky("event.onNameTagPost");
        try {
            EntityLivingBase entity = event.getEntity();
            if (entity == null) return;
            String original = renderedNames.remove(entity.getEntityId());
            if (original != null) entity.setCustomNameTag(original);
        } catch (Throwable guardError) {
            HookGuard.fail("event.onNameTagPost", guardError);
        }
    }

    @SubscribeEvent public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (!HookGuard.enter("event.onOverlayPost")) return;
        try {
            if (!config.debugTranslationOverlay || event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft minecraft = Minecraft.getMinecraft();
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
        } catch (Throwable guardError) {
            HookGuard.fail("event.onOverlayPost", guardError);
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
            java.util.Iterator<String> it = pointedTooltipAwaiting.iterator();
            while (it.hasNext()) {
                if (TRANSLATOR.cached(it.next(), target, config.aiEnabled, config) != null) it.remove();
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
     * (nothing hovered), retranslate the held main-hand item's name so the HUD "selected item"
     * popup has a cached translation next time it shows. AI engine: always a forced full
     * retranslate (invalidate + resend — it already auto-translates, so the key's only job is
     * "I don't like this result, try again"). Machine engine: missing lines only, or a forced
     * full retranslate if every line is already cached (unchanged from round 3).
     */
    private void handleRetranslateItemKey(final Minecraft minecraft) {
        if (config == null || !config.enabled) return;
        if (!config.translationRequestsEnabled) {
            boolean pointed = !pointedTooltipSources.isEmpty() && pointedTooltipTarget != null;
            if (!pointed && !(minecraft.currentScreen == null && minecraft.player != null)) return;
            final java.util.List<String> sources = new java.util.ArrayList<String>(pointedTooltipSources);
            final String target = pointedTooltipTarget;
            ForgeConsentScreen.open(minecraft, false, new Runnable() {
                @Override public void run() {
                    pointedTooltipSources = sources;
                    pointedTooltipTarget = target;
                    handleRetranslateItemKey(minecraft);
                }
            });
            return;
        }
        if (!pointedTooltipSources.isEmpty() && pointedTooltipTarget != null) {
            retranslatePointedTooltip();
            return;
        }
        if (minecraft.player != null) retranslateHeldItem(minecraft);
    }

    private void retranslatePointedTooltip() {
        String target = pointedTooltipTarget;
        java.util.List<String> sources = new java.util.ArrayList<String>(pointedTooltipSources);
        if (sources.isEmpty()) return;
        if (config.aiEnabled) {
            TRANSLATOR.retranslateScreen(sources, target, config);
            return;
        }
        boolean anyMissing = false;
        for (String source : sources) {
            if (TRANSLATOR.cached(source, target, config.aiEnabled, config) == null) { anyMissing = true; break; }
        }
        pointedTooltipAwaiting.clear();
        pointedTooltipAwaiting.addAll(sources);
        pointedTooltipTranslating = true;
        if (anyMissing) {
            for (String source : sources) TRANSLATOR.prefetch(source, target, config.aiEnabled, true, config);
        } else {
            TRANSLATOR.retranslateScreen(sources, target, config);
        }
    }

    private void retranslateHeldItem(Minecraft minecraft) {
        net.minecraft.item.ItemStack stack = minecraft.player.getHeldItemMainhand();
        if (stack == null || stack.isEmpty()) return;
        String name = stack.getDisplayName();
        if (!hasLetters(name)) return;
        String target = currentTarget();
        if (config.aiEnabled) {
            TRANSLATOR.retranslateScreen(java.util.Collections.singletonList(name), target, config);
            return;
        }
        if (TRANSLATOR.cached(name, target, config.aiEnabled, config) == null) {
            TRANSLATOR.prefetch(name, target, config.aiEnabled, true, config);
        } else {
            TRANSLATOR.retranslateScreen(java.util.Collections.singletonList(name), target, config);
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

    private static void addWarmName(java.util.Set<String> names, net.minecraft.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String name = stack.getDisplayName();
        if (hasLetters(name)) names.add(name);
    }

    /**
     * F3 debug overlay text (AI auto-prefetches here, see {@link #onOverlayText}); machine
     * engine stays cache-only — a miss just shows the original.
     */
    private void translateVisibleLines(List<String> lines, boolean highPriority) {
        if (SCREEN_CAPTURE.active(renderingScreen)) {
            for (String source : lines) SCREEN_CAPTURE.record(renderingScreen, source);
            return;
        }
        String target = currentTarget();
        boolean ai = config.aiEnabled;
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i);
            if (!hasLetters(source)) continue;
            String translated = TRANSLATOR.cached(source, target, ai, config);
            if (translated == null) {
                if (ai) TRANSLATOR.prefetch(source, target, true, highPriority, config);
            } else if (!translated.equals(source)) {
                lines.set(i, translated);
            }
        }
    }
    private boolean nameTagMatchesListedPlayer(String plain) {
        Minecraft minecraft = Minecraft.getMinecraft();
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

    static final int KEY_ITEM = 0;
    static final int KEY_SCREEN = 1;
    static final int KEY_SETTINGS = 2;

    /** Name of a hotkey as the player has it bound (for the quick setup and hints). */
    static String keyLabel(int which) {
        if (instance == null) return "?";
        KeyBinding key = which == KEY_ITEM ? instance.itemRetranslateKey
                : which == KEY_SCREEN ? instance.screenScanKey : instance.settingsKey;
        String name = Keyboard.getKeyName(key.getKeyCode());
        return name.length() == 1 ? name.toUpperCase(java.util.Locale.ROOT) : name;
    }

    static String version() {
        try {
            return net.minecraftforge.fml.common.Loader.instance().getIndexedModList().get("nyanlex").getVersion();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Opens a web link after the player confirmed it. */
    static void openLink(final net.minecraft.client.gui.GuiScreen parent, final String url) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(new net.minecraft.client.gui.GuiConfirmOpenLink(new net.minecraft.client.gui.GuiYesNoCallback() {
            @Override public void confirmClicked(boolean yes, int id) {
                if (yes) {
                    try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); }
                    catch (Throwable ignored) { }
                }
                mc.displayGuiScreen(parent);
            }
        }, url, 0, true));
    }

    static LegacyConfig config() { return instance.config; }
    static LegacyCodexClient codexClient() { return instance.codexClient; }
    static void save() { instance.saveConfig(); }
    static void testAi(final Consumer<String> callback) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        TRANSLATOR.testAi(currentTarget(), config(), result -> minecraft.addScheduledTask(() -> callback.accept(result)));
    }
    static String currentTarget() {
        LegacyConfig cfg = config();
        if (!cfg.followGameLanguage) return cfg.targetLang;
        String code = Minecraft.getMinecraft().gameSettings.language;
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
            com.google.gson.JsonElement tree = new com.google.gson.JsonParser().parse(readConfigText(configFile));
            boolean hadRequests = tree != null && tree.isJsonObject()
                    && tree.getAsJsonObject().has("translationRequestsEnabled");
            boolean hadFirstRun = tree != null && tree.isJsonObject()
                    && tree.getAsJsonObject().has("firstRunDone");
            LegacyConfig loaded = LegacyConfig.applyUpgradeDefaults(
                    LegacyConfig.normalizeLoaded(GSON.fromJson(tree, LegacyConfig.class)),
                    hadRequests, hadFirstRun);
            if (loaded != null) { saveConfig(loaded); return loaded; }
        } catch (Exception ignored) {}
        LegacyConfig created = new LegacyConfig();
        saveConfig(created);
        return created;
    }
    /**
     * The config is written as UTF-8. Older builds used FileWriter (platform charset, e.g. MS950 on
     * zh-TW Windows), so bytes that are not strictly valid UTF-8 are decoded with that charset instead;
     * the save right after loading then migrates the file to UTF-8.
     */
    static String readConfigText(File file) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException legacyPlatformCharset) {
            return new String(bytes, java.nio.charset.Charset.defaultCharset());
        }
    }
    private void saveConfig() { saveConfig(config); }
    private void saveConfig(LegacyConfig value) {
        try {
            value.machineTranslationProvider = LegacyConfig.normalizeMachineProvider(value.machineTranslationProvider);
            File parent = configFile.getParentFile(); if (parent != null) parent.mkdirs();
            java.io.Writer writer = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(configFile), java.nio.charset.StandardCharsets.UTF_8);
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
