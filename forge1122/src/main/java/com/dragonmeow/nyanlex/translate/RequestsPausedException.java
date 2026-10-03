package com.dragonmeow.nyanlex.translate;

/**
 * Thrown on a translation worker when the "send new translation requests" switch was
 * turned off while its request was still waiting for a send slot. It is deliberately
 * unchecked and NOT a {@link TranslationException}: translators only catch transport
 * errors, so it travels untouched to the cache, which ends the request without
 * recording any failure, backoff, fallback or keep-original state.
 */
public final class RequestsPausedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RequestsPausedException() {
        super("translation requests are paused", null, false, false);
    }
}
