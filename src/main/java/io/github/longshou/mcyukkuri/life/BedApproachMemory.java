package io.github.longshou.mcyukkuri.life;

import java.util.HashMap;
import java.util.Map;

/** Failed single-bed entrances are avoided for the same minute as a lost contest. */
final class BedApproachMemory {
    static final int WAIT = 1200;
    record Opening(String dimension, long bed, int side) {}
    final Map<Opening,Long> until = new HashMap<>();

    void refuse(Opening opening, long now) {
        until.put(opening, now + WAIT);
    }

    boolean available(Opening opening, long now) {
        until.values().removeIf(end -> end <= now);
        return !until.containsKey(opening);
    }
}
