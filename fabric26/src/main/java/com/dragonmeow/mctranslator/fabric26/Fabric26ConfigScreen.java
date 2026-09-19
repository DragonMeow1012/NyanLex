package com.dragonmeow.mctranslator.fabric26;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.MachineTranslationProvider;
import com.dragonmeow.mctranslator.config.TranslatorConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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
public final class Fabric26ConfigScreen extends Screen {

    private static final int W = 280;
    private static final int AI_W = 70;

    private final Screen parent;
    private int rowWidth = W;
    private boolean confirmClear;

    public Fabric26ConfigScreen(Screen parent) {
        super(Component.translatable("screen.mctranslator.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorFabric26.config();
        rowWidth = Math.min(W, Math.max(120, (this.width - 12) / 2));
        int gap = 6;
        int left = this.width / 2 - rowWidth - gap / 2;
        int right = this.width / 2 + gap / 2;
        int y = 24;
        int step = 20;

        row("config.mctranslator.surface.chat", left, y, step, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        row("config.mctranslator.surface.tooltip", right, y, step, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y += step;
        row("config.mctranslator.surface.scoreboard", left, y, step, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        row("config.mctranslator.surface.name", right, y, step, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y += step;
        row("config.mctranslator.surface.bossbar", left, y, step, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        row("config.mctranslator.surface.title", right, y, step, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y += step;
        row("config.mctranslator.surface.actionbar", left, y, step, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        row("config.mctranslator.surface.book", right, y, step, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y += step;
        row("config.mctranslator.surface.screen", left, y, step, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);
        this.addRenderableWidget(Button.builder(chatDeliveryLabel(cfg), b -> {
            cfg.deliverChatTranslationsInOrder = !cfg.deliverChatTranslationsInOrder;
            MctranslatorFabric26.saveConfig();
            b.setMessage(chatDeliveryLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());

        y += step + 6;
        this.addRenderableWidget(Button.builder(langLabel(cfg),
                        b -> this.minecraft.setScreenAndShow(new Fabric26LanguageScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(providerLabel(cfg),
                        b -> this.minecraft.setScreenAndShow(new Fabric26ProviderScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += step;
        this.addRenderableWidget(Button.builder(debugLabel(cfg), b -> {
            cfg.debugTranslationOverlay = !cfg.debugTranslationOverlay;
            if (!cfg.debugTranslationOverlay) MctranslatorFabric26.clearDebugLog();
            MctranslatorFabric26.saveConfig();
            b.setMessage(debugLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        // 事前冷卻節流：minimum spacing between outbound requests (per engine, Google 與 AI
        // 各自計時). Click cycles 關閉 → 1000 → … → 10000 ms; the pacer reads the value live.
        this.addRenderableWidget(Button.builder(cooldownLabel(cfg), b -> {
            cfg.requestCooldownMs = nextCooldown(cfg.requestCooldownMs);
            MctranslatorFabric26.saveConfig();
            b.setMessage(cooldownLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += step;
        this.addRenderableWidget(Button.builder(aiFallbackLabel(cfg), b -> {
            cfg.disableGoogleFallbackForAi = !cfg.disableGoogleFallbackForAi;
            MctranslatorFabric26.saveConfig();
            b.setMessage(aiFallbackLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(batchWindowLabel(cfg), b -> {
            cfg.batchWindowMs = nextBatchWindow(cfg.batchWindowMs);
            MctranslatorFabric26.saveConfig();
            b.setMessage(batchWindowLabel(cfg));
        }).bounds(right, y, rowWidth, 18).build());
        y += step;
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorFabric26.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.mctranslator.ai.open"),
                        b -> this.minecraft.setScreenAndShow(new Fabric26AiScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += step;
        this.addRenderableWidget(Button.builder(Component.translatable("config.mctranslator.keybind.open"),
                        b -> this.minecraft.setScreenAndShow(new Fabric26KeybindScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(right, y, rowWidth, 18).build());
        y += 22;
        int fileWidth = (rowWidth * 2 + gap - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.translatable("config.mctranslator.translations.export"), b -> MctranslatorFabric26.translationFile(false))
                .bounds(left + 0 * (fileWidth + 4), y, fileWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.mctranslator.translations.import"), b -> MctranslatorFabric26.translationFile(true))
                .bounds(left + 1 * (fileWidth + 4), y, fileWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 2 * (fileWidth + 4), y, fileWidth, 18).build());
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
        if (MctranslatorFabric26.service() != null) MctranslatorFabric26.service().clearTranslations();
        Fabric26TextStyle.clearRenderMemo();
        button.setMessage(Component.translatable("config.mctranslator.cache.cleared"));
    }

    private static Component langLabel(TranslatorConfig cfg) {
        Component target = cfg.followGameLanguage
                ? Component.translatable("config.mctranslator.language.follow", cfg.targetLang)
                : Component.literal(cfg.targetLang);
        return Component.translatable("config.mctranslator.language", target);
    }

    private static Component providerLabel(TranslatorConfig cfg) {
        MachineTranslationProvider provider = MachineTranslationProvider.fromId(
                cfg.machineTranslationProvider);
        return Component.translatable("config.mctranslator.machine_provider",
                Fabric26ProviderScreen.providerName(provider));
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return Component.translatable("config.mctranslator.screen_scan_engine", aiText(cfg.aiScreenScan));
    }

    private static Component chatDeliveryLabel(TranslatorConfig cfg) {
        Component mode = Component.translatable(cfg.deliverChatTranslationsInOrder
                ? "config.mctranslator.chat_delivery.ordered"
                : "config.mctranslator.chat_delivery.ready_first");
        return Component.translatable("config.mctranslator.chat_delivery", mode);
    }

    private static Component debugLabel(TranslatorConfig cfg) {
        return Component.translatable("config.mctranslator.debug", Component.translatable(cfg.debugTranslationOverlay ? "options.on" : "options.off"));
    }
    private static Component aiFallbackLabel(TranslatorConfig cfg) {
        return Component.translatable("config.mctranslator.ai.disable_gt_fallback",
                Component.translatable(cfg.disableGoogleFallbackForAi ? "options.on" : "options.off"));
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

    private int row(String label, int x, int y, int step,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int engineW = Math.min(AI_W, Math.max(52, rowWidth / 3));
        int modeW = rowWidth - engineW - 4;
        this.addRenderableWidget(Button.builder(modeText(label, getMode.get()), b -> {
            DisplayMode next = getMode.get().next();
            setMode.accept(next);
            MctranslatorFabric26.saveConfig();
            b.setMessage(modeText(label, next));
        }).bounds(x, y, modeW, 18).build());
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorFabric26.saveConfig();
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode) {
        return Component.translatable(label, modeName(mode));
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        // NB: 26.2 skips draws whose colour has alpha 0, so colours are fully opaque (0xFF…).
        if (MctranslatorFabric26.service() != null) {
            int done = MctranslatorFabric26.service().translatedCount();
            int pending = MctranslatorFabric26.service().pendingCount();
            Component line1 = Component.translatable("config.mctranslator.progress.done", done);
            Component line2 = Component.translatable("config.mctranslator.progress.pending", pending);
            graphics.text(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0xFF80FF80, false);
            graphics.text(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFFFD080 : 0xFF808080, false);
        }
    }

    @Override
    public void onClose() {
        MctranslatorFabric26.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
