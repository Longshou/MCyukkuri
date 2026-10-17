package io.github.longshou.mcyukkuri.settlement;

import net.minecraft.server.level.ServerLevel;

/**
 * A server-wide allowance of path-heavy searches per tick. A freshly spawned colony used to ask for dozens of
 * plant and path searches in the very same tick; callers that are refused simply look again a few ticks later.
 */
public final class SearchBudget {
    /** Plant and food searches that may run in one server tick, across every member and dimension. */
    public static final int PER_TICK = 3;
    private static int stamp = -1, used;
    /** Server thread only. */
    public static boolean take(ServerLevel level) {
        int now = level.getServer().getTickCount();
        if (now != stamp) { stamp = now; used = 0; }
        if (used >= PER_TICK) return false;
        used++; return true;
    }
    private SearchBudget() {}
}
