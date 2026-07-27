package com.dragonmeow.mctranslator.config;

import com.dragonmeow.mctranslator.translate.CodexAppServerClient.ModelOption;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Selection policy for the live Codex model catalog returned by app-server. */
public final class CodexModelCatalog {

    private CodexModelCatalog() {
    }

    public static Optional<ModelOption> selected(
            TranslatorConfig config, List<ModelOption> models) {
        if (config == null || models == null) return Optional.empty();
        return models.stream()
                .filter(option -> option.model().equals(config.codexModel))
                .findFirst();
    }

    public static void normalizeSelection(
            TranslatorConfig config, List<ModelOption> models) {
        if (models == null || models.isEmpty()) {
            config.codexModel = TranslatorConfig.DEFAULT_CODEX_MODEL;
            config.codexReasoningEffort = TranslatorConfig.DEFAULT_CODEX_REASONING_EFFORT;
            return;
        }
        ModelOption selected = selected(config, models)
                .orElseGet(() -> models.stream()
                        .filter(ModelOption::isDefault)
                        .findFirst()
                        .orElse(models.get(0)));
        select(config, selected);
    }

    public static void select(TranslatorConfig config, ModelOption model) {
        config.codexModel = model.model();
        normalizeEffort(config, model);
    }

    public static void normalizeEffort(TranslatorConfig config, ModelOption model) {
        List<String> efforts = model.reasoningEfforts();
        if (efforts.isEmpty()) {
            config.codexReasoningEffort = "";
            return;
        }
        if (efforts.contains(config.codexReasoningEffort)) return;
        String defaultEffort = model.defaultReasoningEffort();
        config.codexReasoningEffort =
                defaultEffort != null && efforts.contains(defaultEffort)
                        ? defaultEffort
                        : efforts.get(0);
    }

    public static List<ModelOption> filter(List<ModelOption> models, String query) {
        if (models == null || models.isEmpty()) return List.of();
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return List.copyOf(models);
        return models.stream()
                .filter(option -> option.model().toLowerCase(Locale.ROOT).contains(needle)
                        || option.displayName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    public static List<String> supportedEfforts(
            TranslatorConfig config, List<ModelOption> models) {
        return selected(config, models)
                .map(ModelOption::reasoningEfforts)
                .orElse(List.of());
    }
}
