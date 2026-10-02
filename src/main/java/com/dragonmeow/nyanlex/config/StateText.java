package com.dragonmeow.nyanlex.config;

import java.util.List;

/**
 * Value shown inside a settings button label, expressed without Minecraft types:
 * either a lang key (with optional arguments, each a String literal or nested
 * {@link StateText}) or a plain literal when {@link #key()} is {@code null}.
 */
public record StateText(String key, List<Object> args) {

    public static StateText of(String key, Object... args) {
        return new StateText(key, List.of(args));
    }

    public static StateText literal(String text) {
        return new StateText(null, List.of(text));
    }

    public boolean isLiteral() { return key == null; }

    /** The literal text; only valid when {@link #isLiteral()}. */
    public String literalText() { return String.valueOf(args.get(0)); }
}
