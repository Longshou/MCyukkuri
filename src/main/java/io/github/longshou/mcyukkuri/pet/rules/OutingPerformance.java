package io.github.longshou.mcyukkuri.pet.rules;

/** An attached outing lead displaces optional performances, leaving other priorities to their owners. */
public final class OutingPerformance {
    private OutingPerformance() {}

    public static boolean allowed(boolean onEscortLead, boolean optional) {
        return !onEscortLead || !optional;
    }
}
