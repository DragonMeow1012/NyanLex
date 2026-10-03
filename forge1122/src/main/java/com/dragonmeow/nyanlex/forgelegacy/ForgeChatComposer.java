package com.dragonmeow.nyanlex.forgelegacy;

import com.dragonmeow.nyanlex.translate.HookGuard;
import com.dragonmeow.nyanlex.translate.ChatComposerPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Forge's GUI events attach the composer to vanilla chat without replacing its screen. */
public final class ForgeChatComposer {
    private GuiScreen screen;
    private GuiTextField chat, draft;
    private ChatComposerPanel panel;
    private boolean enterHeld;
    private final Minecraft mc = Minecraft.getMinecraft();

    private boolean active(GuiScreen gui) { return panel != null && gui == screen && mc.currentScreen == screen; }

    @SubscribeEvent public void init(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!HookGuard.enter("event.init")) return;
        try {
            if (!(event.getGui() instanceof GuiChat) || !NyanLexForge.config().chatComposerEnabled) return;
            String previous = screen == event.getGui() && draft != null ? draft.getText() : "";
            if (panel != null) panel.close();
            panel = null;
            screen = event.getGui();
            chat = chatField((GuiChat) screen);
            if (chat == null || chat.getText().startsWith("/")) return;
            final java.util.List<String[]> languages = new java.util.ArrayList<String[]>();
            languages.add(new String[]{"en", "English"});
            for (net.minecraft.client.resources.Language lang : mc.getLanguageManager().getLanguages())
                languages.add(new String[]{lang.getLanguageCode().replace('_', '-'), lang.toString()});
            final String[][] rows = languages.toArray(new String[0][]);
            panel = new ChatComposerPanel(new ChatComposerPanel.Host() {
                public String text(String key) { return I18n.format(key); }
                public int textWidth(String text) { return mc.fontRenderer.getStringWidth(text); }
                public String draft() { return draft.getText(); }
                public String chat() { return chat.getText(); }
                public String language() { return NyanLexForge.config().chatComposerLanguage; }
                public void language(String tag) { NyanLexForge.config().chatComposerLanguage = tag; NyanLexForge.save(); }
                public String[][] languages() { return rows; }
                public double positionX() { return NyanLexForge.config().chatComposerX; }
                public double positionY() { return NyanLexForge.config().chatComposerY; }
                public void position(double x, double y) {
                    NyanLexForge.config().chatComposerX = x; NyanLexForge.config().chatComposerY = y; NyanLexForge.save();
                }
                public void fill(String text) { chat.setText(text); draft.setFocused(false); chat.setFocused(true); }
                public boolean current() { return mc.currentScreen == screen; }
                public void execute(Runnable action) { mc.addScheduledTask(action); }
                public void translate(String source, String target, java.util.function.BiConsumer<String, String> callback) {
                    java.util.List<String> names = new java.util.ArrayList<String>();
                if (mc.getConnection() != null) for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap()) {
                    if (info != null && info.getGameProfile() != null) names.add(info.getGameProfile().getName());
                }
                NyanLexForge.TRANSLATOR.translateDraft(source, target, NyanLexForge.config(), names, callback);
                }
            });
            panel.resize(screen.width, screen.height);
            draft = new GuiTextField(9108, mc.fontRenderer, panel.inputX(), panel.inputY(), panel.inputWidth(), 20);
            draft.setMaxStringLength(256);
            draft.setText(previous);
            chat.setFocused(false);
            draft.setFocused(true);
            enterHeld = false;
        } catch (Throwable guardError) {
            HookGuard.fail("event.init", guardError);
        }
    }

    /** One mapped field lookup on screen initialization; both names come from Forge's MCP mappings. */
    private static GuiTextField chatField(GuiChat gui) {
        for (String name : new String[]{"inputField", "field_146415_a"}) {
            try {
                java.lang.reflect.Field field = GuiChat.class.getDeclaredField(name);
                field.setAccessible(true);
                return (GuiTextField) field.get(gui);
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    @SubscribeEvent public void closed(net.minecraftforge.client.event.GuiOpenEvent event) {
        if (!HookGuard.enter("event.closed")) return;
        try {
            if (panel != null && event.getGui() != screen) { panel.close(); panel = null; screen = null; }
        } catch (Throwable guardError) {
            HookGuard.fail("event.closed", guardError);
        }
    }

    @SubscribeEvent public void draw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!HookGuard.enter("event.draw")) return;
        try {
            if (!active(event.getGui())) return;
            ChatComposerPanel.Canvas canvas = new ChatComposerPanel.Canvas() {
                public void fill(int x, int y, int w, int h, int color) { net.minecraft.client.gui.Gui.drawRect(x, y, x + w, y + h, color); }
                public void text(String text, int x, int y, int color) { mc.fontRenderer.drawString(text, x, y, color); }
            };
            if (org.lwjgl.input.Mouse.isButtonDown(0)) panel.drag(event.getMouseX(), event.getMouseY()); else panel.release();
            panel.render(canvas);
            draft.x = panel.inputX(); draft.y = panel.inputY();
            draft.drawTextBox();
            panel.renderChoices(canvas);
        } catch (Throwable guardError) {
            HookGuard.fail("event.draw", guardError);
        }
    }

    private boolean click(double x, double y, int button) {
        if (panel.choosing()) { panel.click(x, y, button); return true; }
        if (!panel.contains(x, y)) { draft.setFocused(false); chat.setFocused(true); return false; }
        if (y >= panel.inputY() && y < panel.inputY() + 20) {
            chat.setFocused(false); draft.setFocused(true); draft.mouseClicked((int) x, (int) y, button);
        } else panel.click(x, y, button);
        return true;
    }
    @SubscribeEvent public void key(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!HookGuard.enter("event.key")) return;
        try {
            if (!active(event.getGui())) return;
            int code = org.lwjgl.input.Keyboard.getEventKey();
            boolean enter = code == 28 || code == 156;
            if (!org.lwjgl.input.Keyboard.getEventKeyState()) { if (enter) enterHeld = false; return; }
            if (enter && enterHeld) { event.setCanceled(true); return; }
            if (!draft.isFocused() || code == 1) return;
            if (enter) { enterHeld = true; panel.submit(); }
            else if (code == 15) { draft.setFocused(false); chat.setFocused(true); }
            else draft.textboxKeyTyped(org.lwjgl.input.Keyboard.getEventCharacter(), code);
            panel.observe(); event.setCanceled(true);
        } catch (Throwable guardError) {
            HookGuard.fail("event.key", guardError);
        }
    }
    @SubscribeEvent public void mouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!HookGuard.enter("event.mouse")) return;
        try {
            if (!active(event.getGui())) return;
            int x = org.lwjgl.input.Mouse.getEventX() * screen.width / mc.displayWidth;
            int y = screen.height - org.lwjgl.input.Mouse.getEventY() * screen.height / mc.displayHeight - 1;
            int button = org.lwjgl.input.Mouse.getEventButton();
            int wheel = org.lwjgl.input.Mouse.getEventDWheel();
            if (wheel != 0 && panel.scroll(wheel)) { event.setCanceled(true); return; }
            if (button >= 0 && org.lwjgl.input.Mouse.getEventButtonState() && click(x, y, button)) event.setCanceled(true);
        } catch (Throwable guardError) {
            HookGuard.fail("event.mouse", guardError);
        }
    }
}
