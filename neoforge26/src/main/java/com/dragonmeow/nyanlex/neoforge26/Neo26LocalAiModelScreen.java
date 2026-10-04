package com.dragonmeow.nyanlex.neoforge26;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.AntigravityCliClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
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

/** Searchable model picker shared by the ChatGPT and Google sign-in modes. */
public final class Neo26LocalAiModelScreen extends OptionsSubScreen {
    private final boolean antigravity;
    private ModelSelectionList modelList;
    private EditBox search;

    public Neo26LocalAiModelScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable(
                NyanLexNeoForge26.config().usesAntigravity()
                        ? "screen.nyanlex.ai.antigravity.model_picker.title"
                        : "screen.nyanlex.ai.codex.model_picker.title"));
        this.antigravity = NyanLexNeoForge26.config().usesAntigravity();
        this.layout.setFooterHeight(36);
    }

    @Override
    protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.search = header.addChild(new EditBox(this.font, 0, 0, 240, 15, Component.empty()));
        this.search.setHint(Component.translatable(this.antigravity
                ? "screen.nyanlex.ai.antigravity.model_picker.search"
                : "screen.nyanlex.ai.codex.model_picker.search")
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

    private void choose(ModelChoice option) {
        TranslatorConfig cfg = NyanLexNeoForge26.config();
        if (this.antigravity) {
            cfg.antigravityModel = option.model();
        } else {
            cfg.codexModel = option.model();
            CodexAppServerClient client = NyanLexNeoForge26.codexClient();
            if (client != null) {
                client.cachedModels().stream()
                        .filter(candidate -> candidate.model().equals(option.model()))
                        .findFirst()
                        .ifPresent(candidate -> Neo26AiScreen.normalizeEffort(cfg, candidate));
            }
        }
        NyanLexNeoForge26.saveConfig();
        Neo26TextStyle.clearRenderMemo();
        this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private List<ModelChoice> models() {
        if (this.antigravity) {
            AntigravityCliClient client = NyanLexNeoForge26.antigravityClient();
            return client == null ? List.of() : client.cachedModels().stream()
                    .map(option -> new ModelChoice(option.model(), option.displayName()))
                    .toList();
        }
        CodexAppServerClient client = NyanLexNeoForge26.codexClient();
        return client == null ? List.of() : client.cachedModels().stream()
                .map(option -> new ModelChoice(option.model(), option.displayName()))
                .toList();
    }

    private String activeModel() {
        TranslatorConfig cfg = NyanLexNeoForge26.config();
        return this.antigravity ? cfg.antigravityModel : cfg.codexModel;
    }

    private record ModelChoice(String model, String displayName) {
    }

    private final class ModelSelectionList
            extends ObjectSelectionList<ModelSelectionList.Entry> {
        private ModelSelectionList(Minecraft minecraft) {
            super(minecraft, Neo26LocalAiModelScreen.this.width,
                    Neo26LocalAiModelScreen.this.height - 33 - 36, 33, 18);
            filterEntries("");
        }

        private void filterEntries(String filter) {
            String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
            List<Entry> entries = models().stream()
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
            String active = activeModel();
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
            private final ModelChoice option;
            private final Component label;

            private Entry(ModelChoice option) {
                this.option = option;
                this.label = Component.literal(option.displayName().equals(option.model())
                        ? option.displayName()
                        : option.displayName() + " (" + option.model() + ")");
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float delta) {
                graphics.centeredText(Neo26LocalAiModelScreen.this.font, this.label,
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
