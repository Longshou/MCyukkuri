package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
import io.github.longshou.mcyukkuri.motor.geom.GridPath;
import io.github.longshou.mcyukkuri.motor.geom.HeightGrid;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Contact motion on a host: turns yield only to supported positions, raised riders settle downhill. */
public final class Floor {
    private Floor() {}
    /** An aboard hop keeps its rider record while the motor carries it through the air. */
    public static boolean standing(YukkuriEntity body) {
        var action = body.motor().current();
        return action == null || !action.airborne();
    }
    public static BodyFootprint shape(SurfaceHost host, YukkuriEntity body) { return shape(host, body, yaw(host, body)); }
    /** The body's turn relative to the host (degrees): its riding yaw there, else its body yaw. */
    public static float yaw(SurfaceHost host, YukkuriEntity body) {
        return body.getVehicle() == host.entity() ? host.riders().yaw(body.getUUID()) : body.yBodyRot - host.entity().getYRot();
    }
    public static BodyFootprint shape(SurfaceHost host, YukkuriEntity body, float yaw) {
        double unit = host.frame().unit();
        return BodyFootprint.of(body.assetId(), body.getBbWidth() / unit, body.getBbHeight() / unit, Math.toRadians(yaw));
    }
    public static List<FloorSearch.Placed> others(SurfaceHost host, YukkuriEntity except) {
        return host.entity().getPassengers().stream().filter(p -> p != except).map(p -> (YukkuriEntity) p)
                .map(y -> new FloorSearch.Placed(shape(host, y), vec(host.riders().local(y.getUUID())))).toList();
    }
    public static Vec vec(Vec3 p) { return new Vec(p.x, p.y, p.z); }
    public static Vec3 point(Vec p) { return new Vec3(p.x(), p.y(), p.z()); }

    /** A supported route to a free point in a fixed body's contact cone. */
    public static List<Vec3> approach(SurfaceHost host, YukkuriEntity body, Vec3 centre, float yaw,
                                      double inner, double outer, double halfAngle, float facingOffset) {
        var local = host.frame().toLocal(centre);
        var forward = host.frame().toLocal(centre.add(Vec3.directionFromRotation(0, yaw))).subtract(local);
        var grid = new HeightGrid[1];
        var refusals = new int[5];
        var path = FloorSearch.coneRoute(vec(host.riders().local(body.getUUID())), vec(local),
                Math.atan2(forward.z, forward.x), inner / host.frame().unit(), outer / host.frame().unit(), Math.toRadians(halfAngle),
                at -> {
                    var delta = host.frame().toWorld(point(at)).subtract(centre);
                    float facing = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90 + facingOffset;
                    return route(host, body, grid, point(at), facing - host.entity().getYRot(), refusals);
                });
        if (path.isEmpty()) GameplayLog.limited(body,"care","floor_no_route",200,
                "floor route unavailable from={} centre={} bearing={} facingOffset={} radius={}..{} width={} height={} yaw={} others={} rejected=support:{},room:{},turn:{},path:{},edge:{}",
                host.riders().local(body.getUUID()),local,Math.toDegrees(Math.atan2(forward.z,forward.x)),facingOffset,
                inner/host.frame().unit(),outer/host.frame().unit(),body.getBbWidth()/host.frame().unit(),body.getBbHeight()/host.frame().unit(),
                host.riders().yaw(body.getUUID()),others(host,body).stream().map(p -> p.at()+"/"+p.body().width()+"/"+p.body().height()+"/"+Math.toDegrees(p.body().yaw())).toList(),
                refusals[0],refusals[1],refusals[2],refusals[3],refusals[4]);
        return new ArrayList<>(path.stream().map(Floor::point).toList());
    }

    private static List<Vec> noRoute(int[] refusals, int reason) { refusals[reason]++; return List.of(); }

