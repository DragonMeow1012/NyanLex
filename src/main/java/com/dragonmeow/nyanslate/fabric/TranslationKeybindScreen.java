package com.dragonmeow.nyanslate.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class TranslationKeybindScreen extends Screen {
    private static final int W = 300;
    private final Screen parent;
    private int buttonWidth = W;
    private KeyMapping listening;
    private Button listeningButton;
    private String listeningPrefix;
    private final List<BindingRow> bindingRows = new ArrayList<>();

    public TranslationKeybindScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.keybind.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        bindingRows.clear();
        buttonWidth = Math.max(80, Math.min(W, width - 20));
        int x = width / 2 - buttonWidth / 2;
        int y = 46;
        y = rebind("screen.nyanslate.keybind.retranslate", NyanslateFabric.retranslateKeyMapping(), x, y);
        y = rebind("screen.nyanslate.keybind.screenscan", NyanslateFabric.screenScanKeyMapping(), x, y);
        y = rebind("screen.nyanslate.keybind.toggle", NyanslateFabric.toggleKeyMapping(), x, y);
        addRenderableWidget(Button.builder(Component.translatable("screen.nyanslate.keybind.reset"), b -> resetBindings())
                .bounds(width / 2 - buttonWidth / 2, y + 8, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - buttonWidth / 2, y + 32, buttonWidth, 20).build());
    }

    private int rebind(String prefix, KeyMapping key, int x, int y) {
        if (key == null) return y;
        Button button = Button.builder(label(prefix, key), b -> {
            listening = key;
            listeningButton = b;
            listeningPrefix = prefix;
            b.setMessage(Component.translatable("screen.nyanslate.keybind.listening"));
        }).bounds(x, y, buttonWidth, 20).build();
        addRenderableWidget(button);
        bindingRows.add(new BindingRow(prefix, key, button));
        return y + 24;
    }

    private void resetBindings() {
        if (minecraft == null) return;
        listening = null;
        listeningButton = null;
        listeningPrefix = null;
        for (BindingRow row : bindingRows) {
            minecraft.options.setKey(row.key(), row.key().getDefaultKey());
            row.button().setMessage(label(row.prefix(), row.key()));
        }
        KeyMapping.resetMapping();
        minecraft.options.save();
    }

    private record BindingRow(String prefix, KeyMapping key, Button button) {
    }

    /** Whether this screen is currently waiting for the next keypress to bind. */
    public boolean isListening() {
        return listening != null;
    }

    private static Component label(String prefix, KeyMapping key) {
        return Component.translatable(prefix).append(key.getTranslatedKeyMessage());
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null && minecraft != null) {
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                minecraft.options.setKey(listening, InputConstants.getKey(keyCode, scanCode));
                KeyMapping.resetMapping();
                minecraft.options.save();
            }
            if (listeningButton != null) listeningButton.setMessage(label(listeningPrefix, listening));
            listening = null;
            listeningButton = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
