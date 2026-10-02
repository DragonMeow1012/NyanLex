package com.dragonmeow.nyanslate.legacy;


import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LegacyLanguageScreen extends Screen {
    private final Screen parent;
    private LanguageList languages;
    private EditBox search;

    LegacyLanguageScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanslate.language.target_title"));
        this.parent = parent;
    }

    @Override protected void init() {
        languages = new LanguageList(minecraft);
        this.children.add(languages);
        search = new EditBox(font, width / 2 - 100, 24, 200, 20, "");
        search.setResponder(languages::filter);
        addButton(search);
        addButton(new Button(width / 2 - 100, height - 38, 200, 20,
                new TranslatableComponent("gui.done").getString(), button -> applyAndClose()));
        setFocused(search);
    }

    private void applyAndClose() {
        LanguageEntry selected = languages.getSelected();
        if (selected != null) {
            LegacyConfig config = LegacyTranslatorMod.config();
            config.followGameLanguage = selected.code == null;
            if (selected.code != null) config.targetLang = LegacyTranslatorMod.mapLanguage(selected.code);
            LegacyTranslatorMod.saveConfig();
        }
        minecraft.setScreen(parent);
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        languages.render(mouseX, mouseY, delta);
        font.drawShadow(title.getString(), width / 2f - font.width(title.getString()) / 2f, 8, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    private final class LanguageList extends ObjectSelectionList<LanguageEntry> {
        LanguageList(Minecraft minecraft) {
            super(minecraft, LegacyLanguageScreen.this.width, LegacyLanguageScreen.this.height,
                    48, LegacyLanguageScreen.this.height - 61, 18);
            filter("");
        }

        void filter(String value) {
            String needle = value == null ? "" : value.toLowerCase(Locale.ROOT);
            List<LanguageEntry> entries = new ArrayList<LanguageEntry>();
            entries.add(new LanguageEntry(null,
                    new TranslatableComponent("screen.nyanslate.language.follow")));
            for (Language info : minecraft.getLanguageManager().getLanguages()) {
                if (needle.isEmpty() || info.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || info.getName().toLowerCase(Locale.ROOT).contains(needle)
                        || info.getRegion().toLowerCase(Locale.ROOT).contains(needle)) {
                    entries.add(new LanguageEntry(info.getCode(), new TextComponent(info.toString())));
                }
            }
            replaceEntries(entries);
            setScrollAmount(0);
        }

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
    }

    private final class LanguageEntry extends ObjectSelectionList.Entry<LanguageEntry> {
        final String code;
        final Component label;

        LanguageEntry(String code, Component label) {
            this.code = code;
            this.label = label;
        }

        @Override public void render(int index, int y, int x, int rowWidth,
                                     int rowHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            font.drawShadow(label.getString(), width / 2f - font.width(label.getString()) / 2f, y + 1, 0xFFFFFF);
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            languages.setSelected(this);
            return true;
        }
    }
}
