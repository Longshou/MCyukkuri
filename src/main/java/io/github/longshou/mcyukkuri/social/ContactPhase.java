package io.github.longshou.mcyukkuri.social;

/** A pose runs on its clip clock. Keep the goal through that clock's last tick so it can hand over or finish. */
enum ContactPhase {
    APPROACH(true), GREETING(false), CLOSE(true), NUZZLE(false);

    private final boolean usesApproachBudget;

    ContactPhase(boolean usesApproachBudget) { this.usesApproachBudget = usesApproachBudget; }

    boolean canTick(int elapsed, int budget) { return !usesApproachBudget || elapsed < budget; }

    int elapsedAfterTick(int elapsed) { return usesApproachBudget ? elapsed + 1 : elapsed; }
}
