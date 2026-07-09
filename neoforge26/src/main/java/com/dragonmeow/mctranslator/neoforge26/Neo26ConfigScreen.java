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
 * Plus output-language (繁體／簡體), disk-cache persistence, and the screen-scan-hotkey engine.
 * Hotkeys themselves are rebindable in vanilla 控制 (registered under the 雜項 category).
 */
public final class Neo26ConfigScreen extends Screen {

    private static final int W = 280;
    private static final int AI_W = 70;

    private final Screen parent;

    public Neo26ConfigScreen(Screen parent) {
        super(Component.literal("翻譯設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        int x = this.width / 2 - W / 2;
        int y = 28;
        int step = 22;

        y = row("聊天", x, y, step, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        y = row("物品名稱／說明（提示與手持）", x, y, step, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y = row("記分板", x, y, step, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        y = row("名牌／全息", x, y, step, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y = row("Boss 血條", x, y, step, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        y = row("標題／副標題", x, y, step, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y = row("動作列訊息", x, y, step, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        y = row("書籍／講台書頁面", x, y, step, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y = row("介面文字（光影／模組設定）", x, y, step, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);

        y += 4;
        // Output language: 跟隨遊戲 → 繁體中文 (zh-TW) → 簡體中文 (zh-CN). 跟隨遊戲 keeps it synced to
        // Minecraft's own language; a fixed 繁/簡 retargets + wipes caches immediately.
        this.addRenderableWidget(Button.builder(langLabel(cfg), b -> {
            cycleLang(cfg);
            if (!cfg.followGameLanguage && MctranslatorNeoForge26.service() != null) {
                MctranslatorNeoForge26.service().setTargetLang(cfg.targetLang);
            }
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(langLabel(cfg));
        }).bounds(x, y, W, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(cacheLabel(cfg), b -> {
            cfg.clearDiskCacheOnStart = !cfg.clearDiskCacheOnStart;
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(cacheLabel(cfg));
        }).bounds(x, y, W, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(x, y, W, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("AI 翻譯設定（模型 / API 金鑰）..."),
                        b -> this.minecraft.setScreenAndShow(new Neo26AiScreen(this)))
                .bounds(x, y, W, 20).build());
        y += 22;
        this.addRenderableWidget(Button.builder(Component.literal("快捷鍵設定..."),
                        b -> this.minecraft.setScreenAndShow(new Neo26KeybindScreen(this)))
                .bounds(x, y, W, 20).build());
        y += 26;
        this.addRenderableWidget(Button.builder(Component.literal("完成"), b -> this.onClose())
                .bounds(this.width / 2 - 100, y, 200, 20).build());
    }

    private static boolean isSimplified(TranslatorConfig cfg) {
        return cfg.targetLang != null && cfg.targetLang.toLowerCase().startsWith("zh-cn");
    }

    /** Cycle the 翻譯語言 control: 跟隨遊戲 → 繁體 → 簡體 → 跟隨遊戲. */
    private static void cycleLang(TranslatorConfig cfg) {
        if (cfg.followGameLanguage) {        // 跟隨遊戲 → 繁體
            cfg.followGameLanguage = false;
            cfg.targetLang = "zh-TW";
        } else if (!isSimplified(cfg)) {     // 繁體 → 簡體
            cfg.targetLang = "zh-CN";
        } else {                             // 簡體 → 跟隨遊戲
            cfg.followGameLanguage = true;
        }
    }

    private static Component langLabel(TranslatorConfig cfg) {
        String s = cfg.followGameLanguage ? "跟隨遊戲（繁/簡）"
                : (isSimplified(cfg) ? "簡體中文 (zh-CN)" : "繁體中文 (zh-TW)");
        return Component.literal("翻譯語言：" + s);
    }

    private static Component cacheLabel(TranslatorConfig cfg) {
        return Component.literal("翻譯快取：" + (cfg.clearDiskCacheOnStart ? "重開遊戲時清除" : "重開遊戲後保留"));
    }

    private static Component screenScanEngineLabel(TranslatorConfig cfg) {
        return Component.literal("介面擷取（快捷鍵）引擎：" + (cfg.aiScreenScan ? "AI 精翻" : "機翻"));
    }

    private int row(String label, int x, int y, int step,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int modeW = W - AI_W - 4;
        this.addRenderableWidget(Button.builder(modeText(label, getMode.get()), b -> {
            DisplayMode next = getMode.get().next();
            setMode.accept(next);
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(modeText(label, next));
        }).bounds(x, y, modeW, 20).build());
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorNeoForge26.saveConfig();
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, AI_W, 20).build());
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
