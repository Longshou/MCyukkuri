package io.github.longshou.mcyukkuri.family;

/** A cool parent's reply can follow the departure walk into ordinary travel. */
enum FarewellWindow {
    EXCHANGE, DEPARTURE, HANDOFF, ONWARD, CLOSED;

    static boolean inRange(double distanceSquared) { return distanceSquared <= 16 * 16; }

    FarewellWindow observed(double distanceSquared, boolean visible, boolean walkingAway) {
        if (this == EXCHANGE) return this; // The approaching child can still come into sight.
        return visible && inRange(distanceSquared) && (this != ONWARD || walkingAway) ? this : CLOSED;
    }
}
