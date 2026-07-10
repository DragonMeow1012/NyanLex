package com.dragonmeow.mctranslator.neoforge;

import com.dragonmeow.mctranslator.config.DisplayMode;
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
        super(Component.literal("翻譯設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorNeoForge.config();
        rowWidth = Math.min(W, Math.max(120, (this.width - 12) / 2));
        int gap = 6;
        int left = this.width / 2 - rowWidth - gap / 2;
        int right = this.width / 2 + gap / 2;
        int full = rowWidth * 2 + gap;
        int y = 24;
        int step = 20;

        row("聊天", left, y, step, true, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        row("物品／提示", right, y, step, true, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y += step;
        row("記分板", left, y, step, true, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        row("名牌／全息", right, y, step, true, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y += step;
        row("Boss 血條", left, y, step, true, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        row("標題／副標題", right, y, step, true, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y += step;
        row("動作列", left, y, step, true, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        row("書籍／講台", right, y, step, true, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y += step;
        row("介面文字", left, y, step, true, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);
        y += step;

        y += 6;
        this.addRenderableWidget(Button.builder(langLabel(cfg),
                        b -> this.minecraft.setScreen(new TranslationLanguageScreen(this)))
                .bounds(left, y, full, 20).build());
        y += 22;
        // Engine for the "translate current screen" (P) hotkey: 機翻 (Google) or AI 精翻.
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorNeoForge.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("AI 翻譯設定（模型 / API 金鑰）..."),
                        b -> this.minecraft.setScreen(new AiConfigScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("快捷鍵設定..."),
                        b -> this.minecraft.setScreen(new TranslationKeybindScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(right, y, rowWidth, 18).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(this.width / 2 - 100, y, 200, 18).build());
    }

    private Component clearLabel() {
        return Component.literal(confirmClear ? "§c再按一次確認清除" : "清除目前語言全部快取");
    }

    private void clearCurrentLanguage(Button button) {
        if (!confirmClear) {
            confirmClear = true;
            button.setMessage(clearLabel());
            return;
        }
        confirmClear = false;
        if (MctranslatorNeoForge.service() != null) MctranslatorNeoForge.service().clearTranslations();
        NeoTextStyle.clearRenderMemo();
        button.setMessage(Component.literal("§a已清除目前語言快取"));
    }

    private static Component langLabel(TranslatorConfig cfg) {
        String s = cfg.followGameLanguage ? "跟隨遊戲（" + cfg.targetLang + "）" : cfg.targetLang;
        return Component.literal("翻譯語言：" + s);
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return Component.literal("介面擷取（快捷鍵）引擎：" + (cfg.aiScreenScan ? "AI 精翻" : "機翻"));
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
            MctranslatorNeoForge.saveConfig();
            b.setMessage(modeText(label, next, threeWay));
        }).bounds(x, y, modeW, 18).build());
        // engine toggle (機翻 / AI)
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorNeoForge.saveConfig(); // also clears the render memo so it re-translates via the new engine
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode, boolean threeWay) {
        String state = threeWay ? mode.displayName()
                : (mode == DisplayMode.ORIGINAL_ONLY ? "原文" : "翻譯");
        return Component.literal(label + "：" + state);
    }

    private static Component aiText(boolean ai) {
        return Component.literal(ai ? "AI 精翻" : "機翻");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        if (MctranslatorNeoForge.service() != null) {
            int done = MctranslatorNeoForge.service().translatedCount();
            int pending = MctranslatorNeoForge.service().pendingCount();
            Component line1 = Component.literal("已翻譯：" + done);
            Component line2 = Component.literal("進行中：" + pending);
            g.drawString(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0x80FF80, false);
            g.drawString(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFD080 : 0x808080, false);
        }

    }

    @Override
    public void onClose() {
        MctranslatorNeoForge.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
