package com.dragonmeow.nyanlex.legacy;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full API-key and ChatGPT/Codex configuration screen for the Java 8 client UI. */
final class LegacyAiConfigScreen extends Screen {
    private static final String OPENAI_API_URL = "https://api.openai.com/v1";
    private static final String CODEX_DOWNLOAD_URL = "https://openai.com/codex/get-started/";
    private static final int FIELD_W = 320;

    private final Screen parent;
    private EditBox baseUrlBox;
    private EditBox modelBox;
    private EditBox keysBox;
    private Button testButton;
    private boolean busy;
    private boolean initialRefreshStarted;
    private boolean missingPromptShown;
    private String status = "";

    LegacyAiConfigScreen(Screen parent) {
        super(new TranslatableComponent("screen.nyanlex.ai.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int x = width / 2 - FIELD_W / 2;
        boolean openAiPanel = isOpenAiProvider(cfg);
        addProviderButtons(x, 28);
        if (openAiPanel) addOpenAiModeButtons(x, 52);
        if (cfg.aiUseCodex) initCodex(x);
        else initApi(x, openAiPanel);
        addButton(new Button(width / 2 - 100, height - 26, 200, 20,
                new TranslatableComponent("gui.done").getString(), button -> onClose()));
        if (cfg.aiUseCodex && !initialRefreshStarted) {
            initialRefreshStarted = true;
            refreshCodex(false);
        }
    }

    private void addProviderButtons(int x, int y) {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int gap = 4, w = (FIELD_W - gap * 3) / 4;
        addProviderButton(providerLabel("Gemini", !cfg.aiUseCodex && isEndpoint(cfg.aiBaseUrl,
                "https://generativelanguage.googleapis.com/v1beta/openai")), x, y, w,
                () -> selectApi("https://generativelanguage.googleapis.com/v1beta/openai",
                        "gemini-3.1-flash-lite"));
        addProviderButton(providerLabel("OpenAI", isOpenAiProvider(cfg)), x + w + gap, y, w,
                this::selectOpenAi);
        addProviderButton(providerLabel("DeepSeek", !cfg.aiUseCodex
                        && isEndpoint(cfg.aiBaseUrl, "https://api.deepseek.com")),
                x + (w + gap) * 2, y, w,
                () -> selectApi("https://api.deepseek.com", "deepseek-chat"));
        addProviderButton(providerLabel(new TranslatableComponent("screen.nyanlex.ai.custom").getString(),
                        !cfg.aiUseCodex && !isKnownEndpoint(cfg.aiBaseUrl)),
                x + (w + gap) * 3, y, w,
                () -> selectApi("http://127.0.0.1:11434/v1", ""));
    }

    private void addProviderButton(String label, int x, int y, int w, final Runnable action) {
        addButton(new Button(x, y, w, 20, label, button -> action.run()));
    }

    private void addOpenAiModeButtons(int x, int y) {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int gap = 4, w = (FIELD_W - gap) / 2;
        addProviderButton(providerLabel(new TranslatableComponent(
                "screen.nyanlex.ai.openai.api_mode").getString(), !cfg.aiUseCodex),
                x, y, w, this::selectOpenAiApi);
        addProviderButton(providerLabel(new TranslatableComponent(
                "screen.nyanlex.ai.openai.codex_mode").getString(), cfg.aiUseCodex),
                x + w + gap, y, w, this::selectCodex);
    }

    private void initApi(int x, boolean openAiPanel) {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int baseY = openAiPanel ? 82 : 58;
        int modelY = openAiPanel ? 116 : 96;
        int keysY = openAiPanel ? 150 : 134;
        int testY = openAiPanel ? 176 : 172;
        baseUrlBox = new EditBox(font, x, baseY, FIELD_W, 20, "Base URL");
        baseUrlBox.setMaxLength(256);
        baseUrlBox.setValue(safe(cfg.aiBaseUrl));
        addButton(baseUrlBox);
        modelBox = new EditBox(font, x, modelY, FIELD_W, 20, "Model");
        modelBox.setMaxLength(128);
        modelBox.setValue(safe(cfg.aiModel));
        addButton(modelBox);
        keysBox = new EditBox(font, x, keysY, FIELD_W, 20,
                new TranslatableComponent("screen.nyanlex.ai.keys").getString());
        keysBox.setMaxLength(8000);
        keysBox.setValue(keysForEndpoint(cfg, cfg.aiBaseUrl));
        addButton(keysBox);
        testButton = new Button(x, testY, FIELD_W, 20,
                new TranslatableComponent("screen.nyanlex.ai.test").getString(), button -> {
            saveApiFields();
            button.active = false;
            button.setMessage(new TranslatableComponent("screen.nyanlex.ai.testing").getString());
            LegacyTranslatorMod.testAi(result -> {
                status = result;
                button.active = true;
                button.setMessage(new TranslatableComponent("screen.nyanlex.ai.test").getString());
            });
        });
        addButton(testButton);
    }

    private void initCodex(int x) {
        LegacyCodexClient client = LegacyTranslatorMod.codexClient();
        LegacyCodexClient.AccountSnapshot account = client == null
                ? LegacyCodexClient.AccountSnapshot.signedOut() : client.cachedAccount();
        final boolean signedIn = account.signedIn();
        Button login = new Button(x, 78, FIELD_W, 20,
                new TranslatableComponent(signedIn ? "screen.nyanlex.ai.codex.logout"
                        : "screen.nyanlex.ai.codex.login").getString(),
                button -> { if (signedIn) logoutCodex(); else loginCodex(); });
        login.active = !busy;
        addButton(login);
        Button model = new Button(x, 104, 252, 20, modelLabel(),
                button -> minecraft.setScreen(new LegacyCodexModelScreen(this)));
        model.active = signedIn && !busy && client != null && !client.cachedModels().isEmpty();
        addButton(model);
        Button refresh = new Button(x + 258, 104, 62, 20,
                new TranslatableComponent("screen.nyanlex.ai.codex.refresh").getString(),
                button -> refreshCodex(true));
        refresh.active = signedIn && !busy && client != null;
        addButton(refresh);
        Button effort = new Button(x, 130, FIELD_W, 20, effortLabel(),
                button -> minecraft.setScreen(new LegacyCodexEffortScreen(this)));
        effort.active = signedIn && !busy && selectedModel() != null
                && !selectedModel().reasoningEfforts().isEmpty();
        addButton(effort);
        testButton = new Button(x, 156, FIELD_W, 20,
                new TranslatableComponent("screen.nyanlex.ai.test").getString(),
                button -> testCodex());
        testButton.active = signedIn && !busy && !safe(LegacyTranslatorMod.config().codexModel).isEmpty();
        addButton(testButton);
    }

    private void selectOpenAi() {
        if (isOpenAiProvider(LegacyTranslatorMod.config())) rebuild();
        else selectOpenAiApi();
    }

    private void selectOpenAiApi() {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        saveApiFields();
        String keys = keysForEndpoint(cfg, OPENAI_API_URL);
        boolean same = isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL);
        cfg.aiUseCodex = false;
        cfg.aiBaseUrl = OPENAI_API_URL;
        if (!same) cfg.aiModel = "gpt-5.4-mini";
        cfg.aiApiKeys = parseKeys(keys);
        saveAndRebuild();
    }

    private void selectCodex() {
        saveApiFields();
        LegacyConfig cfg = LegacyTranslatorMod.config();
        if (!isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL)) {
            cfg.aiBaseUrl = OPENAI_API_URL;
            cfg.aiModel = "gpt-5.4-mini";
            cfg.aiApiKeys = parseKeys(keysForEndpoint(cfg, OPENAI_API_URL));
        }
        cfg.aiUseCodex = true;
        saveAndRebuild();
    }

