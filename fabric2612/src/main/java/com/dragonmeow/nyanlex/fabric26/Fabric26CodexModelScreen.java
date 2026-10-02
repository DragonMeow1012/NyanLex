package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient.ModelOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Searchable Codex model picker matching the translation-language selection UI. */
public final class Fabric26CodexModelScreen extends OptionsSubScreen {
    private ModelSelectionList modelList;
    private EditBox search;

    public Fabric26CodexModelScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.nyanlex.ai.codex.model_picker.title"));
        this.layout.setFooterHeight(36);
    }

    @Override
    protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 240, 15, Component.empty()));
        this.search.setHint(Component.translatable("screen.nyanlex.ai.codex.model_picker.search")
                .withStyle(EditBox.SEARCH_HINT_STYLE));
        this.search.setResponder(value -> {
            if (this.modelList != null) this.modelList.filterEntries(value);
        });
        this.layout.setHeaderHeight(36);
    }

    @Override
    protected void setInitialFocus() {
        if (this.search != null) this.setInitialFocus(this.search);
        else super.setInitialFocus();
    }

    @Override
    protected void addContents() {
        this.modelList = this.layout.addToContents(new ModelSelectionList(this.minecraft));
    }

    @Override
    protected void addOptions() {
    }

    @Override
    protected void addFooter() {
        LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical());
        footer.defaultCellSetting().alignHorizontallyCenter();
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onDone()).build());
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        if (this.modelList != null) this.modelList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        ModelSelectionList.Entry selected =
                this.modelList == null ? null : this.modelList.getSelected();
        if (selected != null) choose(selected.option);
        else this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private void choose(ModelOption option) {
        TranslatorConfig cfg = NyanLexFabric26.config();
        cfg.codexModel = option.model();
        Fabric26AiScreen.normalizeEffort(cfg, option);
        NyanLexFabric26.saveConfig();
        Fabric26TextStyle.clearRenderMemo();
        this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private final class ModelSelectionList
            extends ObjectSelectionList<ModelSelectionList.Entry> {
        private ModelSelectionList(Minecraft minecraft) {
            super(minecraft, Fabric26CodexModelScreen.this.width,
                    Fabric26CodexModelScreen.this.height - 33 - 36, 33, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
            CodexAppServerClient client = NyanLexFabric26.codexClient();
            List<ModelOption> models = client == null ? List.of() : client.cachedModels();
            List<Entry> entries = models.stream()
                    .filter(option -> needle.isEmpty()
                            || option.model().toLowerCase(Locale.ROOT).contains(needle)
                            || option.displayName().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new)
                    .toList();
            this.replaceEntries(entries);
            selectCurrent();
            this.refreshScrollAmount();
        }

        private void selectCurrent() {
            String active = NyanLexFabric26.config().codexModel;
            for (Entry entry : this.children()) {
                if (entry.option.model().equals(active)) {
                    this.setSelected(entry);
                    this.centerScrollOn(entry);
                    return;
                }
            }
        }

        @Override
        public int getRowWidth() {
            return super.getRowWidth() + 50;
        }

        @Override
        protected void extractListBackground(GuiGraphicsExtractor graphics) {
            super.extractListBackground(graphics);
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final ModelOption option;
            private final Component label;

            private Entry(ModelOption option) {
                this.option = option;
                this.label = Component.literal(option.displayName().equals(option.model())
                        ? option.displayName()
                        : option.displayName() + " (" + option.model() + ")");
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float delta) {
                graphics.centeredText(Fabric26CodexModelScreen.this.font, this.label,
                        ModelSelectionList.this.width / 2,
                        this.getContentYMiddle() - 9 / 2, -1);
            }

            @Override
            public boolean keyPressed(KeyEvent event) {
                if (event.isSelection()) {
                    select();
                    onDone();
                    return true;
                }
                return super.keyPressed(event);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                select();
                if (doubleClick) onDone();
                return super.mouseClicked(event, doubleClick);
            }

            private void select() {
                ModelSelectionList.this.setSelected(this);
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", this.label);
            }
        }
    }
}
