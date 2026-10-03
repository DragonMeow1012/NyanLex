package com.dragonmeow.nyanlex.service;

import com.dragonmeow.nyanlex.config.TranslatorConfig;
import com.dragonmeow.nyanlex.translate.*;
import java.util.Collections;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Explicit user draft requests share the production backends/pacers, never the display cache. */
public final class OutgoingChatTranslator {
    private final TranslatorConfig config;
    private final Translator machine, ai;
    private final Executor executor;
    private final Supplier<? extends Collection<String>> names;
    private final AtomicBoolean busy = new AtomicBoolean();

    public OutgoingChatTranslator(TranslatorConfig config, Translator machine, Translator ai,
            Executor executor, Supplier<? extends Collection<String>> names) {
        this.config = config; this.machine = machine; this.ai = ai;
        this.executor = executor; this.names = names;
    }

    public void translate(String source, String target, BiConsumer<String, String> callback) {
        if (!config.translationRequestsEnabled) { callback.accept(null, "offline"); return; }
        if (!busy.compareAndSet(false, true)) { callback.accept(null, "busy"); return; }
        try {
            ChatRequestProfile profile = ChatRequestProfile.capture(config, target);
            NameMasker.Masked masked = NameMasker.mask(source,
                    config.protectPlayerNames ? names.get() : Collections.emptyList(),
                    DoNotTranslateMatcher.compile(config.doNotTranslateTerms));
            executor.execute(() -> {
                String result = null, error = "failed";
                java.util.function.BooleanSupplier previousGate = RequestGate.bind(() ->
                        config.translationRequestsEnabled
                        && profile.equals(ChatRequestProfile.capture(config, target)));
                try {
                    if (!config.translationRequestsEnabled) error = "offline";
                    else if (!profile.equals(ChatRequestProfile.capture(config, target))) error = "changed";
                    else {
                        Translator backend = config.aiChat ? ai : machine;
                        if (backend.sendBlocked()) error = "paused";
                        else {
                            TranslationResult reply = backend.translate(masked.text(), target);
                            if (reply != null && reply.failureReason() == null && reply.translatedText() != null)
                                result = NameMasker.unmask(reply.translatedText(), masked.names());
                        }
                    }
                } catch (RequestsPausedException e) { error = "paused"; }
                catch (Exception e) { error = "failed"; }
                finally { RequestGate.restore(previousGate); busy.set(false); }
                if (!profile.equals(ChatRequestProfile.capture(config, target))) {
                    result = null; error = "changed";
                }
                callback.accept(result, error);
            });
        } catch (RuntimeException e) {
            busy.set(false);
            callback.accept(null, "busy");
        }
    }
}
