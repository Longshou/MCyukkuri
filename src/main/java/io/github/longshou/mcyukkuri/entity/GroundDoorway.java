package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.motor.geom.DoorGap;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A cross section through the open leaf for a vanilla DOOR_OPEN path node. */
record GroundDoorway(boolean acrossX, DoorGap gap, double entry, double exit) {
    static GroundDoorway at(Mob body, int x, int y, int z) {
        int cells = Mth.floor(body.getBbWidth() + 1);
        var level = body.level();
        for (var pos : BlockPos.betweenClosed(x, y, z, x + cells - 1, y, z + cells - 1)) {
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || !state.getValue(DoorBlock.OPEN)) continue;
            boolean acrossX = state.getValue(DoorBlock.FACING).getAxis() == Direction.Axis.Z;
            double lateral = (acrossX ? x : z) + cells * .5, along = acrossX ? pos.getZ() : pos.getX();
            double radius = body.getBbWidth() * .5, span = radius + 1.5;
            double bottom = y + 1e-7, top = y + body.getBbHeight() - 1e-7;
            var slice = acrossX ? new AABB(lateral-span, bottom, along, lateral+span, top, along+1)
                    : new AABB(along, bottom, lateral-span, along+1, top, lateral+span);
            var solids = new ArrayList<DoorGap.Span>();
            for (var shape : level.getBlockCollisions(body, slice))
                for (var box : shape.toAabbs())
                    if (box.intersects(slice)) solids.add(acrossX ? new DoorGap.Span(box.minX, box.maxX)
                            : new DoorGap.Span(box.minZ, box.maxZ));
            var gap = DoorGap.fit(lateral, radius, solids, 1.5);
            return gap == null ? null : new GroundDoorway(acrossX, gap, along-radius, along+1+radius);
        }
        return null;
    }

    /** The next step, or null once the body has reached the far side (or a route ending in the gap). */
    Vec3 aim(Vec3 from, Vec3 node, Vec3 next) {
        double along = acrossX ? node.z : node.x, after = acrossX ? next.z : next.x;
        double target = after > along ? Math.max(along, exit) : after < along ? Math.min(along, entry) : along;
        double fromAlong = acrossX ? from.z : from.x, lateral = acrossX ? from.x : from.z;
        double nodeLateral = acrossX ? node.x : node.z;
        if (gap.reached(lateral, fromAlong, nodeLateral, target, after - along)) return null;
        double lane = gap.lane(nodeLateral);
        double steer = gap.aimAlong(lateral, fromAlong, entry, exit, target);
        return acrossX ? new Vec3(lane, node.y, steer) : new Vec3(steer, node.y, lane);
    }
}
