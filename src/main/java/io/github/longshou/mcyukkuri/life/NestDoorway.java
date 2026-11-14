package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Physical nest openings, independent of residency. Routes end outside; a straight step crosses the low lip. */
public final class NestDoorway {
    private NestDoorway() {}
    /**
     * The opening in the nest's own frame, measured from {@link CareBlockEntity#centre()}: depth runs toward the entrance
     * ({@code facing}), lateral along its edge. {@code inner}: where the level floor ends toward the entrance; {@code outer}:
     * the shell's reach on the entrance side; {@code opening}: half width of the low lip; {@code reach}: the shell's widest
     * reach on any side (a conservative square for route checks).
     */
    public record Doorway(Direction facing, double inner, double outer, double opening, double reach) {
        public double lateral(Vec3 offset) { return -offset.x * facing.getStepZ() + offset.z * facing.getStepX(); }
        public double depth(Vec3 offset) { return offset.x * facing.getStepX() + offset.z * facing.getStepZ(); }
        /** World offset from the origin of a point {@code lateral} along the edge and {@code depth} toward the entrance. */
        public Vec3 offset(double lateral, double depth) {
            return new Vec3(-lateral * facing.getStepZ() + depth * facing.getStepX(), 0, lateral * facing.getStepX() + depth * facing.getStepZ());
        }
    }
    private static final Map<BlockState, Doorway> SHAPES = new ConcurrentHashMap<>();
    private static final Predicate<BlockState> NEST = state -> state.getBlock() instanceof CareBlock care
            && care.kind() == CareBlock.Kind.NEST;
    // Keep the broad phase conservative around vanilla AABB.clip's 1e-7 face tolerance.
    private static final double CLIP_PADDING = 1e-6;

    public static CareBlockEntity bed(YukkuriEntity actor, BlockPos pos) {
        var level = actor.level();
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        return chunk == null ? null : bed(level, chunk.getBlockState(pos), pos);
    }

    private static CareBlockEntity bed(Level level, BlockState state, BlockPos pos) {
        if (!NEST.test(state)) return null;
        return nest(level, state, pos);
    }

    private static CareBlockEntity nest(Level level, BlockState state, BlockPos pos) {
        var origin = state.getBlock() instanceof LargeNestBlock ? LargeNestBlock.origin(state, pos) : pos;
        return level.getBlockEntity(origin) instanceof CareBlockEntity bed && bed.kind() == CareBlock.Kind.NEST
                && bed.bed() != NestResting.LEDGE ? bed : null;
    }

    /** The opening follows the authored collision shell turned into the nest's frame (x lateral, z depth toward the entrance):
     *  the low lip is where the rim beyond the level floor stays below half rim height on both sides of the middle. */
    public static Doorway shape(CareBlockEntity bed) {
        return shape(bed.getBlockState(), bed.entrance(), bed.entranceReach());
    }

    private static Doorway shape(BlockState state, Direction facing, double inner) {
        return SHAPES.computeIfAbsent(state, key -> {
            boolean large = state.getBlock() instanceof LargeNestBlock;
            double centre = large ? 1 : .5;
            var frame = new Doorway(facing, 0, 0, 0, 0);
            var boxes = new ArrayList<AABB>();
            double reach = 0;
            for (int part = 0; part < (large ? 4 : 1); part++) {
                var quarter = large ? state.setValue(LargeNestBlock.PART, part) : state;
                for (var box : quarter.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
                    var a = new Vec3(box.minX + (part & 1) - centre, 0, box.minZ + (part >> 1) - centre);
                    var b = new Vec3(box.maxX + (part & 1) - centre, 0, box.maxZ + (part >> 1) - centre);
                    double l0 = frame.lateral(a), l1 = frame.lateral(b), d0 = frame.depth(a), d1 = frame.depth(b);
                    boxes.add(new AABB(Math.min(l0, l1), box.minY, Math.min(d0, d1),
                            Math.max(l0, l1), box.maxY, Math.max(d0, d1)));
                    reach = Math.max(reach, Math.max(Math.max(-a.x, b.x), Math.max(-a.z, b.z)));
                }
            }
            double outer = boxes.stream().mapToDouble(b -> b.maxZ).max().orElseThrow();
            double top = boxes.stream().mapToDouble(b -> b.maxY).max().orElseThrow();
            double threshold = (height(boxes, 0, 0) + top) * .5, opening = 0;
            // The authored meshes use at least quarter-pixel collision cells. Sample the whole rim, not just its inside lip.
            for (double x = 0; x < inner; x += 1.0 / 64) {
                if (crest(boxes, x, inner) > threshold || crest(boxes, -x, inner) > threshold) break;
                opening = x;
            }
            return new Doorway(frame.facing(), inner, outer, opening, reach);
        });
    }

    private static double crest(List<AABB> boxes, double x, double inner) {
        return boxes.stream().filter(b -> b.minX <= x && b.maxX >= x && b.maxZ >= inner).mapToDouble(b -> b.maxY).max().orElse(0);
    }

