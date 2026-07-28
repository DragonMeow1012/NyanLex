package com.dragonmeow.mctranslator.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searchable live Codex model catalog for the Java 8 GUI API. */
final class LegacyCodexModelScreen extends Screen {
    private final Screen parent;
    private ModelList models;
    private EditBox search;

    LegacyCodexModelScreen(Screen parent) {
        super(new TranslatableComponent("screen.mctranslator.ai.codex.model_picker.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        models = new ModelList(minecraft);
        addWidget(models);
        search = new EditBox(font, width / 2 - 120, 24, 240, 20, new TextComponent(""));
        search.setResponder(models::filter);
        addButton(search);
        addButton(new Button(width / 2 - 100, height - 38, 200, 20,
                new TranslatableComponent("gui.done"), button -> applyAndClose()));
        setFocused(search);
    }

    private void applyAndClose() {
        ModelEntry selected = models == null ? null : models.getSelected();
        if (selected != null) {
            LegacyConfig cfg = LegacyTranslatorMod.config();
            cfg.codexModel = selected.option.model();
            LegacyAiConfigScreen.normalizeEffort(cfg, selected.option);
            LegacyTranslatorMod.saveConfig();
        }
        minecraft.setScreen(parent);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && models != null && models.getSelected() != null) {
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        models.render(pose, mouseX, mouseY, delta);
        GuiComponent.drawCenteredString(pose, font, title, width / 2, 8, 0xFFFFFF);
        super.render(pose, mouseX, mouseY, delta);
    }

    @Override public void tick() { if (search != null) search.tick(); }
    @Override public void onClose() { minecraft.setScreen(parent); }

    private final class ModelList extends ObjectSelectionList<ModelEntry> {
        ModelList(Minecraft minecraft) {
            super(minecraft, LegacyCodexModelScreen.this.width, LegacyCodexModelScreen.this.height,
                    48, LegacyCodexModelScreen.this.height - 61, 20);
            filter("");
        }

        void filter(String value) {
            String needle = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            List<ModelEntry> entries = new ArrayList<ModelEntry>();
            ModelEntry selected = null;
            LegacyCodexClient client = LegacyTranslatorMod.codexClient();
            if (client != null) for (LegacyCodexClient.ModelOption option : client.cachedModels()) {
                if (!needle.isEmpty() && !option.model().toLowerCase(Locale.ROOT).contains(needle)
                        && !option.displayName().toLowerCase(Locale.ROOT).contains(needle)) continue;
                ModelEntry entry = new ModelEntry(option);
                entries.add(entry);
                if (option.model().equals(LegacyTranslatorMod.config().codexModel)) selected = entry;
            }
            replaceEntries(entries);
            setSelected(selected);
            setScrollAmount(0);
        }

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
    }

    private final class ModelEntry extends ObjectSelectionList.Entry<ModelEntry> {
        final LegacyCodexClient.ModelOption option;
        final Component label;
        long lastClick;
        ModelEntry(LegacyCodexClient.ModelOption option) {
            this.option = option;
            this.label = new TextComponent(option.displayName().equals(option.model())
                    ? option.displayName() : option.displayName() + " (" + option.model() + ")");
        }
        @Override public void render(PoseStack pose, int index, int y, int x, int rowWidth, int rowHeight,
                                     int mouseX, int mouseY, boolean hovered, float delta) {
            String text = label.getString();
            font.drawShadow(pose, text, width / 2f - font.width(text) / 2f, y + 2, 0xFFFFFF);
        }
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            models.setSelected(this);
            long now = Util.getMillis();
            if (now - lastClick < 250L) applyAndClose();
            lastClick = now;
            return true;
        }
    }
}