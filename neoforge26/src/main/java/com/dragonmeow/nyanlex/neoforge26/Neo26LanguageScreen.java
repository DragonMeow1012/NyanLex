package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.config.TranslationLanguages;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.FontOptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Vanilla 26.2 language-selection UI, redirected to the translation target. */
public final class Neo26LanguageScreen extends OptionsSubScreen {
    private static final Component WARNING = Component.translatable("options.languageAccuracyWarning").withColor(-4539718);
    private static final Component SEARCH_HINT = Component.translatable("gui.language.search").withStyle(EditBox.SEARCH_HINT_STYLE);
    private LanguageSelectionList languageList;
    private EditBox search;
    /** When set, the answer is handed back instead of being applied (the questionnaire applies it on 完成). */
    private final java.util.function.BiConsumer<Boolean, String> answer;
    private final boolean stagedFollow;
    private final String stagedTag;

    public Neo26LanguageScreen(Screen parent) {
        this(parent, true, null, null);
    }

    /** Picker for the questionnaire: starts on {@code followGame}/{@code tag} and reports the choice to {@code answer}. */
    public Neo26LanguageScreen(Screen parent, boolean followGame, String tag,
                                  java.util.function.BiConsumer<Boolean, String> answer) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanlex.language.target_title"));
        this.answer = answer;
        this.stagedFollow = followGame;
        this.stagedTag = tag;
        this.layout.setFooterHeight(53);
    }

    @Override protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 200, 15, Component.empty()));
        this.search.setHint(SEARCH_HINT);
        this.search.setResponder(value -> { if (this.languageList != null) this.languageList.filterEntries(value); });
        this.layout.setHeaderHeight(36);
    }

    @Override protected void setInitialFocus() {
        if (this.search != null) this.setInitialFocus(this.search);
        else super.setInitialFocus();
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
                b -> this.minecraft.setScreenAndShow(new FontOptionsScreen(this, this.options))).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onDone()).build());
    }

    @Override protected void repositionElements() {
        super.repositionElements();
        if (this.languageList != null) this.languageList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        LanguageSelectionList.Entry selected = this.languageList == null ? null : this.languageList.getSelected();
        if (selected != null) choose(selected.code);
        else this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private void choose(String minecraftCode) {
        if (answer != null) {
            answer.accept(minecraftCode == null,
                    minecraftCode == null ? null : TranslationLanguages.fromMinecraftCode(minecraftCode));
            this.minecraft.setScreenAndShow(this.lastScreen);
            return;
        }
        TranslatorConfig cfg = NyanLexNeoForge26.config();
        cfg.followGameLanguage = minecraftCode == null;
        String selected = minecraftCode == null ? minecraft.getLanguageManager().getSelected() : minecraftCode;
        String target = TranslationLanguages.fromMinecraftCode(selected);
        if (NyanLexNeoForge26.service() != null) NyanLexNeoForge26.service().setTargetLang(target);
        else cfg.targetLang = target;
        Neo26TextStyle.clearRenderMemo();
        NyanLexNeoForge26.saveConfig();
        this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private final class LanguageSelectionList extends ObjectSelectionList<LanguageSelectionList.Entry> {
        private LanguageSelectionList(Minecraft minecraft) {
            super(minecraft, Neo26LanguageScreen.this.width,
                    Neo26LanguageScreen.this.height - 33 - 53, 33, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter.toLowerCase(Locale.ROOT);
            List<Entry> entries = Minecraft.getInstance().getLanguageManager().getLanguages().entrySet().stream()
                    .filter(e -> needle.isEmpty()
                            || e.getKey().toLowerCase(Locale.ROOT).contains(needle)
                            || e.getValue().name().toLowerCase(Locale.ROOT).contains(needle)
                            || e.getValue().region().toLowerCase(Locale.ROOT).contains(needle))
                    .map(e -> new Entry(e.getKey(), e.getValue().toComponent()))
                    .toList();
            ArrayList<Entry> all = new ArrayList<>();
            all.add(new Entry(null, Component.translatable("screen.nyanlex.language.follow")));
            all.addAll(entries);
            this.replaceEntries(all);
            selectCurrent();
            this.refreshScrollAmount();
        }

        private void selectCurrent() {
            TranslatorConfig cfg = NyanLexNeoForge26.config();
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

        @Override public int getRowWidth() { return super.getRowWidth() + 50; }

        @Override protected void extractListBackground(GuiGraphicsExtractor graphics) {
            super.extractListBackground(graphics);
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String code;
            private final Component language;

            private Entry(String code, Component language) { this.code = code; this.language = language; }

            @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                 boolean hovered, float delta) {
                graphics.centeredText(Neo26LanguageScreen.this.font, this.language,
                        LanguageSelectionList.this.width / 2, this.getContentYMiddle() - 9 / 2, -1);
            }

            @Override public boolean keyPressed(KeyEvent event) {
                if (event.isSelection()) { select(); onDone(); return true; }
                return super.keyPressed(event);
            }

            @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                select();
                if (doubleClick) onDone();
                return super.mouseClicked(event, doubleClick);
            }

            private void select() { LanguageSelectionList.this.setSelected(this); }
            @Override public Component getNarration() { return Component.translatable("narrator.select", this.language); }
        }
    }
}
