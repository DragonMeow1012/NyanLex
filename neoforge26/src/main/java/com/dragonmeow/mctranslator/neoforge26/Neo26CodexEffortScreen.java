package com.dragonmeow.mctranslator.neoforge26;

import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient.ModelOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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

/** Direct reasoning-effort picker; clicking the current value no longer cycles it. */
public final class Neo26CodexEffortScreen extends OptionsSubScreen {
    private EffortSelectionList effortList;

    public Neo26CodexEffortScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options,
                Component.translatable("screen.mctranslator.ai.codex.effort_picker.title"));
        this.layout.setHeaderHeight(24);
        this.layout.setFooterHeight(36);
    }

    @Override
    protected void addTitle() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical());
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
    }

    @Override
    protected void addContents() {
        this.effortList = this.layout.addToContents(new EffortSelectionList(this.minecraft));
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
        if (this.effortList != null) this.effortList.updateSize(this.width, this.layout);
    }

    private void onDone() {
        EffortSelectionList.Entry selected =
                this.effortList == null ? null : this.effortList.getSelected();
        if (selected != null) choose(selected.effort);
        else this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private void choose(String effort) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        cfg.codexReasoningEffort = effort;
        MctranslatorNeoForge26.saveConfig();
        Neo26TextStyle.clearRenderMemo();
        this.minecraft.setScreenAndShow(this.lastScreen);
    }

    private List<String> supportedEfforts() {
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        if (client == null) return List.of();
        String selectedModel = MctranslatorNeoForge26.config().codexModel;
        return client.cachedModels().stream()
                .filter(option -> option.model().equals(selectedModel))
                .findFirst()
                .map(ModelOption::reasoningEfforts)
                .orElse(List.of());
    }

    private final class EffortSelectionList
            extends ObjectSelectionList<EffortSelectionList.Entry> {
        private EffortSelectionList(Minecraft minecraft) {
            super(minecraft, Neo26CodexEffortScreen.this.width,
                    Neo26CodexEffortScreen.this.height - 24 - 36, 24, 18);
            this.replaceEntries(supportedEfforts().stream().map(Entry::new).toList());
            selectCurrent();
        }

        private void selectCurrent() {
            String active = MctranslatorNeoForge26.config().codexReasoningEffort;
            for (Entry entry : this.children()) {
                if (entry.effort.equals(active)) {
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
            private final String effort;
            private final Component label;

            private Entry(String effort) {
                this.effort = effort;
                this.label = Component.literal(effort);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float delta) {
                graphics.centeredText(Neo26CodexEffortScreen.this.font, this.label,
                        EffortSelectionList.this.width / 2,
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
                EffortSelectionList.this.setSelected(this);
            }

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", this.label);
            }
        }
    }
}
