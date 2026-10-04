package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.translate.AntigravityCliClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Explicit, visible installer management for the two optional account-login CLIs. */
public final class CliSetupScreen extends Screen {
    enum Provider {
        CODEX("ChatGPT", "https://developers.openai.com/codex/cli/"),
        ANTIGRAVITY("Antigravity CLI", "https://antigravity.google/download");

        private final String displayName;
        private final String officialUrl;

        Provider(String displayName, String officialUrl) {
            this.displayName = displayName;
            this.officialUrl = officialUrl;
        }
    }

    private final AiConfigScreen parent;
    private final Provider provider;
    private Button uninstallButton;
    private boolean checking;
    private Boolean installed;
    private String status = "";

    CliSetupScreen(AiConfigScreen parent, Provider provider) {
        super(Component.translatable("screen.nyanlex.ai.cli.setup_title", provider.displayName));
        this.parent = parent;
        this.provider = provider;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 150;
        int y = Math.max(48, this.height / 2 - 58);
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.nyanlex.ai.cli.official_install_guide"),
                button -> Util.getPlatform().openUri(this.provider.officialUrl))
                .bounds(x, y, 300, 20).build());

        this.uninstallButton = Button.builder(
                Component.translatable(codexUsesDesktopApp()
                        ? "screen.nyanlex.ai.cli.open_system_uninstall"
                        : "screen.nyanlex.ai.cli.uninstall"),
                button -> confirmUninstall()).bounds(x, y + 28, 300, 20).build();
        this.uninstallButton.active = Boolean.TRUE.equals(this.installed);
        this.addRenderableWidget(this.uninstallButton);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"),
                button -> onClose()).bounds(this.width / 2 - 100, y + 76, 200, 20).build());
        if (!this.checking && this.installed == null) refreshInstalledState();
    }

    private void refreshInstalledState() {
        this.checking = true;
        this.status = Component.translatable("screen.nyanlex.ai.cli.checking").getString();
        Thread thread = new Thread(() -> {
            boolean found = this.provider == Provider.CODEX
                    ? codexInstalled() : antigravityInstalled();
            Minecraft client = Minecraft.getInstance();
            if (client != null) client.execute(() -> {
                this.checking = false;
                this.installed = found;
                String statusKey = found
                        ? this.provider == Provider.CODEX && codexUsesDesktopApp()
                                ? "screen.nyanlex.ai.cli.codex_app_installed"
                                : "screen.nyanlex.ai.cli.installed"
                        : this.provider == Provider.ANTIGRAVITY && antigravityDesktopInstalled()
                                ? "screen.nyanlex.ai.cli.desktop_only"
                                : "screen.nyanlex.ai.cli.not_installed";
                this.status = Component.translatable(statusKey, this.provider.displayName).getString();
                if (client.screen == this) this.rebuildWidgets();
            });
        }, "nyanlex-cli-setup-check");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean codexInstalled() {
        CodexAppServerClient client = NyanLexFabric.codexClient();
        return client != null && client.isInstalled();
    }

    private boolean codexUsesDesktopApp() {
        if (this.provider != Provider.CODEX) return false;
        CodexAppServerClient client = NyanLexFabric.codexClient();
        return client != null && client.usesBundledDesktopAppExecutable();
    }

    private boolean antigravityInstalled() {
        AntigravityCliClient client = NyanLexFabric.antigravityClient();
        return client != null && client.isInstalled();
    }

    private static boolean antigravityDesktopInstalled() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) return false;
        try {
            return Files.isRegularFile(Path.of(localAppData, "Programs", "antigravity",
                    "Antigravity.exe"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void confirmUninstall() {
        if (this.minecraft == null) return;
        boolean desktopApp = codexUsesDesktopApp();
        String messageKey = this.provider == Provider.ANTIGRAVITY
                ? "screen.nyanlex.ai.cli.uninstall_confirm_antigravity"
                : desktopApp
                        ? "screen.nyanlex.ai.cli.uninstall_confirm_codex_app"
                        : "screen.nyanlex.ai.cli.uninstall_confirm_codex_cli";
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (this.minecraft != null) this.minecraft.setScreen(this);
            if (confirmed) uninstallCli();
        }, Component.translatable("screen.nyanlex.ai.cli.uninstall_confirm_title"),
                Component.translatable(messageKey),
                Component.translatable(desktopApp
                        ? "screen.nyanlex.ai.cli.open_system_uninstall"
                        : "screen.nyanlex.ai.cli.uninstall"),
                Component.translatable("gui.cancel")));
    }

    private void uninstallCli() {
        if (this.provider == Provider.CODEX) {
            uninstallCodex();
            return;
        }
        uninstallAntigravity();
    }

    private void uninstallAntigravity() {
        AntigravityCliClient client = NyanLexFabric.antigravityClient();
        if (client != null) client.close();
        Path executable = antigravityUserExecutable();
        try {
            boolean removed = Files.deleteIfExists(executable);
            this.installed = false;
            this.status = Component.translatable(removed
                    ? "screen.nyanlex.ai.cli.uninstalled"
                    : "screen.nyanlex.ai.cli.not_installed", this.provider.displayName).getString();
            if (this.uninstallButton != null) this.uninstallButton.active = false;
        } catch (IOException | RuntimeException e) {
            this.status = Component.translatable("message.nyanlex.failed",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).getString();
        }
    }

    private void uninstallCodex() {
        CodexAppServerClient client = NyanLexFabric.codexClient();
        if (client == null) return;
        if (client.usesBundledDesktopAppExecutable()) {
            try {
                if (isWindows()) {
                    new ProcessBuilder("cmd.exe", "/d", "/c", "start", "",
                            "ms-settings:appsfeatures").start();
                } else {
                    Util.getPlatform().openUri(this.provider.officialUrl);
                }
                this.status = Component.translatable(
                        "screen.nyanlex.ai.cli.system_uninstall_opened").getString();
            } catch (IOException e) {
                this.status = Component.translatable("message.nyanlex.failed",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).getString();
            }
            return;
        }

        client.prepareForCliUninstall();
        try {
            visibleTerminal("npm uninstall -g @openai/codex").start();
            this.installed = null;
            this.status = Component.translatable(
                    "screen.nyanlex.ai.cli.terminal_uninstall_opened").getString();
            if (this.uninstallButton != null) this.uninstallButton.active = false;
        } catch (IOException e) {
            this.status = Component.translatable("message.nyanlex.failed",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).getString();
        }
    }

    private static Path antigravityUserExecutable() {
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isBlank()) {
                throw new IllegalStateException("LOCALAPPDATA is unavailable");
            }
            return Path.of(localAppData, "agy", "bin", "agy.exe");
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("user.home is unavailable");
        }
        return Path.of(userHome, ".local", "bin", "agy");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static ProcessBuilder visibleTerminal(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new ProcessBuilder("powershell.exe", "-NoExit",
                    "-ExecutionPolicy", "Bypass", "-Command", command);
        }
        if (os.contains("mac")) {
            String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
            return new ProcessBuilder("osascript", "-e",
                    "tell application \"Terminal\" to do script \"" + escaped + "\"");
        }
        return new ProcessBuilder("x-terminal-emulator", "-e", "sh", "-lc",
                command + "; printf '\\nPress Enter to close...'; read answer");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable(this.provider == Provider.ANTIGRAVITY
                        ? "screen.nyanlex.ai.cli.antigravity_install_notice"
                        : codexUsesDesktopApp()
                                ? "screen.nyanlex.ai.cli.codex_app_notice"
                                : "screen.nyanlex.ai.cli.codex_install_notice"),
                this.width / 2, 34, 0xFFA4A9B8);
        if (!this.status.isBlank()) {
            graphics.drawCenteredString(this.font, Component.literal(this.status),
                    this.width / 2, Math.max(48, this.height / 2 - 58) + 56, 0xFFFFD080);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.parent.refreshLocalProviderOnReturn();
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }
}
