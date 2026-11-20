package io.github.longshou.mcyukkuri.predator;

import java.util.UUID;
import java.util.Map;

final class PredatorFlight {
    static final int OTHER_TASK_LIMIT = 800;
    enum Task {
        NONE, TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, LAND, PATROL, EAT, FOUND, PERCH, FLAP, BRANCH_HOP, FIRST_FLIGHT, BROOD_FLIGHT, DELIVER, VISIT_YOUNG;

        boolean needsLanding(double energy) {
            return energy < 15 && switch (this) {
                case TAKEOFF, CHASE, DIVE, SCAVENGE, RETURN, PATROL, BROOD_FLIGHT, DELIVER, VISIT_YOUNG -> true;
                default -> false;
            };
        }
        boolean pursuit() { return this==TAKEOFF || this==CHASE || this==DIVE; }
        boolean timedOut(int ticks) {
            return this!=LAND && this!=FOUND && this!=PERCH && ticks>(pursuit()?1200:OTHER_TASK_LIMIT);
        }
    }
    record Visit(UUID child, long groundCell) {
        boolean available(Map<UUID,Long> refused) { return !Long.valueOf(groundCell).equals(refused.get(child)); }
        void timedOut(Map<UUID,Long> refused) { refused.put(child, groundCell); }
    }
    private PredatorFlight() {}
}
