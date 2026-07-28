package com.dragonmeow.mctranslator.neoforge26;

import com.dragonmeow.mctranslator.cache.DynamicNamespacedStore;
import com.dragonmeow.mctranslator.cache.LanguageFileStore;
import com.dragonmeow.mctranslator.cache.NamespacedStore;
import com.dragonmeow.mctranslator.cache.PersistentStore;
import com.dragonmeow.mctranslator.cache.ProviderLanguageFileStore;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.MachineTranslationProvider;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.service.TranslationService;
import com.dragonmeow.mctranslator.translate.AiSettings;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient;
import com.dragonmeow.mctranslator.translate.CodexAppServerTransport;
import com.dragonmeow.mctranslator.translate.OpenAiTranslator;
import com.dragonmeow.mctranslator.translate.ParagraphModel;
import com.dragonmeow.mctranslator.translate.RequestPacer;
import com.dragonmeow.mctranslator.translate.SessionTokenUsage;
import com.dragonmeow.mctranslator.translate.SwitchingAiTranslator;
import com.dragonmeow.mctranslator.translate.SwitchingMachineTranslator;
import com.dragonmeow.mctranslator.translate.TranslationDebugLog;
import com.dragonmeow.mctranslator.translate.TextFilter;
import com.dragonmeow.mctranslator.translate.UrlHttpTransport;

import com.dragonmeow.mctranslator.neoforge26.mixin.AbstractContainerScreenAccessor;
import com.dragonmeow.mctranslator.neoforge26.mixin.ChatComponentAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;







@Mod(value = MctranslatorNeoForge26.MOD_ID, dist = Dist.CLIENT)
public final class MctranslatorNeoForge26 {

    public static final String MOD_ID = "mctranslator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static TranslatorConfig config;
    private static TranslationService service;
    private static TranslationDebugLog debugLog;
    private static Path configPath;
    private static UrlHttpTransport transport;
    private static CodexAppServerClient codexClient;
    private static CodexAppServerTransport codexTransport;
    private static final SessionTokenUsage tokenUsage = new SessionTokenUsage();

    private static KeyMapping modeKey;
    private static KeyMapping retranslateKey;
    private static KeyMapping screenScanKey;
    private static KeyMapping toggleKey;
    private long actionBarSequence;

    private static final java.util.Map<Object, String> FTB_PENDING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final ThreadLocal<Integer> tooltipProbeDepth =
            ThreadLocal.withInitial(() -> 0);
    /** True only while NeoForge is rendering the current screen. */
    private static final ThreadLocal<java.util.ArrayDeque<net.minecraft.client.gui.screens.Screen>>
            SCREEN_RENDER_STACK = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    private net.minecraft.client.gui.screens.Screen lastContainerScreen;
    private final java.util.Set<String> warmedContainerNames = new java.util.HashSet<>();
    /** Names queued from the local player's hotbar, backpack, armour and off-hand. */
    /** Last late tooltip snapshot, including lines appended by other tooltip callbacks. */
    private ItemStack lastTooltipStack;
    private List<String> lastTooltipParagraphSources;
    private net.minecraft.client.gui.screens.Screen lastTooltipScreen;
    private long lastTooltipAtMs;

