package com.dragonmeow.nyanlex.config;

/**
 * The few drawing primitives the settings panel needs. Each Minecraft glue wraps its own
 * graphics object in one of these, so all layout and painting logic lives once in core.
 * Colors are ARGB with an explicit 0xFF alpha for opaque pixels.
 */
public interface UiCanvas {

    /** Fills a rectangle (clipped by the current clip, if any). */
    void fill(int x, int y, int w, int h, int argb);

    /** Draws one line of text with its top-left at (x, y). */
    void text(String text, int x, int y, int argb);

    /** Restricts drawing to a rectangle (intersected with any active clip) until {@link #popClip()}. */
    void pushClip(int x, int y, int w, int h);

    void popClip();
}
