package com.dragonmeow.nyanslate.neoforge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 使用說明 — a short, non-blocking reference for first-time players. Paginated rather than
 * scrollable so it behaves identically across every Minecraft/API version this mod targets.
 */
public final class TranslationHelpScreen extends Screen {

    private static final int MAX_CONTENT_W = 360;

    private final Screen parent;
    private List<FormattedCharSequence> lines = List.of();
    private int linesPerPage = 1;
    private int totalPages = 1;
    private int page;
    private int contentX;
    private int topY;
    private int navY;
    private int lineH;

    public TranslationHelpScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.help.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentW = Math.max(160, Math.min(MAX_CONTENT_W, this.width - 40));
        contentX = this.width / 2 - contentW / 2;
        lineH = this.font.lineHeight + 2;
        lines = this.font.split(Component.translatable("screen.nyanslate.help.body"), contentW);

        navY = this.height - 52;
        int doneY = this.height - 26;
        topY = 30;
        int available = Math.max(lineH, navY - 8 - topY);
        linesPerPage = Math.max(1, available / lineH);
        totalPages = Math.max(1, (int) Math.ceil(lines.size() / (double) linesPerPage));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (page > 0) page--;
        }).bounds(contentX, navY, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (page < totalPages - 1) page++;
        }).bounds(contentX + contentW - 40, navY, 40, 20).build());

        int doneW = Math.min(200, contentW);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width / 2 - doneW / 2, doneY, doneW, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int start = page * linesPerPage;
        int end = Math.min(lines.size(), start + linesPerPage);
        int y = topY;
        for (int i = start; i < end; i++) {
            g.drawString(this.font, lines.get(i), contentX, y, 0xFFE0E0E0, false);
            y += lineH;
        }

        g.drawCenteredString(this.font, Component.literal((page + 1) + " / " + totalPages),
                this.width / 2, navY + 6, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
