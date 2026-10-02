package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubPlan;
import com.dragonmeow.nyanlex.hub.HubPlanItem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Non-blocking "we found mod translations for you" popup shown at most once per launch,
 * the first time the title screen appears (see
 * {@link NyanLexFabric#maybeStartHubStartupCheck}). Esc/"稍後" simply returns to the
 * title screen without starting anything; "下載" starts the shared
 * {@link com.dragonmeow.nyanlex.hub.HubDownloadJob} and opens
 * {@link HubDownloadProgressScreen}.
 */
public final class HubStartupPromptScreen extends Screen {
    private static final int MAX_ITEM_LINES = 8;

    private final Screen parent;
    private final HubPlan plan;
    private List<FormattedCharSequence> questionLines = List.of();
    private List<FormattedCharSequence> footerLines = List.of();
    private Button dontShowButton;

    public HubStartupPromptScreen(Screen parent, HubPlan plan) {
        super(Component.translatable("screen.nyanlex.hub.prompt.title"));
        this.parent = parent;
        this.plan = plan;
    }

    @Override
    protected void init() {
        int contentW = Math.max(160, Math.min(320, this.width - 40));
        questionLines = this.font.split(
                Component.translatable("screen.nyanlex.hub.prompt.question"), contentW);
        footerLines = this.font.split(Component.translatable("screen.nyanlex.hub.prompt.footer",
                Component.translatable("screen.nyanlex.config.title"),
                Component.translatable("config.nyanlex.hub.open"),
                Component.translatable("config.nyanlex.hub.identify")), contentW);

        int centerX = this.width / 2;
        int bottomY = this.height - 30;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.nyanlex.hub.prompt.download"),
                b -> onDownload()).bounds(centerX - 125, bottomY, 120, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.nyanlex.hub.prompt.later"),
                b -> onClose()).bounds(centerX + 5, bottomY, 120, 20).build());

        TranslatorConfig cfg = NyanLexFabric.config();
        dontShowButton = this.addRenderableWidget(Button.builder(dontShowLabel(cfg), b -> {
            cfg.hubStartupPromptDisabled = !cfg.hubStartupPromptDisabled;
            NyanLexFabric.saveConfig();
            b.setMessage(dontShowLabel(cfg));
        }).bounds(centerX - 90, bottomY - 24, 180, 20).build());
    }

    private static Component dontShowLabel(TranslatorConfig cfg) {
        String box = cfg.hubStartupPromptDisabled ? "☑ " : "☐ ";
        return Component.literal(box).append(Component.translatable("screen.nyanlex.hub.prompt.dont_show"));
    }

    private void onDownload() {
        if (this.minecraft == null) return;
        NyanLexFabric.hubDownloadJob().start(plan, NyanLexFabric.hubDownloader(),
                NyanLexFabric.hubLocalCache(), NyanLexFabric.hubDownloadState(),
                NyanLexFabric.hubExecutor());
        this.minecraft.setScreen(new HubDownloadProgressScreen(parent));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int lineH = this.font.lineHeight + 1;
        int y = 18;
        g.drawCenteredString(this.font, this.title, centerX, y, 0xFFFFD700);
        y += lineH + 6;
        for (FormattedCharSequence line : questionLines) {
            g.drawCenteredString(this.font, line, centerX, y, 0xFFFFFFFF);
            y += lineH;
        }
        y += 6;

        List<HubPlanItem> items = plan.downloadable();
        int shown = Math.min(items.size(), MAX_ITEM_LINES);
        for (int i = 0; i < shown; i++) {
            HubPlanItem item = items.get(i);
            Component line = Component.translatable("screen.nyanlex.hub.prompt.item",
                    item.label(), HubDownloadConfirmScreen.formatBytes(item.bytes()));
            g.drawCenteredString(this.font, line, centerX, y, 0xFFE0E0E0);
            y += lineH;
        }
        if (items.size() > shown) {
            g.drawCenteredString(this.font, Component.translatable("screen.nyanlex.hub.prompt.more",
                    items.size() - shown), centerX, y, 0xFFA0A0A0);
            y += lineH;
        }
        y += 4;
        long total = plan.totalDownloadBytes();
        g.drawCenteredString(this.font, Component.translatable("screen.nyanlex.hub.prompt.total",
                HubDownloadConfirmScreen.formatBytes(total)), centerX, y, 0xFFFFD700);

        int footerY = this.height - 56;
        for (FormattedCharSequence line : footerLines) {
            g.drawCenteredString(this.font, line, centerX, footerY, 0xFF909090);
            footerY += lineH;
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
