package com.dragonmeow.nyanslate.fabric26;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻譯快捷鍵設定 (MC 26.2) — rebind the mod's hotkeys without leaving the translation settings.
 * Click a row, then press the desired key (Esc cancels). Backed by the same {@link KeyMapping}s
 * registered at startup, so changes also show in vanilla 控制 and persist via {@code options.save()}.
 */
public final class Fabric26KeybindScreen extends Screen {

    private static final int W = 280;

    private final Screen parent;
    private int buttonWidth = W;
    private KeyMapping listening;        // the binding being rebound, or null
    private Button listeningButton;      // its button (to restore the label)
    private String listeningPrefix;      // its label prefix
    private final List<BindingRow> bindingRows = new ArrayList<>();

    public Fabric26KeybindScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.keybind.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        bindingRows.clear();
        buttonWidth = Math.max(80, Math.min(W, this.width - 20));
        int x = this.width / 2 - buttonWidth / 2;
        int y = 44;
        int step = 24;
        y = rebind("screen.nyanslate.keybind.mode", NyanslateFabric26.modeKeyMapping(), x, y, step);
        y = rebind("screen.nyanslate.keybind.retranslate", NyanslateFabric26.retranslateKeyMapping(), x, y, step);
        y = rebind("screen.nyanslate.keybind.screenscan", NyanslateFabric26.screenScanKeyMapping(), x, y, step);
        y = rebind("screen.nyanslate.keybind.toggle", NyanslateFabric26.toggleKeyMapping(), x, y, step);
        y += 8;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.nyanslate.keybind.reset"), b -> resetBindings())
                .bounds(this.width / 2 - buttonWidth / 2, y, buttonWidth, 20).build());
        y += step;
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - buttonWidth / 2, y, buttonWidth, 20).build());
    }

    private int rebind(String prefix, KeyMapping key, int x, int y, int step) {
        if (key == null) return y;
        Button b = Button.builder(rebindLabel(prefix, key), btn -> {
            this.listening = key;
            this.listeningButton = btn;
            this.listeningPrefix = prefix;
            btn.setMessage(Component.translatable("screen.nyanslate.keybind.listening"));
        }).bounds(x, y, buttonWidth, 20).build();
        this.addRenderableWidget(b);
        this.bindingRows.add(new BindingRow(prefix, key, b));
        return y + step;
    }

    private void resetBindings() {
        if (this.minecraft == null) return;
        this.listening = null;
        this.listeningButton = null;
        this.listeningPrefix = null;
        for (BindingRow row : this.bindingRows) {
            row.key().setKey(row.key().getDefaultKey());
            row.button().setMessage(rebindLabel(row.prefix(), row.key()));
        }
        KeyMapping.resetMapping();
        this.minecraft.options.save();
    }

    private record BindingRow(String prefix, KeyMapping key, Button button) {
    }

    /** Whether this screen is currently waiting for the next keypress to bind. */
    public boolean isListening() {
        return this.listening != null;
    }

    private static Component rebindLabel(String prefix, KeyMapping k) {
        return Component.translatable(prefix).append(k.getTranslatedKeyMessage());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.listening != null && this.minecraft != null) {
            if (!event.isEscape()) {
                this.listening.setKey(InputConstants.getKey(event));
                KeyMapping.resetMapping();
                this.minecraft.options.save();
            }
            if (this.listeningButton != null) {
                this.listeningButton.setMessage(rebindLabel(this.listeningPrefix, this.listening));
            }
            this.listening = null;
            this.listeningButton = null;
            return true; // consume (also stops Esc from closing the screen mid-rebind)
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        Component hint = Component.translatable("screen.nyanslate.keybind.hint");
        graphics.centeredText(this.font, hint, this.width / 2, this.height - 30, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent);
    }
}
