package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslationLanguages;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
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
    private static final Component WARNING = new net.minecraft.network.chat.TextComponent("(")
            .append(new net.minecraft.network.chat.TranslatableComponent("options.languageWarning")).append(")").withStyle(ChatFormatting.GRAY);
    private LanguageSelectionList languageList;
    private EditBox search;
    /** When set, the answer is handed back instead of being applied (the questionnaire applies it on 完成). */
    private final java.util.function.BiConsumer<Boolean, String> answer;
    private final boolean stagedFollow;
    private final String stagedTag;

    public TranslationLanguageScreen(Screen parent) {
        this(parent, true, null, null);
    }

    /** Picker for the questionnaire: starts on {@code followGame}/{@code tag} and reports the choice to {@code answer}. */
    public TranslationLanguageScreen(Screen parent, boolean followGame, String tag,
                                     java.util.function.BiConsumer<Boolean, String> answer) {
        super(parent, Minecraft.getInstance().options,
                new net.minecraft.network.chat.TranslatableComponent("screen.nyanlex.language.target_title"));
        this.answer = answer;
        this.stagedFollow = followGame;
        this.stagedTag = tag;
    }

    @Override protected void init() {
        this.languageList = new LanguageSelectionList(this.minecraft);
        this.addWidget(this.languageList);
        this.search = new EditBox(this.font, this.width / 2 - 100, 24, 200, 20, new net.minecraft.network.chat.TextComponent(""));
        this.search.setResponder(this.languageList::filterEntries);
        this.addRenderableWidget(this.search);
        this.addRenderableWidget(new Button(this.width / 2 - 155, this.height - 38, 150, 20,
                new net.minecraft.network.chat.TranslatableComponent("options.forceUnicodeFont"), b -> {
                    this.options.forceUnicodeFont = !this.options.forceUnicodeFont;
                    this.options.save();
                }));
        this.addRenderableWidget(LegacyButton.builder(CommonComponents.GUI_DONE, b -> onDone())
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
        if (answer != null) {
            answer.accept(minecraftCode == null,
                    minecraftCode == null ? null : TranslationLanguages.fromMinecraftCode(minecraftCode));
            this.minecraft.setScreen(this.lastScreen);
            return;
        }
        TranslatorConfig cfg = NyanLexFabric.config();
        cfg.followGameLanguage = minecraftCode == null;
        String selected = minecraftCode == null
                ? minecraft.getLanguageManager().getSelected().getCode() : minecraftCode;
        String target = TranslationLanguages.fromMinecraftCode(selected);
        if (NyanLexFabric.service() != null) NyanLexFabric.service().setTargetLang(target);
        else cfg.targetLang = target;
        FabricTextStyle.clearRenderMemo();
        NyanLexFabric.saveConfig();
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
            List<Entry> entries = Minecraft.getInstance().getLanguageManager().getLanguages().stream()
                    .filter(info -> needle.isEmpty()
                            || info.getCode().toLowerCase(Locale.ROOT).contains(needle)
                            || info.getName().toLowerCase(Locale.ROOT).contains(needle)
                            || info.getRegion().toLowerCase(Locale.ROOT).contains(needle))
                    .map(info -> new Entry(info.getCode(),
                            new net.minecraft.network.chat.TextComponent(info.toString()))).toList();
            ArrayList<Entry> all = new ArrayList<>();
            all.add(new Entry(null, new net.minecraft.network.chat.TranslatableComponent("screen.nyanlex.language.follow")));
            all.addAll(entries);
            this.replaceEntries(all);
            selectCurrent();
            this.setScrollAmount(0);
        }

        private void selectCurrent() {
            TranslatorConfig cfg = NyanLexFabric.config();
            boolean follow = answer != null ? stagedFollow : cfg.followGameLanguage;
            String target = answer != null ? stagedTag : cfg.targetLang;
            for (Entry entry : this.children()) {
                if ((follow && entry.code == null)
                        || (!follow && entry.code != null && target != null
                        && TranslationLanguages.fromMinecraftCode(entry.code).equalsIgnoreCase(target))) {
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

            @Override public Component getNarration() { return new net.minecraft.network.chat.TranslatableComponent("narrator.select", this.language); }
        }
    }
}
