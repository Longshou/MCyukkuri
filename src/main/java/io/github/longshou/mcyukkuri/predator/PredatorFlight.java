package io.github.longshou.mcyukkuri.predator;

import java.util.UUID;

final class PredatorFlight {
    static final int OTHER_TASK_LIMIT = 800;
    enum Task {
        NONE, TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, LAND, PATROL, EAT, FOUND, PERCH, FLAP, BRANCH_HOP, FIRST_FLIGHT, BROOD_FLIGHT, DELIVER, VISIT_YOUNG,
        WARN, THREAT_APPROACH, THREAT_DIVE, YIELD_TERRITORY;

        boolean needsLanding(double energy) {
            return energy < 15 && switch (this) {
                case TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, PATROL, BROOD_FLIGHT, DELIVER, VISIT_YOUNG, THREAT_APPROACH, THREAT_DIVE -> true;
                default -> false;
            };
        }
        boolean pursuit() { return this==TAKEOFF || this==CHASE || this==DIVE; }
        boolean timedOut(int ticks) {
            return this!=LAND && this!=FOUND && this!=PERCH && ticks>(pursuit()?1200:OTHER_TASK_LIMIT);
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
