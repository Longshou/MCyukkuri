package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

/** A single pane between two supported floor cells, with enough real room for this body after it breaks. */
public record PaneOpening(BlockPos pane, Vec3 stand, BlockPos beyond) {
    /** Bounded like home enclosure discovery: 512 cells within 15 blocks; open terrain is not a room. */
    public static List<PaneOpening> find(YukkuriEntity body, BlockPos room, boolean exit, boolean empty) {
        var level = body.level();
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        var candidates = new ArrayList<PaneOpening>();
        if (!floor(body,room)) return List.of();
        seen.add(room); queue.add(room);
        while (!queue.isEmpty()) {
            var cell = queue.remove();
            if (empty && !level.getEntitiesOfClass(LivingEntity.class, new AABB(cell), e -> e != body && e.isAlive()).isEmpty())
                return List.of();
            for (var direction : Direction.Plane.HORIZONTAL) {
                var next = cell.relative(direction);
                if (floor(body,next)) {
                    if (seen.add(next)) {
                        if (seen.size() > 512 || Math.abs(next.getX()-room.getX()) > 15 || Math.abs(next.getZ()-room.getZ()) > 15)
                            return List.of();
                        queue.add(next);
                    }
                } else if (level.getBlockState(next).is(Tags.Blocks.GLASS_PANES)) {
                    var opening = at(body,next,exit ? direction.getOpposite() : direction);
                    if (opening != null) candidates.add(opening);
                }
            }
        }
        return candidates.stream().filter(p -> !seen.contains(p.beyond()) || !exit)
                .sorted(Comparator.comparingDouble(p -> body.distanceToSqr(p.stand()))).toList();
    }

    /** From a known pane and its near-side normal, check the ram stand and the body-sized crossing. */
    public static PaneOpening at(YukkuriEntity body, BlockPos pane, Direction normal) {
        var level = body.level();
        var approach = pane.relative(normal);
        var beyond = pane.relative(normal.getOpposite());
        if (!floor(body,approach) || !floor(body,beyond) || !body.life().reachable(approach,false)) return null;
        var shape = level.getBlockState(pane).getCollisionShape(level,pane).bounds();
        double extent = normal.getAxis() == Direction.Axis.X ? shape.getXsize() : shape.getZsize();
        var stand = Vec3.atBottomCenterOf(pane).add(Vec3.atLowerCornerOf(normal.getNormal())
                .scale(Bash.standOff(body) + extent/2 + Walk.STAND_ARRIVED/2));
        if (!level.noCollision(body,body.getBoundingBox().move(stand.subtract(body.position())))) return null;
        var crossing = body.getBoundingBox().move(Vec3.atBottomCenterOf(pane).subtract(body.position()));
        for (var p : BlockPos.betweenClosed(BlockPos.containing(crossing.minX,crossing.minY,crossing.minZ),
                BlockPos.containing(crossing.maxX,crossing.maxY,crossing.maxZ))) {
            if (p.equals(pane)) continue;
            for (var obstacle : level.getBlockState(p).getCollisionShape(level,p).toAabbs())
                if (obstacle.move(p).intersects(crossing)) return null;
        }
        return new PaneOpening(pane.immutable(),stand,beyond);
    }

    /** Room topology ignores occupants so the separate empty-room test cannot skip their blocked cells. */
    private static boolean floor(YukkuriEntity body, BlockPos pos) {
        var level=body.level();
        var box=body.getBoundingBox().move(Vec3.atBottomCenterOf(pos).subtract(body.position()));
        return level.hasChunkAt(pos) && level.getFluidState(pos).isEmpty() && level.getFluidState(pos.below()).isEmpty()
                && level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP)
                && !level.getBlockCollisions(body,box).iterator().hasNext();
    }
}