    /** TAB-listed player names, refreshed once per second on the tick thread; read by the
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
        for (var info : mc.getConnection().getListedOnlinePlayers()) {
            String name = info == null || info.getProfile() == null
                    ? null : info.getProfile().name();
            if (name != null && PLAYER_NAME.matcher(name).matches()) names.add(name);
        }
        onlineNames = names;
    }

    private static final java.util.regex.Pattern PLAYER_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final java.util.ArrayDeque<PendingChat> pendingChats = new java.util.ArrayDeque<>();
    private final java.util.Map<Long, PendingChat> pendingChatById = new java.util.LinkedHashMap<>();
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
        Component displayedMessage;
        int translationCompletions;

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
     *  the line was absorbed into a block (the event must be cancelled). */
    private boolean handleAnnouncementBlock(Component message, boolean isSystem, DisplayMode mode, String full) {
        boolean isSep = Neo26TextStyle.isSeparatorText(full);
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
        int first = !block.lines.isEmpty() && Neo26TextStyle.isSeparatorText(block.lines.get(0).getString()) ? 1 : 0;
        int end = block.lines.size();
        if (end > first && Neo26TextStyle.isSeparatorText(block.lines.get(end - 1).getString())) end--;

        List<String> visible = new ArrayList<>(Math.max(0, end - first));
        List<Neo26TextStyle.ChatLinePlan> prepared = new ArrayList<>(Math.max(0, end - first));
        for (int i = first; i < end; i++) {
            Neo26TextStyle.ChatLinePlan plan = Neo26TextStyle.prepareChatLine(block.lines.get(i));
            prepared.add(plan);
            visible.add(plan.content());
        }
        List<Integer> starts = new ArrayList<>();
        List<List<Neo26TextStyle.ChatLinePlan>> groups = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (ParagraphModel.Range range : ParagraphModel.ranges(visible)) {
            if (range.size() == 1 && ParagraphModel.isBlank(visible.get(range.start()))) continue;
            List<Neo26TextStyle.ChatLinePlan> plans = new ArrayList<>(range.size());
            List<String> rows = new ArrayList<>(range.size());
            boolean wanted = false;
            for (int row = range.start(); row <= range.end(); row++) {
                Neo26TextStyle.ChatLinePlan plan = prepared.get(row);
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
            List<Neo26TextStyle.ChatLinePlan> plans = groups.get(paragraph);
            String request = requests.get(paragraph);
            service.translateChatAsync(request, translated -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;
                mc.execute(() -> {
                    List<String> rows = validatedParagraphRows(translated, plans.size());
                    if (!rows.isEmpty()) {
                        List<Component> paragraphLines = new ArrayList<>(plans.size());
                        for (int row = 0; row < plans.size(); row++) {
                            Component rebuilt = Neo26TextStyle.rebuildChatLine(plans.get(row), rows.get(row));
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
        if (Neo26TextStyle.isSeparatorText(full)) {
            insideServerFrame = !insideServerFrame;
            frameOpenedAtMs = now;
            return false;
        }
        return insideServerFrame;
    }


    public static KeyMapping retranslateKeyMapping() {
        return retranslateKey;
    }

    
    public static TranslationService service() {
        return service;
    }

    
    public static TranslatorConfig config() {
        return config;
    }

    public static CodexAppServerClient codexClient() {
        return codexClient;
    }

    public static SessionTokenUsage.Snapshot tokenUsageSnapshot() {
        return tokenUsage.snapshot();
    }

    public static TranslationDebugLog debugLog() { return debugLog; }
    public static void clearDebugLog() { if (debugLog != null) debugLog.clear(); }

    




    public static Component screenText(Component c) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return c;
        TranslationService s = service;
        if (s == null || c == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return c;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) return c;
        Component t = Neo26TextStyle.renderTranslated("screenText", c, s::translateScreenText);
        return t != null ? t : c;
    }

    /** Translate an optional FTB Library TextField before FTB measures and wraps it. */
    public static Component ftbText(Object widget, Component source) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return source;
        TranslationService s = service;
        if (widget == null || source == null || s == null
                || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return source;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc) && !ftbWidgetOnCurrentScreen(widget, mc)) return source;
        Component resolved = Neo26TextStyle.resolveLegacyCodes(source);
        Component rendered = Neo26TextStyle.renderTranslated("ftb", resolved, s::translateScreenText);
        if (rendered != null) {
            FTB_PENDING.remove(widget);
            return rendered;
        }
        List<String> requests = Neo26TextStyle.requestLines(resolved).stream()
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
                Component ready = Neo26TextStyle.renderTranslated(
                        "ftb", resolved, s::translateScreenText);
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    Component display = ready != null ? ready : resolved;
                    client.execute(() -> applyFtbText(widget, display));
                }
            });
        }
        return source;
    }

