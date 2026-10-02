package com.dragonmeow.nyanlex.translate;

/**
 * Player-facing side of {@link MachineTranslationGate}: what to tell the player when the gate
 * closes and when a manual action (R, P) meets a closed gate. The loader glue implements
 * {@link Feedback} (action bar in a world, system notification in a menu); this class holds
 * the decisions so they are testable without Minecraft.
 */
public final class MachineGateGuard {

    /** Where the two messages go. Called from any thread; the implementation hops to the client thread. */
    public interface Feedback {
        /** The gate has just closed: Google will be retried automatically in about {@code minutes}. */
        void gateClosed(int minutes);

        /** A manual action was refused because the gate is closed. */
        void manualBlocked(int minutes);
    }

    private MachineGateGuard() {
    }

    /** Announce every closing of {@code gate} (once per close) through {@code ui}. */
    public static void install(MachineTranslationGate gate, Feedback ui) {
        gate.setListener(ui == null ? null : ui::gateClosed);
    }

    /**
     * Whether a manual R/P action must be dropped. Only when online translation is already on
     * (otherwise the consent box runs as usual, and the gate applies after consent), and only
     * for a surface served by the machine engine. A refused key press shows the message and sends nothing.
     */
    public static boolean blocksManualAction(boolean onlineEnabled, boolean machineEngine,
                                             MachineTranslationGate gate, Feedback ui) {
        if (!onlineEnabled || !machineEngine || gate == null || !gate.blocksRequests()) return false;
        if (ui != null) ui.manualBlocked(Math.max(1, gate.minutesUntilReopen()));
        return true;
    }
}
