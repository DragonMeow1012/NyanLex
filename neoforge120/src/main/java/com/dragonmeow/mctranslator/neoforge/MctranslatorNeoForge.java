package com.dragonmeow.mctranslator.neoforge;

import com.dragonmeow.mctranslator.cache.LanguageFileStore;
import com.dragonmeow.mctranslator.cache.PersistentStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.translate.AiSettings;
import com.dragonmeow.mctranslator.translate.DispatchingTranslator;
import com.dragonmeow.mctranslator.translate.GoogleFreeTranslator;
import com.dragonmeow.mctranslator.translate.OpenAiTranslator;
import com.dragonmeow.mctranslator.translate.ParagraphModel;
import com.dragonmeow.mctranslator.translate.Translator;
import com.dragonmeow.mctranslator.translate.UrlHttpTransport;

import com.dragonmeow.mctranslator.neoforge.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NeoForge (Mojang-mapped) client entry point. Reuses the loader-agnostic core
 * (config / translate / cache / service / style) and provides NeoForge glue:
 * chat + item-tooltip translation via events, plus toggle key binds and a
 * background item pre-translation pass.
 */
@Mod(MctranslatorNeoForge.MOD_ID)
public final class MctranslatorNeoForge {

    public static final String MOD_ID = "mctranslator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TranslatorConfig config;
    private static TranslationService service;
    private static Path configPath;
    private static UrlHttpTransport transport;

    private static KeyMapping modeKey;
    private static KeyMapping retranslateKey;
    private static KeyMapping screenScanKey;
    private static KeyMapping toggleKey;
    private long actionBarSequence;

    private static final java.util.Map<Object, String> FTB_PENDING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // Pre-translate items in an open container screen: track the open screen and the
    // item names already warmed, so each distinct item is warmed once (not per tick).
    private net.minecraft.client.gui.screens.Screen lastContainerScreen;
    private final java.util.Set<String> warmedContainerNames = new java.util.HashSet<>();
    /** Names queued from the local player's hotbar, backpack, armour and off-hand. */
    private final java.util.Set<String> warmedOwnedItemNames = new java.util.HashSet<>();
    /** Last late tooltip snapshot, including lines appended by other tooltip callbacks. */
    private ItemStack lastTooltipStack;
    private List<String> lastTooltipParagraphSources;

    /** Online player names, refreshed once per second on the tick thread; read by the
     *  service to mask names in chat and to skip "translating" name tags / scoreboards. */
    private static volatile java.util.Set<String> onlineNames = java.util.Set.of();
    private long lastNameRefreshMs;

    private void refreshOnlineNames(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastNameRefreshMs < 1_000L) return;
        lastNameRefreshMs = now;
        if (mc == null || mc.getConnection() == null) {
            if (!onlineNames.isEmpty()) onlineNames = java.util.Set.of();
            return;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        if (mc.level != null) {
            for (var player : mc.level.players()) {
                String name = player.getGameProfile().getName();
                if (name != null && PLAYER_NAME.matcher(name).matches()) names.add(name);
            }
        }
        for (var info : mc.getConnection().getListedOnlinePlayers()) {
            String name = info == null || info.getProfile() == null
                    ? null : info.getProfile().getName();
            if (name != null && PLAYER_NAME.matcher(name).matches()) names.add(name);
        }
        onlineNames = names;
    }

    private static final java.util.regex.Pattern PLAYER_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");


    private final java.util.ArrayDeque<PendingChat> pendingChats = new java.util.ArrayDeque<>();
    private final java.util.Map<Long, PendingChat> pendingChatById = new java.util.HashMap<>();
    private long nextChatId = 1L;

    private static final class PendingChat {
        final long id;
        final Component message;
        final long queuedAtMs = System.currentTimeMillis();
        DisplayMode mode;
        java.util.function.Supplier<Component> builder;
        boolean ready;
        boolean flushedOriginal; // original already shown (slow translation); append it alone later
        boolean framedByServer;  // inside a server ────── announcement frame: skip our magenta wrap

        PendingChat(long id, Component message) {
            this.id = id;
            this.message = message;
        }
    }

    /** How long a chat line may wait for its translation before the original is shown anyway. */
    private static final long CHAT_MAX_WAIT_MS = 15_000L; // safety net only: 原文＋翻譯 output together

    private boolean insideServerFrame;
    private long frameOpenedAtMs;
    private int separatorSalt;

    /**
     * A framed server announcement being collected:
     * <pre>----- / lines… / -----</pre>
     * The whole block is translated together and emitted as ONE chat message, so
     * ordering can't break and compact-chat mods can't merge the two frame lines.
     */
    private static final class PendingBlock {
        final PendingChat holder;                     // the queue slot keeping chat order
        final DisplayMode mode;
        final List<Component> lines = new ArrayList<>();
        final java.util.Map<Integer, Component> translations = new java.util.HashMap<>();
        final long openedAtMs = System.currentTimeMillis();
        int awaiting;
        boolean closed;

        PendingBlock(PendingChat holder, DisplayMode mode) {
            this.holder = holder;
            this.mode = mode;
        }
    }

    private PendingBlock activeBlock;
    /** An announcement's lines arrive within a tick or two; a frame still open after
     *  this long is treated as a decorative lone separator and closed as-is. */
    private static final long BLOCK_MAX_OPEN_MS = 3_000L;

    /** Collect framed SYSTEM announcements into one combined message. Returns true if
     *  the line was absorbed into a block (the vanilla event must be cancelled). */
    private boolean handleAnnouncementBlock(Component message, boolean isSystem, DisplayMode mode, String full) {
        boolean isSep = NeoTextStyle.isSeparatorText(full);
        if (activeBlock == null) {
            if (!isSep || !isSystem) return false; // only system messages open a frame
            PendingChat holder = queueChat(message);
            holder.mode = DisplayMode.TRANSLATION;   // builder emits the whole block verbatim
            activeBlock = new PendingBlock(holder, mode);
            activeBlock.lines.add(message);
            return true;
        }
        PendingBlock block = activeBlock;
        block.lines.add(message);
        if (isSep) {
            block.closed = true;
            activeBlock = null;
            translateBlockParagraphs(block);
            return true;
        }
        return true;
    }

