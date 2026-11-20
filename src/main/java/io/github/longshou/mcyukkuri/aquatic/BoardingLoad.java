package io.github.longshou.mcyukkuri.aquatic;

import java.util.UUID;

/** The inputs to a shore child's refused boarding, in units of this hull's size and capacity. */
record BoardingLoad(UUID hull, double loadRatio, double relativeSize, double limit) {
    boolean stillFull(BoardingLoad now) {
        return hull.equals(now.hull) && now.loadRatio >= loadRatio && now.relativeSize >= relativeSize && now.limit <= limit;
    }
}