    private static HeightGrid walkGrid(SurfaceHost host, YukkuriEntity body) {
        var rim = host.rim().stream().map(host.frame()::toLocal).toList();
        var from = host.riders().local(body.getUUID());
        return new HeightGrid(rim.stream().mapToDouble(p -> p.x).min().orElseThrow(), rim.stream().mapToDouble(p -> p.z).min().orElseThrow(),
                rim.stream().mapToDouble(p -> p.x).max().orElseThrow(), rim.stream().mapToDouble(p -> p.z).max().orElseThrow(),
                Math.min(FloorSearch.GRID_CELL, shape(host, body).radius()), (x, z) -> {
                    var height = host.support(new Vec3(x, 0, z), body);
                    if (height.isEmpty()) return Double.NaN;
                    var at = new Vec3(x, height.getAsDouble(), z);
                    return host.insideFloor(at) && host.stepRoom(body, from, at) && Motor.clearSpot(host, body, at)
                            ? height.getAsDouble() : Double.NaN;
                });
    }

    private static List<Vec> route(SurfaceHost host, YukkuriEntity body, HeightGrid[] cache, Vec3 target, float facing, int[] refusals) {
        var height = host.support(target, body);
        if (height.isEmpty()) return noRoute(refusals,0);
        target = new Vec3(target.x, height.getAsDouble(), target.z);
        if (!host.insideFloor(target) || !host.room(body, target) || !Motor.clearSpot(host, body, target)) return noRoute(refusals,1);
        var turned = target;
        float yaw = host.riders().yaw(body.getUUID());
        while (Math.abs(Mth.wrapDegrees(facing - yaw)) > Face.FACE_ARRIVED) {
            float rate = (float) BodyAbility.of(body).turnRate();
            yaw += Math.clamp(Mth.wrapDegrees(facing - yaw), -rate, rate);
            var turn = clearTurn(host, body, turned, yaw);
            if (turn.isEmpty()) return noRoute(refusals,2);
            turned = new Vec3(turned.x, turn.getAsDouble(), turned.z);
            if (!host.insideFloor(turned)) return noRoute(refusals,2);
        }
        var from = host.riders().local(body.getUUID());
        if (clearWay(host, body, from, target)) return List.of(vec(target));
        // The walk grid is worth building only once a straight step is refused.
        var grid = cache[0] != null ? cache[0] : (cache[0] = walkGrid(host, body));
        var start = grid.nearest(from.x, from.z);
        var end = grid.nearest(target.x, target.z);
        if (start == null || end == null) return noRoute(refusals,3);
        var way = GridPath.find(grid, grid.centreX(start[0]), grid.centreZ(start[1]), grid.centreX(end[0]), grid.centreZ(end[1]),
                BodyAbility.of(body).stepUp() / host.frame().unit(), 0,
                (a, b) -> clearWay(host, body, point(a), point(b)));
        if (way.isEmpty()) return noRoute(refusals,3);
        var out = new ArrayList<Vec>();
        for (var step : way) {
            var next = new Vec3(step[0], host.support(new Vec3(step[0], 0, step[1]), body).orElseThrow(), step[1]);
            if (!clearWay(host, body, from, next)) return noRoute(refusals,4);
            out.add(vec(next)); from = next;
        }
        if (!clearWay(host, body, from, target)) return noRoute(refusals,4);
        out.add(vec(target));
        return out;
    }

