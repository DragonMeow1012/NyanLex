package com.dragonmeow.nyanlex.neoforge;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.AntigravityCliClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient.AccountSnapshot;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient.LoginStart;
import com.dragonmeow.nyanlex.translate.CodexAppServerClient.ModelOption;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AiConfigScreen extends Screen {
    private static final String OPENAI_API_URL = "https://api.openai.com/v1";
    private static final int FIELD_W = 320;

    private final Screen parent;
    private EditBox baseUrlBox;
    private EditBox modelBox;
    private EditBox keysBox;
    private Button loginButton;
    private Button manageButton;
    private Button modelButton;
    private Button effortButton;
    private Button refreshButton;
    private Button testButton;
    private boolean busy;
    private boolean initialCodexRefreshStarted;
    private boolean initialAntigravityRefreshStarted;
    private String status = "";

    // Vertical layout, computed once per init() so the widgets and the text agree. The key notice wraps
    // to as many lines as the full file path needs; everything below it moves down and, when the
    // screen is short, the gaps shrink.
    private List<String> noticeLines = List.of();
    private int layProv;
    private int layMode;
    private int layBaseLabel;
    private int layBase;
    private int layModelLabel;
    private int layModel;
    private int layKeysLabel;
    private int layKeys;
    private int layTest;
    private int layAccount;
    private int layLogin;
    private int layCodexModel;
    private int layEffort;
    private int layCodexTest;
    private int layQuota;
    private int layStatus;
    private int layDone;
    /** When even the tightest gaps do not fit, 測試連接 and 完成 share one row. */
    private boolean tight;

    private void computeLayout(TranslatorConfig cfg) {
        java.nio.file.Path file = NyanLexNeoForge.configFilePath();
        String path = file == null ? "" : file.toAbsolutePath().toString();
        String text = Component.translatable("screen.nyanlex.ai.key_notice", path).getString();
        noticeLines = com.dragonmeow.nyanlex.config.UiText.wrap(text, Math.max(80, this.width - 12), this.font::width);
        boolean accountModes = isOpenAiProvider(cfg) || isGeminiProvider(cfg);
        boolean localCli = cfg.usesLocalAiCli();
        int[] gaps = {10, 6, 4, 2};
        tight = false;
        boolean fitted = false;
        for (int gi = 0; gi < gaps.length; gi++) {
            int g = gaps[gi];
            int small = Math.min(g, 6);
            int end = 17 + noticeLines.size() * 10;
            layAccount = end + 3;
            layProv = end + (localCli ? 17 : 7);
            int y = layProv + 20;
            if (accountModes) {
                y += small;
                layMode = y;
                y += 20;
            }
            if (localCli) {
                y += small;
                layLogin = y;
                y += 20 + g;
                layCodexModel = y;
                y += 20 + g;
                layEffort = y;
                y += 20 + g;
                layCodexTest = y;
                y += 20 + 4;
                layQuota = y;
                y += 10;
                layStatus = y;
                y += 10;
            } else {
                y += Math.min(g, 4);
                layBaseLabel = y;
                layBase = y + 12;
                y = layBase + 20 + g;
                layModelLabel = y;
                layModel = y + 12;
                y = layModel + 20 + g;
                layKeysLabel = y;
                layKeys = y + 12;
                y = layKeys + 20 + Math.min(g, 8);
                layTest = y;
                y += 20;
                layStatus = y + 2;
            }
            layDone = Math.max(y + 4, 210);
            if (layDone + 20 <= this.height - 4) {
                fitted = true;
                break;
            }
        }
        if (!fitted) {
            tight = true;
            layDone = localCli ? layCodexTest : layTest;
            return;
        }
        layDone = Math.min(layDone, Math.max(4, this.height - 24));
    }

    public AiConfigScreen(Screen parent) {
        super(Component.translatable("screen.nyanlex.ai.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        computeLayout(cfg);
        int x = this.width / 2 - FIELD_W / 2;
        boolean accountModes = isOpenAiProvider(cfg) || isGeminiProvider(cfg);
        addProviderButtons(x, layProv);
        if (accountModes) addAccountModeButtons(x, layMode);
        if (cfg.usesCodex()) {
            initCodex(x);
        } else if (cfg.usesAntigravity()) {
            initAntigravity(x);
        } else {
            initApi(x);
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(tight ? this.width / 2 + 3 : this.width / 2 - 100, layDone, tight ? FIELD_W / 2 - 3 : 200, 20).build());

        if (cfg.usesCodex() && !this.initialCodexRefreshStarted) {
            this.initialCodexRefreshStarted = true;
            refreshCodexSession(false);
        }
        if (cfg.usesAntigravity() && !this.initialAntigravityRefreshStarted) {
            this.initialAntigravityRefreshStarted = true;
            refreshAntigravitySession(false);
        }
    }

    private void addProviderButtons(int x, int y) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        int gap = 6;
        int width = (FIELD_W - gap * 3) / 4;
        addProviderButton(providerLabel("Gemini", isGeminiProvider(cfg)),
                x, y, width, this::selectGeminiProvider);
        addProviderButton(providerLabel("OpenAI", isOpenAiProvider(cfg)),
                x + width + gap, y, width, this::selectOpenAiProvider);
        addProviderButton(providerLabel("DeepSeek", !cfg.usesLocalAiCli()
                        && isEndpoint(cfg.aiBaseUrl, "https://api.deepseek.com")),
                x + (width + gap) * 2, y, width,
                () -> selectApiProvider("https://api.deepseek.com", "deepseek-chat"));
        addProviderButton(providerLabel(
                        Component.translatable("screen.nyanlex.ai.custom").getString(),
                        !cfg.usesLocalAiCli() && !isKnownApiEndpoint(cfg.aiBaseUrl)),
                x + (width + gap) * 3, y, width,
                () -> selectApiProvider("http://127.0.0.1:11434/v1", ""));
    }

    private void addProviderButton(String label, int x, int y, int width, Runnable action) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, width, 20).build());
    }

    private void addAccountModeButtons(int x, int y) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        int gap = 6;
        int width = (FIELD_W - gap) / 2;
        boolean gemini = isGeminiProvider(cfg);
        addProviderButton(providerLabel(
                        Component.translatable("screen.nyanlex.ai.openai.api_mode").getString(),
                        !cfg.usesLocalAiCli()),
                x, y, width, gemini ? this::selectGeminiApiMode : this::selectOpenAiApiMode);
        addProviderButton(providerLabel(
                        Component.translatable(gemini
                                ? "screen.nyanlex.ai.gemini.google_mode"
                                : "screen.nyanlex.ai.openai.codex_mode").getString(),
                        cfg.usesLocalAiCli()),
                x + width + gap, y, width,
                gemini ? this::selectAntigravityProvider : this::selectCodexProvider);
    }

    private void initApi(int x) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        int baseY = layBase;
        int modelY = layModel;
        int keysY = layKeys;
        int testY = layTest;
        this.baseUrlBox = new EditBox(this.font, x, baseY, FIELD_W, 20, Component.translatable("screen.nyanlex.ai.endpoint"));
        this.baseUrlBox.setMaxLength(256);
        this.baseUrlBox.setValue(cfg.aiBaseUrl == null ? "" : cfg.aiBaseUrl);
        this.addRenderableWidget(this.baseUrlBox);

        this.modelBox = new EditBox(this.font, x, modelY, FIELD_W, 20, Component.translatable("screen.nyanlex.ai.model"));
        this.modelBox.setMaxLength(128);
        this.modelBox.setValue(cfg.aiModel == null ? "" : cfg.aiModel);
        this.addRenderableWidget(this.modelBox);

        this.keysBox = new EditBox(this.font, x, keysY, FIELD_W, 20,
                Component.translatable("screen.nyanlex.ai.keys"));
        this.keysBox.setMaxLength(8000);
        this.keysBox.setValue(keysForEndpoint(cfg, cfg.aiBaseUrl));
        this.addRenderableWidget(this.keysBox);

        Button[] button = new Button[1];
        button[0] = Button.builder(Component.translatable("screen.nyanlex.ai.test"), b -> {
            saveApiFields();
            b.active = false;
            b.setMessage(Component.translatable("screen.nyanlex.ai.testing"));
            NyanLexNeoForge.testAi(
                    this.baseUrlBox.getValue().trim(),
                    this.modelBox.getValue().trim(),
                    parseKeys(this.keysBox.getValue()),
                    result -> {
                        if (button[0] != null) {
                            button[0].active = true;
                            button[0].setMessage(Component.literal(result));
                        }
                    });
        }).bounds(x, testY, tight ? FIELD_W / 2 - 3 : FIELD_W, 20).build();
        this.testButton = button[0];
        this.addRenderableWidget(button[0]);
    }

    private void initCodex(int x) {
        CodexAppServerClient client = NyanLexNeoForge.codexClient();
        AccountSnapshot account = client == null ? AccountSnapshot.signedOut() : client.cachedAccount();
        boolean signedIn = account.signedIn();

        this.loginButton = Button.builder(
                Component.translatable(signedIn
                        ? "screen.nyanlex.ai.codex.logout"
                        : "screen.nyanlex.ai.codex.login"),
                b -> {
                    if (signedIn) logoutCodex();
                    else loginCodex();
                }).bounds(x, layLogin, 252, 20).build();
        this.loginButton.active = !this.busy;
        this.addRenderableWidget(this.loginButton);

        this.manageButton = Button.builder(
                Component.translatable("screen.nyanlex.ai.cli.manage"),
                b -> openCliSetup(CliSetupScreen.Provider.CODEX))
                .bounds(x + 258, layLogin, 62, 20).build();
        this.manageButton.active = !this.busy;
        this.addRenderableWidget(this.manageButton);

        this.modelButton = Button.builder(modelLabel(), b -> openModelPicker())
                .bounds(x, layCodexModel, 252, 20).build();
        this.modelButton.active = signedIn && !this.busy && client != null && !client.cachedModels().isEmpty();
        this.addRenderableWidget(this.modelButton);

        this.refreshButton = Button.builder(
                Component.translatable("screen.nyanlex.ai.codex.refresh"),
                b -> refreshCodexSession(true)).bounds(x + 258, layCodexModel, 62, 20).build();
        this.refreshButton.active = signedIn && !this.busy && client != null;
        this.addRenderableWidget(this.refreshButton);

        this.effortButton = Button.builder(effortLabel(), b -> openEffortPicker())
                .bounds(x, layEffort, FIELD_W, 20).build();
        this.effortButton.active = signedIn && !this.busy
                && selectedModel().map(option -> !option.reasoningEfforts().isEmpty()).orElse(false);
        this.addRenderableWidget(this.effortButton);

        this.testButton = Button.builder(
                Component.translatable("screen.nyanlex.ai.test"),
                b -> testCodex()).bounds(x, layCodexTest, tight ? FIELD_W / 2 - 3 : FIELD_W, 20).build();
        this.testButton.active = signedIn && !this.busy
                && NyanLexNeoForge.config().codexModel != null
                && !NyanLexNeoForge.config().codexModel.isBlank();
        this.addRenderableWidget(this.testButton);
    }

    private void initAntigravity(int x) {
        AntigravityCliClient client = NyanLexNeoForge.antigravityClient();
        boolean installed = client != null && client.isInstalledCached();
        boolean modelsLoaded = installed && !client.cachedModels().isEmpty();
        boolean signedIn = modelsLoaded && client.hasAuthenticatedSessionCached();

        this.loginButton = Button.builder(
                Component.translatable(signedIn
                        ? "screen.nyanlex.ai.antigravity.logout"
                        : "screen.nyanlex.ai.antigravity.login"),
                b -> {
                    if (signedIn) logoutAntigravity();
                    else openAntigravityLogin();
                }).bounds(x, layLogin, 252, 20).build();
        this.loginButton.active = installed && !this.busy;
        this.addRenderableWidget(this.loginButton);

        this.manageButton = Button.builder(
                Component.translatable("screen.nyanlex.ai.cli.manage"),
                b -> openCliSetup(CliSetupScreen.Provider.ANTIGRAVITY))
                .bounds(x + 258, layLogin, 62, 20).build();
        this.manageButton.active = !this.busy;
        this.addRenderableWidget(this.manageButton);

        this.modelButton = Button.builder(modelLabel(), b -> openModelPicker())
                .bounds(x, layCodexModel, 252, 20).build();
        this.modelButton.active = signedIn && !this.busy;
        this.addRenderableWidget(this.modelButton);

        this.refreshButton = Button.builder(
                Component.translatable("screen.nyanlex.ai.codex.refresh"),
                b -> refreshAntigravitySession(true)).bounds(x + 258, layCodexModel, 62, 20).build();
        this.refreshButton.active = !this.busy;
        this.addRenderableWidget(this.refreshButton);

        this.testButton = Button.builder(
                Component.translatable("screen.nyanlex.ai.test"),
                b -> testAntigravity()).bounds(x, layEffort,
                        tight ? FIELD_W / 2 - 3 : FIELD_W, 20).build();
        this.testButton.active = signedIn && !this.busy
                && NyanLexNeoForge.config().antigravityModel != null
                && !NyanLexNeoForge.config().antigravityModel.isBlank();
        this.addRenderableWidget(this.testButton);
    }

    private void selectGeminiProvider() {
        if (isGeminiProvider(NyanLexNeoForge.config())) {
            this.rebuildWidgets();
            return;
        }
        selectGeminiApiMode();
    }

    private void selectGeminiApiMode() {
        selectApiProvider("https://generativelanguage.googleapis.com/v1beta/openai",
                "gemini-3.1-flash-lite");
    }

    private void selectOpenAiProvider() {
        if (isOpenAiProvider(NyanLexNeoForge.config())) {
            this.rebuildWidgets();
            return;
        }
        selectOpenAiApiMode();
    }

    private void selectOpenAiApiMode() {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        saveCurrentFields();
        boolean alreadyOpenAiApi = isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL);
        String openAiKeys = keysForEndpoint(cfg, OPENAI_API_URL);
        cfg.selectAiProvider(TranslatorConfig.AI_PROVIDER_API);
        cfg.aiBaseUrl = OPENAI_API_URL;
        if (!alreadyOpenAiApi) cfg.aiModel = "gpt-5.4-mini";
        cfg.aiApiKeys = parseKeys(openAiKeys);
        NyanLexNeoForge.saveConfig();
        NeoTextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void selectApiProvider(String url, String model) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        saveCurrentFields();
        String providerKeys = keysForEndpoint(cfg, url);
        cfg.selectAiProvider(TranslatorConfig.AI_PROVIDER_API);
        cfg.aiBaseUrl = url;
        cfg.aiModel = model;
        cfg.aiApiKeys = parseKeys(providerKeys);
        NyanLexNeoForge.saveConfig();
        NeoTextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void selectCodexProvider() {
        saveCurrentFields();
        TranslatorConfig cfg = NyanLexNeoForge.config();
        if (!isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL)) {
            String openAiKeys = keysForEndpoint(cfg, OPENAI_API_URL);
            cfg.aiBaseUrl = OPENAI_API_URL;
            cfg.aiModel = "gpt-5.4-mini";
            cfg.aiApiKeys = parseKeys(openAiKeys);
        }
        cfg.selectAiProvider(TranslatorConfig.AI_PROVIDER_CODEX);
        NyanLexNeoForge.saveConfig();
        NeoTextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void selectAntigravityProvider() {
        saveCurrentFields();
        TranslatorConfig cfg = NyanLexNeoForge.config();
        if (!isEndpoint(cfg.aiBaseUrl, "https://generativelanguage.googleapis.com/v1beta/openai")) {
            String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
            String geminiKeys = keysForEndpoint(cfg, geminiUrl);
            cfg.aiBaseUrl = geminiUrl;
            cfg.aiModel = "gemini-3.1-flash-lite";
            cfg.aiApiKeys = parseKeys(geminiKeys);
        }
        cfg.selectAiProvider(TranslatorConfig.AI_PROVIDER_ANTIGRAVITY);
        NyanLexNeoForge.saveConfig();
        NeoTextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void saveApiFields() {
        if (this.baseUrlBox == null || this.modelBox == null || this.keysBox == null) return;
        TranslatorConfig cfg = NyanLexNeoForge.config();
        String url = this.baseUrlBox.getValue().trim();
        cfg.aiBaseUrl = url;
        cfg.aiModel = this.modelBox.getValue().trim();
        cfg.aiApiKeys = parseKeys(this.keysBox.getValue());
        cfg.aiKeysByEndpoint.put(endpointKey(url), this.keysBox.getValue());
    }

    private void saveCurrentFields() {
        if (!NyanLexNeoForge.config().usesLocalAiCli()) saveApiFields();
    }

    private void refreshCodexSession(boolean userInitiated) {
        CodexAppServerClient client = NyanLexNeoForge.codexClient();
        if (client == null) {
            setStatus(Component.translatable("message.nyanlex.not_initialized").getString(), false);
            return;
        }
        setBusy(true, Component.translatable("screen.nyanlex.ai.codex.refreshing").getString());
        runAsync("nyanlex-codex-refresh", () -> {
            if (!client.isInstalled()) {
                onMain(() -> {
                    setBusy(false, Component.translatable(
                            "screen.nyanlex.ai.codex.not_installed").getString());
                    if (userInitiated) showMissingCodexPrompt();
                });
                return;
            }
            try {
                AccountSnapshot account = client.readAccount(true);
                if (account.signedIn()) {
                    List<ModelOption> models = client.listModels();
                    normalizeCodexSelection(models);
                    setStatus(Component.translatable(
                            "screen.nyanlex.ai.codex.models_updated", models.size()).getString(), true);
                } else {
                    setStatus(Component.translatable(
                            "screen.nyanlex.ai.codex.signed_out").getString(), true);
                }
            } catch (Exception e) {
                setStatus(Component.translatable("message.nyanlex.failed",
                        errorMessage(e)).getString(), true);
                if (userInitiated && !client.isInstalled()) {
                    onMain(this::showMissingCodexPrompt);
                }
            }
        });
    }

    private void loginCodex() {
        CodexAppServerClient client = NyanLexNeoForge.codexClient();
        if (client == null) return;
        setBusy(true, Component.translatable("screen.nyanlex.ai.codex.starting_login").getString());
        runAsync("nyanlex-codex-login", () -> {
            if (!client.isInstalled()) {
                onMain(() -> {
                    setBusy(false, Component.translatable(
                            "screen.nyanlex.ai.codex.not_installed").getString());
                    showMissingCodexPrompt();
                });
                return;
            }
            try {
                LoginStart login = client.startLogin();
                onMain(() -> Util.getPlatform().openUri(login.authUrl()));
                setStatus(Component.translatable(
                        "screen.nyanlex.ai.codex.waiting_login").getString(), false);
                boolean success = client.awaitLogin(login.loginId(), Duration.ofMinutes(10));
                if (!success) {
                    setStatus(Component.translatable(
                            "screen.nyanlex.ai.codex.login_failed").getString(), true);
                    return;
                }
                List<ModelOption> models = client.listModels();
                normalizeCodexSelection(models);
                setStatus(Component.translatable(
                        "screen.nyanlex.ai.codex.login_success", models.size()).getString(), true);
            } catch (Exception e) {
                setStatus(Component.translatable("message.nyanlex.failed",
                        errorMessage(e)).getString(), true);
            }
        });
    }

    private void logoutCodex() {
        CodexAppServerClient client = NyanLexNeoForge.codexClient();
        if (client == null) return;
        setBusy(true, Component.translatable("screen.nyanlex.ai.codex.logging_out").getString());
        runAsync("nyanlex-codex-logout", () -> {
            try {
                client.logout();
                setStatus(Component.translatable(
                        "screen.nyanlex.ai.codex.logout_success").getString(), true);
            } catch (Exception e) {
                setStatus(Component.translatable("message.nyanlex.failed",
                        errorMessage(e)).getString(), true);
            }
        });
    }

    private void testCodex() {
        if (this.testButton != null) {
            this.testButton.active = false;
            this.testButton.setMessage(Component.translatable("screen.nyanlex.ai.testing"));
        }
        NyanLexNeoForge.testCodex(result -> {
            this.status = result;
            if (this.testButton != null) {
                this.testButton.active = true;
                this.testButton.setMessage(Component.translatable("screen.nyanlex.ai.test"));
            }
        });
    }

    private void refreshAntigravitySession(boolean userInitiated) {
        AntigravityCliClient client = NyanLexNeoForge.antigravityClient();
        if (client == null) {
            setStatus(Component.translatable("message.nyanlex.not_initialized").getString(), false);
            return;
        }
        setBusy(true, Component.translatable(
                "screen.nyanlex.ai.antigravity.checking").getString());
        runAsync("nyanlex-antigravity-refresh", () -> {
            boolean installed = client.isInstalled();
            if (installed) {
                try {
                    List<AntigravityCliClient.ModelOption> models = client.listModels();
                    normalizeAntigravitySelection(models);
                    setStatus(Component.translatable(
                            "screen.nyanlex.ai.antigravity.models_updated", models.size()).getString(), true);
                } catch (Exception e) {
                    setStatus(Component.translatable("message.nyanlex.failed",
                            errorMessage(e)).getString(), true);
                }
            } else {
                onMain(() -> {
                    setBusy(false, Component.translatable(
                            "screen.nyanlex.ai.antigravity.not_installed").getString());
                    if (userInitiated) showMissingAntigravityPrompt();
                });
            }
        });
    }

    private void openAntigravityLogin() {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (this.minecraft != null) this.minecraft.setScreen(this);
            if (confirmed) launchAntigravityLogin();
        }, Component.translatable("screen.nyanlex.ai.antigravity.login_guide_title"),
                Component.translatable("screen.nyanlex.ai.antigravity.login_guide_message"),
                Component.translatable("screen.nyanlex.ai.antigravity.login_guide_open"),
                Component.translatable("gui.cancel")));
    }

    private void launchAntigravityLogin() {
        AntigravityCliClient client = NyanLexNeoForge.antigravityClient();
        if (client == null) return;
        setBusy(true, Component.translatable(
                "screen.nyanlex.ai.antigravity.starting_login").getString());
        runAsync("nyanlex-antigravity-login", () -> {
            try {
                client.openLoginTerminal();
                setStatus(Component.translatable(
                        "screen.nyanlex.ai.antigravity.login_opened").getString(), true);
            } catch (Exception e) {
                setStatus(Component.translatable("message.nyanlex.failed",
                        errorMessage(e)).getString(), true);
                if (!client.isInstalledCached()) onMain(this::showMissingAntigravityPrompt);
            }
        });
    }

    private void logoutAntigravity() {
        AntigravityCliClient client = NyanLexNeoForge.antigravityClient();
        if (client == null) return;
        setBusy(true, Component.translatable(
                "screen.nyanlex.ai.antigravity.logging_out").getString());
        runAsync("nyanlex-antigravity-logout", () -> {
            try {
                client.openLogoutTerminal();
                setStatus(Component.translatable(
                        "screen.nyanlex.ai.antigravity.logout_opened").getString(), true);
            } catch (Exception e) {
                setStatus(Component.translatable("message.nyanlex.failed",
                        errorMessage(e)).getString(), true);
            }
        });
    }

    private void testAntigravity() {
        NyanLexNeoForge.saveConfig();
        if (this.testButton != null) {
            this.testButton.active = false;
            this.testButton.setMessage(Component.translatable("screen.nyanlex.ai.testing"));
        }
        NyanLexNeoForge.testAntigravity(result -> {
            this.status = result;
            if (this.testButton != null) {
                this.testButton.active = true;
                this.testButton.setMessage(Component.translatable("screen.nyanlex.ai.test"));
            }
        });
    }

    private void openModelPicker() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LocalAiModelScreen(this));
        }
    }

    private void openEffortPicker() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LocalAiEffortScreen(this));
        }
    }

    private void normalizeCodexSelection(List<ModelOption> models) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        if (models == null || models.isEmpty()) {
            cfg.codexModel = TranslatorConfig.DEFAULT_CODEX_MODEL;
            cfg.codexReasoningEffort = TranslatorConfig.DEFAULT_CODEX_REASONING_EFFORT;
            NyanLexNeoForge.saveConfig();
            return;
        }
        ModelOption selected = models.stream()
                .filter(option -> option.model().equals(cfg.codexModel))
                .findFirst()
                .orElseGet(() -> models.stream().filter(ModelOption::isDefault)
                        .findFirst().orElse(models.get(0)));
        cfg.codexModel = selected.model();
        normalizeEffort(cfg, selected);
        NyanLexNeoForge.saveConfig();
    }

    private void normalizeAntigravitySelection(List<AntigravityCliClient.ModelOption> models) {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        if (models == null || models.isEmpty()) {
            cfg.antigravityModel = TranslatorConfig.DEFAULT_ANTIGRAVITY_MODEL;
            NyanLexNeoForge.saveConfig();
            return;
        }
        boolean selectedAvailable = models.stream()
                .anyMatch(option -> option.model().equals(cfg.antigravityModel));
        if (!selectedAvailable) {
            cfg.antigravityModel = models.stream()
                    .filter(option -> option.model().equals(
                            TranslatorConfig.DEFAULT_ANTIGRAVITY_MODEL))
                    .findFirst().orElse(models.get(0)).model();
        }
        NyanLexNeoForge.saveConfig();
    }

    static void normalizeEffort(TranslatorConfig cfg, ModelOption selected) {
        List<String> efforts = selected.reasoningEfforts();
        if (efforts.isEmpty()) {
            cfg.codexReasoningEffort = "";
            return;
        }
        if (efforts.contains(cfg.codexReasoningEffort)) return;
        String defaultEffort = selected.defaultReasoningEffort();
        cfg.codexReasoningEffort = defaultEffort != null && efforts.contains(defaultEffort)
                ? defaultEffort : efforts.get(0);
    }

    private java.util.Optional<ModelOption> selectedModel() {
        CodexAppServerClient client = NyanLexNeoForge.codexClient();
        if (client == null) return java.util.Optional.empty();
        String selected = NyanLexNeoForge.config().codexModel;
        return client.cachedModels().stream()
                .filter(option -> option.model().equals(selected))
                .findFirst();
    }

    private Component modelLabel() {
        if (NyanLexNeoForge.config().usesAntigravity()) {
            AntigravityCliClient client = NyanLexNeoForge.antigravityClient();
            String selectedModel = NyanLexNeoForge.config().antigravityModel;
            AntigravityCliClient.ModelOption selected = client == null ? null
                    : client.cachedModels().stream()
                            .filter(option -> option.model().equals(selectedModel))
                            .findFirst().orElse(null);
            String name = selected == null
                    ? Component.translatable("screen.nyanlex.ai.codex.no_models").getString()
                    : selected.displayName();
            return Component.translatable("screen.nyanlex.ai.codex.model", name);
        }
        ModelOption selected = selectedModel().orElse(null);
        String name = selected == null
                ? Component.translatable("screen.nyanlex.ai.codex.no_models").getString()
                : selected.displayName();
        return Component.translatable("screen.nyanlex.ai.codex.model", name);
    }

    private Component effortLabel() {
        String effort = NyanLexNeoForge.config().codexReasoningEffort;
        if (effort == null || effort.isBlank()) {
            effort = Component.translatable(
                    "screen.nyanlex.ai.codex.effort_unavailable").getString();
        }
        return Component.translatable("screen.nyanlex.ai.codex.effort", effort);
    }

    private void setBusy(boolean value, String newStatus) {
        onMain(() -> {
            this.busy = value;
            this.status = newStatus == null ? "" : newStatus;
            if (isCurrentScreen()) this.rebuildWidgets();
        });
    }

    private void setStatus(String newStatus, boolean finishBusy) {
        onMain(() -> {
            if (finishBusy) this.busy = false;
            this.status = newStatus == null ? "" : newStatus;
            if (isCurrentScreen()) this.rebuildWidgets();
        });
    }

    private void showMissingCodexPrompt() {
        openCliSetup(CliSetupScreen.Provider.CODEX);
    }

    private void showMissingAntigravityPrompt() {
        openCliSetup(CliSetupScreen.Provider.ANTIGRAVITY);
    }

    private void openCliSetup(CliSetupScreen.Provider provider) {
        if (this.minecraft != null) this.minecraft.setScreen(new CliSetupScreen(this, provider));
    }

    void refreshLocalProviderOnReturn() {
        this.busy = false;
        this.status = "";
        if (NyanLexNeoForge.config().usesCodex()) this.initialCodexRefreshStarted = false;
        if (NyanLexNeoForge.config().usesAntigravity()) this.initialAntigravityRefreshStarted = false;
    }

    private void runAsync(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void onMain(Runnable task) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) client.execute(task);
        else task.run();
    }

    private boolean isCurrentScreen() {
        return this.minecraft != null && this.minecraft.screen == this;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 4, 0xFFFFFFFF);

        TranslatorConfig cfg = NyanLexNeoForge.config();
        drawKeyNotice(graphics);
        if (cfg.usesLocalAiCli()) {
            if (cfg.usesCodex()) drawCodexAccount(graphics);
            else drawAntigravityState(graphics);
            // always visible, whatever the sign-in status line says below it
            graphics.drawCenteredString(this.font, Component.translatable(
                    cfg.usesCodex()
                            ? "screen.nyanlex.ai.codex.quota_notice"
                            : "screen.nyanlex.ai.antigravity.quota_notice"),
                    this.width / 2, layQuota, 0xFFA4A9B8);
            if (!this.status.isBlank()) {
                graphics.drawCenteredString(this.font, Component.literal(this.status),
                        this.width / 2, layStatus, 0xFFFFD080);
            }
        } else {
            int x = this.width / 2 - FIELD_W / 2;
            graphics.drawString(this.font, Component.translatable("screen.nyanlex.ai.endpoint"), x, layBaseLabel, 0xFFA0A0A0, false);
            graphics.drawString(this.font, Component.translatable("screen.nyanlex.ai.model"), x, layModelLabel, 0xFFA0A0A0, false);
            graphics.drawString(this.font, Component.translatable("screen.nyanlex.ai.keys"), x, layKeysLabel, 0xFFA0A0A0, false);
            if (!this.status.isBlank()) {
                graphics.drawCenteredString(this.font, Component.literal(this.status),
                        this.width / 2, layStatus, 0xFFFFD080);
            }
        }
    }

    /** The always-visible notice: where the API keys live (the full path, wrapped) and that they never leave this computer. */
    private void drawKeyNotice(GuiGraphics graphics) {
        int y = 17;
        for (String line : noticeLines) {
            graphics.drawString(this.font, line, 6, y, 0xFFA4A9B8, false);
            y += 10;
        }
    }

    private void drawCodexAccount(GuiGraphics graphics) {
        CodexAppServerClient client = NyanLexNeoForge.codexClient();
        AccountSnapshot account = client == null ? AccountSnapshot.signedOut() : client.cachedAccount();
        if (!account.signedIn()) {
            Component line = Component.translatable("screen.nyanlex.ai.codex.signed_out");
            graphics.drawCenteredString(this.font, line, this.width / 2, layAccount, 0xFF909090);
            return;
        }
        Component line1 = Component.translatable("screen.nyanlex.ai.codex.signed_in");
        Component line2 = Component.literal(maskEmail(account.email()));
        Component line3 = Component.literal(formatPlan(account.planType()));
        int total = this.font.width(line1) + this.font.width(line2) + this.font.width(line3) + 16;
        int x = this.width / 2 - total / 2;
        graphics.drawString(this.font, line1, x, layAccount, 0xFF80FF80, false);
        x += this.font.width(line1) + 8;
        graphics.drawString(this.font, line2, x, layAccount, 0xFFFFFFFF, false);
        x += this.font.width(line2) + 8;
        graphics.drawString(this.font, line3, x, layAccount, 0xFFA0A0A0, false);
    }

    private void drawAntigravityState(GuiGraphics graphics) {
        AntigravityCliClient client = NyanLexNeoForge.antigravityClient();
        boolean installed = client != null && client.isInstalledCached();
        boolean signedIn = installed && !client.cachedModels().isEmpty()
                && client.hasAuthenticatedSessionCached();
        if (!signedIn) {
            Component line = Component.translatable(installed
                    ? "screen.nyanlex.ai.antigravity.signed_out"
                    : "screen.nyanlex.ai.antigravity.not_installed");
            graphics.drawCenteredString(this.font, line, this.width / 2, layAccount, 0xFF909090);
            return;
        }
        Component line1 = Component.translatable("screen.nyanlex.ai.antigravity.signed_in");
        String email = client.cachedAccountEmail();
        Component line2 = Component.literal(email == null || email.isBlank()
                ? "Google" : maskEmail(email));
        int total = this.font.width(line1) + this.font.width(line2) + 8;
        int x = this.width / 2 - total / 2;
        graphics.drawString(this.font, line1, x, layAccount, 0xFF80FF80, false);
        x += this.font.width(line1) + 8;
        graphics.drawString(this.font, line2, x, layAccount, 0xFFFFFFFF, false);
    }

    private void drawRight(GuiGraphics graphics, Component text, int y, int color) {
        graphics.drawString(this.font, text, this.width - this.font.width(text) - 6, y, color, false);
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "*****";
        String value = email.trim();
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return value.length() <= 2 ? "*****"
                    : value.substring(0, 1) + "***" + value.substring(value.length() - 1);
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() == 1) return local + "***" + domain;
        if (local.length() == 2) return local.substring(0, 1) + "***" + domain;
        return local.substring(0, Math.min(2, local.length() - 1))
                + "***" + local.substring(local.length() - 1) + domain;
    }

    private static String formatPlan(String plan) {
        if (plan == null || plan.isBlank()) return "ChatGPT";
        String normalized = plan.trim().replace('_', ' ').replace('-', ' ');
        StringBuilder title = new StringBuilder();
        for (String part : normalized.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) title.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return "ChatGPT " + title;
    }

    private static String providerLabel(String label, boolean selected) {
        return selected ? "[" + label + "]" : label;
    }

    private static boolean isOpenAiProvider(TranslatorConfig cfg) {
        return cfg != null && (cfg.usesCodex()
                || !cfg.usesLocalAiCli() && isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL));
    }

    private static boolean isGeminiProvider(TranslatorConfig cfg) {
        return cfg != null && (cfg.usesAntigravity()
                || !cfg.usesLocalAiCli() && isEndpoint(cfg.aiBaseUrl,
                "https://generativelanguage.googleapis.com/v1beta/openai"));
    }

    private static boolean isKnownApiEndpoint(String url) {
        return isEndpoint(url, "https://generativelanguage.googleapis.com/v1beta/openai")
                || isEndpoint(url, OPENAI_API_URL)
                || isEndpoint(url, "https://api.deepseek.com");
    }

    private static boolean isEndpoint(String left, String right) {
        return endpointKey(left).equalsIgnoreCase(endpointKey(right));
    }

    private static String keysForEndpoint(TranslatorConfig cfg, String url) {
        String stored = cfg.aiKeysByEndpoint.get(endpointKey(url));
        if (stored != null) return stored;
        return endpointKey(url).equals(endpointKey(cfg.aiBaseUrl))
                && cfg.aiApiKeys != null && !cfg.aiApiKeys.isEmpty()
                ? String.join(", ", cfg.aiApiKeys) : "";
    }

    private static String endpointKey(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }

    @Override
    public void onClose() {
        TranslatorConfig cfg = NyanLexNeoForge.config();
        String oldProvider = cfg.aiProvider;
        String oldUrl = cfg.aiBaseUrl;
        String oldModel = cfg.aiModel;
        String oldAntigravityModel = cfg.antigravityModel;
        List<String> oldKeys = cfg.aiApiKeys == null ? List.of() : List.copyOf(cfg.aiApiKeys);
        if (!cfg.usesLocalAiCli()) saveApiFields();
        NyanLexNeoForge.saveConfig();
        if (!java.util.Objects.equals(oldProvider, cfg.aiProvider)
                || !java.util.Objects.equals(oldUrl, cfg.aiBaseUrl)
                || !java.util.Objects.equals(oldModel, cfg.aiModel)
                || !java.util.Objects.equals(oldAntigravityModel, cfg.antigravityModel)
                || !oldKeys.equals(cfg.aiApiKeys)) {
            NeoTextStyle.clearRenderMemo();
        }
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }

    private static List<String> parseKeys(String raw) {
        List<String> keys = new ArrayList<>();
        if (raw != null) {
            for (String key : raw.split("[,\\n]")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) keys.add(trimmed);
            }
        }
        return keys;
    }

    private static String errorMessage(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