    private static double height(List<AABB> boxes, double x, double z) {
        return boxes.stream().filter(b -> b.minX <= x && b.maxX >= x && b.minZ <= z && b.maxZ >= z)
                .mapToDouble(b -> b.maxY).max().orElseThrow();
    }

    /** The same grounded pad occupancy used by sleep; the rim and the ground beside it are outside. */
    private static boolean inside(YukkuriEntity actor, CareBlockEntity bed, Vec3 point) {
        return Occupancy.at(BedContainer.of(actor,bed.getBlockPos()),point,actor.getBbWidth(),true)==Occupancy.INSIDE_SUPPORTED;
    }

    public static CareBlockEntity at(YukkuriEntity actor, Vec3 point) {
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(point).offset(-1,-1,-1), BlockPos.containing(point).offset(1,0,1))) {
            var bed=bed(actor,pos); if(bed==null)continue;
            if(inside(actor,bed,point))return bed;
        }
        return null;
    }

    /** A stand must be a supported doorway goal or clear the ordinary route exclusion with its whole body. */
    public static CareBlockEntity rimAt(YukkuriEntity actor, Vec3 point) {
        var interior = at(actor, point);
        double radius = actor.getBbWidth() * .5;
        var area = new AABB(point, point).inflate(radius + 1, 0, radius + 1);
        for (var pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY - 1, area.minZ),
                BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            var bed = bed(actor, pos);
            if (bed == null || bed == interior) continue;
            var origin = bed.centre();
            double outer = shape(bed).reach() + radius;
            if (point.y >= origin.y - 1 && point.y < origin.y + 1
                    && Math.abs(point.x - origin.x) < outer && Math.abs(point.z - origin.z) < outer) return bed;
        }
        return null;
    }

    private static boolean opening(YukkuriEntity actor, CareBlockEntity bed, Vec3 point) {
        var d=shape(bed);var p=point.subtract(bed.centre());
        double depth=d.depth(p);
        return Math.abs(d.lateral(p))+passFoot(actor)<=d.opening()+SurfaceApproach.ARRIVAL
                &&depth>0&&depth<=d.outer()+actor.getBbWidth()*.5+.05+SurfaceApproach.ARRIVAL;
    }

    /** The low-lip segment is outside the pad: finish walking it out to the stand before following a route. */
    public static CareBlockEntity doorstep(YukkuriEntity actor) {
        for(var pos:BlockPos.betweenClosed(actor.blockPosition().offset(-1,-1,-1),actor.blockPosition().offset(1,0,1))){
            var bed=bed(actor,pos);if(bed!=null&&opening(actor,bed,actor.position()))return bed;
        }
        return null;
    }

    /** One exclusion for yukkuri route nodes, edges and ordinary last steps; other mobs keep vanilla pathing. */
    public static boolean blocksLine(YukkuriEntity actor, Vec3 from, Vec3 to) {
        double foot = actor.getBbWidth() * .5;
        double padding = foot + 1;
        var min = BlockPos.containing(Math.min(from.x, to.x) - padding, Math.min(from.y, to.y) - 1, Math.min(from.z, to.z) - padding);
        var max = BlockPos.containing(Math.max(from.x, to.x) + padding, Math.max(from.y, to.y), Math.max(from.z, to.z) + padding);
        var level = actor.level();
        int firstY = Math.max(min.getY(), level.getMinBuildHeight());
        int lastY = Math.min(max.getY(), level.getMaxBuildHeight() - 1);
        if (firstY > lastY) return false;
        var pos = new BlockPos.MutableBlockPos();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                var chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int sy = firstY >> 4; sy <= lastY >> 4; sy++) {
                    LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                    // A palette without nest blocks cannot contribute an opening. Query it live so edits
                    // and chunk reloads need no separate invalidation. Keep the section for the cell reads.
                    if (!section.maybeHas(NEST)) continue;
                    for (int y = Math.max(firstY, sy << 4); y <= Math.min(lastY, (sy << 4) + 15); y++)
                        for (int z = Math.max(min.getZ(), cz << 4); z <= Math.min(max.getZ(), (cz << 4) + 15); z++)
                            for (int x = Math.max(min.getX(), cx << 4); x <= Math.min(max.getX(), (cx << 4) + 15); x++) {
                                var state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (NEST.test(state) && blocksLine(actor, nest(level, state, pos.set(x, y, z)), from, to, foot))
                                    return true;
                            }
                }
            }
        }
        return false;
    }

    private static boolean blocksLine(YukkuriEntity actor, CareBlockEntity bed, Vec3 from, Vec3 to, double foot) {
        if (bed == null) return false;
        var c = bed.centre();
        double r = shape(bed).reach() + foot;
        double minX = c.x - r, maxX = c.x + r, minY = c.y - 1, maxY = c.y + 1, minZ = c.z - r, maxZ = c.z + r;
        if (Math.max(from.x, to.x) < minX - CLIP_PADDING || Math.min(from.x, to.x) > maxX + CLIP_PADDING
                || Math.max(from.y, to.y) < minY - CLIP_PADDING || Math.min(from.y, to.y) > maxY + CLIP_PADDING
                || Math.max(from.z, to.z) < minZ - CLIP_PADDING || Math.min(from.z, to.z) > maxZ + CLIP_PADDING) return false;
        var box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        boolean startsInside = box.contains(from);
        // A body pushed onto the rim may take an outward step from the overlap.
        if (startsInside) {
            double x = from.x-c.x, z = from.z-c.z;
            boolean outward = Math.abs(x)>=Math.abs(z) ? (to.x-from.x)*x>0 : (to.z-from.z)*z>0;
            if (outward && !inside(actor, bed, from)) return false;
        }
        return startsInside || box.contains(to) || box.clip(from, to).isPresent();
    }

    /** Observe a real ground crossing; planning never emits a crossing event. */
    public static void observe(YukkuriEntity actor, Vec3 from, Vec3 to, Vec3 drive) {
        if (from.distanceToSqr(to) < 1e-8) return;
        var seen = new HashSet<BlockPos>();
        for (var pos : BlockPos.betweenClosed(actor.blockPosition().offset(-1, -1, -1), actor.blockPosition().offset(1, 0, 1))) {
            var bed = bed(actor, pos);
            if (bed == null || !seen.add(bed.getBlockPos())) continue;
            var centre = bed.centre(); var door = shape(bed);
            boolean was = inside(actor,bed,from), now = inside(actor,bed,to);
            if (was == now) continue;
            boolean opening = opening(actor,bed,now?from:to);
            boolean driven=false;
            if(drive!=null){
                var line=drive.subtract(from).multiply(1,0,1);
                double t=line.lengthSqr()==0?0:Math.clamp(centre.subtract(from).dot(line)/line.lengthSqr(),0,1);
                driven=was?!inside(actor,bed,drive):inside(actor,bed,drive)||inside(actor,bed,from.lerp(drive,t));
            }
            GameplayLog.event(actor, "home", "bed crossing nest={} direction={} side={} opening={} source={} from={} at={} drive={}", bed.getBlockPos(),
                    now ? "in" : "out", door.facing().getName(), opening, driven?"walk":"physics",
                    GameplayLog.at(from), GameplayLog.at(to), drive==null?"none":GameplayLog.at(drive));
        }
    }

    /** The footprint that has to pass the opening (a full-term belly squeezes through). */
    private static double passFoot(YukkuriEntity actor) { return Occupancy.foot(LiveBirthGeometry.passWidth(actor)); }

    /** Align inside the hollow before the one straight crossing: level with the stand across the opening, at the origin's depth. */
    public static Vec3 inner(YukkuriEntity actor, CareBlockEntity bed, Vec3 stand) {
        var d = shape(bed); var origin = bed.centre();
        return SurfaceApproach.surface(actor,origin.add(d.offset(d.lateral(stand.subtract(origin)),0)));
    }

    /** The closest body-sized point outside the actual opening, with no occupant or ownership reservation. */
    public static Vec3 stand(YukkuriEntity actor, CareBlockEntity bed) {
        return stand(actor,bed,actor.position());
    }
    public static Vec3 stand(YukkuriEntity actor, CareBlockEntity bed, Vec3 destination) {
        var shape = shape(bed);
        double room = shape.opening() - passFoot(actor) - SurfaceApproach.ARRIVAL;
        if (room < 0) {
            GameplayLog.limited(actor, "home", "doorway.narrow", 400, "nest doorway too narrow nest={} opening={} foot={} width={}",
                    bed.getBlockPos(), shape.opening(), passFoot(actor), actor.getBbWidth());
            return null;
        }
        var origin = bed.centre();
        double preferred = Math.clamp(shape.lateral(destination.subtract(origin)), -room, room);
        return stand(shape, origin, actor.getBbWidth(), room, preferred, at -> SurfaceApproach.restingFloor(actor, at));
    }

    /** Placement and refits use the same opening and stand candidates as a standard adult walker. */
    static boolean supportsAdult(LevelReader level, BlockPos pos, BlockState state) {
        boolean large = state.getBlock() instanceof LargeNestBlock;
        var facing = large ? state.getValue(LargeNestBlock.FACING) : state.getValue(PetNestBlock.FACING);
        var origin = large ? Vec3.atLowerCornerOf(pos).add(1, 0, 1) : Vec3.atBottomCenterOf(pos);
        var shape = shape(state, facing, large ? NestResting.LARGE.box() : PetNestBlock.entranceReach(state));
        var adult = LiveBirthGeometry.adultDimensions(YukkuriVariant.REIMU);
        double room = shape.opening() - Occupancy.foot(adult.width()) - SurfaceApproach.ARRIVAL;
        return room >= 0 && stand(shape, origin, adult.width(), room, 0,
                at -> SurfaceApproach.restingFloor(level, adult, at)) != null;
    }

    private static Vec3 stand(Doorway shape, Vec3 origin, double width, double room, double preferred, Function<Vec3, Vec3> floor) {
        for (double x : new double[]{preferred, -room, room, 0}) {
            var at = floor.apply(origin.add(shape.offset(x, shape.outer() + width * .5 + .05)));
            if (at != null) return at;
        }
        return null;
    }
}
