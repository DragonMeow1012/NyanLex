package com.dragonmeow.nyanlex.legacy;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.Collections;
import java.util.List;

/**
 * 1.0.7 UI round 3: "? 使用說明" — a short, non-blocking reference for first-time players.
 * Paginated rather than scrollable so it behaves identically across every Java-8 legacy
 * target without depending on a version-specific mouseScrolled signature.
 */
final class LegacyHelpScreen extends Screen {
    private static final int MAX_CONTENT_W = 300;
    private static final int LINE_H = 10;

    private final Screen parent;
    private List<FormattedCharSequence> lines = Collections.emptyList();
    private int linesPerPage = 1;
    private int totalPages = 1;
    private int page;
    private int contentX;
    private int topY;
    private int navY;

    LegacyHelpScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanlex.help.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int contentW = Math.max(160, Math.min(MAX_CONTENT_W, width - 40));
        contentX = width / 2 - contentW / 2;
        lines = font.split(new TranslatableComponent("screen.nyanlex.help.body"), contentW);
        navY = height - 52;
        int doneY = height - 26;
        topY = 50;
        int available = Math.max(LINE_H, navY - 8 - topY);
        linesPerPage = Math.max(1, available / LINE_H);
        totalPages = Math.max(1, (int) Math.ceil(lines.size() / (double) linesPerPage));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        addButton(new Button(contentX, 26, contentW, 18,
                new TranslatableComponent("screen.nyanlex.help.quick"),
                button -> minecraft.setScreen(new LegacySetupScreen(this, true))));
        addButton(new Button(contentX, navY, 40, 20, new net.minecraft.network.chat.TextComponent("<"),
                button -> { if (page > 0) page--; init(minecraft, width, height); }));
        addButton(new Button(contentX + contentW - 40, navY, 40, 20, new net.minecraft.network.chat.TextComponent(">"),
                button -> { if (page < totalPages - 1) page++; init(minecraft, width, height); }));
        int doneW = Math.min(200, contentW);
        addButton(new Button(width / 2 - doneW / 2, doneY, doneW, 20,
                new TranslatableComponent("gui.done"), button -> onClose()));
    }

    @Override public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        renderBackground(pose);
        GuiComponent.drawCenteredString(pose, font, title, width / 2, 12, 0xFFFFFF);
        int start = page * linesPerPage;
        int end = Math.min(lines.size(), start + linesPerPage);
        int y = topY;
        for (int i = start; i < end; i++) {
            font.drawShadow(pose, lines.get(i), contentX, y, 0xE0E0E0);
            y += LINE_H;
        }
        String pageLabel = (page + 1) + " / " + totalPages;
        GuiComponent.drawCenteredString(pose, font, new net.minecraft.network.chat.TextComponent(pageLabel),
                width / 2, navY + 6, 0xA0A0A0);
        super.render(pose, mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }
}
