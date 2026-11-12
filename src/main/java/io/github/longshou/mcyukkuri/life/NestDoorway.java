package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import io.github.longshou.mcyukkuri.motor.surface.BedContainer;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Physical nest openings, independent of residency. Routes end outside; a straight step crosses the low lip. */
public final class NestDoorway {
    private NestDoorway() {}
    public record Doorway(double inner, double outer, double opening, int side) {}
    private static final Map<Block, Doorway> SHAPES = new HashMap<>();

    public static CareBlockEntity bed(YukkuriEntity actor, BlockPos pos) {
        var level = actor.level();
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return null;
        var state = chunk.getBlockState(pos);
        if (!(state.getBlock() instanceof CareBlock)) return null;
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
            var centre = bed.centre();
            double outer = shape(bed).outer() + radius;
            if (point.y >= centre.y - 1 && point.y < centre.y + 1
                    && Math.abs(point.x - centre.x) < outer && Math.abs(point.z - centre.z) < outer) return bed;
        }
        return null;
    }

    private static boolean opening(YukkuriEntity actor, CareBlockEntity bed, Vec3 point) {
        var d=shape(bed);var p=point.subtract(bed.centre());
        return Math.abs(p.x)+passFoot(actor)<=d.opening()+SurfaceApproach.ARRIVAL
                &&p.z*d.side()>0&&p.z*d.side()<=d.outer()+actor.getBbWidth()*.5+.05+SurfaceApproach.ARRIVAL;
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
        var area=new AABB(from,to).inflate(actor.getBbWidth()*.5+1,0,actor.getBbWidth()*.5+1);
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(area.minX,area.minY-1,area.minZ),BlockPos.containing(area.maxX,area.maxY,area.maxZ))){
            var bed=bed(actor,pos);if(bed==null)continue;
            var c=bed.centre();double r=shape(bed).outer()+actor.getBbWidth()*.5;
            var box=new AABB(c.x-r,c.y-1,c.z-r,c.x+r,c.y+1,c.z+r);
            // A pushed/placed body on the rim can walk outward from the overlap, never further into the bed.
            if(box.contains(from)&&!inside(actor,bed,from)){
                double x=from.x-c.x,z=from.z-c.z;
                if(Math.abs(x)>=Math.abs(z)?(to.x-from.x)*x>0:(to.z-from.z)*z>0)continue;
            }
            if(box.contains(from)||box.contains(to)||box.clip(from,to).isPresent())return true;
        }
        return false;
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
                    now ? "in" : "out", door.side() < 0 ? "north" : "south", opening, driven?"walk":"physics",
                    GameplayLog.at(from), GameplayLog.at(to), drive==null?"none":GameplayLog.at(drive));
        }
    }

    /** The footprint that has to pass the opening (a full-term belly squeezes through). */
    private static double passFoot(YukkuriEntity actor) { return Occupancy.foot(LiveBirthGeometry.passWidth(actor)); }

    /** Align inside the hollow before the one straight crossing. */
    public static Vec3 inner(YukkuriEntity actor, CareBlockEntity bed, Vec3 stand) {
        return SurfaceApproach.surface(actor,new Vec3(stand.x,bed.centre().y,bed.centre().z));
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
        var centre = bed.centre();
        double preferred = Math.clamp(destination.x - centre.x, -room, room);
        for (double x : new double[]{preferred, -room, room, 0}) {
            var at = SurfaceApproach.restingFloor(actor, centre.add(x, 0, shape.side() * (shape.outer() + actor.getBbWidth() * .5 + .05)));
            if (at != null) return at;
        }
        return null;
    }
}
