package com.dragonmeow.nyanlex.translate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-frame brain of the overlay dialogue translation: reads the blocks of the overlay that is
 * on screen, decides which of them may be looked up or requested right now, and rebuilds the
 * overlay with every block that already has a translation.
 *
 * <ul>
 *   <li>The spoken text is the only block that can still be "typing": while a new text merely
 *       extends the previous one it stays original and nothing is requested; the request goes
 *       out once the text has not changed for {@link #SETTLE_MS}.</li>
 *   <li>Speaker name, hint and answers are the same every frame: they are looked up and
 *       requested at once, the ordinary way (the backend deduplicates).</li>
 *   <li>The spoken text is requested with {@code immediate = true}: if the engine cannot send
 *       right now nothing is queued and the next check asks about the text then on screen, so
 *       pages that were flipped past never occupy a send slot.</li>
 *   <li>The keys are exactly the block texts, so one sentence is one cache entry no matter how
 *       the server broke it into lines or what else is on the screen.</li>
 * </ul>
 *
 * <p>Not thread-safe: use from the render thread only.</p>
 */
public final class OverlayDialogueSession {

    /** How long the spoken text must stay unchanged before it is sent for translation. */
    public static final long SETTLE_MS = 500L;
    /**
     * Widest body line in px. The server never puts a word on a line that would exceed about
     * 190-208 px next to the portrait frame; 200 sits inside that window and inside the box.
     */
    public static final int LINE_LIMIT_PX = 200;

    private static final int WRAP_MEMO = 16;

    /** Translation source of the session. */
    public interface Backend {
        /** The cached translation of {@code source}, or {@code null}. Must not start a request. */
        String lookup(String source);

        /**
         * Ask for the translation of {@code source}; asked again after a pause until it arrives.
         *
         * @param immediate the text is what the player is reading right now: ask only if the
         *                  request can go out at once, otherwise ask for nothing and return
         *                  {@code false} (the next check asks about whatever is on screen then)
         * @return {@code true} when the request was accepted or there is nothing to ask,
         *         {@code false} when it must be tried again at the next check
         */
        boolean request(String source, boolean immediate);
    }

    /**
     * Glue-side handling of the colours of the spoken text. The core never sees a style, only
     * indices into a style table the glue owns (it may append to it while converting).
     */
    public interface BodyStyler {
        /**
         * The translation key of the spoken text. For a text of one style this is the plain
         * text (so a plain translation already stored is found); for several styles it carries
         * the project's per-phrase colour markers.
         */
        String key(List<OverlayDialogue.BodyPiece> pieces);

        /**
         * The translation returned for the last {@link #key} as styled pieces, or {@code null}
         * when it cannot be used (the colour markers did not survive): the page then stays original.
         */
        List<OverlayDialogue.Segment> segments(String translated);
    }

    /**
     * What to draw. {@code dialogue == false}: not a dialogue box, use the ordinary path.
     * Otherwise {@code runs} is the rebuilt overlay, or {@code null} to draw the original.
     */
    public record Outcome(boolean dialogue, List<OverlayDialogue.OutRun> runs, Spoken spoken) {
        static final Outcome NOT_DIALOGUE = new Outcome(false, null, null);
        static final Outcome UNCHANGED = new Outcome(true, null, null);

        public Outcome(boolean dialogue, List<OverlayDialogue.OutRun> runs) {
            this(dialogue, runs, null);
        }
    }

    /**
     * The page whose spoken text has a translation now (whether or not it fitted the box):
     * what a chat log needs. {@code speakerTranslated} is {@code null} until the name has one.
     */
    public record Spoken(String speakerOriginal, String speakerTranslated, String bodyOriginal,
                         List<OverlayDialogue.Segment> body) {
    }

    private final OverlayTextGate gate = new OverlayTextGate(SETTLE_MS);
    private final Map<String, List<String>> wrapMemo = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > WRAP_MEMO;
        }
    };

    public Outcome process(List<OverlayDialogue.Run> runs, long now, OverlayDialogue.Measure measure,
                           Backend backend) {
        return process(runs, now, measure, backend, null);
    }

    /**
     * @param styler keeps the colours of the spoken text; {@code null} = plain text only (the
     *               first style of each row is used for the whole row)
     */
    public Outcome process(List<OverlayDialogue.Run> runs, long now, OverlayDialogue.Measure measure,
                           Backend backend, BodyStyler styler) {
        OverlayDialogue.Analysis analysis = OverlayDialogue.analyze(runs);
        if (!analysis.dialogue()) {
            gate.clear();
            return Outcome.NOT_DIALOGUE;
        }
        Map<String, String> sources = analysis.sources();
        for (String id : IDS) {
            gate.observe(id, sources.get(id), now);
        }
        Map<String, String> translations = new HashMap<>();
        List<OverlayDialogue.Segment> bodySegments = null;
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String id = entry.getKey();
            // The gate above follows the plain text (typing is judged on the words, never on
            // markers); what is looked up and asked for may be the marked-up key.
            boolean body = OverlayDialogue.BODY_ID.equals(id);
            String source = entry.getValue();
            if (body && styler != null) {
                String key = styler.key(analysis.bodyPieces());
                if (key != null && !key.isEmpty()) {
                    source = key;
                }
            }
            // The spoken text is the page on screen: it is asked for only when it can go out at
            // once. Name, hint and answers ride along with the next batch in the ordinary way.
            boolean immediate = OverlayDialogue.BODY_ID.equals(id);
            long settle = immediate ? SETTLE_MS : 0L;
            if (gate.typing(id, now)) {
                continue;
            }
            String translated = backend.lookup(source);
            if (translated != null) {
                if (body && styler != null) {
                    bodySegments = styler.segments(translated);
                } else {
                    translations.put(id, translated);
                }
            } else if (gate.due(id, now, settle) && backend.request(source, immediate)) {
                gate.asked(id, now);
            }
        }
        if (translations.isEmpty() && bodySegments == null) {
            return Outcome.UNCHANGED;
        }
        List<OverlayDialogue.Segment> spokenBody = bodySegments;
        if (spokenBody == null && translations.get(OverlayDialogue.BODY_ID) != null) {
            spokenBody = List.of(new OverlayDialogue.Segment(translations.get(OverlayDialogue.BODY_ID), -1));
        }
        Spoken spoken = spokenBody == null ? null : new Spoken(sources.get(OverlayDialogue.NAMEPLATE_ID),
                translations.get(OverlayDialogue.NAMEPLATE_ID), sources.get(OverlayDialogue.BODY_ID), spokenBody);
        List<OverlayDialogue.OutRun> out = OverlayDialogue.rewrite(analysis, translations, bodySegments,
                measure, LINE_LIMIT_PX, new OverlayDialogue.Wrapper() {
                    @Override
                    public List<String> wrap(String font, int style, String text, int limit) {
                        return OverlayDialogueSession.this.wrap(measure, font, style, text, limit, Map.of());
                    }

                    @Override
                    public List<String> wrap(String font, int style, String text, int limit,
                                             Map<Integer, Integer> fixedWidths) {
                        return OverlayDialogueSession.this.wrap(measure, font, style, text, limit, fixedWidths);
                    }
                });
        return new Outcome(true, out, spoken);
    }

    private static final String[] IDS = ids();

    private static String[] ids() {
        String[] ids = new String[3 + OverlayDialogueFonts.CHOICE_ROWS];
        ids[0] = OverlayDialogue.BODY_ID;
        ids[1] = OverlayDialogue.NAMEPLATE_ID;
        ids[2] = OverlayDialogue.CONTROL_ID;
        for (int row = 0; row < OverlayDialogueFonts.CHOICE_ROWS; row++) {
            ids[3 + row] = OverlayDialogue.choiceId(row);
        }
        return ids;
    }

    private List<String> wrap(OverlayDialogue.Measure measure, String font, int style, String text,
                              int limit, Map<Integer, Integer> fixedWidths) {
        String key = font + '\u0000' + limit + '\u0000' + text
                + (fixedWidths.isEmpty() ? "" : '\u0000' + new java.util.TreeMap<>(fixedWidths).toString());
        List<String> lines = wrapMemo.get(key);
        if (lines == null) {
            lines = OverlayLineWrap.wrap(text, limit, cp -> {
                Integer fixed = fixedWidths.get(cp);
                return fixed != null ? fixed : measure.width(font, style, new String(Character.toChars(cp)));
            });
            wrapMemo.put(key, lines);
        }
        return lines;
    }
}
