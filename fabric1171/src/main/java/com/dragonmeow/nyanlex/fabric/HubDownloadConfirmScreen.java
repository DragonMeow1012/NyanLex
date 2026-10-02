package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.hub.HubPlan;
import com.dragonmeow.nyanlex.hub.HubPlanItem;
import com.dragonmeow.nyanlex.hub.HubSource;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

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
    private static final int PAGE_SIZE = 6;

    private final Screen parent;
    private final HubPlan plan;
    private final String serverHost;
    private final String modpackLabel;
    private final int installedModCount;
    private int page;
    private Button prevButton;
    private Button nextButton;

    public HubDownloadConfirmScreen(Screen parent, HubPlan plan, String serverHost,
            String modpackLabel, int installedModCount) {
        super(new TranslatableComponent("screen.nyanlex.hub.confirm.title"));
        this.parent = parent;
        this.plan = plan;
        this.serverHost = serverHost;
        this.modpackLabel = modpackLabel;
        this.installedModCount = installedModCount;
    }

    private int totalPages() {
        return Math.max(1, (plan.items().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int bottomY = this.height - 28;

        if (plan.isEmpty()) {
            int w = Math.min(200, this.width - 40);
            this.addRenderableWidget(LegacyButton.builder(
                    new TranslatableComponent("screen.nyanlex.hub.confirm.back"),
                    b -> onClose()).bounds(centerX - w / 2, bottomY, w, 20).build());
            return;
        }

        page = Math.min(page, totalPages() - 1);

        prevButton = this.addRenderableWidget(LegacyButton.builder(
                new TranslatableComponent("screen.nyanlex.hub.confirm.prev"),
                b -> { page = Math.max(0, page - 1); updatePageButtons(); })
                .bounds(centerX - 180, bottomY - 24, 70, 20).build());
        nextButton = this.addRenderableWidget(LegacyButton.builder(
                new TranslatableComponent("screen.nyanlex.hub.confirm.next"),
                b -> { page = Math.min(totalPages() - 1, page + 1); updatePageButtons(); })
                .bounds(centerX + 110, bottomY - 24, 70, 20).build());

        boolean hasDownload = !plan.downloadable().isEmpty();
        Button downloadButton = this.addRenderableWidget(LegacyButton.builder(
                new TranslatableComponent("screen.nyanlex.hub.confirm.download"),
                b -> onDownload()).bounds(centerX - 125, bottomY, 120, 20).build());
        downloadButton.active = hasDownload;
        this.addRenderableWidget(LegacyButton.builder(
                new TranslatableComponent("screen.nyanlex.hub.confirm.back"),
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

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        GuiComponent.drawCenteredString(g, this.font, this.title, centerX, 12, 0xFFFFFFFF);

        int y = 30;
        Component serverLine = new TranslatableComponent("screen.nyanlex.hub.confirm.server",
                serverHost != null ? serverHost
                        : new TranslatableComponent("screen.nyanlex.hub.confirm.server.singleplayer"));
        GuiComponent.drawCenteredString(g, this.font, serverLine, centerX, y, 0xFFE0E0E0);
        y += 12;
        Component modpackLine = new TranslatableComponent("screen.nyanlex.hub.confirm.modpack",
                modpackLabel != null ? modpackLabel
                        : new TranslatableComponent("screen.nyanlex.hub.confirm.modpack.none"));
        GuiComponent.drawCenteredString(g, this.font, modpackLine, centerX, y, 0xFFE0E0E0);
        y += 12;
        // Only mods the repository actually has content for count toward the "hub
        // has %s" half of the label (2026-10-01: every installed mod now gets its
        // own plan item regardless of repository content, so filtering by kind alone
        // would make this count always equal installedModCount).
        long modItemCount = plan.items().stream()
                .filter(it -> it.source().kind() == HubSource.Kind.MOD && it.hasContent()).count();
        GuiComponent.drawCenteredString(g, this.font, new TranslatableComponent("screen.nyanlex.hub.confirm.mods",
                installedModCount, (int) modItemCount), centerX, y, 0xFFE0E0E0);
        y += 18;

        if (plan.isEmpty()) {
            GuiComponent.drawCenteredString(g, this.font, new TranslatableComponent("screen.nyanlex.hub.confirm.empty"),
                    centerX, y + 10, 0xFFFFD700);
            return;
        }

        List<HubPlanItem> items = plan.items();
        int start = page * PAGE_SIZE;
        int end = Math.min(items.size(), start + PAGE_SIZE);
        int listLeft = Math.max(10, centerX - 160);
        int listRight = Math.min(this.width - 10, centerX + 160);
        for (int i = start; i < end; i++) {
            HubPlanItem item = items.get(i);
            GuiComponent.drawString(g, this.font, item.label(), listLeft, y, 0xFFFFFFFF);
            Component status = itemStatus(item);
            int statusWidth = this.font.width(status);
            GuiComponent.drawString(g, this.font, status, listRight - statusWidth, y, statusColor(item));
            y += 12;
        }

        int bottomY = this.height - 28;
        GuiComponent.drawCenteredString(g, this.font,
                new TranslatableComponent("screen.nyanlex.hub.confirm.page", page + 1, totalPages()),
                centerX, bottomY - 40, 0xFFA0A0A0);
        GuiComponent.drawCenteredString(g, this.font,
                new TranslatableComponent("screen.nyanlex.hub.confirm.total", formatBytes(plan.totalDownloadBytes())),
                centerX, bottomY - 14, 0xFFFFD700);
    }

    private static Component itemStatus(HubPlanItem item) {
        if (!item.hasContent()) return new TranslatableComponent("screen.nyanlex.hub.confirm.item.none");
        if (item.upToDate()) {
            return new TranslatableComponent("screen.nyanlex.hub.confirm.item.uptodate", item.rows());
        }
        return new TranslatableComponent("screen.nyanlex.hub.confirm.item.content",
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
