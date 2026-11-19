package io.github.longshou.mcyukkuri.social;

/** Order of the social choices once both peers have accepted the invitation. */
enum PeerNuzzle {
    LEVEL, RISE, GREET;

    static PeerNuzzle choose(boolean levelReachable, boolean riseContactReachable) {
        return levelReachable ? LEVEL : riseContactReachable ? RISE : GREET;
    }

    interface Display {
        void show(String clip, int frame, float spacing);
    }

    static String clip(boolean mover) { return mover ? "snuggle_right" : "snuggle_left"; }

    static int contactAfter(int elapsed, boolean touching, float spacing, Display mover, Display anchor) {
        if (!touching) return 0;
        anchor.show(clip(false), elapsed, spacing);
        mover.show(clip(true), elapsed, spacing);
        return elapsed + 1;
    }

    /** Compare travel time against the actor's continuous willingness to make the trip. */
    static double effort(double distance, double pace, int affectionNeed) {
        return distance / (pace * (1 + affectionNeed / 100.0));
    }
}
