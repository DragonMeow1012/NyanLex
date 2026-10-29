package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What this game session has learned about each AI model: optional request features it
 * rejected, and the effort levels, thinking types and output-token limit its catalog entry
 * advertises. Kept in memory only, so a restart forgets it and tries again. Java 8 compatible
 * (copied verbatim to the old loaders).
 */
public final class AiFeatureMemory {
    private static final int MAX_MODELS = 256;
    private static final AiFeatureMemory SHARED = new AiFeatureMemory();

    private final Map<String, Set<AnthropicWire.Feature>> unsupported =
            new HashMap<String, Set<AnthropicWire.Feature>>();
    private final Map<String, List<String>> effortLevels = new HashMap<String, List<String>>();
    private final Map<String, Integer> maxOutputTokens = new HashMap<String, Integer>();
    private final Map<String, List<String>> thinkingTypes = new HashMap<String, List<String>>();

    public static AiFeatureMemory shared() {
        return SHARED;
    }

    public synchronized boolean isUnsupported(String model, AnthropicWire.Feature feature) {
        Set<AnthropicWire.Feature> features = unsupported.get(key(model));
        return features != null && features.contains(feature);
    }

    public synchronized void markUnsupported(String model, AnthropicWire.Feature feature) {
        if (unsupported.size() >= MAX_MODELS && !unsupported.containsKey(key(model))) unsupported.clear();
        Set<AnthropicWire.Feature> features = unsupported.get(key(model));
        if (features == null) {
            features = EnumSet.noneOf(AnthropicWire.Feature.class);
            unsupported.put(key(model), features);
        }
        features.add(feature);
    }

    /**
     * Effort levels the catalog lists for {@code model}: {@code null} when the catalog was
     * never read (unknown), an empty list when it says effort is not supported at all.
     */
    public synchronized List<String> effortLevels(String model) {
        List<String> levels = effortLevels.get(key(model));
        return levels == null ? null : Collections.unmodifiableList(new ArrayList<String>(levels));
    }

    /** Record what the catalog said; a {@code null} list means the catalog did not say. */
    public synchronized void recordEffortLevels(String model, List<String> levels) {
        if (levels == null) {
            effortLevels.remove(key(model));
            return;
        }
        if (effortLevels.size() >= MAX_MODELS && !effortLevels.containsKey(key(model))) effortLevels.clear();
        effortLevels.put(key(model), new ArrayList<String>(levels));
    }

    /**
     * The {@code thinking.type} values the model accepts, from its catalog entry or from what an
     * error taught us: {@code null} when never learned, an empty list when none can be used.
     */
    public synchronized List<String> thinkingTypes(String model) {
        List<String> types = thinkingTypes.get(key(model));
        return types == null ? null : Collections.unmodifiableList(new ArrayList<String>(types));
    }

    /** Record the accepted thinking types; a {@code null} list means they are not known. */
    public synchronized void recordThinkingTypes(String model, List<String> types) {
        if (types == null) {
            thinkingTypes.remove(key(model));
            return;
        }
        if (thinkingTypes.size() >= MAX_MODELS && !thinkingTypes.containsKey(key(model))) thinkingTypes.clear();
        thinkingTypes.put(key(model), new ArrayList<String>(types));
    }

    /** The output-token limit the catalog gives {@code model}; 0 when it never said. */
    public synchronized int maxOutputTokens(String model) {
        Integer limit = maxOutputTokens.get(key(model));
        return limit == null ? 0 : limit.intValue();
    }

    /** Record the catalog's output-token limit; a value that is not positive is ignored. */
    public synchronized void recordMaxOutputTokens(String model, int limit) {
        if (limit <= 0) return;
        if (maxOutputTokens.size() >= MAX_MODELS && !maxOutputTokens.containsKey(key(model))) maxOutputTokens.clear();
        maxOutputTokens.put(key(model), Integer.valueOf(limit));
    }

    private static String key(String model) {
        return model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
    }
}
