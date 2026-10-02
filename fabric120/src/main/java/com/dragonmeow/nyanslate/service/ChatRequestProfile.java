package com.dragonmeow.nyanslate.service;

import com.dragonmeow.nyanslate.config.TranslatorConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable identity of the settings that can change the result of a chat
 * translation request. Delivery order and other presentation-only settings
 * intentionally do not belong here: toggling them must not retire a backlog.
 */
public final class ChatRequestProfile {

    private final String targetLang;
    private final String sourceLang;
    private final boolean aiChat;
    private final String machineProvider;
    private final boolean machineFallbackEnabled;
    private final String aiBaseUrl;
    private final String aiModel;
    private final boolean useCodex;
    private final String codexModel;
    private final String codexReasoningEffort;
    private final List<String> glossary;
    private final boolean protectPlayerNames;
    /** Turning new requests off (or on) retires the pending backlog as originals. */
    private final boolean translationRequestsEnabled;
    /** Do-not-translate terms change the masked request of every engine. */
    private final List<String> doNotTranslateTerms;

    private ChatRequestProfile(TranslatorConfig config, String activeTargetLang) {
        targetLang = text(activeTargetLang);
        aiChat = config.aiChat;
        machineFallbackEnabled = aiChat && !config.disableGoogleFallbackForAi;
        boolean usesMachine = !aiChat || machineFallbackEnabled;
        sourceLang = usesMachine ? text(config.sourceLang) : "";
        machineProvider = usesMachine ? text(config.machineTranslationProvider) : "";
        useCodex = aiChat && config.aiUseCodex;
        aiBaseUrl = aiChat && !useCodex ? text(config.aiBaseUrl) : "";
        aiModel = aiChat && !useCodex ? text(config.aiModel) : "";
        codexModel = useCodex ? text(config.codexModel) : "";
        codexReasoningEffort = useCodex ? text(config.codexReasoningEffort) : "";
        glossary = aiChat
                ? Collections.unmodifiableList(new ArrayList<>(config.aiGlossary == null
                        ? Collections.emptyList() : config.aiGlossary))
                : Collections.emptyList();
        protectPlayerNames = config.protectPlayerNames;
        translationRequestsEnabled = config.translationRequestsEnabled;
        doNotTranslateTerms = Collections.unmodifiableList(new ArrayList<>(
                config.doNotTranslateTerms == null
                        ? Collections.emptyList() : config.doNotTranslateTerms));
    }

    public static ChatRequestProfile capture(TranslatorConfig config, String activeTargetLang) {
        return new ChatRequestProfile(Objects.requireNonNull(config, "config"), activeTargetLang);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ChatRequestProfile)) return false;
        ChatRequestProfile that = (ChatRequestProfile) other;
        return aiChat == that.aiChat
                && machineFallbackEnabled == that.machineFallbackEnabled
                && useCodex == that.useCodex
                && protectPlayerNames == that.protectPlayerNames
                && translationRequestsEnabled == that.translationRequestsEnabled
                && targetLang.equals(that.targetLang)
                && sourceLang.equals(that.sourceLang)
                && machineProvider.equals(that.machineProvider)
                && aiBaseUrl.equals(that.aiBaseUrl)
                && aiModel.equals(that.aiModel)
                && codexModel.equals(that.codexModel)
                && codexReasoningEffort.equals(that.codexReasoningEffort)
                && glossary.equals(that.glossary)
                && doNotTranslateTerms.equals(that.doNotTranslateTerms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetLang, sourceLang, aiChat, machineProvider,
                machineFallbackEnabled, aiBaseUrl, aiModel, useCodex, codexModel,
                codexReasoningEffort, glossary, protectPlayerNames,
                translationRequestsEnabled, doNotTranslateTerms);
    }
}
