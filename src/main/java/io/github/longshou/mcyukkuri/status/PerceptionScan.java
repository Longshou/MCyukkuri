package io.github.longshou.mcyukkuri.status;

import java.util.ArrayList;
import java.util.List;

public final class PerceptionScan {
    private static final int INTERVAL = 5;

    private PerceptionScan() {}

    static boolean due(long tick, int entityId) {
        return Math.floorMod(tick, INTERVAL) == Math.floorMod(entityId, INTERVAL);
    }

    /** Holds only current section members, in the section's insertion order. */
    public static final class Section<T> {
        private final ArrayList<T> candidates = new ArrayList<>();

        public void enter(T entity) { candidates.add(entity); }
        public void leave(T entity) { candidates.remove(entity); }
        public List<T> candidates() { return candidates; }
    }
}
