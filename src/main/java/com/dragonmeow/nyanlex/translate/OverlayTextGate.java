package com.dragonmeow.nyanlex.translate;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides when a block of text that an overlay re-sends every frame is worth translating.
 *
 * <p>Some overlays reveal their text one character per frame: every frame carries the previous
 * text plus a few more characters. Translating those half sentences is wasted work (and each
 * one is a different cache key). The gate therefore tracks each block by an id, notices when a
 * new text merely <em>extends</em> the previous one (still being typed) and only lets a request
 * out once the text has stopped changing for the block's settle time. A text that is not an
 * extension (a new page, another speaker) is not "typing" and may be looked up straight away.</p>
 *
 * <p>Not thread-safe: one instance belongs to the thread that renders the overlay.</p>
 */
public final class OverlayTextGate {

    /** Minimum gap between two requests for the same unchanged text. */
    public static final long REPEAT_MS = 2_000L;

    private static final class State {
        String text;
        long changedAt;
        boolean extending;
        long lastAsk = -1L;
    }

    private final long typingMs;
    private final Map<String, State> states = new HashMap<>();

    /**
     * @param typingMs how long after the last growth a block still counts as "being typed"
     */
    public OverlayTextGate(long typingMs) {
        this.typingMs = typingMs;
    }

    /** Records what block {@code id} shows now; {@code null} or empty forgets the block. */
    public void observe(String id, String text, long now) {
        if (text == null || text.isEmpty()) {
            states.remove(id);
            return;
        }
        State state = states.get(id);
        if (state == null) {
            state = new State();
            state.text = text;
            state.changedAt = now;
            states.put(id, state);
            return;
        }
        if (text.equals(state.text)) {
            return;
        }
        state.extending = text.length() > state.text.length() && text.startsWith(state.text);
        state.text = text;
        state.changedAt = now;
        state.lastAsk = -1L;
    }

    /** Drops every block (the overlay stopped showing a dialogue). */
    public void clear() {
        states.clear();
    }

    /** True while the block's text grew within the last {@code typingMs}: show the original. */
    public boolean typing(String id, long now) {
        State state = states.get(id);
        return state != null && state.extending && now - state.changedAt < typingMs;
    }

    /**
     * True when a request for the block's current text is due: the text has been unchanged for
     * at least {@code settleMs}, and it was not already asked for within {@link #REPEAT_MS}.
     * Read-only; report an accepted request with {@link #asked}, so a request the backend
     * could not take yet (engine busy) is due again at the very next check.
     */
    public boolean due(String id, long now, long settleMs) {
        State state = states.get(id);
        if (state == null || now - state.changedAt < settleMs) {
            return false;
        }
        return state.lastAsk < 0L || now - state.lastAsk >= REPEAT_MS;
    }

    /** Records that the backend accepted the request for the block's current text. */
    public void asked(String id, long now) {
        State state = states.get(id);
        if (state != null) {
            state.lastAsk = now;
        }
    }
}
