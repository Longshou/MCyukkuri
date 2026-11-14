package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/** Queries about sealed dens and the release of a screen when a resident leaves. */
public final class DenBarrier {
    private DenBarrier() {}

    /** Whether the mouth cell of this den holds a screen. */
    public static boolean sealed(ShelterBlockEntity den) {
        return den.getLevel() != null && den.getLevel().getBlockState(den.entrance()).getBlock() instanceof DenBarrierBlock;
    }

    /** Departure opens the existing screen through the block entity's refund and removal transaction. */
    static void release(ShelterBlockEntity den, String reason, YukkuriEntity resident) {
        if (den.kind() != ShelterBlock.Kind.BURROW) return;
        var level = den.getLevel();
        for (var side : Direction.Plane.HORIZONTAL) {
            var cell = den.getBlockPos().relative(side);
            if (level.hasChunkAt(cell) && level.getBlockEntity(cell) instanceof DenBarrierBlockEntity barrier)
                barrier.releaseForDeparture(den, reason, resident);
        }
    }

    /** The sealed natural den this individual is inside (resident or not), or null. Only loaded dens near it are looked at. */
    public static ShelterBlockEntity shielding(YukkuriEntity target) {
        if (!(target.level() instanceof ServerLevel level)) return null;
        BlockPos at = target.blockPosition();
        for (var den : LoadedShelters.within(level, at.offset(-NaturalShelter.RADIUS, -3, -NaturalShelter.RADIUS),
                at.offset(NaturalShelter.RADIUS, 3, NaturalShelter.RADIUS)))
            if (den.kind() == ShelterBlock.Kind.BURROW && den.contains(at) && sealed(den)) return den;
        return null;
    }
}
