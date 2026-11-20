package io.github.longshou.mcyukkuri.predator;

import java.util.UUID;
import java.util.function.BooleanSupplier;

final class PredatorFlight {
    static final int OTHER_TASK_LIMIT = 800;
    enum Task {
        NONE, TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, LAND, PATROL, EAT, FOUND, PERCH, FLAP, BRANCH_HOP, FIRST_FLIGHT, BROOD_FLIGHT, DELIVER, VISIT_YOUNG,
        WARN, THREAT_APPROACH, THREAT_DIVE, YIELD_TERRITORY, CLEAR_PAD;

        boolean needsLanding(double energy) {
            return energy < 15 && switch (this) {
                case TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, PATROL, BROOD_FLIGHT, DELIVER, VISIT_YOUNG,
                        THREAT_APPROACH, THREAT_DIVE, CLEAR_PAD -> true;
                default -> false;
            };
        }
        boolean pursuit() { return this==TAKEOFF || this==CHASE || this==DIVE; }
        boolean timedOut(int ticks) {
            return this!=LAND && this!=FOUND && this!=PERCH && ticks>(pursuit()?1200:OTHER_TASK_LIMIT);
        }
    }
    enum NestSurvey {
        UNAVAILABLE, CLAIMED_HOME, FOUNDING_SITE;

        Task chooseWork(BooleanSupplier chooseLeftovers) {
            return switch (this) {
                case UNAVAILABLE -> Task.NONE;
                case CLAIMED_HOME -> chooseLeftovers.getAsBoolean() ? Task.CLEAR_PAD : Task.NONE;
                case FOUNDING_SITE -> Task.FOUND;
            };
        }
    }
    record Visit(UUID child, long groundCell, long standCell) {}

    static long visitRetryAt(long now, int elapsed) {
        // An early failure waits out this attempt. A timed-out attempt gives the same route a full pause.
        int wait = Task.VISIT_YOUNG.timedOut(elapsed) ? OTHER_TASK_LIMIT : OTHER_TASK_LIMIT - elapsed + 1;
        return now + wait;
    }
    private PredatorFlight() {}
}
