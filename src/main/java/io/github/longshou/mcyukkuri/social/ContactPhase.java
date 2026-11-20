package io.github.longshou.mcyukkuri.social;

/** A pose runs on its clip clock. Keep the goal through that clock's last tick so it can hand over or finish. */
enum ContactPhase {
    APPROACH(true), GREETING(false), CLOSE(true), NUZZLE(false);

    private final boolean usesApproachBudget;

    ContactPhase(boolean usesApproachBudget) { this.usesApproachBudget = usesApproachBudget; }

    static int walkTicks(double distance, double reach, double pace) {
        return (int) Math.ceil(Math.max(0, distance - reach) / pace);
    }

    static boolean answerOpen(long now, long deadline, boolean playing) { return now <= deadline || playing; }

    boolean canTick(int elapsed, int budget) { return !usesApproachBudget || elapsed < budget; }

    int elapsedAfterTick(int elapsed) { return usesApproachBudget ? elapsed + 1 : elapsed; }
}
