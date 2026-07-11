package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
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
        super(new net.minecraft.network.chat.TranslatableComponent("screen.mctranslator.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorFabric.config();
        rowWidth = Math.min(W, Math.max(120, (this.width - 12) / 2));
        int gap = 6;
        int left = this.width / 2 - rowWidth - gap / 2;
        int right = this.width / 2 + gap / 2;
        int full = rowWidth * 2 + gap;
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
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(langLabel(cfg),
                        b -> this.minecraft.setScreen(new TranslationLanguageScreen(this)))
                .bounds(left, y, full, 20).build());
        y += 22;
        // Engine for the "translate current screen" (P) hotkey: 機翻 (Google) or AI 精翻.
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorFabric.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.ai.open"),
                        b -> this.minecraft.setScreen(new AiConfigScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 24;
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.keybind.open"),
                        b -> this.minecraft.setScreen(new TranslationKeybindScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(right, y, rowWidth, 18).build());
        y += 22;
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(new net.minecraft.network.chat.TranslatableComponent("gui.done"), b -> onClose())
                .bounds(this.width / 2 - 100, y, 200, 18).build());
    }

    private Component clearLabel() {
        return new net.minecraft.network.chat.TranslatableComponent(confirmClear ? "config.mctranslator.cache.confirm" : "config.mctranslator.cache.clear");
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
        button.setMessage(new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.cache.cleared"));
    }

    private static Component langLabel(TranslatorConfig cfg) {
        Component target = cfg.followGameLanguage
                ? new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.language.follow", cfg.targetLang)
                : new net.minecraft.network.chat.TextComponent(cfg.targetLang);
        return new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.language", target);
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.screen_scan_engine", aiText(cfg.aiScreenScan));
    }


    private int row(String label, int x, int y, int step, boolean threeWay,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int engineW = Math.min(AI_W, Math.max(52, rowWidth / 3));
        int modeW = rowWidth - engineW - 4;
        // mode button
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(modeText(label, getMode.get(), threeWay), b -> {
            DisplayMode next = threeWay
                    ? getMode.get().next()
                    : (getMode.get() == DisplayMode.ORIGINAL_ONLY ? DisplayMode.TRANSLATION : DisplayMode.ORIGINAL_ONLY);
            setMode.accept(next);
            MctranslatorFabric.saveConfig();
            b.setMessage(modeText(label, next, threeWay));
        }).bounds(x, y, modeW, 18).build());
        // engine toggle (機翻 / AI)
        this.addRenderableWidget(com.dragonmeow.mctranslator.fabric.LegacyButton.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorFabric.saveConfig(); // also clears the render memo so it re-translates via the new engine
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode, boolean threeWay) {
        Component state = threeWay ? modeName(mode)
                : new net.minecraft.network.chat.TranslatableComponent(mode == DisplayMode.ORIGINAL_ONLY
                        ? "config.mctranslator.mode.original" : "config.mctranslator.mode.translation");
        return new net.minecraft.network.chat.TranslatableComponent(label, state);
    }

    private static Component modeName(DisplayMode mode) {
        return new net.minecraft.network.chat.TranslatableComponent(switch (mode) {
            case ORIGINAL_ONLY -> "config.mctranslator.mode.original";
            case BOTH -> "config.mctranslator.mode.both";
            case TRANSLATION -> "config.mctranslator.mode.translation";
        });
    }

    private static Component aiText(boolean ai) {
        return new net.minecraft.network.chat.TranslatableComponent(ai ? "config.mctranslator.engine.ai" : "config.mctranslator.engine.machine");
    }

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(g, this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        if (MctranslatorFabric.service() != null) {
            int done = MctranslatorFabric.service().translatedCount();
            int pending = MctranslatorFabric.service().pendingCount();
            Component line1 = new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.progress.done", done);
            Component line2 = new net.minecraft.network.chat.TranslatableComponent("config.mctranslator.progress.pending", pending);
            this.font.draw(g, line1, this.width - this.font.width(line1) - 6, 6, 0x80FF80);
            this.font.draw(g, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFD080 : 0x808080);
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
