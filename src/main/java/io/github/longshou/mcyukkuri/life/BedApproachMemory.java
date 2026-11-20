package io.github.longshou.mcyukkuri.life;

import java.util.HashMap;
import java.util.Map;

/** Retry conditions for assigned shared stands and contested single-bed entrances. */
final class BedApproachMemory {
    static final int WAIT = 1200;
    record Opening(String dimension, long bed, int side) {}
    final Map<Opening,Long> until = new HashMap<>();

    static boolean retryBoarding(boolean hadBlocker, boolean stillBlocks, boolean newStart, boolean stepOpened) {
        return hadBlocker ? !stillBlocks : newStart || stepOpened;
    }

    void refuse(Opening opening, long now) {
        until.put(opening, now + WAIT);
    }

    boolean available(Opening opening, long now) {
        until.values().removeIf(end -> end <= now);
        return !until.containsKey(opening);
    }
}
