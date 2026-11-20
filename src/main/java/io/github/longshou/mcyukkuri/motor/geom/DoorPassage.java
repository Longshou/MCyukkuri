package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.Function;

/** The frame occupies [0, 1] along the direction of travel. */
public final class DoorPassage {
    public enum Escort { WAIT, CLEARED, LAGGING }
    private DoorPassage() {}

    public static boolean cleared(double nearEdge, double farEdge, boolean positive) {
        return positive ? nearEdge >= 1 : farEdge <= 0;
    }

    public static boolean overlaps(double low, double high) { return low < 1 && high > 0; }

    /** Put the trailing edge beyond the frame even when steering stops within its arrival tolerance. */
    public static double stopEdge(boolean positive, double radius, double tolerance) {
        return positive ? 1 + radius + tolerance : -radius - tolerance;
    }

    /** The saved crossing direction wins when a finished route is no longer available. */
    public static boolean stopSide(Boolean routeSide, double target, double centre) {
        return routeSide != null ? routeSide : (target == .5 ? centre : target) >= .5;
    }

    public static <T> T stopStand(boolean positive, Function<Boolean, T> available) {
        T stand = available.apply(positive);
        return stand != null ? stand : available.apply(!positive);
    }

    /** Wait on the second far-side cell so the first is available to the follower. */
    public static double stand(boolean positive, boolean keeper) {
        double distance = keeper ? 1.5 : .5;
        return positive ? 1 + distance : -distance;
    }

    /** The occupant has priority until its trailing edge clears. Break ties by stable ordering. */
    public static boolean yields(boolean inFrame, boolean otherInFrame, boolean otherFirst) {
        return inFrame == otherInFrame ? otherFirst : otherInFrame;
    }

    public static boolean awaitingPassage(boolean approaching, boolean inFrame, boolean cleared) {
        return !cleared && (approaching || inFrame);
    }

    public static boolean close(boolean occupied, boolean needed, long now, long until) {
        return !occupied && (!needed || now >= until);
    }

    public static Escort escort(boolean trailing, long now, long until) {
        return !trailing ? Escort.CLEARED : now < until ? Escort.WAIT : Escort.LAGGING;
    }
}
