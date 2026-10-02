package com.dragonmeow.nyanlex.translate;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/**
 * Retired: the per-paragraph tooltip trace wrote a file for every tooltip. 偵錯模式 now only logs
 * errors ({@link DebugErrorLog}). The class stays so loader glue that has not been updated yet
 * still compiles; it does nothing.
 */
@Deprecated
public final class TooltipTraceWriter {
    public TooltipTraceWriter(Path dumpDir, BooleanSupplier enabled, int maxFiles) {
        // intentionally nothing
    }
}
