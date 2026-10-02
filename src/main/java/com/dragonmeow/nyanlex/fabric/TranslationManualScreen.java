package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.ManualPanel;
import com.dragonmeow.nyanlex.config.SettingsModel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 說明書: the stand-alone manual screen (scrollable sections, 返回 at the bottom, Escape also
 * returns). All layout and input logic lives in core's {@link ManualPanel}.
 */
public final class TranslationManualScreen extends Screen {
    private final Screen parent;
    private ManualPanel panel;

    public TranslationManualScreen(Screen parent) {
        super(Component.translatable(SettingsModel.KEY_MANUAL_TITLE));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (panel == null) {
            List<ManualPanel.Section> sections = new ArrayList<>();
            for (int i = 1; i <= SettingsModel.MANUAL_SECTIONS; i++) {
                sections.add(new ManualPanel.Section(
                        Component.translatable(SettingsModel.manualTitleKey(i)).getString(),
                        Component.translatable(SettingsModel.manualBodyKey(i)).getString()));
            }
            panel = new ManualPanel(text -> this.font.width(text),
                    Component.translatable(SettingsModel.KEY_MANUAL_TITLE).getString(),
                    Component.translatable(SettingsModel.KEY_MANUAL_BACK).getString(), sections);
        }
        panel.resize(this.width, this.height);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (panel.mouseClicked((int) mouseX, (int) mouseY, button) == ManualPanel.BACK) onClose();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        panel.mouseDragged((int) mouseX, (int) mouseY);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (panel.keyPressed(keyCode) == ManualPanel.BACK) onClose();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
