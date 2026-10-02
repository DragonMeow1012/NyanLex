package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.hub.HubDownloadJob;
import com.dragonmeow.nyanlex.hub.HubDownloadResult;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Progress screen for the single shared {@link HubDownloadJob}. The job runs on its own
 * background thread regardless of whether this screen is open: closing it (Esc or
 * "返回") does not stop the download, and reopening it (from the hub settings screen's
 * action button, or the startup prompt) just resumes showing the same job's live state.
 * No listener is registered here — every frame simply re-reads the job's volatile
 * fields, which is cheap and keeps this screen trivial to reopen/close at any time.
 */
public final class HubDownloadProgressScreen extends Screen {
    private final Screen parent;
    private Button cancelButton;

    public HubDownloadProgressScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.hub.progress.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 + 40;
        cancelButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.hub.progress.cancel"),
                b -> NyanLexFabric.hubDownloadJob().cancel())
                .bounds(centerX - 125, y, 120, 20).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.hub.progress.back"),
                b -> onClose()).bounds(centerX + 5, y, 120, 20).build());
    }

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        HubDownloadJob job = NyanLexFabric.hubDownloadJob();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        GuiComponent.drawCenteredString(g, this.font, this.title, centerX, 12, 0xFFFFFFFF);

        HubDownloadJob.State state = job.state();
        cancelButton.active = state == HubDownloadJob.State.DOWNLOADING;

        int barWidth = Math.min(260, this.width - 40);
        int barX = centerX - barWidth / 2;
        int barY = centerY - 10;
        long total = job.totalBytes();
        long done = job.downloadedBytes();
        float fraction = total > 0 ? Math.min(1f, done / (float) total)
                : (job.totalFiles() > 0 ? Math.min(1f, job.completedFiles() / (float) job.totalFiles()) : 0f);
        if (state == HubDownloadJob.State.DONE) fraction = 1f;
        GuiComponent.fill(g, barX, barY, barX + barWidth, barY + 14, 0xFF303030);
        GuiComponent.fill(g, barX, barY, barX + (int) (barWidth * fraction), barY + 14, 0xFF4C8CFF);

        GuiComponent.drawCenteredString(g, this.font,
                Component.translatable("screen.nyanlex.hub.progress.bytes",
                        HubDownloadConfirmScreen.formatBytes(done), HubDownloadConfirmScreen.formatBytes(total)),
                centerX, barY + 20, 0xFFE0E0E0);
        GuiComponent.drawCenteredString(g, this.font,
                Component.translatable("screen.nyanlex.hub.progress.count",
                        job.completedFiles(), job.totalFiles()),
                centerX, barY + 32, 0xFFA0A0A0);
        String currentFile = job.currentFileName();
        if (currentFile != null && !currentFile.isEmpty() && state == HubDownloadJob.State.DOWNLOADING) {
            GuiComponent.drawCenteredString(g, this.font,
                    Component.translatable("screen.nyanlex.hub.progress.file", currentFile),
                    centerX, barY - 14, 0xFFA0A0A0);
        }

        int resultY = barY + 48;
        switch (state) {
            case DONE -> {
                HubDownloadResult result = job.result();
                if (result != null) {
                    GuiComponent.drawCenteredString(g, this.font, Component.translatable("screen.nyanlex.hub.progress.done",
                            result.added(), result.alreadyPresent(), result.rejected()), centerX, resultY, 0xFF80FF80);
                }
            }
            case FAILED -> {
                String reason = job.failureMessage();
                GuiComponent.drawCenteredString(g, this.font, Component.translatable("screen.nyanlex.hub.progress.failed",
                        reason == null ? "" : reason), centerX, resultY, 0xFFFF6060);
            }
            case CANCELLED -> {
                HubDownloadResult result = job.result();
                int kept = result == null ? 0 : result.added();
                GuiComponent.drawCenteredString(g, this.font, Component.translatable("screen.nyanlex.hub.progress.cancelled",
                        kept), centerX, resultY, 0xFFFFD700);
            }
            case IDLE -> GuiComponent.drawCenteredString(g, this.font,
                    Component.translatable("screen.nyanlex.hub.progress.idle"), centerX, resultY, 0xFFA0A0A0);
            default -> { }
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
