package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/**
 * Pure pace law for a human owner leading companions on an outing.
 * <p>
 * Vanilla ground speed scales with the square of {@code attribute * request}, so the cruise request scales with the square
 * root of the slowest companion's {@link AbilityMath.Values#walkSpeed()} share relative to the reference adult. Lagging
 * eases smoothly down to a higher lag floor instead of a binary crawl, and halts before a vanilla lead reaches its 6-block
 * elastic drag threshold.
 */
public final class OutingPace {
    public enum Mode { CRUISE, SLOW, WAIT }

    /** Reference adult walk speed (blocks/tick) from {@link AbilityMath.Coefficients#DEFAULTS}. */
    public static final double REF_WALK = AbilityMath.Coefficients.DEFAULTS.walkSpeed();
    /** Base stroll requests by owner attitude (ordinal: 0=CRUEL, 1=CARING, 2=NEUTRAL), within 0.75-0.90. */
    public static final double BASE_CRUEL = .88, BASE_CARING = .80, BASE_NEUTRAL = .85;
    public static final double INDIVIDUAL_SPREAD = .02;
    public static final double MIN_REQUEST = .48, MAX_REQUEST = .90;
    public static final double BASE_LAG_FLOOR = .55, MIN_LAG_FLOOR = .42;
    public static final double MIN_SHARE = .35, MAX_SHARE = 1.10;
    /** Vanilla elastic leash pull starts at 6.0 blocks; cap leashed follow comfort and wait distance safely below it. */
    public static final double LEASH_ELASTIC_DIST = 6.0;
    public static final double LEAD_COMFORT_CAP = 3.6;
    public static final double LEAD_WAIT_CAP = 5.1;
    public static final double UNLEASHED_WAIT_BAND = 2.5;
    public static final double WAIT_RELEASE_MARGIN = .5;
    public static final double RAMP_STEP = .03;

    public record Companion(double distance, double outerDistance, double walkSpeed, boolean onLead) {}

    public record Sample(Mode mode, double target, double cruise, double floor, double slowestWalk,
                         double maxLeashedGap, int laggingIndex, String cause) {}

    private OutingPace() {}

    /** Owner's base stroll request from attitude (0=CRUEL, 1=CARING, 2=NEUTRAL) and deterministic individual offset in [-1, 1]. */
    public static double baseRequest(int attitudeOrdinal, double individualUnit) {
        double base = switch (attitudeOrdinal) {
            case 0 -> BASE_CRUEL;
            case 2 -> BASE_NEUTRAL;
            default -> BASE_CARING;
        };
        return Math.clamp(base + INDIVIDUAL_SPREAD * Math.clamp(individualUnit, -1, 1), .75, MAX_REQUEST);
    }

    /** Ratio of the slowest companion's walk speed to a reference adult, clamped to a sensible interval. */
    public static double companionShare(double slowestWalk) {
        return Math.clamp(slowestWalk / REF_WALK, MIN_SHARE, MAX_SHARE);
    }

    /** Cruise speed modifier for the owner, scaled by sqrt(share) so ground speed scales linearly with companion walk speed. */
    public static double cruiseRequest(double baseRequest, double slowestWalk) {
        return Math.clamp(baseRequest * Math.sqrt(companionShare(slowestWalk)), MIN_REQUEST, MAX_REQUEST);
    }

    /** Lag floor request: rises from the old 0.30 to ~0.55 for healthy adults and scales down for frail/baby companions. */
    public static double lagFloor(double cruise, double slowestWalk) {
        return Math.min(cruise, Math.max(MIN_LAG_FLOOR, BASE_LAG_FLOOR * Math.sqrt(companionShare(slowestWalk))));
    }

    /** Distance past which a companion is considered trailing; capped below the 6-block elastic drag when on lead. */
    public static double followComfort(double outerDistance, boolean onLead) {
        return onLead ? Math.min(outerDistance, LEAD_COMFORT_CAP) : outerDistance;
    }

    /** Distance at which the owner stops and waits rather than stretching the lead taut. */
    public static double waitDistance(double comfort, boolean onLead) {
        return onLead ? LEAD_WAIT_CAP : comfort + UNLEASHED_WAIT_BAND;
    }

    /** Theoretical dry-ground speed (blocks/s) for a vanilla Mob with {@code attribute} and {@code request}. */
    public static double groundSpeedPerSecond(double attribute, double request) {
        double control = attribute * Math.max(0, request);
        return control * control * Gait.GROUND_TRAVEL * 20;
    }

    public static Sample evaluate(double baseRequest, List<Companion> companions, boolean currentlyWaiting) {
        if (companions.isEmpty()) {
            double cruise = Math.clamp(baseRequest, MIN_REQUEST, MAX_REQUEST);
            return new Sample(Mode.CRUISE, cruise, cruise, Math.min(cruise, BASE_LAG_FLOOR), REF_WALK, 0, -1, "cruise");
        }
        double slowest = Double.POSITIVE_INFINITY;
        double maxLeashed = 0;
        for (var c : companions) {
            if (c.walkSpeed() < slowest) slowest = c.walkSpeed();
            if (c.onLead() && c.distance() > maxLeashed) maxLeashed = c.distance();
        }
        double cruise = cruiseRequest(baseRequest, slowest);
        double floor = lagFloor(cruise, slowest);

        boolean wait = false;
        boolean tautWait = false;
        double worstFrac = 0;
        int laggingIndex = -1;
        double worstOver = 0;
        for (int i = 0; i < companions.size(); i++) {
            var c = companions.get(i);
            double comfort = followComfort(c.outerDistance(), c.onLead());
            double waitDist = waitDistance(comfort, c.onLead());
            double releaseDist = Math.max(comfort, waitDist - WAIT_RELEASE_MARGIN);
            if (c.distance() >= waitDist || currentlyWaiting && c.distance() > releaseDist) {
                wait = true;
                if (c.onLead() && c.distance() >= LEAD_WAIT_CAP - WAIT_RELEASE_MARGIN) tautWait = true;
                if (c.distance() - comfort > worstOver || laggingIndex < 0) {
                    worstOver = c.distance() - comfort;
                    laggingIndex = i;
                }
            } else if (c.distance() > comfort) {
                double frac = Math.clamp((c.distance() - comfort) / Math.max(.25, waitDist - comfort), 0, 1);
                if (frac > worstFrac) {
                    worstFrac = frac;
                    if (!wait) laggingIndex = i;
                }
            }
        }
        if (wait) {
            return new Sample(Mode.WAIT, 0, cruise, floor, slowest, maxLeashed, laggingIndex,
                    tautWait ? "taut_wait" : "lag_wait");
        }
        if (worstFrac > 0) {
            double target = cruise - worstFrac * (cruise - floor);
            return new Sample(Mode.SLOW, target, cruise, floor, slowest, maxLeashed, laggingIndex, "lag");
        }
        String cause = companionShare(slowest) < .92 ? "companion_pace" : "cruise";
        return new Sample(Mode.CRUISE, cruise, cruise, floor, slowest, maxLeashed, -1, cause);
    }

    /** Advances the current navigation speed modifier toward {@code sample.target()}, halting immediately on WAIT. */
    public static double step(double currentPace, Sample sample) {
        if (sample.mode() == Mode.WAIT) return 0;
        if (currentPace <= 0) return Math.min(sample.floor(), sample.target());
        return currentPace + Math.clamp(sample.target() - currentPace, -RAMP_STEP, RAMP_STEP);
    }
}
