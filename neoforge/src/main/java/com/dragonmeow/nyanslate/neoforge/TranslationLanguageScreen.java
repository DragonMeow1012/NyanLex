package com.dragonmeow.nyanslate.neoforge;

import com.dragonmeow.nyanslate.config.TranslationLanguages;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.FontOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minecraft's language-selection UI, with selection redirected to the translation target. */
public final class TranslationLanguageScreen extends OptionsSubScreen {
    private static final Component WARNING = Component.translatable("options.languageAccuracyWarning").withColor(-4539718);
    private LanguageSelectionList languageList;
    private EditBox search;

    public TranslationLanguageScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanslate.language.target_title"));
        this.layout.setFooterHeight(53);
    }

    @Override protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 200, 15, Component.empty()));
        this.search.setHint(Component.translatable("screen.nyanslate.language.search"));
        this.search.setResponder(value -> {
            if (this.languageList != null) this.languageList.filterEntries(value);
        });
        this.layout.setHeaderHeight(36);
    }

    @Override protected void addContents() {
        this.languageList = this.layout.addToContents(new LanguageSelectionList(this.minecraft));
    }

    @Override protected void addOptions() {}

    @Override protected void addFooter() {
        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical()).spacing(8);
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(new StringWidget(WARNING, this.font));
        LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(8));
        buttons.addChild(Button.builder(Component.translatable("options.font"),
                b -> this.minecraft.setScreen(new FontOptionsScreen(this, this.options))).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onDone()).build());
    }

    @Override protected void repositionElements() {
        super.repositionElements();
        this.languageList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        LanguageSelectionList.Entry selected = this.languageList.getSelected();
        if (selected != null) choose(selected.code);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(String minecraftCode) {
        TranslatorConfig cfg = NyanslateNeoForge.config();
        cfg.followGameLanguage = minecraftCode == null;
        String selected = minecraftCode == null
                ? minecraft.getLanguageManager().getSelected() : minecraftCode;
        String target = TranslationLanguages.fromMinecraftCode(selected);
        if (NyanslateNeoForge.service() != null) NyanslateNeoForge.service().setTargetLang(target);
        else cfg.targetLang = target;
        NeoTextStyle.clearRenderMemo();
        NyanslateNeoForge.saveConfig();
        this.minecraft.setScreen(this.lastScreen);
    }

    private final class LanguageSelectionList extends ObjectSelectionList<LanguageSelectionList.Entry> {
        private LanguageSelectionList(Minecraft minecraft) {
            super(minecraft, TranslationLanguageScreen.this.width,
                    TranslationLanguageScreen.this.height - 33 - 53, 33, 18);
            replaceWith("");
        }

        private void filterEntries(String filter) {
            replaceWith(filter);
            this.setScrollAmount(0);
        }

        private void replaceWith(String filter) {
            String needle = filter.toLowerCase(Locale.ROOT);
            List<Entry> entries = Minecraft.getInstance().getLanguageManager().getLanguages().entrySet().stream()
                    .filter(e -> needle.isEmpty()
                            || e.getKey().toLowerCase(Locale.ROOT).contains(needle)
                            || e.getValue().name().toLowerCase(Locale.ROOT).contains(needle)
                            || e.getValue().region().toLowerCase(Locale.ROOT).contains(needle))
                    .map(e -> new Entry(e.getKey(), e.getValue().toComponent()))
                    .toList();
            java.util.ArrayList<Entry> all = new java.util.ArrayList<>();
            all.add(new Entry(null, Component.translatable("screen.nyanslate.language.follow")));
            all.addAll(entries);
            this.replaceEntries(all);
            selectCurrent();
        }

        private void selectCurrent() {
            TranslatorConfig cfg = NyanslateNeoForge.config();
            for (Entry entry : this.children()) {
                if ((cfg.followGameLanguage && entry.code == null)
                        || (!cfg.followGameLanguage && entry.code != null
                        && TranslationLanguages.fromMinecraftCode(entry.code).equalsIgnoreCase(cfg.targetLang))) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }

        @Override protected void renderListBackground(GuiGraphics graphics) {
            super.renderListBackground(graphics);
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String code;
            private final Component language;
            private long lastClickTime;

            private Entry(String code, Component language) {
                this.code = code;
                this.language = language;
            }

            @Override public void render(GuiGraphics graphics, int index, int y, int x, int width,
                                         int height, int mouseX, int mouseY, boolean hovered, float delta) {
                graphics.drawCenteredString(TranslationLanguageScreen.this.font, this.language,
                        LanguageSelectionList.this.width / 2, y + height / 2 - 9 / 2, -1);
            }

            @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (CommonInputs.selected(keyCode)) {
                    select();
                    onDone();
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                select();
                if (Util.getMillis() - this.lastClickTime < 250L) onDone();
                this.lastClickTime = Util.getMillis();
                return super.mouseClicked(mouseX, mouseY, button);
            }

            private void select() { LanguageSelectionList.this.setSelected(this); }
            @Override public Component getNarration() { return Component.translatable("narrator.select", this.language); }
        }
    }
}
