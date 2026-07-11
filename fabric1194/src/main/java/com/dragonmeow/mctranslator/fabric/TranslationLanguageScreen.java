package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.TranslationLanguages;
import com.dragonmeow.mctranslator.config.TranslatorConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.OptionsSubScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Vanilla 1.20.1 language-selection UI, with a native-styled search field. */
public final class TranslationLanguageScreen extends OptionsSubScreen {
    private static final Component WARNING = Component.literal("(")
            .append(Component.translatable("options.languageWarning")).append(")").withStyle(ChatFormatting.GRAY);
    private LanguageSelectionList languageList;
    private EditBox search;

    public TranslationLanguageScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.mctranslator.language.target_title"));
    }

    @Override protected void init() {
        this.languageList = new LanguageSelectionList(this.minecraft);
        this.addWidget(this.languageList);
        this.search = new EditBox(this.font, this.width / 2 - 100, 24, 200, 20, Component.empty());
        this.search.setHint(Component.translatable("screen.mctranslator.language.search"));
        this.search.setResponder(this.languageList::filterEntries);
        this.addRenderableWidget(this.search);
        this.addRenderableWidget(this.options.forceUnicodeFont().createButton(
                this.options, this.width / 2 - 155, this.height - 38, 150));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onDone())
                .bounds(this.width / 2 + 5, this.height - 38, 150, 20).build());
        super.init();
        this.setFocused(this.search);
    }

    private void onDone() {
        LanguageSelectionList.Entry selected = this.languageList.getSelected();
        if (selected != null) choose(selected.code);
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(String minecraftCode) {
        TranslatorConfig cfg = MctranslatorFabric.config();
        cfg.followGameLanguage = minecraftCode == null;
        String selected = minecraftCode == null ? minecraft.getLanguageManager().getSelected() : minecraftCode;
        String target = TranslationLanguages.fromMinecraftCode(selected);
        if (MctranslatorFabric.service() != null) MctranslatorFabric.service().setTargetLang(target);
        else cfg.targetLang = target;
        FabricTextStyle.clearRenderMemo();
        MctranslatorFabric.saveConfig();
        this.minecraft.setScreen(this.lastScreen);
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            LanguageSelectionList.Entry selected = this.languageList.getSelected();
            if (selected != null) { onDone(); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
        this.languageList.render(graphics, mouseX, mouseY, delta);
        GuiComponent.drawCenteredString(graphics, this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        GuiComponent.drawCenteredString(graphics, this.font, WARNING, this.width / 2, this.height - 56, 0x808080);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private final class LanguageSelectionList extends ObjectSelectionList<LanguageSelectionList.Entry> {
        private LanguageSelectionList(Minecraft minecraft) {
            super(minecraft, TranslationLanguageScreen.this.width, TranslationLanguageScreen.this.height,
                    48, TranslationLanguageScreen.this.height - 61, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter.toLowerCase(Locale.ROOT);
            List<Entry> entries = Minecraft.getInstance().getLanguageManager().getLanguages().entrySet().stream()
                    .filter(e -> needle.isEmpty()
                            || e.getKey().toLowerCase(Locale.ROOT).contains(needle)
                            || e.getValue().name().toLowerCase(Locale.ROOT).contains(needle)
                            || e.getValue().region().toLowerCase(Locale.ROOT).contains(needle))
                    .map(e -> new Entry(e.getKey(), e.getValue().toComponent())).toList();
            ArrayList<Entry> all = new ArrayList<>();
            all.add(new Entry(null, Component.translatable("screen.mctranslator.language.follow")));
            all.addAll(entries);
            this.replaceEntries(all);
            selectCurrent();
            this.setScrollAmount(0);
        }

        private void selectCurrent() {
            TranslatorConfig cfg = MctranslatorFabric.config();
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

        @Override protected int getScrollbarPosition() { return super.getScrollbarPosition() + 20; }
        @Override public int getRowWidth() { return super.getRowWidth() + 50; }
        @Override protected void renderBackground(PoseStack graphics) {
            TranslationLanguageScreen.this.renderBackground(graphics);
            GuiComponent.fill(graphics, 0, this.y0, this.width, this.y1, 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String code;
            private final Component language;
            private long lastClickTime;

            private Entry(String code, Component language) { this.code = code; this.language = language; }

            @Override public void render(PoseStack graphics, int index, int y, int x, int width,
                                         int height, int mouseX, int mouseY, boolean hovered, float delta) {
                GuiComponent.drawCenteredString(graphics, TranslationLanguageScreen.this.font, this.language,
                        LanguageSelectionList.this.width / 2, y + 1, 0xFFFFFF);
            }

            @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button != 0) return false;
                LanguageSelectionList.this.setSelected(this);
                if (Util.getMillis() - this.lastClickTime < 250L) onDone();
                this.lastClickTime = Util.getMillis();
                return true;
            }

            @Override public Component getNarration() { return Component.translatable("narrator.select", this.language); }
        }
    }
}
