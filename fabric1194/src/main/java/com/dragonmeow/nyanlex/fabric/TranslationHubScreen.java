package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.hub.HubDownloadJob;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * GitHub AI 翻譯倉庫設定 (download-only; nothing downloads before the player confirms) — the
 * "識別當前伺服器/MOD下載並匯入翻譯檔" action button, the startup-check toggle, and
 * "清除倉庫翻譯".
 */
public final class TranslationHubScreen extends Screen {

    private static final String HUB_URL =
            "https://github.com/DragonMeow1012/NyanLex/tree/main/translation-hub";
    private static final int W = 300;

    /** "清除倉庫翻譯" requires two presses within this window to actually clear. */
    private static final long CLEAR_CONFIRM_WINDOW_MS = 4_000L;

    private final Screen parent;
    private List<FormattedCharSequence> introLines = List.of();
    private int introY;
    private Button identifyButton;
    private Button clearButton;
    private long clearArmedUntilMs;

    public TranslationHubScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.hub.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanLexFabric.config();
        int contentW = Math.max(120, Math.min(W, this.width - 20));
        int contentX = this.width / 2 - contentW / 2;
        int half = (contentW - 6) / 2;
        int lineH = this.font.lineHeight + 1;

        introLines = this.font.split(Component.translatable("screen.nyanlex.hub.hint"), contentW);

        int y = 30;
        introY = y;
        y += introLines.size() * lineH + 10;

        identifyButton = this.addRenderableWidget(Button.builder(identifyLabel(), b ->
                NyanLexFabric.startHubIdentifyAndPlan(this))
                .bounds(contentX, y, half, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanlex.hub.open_repo"),
                        this::confirmOpenRepo)
                .bounds(contentX + contentW - half, y, half, 20).build());
        y += 28;

        clearArmedUntilMs = 0L;
        this.addRenderableWidget(Button.builder(startupCheckLabel(cfg), b -> {
            cfg.hubStartupPromptDisabled = !cfg.hubStartupPromptDisabled;
            NyanLexFabric.saveConfig();
            b.setMessage(startupCheckLabel(cfg));
        }).bounds(contentX, y, half, 20).build());
        clearButton = this.addRenderableWidget(Button.builder(clearLabel(false), this::onClearPressed)
                .bounds(contentX + contentW - half, y, half, 20).build());
        y += 30;

        int doneW = Math.min(200, contentW);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width / 2 - doneW / 2, y, doneW, 20).build());
    }

    private void onClearPressed(Button button) {
        long now = System.currentTimeMillis();
        if (now < clearArmedUntilMs) {
            clearArmedUntilMs = 0L;
            int removed = NyanLexFabric.hubLocalCache().size();
            String clearedLanguage = NyanLexFabric.hubLocalCache().language();
            NyanLexFabric.hubLocalCache().clearAll();
            NyanLexFabric.hubDownloadState().forgetLanguage(clearedLanguage);
            button.setMessage(clearLabel(false));
            NyanLexFabric.postHubStatus(
                    Component.translatable("message.nyanlex.hub.cleared", removed).getString());
        } else {
            clearArmedUntilMs = now + CLEAR_CONFIRM_WINDOW_MS;
            button.setMessage(clearLabel(true));
        }
    }

    private void confirmOpenRepo(Button button) {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) Util.getPlatform().openUri(HUB_URL);
            if (this.minecraft != null) this.minecraft.setScreen(this);
        }, Component.translatable("screen.nyanlex.hub.open_repo.title"),
                Component.translatable("screen.nyanlex.hub.open_repo.message"),
                Component.translatable("screen.nyanlex.hub.open_repo.confirm"),
                Component.translatable("gui.cancel")));
    }

    private static Component startupCheckLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanlex.hub.startup_check",
                Component.translatable(cfg.hubStartupPromptDisabled ? "options.off" : "options.on"));
    }

    private static Component clearLabel(boolean armed) {
        return Component.translatable(armed
                ? "config.nyanlex.hub.clear.confirm" : "config.nyanlex.hub.clear");
    }

    /** While a download is running, the identify button doubles as its progress readout. */
    private static Component identifyLabel() {
        HubDownloadJob job = NyanLexFabric.hubDownloadJob();
        if (job == null || !job.isRunning()) return Component.translatable("config.nyanlex.hub.identify");
        long total = job.totalBytes();
        long done = job.downloadedBytes();
        int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
        return Component.translatable("config.nyanlex.hub.identify.downloading", percent + "%");
    }

    @Override
    public void render(PoseStack g, int mouseX, int mouseY, float partialTick) {
        if (identifyButton != null) identifyButton.setMessage(identifyLabel());
        if (clearButton != null && clearArmedUntilMs != 0L && System.currentTimeMillis() >= clearArmedUntilMs) {
            clearArmedUntilMs = 0L;
            clearButton.setMessage(clearLabel(false));
        }
        super.render(g, mouseX, mouseY, partialTick);
        GuiComponent.drawCenteredString(g, this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int lineH = this.font.lineHeight + 1;
        int introColor = 0xFFB0B0B0;
        int ly = introY;
        for (FormattedCharSequence line : introLines) {
            GuiComponent.drawCenteredString(g, this.font, line, this.width / 2, ly, introColor);
            ly += lineH;
        }
    }

    @Override
    public void onClose() {
        NyanLexFabric.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
