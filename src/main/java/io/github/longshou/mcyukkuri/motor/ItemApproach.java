package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.StepIn;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The next shared movement to a loose item: supported walk, bank entry, or swimming by species ability. */
public final class ItemApproach {
    private ItemApproach() {}

    public static Action next(YukkuriEntity body, ItemEntity item) {
        if (body.isInWaterOrBubble())
            return AbilityModifier.flag(body, AbilityModifier.SWIM) ? Swim.contact(item) : null;
        if (!item.isInWater()) {
            var stand = SurfaceApproach.floor(body, item.position());
            return stand != null && SurfaceApproach.canApproach(body, stand) ? Walk.stand(stand, 1) : null;
        }
        if (!AbilityModifier.flag(body, AbilityModifier.SWIM)) return null;
        var level = body.level();
        var centre = item.blockPosition();
        Vec3 stand = null, entry = null;
        double best = Double.POSITIVE_INFINITY;
        for (var p : BlockPos.betweenClosed(centre.offset(-8, -1, -8), centre.offset(8, 2, 8))) {
            if (!level.hasChunkAt(p) || !body.life().dryStand(p)) continue;
            var at = Vec3.atBottomCenterOf(p);
            for (var direction : Direction.Plane.HORIZONTAL) {
                var wet = p.relative(direction).below();
                var fluid = level.getFluidState(wet);
                if (!fluid.is(FluidTags.WATER) || !level.getBlockState(wet.above()).getCollisionShape(level, wet.above()).isEmpty()) continue;
                var water = new Vec3(wet.getX() + .5, wet.getY() + fluid.getHeight(level, wet), wet.getZ() + .5);
                double distance = at.distanceToSqr(item.position());
                if (distance >= best || !SurfaceApproach.canApproach(body, at)
                        || level.clip(new ClipContext(water, item.position(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() != HitResult.Type.MISS) continue;
                best = distance; stand = at; entry = water;
            }
        }
        return stand == null ? null : SurfaceApproach.arrived(body, stand) ? new StepIn(entry) : new Walk(stand);
    }
}
