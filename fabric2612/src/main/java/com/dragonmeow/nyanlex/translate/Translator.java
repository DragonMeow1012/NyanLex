package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.List;

/** Abstraction over a translation backend so it can be swapped / mocked. */
public interface Translator {

    /**
     * Translate {@code text} into {@code targetLang}.
     *
     * @throws TranslationException if the request fails
     */
    TranslationResult translate(String text, String targetLang) throws TranslationException;

    /**
     * Translate several texts. The default loops {@link #translate}; backends that
     * support batching (one request for many texts) should override this for speed.
     * The returned list is aligned 1:1 with {@code texts}.
     */
    default List<TranslationResult> translateBatch(List<String> texts, String targetLang) throws TranslationException {
        List<TranslationResult> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(translate(text, targetLang));
        }
        return out;
    }

    /**
     * Batch translation with optional shared surface context: {@code surfaceContext} is the
     * COMPLETE paragraph list of the surface the batch came from (e.g. a whole item
     * tooltip, book page or scoreboard), including units already cached and therefore absent
     * from {@code texts}. No generic first-unit/title assumption is implied. Context-aware
     * backends use it so partial batches still translate
     * coherently with the whole surface; the default simply ignores it and delegates to
     * {@link #translateBatch(List, String)}, so existing implementations keep working.
     * {@code surfaceContext} may be {@code null} (no context).
     */
    default List<TranslationResult> translateBatch(List<String> texts, String targetLang,
                                                   List<String> surfaceContext) throws TranslationException {
        return translateBatch(texts, targetLang);
    }

    /**
     * Batch translation in which every text may carry its OWN surface context:
     * {@code itemContexts} is aligned 1:1 with {@code texts}, and an entry (or the whole
     * list) may be {@code null} for "no context". A window-collected batch mixes several
     * surfaces (two tooltips, a sidebar, chat) in one request; a context-aware backend
     * labels each context with the units it applies to. The default keeps the single-context
     * contract: a context shared by every text is forwarded, mixed contexts are dropped,
     * exactly as a collector did before per-item contexts existed.
     */
    default List<TranslationResult> translateBatchWithContexts(List<String> texts, String targetLang,
                                                               List<List<String>> itemContexts)
            throws TranslationException {
        return translateBatch(texts, targetLang, sharedContext(itemContexts, texts.size()));
    }

    /**
     * How long this backend's own request pacing would hold a request issued now, in
     * milliseconds ({@code 0} = it may go out immediately). Read-only: nothing is reserved.
     * Backends without pacing keep the default. A window collector reads it on the client
     * tick so it composes a batch only when that batch can actually be sent.
     */
    default long nextRequestDelayMs() {
        return 0L;
    }

    /**
     * Whether this backend currently refuses to send anything (a global rate-limit gate is
     * closed). Read-only. The cache then leaves its queue untouched instead of draining it into
     * requests that would be abandoned.
     */
    default boolean sendBlocked() {
        return false;
    }

    /** The context every one of {@code count} items shares, or {@code null} when absent or mixed. */
    static List<String> sharedContext(List<List<String>> itemContexts, int count) {
        if (itemContexts == null || itemContexts.isEmpty() || itemContexts.size() != count) return null;
        List<String> shared = itemContexts.get(0);
        if (shared == null) return null;
        for (List<String> context : itemContexts) {
            if (!shared.equals(context)) return null;
        }
        return shared;
    }
}
