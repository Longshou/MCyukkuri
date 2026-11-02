package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.Detour;
import io.github.longshou.mcyukkuri.motor.geom.Doorway;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Physical nest openings, independent of residency. Navigation and exact approaches share these rim footprints. */
public final class NestDoorway {
    private NestDoorway() {}
    private static final Map<Block, Doorway> SHAPES = new HashMap<>();

    public static CareBlockEntity bed(YukkuriEntity actor, BlockPos pos) {
        var level = actor.level();
        if (!level.hasChunkAt(pos)) return null;
        var state = level.getBlockState(pos);
        var origin = state.getBlock() instanceof LargeNestBlock ? LargeNestBlock.origin(state, pos) : pos;
        return level.getBlockEntity(origin) instanceof CareBlockEntity bed && bed.kind() == CareBlock.Kind.NEST
                && bed.bed() != NestResting.LEDGE ? bed : null;
    }

    /** The opening follows the authored collision shell: low front versus high back, shoulders below half rim height. */
    public static Doorway shape(CareBlockEntity bed) {
        return SHAPES.computeIfAbsent(bed.getBlockState().getBlock(), block -> {
            var centre = bed.centre();
            var boxes = new ArrayList<AABB>();
            for (int part = 0; part < (bed.largeNest() ? 4 : 1); part++) {
                var at = bed.getBlockPos().offset(part & 1, 0, part >> 1);
                for (var box : bed.getLevel().getBlockState(at).getCollisionShape(bed.getLevel(), at).toAabbs())
                    boxes.add(box.move(at.getX() - centre.x, -centre.y + at.getY(), at.getZ() - centre.z));
            }
            double inner = bed.bed().box(), outer = boxes.stream().mapToDouble(b -> b.maxZ).max().orElseThrow();
            double north = crest(boxes, 0, inner, -1), south = crest(boxes, 0, inner, 1);
            int side = north < south ? -1 : 1;
            double top = boxes.stream().mapToDouble(b -> b.maxY).max().orElseThrow();
            double threshold = (height(boxes, 0, 0) + top) * .5, opening = 0;
            // The authored meshes use at least quarter-pixel collision cells. Sample the whole rim, not just its inside lip.
            for (double x = 0; x < inner; x += 1.0 / 64) {
                if (crest(boxes, x, inner, side) > threshold) break;
                opening = x;
            }
            return new Doorway(inner, outer, opening, side);
        });
    }

    private static double crest(List<AABB> boxes, double x, double inner, int side) {
        return boxes.stream().filter(b -> b.minX <= x && b.maxX >= x
                && (side > 0 ? b.maxZ >= inner : b.minZ <= -inner)).mapToDouble(b -> b.maxY).max().orElse(0);
    }

    private static double height(List<AABB> boxes, double x, double z) {
        return boxes.stream().filter(b -> b.minX <= x && b.maxX >= x && b.minZ <= z && b.maxZ >= z)
                .mapToDouble(b -> b.maxY).max().orElseThrow();
    }

    public static List<Detour.Box> walls(YukkuriEntity actor, AABB area) {
        var result = new ArrayList<Detour.Box>();
        var seen = new HashSet<BlockPos>();
        for (var at : BlockPos.betweenClosed(BlockPos.containing(area.minX - 1, actor.getY() - .5, area.minZ - 1),
                BlockPos.containing(area.maxX + 1, actor.getY(), area.maxZ + 1))) {
            var bed = bed(actor, at);
            if (bed == null || !seen.add(bed.getBlockPos())) continue;
            var centre = bed.centre();
            result.addAll(shape(bed).walls(bed.getBlockPos().asLong() * 8, centre.x, centre.z));
        }
        return result;
    }

    /** Observe a real ground crossing; planning never emits a crossing event. */
    public static void observe(YukkuriEntity actor, Vec3 from, Vec3 to) {
        if (from.distanceToSqr(to) < 1e-8) return;
        var seen = new HashSet<BlockPos>();
        for (var pos : BlockPos.betweenClosed(actor.blockPosition().offset(-1, -1, -1), actor.blockPosition().offset(1, 0, 1))) {
            var bed = bed(actor, pos);
            if (bed == null || !seen.add(bed.getBlockPos())) continue;
            var centre = bed.centre(); var door = shape(bed);
            double x0 = from.x - centre.x, z0 = from.z - centre.z, x1 = to.x - centre.x, z1 = to.z - centre.z;
            boolean was = Math.max(Math.abs(x0), Math.abs(z0)) < door.inner();
            boolean now = Math.max(Math.abs(x1), Math.abs(z1)) < door.inner();
            if (was == now) continue;
            double t = (door.side() * door.inner() - z0) / (z1 - z0), x = x0 + t * (x1 - x0);
            boolean opening = t >= 0 && t <= 1 && Math.abs(x) + Occupancy.foot(actor.getBbWidth()) <= door.opening() + .02;
            GameplayLog.event(actor, "home", "bed crossing nest={} direction={} side={} opening={} from={} at={}", bed.getBlockPos(),
                    now ? "in" : "out", door.side() < 0 ? "north" : "south", opening, GameplayLog.at(from), GameplayLog.at(to));
        }
    }

    /** The closest body-sized point outside the actual opening, with no occupant or ownership reservation. */
    public static Vec3 stand(YukkuriEntity actor, CareBlockEntity bed) {
        var shape = shape(bed);
        double room = shape.opening() - Occupancy.foot(actor.getBbWidth());
        if (room < 0) return null;
        var centre = bed.centre();
        double preferred = Math.clamp(actor.getX() - centre.x, -room, room);
        for (double x : new double[]{preferred, -room, room, 0}) {
            var at = SurfaceApproach.floor(actor, centre.add(x, 0, shape.side() * (shape.outer() + actor.getBbWidth() * .5 + .05)));
            if (at != null && actor.life().covered(BlockPos.containing(at))) return at;
        }
        return null;
    }
}
