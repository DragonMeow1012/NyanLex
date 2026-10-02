package com.dragonmeow.nyanlex.translate;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Kept for the loader trees that still build their AI exchange hook from a folder and a switch.
 * It no longer dumps every exchange: it is the error-only sink of a {@link DebugErrorLog} that
 * lives in {@code dumpDir} (see {@link DebugErrorLog#exchangeSink()}). Nothing is written while
 * the switch is off, or while exchanges go well; keys are masked.
 */
public final class ExchangeDumpWriter implements OpenAiTranslator.ExchangeDumpSink {
    private final DebugErrorLog log;
    private final OpenAiTranslator.ExchangeDumpSink sink;

    public ExchangeDumpWriter(Path dumpDir, BooleanSupplier enabled, int maxFiles) {
        this(dumpDir, enabled, maxFiles, List::of);
    }

    public ExchangeDumpWriter(Path dumpDir, BooleanSupplier enabled, int maxFiles,
                              Supplier<List<String>> secretsSupplier) {
        this.log = new DebugErrorLog(dumpDir.resolve("nyanlex-debug-log.jsonl"), enabled,
                DebugErrorLog.DEFAULT_MAX_ENTRIES, secretsSupplier);
        this.sink = log.exchangeSink();
    }

    @Override
    public void record(String requestBody, String responseBody, List<String> sourceTexts,
                       List<TranslationResult> results) {
        sink.record(requestBody, responseBody, sourceTexts, results);
    }
}
