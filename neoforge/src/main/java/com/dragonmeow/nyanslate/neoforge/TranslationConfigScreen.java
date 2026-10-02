package com.dragonmeow.nyanslate.neoforge;

import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.MachineTranslationProvider;
import com.dragonmeow.nyanslate.config.TranslatorConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 翻譯設定 — per-surface translation settings. Each row has a mode button (chat &amp;
 * tooltip: 原文／原文＋翻譯／只有翻譯; single-line surfaces: 原文／翻譯) and an engine
 * toggle (機翻 Google / AI 精翻). Saved immediately.
 */
public final class TranslationConfigScreen extends Screen {

    private static final int W = 260;
    private static final int AI_W = 70;

    private final Screen parent;
    private int rowWidth = W;
    private boolean confirmClear;

    public TranslationConfigScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanslateNeoForge.config();
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
                        b -> this.minecraft.setScreen(new TranslationHelpScreen(this)))
                .bounds(6, 4, 70, 14).build());

        row("config.nyanslate.surface.chat", left, y, step, true, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        row("config.nyanslate.surface.tooltip", right, y, step, true, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y += step;
        row("config.nyanslate.surface.scoreboard", left, y, step, true, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        row("config.nyanslate.surface.name", right, y, step, true, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y += step;
        row("config.nyanslate.surface.bossbar", left, y, step, true, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        row("config.nyanslate.surface.title", right, y, step, true, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y += step;
        row("config.nyanslate.surface.actionbar", left, y, step, true, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        row("config.nyanslate.surface.book", right, y, step, true, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y += step;
        row("config.nyanslate.surface.screen", left, y, step, true, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);
        this.addRenderableWidget(Button.builder(chatDeliveryLabel(cfg), b -> {
            cfg.deliverChatTranslationsInOrder = !cfg.deliverChatTranslationsInOrder;
            NyanslateNeoForge.saveConfig();
            b.setMessage(chatDeliveryLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += step;

        this.addRenderableWidget(Button.builder(langLabel(cfg),
                        b -> this.minecraft.setScreen(new TranslationLanguageScreen(this)))
                .bounds(left, y, rowWidth, 20).build());
        this.addRenderableWidget(Button.builder(providerLabel(cfg),
                        b -> this.minecraft.setScreen(new TranslationMachineProviderScreen(this)))
                .bounds(right, y, rowWidth, 20).build());
        y += 22;
        // Row 1: debug overlay | request cooldown + batch window sub-screen.
        this.addRenderableWidget(Button.builder(debugLabel(cfg), b -> {
            cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
            if (!cfg.debugTranslationOverlay) NyanslateNeoForge.clearDebugLog();
            NyanslateNeoForge.saveConfig();
            b.setMessage(debugLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.request_cooldown.open"),
                        b -> this.minecraft.setScreen(new TranslationCooldownScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 20;
        // Row 2: AI-failure machine-translation fallback | screen-scan engine.
        this.addRenderableWidget(Button.builder(aiFallbackLabel(cfg), b -> {
            cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi;
            NyanslateNeoForge.saveConfig();
            b.setMessage(aiFallbackLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        // Engine for the "translate current screen" (P) hotkey: 機翻 (Google) or AI 精翻.
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            NyanslateNeoForge.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += 20;
        // Row 3: AI settings | keybind settings.
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.ai.open"),
                        b -> this.minecraft.setScreen(new AiConfigScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.keybind.open"),
                        b -> this.minecraft.setScreen(new TranslationKeybindScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 20;
        // Row 4: clear cache | do-not-translate filter — each now gets a full cell.
        this.addRenderableWidget(Button.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.requests.open"),
                        b -> this.minecraft.setScreen(new TranslationRequestsScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 20;
        int fileWidth = (rowWidth * 2 + gap - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.translations.export"), b -> NyanslateNeoForge.translationFile(false))
                .bounds(left + 0 * (fileWidth + 4), y, fileWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.translations.import"), b -> NyanslateNeoForge.translationFile(true))
                .bounds(left + 1 * (fileWidth + 4), y, fileWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 2 * (fileWidth + 4), y, fileWidth, 18).build());
        y += 20;
        // Global master switch (left half): stops sending NEW translation requests (cached
        // ones keep showing). Right half opens the community translation hub screen.
        this.addRenderableWidget(Button.builder(requestsToggleLabel(cfg), b -> {
            cfg.translationRequestsEnabled = !cfg.translationRequestsEnabled;
            NyanslateNeoForge.saveConfig();
            NyanslateNeoForge.clearFtbPending();
            b.setMessage(requestsToggleLabel(cfg));
        }).bounds(left, y, rowWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.nyanslate.requests.toggle.hint")))
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.hub.open"),
                        b -> this.minecraft.setScreen(new TranslationHubScreen(this)))
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
        if (NyanslateNeoForge.service() != null) NyanslateNeoForge.service().clearTranslations();
        NeoTextStyle.clearRenderMemo();
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

    /** Cooldown values the button cycles through, in ms; 0 = pacing off (a valid value). */
    static final int[] COOLDOWN_STEPS = {
            0, 1000, 2000, 4000, 6000, 8000, 10000
    };

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

    private static Component debugLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.debug",
                Component.translatable(cfg.debugTranslationOverlay ? "options.on" : "options.off"));
    }

    /** disableGoogleFallbackForAi is stored as "disable"; the button shows it inverted,
     *  i.e. 開 means the machine-translation fallback is still allowed on AI failure. */
    private static Component aiFallbackLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.ai.machine_fallback",
                Component.translatable(cfg.disableGoogleFallbackForAi ? "options.off" : "options.on"));
    }


    private int row(String label, int x, int y, int step, boolean threeWay,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int engineW = Math.min(AI_W, Math.max(52, rowWidth / 3));
        int modeW = rowWidth - engineW - 4;
        // mode button
        this.addRenderableWidget(Button.builder(modeText(label, getMode.get(), threeWay), b -> {
            DisplayMode next = threeWay
                    ? getMode.get().next()
                    : (getMode.get() == DisplayMode.ORIGINAL_ONLY ? DisplayMode.TRANSLATION : DisplayMode.ORIGINAL_ONLY);
            setMode.accept(next);
            NyanslateNeoForge.saveConfig();
            b.setMessage(modeText(label, next, threeWay));
        }).bounds(x, y, modeW, 18).build());
        // engine toggle (機翻 / AI)
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            NyanslateNeoForge.saveConfig(); // also clears the render memo so it re-translates via the new engine
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode, boolean threeWay) {
        Component state = threeWay ? modeName(mode)
                : Component.translatable(mode == DisplayMode.ORIGINAL_ONLY
                        ? "config.nyanslate.mode.original" : "config.nyanslate.mode.translation");
        return Component.translatable(label, state);
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        if (NyanslateNeoForge.service() != null) {
            int done = NyanslateNeoForge.service().translatedCount();
            int pending = NyanslateNeoForge.service().pendingCount();
            Component line1 = Component.translatable("config.nyanslate.progress.done", done);
            Component line2 = Component.translatable("config.nyanslate.progress.pending", pending);
            g.drawString(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0x80FF80, false);
            g.drawString(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFD080 : 0x808080, false);
        }

    }

    @Override
    public void onClose() {
        NyanslateNeoForge.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
