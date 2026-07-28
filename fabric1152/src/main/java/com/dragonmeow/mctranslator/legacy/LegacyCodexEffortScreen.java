package com.dragonmeow.mctranslator.legacy;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Model-specific reasoning effort picker for the Java 8 GUI API. */
final class LegacyCodexEffortScreen extends Screen {
    private final Screen parent;
    private EffortList efforts;

    LegacyCodexEffortScreen(Screen parent) {
        super(new TranslatableComponent("screen.mctranslator.ai.codex.effort_picker.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        efforts = new EffortList(minecraft);
        children.add(efforts);
        addButton(new Button(width / 2 - 100, height - 38, 200, 20,
                new TranslatableComponent("gui.done").getString(), button -> applyAndClose()));
    }

    private List<String> supportedEfforts() {
        LegacyCodexClient client = LegacyTranslatorMod.codexClient();
        if (client == null) return Collections.emptyList();
        String model = LegacyTranslatorMod.config().codexModel;
        for (LegacyCodexClient.ModelOption option : client.cachedModels())
            if (option.model().equals(model)) return option.reasoningEfforts();
        return Collections.emptyList();
    }

    private void applyAndClose() {
        EffortEntry selected = efforts == null ? null : efforts.getSelected();
        if (selected != null) {
            LegacyTranslatorMod.config().codexReasoningEffort = selected.effort;
            LegacyTranslatorMod.saveConfig();
        }
        minecraft.setScreen(parent);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && efforts != null && efforts.getSelected() != null) {
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        efforts.render(mouseX, mouseY, delta);
        font.drawShadow(title.getString(), width / 2f - font.width(title.getString()) / 2f, 8, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    private final class EffortList extends ObjectSelectionList<EffortEntry> {
        EffortList(Minecraft minecraft) {
            super(minecraft, LegacyCodexEffortScreen.this.width, LegacyCodexEffortScreen.this.height,
                    28, LegacyCodexEffortScreen.this.height - 61, 20);
            List<EffortEntry> entries = new ArrayList<EffortEntry>();
            EffortEntry selected = null;
            for (String effort : supportedEfforts()) {
                EffortEntry entry = new EffortEntry(effort);
                entries.add(entry);
                if (effort.equals(LegacyTranslatorMod.config().codexReasoningEffort)) selected = entry;
            }
            replaceEntries(entries);
            setSelected(selected);
        }
        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
    }

    private final class EffortEntry extends ObjectSelectionList.Entry<EffortEntry> {
        final String effort;
        final Component label;
        long lastClick;
        EffortEntry(String effort) { this.effort = effort; this.label = new TextComponent(effort); }
        @Override public void render(int index, int y, int x, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float delta) {
            font.drawShadow(effort, width / 2f - font.width(effort) / 2f, y + 2, 0xFFFFFF);
        }
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            efforts.setSelected(this);
            long now = Util.getMillis();
            if (now - lastClick < 250L) applyAndClose();
            lastClick = now;
            return true;
        }
    }
}