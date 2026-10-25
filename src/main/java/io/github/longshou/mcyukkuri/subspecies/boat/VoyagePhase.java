package io.github.longshou.mcyukkuri.subspecies.boat;

/**
 * The captain's one lifecycle. Every phase but the resting ones has a time limit ({@link #ttl}) and an explicit fallback, written in
 * {@link VoyageController}: launch failures stow back to {@link #ASHORE}, landing failures try another berth and then stay
 * {@link #AFLOAT}.
 */
public enum VoyagePhase {
    /** On the bank or on land; nothing is under way. */
    ASHORE(0),
    /** Finding the paddle and food, walking to the launch bank. */
    PREPARE(900),
    /** Classifying every child of the trip: ready, carried, busy or absent. Busy ones are tended within a limit. */
    MUSTER(420),
    /** The empty hull is set down at the berth and held there. */
    MOOR(160),
    /** The young go aboard first: jumpers hop, dependents are lifted from the bank by the captain. */
    LOAD(1500),
    /** The captain steps aboard last. */
    EMBARK(240),
    /** Under way; the afloat agenda runs. */
    AFLOAT(0),
    /** Sailing to the landing berth. */
    RETURN(1200),
    /** The hull is brought alongside the bank. */
    DOCK(420),
    /** Everyone steps or is lifted ashore, then the captain. */
    DISEMBARK(2600),
    /** The captain walks to the hat, faces it and takes it in. */
    STOW(600),
    /** The hull is over or being righted; the water incident code owns the bodies. */
    CAPSIZED(7200),
    /** Captain ashore, own hull adrift or beached: walk to it, refloat it, step aboard. */
    RECOVER(900);

    private final int ttl;
    VoyagePhase(int ttl) { this.ttl = ttl; }

    /** Ticks the phase may last before its fallback; 0 when it is a resting phase. */
    public int ttl() { return ttl; }

    /** Phases run by the captain's priority-3 goal as one transaction; the others are resting phases. */
    public boolean transactional() {
        return switch (this) { case ASHORE, AFLOAT, CAPSIZED -> false; default -> true; };
    }

    /** The hull is being docked, unloaded or put away. */
    public boolean landing() { return this == DOCK || this == DISEMBARK || this == STOW; }

    /** A shore visit is in progress (sailing back, docking, unloading or stowing). */
    public boolean shoreVisit() { return this == RETURN || landing(); }

    /** A launch is in progress, from mustering to stepping aboard. */
    public boolean launching() { return this == MUSTER || this == MOOR || this == LOAD || this == EMBARK; }
}
