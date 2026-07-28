package com.dragonmeow.mctranslator.neoforge26;

import com.dragonmeow.mctranslator.config.TranslatorConfig;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient.AccountSnapshot;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient.LoginStart;
import com.dragonmeow.mctranslator.translate.CodexAppServerClient.ModelOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Neo26AiScreen extends Screen {
    private static final String OPENAI_API_URL = "https://api.openai.com/v1";
    private static final String CODEX_DOWNLOAD_URL = "https://openai.com/codex/get-started/";
    private static final int FIELD_W = 320;

    private final Screen parent;
    private EditBox baseUrlBox;
    private EditBox modelBox;
    private EditBox keysBox;
    private Button loginButton;
    private Button modelButton;
    private Button effortButton;
    private Button refreshButton;
    private Button testButton;
    private boolean busy;
    private boolean initialCodexRefreshStarted;
    private boolean missingPromptShown;
    private String status = "";

    public Neo26AiScreen(Screen parent) {
        super(Component.translatable("screen.mctranslator.ai.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        int x = this.width / 2 - FIELD_W / 2;
        boolean openAiPanel = isOpenAiProvider(cfg);
        addProviderButtons(x, 34);
        if (openAiPanel) addOpenAiModeButtons(x, 60);
        if (cfg.aiUseCodex) {
            initCodex(x);
        } else {
            initApi(x, openAiPanel);
        }
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - 100, 210, 200, 20).build());

        if (cfg.aiUseCodex && !this.initialCodexRefreshStarted) {
            this.initialCodexRefreshStarted = true;
            refreshCodexSession(false);
        }
    }

    private void addProviderButtons(int x, int y) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        int gap = 6;
        int width = (FIELD_W - gap * 3) / 4;
        addProviderButton(providerLabel("Gemini", !cfg.aiUseCodex
                        && isEndpoint(cfg.aiBaseUrl, "https://generativelanguage.googleapis.com/v1beta/openai")),
                x, y, width, () -> selectApiProvider(
                        "https://generativelanguage.googleapis.com/v1beta/openai",
                        "gemini-3.1-flash-lite"));
        addProviderButton(providerLabel("OpenAI", isOpenAiProvider(cfg)),
                x + width + gap, y, width, this::selectOpenAiProvider);
        addProviderButton(providerLabel("DeepSeek", !cfg.aiUseCodex
                        && isEndpoint(cfg.aiBaseUrl, "https://api.deepseek.com")),
                x + (width + gap) * 2, y, width,
                () -> selectApiProvider("https://api.deepseek.com", "deepseek-chat"));
        addProviderButton(providerLabel(
                        Component.translatable("screen.mctranslator.ai.custom").getString(),
                        !cfg.aiUseCodex && !isKnownApiEndpoint(cfg.aiBaseUrl)),
                x + (width + gap) * 3, y, width,
                () -> selectApiProvider("http://127.0.0.1:11434/v1", ""));
    }

    private void addProviderButton(String label, int x, int y, int width, Runnable action) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, width, 20).build());
    }

    private void addOpenAiModeButtons(int x, int y) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        int gap = 6;
        int width = (FIELD_W - gap) / 2;
        addProviderButton(providerLabel(
                        Component.translatable("screen.mctranslator.ai.openai.api_mode").getString(),
                        !cfg.aiUseCodex),
                x, y, width, this::selectOpenAiApiMode);
        addProviderButton(providerLabel(
                        Component.translatable("screen.mctranslator.ai.openai.codex_mode").getString(),
                        cfg.aiUseCodex),
                x + width + gap, y, width, this::selectCodexProvider);
    }

    private void initApi(int x, boolean openAiPanel) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        int baseY = openAiPanel ? 94 : 70;
        int modelY = openAiPanel ? 126 : 112;
        int keysY = openAiPanel ? 158 : 154;
        int testY = openAiPanel ? 180 : 182;
        this.baseUrlBox = new EditBox(this.font, x, baseY, FIELD_W, 20, Component.literal("Base URL"));
        this.baseUrlBox.setMaxLength(256);
        this.baseUrlBox.setValue(cfg.aiBaseUrl == null ? "" : cfg.aiBaseUrl);
        this.addRenderableWidget(this.baseUrlBox);

        this.modelBox = new EditBox(this.font, x, modelY, FIELD_W, 20, Component.literal("Model"));
        this.modelBox.setMaxLength(128);
        this.modelBox.setValue(cfg.aiModel == null ? "" : cfg.aiModel);
        this.addRenderableWidget(this.modelBox);

        this.keysBox = new EditBox(this.font, x, keysY, FIELD_W, 20,
                Component.translatable("screen.mctranslator.ai.keys"));
        this.keysBox.setMaxLength(8000);
        this.keysBox.setValue(keysForEndpoint(cfg, cfg.aiBaseUrl));
        this.addRenderableWidget(this.keysBox);

        Button[] button = new Button[1];
        button[0] = Button.builder(Component.translatable("screen.mctranslator.ai.test"), b -> {
            saveApiFields();
            b.active = false;
            b.setMessage(Component.translatable("screen.mctranslator.ai.testing"));
            MctranslatorNeoForge26.testAi(
                    this.baseUrlBox.getValue().trim(),
                    this.modelBox.getValue().trim(),
                    parseKeys(this.keysBox.getValue()),
                    result -> {
                        if (button[0] != null) {
                            button[0].active = true;
                            button[0].setMessage(Component.literal(result));
                        }
                    });
        }).bounds(x, testY, FIELD_W, 20).build();
        this.testButton = button[0];
        this.addRenderableWidget(button[0]);
    }

    private void initCodex(int x) {
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        AccountSnapshot account = client == null ? AccountSnapshot.signedOut() : client.cachedAccount();
        boolean signedIn = account.signedIn();

        this.loginButton = Button.builder(
                Component.translatable(signedIn
                        ? "screen.mctranslator.ai.codex.logout"
                        : "screen.mctranslator.ai.codex.login"),
                b -> {
                    if (signedIn) logoutCodex();
                    else loginCodex();
                }).bounds(x, 86, FIELD_W, 20).build();
        this.loginButton.active = !this.busy;
        this.addRenderableWidget(this.loginButton);

        this.modelButton = Button.builder(modelLabel(), b -> openModelPicker())
                .bounds(x, 116, 252, 20).build();
        this.modelButton.active = signedIn && !this.busy && client != null && !client.cachedModels().isEmpty();
        this.addRenderableWidget(this.modelButton);

        this.refreshButton = Button.builder(
                Component.translatable("screen.mctranslator.ai.codex.refresh"),
                b -> refreshCodexSession(true)).bounds(x + 258, 116, 62, 20).build();
        this.refreshButton.active = signedIn && !this.busy && client != null;
        this.addRenderableWidget(this.refreshButton);

        this.effortButton = Button.builder(effortLabel(), b -> openEffortPicker())
                .bounds(x, 146, FIELD_W, 20).build();
        this.effortButton.active = signedIn && !this.busy
                && selectedModel().map(option -> !option.reasoningEfforts().isEmpty()).orElse(false);
        this.addRenderableWidget(this.effortButton);

        this.testButton = Button.builder(
                Component.translatable("screen.mctranslator.ai.test"),
                b -> testCodex()).bounds(x, 174, FIELD_W, 20).build();
        this.testButton.active = signedIn && !this.busy
                && MctranslatorNeoForge26.config().codexModel != null
                && !MctranslatorNeoForge26.config().codexModel.isBlank();
        this.addRenderableWidget(this.testButton);
    }

    private void selectOpenAiProvider() {
        if (isOpenAiProvider(MctranslatorNeoForge26.config())) {
            this.rebuildWidgets();
            return;
        }
        selectOpenAiApiMode();
    }

    private void selectOpenAiApiMode() {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        saveApiFields();
        boolean alreadyOpenAiApi = isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL);
        String openAiKeys = keysForEndpoint(cfg, OPENAI_API_URL);
        cfg.aiUseCodex = false;
        cfg.aiBaseUrl = OPENAI_API_URL;
        if (!alreadyOpenAiApi) cfg.aiModel = "gpt-5.4-mini";
        cfg.aiApiKeys = parseKeys(openAiKeys);
        MctranslatorNeoForge26.saveConfig();
        Neo26TextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void selectApiProvider(String url, String model) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        saveApiFields();
        String providerKeys = keysForEndpoint(cfg, url);
        cfg.aiUseCodex = false;
        cfg.aiBaseUrl = url;
        cfg.aiModel = model;
        cfg.aiApiKeys = parseKeys(providerKeys);
        MctranslatorNeoForge26.saveConfig();
        Neo26TextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void selectCodexProvider() {
        saveApiFields();
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        if (!isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL)) {
            String openAiKeys = keysForEndpoint(cfg, OPENAI_API_URL);
            cfg.aiBaseUrl = OPENAI_API_URL;
            cfg.aiModel = "gpt-5.4-mini";
            cfg.aiApiKeys = parseKeys(openAiKeys);
        }
        cfg.aiUseCodex = true;
        MctranslatorNeoForge26.saveConfig();
        Neo26TextStyle.clearRenderMemo();
        this.status = "";
        this.rebuildWidgets();
    }

    private void saveApiFields() {
        if (this.baseUrlBox == null || this.modelBox == null || this.keysBox == null) return;
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        String url = this.baseUrlBox.getValue().trim();
        cfg.aiBaseUrl = url;
        cfg.aiModel = this.modelBox.getValue().trim();
        cfg.aiApiKeys = parseKeys(this.keysBox.getValue());
        cfg.aiKeysByEndpoint.put(endpointKey(url), this.keysBox.getValue());
    }

    private void refreshCodexSession(boolean userInitiated) {
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        if (client == null) {
            setStatus(Component.translatable("message.mctranslator.not_initialized").getString(), false);
            return;
        }
        setBusy(true, Component.translatable("screen.mctranslator.ai.codex.refreshing").getString());
        runAsync("mctranslator-codex-refresh", () -> {
            if (!client.isInstalled()) {
                onMain(() -> {
                    setBusy(false, Component.translatable(
                            "screen.mctranslator.ai.codex.not_installed").getString());
                    showMissingCodexPrompt();
                });
                return;
            }
            try {
                AccountSnapshot account = client.readAccount(true);
                if (account.signedIn()) {
                    List<ModelOption> models = client.listModels();
                    normalizeCodexSelection(models);
                    setStatus(Component.translatable(
                            "screen.mctranslator.ai.codex.models_updated", models.size()).getString(), true);
                } else {
                    setStatus(Component.translatable(
                            "screen.mctranslator.ai.codex.signed_out").getString(), true);
                }
            } catch (Exception e) {
                setStatus(Component.translatable("message.mctranslator.failed",
                        errorMessage(e)).getString(), true);
                if (userInitiated && !client.isInstalled()) {
                    onMain(this::showMissingCodexPrompt);
                }
            }
        });
    }

    private void loginCodex() {
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        if (client == null) return;
        setBusy(true, Component.translatable("screen.mctranslator.ai.codex.starting_login").getString());
        runAsync("mctranslator-codex-login", () -> {
            if (!client.isInstalled()) {
                onMain(() -> {
                    setBusy(false, Component.translatable(
                            "screen.mctranslator.ai.codex.not_installed").getString());
                    showMissingCodexPrompt();
                });
                return;
            }
            try {
                LoginStart login = client.startLogin();
                onMain(() -> Util.getPlatform().openUri(login.authUrl()));
                setStatus(Component.translatable(
                        "screen.mctranslator.ai.codex.waiting_login").getString(), false);
                boolean success = client.awaitLogin(login.loginId(), Duration.ofMinutes(10));
                if (!success) {
                    setStatus(Component.translatable(
                            "screen.mctranslator.ai.codex.login_failed").getString(), true);
                    return;
                }
                List<ModelOption> models = client.listModels();
                normalizeCodexSelection(models);
                setStatus(Component.translatable(
                        "screen.mctranslator.ai.codex.login_success", models.size()).getString(), true);
            } catch (Exception e) {
                setStatus(Component.translatable("message.mctranslator.failed",
                        errorMessage(e)).getString(), true);
            }
        });
    }

    private void logoutCodex() {
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        if (client == null) return;
        setBusy(true, Component.translatable("screen.mctranslator.ai.codex.logging_out").getString());
        runAsync("mctranslator-codex-logout", () -> {
            try {
                client.logout();
                setStatus(Component.translatable(
                        "screen.mctranslator.ai.codex.logout_success").getString(), true);
            } catch (Exception e) {
                setStatus(Component.translatable("message.mctranslator.failed",
                        errorMessage(e)).getString(), true);
            }
        });
    }

    private void testCodex() {
        if (this.testButton != null) {
            this.testButton.active = false;
            this.testButton.setMessage(Component.translatable("screen.mctranslator.ai.testing"));
        }
        MctranslatorNeoForge26.testCodex(result -> {
            this.status = result;
            if (this.testButton != null) {
                this.testButton.active = true;
                this.testButton.setMessage(Component.translatable("screen.mctranslator.ai.test"));
            }
        });
    }

    private void openModelPicker() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new Neo26CodexModelScreen(this));
        }
    }

    private void openEffortPicker() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(new Neo26CodexEffortScreen(this));
        }
    }

    private void normalizeCodexSelection(List<ModelOption> models) {
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        if (models == null || models.isEmpty()) {
            cfg.codexModel = TranslatorConfig.DEFAULT_CODEX_MODEL;
            cfg.codexReasoningEffort = TranslatorConfig.DEFAULT_CODEX_REASONING_EFFORT;
            MctranslatorNeoForge26.saveConfig();
            return;
        }
        ModelOption selected = models.stream()
                .filter(option -> option.model().equals(cfg.codexModel))
                .findFirst()
                .orElseGet(() -> models.stream().filter(ModelOption::isDefault)
                        .findFirst().orElse(models.get(0)));
        cfg.codexModel = selected.model();
        normalizeEffort(cfg, selected);
        MctranslatorNeoForge26.saveConfig();
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
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        if (client == null) return java.util.Optional.empty();
        String selected = MctranslatorNeoForge26.config().codexModel;
        return client.cachedModels().stream()
                .filter(option -> option.model().equals(selected))
                .findFirst();
    }

    private Component modelLabel() {
        ModelOption selected = selectedModel().orElse(null);
        String name = selected == null
                ? Component.translatable("screen.mctranslator.ai.codex.no_models").getString()
                : selected.displayName();
        return Component.translatable("screen.mctranslator.ai.codex.model", name);
    }

    private Component effortLabel() {
        String effort = MctranslatorNeoForge26.config().codexReasoningEffort;
        if (effort == null || effort.isBlank()) {
            effort = Component.translatable(
                    "screen.mctranslator.ai.codex.effort_unavailable").getString();
        }
        return Component.translatable("screen.mctranslator.ai.codex.effort", effort);
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
        if (this.minecraft == null || this.missingPromptShown) return;
        this.missingPromptShown = true;
        ConfirmScreen confirm = new ConfirmScreen(confirmed -> {
            this.missingPromptShown = false;
            if (confirmed) Util.getPlatform().openUri(CODEX_DOWNLOAD_URL);
            if (this.minecraft != null) this.minecraft.setScreenAndShow(this);
        }, Component.translatable("screen.mctranslator.ai.codex.missing_title"),
                Component.translatable("screen.mctranslator.ai.codex.missing_message"),
                Component.translatable("screen.mctranslator.ai.codex.open_download"),
                Component.translatable("gui.cancel"));
        this.minecraft.setScreenAndShow(confirm);
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
        return this.minecraft != null && this.minecraft.gui.screen() == this;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        if (cfg.aiUseCodex) {
            drawCodexAccount(graphics);
            if (this.status.isBlank()) graphics.centeredText(this.font, Component.translatable(
                    "screen.mctranslator.ai.codex.independent_hint"), this.width / 2, 198, 0xFF909090);
        } else {
            int x = this.width / 2 - FIELD_W / 2;
            boolean openAiPanel = isOpenAiProvider(cfg);
            int endpointLabelY = openAiPanel ? 82 : 58;
            int modelLabelY = openAiPanel ? 114 : 100;
            int keysLabelY = openAiPanel ? 146 : 142;
            graphics.text(this.font, Component.translatable("screen.mctranslator.ai.endpoint"),
                    x, endpointLabelY, 0xFFA0A0A0, false);
            graphics.text(this.font, Component.translatable("screen.mctranslator.ai.model"),
                    x, modelLabelY, 0xFFA0A0A0, false);
            graphics.text(this.font, Component.translatable("screen.mctranslator.ai.keys"),
                    x, keysLabelY, 0xFFA0A0A0, false);
        }
        if (!this.status.isBlank()) {
            graphics.centeredText(this.font, Component.literal(this.status),
                    this.width / 2, 198, 0xFFFFD080);
        }
    }

    private void drawCodexAccount(GuiGraphicsExtractor graphics) {
        CodexAppServerClient client = MctranslatorNeoForge26.codexClient();
        AccountSnapshot account = client == null ? AccountSnapshot.signedOut() : client.cachedAccount();
        if (!account.signedIn()) {
            Component line = Component.translatable("screen.mctranslator.ai.codex.signed_out");
            graphics.text(this.font, line, this.width - this.font.width(line) - 6,
                    7, 0xFF909090, false);
            return;
        }
        Component line1 = Component.translatable("screen.mctranslator.ai.codex.signed_in");
        Component line2 = Component.literal(maskEmail(account.email()));
        Component line3 = Component.literal(formatPlan(account.planType()));
        drawRight(graphics, line1, 5, 0xFF80FF80);
        drawRight(graphics, line2, 16, 0xFFFFFFFF);
        drawRight(graphics, line3, 27, 0xFFA0A0A0);
    }

    private void drawRight(GuiGraphicsExtractor graphics, Component text, int y, int color) {
        graphics.text(this.font, text, this.width - this.font.width(text) - 6, y, color, false);
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
        return cfg != null && (cfg.aiUseCodex || isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL));
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
        TranslatorConfig cfg = MctranslatorNeoForge26.config();
        boolean wasCodex = cfg.aiUseCodex;
        String oldUrl = cfg.aiBaseUrl;
        String oldModel = cfg.aiModel;
        List<String> oldKeys = cfg.aiApiKeys == null ? List.of() : List.copyOf(cfg.aiApiKeys);
        saveApiFields();
        MctranslatorNeoForge26.saveConfig();
        if (wasCodex != cfg.aiUseCodex
                || !java.util.Objects.equals(oldUrl, cfg.aiBaseUrl)
                || !java.util.Objects.equals(oldModel, cfg.aiModel)
                || !oldKeys.equals(cfg.aiApiKeys)) {
            Neo26TextStyle.clearRenderMemo();
        }
        if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent);
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
