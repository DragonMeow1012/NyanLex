package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * 翻譯請求冷卻設定 — the two outbound-pacing cycles ({@link TranslatorConfig#requestCooldownMs}
 * and {@link TranslatorConfig#batchWindowMs}) moved off the crowded main settings screen.
 * Values and cycling behaviour are unchanged; only the buttons' home moved.
 */
public final class TranslationCooldownScreen extends Screen {

    private static final int W = 260;
    private final Screen parent;

    public TranslationCooldownScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanlex.cooldown.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanLexFabric.config();
        int rowWidth = Math.max(120, Math.min(W, this.width - 20));
        int x = this.width / 2 - rowWidth / 2;
        int y = 50;

        this.addRenderableWidget(LegacyButton.builder(TranslationConfigScreen.cooldownLabel(cfg), b -> {
            cfg.requestCooldownMs = TranslationConfigScreen.nextCooldown(cfg.requestCooldownMs);
            NyanLexFabric.saveConfig();
            b.setMessage(TranslationConfigScreen.cooldownLabel(cfg));
        }).bounds(x, y, rowWidth, 20).build());
        y += 24;
        this.addRenderableWidget(LegacyButton.builder(TranslationConfigScreen.batchWindowLabel(cfg), b -> {
            cfg.batchWindowMs = TranslationConfigScreen.nextBatchWindow(cfg.batchWindowMs);
            NyanLexFabric.saveConfig();
            b.setMessage(TranslationConfigScreen.batchWindowLabel(cfg));
        }).bounds(x, y, rowWidth, 20).build());
        y += 32;

        this.addRenderableWidget(LegacyButton.builder(new TranslatableComponent("gui.done"), b -> onClose())
                .bounds(x, y, rowWidth, 20).build());
    }

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(g, this.font, this.title, this.width / 2, 16, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        NyanLexFabric.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
