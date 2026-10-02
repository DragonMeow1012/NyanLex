package com.dragonmeow.nyanlex.legacy;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.Collections;
import java.util.List;

/**
 * 1.0.7 UI round 3: "? 使用說明" — a short, non-blocking reference for first-time players.
 * Paginated (not scrollable) so it behaves identically across every Java-8 legacy target;
 * Minecraft's mouseScrolled signature is not what forces this here (legacy has one API per
 * tree already), but pagination keeps this screen trivially portable if that ever changes.
 */
final class LegacyHelpScreen extends Screen {
    private static final int MAX_CONTENT_W = 300;
    private static final int LINE_H = 10;

    private final Screen parent;
    private List<String> lines = Collections.emptyList();
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
        lines = font.split(new TranslatableComponent("screen.nyanlex.help.body").getString(), contentW);
        navY = height - 52;
        int doneY = height - 26;
        topY = 30;
        int available = Math.max(LINE_H, navY - 8 - topY);
        linesPerPage = Math.max(1, available / LINE_H);
        totalPages = Math.max(1, (int) Math.ceil(lines.size() / (double) linesPerPage));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        addButton(new Button(contentX, navY, 40, 20, "<",
                button -> { if (page > 0) page--; init(minecraft, width, height); }));
        addButton(new Button(contentX + contentW - 40, navY, 40, 20, ">",
                button -> { if (page < totalPages - 1) page++; init(minecraft, width, height); }));
        int doneW = Math.min(200, contentW);
        addButton(new Button(width / 2 - doneW / 2, doneY, doneW, 20,
                new TranslatableComponent("gui.done").getString(), button -> onClose()));
    }

    @Override public void render(int mouseX, int mouseY, float delta) {
        renderBackground();
        font.drawShadow(title.getString(), width / 2f - font.width(title.getString()) / 2f, 12, 0xFFFFFF);
        int start = page * linesPerPage;
        int end = Math.min(lines.size(), start + linesPerPage);
        int y = topY;
        for (int i = start; i < end; i++) {
            font.drawShadow(lines.get(i), contentX, y, 0xE0E0E0);
            y += LINE_H;
        }
        String pageLabel = (page + 1) + " / " + totalPages;
        font.drawShadow(pageLabel, width / 2f - font.width(pageLabel) / 2f, navY + 6, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void onClose() {
        minecraft.setScreen(parent);
    }
}