    private void selectApi(String url, String model) {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        saveApiFields();
        String keys = keysForEndpoint(cfg, url);
        cfg.aiUseCodex = false;
        cfg.aiBaseUrl = url;
        cfg.aiModel = model;
        cfg.aiApiKeys = parseKeys(keys);
        saveAndRebuild();
    }

    private void saveApiFields() {
        if (baseUrlBox == null || modelBox == null || keysBox == null) return;
        LegacyConfig cfg = LegacyTranslatorMod.config();
        cfg.aiBaseUrl = baseUrlBox.getValue().trim();
        cfg.aiModel = modelBox.getValue().trim();
        cfg.aiApiKeys = parseKeys(keysBox.getValue());
        cfg.aiKeysByEndpoint.put(endpointKey(cfg.aiBaseUrl), keysBox.getValue());
    }

    private void saveAndRebuild() {
        LegacyTranslatorMod.saveConfig();
        status = "";
        rebuild();
    }

    private void refreshCodex(final boolean userInitiated) {
        final LegacyCodexClient client = LegacyTranslatorMod.codexClient();
        if (client == null) { setStatus("Not initialized", false); return; }
        setBusy(true, new TranslatableComponent("screen.nyanlex.ai.codex.refreshing").getString());
        runAsync("nyanlex-codex-refresh", new Runnable() {
            @Override public void run() {
                if (!client.isInstalled()) {
                    setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.not_installed").getString(), true);
                    if (userInitiated) onMain(() -> showMissingPrompt());
                    return;
                }
                try {
                    LegacyCodexClient.AccountSnapshot account = client.readAccount(true);
                    if (account.signedIn()) {
                        List<LegacyCodexClient.ModelOption> models = client.listModels();
                        normalizeSelection(models);
                        setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.models_updated",
                                models.size()).getString(), true);
                    } else setStatus(new TranslatableComponent(
                            "screen.nyanlex.ai.codex.signed_out").getString(), true);
                } catch (Exception e) { setStatus("Failed: " + error(e), true); }
            }
        });
    }

    private void loginCodex() {
        final LegacyCodexClient client = LegacyTranslatorMod.codexClient();
        if (client == null) return;
        setBusy(true, new TranslatableComponent("screen.nyanlex.ai.codex.starting_login").getString());
        runAsync("nyanlex-codex-login", new Runnable() {
            @Override public void run() {
                if (!client.isInstalled()) {
                    setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.not_installed").getString(), true);
                    onMain(() -> showMissingPrompt());
                    return;
                }
                try {
                    LegacyCodexClient.LoginStart login = client.startLogin();
                    onMain(() -> Util.getPlatform().openUri(login.authUrl()));
                    setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.waiting_login").getString(), false);
                    if (!client.awaitLogin(login.loginId(), 600000L)) {
                        setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.login_failed").getString(), true);
                        return;
                    }
                    List<LegacyCodexClient.ModelOption> models = client.listModels();
                    normalizeSelection(models);
                    setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.login_success",
                            models.size()).getString(), true);
                } catch (Exception e) { setStatus("Failed: " + error(e), true); }
            }
        });
    }

    private void logoutCodex() {
        final LegacyCodexClient client = LegacyTranslatorMod.codexClient();
        if (client == null) return;
        setBusy(true, new TranslatableComponent("screen.nyanlex.ai.codex.logging_out").getString());
        runAsync("nyanlex-codex-logout", () -> {
            try {
                client.logout();
                setStatus(new TranslatableComponent("screen.nyanlex.ai.codex.logout_success").getString(), true);
            } catch (Exception e) { setStatus("Failed: " + error(e), true); }
        });
    }

    private void testCodex() {
        if (testButton != null) {
            testButton.active = false;
            testButton.setMessage(new TranslatableComponent("screen.nyanlex.ai.testing").getString());
        }
        LegacyTranslatorMod.testAi(result -> {
            status = result;
            if (testButton != null) {
                testButton.active = true;
                testButton.setMessage(new TranslatableComponent("screen.nyanlex.ai.test").getString());
            }
        });
    }

    private void normalizeSelection(List<LegacyCodexClient.ModelOption> models) {
        LegacyConfig cfg = LegacyTranslatorMod.config();
        if (models == null || models.isEmpty()) {
            cfg.codexModel = LegacyConfig.DEFAULT_CODEX_MODEL;
            cfg.codexReasoningEffort = LegacyConfig.DEFAULT_CODEX_REASONING_EFFORT;
            LegacyTranslatorMod.saveConfig();
            return;
        }
        LegacyCodexClient.ModelOption selected = null;
        for (LegacyCodexClient.ModelOption option : models) {
            if (option.model().equals(cfg.codexModel)) { selected = option; break; }
        }
        if (selected == null) for (LegacyCodexClient.ModelOption option : models) {
            if (option.isDefault()) { selected = option; break; }
        }
        if (selected == null) selected = models.get(0);
        cfg.codexModel = selected.model();
        normalizeEffort(cfg, selected);
        LegacyTranslatorMod.saveConfig();
    }

    static void normalizeEffort(LegacyConfig cfg, LegacyCodexClient.ModelOption selected) {
        List<String> efforts = selected.reasoningEfforts();
        if (efforts.isEmpty()) { cfg.codexReasoningEffort = ""; return; }
        if (efforts.contains(cfg.codexReasoningEffort)) return;
        String preferred = selected.defaultReasoningEffort();
        cfg.codexReasoningEffort = preferred != null && efforts.contains(preferred)
                ? preferred : efforts.get(0);
    }

    private LegacyCodexClient.ModelOption selectedModel() {
        LegacyCodexClient client = LegacyTranslatorMod.codexClient();
        if (client == null) return null;
        for (LegacyCodexClient.ModelOption option : client.cachedModels())
            if (option.model().equals(LegacyTranslatorMod.config().codexModel)) return option;
        return null;
    }

    private String modelLabel() {
        LegacyCodexClient.ModelOption selected = selectedModel();
        String name = selected == null
                ? new TranslatableComponent("screen.nyanlex.ai.codex.no_models").getString()
                : selected.displayName();
        return new TranslatableComponent("screen.nyanlex.ai.codex.model", name).getString();
    }

    private String effortLabel() {
        String effort = safe(LegacyTranslatorMod.config().codexReasoningEffort);
        if (effort.isEmpty()) effort = new TranslatableComponent(
                "screen.nyanlex.ai.codex.effort_unavailable").getString();
        return new TranslatableComponent("screen.nyanlex.ai.codex.effort", effort).getString();
    }

    private void setBusy(final boolean value, final String message) {
        onMain(() -> { busy = value; status = safe(message); if (minecraft != null && minecraft.screen == this) rebuild(); });
    }

    private void setStatus(final String message, final boolean finishBusy) {
        onMain(() -> { if (finishBusy) busy = false; status = safe(message);
            if (minecraft != null && minecraft.screen == this) rebuild(); });
    }

    private void showMissingPrompt() {
        if (minecraft == null || missingPromptShown) return;
        missingPromptShown = true;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            missingPromptShown = false;
            if (confirmed) Util.getPlatform().openUri(CODEX_DOWNLOAD_URL);
            minecraft.setScreen(this);
        }, new TranslatableComponent("screen.nyanlex.ai.codex.missing_title"),
                new TranslatableComponent("screen.nyanlex.ai.codex.missing_message"),
                new TranslatableComponent("screen.nyanlex.ai.codex.open_download").getString(),
                new TranslatableComponent("gui.cancel").getString()));
    }

    private void rebuild() { if (minecraft != null) init(minecraft, width, height); }
    private void onMain(Runnable task) { Minecraft client = Minecraft.getInstance(); if (client != null) client.execute(task); else task.run(); }
    private static void runAsync(String name, Runnable task) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }

    @Override public void render(int mouseX, int mouseY, float delta) {
        renderBackground();
        font.drawShadow(title.getString(), width / 2f - font.width(title.getString()) / 2f, 8, 0xFFFFFF);
        LegacyConfig cfg = LegacyTranslatorMod.config();
        int x = width / 2 - FIELD_W / 2;
        if (!cfg.aiUseCodex) {
            boolean open = isOpenAiProvider(cfg);
            font.drawShadow(new TranslatableComponent("screen.nyanlex.ai.endpoint").getString(), x, open ? 72 : 48, 0xA0A0A0);
            font.drawShadow(new TranslatableComponent("screen.nyanlex.ai.model").getString(), x, open ? 106 : 86, 0xA0A0A0);
            font.drawShadow(new TranslatableComponent("screen.nyanlex.ai.keys").getString(), x, open ? 140 : 124, 0xA0A0A0);
        } else {
            LegacyCodexClient client = LegacyTranslatorMod.codexClient();
            LegacyCodexClient.AccountSnapshot account = client == null
                    ? LegacyCodexClient.AccountSnapshot.signedOut() : client.cachedAccount();
            String accountText = account.signedIn()
                    ? new TranslatableComponent("screen.nyanlex.ai.codex.signed_in").getString()
                            + (account.email() == null ? "" : ": " + account.email())
                    : new TranslatableComponent("screen.nyanlex.ai.codex.signed_out").getString();
            font.drawShadow(accountText, width / 2f - font.width(accountText) / 2f, 182, 0xA0A0A0);
        }
        if (!status.isEmpty()) font.drawShadow(status, width / 2f - font.width(status) / 2f, 196, 0xFFD080);
        super.render(mouseX, mouseY, delta);
    }

    @Override public void tick() {
        if (baseUrlBox != null) baseUrlBox.tick();
        if (modelBox != null) modelBox.tick();
        if (keysBox != null) keysBox.tick();
    }

    @Override public void onClose() { saveApiFields(); LegacyTranslatorMod.saveConfig(); minecraft.setScreen(parent); }

    private static String providerLabel(String name, boolean active) { return active ? "[" + name + "]" : name; }
    private static boolean isEndpoint(String left, String right) { return endpointKey(left).equals(endpointKey(right)); }
    private static boolean isOpenAiProvider(LegacyConfig cfg) { return cfg.aiUseCodex || isEndpoint(cfg.aiBaseUrl, OPENAI_API_URL); }
    private static boolean isKnownEndpoint(String url) {
        return isEndpoint(url, OPENAI_API_URL)
                || isEndpoint(url, "https://generativelanguage.googleapis.com/v1beta/openai")
                || isEndpoint(url, "https://api.deepseek.com");
    }
    private static String endpointKey(String url) {
        String value = safe(url).trim().toLowerCase(Locale.ROOT);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
    private static String keysForEndpoint(LegacyConfig cfg, String url) {
        String saved = cfg.aiKeysByEndpoint.get(endpointKey(url));
        if (saved != null) return saved;
        StringBuilder out = new StringBuilder();
        for (String key : cfg.aiApiKeys) { if (key == null || key.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(", "); out.append(key.trim()); }
        return out.toString();
    }
    private static List<String> parseKeys(String raw) {
        List<String> keys = new ArrayList<String>();
        for (String value : safe(raw).split("[,;\\r\\n]+")) if (!value.trim().isEmpty()) keys.add(value.trim());
        return keys;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String error(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "unknown" : message;
    }
}