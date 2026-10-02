package com.dragonmeow.nyanslate.config;

/** What the warm-up card asks the glue to do (the glue owns the driver and its screens). */
public enum WarmupCommand {
    /** Open the scan-and-confirm screen (a run never starts without that confirmation). */
    START,
    PAUSE,
    RESUME,
    STOP,
    /** Open the full progress screen. */
    OPEN_PROGRESS
}
