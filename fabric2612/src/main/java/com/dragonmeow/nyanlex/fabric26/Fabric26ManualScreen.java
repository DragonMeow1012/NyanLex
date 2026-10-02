package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.ManualPanel;
import com.dragonmeow.nyanlex.config.SettingsModel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 說明書: the stand-alone manual screen (scrollable sections, 返回 at the bottom, Escape also
 * returns). All layout and input logic lives in core's {@link ManualPanel}.
 */
public final class Fabric26ManualScreen extends Screen {
    private final Screen parent;
    private ManualPanel panel;

    public Fabric26ManualScreen(Screen parent) {
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (panel.mouseClicked((int) event.x(), (int) event.y(), event.button()) == ManualPanel.BACK) onClose();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        panel.mouseDragged((int) event.x(), (int) event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.mouseReleased();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        panel.mouseScrolled(scrollY);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (panel.keyPressed(event.key()) == ManualPanel.BACK) onClose();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        panel.render(new GuiCanvas(g, this.font), mouseX, mouseY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
