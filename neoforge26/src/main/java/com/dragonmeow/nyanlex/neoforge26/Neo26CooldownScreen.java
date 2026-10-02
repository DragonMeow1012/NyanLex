package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.config.TranslatorConfig;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 翻譯請求冷卻設定 (MC 26.x) — the two outbound-pacing cycles ({@link TranslatorConfig#requestCooldownMs}
 * and {@link TranslatorConfig#batchWindowMs}) moved off the crowded main settings screen.
 * Values and cycling behaviour are unchanged; only the buttons' home moved.
 */
public final class Neo26CooldownScreen extends Screen {

    private static final int W = 280;
    private final Screen parent;

    public Neo26CooldownScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.cooldown.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanLexNeoForge26.config();
        int rowWidth = Math.max(120, Math.min(W, this.width - 20));
        int x = this.width / 2 - rowWidth / 2;
        int y = 50;

        this.addRenderableWidget(Button.builder(Neo26ConfigScreen.cooldownLabel(cfg), b -> {
            cfg.requestCooldownMs = Neo26ConfigScreen.nextCooldown(cfg.requestCooldownMs);
            NyanLexNeoForge26.saveConfig();
            b.setMessage(Neo26ConfigScreen.cooldownLabel(cfg));
        }).bounds(x, y, rowWidth, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Neo26ConfigScreen.batchWindowLabel(cfg), b -> {
            cfg.batchWindowMs = Neo26ConfigScreen.nextBatchWindow(cfg.batchWindowMs);
            NyanLexNeoForge26.saveConfig();
            b.setMessage(Neo26ConfigScreen.batchWindowLabel(cfg));
        }).bounds(x, y, rowWidth, 20).build());
        y += 32;

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x, y, rowWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        NyanLexNeoForge26.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
