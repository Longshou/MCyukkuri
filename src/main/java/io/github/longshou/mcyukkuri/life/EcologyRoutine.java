package io.github.longshou.mcyukkuri.life;

/** A component's existing passive clock, dispatched at its original lifecycle slot.
 * Goal execution and interruption remain with the component and its original Goal.
 */
public interface EcologyRoutine {
    /** Only the old clock-entry guards; do not add a shared no-AI or exclusive-species filter. */
    boolean supportsPassiveTick();

    /** Called by EcologyRoutines after the entry guards, with the component's own ordering intact. */
    void passiveTick();
}
