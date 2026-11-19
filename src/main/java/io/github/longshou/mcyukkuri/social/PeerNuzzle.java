package io.github.longshou.mcyukkuri.social;

/** Order of the social choices once both peers have accepted the invitation. */
enum PeerNuzzle {
    LEVEL, RISE, GREET;

    static PeerNuzzle choose(boolean levelReachable, boolean riseContactReachable) {
        return levelReachable ? LEVEL : riseContactReachable ? RISE : GREET;
    }

    /** Compare travel time against the actor's continuous willingness to make the trip. */
    static double effort(double distance, double pace, int affectionNeed) {
        return distance / (pace * (1 + affectionNeed / 100.0));
    }
}
