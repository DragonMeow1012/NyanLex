package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient.ModelOption;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Direct reasoning-effort picker; clicking the current value no longer cycles it. */
public final class CodexEffortScreen extends OptionsSubScreen {
    private EffortSelectionList effortList;

    public CodexEffortScreen(Screen parent) {
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
        else this.minecraft.setScreen(this.lastScreen);
    }

    private void choose(String effort) {
        TranslatorConfig cfg = MctranslatorFabric.config();
        cfg.codexReasoningEffort = effort;
        MctranslatorFabric.saveConfig();
        FabricTextStyle.clearRenderMemo();
        this.minecraft.setScreen(this.lastScreen);
    }

    private List<String> supportedEfforts() {
        CodexAppServerClient client = MctranslatorFabric.codexClient();
        if (client == null) return List.of();
        String selectedModel = MctranslatorFabric.config().codexModel;
        return client.cachedModels().stream()
                .filter(option -> option.model().equals(selectedModel))
                .findFirst()
                .map(ModelOption::reasoningEfforts)
                .orElse(List.of());
    }

    private final class EffortSelectionList
            extends ObjectSelectionList<EffortSelectionList.Entry> {
        private EffortSelectionList(Minecraft minecraft) {
            super(minecraft, CodexEffortScreen.this.width,
                    CodexEffortScreen.this.height - 24 - 36, 24, 18);
            this.replaceEntries(supportedEfforts().stream().map(Entry::new).toList());
            selectCurrent();
        }

        private void selectCurrent() {
            String active = MctranslatorFabric.config().codexReasoningEffort;
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
        protected void renderListBackground(GuiGraphics graphics) {
            super.renderListBackground(graphics);
            graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0x20204A60);
        }

        private final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final String effort;
            private final Component label;
            private long lastClickTime;

            private Entry(String effort) {
                this.effort = effort;
                this.label = Component.literal(effort);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int y, int x, int width,
                               int height, int mouseX, int mouseY, boolean hovered, float delta) {
                graphics.drawCenteredString(CodexEffortScreen.this.font, this.label,
                        EffortSelectionList.this.width / 2, y + height / 2 - 9 / 2, -1);
            }

            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (CommonInputs.selected(keyCode)) {
                    select();
                    onDone();
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                select();
                if (Util.getMillis() - this.lastClickTime < 250L) onDone();
                this.lastClickTime = Util.getMillis();
                return super.mouseClicked(mouseX, mouseY, button);
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
