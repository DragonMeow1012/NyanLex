package com.dragonmeow.nyanslate.neoforge26;

import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.MachineTranslationProvider;
import com.dragonmeow.nyanslate.config.TranslatorConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 翻譯設定 — per-surface translation settings (MC 26.2). Each row has a mode button
 * (原文／原文＋翻譯／只有翻譯) and an engine toggle (機翻 Google / AI 精翻); saved immediately.
 * Includes the complete Minecraft language picker, permanent per-language cache controls,
 * and the screen-scan-hotkey engine.
 * Hotkeys themselves are rebindable in vanilla 控制 (registered under the 雜項 category).
 */
public final class Neo26ConfigScreen extends Screen {

    private static final int W = 280;
    private static final int AI_W = 70;

    private final Screen parent;
    private int rowWidth = W;
    private boolean confirmClear;

    public Neo26ConfigScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanslateNeoForge26.config();
        rowWidth = Math.min(W, Math.max(120, (this.width - 12) / 2));
        int gap = 6;
        int left = this.width / 2 - rowWidth - gap / 2;
        int right = this.width / 2 + gap / 2;
        int y = 24;
        int step = 20;

        // Prominent (but non-blocking) entry point to the help screen, top-left corner.
        this.addRenderableWidget(Button.builder(
                        Component.translatable("config.nyanslate.help.open")
                                .withStyle(ChatFormatting.YELLOW),
                        b -> this.minecraft.setScreenAndShow(new Neo26HelpScreen(this)))
                .bounds(6, 4, 70, 14).build());

