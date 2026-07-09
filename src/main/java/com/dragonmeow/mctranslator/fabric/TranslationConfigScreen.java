package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.config.TranslatorConfig;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 翻譯設定 — per-surface translation settings. Each row has a mode button (chat &amp;
 * tooltip: 原文／原文＋翻譯／只有翻譯; single-line surfaces: 原文／翻譯) and an engine
 * toggle (機翻 Google / AI 精翻). Saved immediately.
 */
public final class TranslationConfigScreen extends Screen {

    private static final int W = 280;
    private static final int AI_W = 70;

    private final Screen parent;
    private KeyMapping listening;       // keybind being rebound, or null
    private Button listeningButton;     // the rebind button currently being edited
    private String listeningPrefix;     // its label prefix

    public TranslationConfigScreen(Screen parent) {
        super(Component.literal("翻譯設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorFabric.config();
        int x = this.width / 2 - W / 2;
        int y = 28;
        int step = 22;

        y = row("聊天", x, y, step, true, () -> cfg.chatMode, m -> cfg.chatMode = m, () -> cfg.aiChat, v -> cfg.aiChat = v);
        y = row("物品名稱／說明（提示與手持）", x, y, step, true, () -> cfg.tooltipMode, m -> cfg.tooltipMode = m, () -> cfg.aiTooltip, v -> cfg.aiTooltip = v);
        y = row("記分板", x, y, step, true, () -> cfg.scoreboardMode, m -> cfg.scoreboardMode = m, () -> cfg.aiScoreboard, v -> cfg.aiScoreboard = v);
        y = row("名牌／全息", x, y, step, true, () -> cfg.nameMode, m -> cfg.nameMode = m, () -> cfg.aiName, v -> cfg.aiName = v);
        y = row("Boss 血條", x, y, step, true, () -> cfg.bossBarMode, m -> cfg.bossBarMode = m, () -> cfg.aiBossBar, v -> cfg.aiBossBar = v);
        y = row("標題／副標題", x, y, step, true, () -> cfg.titleMode, m -> cfg.titleMode = m, () -> cfg.aiTitle, v -> cfg.aiTitle = v);
        y = row("動作列訊息", x, y, step, true, () -> cfg.actionBarMode, m -> cfg.actionBarMode = m, () -> cfg.aiActionBar, v -> cfg.aiActionBar = v);
        y = row("書籍／講台書頁面", x, y, step, true, () -> cfg.bookMode, m -> cfg.bookMode = m, () -> cfg.aiBook, v -> cfg.aiBook = v);
        y = row("介面文字（光影／模組設定）", x, y, step, true, () -> cfg.screenTextMode, m -> cfg.screenTextMode = m, () -> cfg.aiScreenText, v -> cfg.aiScreenText = v);

        y += 4;
        // Output language: 跟隨遊戲 → 繁體中文 (zh-TW) → 簡體中文 (zh-CN). 跟隨遊戲 keeps it synced to
        // Minecraft's own language; a fixed 繁/簡 retargets + wipes caches so everything re-translates.
        this.addRenderableWidget(Button.builder(langLabel(cfg), b -> {
            cycleLang(cfg);
            if (!cfg.followGameLanguage && MctranslatorFabric.service() != null) {
                MctranslatorFabric.service().setTargetLang(cfg.targetLang);
            }
            MctranslatorFabric.saveConfig();
            b.setMessage(langLabel(cfg));
        }).bounds(x, y, W, 20).build());
        y += 22;
        // Keep the on-disk translation cache across game restarts (so AI 精翻 results are
        // not re-fetched every launch). Takes effect on next start.
        this.addRenderableWidget(Button.builder(cacheLabel(cfg), b -> {
            cfg.clearDiskCacheOnStart = !cfg.clearDiskCacheOnStart;
            MctranslatorFabric.saveConfig();
            b.setMessage(cacheLabel(cfg));
        }).bounds(x, y, W, 20).build());
        y += 22;
        // Engine for the "translate current screen" (P) hotkey: 機翻 (Google) or AI 精翻.
        this.addRenderableWidget(Button.builder(screenScanEngineLabel(cfg), b -> {
            cfg.aiScreenScan = !cfg.aiScreenScan;
            MctranslatorFabric.saveConfig();
            b.setMessage(screenScanEngineLabel(cfg));
        }).bounds(x, y, W, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("AI 翻譯設定（模型 / API 金鑰）..."),
                        b -> this.minecraft.setScreen(new AiConfigScreen(this)))
                .bounds(x, y, W, 20).build());
        y += 24;
        // Rebindable hotkeys.
        KeyMapping rk = MctranslatorFabric.retranslateKeyMapping();
        if (rk != null) {
            this.addRenderableWidget(rebindButton(rk, "重新翻譯指向物品（快捷鍵）：", x, y));
            y += 22;
        }
        KeyMapping sk = MctranslatorFabric.screenScanKeyMapping();
        if (sk != null) {
            this.addRenderableWidget(rebindButton(sk, "翻譯目前介面按鈕／選項（快捷鍵）：", x, y));
            y += 22;
        }
        KeyMapping tk = MctranslatorFabric.toggleKeyMapping();
        if (tk != null) {
            this.addRenderableWidget(rebindButton(tk, "快速切換 原文／翻譯（快捷鍵）：", x, y));
            y += 22;
        }
        y += 2;
        this.addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
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

    /** A rebind button that, when clicked, listens for the next key and rebinds {@code key}. */
    private Button rebindButton(KeyMapping key, String prefix, int x, int y) {
        return Button.builder(rebindLabel(prefix, key), b -> {
            listening = key;
            listeningButton = b;
            listeningPrefix = prefix;
            b.setMessage(Component.literal("§e> 按任意鍵（Esc 取消）<"));
        }).bounds(x, y, W, 20).build();
    }

    private static Component rebindLabel(String prefix, KeyMapping k) {
        return Component.literal(prefix).append(k.getTranslatedKeyMessage());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null && this.minecraft != null) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                this.minecraft.options.setKey(listening, InputConstants.getKey(keyCode, scanCode));
                KeyMapping.resetMapping();
            }
            if (listeningButton != null) listeningButton.setMessage(rebindLabel(listeningPrefix, listening));
            listening = null;
            listeningButton = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int row(String label, int x, int y, int step, boolean threeWay,
                    Supplier<DisplayMode> getMode, Consumer<DisplayMode> setMode,
                    BooleanSupplier getAi, Consumer<Boolean> setAi) {
        int modeW = W - AI_W - 4;
        // mode button
        this.addRenderableWidget(Button.builder(modeText(label, getMode.get(), threeWay), b -> {
            DisplayMode next = threeWay
                    ? getMode.get().next()
                    : (getMode.get() == DisplayMode.ORIGINAL_ONLY ? DisplayMode.TRANSLATION : DisplayMode.ORIGINAL_ONLY);
            setMode.accept(next);
            MctranslatorFabric.saveConfig();
            b.setMessage(modeText(label, next, threeWay));
        }).bounds(x, y, modeW, 20).build());
        // engine toggle (機翻 / AI)
        this.addRenderableWidget(Button.builder(aiText(getAi.getAsBoolean()), b -> {
            boolean next = !getAi.getAsBoolean();
            setAi.accept(next);
            MctranslatorFabric.saveConfig(); // also clears the render memo so it re-translates via the new engine
            b.setMessage(aiText(next));
        }).bounds(x + modeW + 4, y, AI_W, 20).build());
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
        if (MctranslatorFabric.service() != null) {
            int done = MctranslatorFabric.service().translatedCount();
            int pending = MctranslatorFabric.service().pendingCount();
            Component line1 = Component.literal("已翻譯：" + done);
            Component line2 = Component.literal("進行中：" + pending);
            g.drawString(this.font, line1, this.width - this.font.width(line1) - 6, 6, 0x80FF80, false);
            g.drawString(this.font, line2, this.width - this.font.width(line2) - 6, 17,
                    pending > 0 ? 0xFFD080 : 0x808080, false);
        }

        String[] hints = {
                "左：顯示模式　右：翻譯引擎（機翻 Google／AI 精翻，需先在下方設定金鑰）",
                "可在「控制」綁定快捷鍵：開啟翻譯設定／清除並重新翻譯",
        };
        g.pose().pushPose();
        g.pose().scale(0.5f, 0.5f, 1.0f);
        int hy = (this.height - 20) * 2;
        for (String h : hints) {
            g.drawString(this.font, h, this.width - this.font.width(h) / 2, hy, 0xA0A0A0, false);
            hy += 12;
        }
        g.pose().popPose();
    }

    @Override
    public void onClose() {
        MctranslatorFabric.saveConfig();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