    /** FTB populates TextField content while the new screen is being initialized,
     * before the first Render.Pre event. Accept that call only when the widget's own
     * GUI is the BaseScreen wrapped by Minecraft's current ScreenWrapper;
     * unrelated/background widgets remain outside the translation scope. */
    private static boolean ftbWidgetOnCurrentScreen(Object widget, Minecraft mc) {
        if (widget == null || mc == null || mc.gui.screen() == null) return false;
        try {
            java.lang.reflect.Method getter = widget.getClass().getMethod("getGui");
            Object widgetGui = getter.invoke(widget);
            if (widgetGui == mc.gui.screen()) return true;
            java.lang.reflect.Method screenGetter = mc.gui.screen().getClass().getMethod("getGui");
            return screenGetter.invoke(mc.gui.screen()) == widgetGui;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void applyFtbText(Object widget, Component translated) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui.screen() == null
                || !mc.gui.screen().getClass().getName().startsWith("dev.ftb.")) return;
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
                Object gui = widget.getClass().getMethod("getGui").invoke(widget);
                if (gui != null) gui.getClass().getMethod("refreshWidgets").invoke(gui);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.debug("Unable to reflow translated FTB text field", error);
        }
    }

    
    public static String screenText(String str) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return str;
        TranslationService s = service;
        if (s == null || str == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return str;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) return str;
        if (str.indexOf('\n') >= 0 || str.indexOf('\r') >= 0) {
            String normalized = str.replace("\r\n", "\n").replace('\r', '\n');
            Component translated = Neo26TextStyle.renderTranslated(
                    "screenText", Component.literal(normalized), s::translateScreenText);
            return translated != null ? translated.getString() : str;
        }
        TranslationDecision d = s.translateScreenText(str);
        return d.changed() ? d.translated() : str;
    }

    





    public static net.minecraft.util.FormattedCharSequence screenText(net.minecraft.util.FormattedCharSequence fcs) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return fcs;
        TranslationService s = service;
        if (s == null || fcs == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return fcs;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) return fcs;
        if (mc.gui.screen().getClass().getName().startsWith("dev.ftb.")) return fcs;
        Component source = Neo26TextStyle.toComponent(fcs);
        Component styled = Neo26TextStyle.renderTranslated("screenTextFcs", source, s::translateScreenText);
        if (styled == null) return fcs;
        Font font = mc.font;
        if (font != null) {
            int originalWidth = font.width(fcs);
            int budget = Math.max(originalWidth + 24, (int) (originalWidth * 1.25f));
            if (font.width(styled) > budget) return fcs;
        }
        return styled.getVisualOrderText();
    }

    





    public static net.minecraft.network.chat.FormattedText screenText(net.minecraft.network.chat.FormattedText text) {
        if (com.dragonmeow.mctranslator.translate.InternalRenderGuard.active()) return text;
        TranslationService s = service;
        if (s == null || text == null || s.screenTextMode() == DisplayMode.ORIGINAL_ONLY) return text;
        Minecraft mc = Minecraft.getInstance();
        if (!renderingCurrentScreen(mc)
                || mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) return text;
        if (mc.gui.screen().getClass().getName().startsWith("dev.ftb.")) return text;
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen) return text;
        Component source = Neo26TextStyle.toComponent(text);
        Component translated = Neo26TextStyle.renderTranslated("screenTextBlock", source, s::translateScreenText);
        return translated == null ? text : translated;
    }

    private static boolean renderingCurrentScreen(Minecraft mc) {
        return mc != null && mc.gui.screen() != null
                && screenTranslationAllowed(mc.gui.screen())
                && SCREEN_RENDER_STACK.get().peek() == mc.gui.screen();
    }

    private static boolean screenTranslationAllowed(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null
                || screen.getClass().getName().startsWith("com.dragonmeow.mctranslator.")) return false;
        Component title = screen.getTitle();
        String key = title != null
                && title.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
                ? translatable.getKey() : null;
        return com.dragonmeow.mctranslator.translate.ScreenTranslationPolicy.allowsTranslation(key);
    }

    

    public static void saveConfig() {
        if (config != null && configPath != null) {
            config.save(configPath);
        }
        Neo26TextStyle.clearRenderMemo();
    }

    public MctranslatorNeoForge26(IEventBus modBus, ModContainer container) {
        configPath = FMLPaths.CONFIGDIR.get().resolve(MOD_ID + ".json");
        config = TranslatorConfig.load(configPath);

        int workers = Math.max(1, config.workerThreads);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "mctranslator-worker");
            t.setDaemon(true);
            return t;
        };
        ExecutorService executor = new com.dragonmeow.mctranslator.translate.PriorityTranslationExecutor(
                workers, threadFactory);

        transport = new UrlHttpTransport(Duration.ofMillis(config.httpTimeoutMs));
        SwitchingMachineTranslator google = new SwitchingMachineTranslator(
                transport, () -> config.sourceLang, () -> config.machineTranslationProvider,
                new RequestPacer(() -> config.requestCooldownMs));
        OpenAiTranslator apiAi = new OpenAiTranslator(transport,
                () -> new AiSettings(config.aiBaseUrl, config.aiModel, config.aiApiKeys, config.aiGlossary),
                new RequestPacer(() -> config.requestCooldownMs));
        apiAi.setTokenUsage(tokenUsage);
        Path codexRoot = configPath.getParent();
        codexClient = new CodexAppServerClient(
                codexRoot.resolve(MOD_ID + "-codex-home"),
                codexRoot.resolve(MOD_ID + "-codex-workspace"));
        codexClient.setTokenUsage(tokenUsage);
        codexTransport = new CodexAppServerTransport(codexClient,
                () -> config.codexReasoningEffort);
        OpenAiTranslator codexAi = new OpenAiTranslator(codexTransport,
                () -> new AiSettings("codex://app-server", config.codexModel,
                        java.util.Collections.emptyList(), config.aiGlossary),
                RequestPacer.disabled());
        SwitchingAiTranslator ai = new SwitchingAiTranslator(
                apiAi, codexAi, () -> config.aiUseCodex);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CodexAppServerClient client = codexClient;
            if (client != null) client.close();
        }, "mctranslator-codex-shutdown"));
        
        PersistentStore googleStore = new ProviderLanguageFileStore(
                FMLPaths.CONFIGDIR.get(), MOD_ID + "-cache", config.targetLang,
                () -> config.machineTranslationProvider);
        PersistentStore aiStore = new LanguageFileStore(
                FMLPaths.CONFIGDIR.get(), MOD_ID + "-ai-cache", config.targetLang);
        PersistentStore failureStore = new LanguageFileStore(
                FMLPaths.CONFIGDIR.get(), MOD_ID + "-failures", config.targetLang);
        
        TranslationCache cache = new TranslationCache(google, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, googleStore);
        TranslationCache aiCache = new TranslationCache(ai, config.targetLang, executor,
                config.cacheMaxSize, config.failureBackoffMs, System::currentTimeMillis, aiStore);
        cache.setFailureStore(new DynamicNamespacedStore(failureStore, () -> {
            String provider = MachineTranslationProvider.normalize(config.machineTranslationProvider);
            return MachineTranslationProvider.GOOGLE.id().equals(provider)
                    ? "gt" : "gt-" + provider;
        }));
        aiCache.setFailureStore(new NamespacedStore(failureStore, "ai"));
        aiCache.setProvisionalStore(googleStore);
        debugLog = new TranslationDebugLog(() -> config != null && config.debugTranslationOverlay);
        cache.setDebugLog("GT", debugLog);
        aiCache.setDebugLog("AI", debugLog);
        aiCache.setProvisionalRetryGate(() ->
                (config.aiUseCodex
                        ? codexClient != null && codexClient.isSignedInCached()
                                && config.codexModel != null && !config.codexModel.isBlank()
                        : config.aiApiKeys != null && !config.aiApiKeys.isEmpty())
                        && !ai.isRateLimited());
        service = new TranslationService(config, cache, aiCache);
        service.setTargetLangChangeListener(this::onTargetLanguageChanged);
        service.setBatchWindowMs(() -> config.batchWindowMs);
        service.setItemSourceLanguage(() -> {
            if (config.sourceLang != null && !config.sourceLang.isBlank()
                    && !"auto".equalsIgnoreCase(config.sourceLang)) return config.sourceLang;
            Minecraft mc = Minecraft.getInstance();
            return mc == null || mc.options == null ? null : mc.options.languageCode;
        });
        service.setProtectedNames(() -> onlineNames);

        modBus.addListener(this::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("[{}] (NeoForge) initialized (target={}, chat={}, tooltip={})",
                MOD_ID, config.targetLang, config.chatMode, config.tooltipMode);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        
        
        modeKey = new KeyMapping("key.mctranslator.mode", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, KeyMapping.Category.MISC);
        event.register(modeKey);
        
        
        retranslateKey = new KeyMapping("key.mctranslator.retranslate", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R, KeyMapping.Category.MISC);
        event.register(retranslateKey);
        
        
        
        screenScanKey = new KeyMapping("key.mctranslator.screenscan", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P, KeyMapping.Category.MISC);
        event.register(screenScanKey);
        
        toggleKey = new KeyMapping("key.mctranslator.toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G, KeyMapping.Category.MISC);
        event.register(toggleKey);
    }

    
    private void flipShowOriginal() {
        if (service == null) return;
        boolean originalsNow = service.toggleShowOriginal();
        Neo26TextStyle.clearRenderMemo();
        if (originalsNow) flushPendingChatOriginals();
        status(Component.translatable(originalsNow ? "message.mctranslator.show_original" : "message.mctranslator.show_translation").getString());
    }

    
    private void syncGameLanguage(Minecraft mc) {
        if (service == null || config == null || !config.followGameLanguage || mc == null || mc.options == null) return;
        String desired = mapGameLang(mc.options.languageCode);
        if (!desired.equals(config.targetLang)) {
            service.setTargetLang(desired);
        }
    }

    private void onTargetLanguageChanged() {
        Neo26TextStyle.clearRenderMemo();
        lastContainerScreen = null;
        warmedContainerNames.clear();
        lastTooltipStack = null;
        lastTooltipParagraphSources = null;
        lastTooltipScreen = null;
        lastTooltipAtMs = 0L;
        synchronized (FTB_PENDING) { FTB_PENDING.clear(); }
        refreshCurrentFtbScreen();
    }

    private static void refreshCurrentFtbScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui.screen() == null) return;
        try {
            Object gui = mc.gui.screen().getClass().getMethod("getGui").invoke(mc.gui.screen());
            if (gui != null) gui.getClass().getMethod("refreshWidgets").invoke(gui);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    
    static String mapGameLang(String gameLang) {
        return com.dragonmeow.mctranslator.config.TranslationLanguages.fromMinecraftCode(gameLang);
    }

    
    public static KeyMapping screenScanKeyMapping() {
        return screenScanKey;
    }

    public static KeyMapping toggleKeyMapping() {
        return toggleKey;
    }

    public static KeyMapping modeKeyMapping() {
        return modeKey;
    }

    

    /**
     * Name-tag entry (R7/R15 guard, string fallback): a REAL online player — one the TAB
     * player list shows, i.e. in {@code getListedOnlinePlayers()} — keeps the ORIGINAL name
     * tag (player IDs are names, not text; "最偉大的迪加" must never happen). 26.2's
     * The mixin passes the render state, so Avatar entities keep their complete original
     * component. Whole-token matching of listed names remains as a fallback for servers
     * that render player tags through ArmorStand/TextDisplay entities.
     */
    public static Component nameTag(
            net.minecraft.client.renderer.entity.state.EntityRenderState state, Component c) {
        if (c == null) return null;
        // Player render states are authoritative: return the whole original component
        // before any translation memo/cache can expose a mistaken player-ID translation.
        if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState) return c;
        // Real-player guard runs BEFORE any memo/cache (translateNameTag holds the memo).
        if (nameTagMatchesListedPlayer(c.getString())) return c;
        return translateNameTag(c);
    }

    public static Component nameTag(Component c) {
        return nameTag(null, c);
    }

    private static Component translateNameTag(Component c) {
        TranslationService s = service;
        if (s == null || c == null) return c;
        Component t = Neo26TextStyle.renderTranslated("nameTag", c, s::translateUi);
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
            String name = (info == null || info.getProfile() == null) ? null : info.getProfile().name();
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
        Component source = Neo26TextStyle.resolveLegacyCodes(message);
        DisplayMode mode = service.actionBarMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return false;
        Neo26TextStyle.MarkedChat marked = Neo26TextStyle.markChatContent(source, 0);
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
                                      Neo26TextStyle.MarkedChat marked, DisplayMode mode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || mc.gui.hud == null
                || translated == null || mode == DisplayMode.ORIGINAL_ONLY) return;
        Component rich = Neo26TextStyle.rebuildRich(source, translated, marked);
        Component shown = mode == DisplayMode.BOTH
                ? source.copy().append(Component.literal("　")).append(rich)
                : rich;
        mc.gui.hud.setOverlayMessage(shown, false);
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
        Component message = Neo26TextStyle.resolveLegacyCodes(event.getMessage());
        if (message == null) return;
        List<Component> hardLines = Neo26TextStyle.splitStyledLines(message);
        if (hardLines.size() > 1) {
            boolean isSystem = event instanceof ClientChatReceivedEvent.System;
            boolean canceled;
            if (isSystem && (activeBlock != null || Neo26TextStyle.isSeparatorText(hardLines.get(0).getString()))) {
                List<Component> remainder = new ArrayList<>();
                for (Component line : hardLines) {
                    if (!handleAnnouncementBlock(line, true, mode, line.getString())) remainder.add(line);
                }
                canceled = true;
                if (!remainder.isEmpty()) {
                    translateHardLineMessage(Neo26TextStyle.joinStyledLines(remainder), mode, remainder);
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
            if (Neo26TextStyle.isSeparatorText(full)) {
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
        
        
        event.setCanceled(true);

        
        
        PendingChat pending = queueChat(message);
        pending.mode = mode;
        pending.framedByServer = framedByServer;



        Neo26TextStyle.MarkedChat marked = Neo26TextStyle.markChatContent(message, cs);
        if (marked.marked()) {
            // Word-level colour preservation: wrap each style run in an invisible ⟦CS#⟧
            // marker, translate the WHOLE line in one request (better grammar, fewer
            // requests than per-segment), then map every marker region back to its style
            // — a red word stays red on its translated word. Click/hover ride along on
            // the segment styles.
            service.translateChatAsync(marked.text(), translated ->
                    completeChat(pending.id, mode, translated == null ? null : () -> {
                        Font font = Minecraft.getInstance().font;
                        var core = Neo26TextStyle.markedChat(message, cs, translated, marked);
                        if (prefix) {
                            return Component.empty()
                                    .append(Neo26TextStyle.takePrefix(message, cs))
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
        List<Neo26TextStyle.ChatLinePlan> plans = new ArrayList<>(hardLines.size());
        List<String> visible = new ArrayList<>(hardLines.size());
        for (int i = 0; i < hardLines.size(); i++) {
            Neo26TextStyle.ChatLinePlan plan = Neo26TextStyle.prepareChatLine(hardLines.get(i));
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
                Neo26TextStyle.ChatLinePlan plan = plans.get(row);
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
                // A style-fallback paragraph arrives as ONE marked string. Strip the
                // prefix before the row split, then re-mark EVERY row: otherwise only
                // row 0 carries the prefix and the remaining rows fail
                // validMarkedResponse and silently fall back to the original text.
                boolean styleFallback = TextFilter.isStyleFallback(translated);
                String semantic = TextFilter.stripStyleFallback(translated);
                List<String> rows = validatedParagraphRows(semantic, range.size());
                if (!rows.isEmpty()) {
                    List<Component> paragraphLines = new ArrayList<>(range.size());
                    for (int row = range.start(); row <= range.end(); row++) {
                        String rowText = rows.get(row - range.start());
                        // Only marked rows understand the prefix (markedChat strips it);
                        // an unmarked row would render the NUL prefix as literal text.
                        if (styleFallback && plans.get(row).marked().marked()) {
                            rowText = TextFilter.markStyleFallback(rowText);
                        }
                        paragraphLines.add(Neo26TextStyle.rebuildChatLine(
                                plans.get(row), rowText));
                    }
                    for (int row = range.start(); row <= range.end(); row++) {
                        rebuilt.set(row, paragraphLines.get(row - range.start()));
                    }
                }
                // <= 0, not == 0: an exact-style recovery waiter may call back a second
                // time (approx fallback first, exact projection later), driving the
                // counter negative — each late arrival must still replace the shown row.
                // Known limit: with several paragraphs each arriving late more than
                // once, replaces after the 2nd completion are dropped because
                // completeChat retires the entry (translationCompletions >= 2); a
                // multi-paragraph CS chat message is rare enough to accept that.
                if (awaiting.decrementAndGet() <= 0) {
                    completeChat(pending.id, mode, () -> {
                        List<Component> ready = new ArrayList<>(rebuilt.length());
                        for (int row = 0; row < rebuilt.length(); row++) ready.add(rebuilt.get(row));
                        return Neo26TextStyle.joinStyledLines(ready);
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
        if (pendingChatById.size() >= 512) {
            java.util.Iterator<PendingChat> old = pendingChatById.values().iterator();
            while (old.hasNext()) {
                if (old.next().displayedMessage != null) { old.remove(); break; }
            }
        }
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
            if (pending.displayedMessage != null) {
                Component replacement = pendingChatDisplay(pending, mode, builder);
                if (replaceChatMessage(mc.gui.hud.getChat(), pending.displayedMessage, replacement)) {
                    pending.displayedMessage = replacement;
                }
                pending.translationCompletions++;
                if (pending.translationCompletions >= 2 || !config.aiChat) pendingChatById.remove(id);
                return;
            }
            pending.mode = mode;
            pending.builder = builder;
            pending.ready = true;
            pending.translationCompletions++;
            flushReadyChats(mc);
        });
    }

    /** Never hold chat hostage: after {@link #CHAT_MAX_WAIT_MS} the original is shown and the
     *  translation (when it eventually lands) is appended as its own line. */
    private void flushStaleChats(Minecraft mc) {
        if (mc == null || mc.gui == null) return;
        long now = System.currentTimeMillis();
        pendingChatById.values().removeIf(p -> p.displayedMessage != null
                && now - p.queuedAtMs > 5 * 60_000L);
        while (!pendingChats.isEmpty()) {
            PendingChat head = pendingChats.peekFirst();
            if (head.ready) {
                flushReadyChats(mc);
                continue;
            }
            if (System.currentTimeMillis() - head.queuedAtMs < CHAT_MAX_WAIT_MS) break;
            pendingChats.removeFirst();
            head.flushedOriginal = true; // stays in pendingChatById for the late translation
            Component shown = head.mode == DisplayMode.BOTH
                    ? Neo26TextStyle.chatBlock(head.message, null) : head.message;
            head.displayedMessage = shown;
            mc.gui.hud.getChat().addClientSystemMessage(shown);
        }
    }

    private void flushReadyChats(Minecraft mc) {
        while (!pendingChats.isEmpty() && pendingChats.peekFirst().ready) {
            PendingChat pending = pendingChats.removeFirst();
            addPendingChat(mc, pending);
            if (!config.aiChat) pendingChatById.remove(pending.id);
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
                mc.gui.hud.getChat().addClientSystemMessage(pending.message);
            }
            pendingChatById.clear();
        });
    }

    private void addPendingChat(Minecraft mc, PendingChat pending) {
        Component shown = pendingChatDisplay(pending, pending.mode, pending.builder);
        pending.displayedMessage = shown;
        mc.gui.hud.getChat().addClientSystemMessage(shown);
    }

    private static Component pendingChatDisplay(PendingChat pending, DisplayMode mode,
                                                java.util.function.Supplier<Component> builder) {
        Component translated = builder == null ? null : builder.get();
        if (mode == DisplayMode.TRANSLATION) return translated != null ? translated : pending.message;
        if (mode == DisplayMode.BOTH) return Neo26TextStyle.chatBlock(pending.message, translated);
        return pending.message;
    }

    private static boolean replaceChatMessage(net.minecraft.client.gui.components.ChatComponent chat,
                                              Component previous, Component replacement) {
        try {
            java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage> messages =
                    ((ChatComponentAccessor) (Object) chat).mctranslator$getAllMessages();
            for (int i = 0; i < messages.size(); i++) {
                net.minecraft.client.multiplayer.chat.GuiMessage old = messages.get(i);
                if (old.content() != previous && !old.content().equals(previous)) continue;
                messages.set(i, new net.minecraft.client.multiplayer.chat.GuiMessage(
                        old.addedTime(), replacement, old.signature(), old.source(), old.tag()));
                chat.rescaleChat();
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }
    

    private static Component chatLine(Font font, Component message, boolean hasPrefix, int contentStart,
                                      String translated) {
        net.minecraft.network.chat.Style interactive =
                Neo26TextStyle.interactiveStyle(message, contentStart);
        if (hasPrefix) {
            Component styled = Neo26TextStyle.withInteractive(
                    Neo26TextStyle.styledChatContent(message, contentStart, translated), interactive);
            return Component.empty().append(Neo26TextStyle.takePrefix(message, contentStart)).append(styled);
        }
        return Neo26TextStyle.withInteractive(
                Neo26TextStyle.styledChatContent(message, contentStart, translated), interactive);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onItemTooltip(ItemTooltipEvent event) {
        if (service == null) return;
        if (tooltipProbeDepth.get() > 0) return;
        Minecraft tooltipClient = Minecraft.getInstance();
        if (event.getEntity() == null || tooltipClient == null
                || !tooltipClient.isSameThread()
                || !renderingCurrentScreen(tooltipClient)) return;
        DisplayMode mode = service.tooltipMode();
        if (mode == DisplayMode.ORIGINAL_ONLY) return;
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) return;

        int n = lines.size();
        ItemStack stack = event.getItemStack();
        TooltipParagraphPlan plan = tooltipParagraphPlan(
                stack, lines, Neo26TextStyle::paragraphRequestText);
        lastTooltipStack = stack;
        lastTooltipParagraphSources = plan.sources();
        lastTooltipScreen = tooltipClient.gui.screen();
        lastTooltipAtMs = System.currentTimeMillis();
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
                    if (!text.isBlank()) originalEndsWithSeparator = Neo26TextStyle.isSeparatorText(text);
                }
            }
            if (!paragraphReady[i]) {
                out.addAll(lines.subList(i, end + 1));
                i = end + 1;
                continue;
            }
            List<Component> group = new ArrayList<>(lines.subList(i, end + 1));
            if (end > i) {
                List<Component> translated = Neo26TextStyle.renderTranslatedParagraph(group, service::translateItemLine, font);
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
            Component translated = Neo26TextStyle.renderTranslated("tooltip", line, service::translateItemLine);
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
                out.add(Neo26TextStyle.separatorLine(maxLen));
            }
            out.addAll(appended);
            out.add(Neo26TextStyle.separatorLine(maxLen));
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
    public void onClientTick(ClientTickEvent.Post event) {
        // Recover if a cancelled/failed render did not deliver the matching Post event.
        SCREEN_RENDER_STACK.remove();
        if (modeKey != null) {
            while (modeKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) mc.setScreenAndShow(new Neo26ConfigScreen(mc.gui.screen()));
            }
        }
        if (retranslateKey != null && service != null) {
            
            
            while (retranslateKey.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) retranslateItem(mc.player.getMainHandItem());
            }
        }
        if (toggleKey != null && service != null) {
            while (toggleKey.consumeClick()) flipShowOriginal();
        }
        syncGameLanguage(Minecraft.getInstance());
        
        
        refreshOnlineNames(Minecraft.getInstance());
        if (service != null) service.flushBatches();
        expireStaleBlock();
        flushStaleChats(Minecraft.getInstance());
        warmVisibleHudItems(Minecraft.getInstance());
        // R12 (user clarification of R10): the OPEN container is "the current page" — its
        // slots pre-translate; queued batches are kept even if the screen closes ("排隊項
        // 不要丟棄，有看到的都加入排隊，沒看到的先不管"). Only never-seen text stays unbought.
        warmOpenContainerItems(Minecraft.getInstance());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onScreenRenderPre(net.neoforged.neoforge.client.event.ScreenEvent.Render.Pre event) {
        SCREEN_RENDER_STACK.get().push(event.getScreen());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onScreenRenderPost(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        java.util.ArrayDeque<net.minecraft.client.gui.screens.Screen> stack =
                SCREEN_RENDER_STACK.get();
        if (!stack.isEmpty() && stack.peek() == event.getScreen()) stack.pop();
        else stack.removeFirstOccurrence(event.getScreen());
        if (stack.isEmpty()) SCREEN_RENDER_STACK.remove();
    }



    @SubscribeEvent
    public void onScreenKeyPressed(net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (service == null) return;
        net.minecraft.client.gui.screens.Screen screen = event.getScreen();
        
        
        if (screen instanceof Neo26ConfigScreen || screen instanceof Neo26AiScreen
                || screen instanceof Neo26KeybindScreen) return;
        
        
        net.minecraft.client.input.KeyEvent ke = new net.minecraft.client.input.KeyEvent(
                event.getKeyCode(), event.getScanCode(), event.getModifiers());
        if (toggleKey != null && toggleKey.matches(ke)) {
            flipShowOriginal();
            return;
        }
        if (retranslateKey != null && retranslateKey.matches(ke)) {
            retranslatePointedItem(screen);
            return;
        }
        if (screenScanKey != null && screenScanKey.matches(ke)) {
            
            scanAndTranslateScreen(screen);
        }
    }

    







    private void scanAndTranslateScreen(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null || service == null
                || screen instanceof net.minecraft.client.gui.screens.ChatScreen
                || !screenTranslationAllowed(screen)) return;
        List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>();
        collectWidgets(screen.children(), widgets, 0);
        int requested = 0;
        for (net.minecraft.client.gui.components.AbstractWidget widget : widgets) {
            Component raw = widget.getMessage();
            if (raw == null) continue;
            final Component source = Neo26TextStyle.resolveLegacyCodes(raw);
            List<String> requests = Neo26TextStyle.requestLines(source);
            for (String request : requests) {
                service.requestScreenTextAsync(request, translated -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null) return;
                    Component ready = Neo26TextStyle.renderTranslated(
                            "screenScan", source, service::translateScreenScanText);
                    Component display = ready != null ? ready : source;
                    mc.execute(() -> widget.setMessage(display));
                });
            }
            requested += requests.size();
        }
        status(Component.translatable("message.mctranslator.screen_scan", requested).getString());
    }

    
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

    







    private void warmOpenContainerItems(Minecraft mc) {
        if (mc == null || service == null) return;
        if (service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
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
            if (slot == null || !slot.isActive() || !slot.hasItem()) continue;
            String name = slot.getItem().getHoverName().getString();
            if (name != null && !name.isBlank()) {
                newNames.add(name);
            }
        }
        if (!newNames.isEmpty()) service.warmNamesBatch(newNames);
    }

    /** Only the hotbar and off-hand item icons currently visible on the HUD are
     * collected. Hidden backpack/armour slots wait until their screen is opened. */
    private void warmVisibleHudItems(Minecraft mc) {
        if (mc == null || mc.player == null || service == null
                || service.tooltipMode() == DisplayMode.ORIGINAL_ONLY) return;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (stack == null || stack.isEmpty()) continue;
            String name = stack.getHoverName().getString();
            if (name != null && !name.isBlank()) names.add(name);
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand != null && !offhand.isEmpty()) {
            String name = offhand.getHoverName().getString();
            if (name != null && !name.isBlank()) names.add(name);
        }
        if (!names.isEmpty()) service.warmNamesBatch(List.copyOf(names));
    }

    private void retranslatePointedItem(net.minecraft.client.gui.screens.Screen screen) {
        ItemStack target = null;
        if (screen instanceof AbstractContainerScreen<?> container
                && container instanceof AbstractContainerScreenAccessor accessor) {
            Slot slot = accessor.mctranslator$hoveredSlot();
            if (slot != null && slot.hasItem()) target = slot.getItem();
        }
        if ((target == null || target.isEmpty()) && lastTooltipScreen == screen
                && System.currentTimeMillis() - lastTooltipAtMs <= 1_500L) {
            target = lastTooltipStack;
        }
        if (target != null && !target.isEmpty()) retranslateItem(target);
    }

    private void retranslateItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || service == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        List<String> sources = lastTooltipStack == stack ? lastTooltipParagraphSources : null;
        if (sources == null) {
            List<Component> lines;
            int depth = tooltipProbeDepth.get();
            tooltipProbeDepth.set(depth + 1);
            try {
                Item.TooltipContext ctx = Item.TooltipContext.of(mc.level);
                lines = stack.getTooltipLines(ctx, mc.player, TooltipFlag.Default.NORMAL);
            } catch (RuntimeException e) {
                return;
            } finally {
                if (depth == 0) tooltipProbeDepth.remove();
                else tooltipProbeDepth.set(depth);
            }
            TooltipParagraphPlan plan = tooltipParagraphPlan(
                    stack, lines, Neo26TextStyle::paragraphRequestText);
            sources = plan.sources();
        }
        java.util.LinkedHashSet<String> requests = new java.util.LinkedHashSet<>();
        String itemName = stack.getHoverName().getString();
        if (itemName != null && !itemName.isBlank()) requests.add(itemName);
        if (sources != null) requests.addAll(sources);
        service.retranslate(List.copyOf(requests));
        Neo26TextStyle.clearRenderMemo();
        status(Component.translatable("message.mctranslator.retranslate", stack.getHoverName().getString()).getString());
    }

    
    public static void testAi(String baseUrl, String model, List<String> keys, java.util.function.Consumer<String> onResult) {
        if (transport == null) {
            onResult.accept(Component.translatable("message.mctranslator.not_initialized").getString());
            return;
        }
        Thread t = new Thread(() -> {
            String msg;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(transport, () -> new AiSettings(baseUrl, model, keys));
                ai.setTokenUsage(tokenUsage);
                String out = ai.translate("Hello, world", "zh-TW").translatedText();
                msg = Component.translatable("message.mctranslator.success", "Hello, world → " + out).getString();
            } catch (Exception e) {
                msg = Component.translatable("message.mctranslator.failed", e.getMessage()).getString();
            }
            final String result = msg;
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> onResult.accept(result));
            else onResult.accept(result);
        }, "mctranslator-aitest");
        t.setDaemon(true);
        t.start();
    }

    public static void testCodex(java.util.function.Consumer<String> onResult) {
        if (codexTransport == null || config == null) {
            onResult.accept("Codex is not initialized");
            return;
        }
        Thread thread = new Thread(() -> {
            String result;
            try {
                OpenAiTranslator ai = new OpenAiTranslator(codexTransport,
                        () -> new AiSettings("codex://app-server", config.codexModel,
                                java.util.Collections.emptyList(), config.aiGlossary),
                        RequestPacer.disabled());
                String translated = ai.translate("Hello, world", "zh-TW").translatedText();
                result = "Hello, world -> " + translated;
            } catch (Exception error) {
                result = "Codex: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            final String message = result;
            Minecraft client = Minecraft.getInstance();
            if (client != null) client.execute(() -> onResult.accept(message));
            else onResult.accept(message);
        }, "mctranslator-codex-test");
        thread.setDaemon(true);
        thread.start();
    }

    private void status(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("message.mctranslator.prefix", msg));
        }
    }
}