        row("config.nyanslate.surface.chat", left, y, step, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        row("config.nyanslate.surface.tooltip", right, y, step, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y += step;
        row("config.nyanslate.surface.scoreboard", left, y, step, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        row("config.nyanslate.surface.name", right, y, step, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y += step;
        row("config.nyanslate.surface.bossbar", left, y, step, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        row("config.nyanslate.surface.title", right, y, step, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y += step;
        row("config.nyanslate.surface.actionbar", left, y, step, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        row("config.nyanslate.surface.book", right, y, step, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y += step;
        row("config.nyanslate.surface.screen", left, y, step, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);
        this.addRenderableWidget(Button.builder(chatDeliveryLabel(cfg), b -> {
            cfg.deliverChatTranslationsInOrder = !cfg.deliverChatTranslationsInOrder;
            NyanslateNeoForge26.saveConfig();
            b.setMessage(chatDeliveryLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());

        y += step;
        this.addRenderableWidget(Button.builder(langLabel(cfg),
                        b -> this.minecraft.setScreenAndShow(new Neo26LanguageScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(providerLabel(cfg),
                        b -> this.minecraft.setScreenAndShow(new Neo26ProviderScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += step;
        // Row 1: debug overlay | request cooldown + batch window sub-screen.
        this.addRenderableWidget(Button.builder(debugLabel(cfg), b -> {
            cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
            if (!cfg.debugTranslationOverlay) NyanslateNeoForge26.clearDebugLog();
            NyanslateNeoForge26.saveConfig();
            b.setMessage(debugLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.request_cooldown.open"),
                        b -> this.minecraft.setScreenAndShow(new Neo26CooldownScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += step;
        // Row 2: AI-failure machine-translation fallback | screen-scan engine.
        this.addRenderableWidget(Button.builder(aiFallbackLabel(cfg), b -> {
            cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi;
            NyanslateNeoForge26.saveConfig();
            b.setMessage(aiFallbackLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            NyanslateNeoForge26.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += step;
        // Row 3: AI settings | keybind settings.
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.ai.open"),
                        b -> this.minecraft.setScreenAndShow(new Neo26AiScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.keybind.open"),
                        b -> this.minecraft.setScreenAndShow(new Neo26KeybindScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += step;
        // Row 4: clear cache | do-not-translate filter — each now gets a full cell.
        this.addRenderableWidget(Button.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.requests.open"),
                        b -> this.minecraft.setScreenAndShow(new Neo26RequestsScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 20;
        int fileWidth = (rowWidth * 2 + gap - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.translations.export"), b -> NyanslateNeoForge26.translationFile(false))
                .bounds(left + 0 * (fileWidth + 4), y, fileWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.translations.import"), b -> NyanslateNeoForge26.translationFile(true))
                .bounds(left + 1 * (fileWidth + 4), y, fileWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 2 * (fileWidth + 4), y, fileWidth, 18).build());
        y += 20;
        // Global master switch (left half): stops sending NEW translation requests (cached
        // ones keep showing). Right half opens the community translation hub screen.
        this.addRenderableWidget(Button.builder(requestsToggleLabel(cfg), b -> {
            cfg.translationRequestsEnabled = !cfg.translationRequestsEnabled;
            NyanslateNeoForge26.saveConfig();
            NyanslateNeoForge26.clearFtbPending();
            b.setMessage(requestsToggleLabel(cfg));
        }).bounds(left, y, rowWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.nyanslate.requests.toggle.hint")))
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.hub.open"),
                        b -> this.minecraft.setScreenAndShow(new Neo26HubScreen(this)))
                .bounds(right, y, rowWidth, 20).build());
    }

    private Component clearLabel() {
        return Component.translatable(confirmClear ? "config.nyanslate.cache.confirm" : "config.nyanslate.cache.clear");
    }

    private void clearCurrentLanguage(Button button) {
        if (!confirmClear) {
            confirmClear = true;
            button.setMessage(clearLabel());
            return;
        }
        confirmClear = false;
        if (NyanslateNeoForge26.service() != null) NyanslateNeoForge26.service().clearTranslations();
        Neo26TextStyle.clearRenderMemo();
        button.setMessage(Component.translatable("config.nyanslate.cache.cleared"));
    }

    private static Component requestsToggleLabel(TranslatorConfig cfg) {
        return Component.translatable("screen.nyanslate.requests.toggle",
                Component.translatable(cfg.translationRequestsEnabled ? "options.off" : "options.on"));
    }

    private static Component langLabel(TranslatorConfig cfg) {
        Component target = cfg.followGameLanguage
                ? Component.translatable("config.nyanslate.language.follow", cfg.targetLang)
                : Component.literal(cfg.targetLang);
        return Component.translatable("config.nyanslate.language", target);
    }

    private static Component providerLabel(TranslatorConfig cfg) {
        MachineTranslationProvider provider = MachineTranslationProvider.fromId(
                cfg.machineTranslationProvider);
        return Component.translatable("config.nyanslate.machine_provider",
                Component.translatable("screen.nyanslate.provider." + provider.id()));
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.screen_scan_engine", aiText(cfg.aiScreenScan));
    }

    private static Component chatDeliveryLabel(TranslatorConfig cfg) {
        Component mode = Component.translatable(cfg.deliverChatTranslationsInOrder
                ? "config.nyanslate.chat_delivery.ordered"
                : "config.nyanslate.chat_delivery.ready_first");
        return Component.translatable("config.nyanslate.chat_delivery", mode);
    }

    private static Component debugLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.debug", Component.translatable(cfg.debugTranslationOverlay ? "options.on" : "options.off"));
    }
    /** disableGoogleFallbackForAi is stored as "disable"; the button shows it inverted,
     *  i.e. 開 means the machine-translation fallback is still allowed on AI failure. */
    private static Component aiFallbackLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.ai.machine_fallback",
                Component.translatable(cfg.disableGoogleFallbackForAi ? "options.off" : "options.on"));
    }

    /** Cooldown values the button cycles through, in ms; 0 = pacing off (a valid value).
     *  Package-visible: shared with {@link Neo26CooldownScreen}. */
    static final int[] COOLDOWN_STEPS = {0, 1000, 2000, 4000, 6000, 8000, 10000};

    /** Next step above the current value; wraps to 0 (關閉) past the top. Off-list values snap up. */
    static int nextCooldown(int current) {
        for (int v : COOLDOWN_STEPS) {
            if (v > current) return v;
        }
        return 0;
    }

    static Component cooldownLabel(TranslatorConfig cfg) {
        Component state = cfg.requestCooldownMs <= 0
                ? Component.translatable("config.nyanslate.request_cooldown.off")
                : Component.literal(cfg.requestCooldownMs + " ms");
        return Component.translatable("config.nyanslate.request_cooldown", state);
    }
    static final int[] BATCH_WINDOW_STEPS = {0, 1000, 2000, 3000, 5000, 8000, 10000};
    static int nextBatchWindow(int current) {
        for (int value : BATCH_WINDOW_STEPS) if (value > current) return value;
        return 0;
    }
    static Component batchWindowLabel(TranslatorConfig cfg) {
        Component state = cfg.batchWindowMs <= 0
                ? Component.translatable("config.nyanslate.batch_window.off")
                : Component.literal(cfg.batchWindowMs / 1000F + " s");
        return Component.translatable("config.nyanslate.batch_window", state);
    }

    private int row(String label, int x, int y, int step,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int engineW = Math.min(AI_W, Math.max(52, rowWidth / 3));
        int modeW = rowWidth - engineW - 4;
        this.addRenderableWidget(Button.builder(modeText(label, getMode.get()), b -> {
            DisplayMode next = getMode.get().next();
            setMode.accept(next);
            NyanslateNeoForge26.saveConfig();
            b.setMessage(modeText(label, next));
        }).bounds(x, y, modeW, 18).build());
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            NyanslateNeoForge26.saveConfig();
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode) {
        return Component.translatable(label, modeName(mode));
    }

    private static Component modeName(DisplayMode mode) {
        return Component.translatable(switch (mode) {
            case ORIGINAL_ONLY -> "config.nyanslate.mode.original";
            case BOTH -> "config.nyanslate.mode.both";
            case TRANSLATION -> "config.nyanslate.mode.translation";
        });
    }

    private static Component aiText(boolean ai) {
        return Component.translatable(ai ? "config.nyanslate.engine.ai" : "config.nyanslate.engine.machine");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        // NB: 26.2 skips draws whose colour has alpha 0, so colours are fully opaque (0xFF…).
        if (NyanslateNeoForge26.service() != null) {
            int done = NyanslateNeoForge26.service().translatedCount();
            int pending = NyanslateNeoForge26.service().pendingCount();
            Component line1 = Component.translatable("config.nyanslate.progress.done", done);
            Component line2 = Component.translatable("config.nyanslate.progress.pending", pending);
            graphics.text(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0xFF80FF80, false);
            graphics.text(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFFFD080 : 0xFF808080, false);
        }
    }

    @Override
    public void onClose() {
        NyanslateNeoForge26.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
