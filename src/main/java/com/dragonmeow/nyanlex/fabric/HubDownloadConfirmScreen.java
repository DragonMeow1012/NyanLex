package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.hub.HubPlan;
import com.dragonmeow.nyanlex.hub.HubPlanItem;
import com.dragonmeow.nyanlex.hub.HubSource;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Locale;

/**
 * "下載確認" screen: shown after the background identify+plan step
 * ({@link NyanLexFabric#startHubIdentifyAndPlan}) fetched {@code index.json} and
 * built a {@link HubPlan}. Lists every server/modpack/mod the plan knows about with its
 * repository status, and lets the player confirm the actual file download
 * ({@link HubDownloadProgressScreen}). Paginated rather than scrollable so the same code
 * works unmodified on every Minecraft version this mod supports.
 */
public final class HubDownloadConfirmScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int INFO_TOP = 28;

    private final Screen parent;
    private final HubPlan plan;
    private final String serverHost;
    private final String modpackLabel;
    private final int installedModCount;
    private int page;
    private int pageSize = 6;
    private List<FormattedCharSequence> pathLines = List.of();
    private Button prevButton;
    private Button nextButton;

    public HubDownloadConfirmScreen(Screen parent, HubPlan plan, String serverHost,
            String modpackLabel, int installedModCount) {
        super(Component.translatable("screen.nyanlex.hub.confirm.title"));
        this.parent = parent;
        this.plan = plan;
        this.serverHost = serverHost;
        this.modpackLabel = modpackLabel;
        this.installedModCount = installedModCount;
    }

    private int totalPages() {
        return Math.max(1, (plan.items().size() + pageSize - 1) / pageSize);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int bottomY = this.height - 28;
        // Footer text (path) wraps to the content width; the page size then follows from
        // the space left between the info lines and the footer so nothing overlaps.
        int contentW = Math.min(this.width - 20, 340);
        pathLines = this.font.split(Component.translatable("screen.nyanlex.hub.confirm.path", storagePath()),
                contentW);
        int listTop = INFO_TOP + 3 * 12 + 8;
        int footerH = 4 + ROW_H + pathLines.size() * (this.font.lineHeight + 1) + 6;
        int navY = bottomY - 24;
        int availableForRows = navY - 4 - listTop - footerH;
        pageSize = Math.max(1, availableForRows / ROW_H);

        if (plan.isEmpty()) {
            int w = Math.min(200, this.width - 40);
            this.addRenderableWidget(Button.builder(
                    Component.translatable("screen.nyanlex.hub.confirm.back"),
                    b -> onClose()).bounds(centerX - w / 2, bottomY, w, 20).build());
            return;
        }

        page = Math.min(page, totalPages() - 1);

        prevButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.hub.confirm.prev"),
                b -> { page = Math.max(0, page - 1); updatePageButtons(); })
                .bounds(Math.max(4, centerX - 180), bottomY - 24, 70, 20).build());
        nextButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.hub.confirm.next"),
                b -> { page = Math.min(totalPages() - 1, page + 1); updatePageButtons(); })
                .bounds(Math.min(this.width - 74, centerX + 110), bottomY - 24, 70, 20).build());

        boolean hasDownload = !plan.downloadable().isEmpty();
        Button downloadButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.hub.confirm.download"),
                b -> onDownload()).bounds(centerX - 125, bottomY, 120, 20).build());
        downloadButton.active = hasDownload;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.hub.confirm.back"),
                b -> onClose()).bounds(centerX + 5, bottomY, 120, 20).build());
        updatePageButtons();
    }

    private void updatePageButtons() {
        if (prevButton != null) prevButton.active = page > 0;
        if (nextButton != null) nextButton.active = page < totalPages() - 1;
    }

    private void onDownload() {
        if (this.minecraft == null) return;
        NyanLexFabric.hubDownloadJob().start(plan, NyanLexFabric.hubDownloader(),
                NyanLexFabric.hubLocalCache(), NyanLexFabric.hubDownloadState(),
                NyanLexFabric.hubExecutor());
        this.minecraft.setScreen(new HubDownloadProgressScreen(parent));
    }

    private static String storagePath() {
        return NyanLexFabric.hubLocalCache().activeFile().toAbsolutePath().toString();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        g.drawCenteredString(this.font, this.title, centerX, 12, 0xFFFFFFFF);

        int y = INFO_TOP;
        Component serverLine = Component.translatable("screen.nyanlex.hub.confirm.server",
                serverHost != null ? serverHost
                        : Component.translatable("screen.nyanlex.hub.confirm.server.singleplayer"));
        g.drawCenteredString(this.font, serverLine, centerX, y, 0xFFE0E0E0);
        y += 12;
        Component modpackLine = Component.translatable("screen.nyanlex.hub.confirm.modpack",
                modpackLabel != null ? modpackLabel
                        : Component.translatable("screen.nyanlex.hub.confirm.modpack.none"));
        g.drawCenteredString(this.font, modpackLine, centerX, y, 0xFFE0E0E0);
        y += 12;
        // Only mods the repository actually has content for count toward the "hub
        // has %s" half of the label (2026-10-01: every installed mod now gets its
        // own plan item regardless of repository content, so filtering by kind alone
        // would make this count always equal installedModCount).
        long modItemCount = plan.items().stream()
                .filter(it -> it.source().kind() == HubSource.Kind.MOD && it.hasContent()).count();
        g.drawCenteredString(this.font, Component.translatable("screen.nyanlex.hub.confirm.mods",
                installedModCount, (int) modItemCount), centerX, y, 0xFFE0E0E0);
        y += 20;

        if (plan.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("screen.nyanlex.hub.confirm.empty"),
                    centerX, y + 10, 0xFFFFD700);
            return;
        }

        List<HubPlanItem> items = plan.items();
        int start = page * pageSize;
        int end = Math.min(items.size(), start + pageSize);
        int listLeft = Math.max(10, centerX - 170);
        int listRight = Math.min(this.width - 10, centerX + 170);
        for (int i = start; i < end; i++) {
            HubPlanItem item = items.get(i);
            Component status = itemStatus(item);
            int statusWidth = this.font.width(status);
            int labelMax = Math.max(20, listRight - listLeft - statusWidth - 10);
            String label = item.label();
            if (this.font.width(label) > labelMax) {
                label = this.font.plainSubstrByWidth(label, labelMax - this.font.width("...")) + "...";
            }
            g.drawString(this.font, label, listLeft, y, 0xFFFFFFFF);
            g.drawString(this.font, status, listRight - statusWidth, y, statusColor(item));
            y += ROW_H;
        }

        // The last row of the table is the expected total, then where the files will be stored.
        g.fill(listLeft, y + 1, listRight, y + 2, 0xFF606060);
        y += 4;
        Component total = Component.translatable("screen.nyanlex.hub.confirm.total",
                formatBytes(plan.totalDownloadBytes()));
        g.drawString(this.font, total, listLeft, y, 0xFFFFD700);
        y += ROW_H;
        for (FormattedCharSequence line : pathLines) {
            g.drawString(this.font, line, listLeft, y, 0xFFB0C8E0);
            y += this.font.lineHeight + 1;
        }

        int bottomY = this.height - 28;
        g.drawCenteredString(this.font,
                Component.translatable("screen.nyanlex.hub.confirm.page", page + 1, totalPages()),
                centerX, bottomY - 18, 0xFFA0A0A0);
    }

    private static Component itemStatus(HubPlanItem item) {
        if (!item.hasContent()) return Component.translatable("screen.nyanlex.hub.confirm.item.none");
        if (item.upToDate()) {
            return Component.translatable("screen.nyanlex.hub.confirm.item.uptodate", item.rows());
        }
        return Component.translatable("screen.nyanlex.hub.confirm.item.content",
                item.rows(), formatBytes(item.bytes()));
    }

    private static int statusColor(HubPlanItem item) {
        if (!item.hasContent()) return 0xFF808080;
        if (item.upToDate()) return 0xFF80C080;
        return 0xFFFFD700;
    }

    static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        if (bytes >= 1024L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
