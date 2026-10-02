package com.dragonmeow.nyanslate.fabric;

import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.hub.HubDownloadJob;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * GitHub AI 翻譯倉庫設定 — repository share/consent preferences, the
 * "識別當前伺服器/MOD下載並匯入翻譯檔" action button, the startup-check toggle, and
 * "清除倉庫翻譯".
 */
public final class TranslationHubScreen extends Screen {

    static final String HUB_URL =
            "https://github.com/DragonMeow1012/Nyanslate/tree/main/translation-hub";
    private static final int W = 300;

    /** "清除倉庫翻譯" requires two presses within this window to actually clear. */
    private static final long CLEAR_CONFIRM_WINDOW_MS = 4_000L;

    private final Screen parent;
    private boolean showIntro;
    private List<FormattedCharSequence> introLines = List.of();
    private List<FormattedCharSequence> questionLines = List.of();
    private int introY;
    private int questionY;
    private Button identifyButton;
    private Button clearButton;
    private long clearArmedUntilMs;

    public TranslationHubScreen(Screen parent) {
        super(Component.translatable("screen.nyanslate.hub.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanslateFabric.config();
        int contentW = Math.max(120, Math.min(W, this.width - 20));
        int contentX = this.width / 2 - contentW / 2;
        int half = (contentW - 6) / 2;
        int lineH = this.font.lineHeight + 1;

        // First open ever: show the full explanation once, then remember it was seen.
        showIntro = !cfg.hubIntroSeen;
        if (showIntro) {
            cfg.hubIntroSeen = true;
            NyanslateFabric.saveConfig();
        }
        Component introText = Component.translatable(
                showIntro ? "screen.nyanslate.hub.intro" : "screen.nyanslate.hub.hint");
        introLines = this.font.split(introText, contentW);

        int y = 30;
        introY = y;
        y += introLines.size() * lineH + 10;

        identifyButton = this.addRenderableWidget(Button.builder(identifyLabel(), b ->
                NyanslateFabric.startHubIdentifyAndPlan(this))
                .bounds(contentX, y, half, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.nyanslate.hub.open_repo"),
                        this::confirmOpenRepo)
                .bounds(contentX + contentW - half, y, half, 20).build());
        y += 28;

        questionLines = this.font.split(
                Component.translatable("screen.nyanslate.hub.consent.question"), contentW);
        questionY = y;
        y += questionLines.size() * lineH + 6;

        int consentW = Math.min(half * 2, contentW);
        this.addRenderableWidget(Button.builder(consentLabel(cfg), b -> {
            cfg.hubShareConsent = !cfg.hubShareConsent;
            NyanslateFabric.saveConfig();
            b.setMessage(consentLabel(cfg));
        }).bounds(this.width / 2 - consentW / 2, y, consentW, 20).build());
        y += 28;

        clearArmedUntilMs = 0L;
        this.addRenderableWidget(Button.builder(startupCheckLabel(cfg), b -> {
            cfg.hubStartupPromptDisabled = !cfg.hubStartupPromptDisabled;
            NyanslateFabric.saveConfig();
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
            int removed = NyanslateFabric.hubLocalCache().size();
            String clearedLanguage = NyanslateFabric.hubLocalCache().language();
            NyanslateFabric.hubLocalCache().clearAll();
            // A5: also drop this language's sha256 throttling ledger, or the next
            // identify/download pass reports "already up to date" for unchanged upstream
            // content and the player can never get back what they just cleared.
            NyanslateFabric.hubDownloadState().forgetLanguage(clearedLanguage);
            button.setMessage(clearLabel(false));
            NyanslateFabric.postHubStatus(
                    Component.translatable("message.nyanslate.hub.cleared", removed).getString());
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
        }, Component.translatable("screen.nyanslate.hub.open_repo.title"),
                Component.translatable("screen.nyanslate.hub.open_repo.message"),
                Component.translatable("screen.nyanslate.hub.open_repo.confirm"),
                Component.translatable("gui.cancel")));
    }

    private static Component consentLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.hub.consent",
                Component.translatable(cfg.hubShareConsent
                        ? "config.nyanslate.hub.consent.yes"
                        : "config.nyanslate.hub.consent.no"));
    }

    private static Component startupCheckLabel(TranslatorConfig cfg) {
        return Component.translatable("config.nyanslate.hub.startup_check",
                Component.translatable(cfg.hubStartupPromptDisabled ? "options.off" : "options.on"));
    }

    private static Component clearLabel(boolean armed) {
        return Component.translatable(armed
                ? "config.nyanslate.hub.clear.confirm" : "config.nyanslate.hub.clear");
    }

    /** While a download is running, the identify button doubles as its progress readout. */
    private static Component identifyLabel() {
        HubDownloadJob job = NyanslateFabric.hubDownloadJob();
        if (job == null || !job.isRunning()) return Component.translatable("config.nyanslate.hub.identify");
        long total = job.totalBytes();
        long done = job.downloadedBytes();
        int percent = total > 0 ? (int) Math.min(100L, done * 100L / total)
                : (job.totalFiles() > 0 ? job.completedFiles() * 100 / job.totalFiles() : 0);
        return Component.translatable("config.nyanslate.hub.identify.downloading", percent + "%");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (identifyButton != null) identifyButton.setMessage(identifyLabel());
        if (clearButton != null && clearArmedUntilMs != 0L && System.currentTimeMillis() >= clearArmedUntilMs) {
            clearArmedUntilMs = 0L;
            clearButton.setMessage(clearLabel(false));
        }
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int lineH = this.font.lineHeight + 1;
        int introColor = showIntro ? 0xFFFFD700 : 0xFF909090;
        int ly = introY;
        for (FormattedCharSequence line : introLines) {
            g.drawCenteredString(this.font, line, this.width / 2, ly, introColor);
            ly += lineH;
        }

        int qy = questionY;
        for (FormattedCharSequence line : questionLines) {
            g.drawCenteredString(this.font, line, this.width / 2, qy, 0xFFA0A0A0);
            qy += lineH;
        }
    }

    @Override
    public void onClose() {
        NyanslateFabric.saveConfig();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
