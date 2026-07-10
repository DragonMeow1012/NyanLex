package com.dragonmeow.mctranslator.neoforge26;

import com.dragonmeow.mctranslator.config.DisplayMode;
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
public final class Neo26ConfigScreen extends Screen {

    private static final int W = 280;
    private static final int AI_W = 70;

    private final Screen parent;
    private int rowWidth = W;
    private boolean confirmClear;

    public Neo26ConfigScreen(Screen parent) {
        super(Component.literal("翻譯設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        rowWidth = Math.min(W, Math.max(120, (this.width - 12) / 2));
        int gap = 6;
        int left = this.width / 2 - rowWidth - gap / 2;
        int right = this.width / 2 + gap / 2;
        int y = 24;
        int step = 20;

        row("聊天", left, y, step, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        row("物品／提示", right, y, step, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y += step;
        row("記分板", left, y, step, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        row("名牌／全息", right, y, step, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y += step;
        row("Boss 血條", left, y, step, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        row("標題／副標題", right, y, step, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y += step;
        row("動作列", left, y, step, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        row("書籍／講台", right, y, step, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y += step;
        row("介面文字", left, y, step, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);

        y += step + 6;
        this.addRenderableWidget(Button.builder(langLabel(cfg),
                        b -> this.minecraft.setScreenAndShow(new Neo26LanguageScreen(this)))
                .bounds(left, y, rowWidth * 2 + gap, 18).build());
        y += step;
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("AI 翻譯設定（模型 / API 金鑰）..."),
                        b -> this.minecraft.setScreenAndShow(new Neo26AiScreen(this)))
                .bounds(right, y, rowWidth, 18).build());
        y += step;
        this.addRenderableWidget(Button.builder(Component.literal("快捷鍵設定..."),
                        b -> this.minecraft.setScreenAndShow(new Neo26KeybindScreen(this)))
                .bounds(left, y, rowWidth, 18).build());
        this.addRenderableWidget(Button.builder(clearLabel(), this::clearCurrentLanguage)
                .bounds(right, y, rowWidth, 18).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.literal("完成"), b -> this.onClose())
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
        if (MctranslatorNeoForge26.service() != null) MctranslatorNeoForge26.service().clearTranslations();
        Neo26TextStyle.clearRenderMemo();
        button.setMessage(Component.literal("§a已清除目前語言快取"));
    }

    private static Component langLabel(TranslatorConfig cfg) {
        String s = cfg.followGameLanguage ? "跟隨遊戲（" + cfg.targetLang + "）" : cfg.targetLang;
        return Component.literal("翻譯語言：" + s);
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return Component.literal("介面擷取（快捷鍵）引擎：" + (cfg.aiScreenScan ? "AI 精翻" : "機翻"));
    }

    private int row(String label, int x, int y, int step,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int engineW = Math.min(AI_W, Math.max(52, rowWidth / 3));
        int modeW = rowWidth - engineW - 4;
        this.addRenderableWidget(Button.builder(modeText(label, getMode.get()), b -> {
            DisplayMode next = getMode.get().next();
            setMode.accept(next);
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(modeText(label, next));
        }).bounds(x, y, modeW, 18).build());
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, engineW, 18).build());
        return y + step;
    }

    private static Component modeText(String label, DisplayMode mode) {
        return Component.literal(label + "：" + mode.displayName());
    }

    private static Component aiText(boolean ai) {
        return Component.literal(ai ? "AI 精翻" : "機翻");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        // Top-right progress: already-translated (cached) + in-flight (queued/fetching) counts.
        // NB: 26.2 skips draws whose colour has alpha 0, so colours are fully opaque (0xFF…).
        if (MctranslatorNeoForge26.service() != null) {
            int done = MctranslatorNeoForge26.service().translatedCount();
            int pending = MctranslatorNeoForge26.service().pendingCount();
            Component line1 = Component.literal("已翻譯：" + done);
            Component line2 = Component.literal("進行中：" + pending);
            graphics.text(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0xFF80FF80, false);
            graphics.text(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFFFD080 : 0xFF808080, false);
        }
    }

    @Override
    public void onClose() {
        MctranslatorNeoForge26.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