    /** Translate a collected frame only after its closing separator has arrived. */
    private void translateBlockParagraphs(PendingBlock block) {
        int first = !block.lines.isEmpty() && NeoTextStyle.isSeparatorText(block.lines.get(0).getString()) ? 1 : 0;
        int end = block.lines.size();
        if (end > first && NeoTextStyle.isSeparatorText(block.lines.get(end - 1).getString())) end--;

        List<String> visible = new ArrayList<>(Math.max(0, end - first));
        List<NeoTextStyle.ChatLinePlan> prepared = new ArrayList<>(Math.max(0, end - first));
        for (int i = first; i < end; i++) {
            NeoTextStyle.ChatLinePlan plan = NeoTextStyle.prepareChatLine(block.lines.get(i));
            prepared.add(plan);
            visible.add(plan.content());
        }
        List<Integer> starts = new ArrayList<>();
        List<List<NeoTextStyle.ChatLinePlan>> groups = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (ParagraphModel.Range range : ParagraphModel.ranges(visible)) {
            if (range.size() == 1 && ParagraphModel.isBlank(visible.get(range.start()))) continue;
            List<NeoTextStyle.ChatLinePlan> plans = new ArrayList<>(range.size());
            List<String> rows = new ArrayList<>(range.size());
            boolean wanted = false;
            for (int row = range.start(); row <= range.end(); row++) {
                NeoTextStyle.ChatLinePlan plan = prepared.get(row);
                plans.add(plan);
                rows.add(plan.request());
                wanted |= !plan.request().isBlank() && service.wantsChatTranslation(plan.content());
            }
            if (!wanted) continue;
            starts.add(first + range.start());
            groups.add(plans);
            requests.add(ParagraphModel.join(rows));
        }

        block.awaiting = groups.size();
        if (groups.isEmpty()) {
            maybeFinishBlock(block);
            return;
        }
        for (int paragraph = 0; paragraph < groups.size(); paragraph++) {
            int start = starts.get(paragraph);
            List<NeoTextStyle.ChatLinePlan> plans = groups.get(paragraph);
            String request = requests.get(paragraph);
            service.translateChatAsync(request, translated -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> {
                    List<String> rows = validatedParagraphRows(translated, plans.size());
                    if (!rows.isEmpty()) {
                        List<Component> paragraphLines = new ArrayList<>(plans.size());
                        for (int row = 0; row < plans.size(); row++) {
                            Component rebuilt = NeoTextStyle.rebuildChatLine(plans.get(row), rows.get(row));
                            Component source = block.lines.get(start + row);
                            paragraphLines.add(block.mode == DisplayMode.BOTH
                                    ? Component.empty().append(source).append(Component.literal("\n")).append(rebuilt)
                                    : rebuilt);
                        }
                        for (int row = 0; row < paragraphLines.size(); row++) {
                            block.translations.put(start + row, paragraphLines.get(row));
                        }
                    }
                    block.awaiting--;
                    maybeFinishBlock(block);
                });
            });
        }
    }

    private void maybeFinishBlock(PendingBlock block) {
        if (!block.closed || block.awaiting > 0 || block.holder.ready) return;
        block.holder.builder = () -> {
            net.minecraft.network.chat.MutableComponent out = Component.empty();
            for (int i = 0; i < block.lines.size(); i++) {
                if (i > 0) out.append(Component.literal("\n"));
                Component translated = block.translations.get(i);
                out.append(translated != null ? translated : block.lines.get(i));
            }
            return out;
        };
        block.holder.ready = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) flushReadyChats(mc);
    }

    /** Close a frame the server never closed (lone decorative separator). */
    private void expireStaleBlock() {
        if (activeBlock != null && System.currentTimeMillis() - activeBlock.openedAtMs > BLOCK_MAX_OPEN_MS) {
            PendingBlock block = activeBlock;
            activeBlock = null;
            block.closed = true;
            translateBlockParagraphs(block);
        }
    }

    /** Track the server's own ────── announcement frames: content between a frame's two
     *  separator lines is already visually boxed, so our magenta wrap is suppressed there.
     *  A frame left open (unpaired decorative separator) expires after 3s. */
    private boolean trackServerFrame(String full) {
        long now = System.currentTimeMillis();
        if (insideServerFrame && now - frameOpenedAtMs > 3_000L) insideServerFrame = false;
        if (NeoTextStyle.isSeparatorText(full)) {
            insideServerFrame = !insideServerFrame;
            frameOpenedAtMs = now;
            return false;
        }
        return insideServerFrame;
    }

    /** The "re-translate pointed item" key binding (rebindable from the 翻譯設定 screen). */
    public static KeyMapping retranslateKeyMapping() {
        return retranslateKey;
    }

    /** Accessor for the Options-screen button Mixin. */
    public static TranslationService service() {
        return service;
    }

    /** Accessor for Mixins (e.g. the scoreboard toggle). */
    public static TranslatorConfig config() {
        return config;
    }

    /**
     * Translate arbitrary GUI text drawn via {@code GuiGraphics} (custom mod screens such as
     * shader-pack settings). Gated by {@code screenTextMode} and only while a screen is open
     * (so the in-world HUD is untouched). Non-blocking + memoised. Called by GuiGraphicsTextMixin.
     */
    public static Component screenText(Component c) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return c;
        TranslationService s = service;
        if (s == null || c == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return c;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return c;
        Component t = NeoTextStyle.renderTranslated("screenText", c, s::translateScreenText);
        return t != null ? t : c;
    }

    /** Translate an optional FTB Library TextField before FTB measures and wraps it. */
    public static Component ftbText(Object widget, Component source) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return source;
        TranslationService s = service;
        if (widget == null || source == null || s == null
                || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return source;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null) return source;
        Component resolved = NeoTextStyle.resolveLegacyCodes(source);
        Component rendered = NeoTextStyle.renderTranslated("ftb", resolved, s::translateScreenText);
        if (rendered != null) {
            FTB_PENDING.remove(widget);
            return rendered;
        }
        List<String> requests = NeoTextStyle.requestLines(resolved).stream()
                .filter(s::wantsScreenTextTranslation).toList();
        if (requests.isEmpty()) return source;
        String request = String.join("\u0000", requests);
        boolean submit;
        synchronized (FTB_PENDING) {
            submit = !request.equals(FTB_PENDING.get(widget));
            if (submit) FTB_PENDING.put(widget, request);
        }
        if (submit) for (String lineRequest : requests) {
            s.requestLiveScreenTextAsync(lineRequest, translated -> {
                synchronized (FTB_PENDING) {
                    if (!request.equals(FTB_PENDING.get(widget))) return;
                }
                Component ready = NeoTextStyle.renderTranslated(
                        "ftb", resolved, s::translateScreenText);
                Minecraft client = Minecraft.getInstance();
                if (ready != null && client != null) {
                    client.execute(() -> applyFtbText(widget, ready));
                }
            });
        }
        return source;
    }

    private static void applyFtbText(Object widget, Component translated) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null
                || !mc.screen.getClass().getName().startsWith("dev.ftb.")) return;
        try {
            Class<?> type = widget.getClass();
            java.lang.reflect.Method setter = null;
            while (type != null && setter == null) {
                try { setter = type.getDeclaredMethod("setText", Component.class); }
                catch (NoSuchMethodException ignored) { type = type.getSuperclass(); }
            }
            if (setter != null) {
                setter.setAccessible(true);
                com.dragonmeow.mctranslator.translate.InternalRenderGuard.enter();
                try {
                    setter.invoke(widget, translated);
                } finally {
                    com.dragonmeow.mctranslator.translate.InternalRenderGuard.exit();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.debug("Unable to reflow translated FTB text field", error);
        }
    }

    /** String overload of {@link #screenText(Component)}. */
    public static String screenText(String str) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return str;
        TranslationService s = service;
        if (s == null || str == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return str;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return str;
        if (str.indexOf('\n') >= 0 || str.indexOf('\r') >= 0) {
            String normalized = str.replace("\r\n", "\n").replace('\r', '\n');
            Component translated = NeoTextStyle.renderTranslated(
                    "screenText", Component.literal(normalized), s::translateScreenText);
            return translated != null ? translated.getString() : str;
        }
        TranslationDecision d = s.translateScreenText(str);
        return d.changed() ? d.translated() : str;
    }

    /**
     * FormattedCharSequence overload: catches text that mod GUIs draw as already-laid-out
     * ordered text (e.g. FTB Quests descriptions, wrapped/centred lines). The line is
     * flattened to plain text, translated, and rebuilt. Component-originated text is already
     * translated by {@link #screenText(Component)} upstream, so here it is Chinese and skipped.
     */
    public static net.minecraft.util.FormattedCharSequence screenText(net.minecraft.util.FormattedCharSequence fcs) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return fcs;
        TranslationService s = service;
        if (s == null || fcs == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return fcs;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return fcs;
        if (mc.screen.getClass().getName().startsWith("dev.ftb.")) return fcs;
        Component source = NeoTextStyle.toComponent(fcs);
        Component styled = NeoTextStyle.renderTranslated("screenTextFcs", source, s::translateScreenText);
        if (styled == null) return fcs;
        Font font = mc.font;
        if (font != null) {
            int originalWidth = font.width(fcs);
            int budget = Math.max(originalWidth + 24, (int) (originalWidth * 1.25f));
            if (font.width(styled) > budget) return fcs;
        }
        return styled.getVisualOrderText();
    }

    /**
     * FormattedText overload, hooked at {@code Font.split(FormattedText,width)} — the point a
     * GUI wraps a WHOLE block of text into lines (FTB quest descriptions, multi-line tooltips).
     * Translating the whole block here (then letting Minecraft re-wrap the translation) keeps it
     * coherent, instead of translating each already-wrapped line separately (which reads choppy).
     */
    public static net.minecraft.network.chat.FormattedText screenText(net.minecraft.network.chat.FormattedText text) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return text;
        TranslationService s = service;
        if (s == null || text == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return text;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.screen == null
                || mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return text;
        if (mc.screen.getClass().getName().startsWith("dev.ftb.")) return text;
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen) return text;
        Component source = NeoTextStyle.toComponent(text);
        Component translated = NeoTextStyle.renderTranslated("screenTextBlock", source, s::translateScreenText);
        return translated == null ? text : translated;
    }

    /** Persist config (used by the config screen). Also drops the render cache so a
     *  surface turned off / mode switched takes effect immediately. */
    public static void saveConfig() {
        if (config != null && configPath != null) {
            config.save(configPath);
        }
        NeoTextStyle.clearRenderMemo();
    }

    public MctranslatorNeoForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        configPath = FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json");
        config = TranslatorConfig.load(configPath);

        int workers = Math.max(1, config.workerThreads);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "mctranslator-worker");
            t.setDaemon(true);
            return t;
        };
        // LIFO work queue: the most-recently-requested translations — i.e. the page / interface
        // the player is looking at RIGHT NOW — are taken first, jumping ahead of any older
        // backlog (a previous screen, or the background pre-translation). So the current screen
        // is never stuck waiting behind a long queue.
        java.util.concurrent.LinkedBlockingDeque<Runnable> workQueue =
                new java.util.concurrent.LinkedBlockingDeque<>() {
                    @Override
                    public boolean offer(Runnable r) {
                        return super.offerFirst(r); // enqueue at the front => LIFO
                    }
                };
        ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
                workers, workers, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, workQueue, threadFactory);

        transport = new UrlHttpTransport(Duration.ofMillis(config.httpTimeoutMs));
        GoogleFreeTranslator google = new GoogleFreeTranslator(transport, config.sourceLang);
        OpenAiTranslator ai = new OpenAiTranslator(transport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel, config.aiApiKeys, config.aiGlossary));
        // The AI cache tries AI when a key is configured, else falls back to Google.
        Translator aiTranslator = new DispatchingTranslator(ai, google,
                () -> config.aiApiKeys != null && !config.aiApiKeys.isEmpty());

        PersistentStore googleStore = new LanguageFileStore(
                FMLPaths.CONFIGDIR.get(), MOD_ID + "-cache", config.targetLang);
        PersistentStore aiStore = new LanguageFileStore(
                FMLPaths.CONFIGDIR.get(), MOD_ID + "-ai-cache", config.targetLang);
        // Separate caches per engine so a 機翻 and an AI result for the same string never collide.
        TranslationCache cache = new TranslationCache(google, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, googleStore);
        TranslationCache aiCache = new TranslationCache(aiTranslator, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, aiStore);
        // GT 暫代 → AI 補翻: provisional (fallback-produced) entries in the AI cache are
        // re-asked of the AI on a later hit, but only when keys are configured AND the
        // global 429 gate has reopened. Only the AI cache gets a gate — the Google cache
        // never stores provisional values.
        aiCache.setProvisionalRetryGate(() ->
                config.aiApiKeys != null && !config.aiApiKeys.isEmpty() && !ai.isRateLimited());
        service = new TranslationService(config, cache, aiCache);
        service.setProtectedNames(() -> onlineNames);

        modBus.addListener(this::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[{}] (NeoForge) initialized (target={}, chat={}, tooltip={})",
                MOD_ID, config.targetLang, config.chatMode, config.tooltipMode);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        // Cycle the display mode. Unbound by default (use the Options-screen button,
        // or bind a free key in Controls).
        modeKey = new KeyMapping("key.mctranslator.mode", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "category.mctranslator");
        event.register(modeKey);
        // Re-translate the item you are pointing at / holding. Default: R.
        retranslateKey = new KeyMapping("key.mctranslator.retranslate", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R, "category.mctranslator");
        event.register(retranslateKey);
        // Capture & translate all buttons/options of the open screen (e.g. a modpack
        // quest book). Default: P. Handled in onScreenKeyPressed (key binds don't tick
        // while a screen is open).
        screenScanKey = new KeyMapping("key.mctranslator.screenscan", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P, "category.mctranslator");
        event.register(screenScanKey);
        // Quick 原文/翻譯 master toggle. Default: G.
        toggleKey = new KeyMapping("key.mctranslator.toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, "category.mctranslator");
        event.register(toggleKey);
    }

    /** Flip the master 原文/翻譯 switch, bust the render memo so persistent surfaces flip at once, and report. */
    private void flipShowOriginal() {
        if (service == null) return;
        boolean originalsNow = service.toggleShowOriginal();
        NeoTextStyle.clearRenderMemo();
        if (originalsNow) flushPendingChatOriginals();
        status(originalsNow ? "顯示原文（翻譯已暫停）" : "顯示翻譯");
    }

    /** When 跟隨遊戲 is on, keep the translation target language synced to Minecraft's own (繁/簡). */
    private void syncGameLanguage(Minecraft mc) {
        if (service == null || config == null || !config.followGameLanguage || mc == null || mc.options == null) return;
        String desired = mapGameLang(mc.options.languageCode);
        if (!desired.equals(config.targetLang)) {
            service.setTargetLang(desired);
            NeoTextStyle.clearRenderMemo();
        }
    }

    /** Map Minecraft's language code (zh_cn / zh_tw / en_us …) to 繁/簡 target. */
    static String mapGameLang(String gameLang) {
        return com.dragonmeow.mctranslator.config.TranslationLanguages.fromMinecraftCode(gameLang);
    }

    /** The "translate current screen" key binding (rebindable from the 翻譯設定 screen). */
    public static KeyMapping screenScanKeyMapping() {
        return screenScanKey;
    }

    public static KeyMapping toggleKeyMapping() {
        return toggleKey;
    }

    @SubscribeEvent
    public void onRenderNameTag(net.minecraftforge.client.event.RenderNameTagEvent event) {
        if (service == null) return;
        Component current = event.getContent();
        if (current == null) return;
        // Entity path (R7 guard): TAB-listed real players keep their original tag.
        Component translated = nameTag(event.getEntity(), current);
        if (translated != null && translated != current) event.setContent(translated);
    }

    /**
     * Name-tag entry with the event's entity: a REAL online player — one the TAB player
     * list shows, i.e. in {@code getListedOnlinePlayers()} — keeps the ORIGINAL name tag
     * (player IDs are names, not text; "最偉大的迪加" must never happen). NPCs still
     * translate: Hypixel-style fake players have a profile but are NOT listed, so the
     * LISTED set is the discriminator (never {@code getPlayerInfo != null}). Only the
     * nameTag surface is guarded — chat keeps its NameMasker, scoreboards are untouched.
     */
    public static Component nameTag(net.minecraft.world.entity.Entity entity, Component c) {
        if (c == null) return null;
        // Real-player guard runs BEFORE any memo/cache (translateNameTag holds the memo):
        // (a) the entity is a TAB-listed Player, OR (b) — Hypixel renders player name tags
        // via invisible ArmorStand/TextDisplay entities, which are NOT Player, so the
        // entity check alone is blind there — the tag TEXT contains any listed player's
        // name as a whole token. Either way the tag stays verbatim.
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            return c; // real player: no cache lookup, no request, verbatim tag
        }
        if (nameTagMatchesListedPlayer(c.getString())) return c;
        return translateNameTag(c);
    }

    /** Entity-less name-tag entry: string fallback — a tag containing any LISTED player's
     *  name as a whole token is a real player's tag and stays verbatim. */
    public static Component nameTag(Component c) {
        return nameTag(null, c);
    }

    private static Component translateNameTag(Component c) {
        TranslationService s = service;
        if (s == null || c == null) return c;
        Component t = NeoTextStyle.renderTranslated("nameTag", c, s::translateUi);
        return t != null ? t : c;
    }

    /** Whole-token match (name chars = [A-Za-z0-9_], so "Steve" never matches inside
     *  "Steves") of any LISTED player name inside a name tag's plain text. */
    private static boolean nameTagMatchesListedPlayer(String plain) {
        if (plain == null || plain.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientPacketListener conn =
                (mc == null) ? null : mc.getConnection();
        if (conn == null) return false;
        for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getListedOnlinePlayers()) {
            String name = (info == null || info.getProfile() == null) ? null : info.getProfile().getName();
            if (name == null || name.isEmpty()) continue;
            int at = plain.indexOf(name);
            while (at >= 0) {
                boolean leftEdge = at == 0 || !isNameTokenChar(plain.charAt(at - 1));
                int end = at + name.length();
                boolean rightEdge = end >= plain.length() || !isNameTokenChar(plain.charAt(end));
                if (leftEdge && rightEdge) return true;
                at = plain.indexOf(name, at + 1);
            }
        }
        return false;
    }

    private static boolean isNameTokenChar(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

    private boolean handleOverlayMessage(Component message) {
        if (service == null || message == null) return false;
        long sequence = ++actionBarSequence;
        Component source = NeoTextStyle.resolveLegacyCodes(message);
        DisplayMode mode = service.actionBarMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return false;
        NeoTextStyle.MarkedChat marked = NeoTextStyle.markChatContent(source, 0);
        String request = marked.marked() ? marked.text() : source.getString();
        if (!service.wantsActionBarTranslation(request)) return false;
        TranslationDecision cached = service.translateActionBar(request);
        if (cached.changed()) {
            showActionBar(source, cached.translated(), marked, cached.mode());
            return true;
        }
        service.requestActionBarAsync(request, translated -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                if (sequence != actionBarSequence) return;
                showActionBar(source, translated, marked, service.actionBarMode());
            });
        });
        return false;
    }

    private static void showActionBar(Component source, String translated,
                                      NeoTextStyle.MarkedChat marked, DisplayMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || translated == null || mode == DisplayMode.ORIGINAL_ONLY) return;
        Component rich = NeoTextStyle.rebuildRich(source, translated, marked);
        Component shown = mode == DisplayMode.BOTH
                ? source.copy().append(Component.literal("　")).append(rich)
                : rich;
        mc.gui.setOverlayMessage(shown, false);
    }

    @SubscribeEvent
    public void onClientChat(ClientChatReceivedEvent event) {
        if (service == null) return;
        if (event instanceof ClientChatReceivedEvent.System sys && sys.isOverlay()) {
            if (handleOverlayMessage(event.getMessage())) event.setCanceled(true);
            return;
        }
        DisplayMode mode = service.chatMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return;
        Component message = NeoTextStyle.resolveLegacyCodes(event.getMessage());
        if (message == null) return;
        List<Component> hardLines = NeoTextStyle.splitStyledLines(message);
        if (hardLines.size() > 1) {
            boolean isSystem = event instanceof ClientChatReceivedEvent.System;
            boolean canceled;
            if (isSystem && (activeBlock != null || NeoTextStyle.isSeparatorText(hardLines.get(0).getString()))) {
                List<Component> remainder = new ArrayList<>();
                for (Component line : hardLines) {
                    if (!handleAnnouncementBlock(line, true, mode, line.getString())) remainder.add(line);
                }
                canceled = true;
                if (!remainder.isEmpty()) {
                    translateHardLineMessage(NeoTextStyle.joinStyledLines(remainder), mode, remainder);
                }
            } else {
                canceled = translateHardLineMessage(message, mode, hardLines);
            }
            if (canceled) event.setCanceled(true);
            return;
        }
        String full = message.getString();
        if (handleAnnouncementBlock(message, event instanceof ClientChatReceivedEvent.System, mode, full)) {
            event.setCanceled(true);
            return;
        }
        boolean framedByServer = trackServerFrame(full);

        // Translate only the content after the rank/name separator (» etc.); keep the prefix.
        int contentStart = com.dragonmeow.mctranslator.translate.ChatSegmenter.contentStart(full);
        boolean hasPrefix = contentStart > 0 && contentStart < full.length();
        String content = hasPrefix ? full.substring(contentStart) : full;
        if (!service.wantsChatTranslation(content)) {
            // Untranslatable line (e.g. the "-----" frame of a Hypixel announcement): if
            // translatable lines are still queued ahead of it, it must WAIT IN LINE as a
            // ready pass-through — otherwise the frame prints before its framed content.
            if (pendingChats.isEmpty()) return;
            event.setCanceled(true);
            Component reinjected = message;
            if (NeoTextStyle.isSeparatorText(full)) {
                // Compact-chat mods merge identical frame lines and delete the earlier one;
                // alternate an invisible trailing space so the two frames never compare equal.
                separatorSalt = (separatorSalt + 1) & 3;
                if (separatorSalt > 0) {
                    reinjected = message.copy().append(Component.literal(" ".repeat(separatorSalt)));
                }
            }
            PendingChat passThrough = queueChat(reinjected);
            passThrough.mode = DisplayMode.ORIGINAL_ONLY;
            passThrough.ready = true;
            return;
        }

        final int cs = contentStart;
        final boolean prefix = hasPrefix;
        // Cancel the vanilla line; we re-inject the (translated) line once ready, falling
        // back to the original if it can't be translated so nothing is ever lost.
        event.setCanceled(true);

        // Colour ONLY from the message content (after any rank/name prefix), so the prefix's
        // colours are never smeared onto the translation.
        PendingChat pending = queueChat(message);
        pending.mode = mode;
        pending.framedByServer = framedByServer;

        NeoTextStyle.MarkedChat marked = NeoTextStyle.markChatContent(message, cs);
        if (marked.marked()) {
            // Word-level colour preservation: wrap each style run in an invisible ⟦CS#⟧
            // marker, translate the WHOLE line in one request (better grammar, fewer
            // requests than per-segment), then map every marker region back to its style
            // — a red word stays red on its translated word. Click/hover ride along on
            // the segment styles.
            service.translateChatAsync(marked.text(), translated ->
                    completeChat(pending.id, mode, translated == null ? null : () -> {
                        Font font = Minecraft.getInstance().font;
                        var core = NeoTextStyle.markedChat(message, cs, translated, marked);
                        if (prefix) {
                            return Component.empty()
                                    .append(NeoTextStyle.takePrefix(message, cs))
                                    .append(core);
                        }
                        return core; // core keeps the original's leading whitespace: starts aligned
                    }));
            return;
        }
        service.translateChatAsync(content, translated ->
                completeChat(pending.id, mode, translated == null ? null
                        : () -> chatLine(Minecraft.getInstance().font, message, prefix, cs, translated)));
    }

    private boolean translateHardLineMessage(Component original, DisplayMode mode,
                                             List<Component> hardLines) {
        List<NeoTextStyle.ChatLinePlan> plans = new ArrayList<>(hardLines.size());
        List<String> visible = new ArrayList<>(hardLines.size());
        for (int i = 0; i < hardLines.size(); i++) {
            NeoTextStyle.ChatLinePlan plan = NeoTextStyle.prepareChatLine(hardLines.get(i));
            plans.add(plan);
            visible.add(plan.content());
        }
        List<ParagraphModel.Range> requested = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (ParagraphModel.Range range : ParagraphModel.ranges(visible)) {
            if (range.size() == 1 && ParagraphModel.isBlank(visible.get(range.start()))) continue;
            List<String> rows = new ArrayList<>(range.size());
            boolean wanted = false;
            for (int row = range.start(); row <= range.end(); row++) {
                NeoTextStyle.ChatLinePlan plan = plans.get(row);
                rows.add(plan.request());
                wanted |= !plan.request().isBlank() && service.wantsChatTranslation(plan.content());
            }
            if (wanted) {
                requested.add(range);
                requests.add(ParagraphModel.join(rows));
            }
        }
        if (requested.isEmpty()) {
            if (pendingChats.isEmpty()) return false;
            PendingChat passThrough = queueChat(original);
            passThrough.mode = DisplayMode.ORIGINAL_ONLY;
            passThrough.ready = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gui != null) flushReadyChats(mc);
            return true;
        }
        PendingChat pending = queueChat(original);
        pending.mode = mode;
        java.util.concurrent.atomic.AtomicReferenceArray<Component> rebuilt =
                new java.util.concurrent.atomic.AtomicReferenceArray<>(hardLines.size());
        for (int i = 0; i < hardLines.size(); i++) rebuilt.set(i, hardLines.get(i).copy());
        java.util.concurrent.atomic.AtomicInteger awaiting =
                new java.util.concurrent.atomic.AtomicInteger(requested.size());
        for (int paragraph = 0; paragraph < requested.size(); paragraph++) {
            ParagraphModel.Range range = requested.get(paragraph);
            String request = requests.get(paragraph);
            service.translateChatAsync(request, translated -> {
                List<String> rows = validatedParagraphRows(translated, range.size());
                if (!rows.isEmpty()) {
                    List<Component> paragraphLines = new ArrayList<>(range.size());
                    for (int row = range.start(); row <= range.end(); row++) {
                        paragraphLines.add(NeoTextStyle.rebuildChatLine(
                                plans.get(row), rows.get(row - range.start())));
                    }
                    for (int row = range.start(); row <= range.end(); row++) {
                        rebuilt.set(row, paragraphLines.get(row - range.start()));
                    }
                }
                if (awaiting.decrementAndGet() == 0) {
                    completeChat(pending.id, mode, () -> {
                        List<Component> ready = new ArrayList<>(rebuilt.length());
                        for (int row = 0; row < rebuilt.length(); row++) ready.add(rebuilt.get(row));
                        return NeoTextStyle.joinStyledLines(ready);
                    });
                }
            });
        }
        return true;
    }

    /** Google/AI fallback is accepted only when every immutable PB anchor survived in order. */
    private static List<String> validatedParagraphRows(String translated, int expectedRows) {
        if (translated == null || expectedRows < 1) return List.of();
        java.util.regex.Matcher matcher = ParagraphModel.BREAK_TOKEN_PATTERN.matcher(translated);
        int token = 0;
        while (matcher.find()) {
            int found;
            try {
                found = Integer.parseInt(matcher.group(1));
            } catch (RuntimeException malformed) {
                return List.of();
            }
            if (found != token++) return List.of();
        }
        if (token != expectedRows - 1) return List.of();
        List<String> rows = ParagraphModel.split(translated);
        return rows.size() == expectedRows ? rows : List.of();
    }

    private PendingChat queueChat(Component message) {
        PendingChat pending = new PendingChat(nextChatId++, message);
        pendingChats.addLast(pending);
        pendingChatById.put(pending.id, pending);
        return pending;
    }

    private void completeChat(long id, DisplayMode mode, java.util.function.Supplier<Component> builder) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.gui == null) return;
            PendingChat pending = pendingChatById.get(id);
            if (pending == null) return;
            if (pending.flushedOriginal) {
                // The original was already shown by the timeout: append just the translation.
                pendingChatById.remove(id);
                if (builder != null && mode != DisplayMode.ORIGINAL_ONLY) {
                    Component translated = builder.get();
                    if (translated != null) {
                        mc.gui.getChat().addMessage(mode == DisplayMode.BOTH
                                ? NeoTextStyle.chatBlock(pending.message, translated) : translated);
                    }
                }
                return;
            }
            pending.mode = mode;
            pending.builder = builder;
            pending.ready = true;
            flushReadyChats(mc);
        });
    }

    /** Never hold chat hostage: after {@link #CHAT_MAX_WAIT_MS} the original is shown and the
     *  translation (when it eventually lands) is appended as its own line. */
    private void flushStaleChats(Minecraft mc) {
        if (mc == null || mc.gui == null) return;
        while (!pendingChats.isEmpty()) {
            PendingChat head = pendingChats.peekFirst();
            if (head.ready) {
                flushReadyChats(mc);
                continue;
            }
            if (System.currentTimeMillis() - head.queuedAtMs < CHAT_MAX_WAIT_MS) break;
            pendingChats.removeFirst();
            head.flushedOriginal = true; // stays in pendingChatById for the late translation
            mc.gui.getChat().addMessage(head.mode == DisplayMode.BOTH
                    ? NeoTextStyle.chatBlock(head.message, null) : head.message);
        }
    }

    private void flushReadyChats(Minecraft mc) {
        while (!pendingChats.isEmpty() && pendingChats.peekFirst().ready) {
            PendingChat pending = pendingChats.removeFirst();
            pendingChatById.remove(pending.id);
            addPendingChat(mc, pending);
        }
    }

    private void flushPendingChatOriginals() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.gui == null) return;
            while (!pendingChats.isEmpty()) {
                PendingChat pending = pendingChats.removeFirst();
                pendingChatById.remove(pending.id);
                mc.gui.getChat().addMessage(pending.message);
            }
        });
    }

    private void addPendingChat(Minecraft mc, PendingChat pending) {
        Component translatedLine = (pending.builder != null) ? pending.builder.get() : null;
        if (pending.mode == DisplayMode.TRANSLATION) {
            mc.gui.getChat().addMessage(translatedLine != null ? translatedLine : pending.message);
            return;
        }
        if (pending.mode == DisplayMode.BOTH) {
            mc.gui.getChat().addMessage(NeoTextStyle.chatBlock(pending.message, translatedLine));
            return;
        }
        mc.gui.getChat().addMessage(pending.message);
    }

    /** Builds a single-colour translated chat line: prefix kept verbatim, translation coloured
     *  from the CONTENT's profile and (when unprefixed) re-centred. */
    private static Component chatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                      String translated) {
        net.minecraft.network.chat.Style interactive =
                NeoTextStyle.interactiveStyle(message, contentStart);
        if (hasPrefix) {
            Component styled = NeoTextStyle.withInteractive(
                    NeoTextStyle.styledChatContent(message, contentStart, translated), interactive);
            return Component.empty().append(NeoTextStyle.takePrefix(message, contentStart)).append(styled);
        }
        return NeoTextStyle.withInteractive(
                NeoTextStyle.styledChatContent(message, contentStart, translated), interactive);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemTooltip(ItemTooltipEvent event) {
        if (service == null) return;
        DisplayMode mode = service.tooltipMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return;
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) return;

        int n = lines.size();
        ItemStack stack = event.getItemStack();
        TooltipParagraphPlan plan = tooltipParagraphPlan(
                stack, lines, NeoTextStyle::paragraphRequestText);
        lastTooltipStack = stack;
        lastTooltipParagraphSources = plan.sources();
        service.warmTooltipBatch(plan.sources());
        boolean[] paragraphReady = tooltipParagraphReadiness(lines, plan);
        if (stack != null && !stack.isEmpty()) {
            service.reconcileItemNameWithTooltip(
                    stack.getHoverName().getString(), plan.plainSources());
        }

        Font font = Minecraft.getInstance().font;
        List<Component> out = new ArrayList<>(n);
        boolean completeBothBlock = mode == DisplayMode.BOTH
                && tooltipTranslationRegionReady(lines, plan, paragraphReady);
        List<Component> appended = completeBothBlock ? new ArrayList<>() : null;
        boolean anyTranslated = false;
        int maxLen = 0;
        boolean originalEndsWithSeparator = false;
        for (int i = 0; i < n; ) {
            int end = plan.groupEnd()[i];
            Component line = lines.get(i);
            if (line == null) {
                out.add(line); // keep the list shape other mods may rely on
                if (appended != null) appended.add(Component.empty());
                i = end + 1;
                continue;
            }
            if (mode == DisplayMode.BOTH) {
                for (int k = i; k <= end; k++) {
                    String text = lines.get(k) == null ? "" : lines.get(k).getString();
                    maxLen = Math.max(maxLen, text.length());
                    if (!text.isBlank()) originalEndsWithSeparator = NeoTextStyle.isSeparatorText(text);
                }
            }
            if (!paragraphReady[i]) {
                out.addAll(lines.subList(i, end + 1));
                i = end + 1;
                continue;
            }
            List<Component> group = new ArrayList<>(lines.subList(i, end + 1));
            if (end > i) {
                List<Component> translated = NeoTextStyle.renderTranslatedParagraph(group, service::translateItemLine, font);
                if (translated == null) out.addAll(group);
                else if (mode == DisplayMode.BOTH) {
                    out.addAll(group);
                    if (appended != null) {
                        anyTranslated = true;
                        appended.addAll(translated);
                        for (Component t : translated) maxLen = Math.max(maxLen, t.getString().length());
                    }
                } else out.addAll(translated);
                if (mode == DisplayMode.BOTH && appended != null && translated == null) {
                    appendTooltipShape(appended, group);
                }
                i = end + 1;
                continue;
            }
            Component translated = NeoTextStyle.renderTranslated("tooltip", line, service::translateItemLine);
            if (translated == null) {
                out.add(line);
                if (appended != null) appendTooltipShape(appended, group);
            } else if (mode == DisplayMode.BOTH) {
                out.add(line);
                if (appended != null) {
                    anyTranslated = true;
                    appended.add(translated);
                    maxLen = Math.max(maxLen, translated.getString().length());
                }
            } else {
                out.add(translated);
            }
            i = end + 1;
        }
        if (appended != null && anyTranslated) {
            if (!originalEndsWithSeparator) {
                out.add(NeoTextStyle.separatorLine(maxLen));   // top divider
            }
            out.addAll(appended);
            out.add(NeoTextStyle.separatorLine(maxLen));   // bottom divider
        }
        lines.clear();
        lines.addAll(out);
    }

    private boolean[] tooltipParagraphReadiness(
            List<Component> lines, TooltipParagraphPlan plan) {
        int n = lines.size();
        boolean[] readyByLine = new boolean[n];
        for (int start = 0; start < n; ) {
            Component first = lines.get(start);
            int end = plan.groupEnd()[start];
            String request = plan.requests()[start];
            boolean ready = first == null || first.getString().isBlank()
                    || request == null || service.isTooltipTranslationReady(request);
            for (int i = start; i <= end; i++) readyByLine[i] = ready;
            start = end + 1;
        }
        return readyByLine;
    }

    private static boolean tooltipTranslationRegionReady(
            List<Component> lines, TooltipParagraphPlan plan, boolean[] readyByLine) {
        for (int start = 0; start < lines.size(); start = plan.groupEnd()[start] + 1) {
            Component first = lines.get(start);
            if (first != null && !first.getString().isBlank() && !readyByLine[start]) return false;
        }
        return true;
    }

    private static void appendTooltipShape(List<Component> out, List<Component> paragraph) {
        for (Component line : paragraph) out.add(line == null ? Component.empty() : line);
    }

    private static TooltipParagraphPlan tooltipParagraphPlan(
            ItemStack stack, List<Component> lines,
            java.util.function.Function<List<Component>, String> paragraphRequestText) {
        int n = lines.size();
        int[] groupEnd = new int[n];
        for (int i = 0; i < n; i++) groupEnd[i] = i;
        int bodyStart = hasVerifiedItemTitle(stack, lines) ? 1 : 0;
        List<String> paragraphLines = new ArrayList<>(n - bodyStart);
        for (int i = bodyStart; i < n; i++) {
            Component line = lines.get(i);
            paragraphLines.add(line == null ? null : line.getString());
        }
        for (com.dragonmeow.mctranslator.translate.ParagraphModel.Range range
                : com.dragonmeow.mctranslator.translate.ParagraphModel.ranges(paragraphLines)) {
            groupEnd[bodyStart + range.start()] = bodyStart + range.end();
        }
        String[] requests = new String[n];
        List<String> sources = new ArrayList<>(n);
        List<String> plainSources = new ArrayList<>(n);
        for (Component line : lines) if (line != null) plainSources.add(line.getString());
        for (int start = 0; start < n; start = groupEnd[start] + 1) {
            Component first = lines.get(start);
            if (first == null || first.getString().isBlank()) {
                sources.add("");
                continue;
            }
            String request = paragraphRequestText.apply(
                    lines.subList(start, groupEnd[start] + 1));
            requests[start] = request;
            sources.add(request);
        }
        return new TooltipParagraphPlan(groupEnd, requests,
                List.copyOf(sources), List.copyOf(plainSources));
    }

    private record TooltipParagraphPlan(
            int[] groupEnd, String[] requests,
            List<String> sources, List<String> plainSources) {
    }

    private static boolean hasVerifiedItemTitle(ItemStack stack, List<Component> lines) {
        if (stack == null || stack.isEmpty() || lines.isEmpty() || lines.get(0) == null) return false;
        String first = com.dragonmeow.mctranslator.translate.TextFilter
                .stripFormatting(lines.get(0).getString()).strip();
        String name = com.dragonmeow.mctranslator.translate.TextFilter
                .stripFormatting(stack.getHoverName().getString()).strip();
        return !name.isEmpty() && first.equals(name);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (modeKey != null) {
            while (modeKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) mc.setScreen(new TranslationConfigScreen(mc.screen));
            }
        }
        if (retranslateKey != null && service != null) {
            // In-world (no screen open): re-translate the held item. The container-screen
            // case is handled by onScreenKeyPressed (key binds don't tick while a screen is open).
            while (retranslateKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) retranslateItem(mc.player.getMainHandItem());
            }
        }
        if (toggleKey != null && service != null) {
            while (toggleKey.consumeClick()) flipShowOriginal();
        }
        syncGameLanguage(Minecraft.getInstance());
        // Flush the coalesced per-frame translation buffer once per tick: a screen full of
        // text becomes ONE batched request rather than dozens (fewer AI / Google requests).
        refreshOnlineNames(Minecraft.getInstance());
        if (service != null) service.flushBatches();
        expireStaleBlock();
        flushStaleChats(Minecraft.getInstance());
        // R12 (user clarification of R10): the OPEN container is "the current page" — its
        // slots pre-translate; queued batches are kept even if the screen closes ("排隊項
        // 不要丟棄，有看到的都加入排隊，沒看到的先不管"). Only never-seen text stays unbought.
        warmOwnedItems(Minecraft.getInstance());
        warmOpenContainerItems(Minecraft.getInstance());
    }

    @SubscribeEvent
    public void onScreenKeyPressed(net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (service == null) return;
        // Key binds don't tick while a screen is showing, so handle our screen hotkeys here.
        if (toggleKey != null && toggleKey.matches(event.getKeyCode(), event.getScanCode())) {
            flipShowOriginal();
            return;
        }
        if (retranslateKey != null && retranslateKey.matches(event.getKeyCode(), event.getScanCode())) {
            // Re-translate the item under the mouse in a container screen.
            if (event.getScreen() instanceof AbstractContainerScreen<?> screen
                    && screen instanceof AbstractContainerScreenAccessor accessor) {
                Slot slot = accessor.mctranslator$hoveredSlot();
                if (slot != null && slot.hasItem()) {
                    retranslateItem(slot.getItem());
                }
            }
            return;
        }
        if (screenScanKey != null && screenScanKey.matches(event.getKeyCode(), event.getScanCode())) {
            // Translate every button/option of the open screen (e.g. a quest book).
            scanAndTranslateScreen(event.getScreen());
        }
    }

    /**
     * Capture and translate the text of every button / option widget on {@code screen}
     * (recursing into nested widget containers — "含分支"), replacing each widget's label
     * with its translation in place. Async &amp; non-blocking; an explicit user action, so
     * it ignores the per-surface on/off toggles. Best-effort: catches standard
     * {@link net.minecraft.client.gui.components.AbstractWidget} labels (vanilla-style
     * buttons/options); screens that draw raw text without widgets are not covered.
     */
    private void scanAndTranslateScreen(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null || service == null
                || screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
        List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
        collectWidgets(screen.children(), widgets, 0);
        int requested = 0;
        for (net.minecraft.client.gui.components.AbstractWidget widget : widgets) {
            Component raw = widget.getMessage();
            if (raw == null) continue;
            final Component source = NeoTextStyle.resolveLegacyCodes(raw);
            List<String> requests = NeoTextStyle.requestLines(source);
            for (String request : requests) {
                service.requestScreenTextAsync(request, translated -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;
                    Component ready = NeoTextStyle.renderTranslated(
                            "screenScan", source, service::translateScreenScanText);
                    if (ready != null) mc.execute(() -> widget.setMessage(ready));
                });
            }
            requested += requests.size();
        }
        status("擷取介面文字翻譯中…（" + requested + " 項）");
    }

    /** Depth-bounded recursive collect of all widgets, descending into nested containers. */
    private static void collectWidgets(
            List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children,
            List<net.minecraft.client.gui.components.AbstractWidget> out, int depth) {
        if (children == null || depth > 8) return;
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                out.add(w);
            }
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                collectWidgets(c.children(), out, depth + 1);
            }
        }
    }

    /**
     * Warm the names of every item in the currently-open container/inventory screen, so
     * they are translated and ready the instant the player hovers (rather than only on
     * hover, popping in a frame later). Each distinct item name is warmed once per screen;
     * the per-tick scan only adds newly-arrived items (servers sync container contents a
     * tick or two after the screen opens), so it is cheap. The full tooltip (lore) still
     * warms on hover via {@link #onItemTooltip}.
     */
    private void warmOpenContainerItems(Minecraft mc) {
        if (mc == null || service == null) return;
        if (service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return; // nothing to warm
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            // Left the container: reset so the next one warms fresh.
            if (lastContainerScreen != null) {
                lastContainerScreen = null;
                warmedContainerNames.clear();
            }
            return;
        }
        if (screen != lastContainerScreen) {
            lastContainerScreen = screen;
            warmedContainerNames.clear();
        }
        List<String> newNames = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot == null || !slot.hasItem()) continue;
            String name = slot.getItem().getHoverName().getString();
            if (name != null && !name.isBlank() && warmedContainerNames.add(name)) {
                newNames.add(name);
            }
        }
        if (!newNames.isEmpty()) service.warmNamesBatch(newNames);
    }

    /** Warm only names of items the player actually owns; lore remains hover-driven. */
    private void warmOwnedItems(Minecraft mc) {
        if (mc == null || service == null) return;
        if (mc.player == null) {
            warmedOwnedItemNames.clear();
            return;
        }
        if (service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return;
        List<String> newNames = new ArrayList<>();
        for (Slot slot : mc.player.inventoryMenu.slots) {
            if (slot == null || !slot.hasItem()) continue;
            String name = slot.getItem().getHoverName().getString();
            if (name != null && !name.isBlank() && warmedOwnedItemNames.add(name)) {
                newNames.add(name);
            }
        }
        if (!newNames.isEmpty()) service.warmNamesBatch(newNames);
    }

    /** Evict + re-translate one item's tooltip lines (the "re-translate pointed item" hotkey). */
    private void retranslateItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || service == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        List<String> sources = lastTooltipStack == stack ? lastTooltipParagraphSources : null;
        if (sources == null) {
            List<Component> lines;
            try {
                lines = stack.getTooltipLines(mc.player, TooltipFlag.Default.NORMAL);
            } catch (RuntimeException e) {
                return;
            }
            TooltipParagraphPlan plan = tooltipParagraphPlan(
                    stack, lines, NeoTextStyle::paragraphRequestText);
            sources = plan.sources();
        }
        service.retranslate(sources);
        NeoTextStyle.clearRenderMemo();
        status("重新翻譯：" + stack.getHoverName().getString());
    }

    /** Background AI connection test used by the AI settings screen; result delivered on the client thread. */
    public static void testAi(String baseUrl, String model, List<String> keys, java.util.function.Consumer<String> onResult) {
        if (transport == null) {
            onResult.accept("§c尚未初始化");
            return;
        }
        Thread t = new Thread(() -> {
            String msg;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(transport, () -> new AiSettings(baseUrl, model, keys));
                String out = ai.translate("Hello, world", "zh-TW").translatedText();
                msg = "§a成功：Hello, world → " + out;
            } catch (Exception e) {
                msg = "§c失敗：" + e.getMessage();
            }
            final String result = msg;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> onResult.accept(result));
            else onResult.accept(result);
        }, "mctranslator-aitest");
        t.setDaemon(true);
        t.start();
    }

    private void status(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal("[翻譯] " + msg));
        }
    }
}
