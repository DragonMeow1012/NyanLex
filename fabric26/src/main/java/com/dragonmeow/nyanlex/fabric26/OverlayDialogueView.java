package com.dragonmeow.nyanlex.fabric26;

import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.service.TranslationService;
import com.dragonmeow.nyanlex.translate.OverlayDialogue;
import com.dragonmeow.nyanlex.translate.OverlayDialogueFonts;
import com.dragonmeow.nyanlex.translate.OverlayDialogueSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Draw-time view of an overlay dialogue box (Minecraft 26.1 glue around the Minecraft-free
 * {@link OverlayDialogueSession}). The overlay text is flattened into runs, the session decides
 * what is translated, and the runs it returns are turned back into a component that has exactly
 * the same total advance as the original, so the overlay stays where it was.
 *
 * <p>Render thread only. The rebuilt component is memoised per received component for
 * {@link #RECHECK_MS}: the server re-sends the overlay every tick while it types and about once
 * a second afterwards, and a new component object is the signal that something may have changed.</p>
 */
public final class OverlayDialogueView {

    private static final long RECHECK_MS = 100L;

    private static final OverlayDialogueSession SESSION = new OverlayDialogueSession();
    private static final Map<String, FontDescription> FONTS = new HashMap<>();

    private static Component lastInput;
    private static long lastAt;
    private static Component lastOutput;

    private OverlayDialogueView() {
    }

    /** True when the component contains any dialogue box font (cheap, stops at the first hit). */
    public static boolean isDialogue(Component text) {
        if (text == null) {
            return false;
        }
        return text.<Boolean>visit((style, content) -> {
            if (OverlayDialogueFonts.isDialogue(fontId(style))) {
                return Optional.of(Boolean.TRUE);
            }
            return Optional.<Boolean>empty();
        }, Style.EMPTY).isPresent();
    }

    /**
     * What to draw for an overlay component.
     *
     * @return {@code null} when {@code text} is not a dialogue box (use the ordinary action bar
     *         path); otherwise the component to draw, which is {@code text} itself when nothing
     *         is translated yet
     */
    public static Component view(Component text, Font font) {
        long now = System.nanoTime() / 1_000_000L;
        if (text == lastInput && now - lastAt < RECHECK_MS) {
            return lastOutput;
        }
        Component out = compute(text, font, now);
        lastInput = text;
        lastAt = now;
        lastOutput = out;
        return out;
    }

    private static Component compute(Component text, Font font, long now) {
        TranslationService service = NyanLexFabric26.service();
        List<OverlayDialogue.Run> runs = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        text.visit((style, content) -> {
            runs.add(new OverlayDialogue.Run(content, fontId(style)));
            styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        boolean dialogue = false;
        for (OverlayDialogue.Run run : runs) {
            if (OverlayDialogueFonts.isDialogue(run.font())) {
                dialogue = true;
                break;
            }
        }
        if (!dialogue) {
            return null;
        }
        if (service == null || service.actionBarMode() == DisplayMode.ORIGINAL_ONLY
                || NyanLexFabric26.projectionSuppressed()) {
            return text;
        }
        OverlayDialogueSession.Outcome outcome = SESSION.process(runs, now,
                (fontId, styleSource, content) -> font.width(
                        Component.literal(content).withStyle(withFont(styles.get(styleSource), fontId))),
                new OverlayDialogueSession.Backend() {
                    @Override
                    public String lookup(String source) {
                        TranslationDecision decision = service.peekActionBar(source);
                        return decision != null && decision.changed() ? decision.translated() : null;
                    }

                    @Override
                    public boolean request(String source, boolean immediate) {
                        if (!service.wantsActionBarTranslation(source)) {
                            return true; // nothing to ask for: do not retry every check
                        }
                        if (immediate) {
                            // the page on screen: now or not at all, so a page that is flipped past
                            // never holds a send slot (the next check asks about the next page)
                            return service.requestActionBarNow(source);
                        }
                        service.requestActionBarAsync(source, translated -> { });
                        return true;
                    }
                }, new Styler(styles));
        if (!outcome.dialogue()) {
            return null;
        }
        if (outcome.spoken() != null && TransientChatLogger.dialogueOn(service)) {
            TransientChatLogger.dialogue(outcome.spoken(), styles);
        }
        if (outcome.runs() == null) {
            return text;
        }
        MutableComponent rebuilt = Component.empty();
        for (OverlayDialogue.OutRun run : outcome.runs()) {
            Style style = styles.get(run.styleSource());
            if (run.font() != null) {
                style = withFont(style, run.font());
            }
            rebuilt.append(Component.literal(run.text()).withStyle(style));
        }
        return rebuilt;
    }

    /**
     * Keeps the colours of the spoken text: the text runs become one component (fonts removed,
     * so the row fonts do not split it into styles), the project's phrase markers decide the
     * key, and the translation is turned back into styled pieces by the same rebuild the chat
     * and action bar use.
     */
    static final class Styler implements OverlayDialogueSession.BodyStyler {
        private final List<Style> styles;
        private Component component;
        private Fabric26TextStyle.MarkedChat marked;

        Styler(List<Style> styles) {
            this.styles = styles;
        }

        @Override
        public String key(List<OverlayDialogue.BodyPiece> pieces) {
            component = null;
            marked = null;
            if (pieces.isEmpty()) {
                return null;
            }
            Style base = plain(styles.get(firstRun(pieces)));
            MutableComponent text = Component.empty();
            for (int i = 0; i < pieces.size(); i++) {
                OverlayDialogue.BodyPiece piece = pieces.get(i);
                Style style;
                if (piece.icon()) {
                    // an inline icon keeps its own font: it is what tells it apart from the words
                    style = styles.get(piece.styleSource());
                } else if (piece.styleSource() >= 0) {
                    style = plain(styles.get(piece.styleSource()));
                } else {
                    // the space between two rows: it joins the phrase when both neighbours share
                    // a style, otherwise it is plain text
                    Style before = i > 0 ? styleOf(pieces.get(i - 1), base) : base;
                    Style after = i + 1 < pieces.size() ? styleOf(pieces.get(i + 1), base) : base;
                    style = before.equals(after) ? before : base;
                }
                text.append(Component.literal(piece.text()).setStyle(style));
            }
            component = text;
            marked = Fabric26TextStyle.markChatContent(text, 0);
            return marked.marked() ? marked.text() : text.getString();
        }

        @Override
        public List<OverlayDialogue.Segment> segments(String translated) {
            if (component == null || translated == null) {
                return null;
            }
            Component rich = Fabric26TextStyle.rebuildRich(component, translated, marked);
            String original = component.getString();
            String shown = rich.getString();
            // a response whose markers were lost is rebuilt as the untouched original: no translation
            if (shown.isBlank() || (shown.equals(original) && !translated.equals(original))) {
                return null;
            }
            List<OverlayDialogue.Segment> out = new ArrayList<>();
            rich.visit((style, content) -> {
                if (!content.isEmpty()) {
                    int index = styles.indexOf(style);
                    if (index < 0) {
                        styles.add(style);
                        index = styles.size() - 1;
                    }
                    out.add(new OverlayDialogue.Segment(content, index));
                }
                return Optional.empty();
            }, Style.EMPTY);
            return out.isEmpty() ? null : out;
        }

        private Style styleOf(OverlayDialogue.BodyPiece piece, Style fallback) {
            if (piece.icon()) {
                return styles.get(piece.styleSource());
            }
            return piece.styleSource() >= 0 ? plain(styles.get(piece.styleSource())) : fallback;
        }

        private static int firstRun(List<OverlayDialogue.BodyPiece> pieces) {
            for (OverlayDialogue.BodyPiece piece : pieces) {
                if (piece.styleSource() >= 0) {
                    return piece.styleSource();
                }
            }
            return 0;
        }
    }

    /** The style without its font: the row fonts must not make equal colours look different. */
    private static Style plain(Style style) {
        return style.withFont(FontDescription.DEFAULT);
    }

    private static String fontId(Style style) {
        FontDescription description = style.getFont();
        if (description instanceof FontDescription.Resource resource) {
            return resource.id().toString();
        }
        return "minecraft:default";
    }

    private static Style withFont(Style style, String fontId) {
        FontDescription description = FONTS.get(fontId);
        if (description == null) {
            Identifier id = Identifier.tryParse(fontId);
            description = id == null ? FontDescription.DEFAULT : new FontDescription.Resource(id);
            FONTS.put(fontId, description);
        }
        return style.withFont(description);
    }
}
