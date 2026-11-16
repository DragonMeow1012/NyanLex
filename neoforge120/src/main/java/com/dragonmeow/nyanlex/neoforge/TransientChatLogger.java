package com.dragonmeow.nyanlex.neoforge;

import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.OverlayDialogue;
import com.dragonmeow.nyanlex.translate.OverlayDialogueSession;
import com.dragonmeow.nyanlex.translate.TransientChatLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Copies the translation of a title, subtitle or dialogue page into the player's own chat
 * (the "標題與對話留在聊天欄" switch). Everything stays local: the line is added with
 * {@code ChatComponent.addMessage}, which never reaches the server and does not pass through the
 * incoming-message events, so it is neither translated again nor recorded again here. The
 * rules of what is recorded live in {@link TransientChatLog}.
 *
 * <p>With the switch off every entry point is one boolean read.</p>
 */
public final class TransientChatLogger {

    /** A title shown before its translation was ready: checked again until it arrives or is too old. */
    private record Pending(TransientChatLog.Kind kind, Component original, long since) {
    }

    private static final long PENDING_MAX_MS = 120_000L;
    private static final int POLL_EVERY_TICKS = 10;
    private static final int PENDING_MAX = 4;

    private static final TransientChatLog LOG = new TransientChatLog();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static boolean tickRegistered;
    private static int tickCount;

    private TransientChatLogger() {
    }

    /** The switch (cheap: callers check it before doing any work). */
    public static boolean on() {
        TranslatorConfig config = NyanLexNeoForge.config();
        return config != null && config.logTransientToChat;
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    // ---------------------------------------------------------------- titles

    /**
     * Called every frame a title or subtitle is drawn.
     *
     * @param translated whether a translation was available for this frame
     */
    public static void title(boolean subtitle, Component original, boolean translated) {
        if (original == null || !on()) {
            return;
        }
        TranslationService service = NyanLexNeoForge.service();
        if (service == null || !TransientChatLog.enabled(true, service.titleMode() == DisplayMode.ORIGINAL_ONLY)) {
            return;
        }
        TransientChatLog.Kind kind = subtitle ? TransientChatLog.Kind.SUBTITLE : TransientChatLog.Kind.TITLE;
        if (translated) {
            settle(kind, original, service);
        } else {
            remember(kind, original);
        }
    }

    /** @return whether the title is settled (recorded, or a repeat of one that was) */
    private static boolean settle(TransientChatLog.Kind kind, Component original, TranslationService service) {
        String plain = original.getString();
        if (!LOG.acceptTitle(kind, plain, nowMs())) {
            forget(kind, plain);
            return true;
        }
        // "tooltip" asks for the translation alone, even when the surface shows original and translation
        Component translation = NeoTextStyle.renderTranslated("tooltip", original, service::peekTitle);
        if (translation == null) {
            LOG.forgetTitle(kind, plain);
            return false;
        }
        addLine(Component.translatable(kind == TransientChatLog.Kind.SUBTITLE
                ? "message.nyanlex.log.subtitle" : "message.nyanlex.log.title"), translation);
        forget(kind, plain);
        return true;
    }

    private static void remember(TransientChatLog.Kind kind, Component original) {
        String plain = original.getString();
        for (Pending pending : PENDING) {
            if (pending.kind() == kind && pending.original().getString().equals(plain)) {
                return;
            }
        }
        if (PENDING.size() >= PENDING_MAX) {
            PENDING.remove(0);
        }
        PENDING.add(new Pending(kind, original, nowMs()));
        if (!tickRegistered) {
            tickRegistered = true;
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class,
                    event -> {
                        if (event.phase == TickEvent.Phase.END) poll();
                    });
        }
    }

    private static void forget(TransientChatLog.Kind kind, String plain) {
        if (PENDING.isEmpty()) {
            return;
        }
        PENDING.removeIf(p -> p.kind() == kind && p.original().getString().equals(plain));
    }

    /** Titles whose translation arrived after they were gone. */
    private static void poll() {
        if (PENDING.isEmpty() || ++tickCount % POLL_EVERY_TICKS != 0) {
            return;
        }
        try {
            TranslationService service = NyanLexNeoForge.service();
            if (service == null || !on() || service.titleMode() == DisplayMode.ORIGINAL_ONLY) {
                PENDING.clear();
                return;
            }
            long now = nowMs();
            for (Iterator<Pending> it = new ArrayList<>(PENDING).iterator(); it.hasNext(); ) {
                Pending pending = it.next();
                if (now - pending.since() > PENDING_MAX_MS) {
                    PENDING.remove(pending);
                } else if (NeoTextStyle.renderTranslated("tooltip", pending.original(), service::peekTitle) != null) {
                    settle(pending.kind(), pending.original(), service);
                    PENDING.remove(pending);
                }
            }
        } catch (RuntimeException failure) {
            PENDING.clear();
        }
    }

    // ---------------------------------------------------------------- dialogue

    /** Whether dialogue pages are recorded right now (switch on, the action bar shows translations). */
    public static boolean dialogueOn(TranslationService service) {
        return on() && service != null
                && TransientChatLog.enabled(true, service.actionBarMode() == DisplayMode.ORIGINAL_ONLY);
    }

    /**
     * Records a dialogue page whose spoken text has a translation. {@code styles} is the style
     * table the segments index into (colours are kept; the dialogue fonts are already gone).
     */
    public static void dialogue(OverlayDialogueSession.Spoken spoken, List<Style> styles) {
        if (!LOG.acceptDialogue(spoken.speakerOriginal(), spoken.bodyOriginal())) {
            return;
        }
        MutableComponent body = Component.empty();
        for (OverlayDialogue.Segment segment : spoken.body()) {
            int index = segment.styleSource();
            Style style = index >= 0 && index < styles.size() ? styles.get(index) : Style.EMPTY;
            body.append(Component.literal(segment.text()).setStyle(style));
        }
        if (body.getString().isBlank()) {
            LOG.forgetDialogue(spoken.speakerOriginal(), spoken.bodyOriginal());
            return;
        }
        String speaker = TransientChatLog.speakerPrefix(spoken.speakerTranslated(), spoken.speakerOriginal());
        addLine(speaker.isEmpty() ? null : Component.literal(speaker), body);
    }

    // ---------------------------------------------------------------- the chat line

    /** {@code prefix + " " + text}, the prefix in a dimmer colour. */
    static MutableComponent compose(Component prefix, Component text) {
        MutableComponent line = Component.empty();
        if (prefix != null) {
            line.append(prefix.copy().withStyle(ChatFormatting.GRAY));
            line.append(Component.literal(" "));
        }
        line.append(text);
        return line;
    }

    /** Adds the line to the player's own chat; local only. */
    private static void addLine(Component prefix, Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        MutableComponent line = compose(prefix, text);
        mc.gui.getChat().addMessage(line);
    }
}
