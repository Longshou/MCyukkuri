package io.github.longshou.mcyukkuri.settlement.site;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * One named place of a site. {@code anchor} meaning: HOME/HALL bed centre (the door is {@code anchor.relative(facing)}),
 * GRANARY the door step, STORE the forage pile (facing = side the mouth stands on), PLAZA the gathering cell,
 * WATER a pool water block, LATRINE a toilet block, LOOKOUT the standing cell on top, NURSERY the bed centre.
 */
public record SiteSlot(Kind kind, BlockPos anchor, Direction facing, int capacity) {
    public enum Kind { HOME, HALL, GRANARY, STORE, PLAZA, WATER, LATRINE, LOOKOUT, NURSERY }
    public boolean dwelling() { return kind == Kind.HOME || kind == Kind.HALL; }
    public BlockPos door() { return anchor.relative(facing); }
}