    /** Sweep the actual footprint between grid centres too; a clear endpoint cannot cut through another rider. */
    private static boolean clearWay(SurfaceHost host, YukkuriEntity body, Vec3 from, Vec3 to) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / Math.min(FloorSearch.GRID_CELL, shape(host, body).radius())));
        var previous = from;
        for (int i = 1; i <= steps; i++) {
            var at = from.lerp(to, (double) i / steps);
            var height = host.support(at, body);
            if (height.isEmpty()) return false;
            at = new Vec3(at.x, height.getAsDouble(), at.z);
            if (!host.insideFloor(at) && at.y > from.y
                    || Math.abs(at.y - previous.y) * host.frame().unit() > BodyAbility.of(body).stepUp()
                    || !host.stepRoom(body, previous, at) || !Motor.clearSpot(host, body, at)) return false;
            previous = at;
        }
        return true;
    }

    /** Consume one physical step of the selected route, preserving the contact facing. */
    public static boolean follow(SurfaceHost host, YukkuriEntity body, List<Vec3> route) {
        var target = route.getFirst();
        if (!advance(host, body, target)) return false;
        var at = host.riders().local(body.getUUID());
        if (Math.hypot(at.x - target.x, at.z - target.z) < 1e-6) route.removeFirst();
        return true;
    }

    /** Turn only this body; contact approaches cannot displace the stationary caregiver or a serving sibling. */
    public static void turnInPlace(SurfaceHost host, YukkuriEntity body, float stepDeg) {
        var spot = host.riders().spot(body.getUUID());
        var height = clearTurn(host, body, spot.local(), spot.yaw() + stepDeg);
        if (height.isPresent()) Motor.spot(host, body, new Vec3(spot.local().x, height.getAsDouble(), spot.local().z),
                spot.yaw() + stepDeg, "turn");
    }

    private static OptionalDouble clearTurn(SurfaceHost host, YukkuriEntity body, Vec3 local, float yaw) {
        var height = host.turnSupport(local, body, yaw);
        if (height.isEmpty() || (height.getAsDouble() - local.y) * host.frame().unit() > Occupancy.MARGIN) return OptionalDouble.empty();
        var at = new Vec(local.x, height.getAsDouble(), local.z);
        return FloorSearch.clear(shape(host, body, yaw), at, others(host, body), host.roomGap())
                && Motor.clearSpot(host, body, point(at)) ? height : OptionalDouble.empty();
    }

    /** A short supported floor move that preserves the body's facing. */
    public static boolean advance(SurfaceHost host, YukkuriEntity body, Vec3 target) {
        var spot = host.riders().spot(body.getUUID());
        var delta = target.subtract(spot.local());
        double distance = delta.length();
        if (distance == 0) return true;
        var next = spot.local().add(delta.scale(Math.min(distance, BodyAbility.of(body).walkSpeed() / host.frame().unit()) / distance));
        var height = host.support(next, body);
        if (height.isEmpty()) return false;
        next = new Vec3(next.x, height.getAsDouble(), next.z);
        if (!host.stepRoom(body, spot.local(), next) || !Motor.clearSpot(host, body, next)) return false;
        Motor.spot(host, body, next, spot.yaw(), "floor_move");
        return true;
    }

    /** The turn that fits: its step, where the turner stands after it, and where every rider (the turner included) ends up. */
    private record Turn(float step, Vec at, List<FloorSearch.Placed> proposed) {}

    /** Try the requested turn, then smaller ones (up to {@code attempts}), without writing: the first that gives every pushed body a clear destination. */
    private static Turn plan(SurfaceHost host, YukkuriEntity body, float stepDeg, int attempts) {
        var riders = host.entity().getPassengers().stream().map(p -> (YukkuriEntity) p).toList();
        int mover = riders.indexOf(body);
        var spot = host.riders().spot(body.getUUID());
        for (int attempt = 0; attempt < attempts; attempt++, stepDeg /= 2) {
            var proposed = new ArrayList<FloorSearch.Placed>(others(host, null));
            var turned = shape(host, body, spot.yaw() + stepDeg);
            var height = host.turnSupport(spot.local(), body, spot.yaw() + stepDeg);
            if (height.isEmpty() || (height.getAsDouble() - spot.local().y) * host.frame().unit() > Occupancy.MARGIN) continue;
            var at = new Vec(spot.local().x, height.getAsDouble(), spot.local().z);
            proposed.set(mover, new FloorSearch.Placed(turned, at));
            boolean fits = true;
            for (int i = 0; i < riders.size(); i++) {
                if (i == mover) continue;
                var other = proposed.get(i);
                if (FloorSearch.clear(other.body(), other.at(), List.of(proposed.get(mover)), host.roomGap())) continue;
                if (!standing(riders.get(i))) { fits = false; break; }
                var obstacles = new ArrayList<>(proposed); obstacles.remove(i);
                var destinations = FloorSearch.spots((x,z,fp) -> host.support(new Vec3(x,0,z),fp).orElse(Double.NaN),
                        host.container().floorY() / host.frame().unit(), Occupancy.MARGIN / host.frame().unit(),
                        other.body(), obstacles, other.at(), BodyAbility.of(riders.get(i)).walkSpeed() / host.frame().unit(),
                        false, host.roomGap(), 1);
                if (destinations.isEmpty() || !Motor.clearSpot(host, riders.get(i), point(destinations.getFirst()))) { fits = false; break; }
                proposed.set(i, new FloorSearch.Placed(other.body(), destinations.getFirst()));
            }
            if (fits && Motor.clearSpot(host, body, point(at))) return new Turn(stepDeg, at, proposed);
        }
        return null;
    }

    /** Whether the whole turn by {@code deg} finds room at its end (riders pushed aside if they can be), with nothing written. */
    public static boolean canTurn(SurfaceHost host, YukkuriEntity body, float deg) { return plan(host, body, deg, 1) != null; }

    /** Turn by the requested step, or the largest of two smaller ones that fits; writes only once every pushed body has a clear
     *  destination. Returns the turn made (0 when none fits). */
    public static float turn(SurfaceHost host, YukkuriEntity body, float stepDeg) {
        var turn = plan(host, body, stepDeg, 3);
        if (turn == null) return 0;
        var riders = host.entity().getPassengers().stream().map(p -> (YukkuriEntity) p).toList();
        int mover = riders.indexOf(body);
        var spot = host.riders().spot(body.getUUID());
        for (int i = 0; i < riders.size(); i++) if (i != mover) {
            var rider = riders.get(i); var before = host.riders().spot(rider.getUUID());
            var after = point(turn.proposed().get(i).at());
            if (after.equals(before.local())) continue;
            Motor.spot(host, rider, after, before.yaw(), "turn_push");
            String band = after.y * host.frame().unit() > host.container().floorY() + Occupancy.MARGIN ? "raised" : "floor";
            GameplayLog.limited(host.entity(), "hat", "turn_push." + rider.getUUID() + "." + band, 100,
                    "turn push rider={} by={} from={} to={}", rider.getUUID(), body.getUUID(), before.local(), after);
        }
        Motor.spot(host, body, point(turn.at()), spot.yaw() + turn.step(), "turn");
        return turn.step();
    }

    /** Read-only support sweep for a planned turn, with the same step sizes and rise limit as the executed turn. */
    public static OptionalDouble turnHeight(SurfaceHost host, YukkuriEntity body, Vec3 local, float from, float to) {
        double left = Mth.wrapDegrees(to - from);
        while (Math.abs(left) > Face.FACE_ARRIVED) {
            float step = (float) Math.copySign(Math.min(Math.abs(left), BodyAbility.of(body).turnRate()), left);
            OptionalDouble height = OptionalDouble.empty();
            for (int attempt = 0; attempt < 3; attempt++, step /= 2) {
                var next = host.turnSupport(local, body, from + step);
                if (next.isPresent() && (next.getAsDouble() - local.y) * host.frame().unit() <= Occupancy.MARGIN) {
                    height = next;
                    break;
                }
            }
            if (height.isEmpty() || step == 0) return OptionalDouble.empty();
            local = new Vec3(local.x, height.getAsDouble(), local.z);
            from += step;
            left = Mth.wrapDegrees(to - from);
        }
        return OptionalDouble.of(local.y);
    }

    public static void settle(SurfaceHost host) {
        if (host.capsized()) return;
        double unit = host.frame().unit();
        var container = host.container();
        for (var passenger : host.entity().getPassengers()) if (passenger instanceof YukkuriEntity body) {
            // A body between the legs of its own hop stands where the leg came down: the next takeoff was admitted from there.
            if (!standing(body) || body.motor().current() instanceof Hop) continue;
            var spot = host.riders().spot(body.getUUID()); var from = spot.local();
            if (!container.floor().contains(from.x * unit, from.z * unit)
                    || from.y * unit <= container.floorY() + Occupancy.MARGIN || !host.room(body, from)) continue;
            var toward = new Vec3(-from.x, 0, -from.z);
            double distance = toward.length();
            if (distance == 0) continue;
            var next = from.add(toward.scale(Math.min(distance, BodyAbility.of(body).walkSpeed() / unit) / distance));
            var height = host.support(next, body);
            if (height.isEmpty() || height.getAsDouble() >= from.y) continue;
            next = new Vec3(next.x, height.getAsDouble(), next.z);
            if (!host.room(body, next) || !Motor.clearSpot(host, body, next)) continue;
            Motor.spot(host, body, next, spot.yaw(), "settle");
            GameplayLog.limited(host.entity(), "hat", "settle." + body.getUUID(), 100,
                    "settled rider={} from={} to={}", body.getUUID(), from, next);
        }
    }
}
