package io.github.longshou.mcyukkuri.subspecies;

/** A branch's handoff intent, never a running task, priority, or saved state.
 * AquaticLife executes only the ordered cleanup explicitly listed for each intent.
 */
enum BoatTask {
    TRIP_ENDED,
    LANDING, SHORE_ALARM, FIRST_VOYAGE, UNSTEADY, BEACHED_RESCUE,
    AFLOAT_ALARM,
    PADDLE_UNAVAILABLE, FORAGE_UNAVAILABLE,
    TRANSPORT, PASSENGER_ACTIVITY, PADDLE,
    TOWED, LABOR, EATING, URGENT_FOOD, DISTRESS,
    DRINKING, FAMILY_WAIT, NO_PADDLE, SELF_FEED, GATHERING, REST,
    FAMILY_ROUTE, CROWD_GUARD, FAMILY_REACTION,
    FAMILY_CARE_YIELD, RESCUE,
    TOILET, FLOATING_FOOD, SHORE_FORAGE, PADDLE_HANDOFF, CARE,
    LAND, STOP
}
