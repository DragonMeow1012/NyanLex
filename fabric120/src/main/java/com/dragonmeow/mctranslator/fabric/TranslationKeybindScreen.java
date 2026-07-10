package com.dragonmeow.mctranslator.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class TranslationKeybindScreen extends Screen {
    private static final int W = 300;
    private final Screen parent;
    private int buttonWidth = W;
    private KeyMapping listening;
    private Button listeningButton;
    private String listeningPrefix;
    public TranslationKeybindScreen(Screen parent) { super(Component.literal("翻譯快捷鍵")); this.parent = parent; }
    @Override protected void init() {
        buttonWidth = Math.max(80, Math.min(W, width - 20));
        int x = width / 2 - buttonWidth / 2;
        int y = 46;
        y = rebind("重新翻譯指向的物品：", MctranslatorFabric.retranslateKeyMapping(), x, y);
        y = rebind("翻譯目前介面：", MctranslatorFabric.screenScanKeyMapping(), x, y);
        y = rebind("切換原文／翻譯：", MctranslatorFabric.toggleKeyMapping(), x, y);
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(width / 2 - buttonWidth / 2, y + 8, buttonWidth, 20).build());
    }
    private int rebind(String prefix, KeyMapping key, int x, int y) {
        if (key == null) return y;
        Button button = Button.builder(label(prefix, key), b -> {
            listening = key; listeningButton = b; listeningPrefix = prefix;
            b.setMessage(Component.literal("§e按任意鍵（Esc 取消）"));
        }).bounds(x, y, buttonWidth, 20).build();
        addRenderableWidget(button);
        return y + 24;
    }
    private static Component label(String prefix, KeyMapping key) {
        return Component.literal(prefix).append(key.getTranslatedKeyMessage());
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null && minecraft != null) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                minecraft.options.setKey(listening, InputConstants.getKey(keyCode, scanCode));
                KeyMapping.resetMapping();
                minecraft.options.save();
            }
            if (listeningButton != null) listeningButton.setMessage(label(listeningPrefix, listening));
            listening = null; listeningButton = null; return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
