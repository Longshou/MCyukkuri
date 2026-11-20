package io.github.longshou.mcyukkuri.family;

/** A cool parent's reply can follow the departure walk into ordinary travel. */
enum FarewellWindow {
    EXCHANGE, DEPARTURE, ONWARD, CLOSED;

    static boolean inRange(double distanceSquared) { return distanceSquared <= 16 * 16; }

    boolean open(double distanceSquared) {
        // A child that is still approaching can come into range to say goodbye.
        return this == EXCHANGE || this != CLOSED && inRange(distanceSquared);
    }

    boolean canAnswer(boolean visible, boolean walkingAway) {
        return this != CLOSED && visible && (this != ONWARD || walkingAway);
    }
}
