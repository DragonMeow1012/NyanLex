package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.MachineTranslationProvider;
import com.dragonmeow.mctranslator.config.TranslatorConfig;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
        super(Component.translatable("screen.mctranslator.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorFabric.config();
        rowWidth = Math.min(W, Math.max(120, (this.width - 12) / 2));
        int gap = 6;
        int left = this.width / 2 - rowWidth - gap / 2;
        int right = this.width / 2 + gap / 2;
        int y = 24;
        int step = 20;

        row("config.mctranslator.surface.chat", left, y, step, true, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        row("config.mctranslator.surface.tooltip", right, y, step, true, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y += step;
        row("config.mctranslator.surface.scoreboard", left, y, step, true, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        row("config.mctranslator.surface.name", right, y, step, true, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y += step;
        row("config.mctranslator.surface.bossbar", left, y, step, true, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        row("config.mctranslator.surface.title", right, y, step, true, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y += step;
        row("config.mctranslator.surface.actionbar", left, y, step, true, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        row("config.mctranslator.surface.book", right, y, step, true, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y += step;
        row("config.mctranslator.surface.screen", left, y, step, true, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);
        y += step;

        y += 6;
        this.addRenderableWidget(Button.builder(langLabel(cfg),
                        b -> this.minecraft.setScreen(new TranslationLanguageScreen(this)))
                .bounds(left, y, rowWidth, 20).build());
        this.addRenderableWidget(Button.builder(providerLabel(cfg),
                        b -> this.minecraft.setScreen(new MachineProviderScreen(this)))
                .bounds(right, y, rowWidth, 20).build());
        y += 22;
        // 事前冷卻節流：minimum spacing between outbound requests (per engine, Google 與 AI
        // 各自計時). Click cycles 關閉 → 1000 → … → 10000 ms; the pacer reads the value live.
        this.addRenderableWidget(Button.builder(cooldownLabel(cfg), b -> {
            cfg.requestCooldownMs = nextCooldown(cfg.requestCooldownMs);
            MctranslatorFabric.saveConfig();
            b.setMessage(cooldownLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(batchWindowLabel(cfg), b -> {
            cfg.batchWindowMs = nextBatchWindow(cfg.batchWindowMs);
            MctranslatorFabric.saveConfig();
            b.setMessage(batchWindowLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += 22;
        this.addRenderableWidget(Button.builder(debugLabel(cfg), b -> {
            cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
            if (!cfg.debugTranslationOverlay) MctranslatorFabric.clearDebugLog();
            MctranslatorFabric.saveConfig();
            b.setMessage(debugLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(aiFallbackLabel(cfg), b -> {
            cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi;
            MctranslatorFabric.saveConfig();
            b.setMessage(aiFallbackLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += 22;
        // Engine for the "translate current screen" (P) hotkey: 機翻 (Google) or AI 精翻.
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorFabric.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.mctranslator.ai.open"),
                        b -> this.minecraft.setScreen(new AiConfigScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.translatable("config.mctranslator.keybind.open"),
                        b -> this.minecraft.setScreen(new TranslationKeybindScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(right, y, rowWidth, 18).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width / 2 - 100, y, 200, 18).build());
    }

    private Component clearLabel() {
        return Component.translatable(confirmClear ? "config.mctranslator.cache.confirm" : "config.mctranslator.cache.clear");
    }

    private void clearCurrentLanguage(Button button) {
        if (!confirmClear) {
            confirmClear = true;
            button.setMessage(clearLabel());
            return;
        }
        confirmClear = false;
        if (MctranslatorFabric.service() != null) MctranslatorFabric.service().clearTranslations();
        FabricTextStyle.clearRenderMemo();
        button.setMessage(Component.translatable("config.mctranslator.cache.cleared"));
    }

    private static Component langLabel(TranslatorConfig cfg) {
        Component target = cfg.followGameLanguage
                ? Component.translatable("config.mctranslator.language.follow", cfg.targetLang)
                : Component.literal(cfg.targetLang);
        return Component.translatable("config.mctranslator.language", target);
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return Component.translatable("config.mctranslator.screen_scan_engine", aiText(cfg.aiScreenScan));
    }

    /** Cooldown values the button cycles through, in ms; 0 = pacing off (a valid value). */
    private static final int[] COOLDOWN_STEPS = {0, 1000, 2000, 4000, 6000, 8000, 10000};

    /** Next step above the current value; wraps to 0 (關閉) past the top. Off-list values snap up. */
    private static int nextCooldown(int current) {
        for (int v : COOLDOWN_STEPS) {
            if (v > current) return v;
        }
        return 0;
    }

    private static Component cooldownLabel(TranslatorConfig cfg) {
        Component state = cfg.requestCooldownMs <= 0
                ? Component.translatable("config.mctranslator.request_cooldown.off")
                : Component.literal(cfg.requestCooldownMs + " ms");
        return Component.translatable("config.mctranslator.request_cooldown", state);
    }

    private static Component providerLabel(TranslatorConfig cfg) {
        MachineTranslationProvider provider = MachineTranslationProvider.fromId(
                cfg.machineTranslationProvider);
        return Component.translatable("config.mctranslator.machine_provider",
                MachineProviderScreen.providerName(provider));
    }
    private static final int[] BATCH_WINDOW_STEPS = {0, 1000, 2000, 3000, 5000, 8000, 10000};
    private static int nextBatchWindow(int current) {
        for (int value : BATCH_WINDOW_STEPS) if (value > current) return value;
        return 0;
    }
    private static Component batchWindowLabel(TranslatorConfig cfg) {
        Component state = cfg.batchWindowMs <= 0
                ? Component.translatable("config.mctranslator.batch_window.off")
                : Component.literal(cfg.batchWindowMs / 1000F + " s");
        return Component.translatable("config.mctranslator.batch_window", state);
    }

    private static Component debugLabel(TranslatorConfig cfg) {
        return Component.translatable("config.mctranslator.debug",
                Component.translatable(cfg.debugTranslationOverlay ? "options.on" : "options.off"));
    }

    private static Component aiFallbackLabel(TranslatorConfig cfg) {
        return Component.translatable("config.mctranslator.ai.disable_gt_fallback",
                Component.translatable(cfg.disableGoogleFallbackForAi ? "options.on" : "options.off"));
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
            MctranslatorFabric.saveConfig();
            b.setMessage(modeText(label, next, threeWay));
        }).bounds(x, y, modeW, 18).build());
        // engine toggle (機翻 / AI)
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorFabric.saveConfig(); // also clears the render memo so it re-translates via the new engine
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode, boolean threeWay) {
        Component state = threeWay ? modeName(mode)
                : Component.translatable(mode == DisplayMode.ORIGINAL_ONLY
                        ? "config.mctranslator.mode.original" : "config.mctranslator.mode.translation");
        return Component.translatable(label, state);
    }

    private static Component modeName(DisplayMode mode) {
        return Component.translatable(switch (mode) {
            case ORIGINAL_ONLY -> "config.mctranslator.mode.original";
            case BOTH -> "config.mctranslator.mode.both";
            case TRANSLATION -> "config.mctranslator.mode.translation";
        });
    }

    private static Component aiText(boolean ai) {
        return Component.translatable(ai ? "config.mctranslator.engine.ai" : "config.mctranslator.engine.machine");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        if (MctranslatorFabric.service() != null) {
            int done = MctranslatorFabric.service().translatedCount();
            int pending = MctranslatorFabric.service().pendingCount();
            Component line1 = Component.translatable("config.mctranslator.progress.done", done);
            Component line2 = Component.translatable("config.mctranslator.progress.pending", pending);
            g.drawString(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0x80FF80, false);
            g.drawString(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFD080 : 0x808080, false);
        }

    }

    @Override
    public void onClose() {
        MctranslatorFabric.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
