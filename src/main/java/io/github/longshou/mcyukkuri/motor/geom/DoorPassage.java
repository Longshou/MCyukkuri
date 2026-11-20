package io.github.longshou.mcyukkuri.motor.geom;

/** The frame occupies [0, 1] along the direction of travel. */
public final class DoorPassage {
    public enum Escort { WAIT, CLEARED, LAGGING }
    private DoorPassage() {}

    public static boolean cleared(double nearEdge, double farEdge, boolean positive) {
        return positive ? nearEdge >= 1 : farEdge <= 0;
    }

    /** Wait on the second far-side cell so the first is available to the follower. */
    public static double stand(boolean positive, boolean keeper) {
        double distance = keeper ? 1.5 : .5;
        return positive ? 1 + distance : -distance;
    }

    /** A body with its centre inside the frame goes first. Use stable ordering before either enters. */
    public static boolean yields(double own, double other, boolean otherFirst) {
        boolean inside = own >= 0 && own < 1, otherInside = other >= 0 && other < 1;
        return inside == otherInside ? otherFirst : otherInside;
    }

    public static boolean close(boolean occupied, boolean needed, long now, long until) {
        return !occupied && (!needed || now >= until);
    }

    public static Escort escort(boolean trailing, long now, long until) {
        return !trailing ? Escort.CLEARED : now < until ? Escort.WAIT : Escort.LAGGING;
    }
}
