package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps one frame per typed-out text. An overlay that types a sentence out sends a new frame
 * for every character; a recording of it only needs the last frame of each text. A frame is
 * held back while the next one merely extends its text, and released when the text changes in
 * any other way, when a frame that is not a typed-out text comes, or when nothing newer came
 * for a while ({@link #idle}).
 *
 * <p>Not thread-safe: one writer thread owns an instance.</p>
 */
public final class TypedFrameCollapser<T> {

    private T held;
    private String heldText;
    private long heldAt;

    /**
     * @param frame the new frame
     * @param typed the words of the frame, or {@code null} when it is not a typed-out text
     *              (such a frame is passed through at once, after the one held)
     * @return the frames to write now, oldest first
     */
    public List<T> offer(T frame, String typed, long now) {
        List<T> out = new ArrayList<>(2);
        if (typed == null) {
            release(out);
            out.add(frame);
            return out;
        }
        if (held != null && typed.equals(heldText)) {
            return out; // the same words again (a blinking icon, a moving bar): the first one stays
        }
        if (held != null && !heldText.isEmpty() && typed.startsWith(heldText)) {
            held = frame; // typed further: the older frame is not worth a line
            heldText = typed;
            heldAt = now;
            return out;
        }
        release(out);
        held = frame;
        heldText = typed;
        heldAt = now;
        return out;
    }

    /** The held frame, if nothing newer came for {@code idleMs}. */
    public List<T> idle(long now, long idleMs) {
        List<T> out = new ArrayList<>(1);
        if (held != null && now - heldAt >= idleMs) {
            release(out);
        }
        return out;
    }

    private void release(List<T> out) {
        if (held != null) {
            out.add(held);
            held = null;
            heldText = null;
        }
    }
}
