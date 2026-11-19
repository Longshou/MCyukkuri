package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.NestResting;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Check the cells and standing room of existing homes against the proposed blocks before changing the world. */
final class ShelterClearance {
    private record Proposal(List<BlockPos> footprint, Function<BlockPos, VoxelShape> shape) {
        boolean takes(BlockPos pos) {
            return footprint.contains(pos) || footprint.contains(pos.below());
        }

        boolean crowds(AABB space) {
            var access = box(space);
            for (var pos : footprint) {
                var cell = box(new AABB(pos));
                if (!cell.intersects(access)) continue;
                var shell = shape.apply(pos).move(pos.getX(), pos.getY(), pos.getZ()).toAabbs();
                if (HomeClearance.blocks(shell.stream().map(ShelterClearance::box).toList(), access)) return true;
            }
            return false;
        }
    }

    static boolean preserves(ServerLevel level, List<BlockPos> footprint, Function<BlockPos, VoxelShape> shape) {
        // A surveyed bed is at most RADIUS from the core. Leave room for a body at that cell or at a nest doorway.
        int reach = NaturalShelter.RADIUS + 2;
        var min = footprint.getFirst().offset(-reach, -reach, -reach);
        var max = footprint.getLast().offset(reach, reach, reach);
        var build = new Proposal(footprint, shape);
        for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
            for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var entity : chunk.getBlockEntities().values()) {
                    var pos = entity.getBlockPos();
                    if (entity.isRemoved() || pos.getX() < min.getX() || pos.getX() > max.getX()
                            || pos.getY() < min.getY() || pos.getY() > max.getY()
                            || pos.getZ() < min.getZ() || pos.getZ() > max.getZ()) continue;
                    if (entity instanceof ShelterBlockEntity home && home.occupants() > 0 && blocks(level, build, home)) return false;
                    if (entity instanceof CareBlockEntity nest && nest.kind() == CareBlock.Kind.NEST && nest.resident() != null
                            && nest.bed() != NestResting.LEDGE && blocks(level, build, nest)) return false;
                    if (entity instanceof HouseNestBlockEntity nest && !nest.destroyed() && build.takes(nest.entrance())) return false;
                }
            }
        }
        return true;
    }

    private static boolean blocks(ServerLevel level, Proposal build, ShelterBlockEntity home) {
        // sound() requires the surveyed floor cells to remain enclosed, dry and free of new blocks.
        if (build.takes(home.getBlockPos()) || build.takes(home.entrance()) || home.beds().stream().anyMatch(build::takes)
                || home.pileCell() != null && build.takes(home.pileCell())) return true;
        if (home.kind().found()) {
            var side = home.facing().getClockWise();
            var entry = Vec3.atBottomCenterOf(home.entrance());
            var mouth = FoundShelter.turnBounds(entry, home.kind().innerReach, home.kind().innerHeight);
            return build.crowds(mouth.expandTowards(side.getStepX() * .5, 0, side.getStepZ() * .5)
                    .expandTowards(-side.getStepX() * .5, 0, -side.getStepZ() * .5));
        }
        // dryStand() also needs room for the resident's full body, potentially outside the bed or entrance block.
        var entry = Vec3.atBottomCenterOf(home.entrance());
        for (var id : home.residentIds()) {
            if (build.crowds(standingBox(level, id, entry))) return true;
            var bed = home.bed(id);
            if (bed != null && build.crowds(standingBox(level, id, Vec3.atBottomCenterOf(bed)))) return true;
        }
        return false;
    }

    private static boolean blocks(ServerLevel level, Proposal build, CareBlockEntity nest) {
        var doorway = NestDoorway.shape(nest);
        var origin = nest.centre();
        // The existing lip and the supported stands beyond it need to stay clear for each resident.
        for (var id : nest.residentIds()) {
            var body = standingBox(level, id, origin);
            double width = body.getXsize();
            var opening = body.move(doorway.offset(0, doorway.outer()));
            var outer = body.move(doorway.offset(0, doorway.outer() + width * .5 + .05));
            var side = doorway.offset(doorway.opening(), 0);
            if (build.crowds(opening.minmax(outer).minmax(outer.move(side)).minmax(outer.move(side.scale(-1))))
                    || build.crowds(body)) return true;
        }
        return false;
    }

    private static AABB standingBox(ServerLevel level, UUID id, Vec3 feet) {
        if (level.getEntity(id) instanceof YukkuriEntity resident) {
            return resident.getBoundingBox().move(feet.subtract(resident.position()));
        }
        // The claim survives the resident unloading. Use the same standard adult as nest placement.
        return LiveBirthGeometry.adultDimensions(YukkuriVariant.REIMU).makeBoundingBox(feet);
    }

    private static HomeClearance.Box box(AABB area) {
        return new HomeClearance.Box(area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ);
    }

    private ShelterClearance() {}
}
