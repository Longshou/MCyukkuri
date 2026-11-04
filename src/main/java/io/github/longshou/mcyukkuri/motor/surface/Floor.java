package io.github.longshou.mcyukkuri.motor.surface;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.FloorSearch;
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
    public static BodyFootprint shape(SurfaceHost host, YukkuriEntity body) {
        float yaw = body.getVehicle() == host.entity() ? host.riders().yaw(body.getUUID())
                : body.yBodyRot - host.entity().getYRot();
        return shape(host, body, yaw);
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
    /** A short supported floor move that preserves the body's facing. */
    public static boolean advance(SurfaceHost host, YukkuriEntity body, Vec3 target) {
        var spot = host.riders().spot(body.getUUID());
        var delta = target.subtract(spot.local());
        double distance = delta.length();
        if (distance == 0) return true;
        var next = spot.local().add(delta.scale(Math.min(distance, BodyAbility.of(body).walkSpeed() / host.frame().unit()) / distance));
        var height = host.support(next, body);
        if (height.isEmpty() || !host.room(body, next)) return false;
        next = new Vec3(next.x, height.getAsDouble(), next.z);
        if (body.level().getBlockCollisions(body, body.getBoundingBox().move(host.frame().toWorld(next).subtract(body.position()))).iterator().hasNext()) return false;
        Motor.spot(host, body, next, spot.yaw(), "floor_move");
        return true;
    }

    /** Try the requested turn, then two smaller turns; write only after every pushed body has a clear destination. */
    public static float turn(SurfaceHost host, YukkuriEntity body, float stepDeg) {
        var riders = host.entity().getPassengers().stream().map(p -> (YukkuriEntity) p).toList();
        int mover = riders.indexOf(body);
        var spot = host.riders().spot(body.getUUID());
        for (int attempt = 0; attempt < 3; attempt++, stepDeg /= 2) {
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
            if (!fits || !Motor.clearSpot(host, body, point(at))) continue;
            for (int i = 0; i < riders.size(); i++) if (i != mover) {
                var rider = riders.get(i); var before = host.riders().spot(rider.getUUID());
                var after = point(proposed.get(i).at());
                if (after.equals(before.local())) continue;
                Motor.spot(host, rider, after, before.yaw(), "turn_push");
                String band = after.y * host.frame().unit() > host.container().floorY() + Occupancy.MARGIN ? "raised" : "floor";
                GameplayLog.limited(host.entity(), "hat", "turn_push." + rider.getUUID() + "." + band, 100,
                        "turn push rider={} by={} from={} to={}", rider.getUUID(), body.getUUID(), before.local(), after);
            }
            Motor.spot(host, body, point(at), spot.yaw() + stepDeg, "turn");
            return stepDeg;
        }
        return 0;
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
            if (!standing(body)) continue;
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
            if (!host.room(body, next)) continue;
            Motor.spot(host, body, next, spot.yaw(), "settle");
            GameplayLog.limited(host.entity(), "hat", "settle." + body.getUUID(), 100,
                    "settled rider={} from={} to={}", body.getUUID(), from, next);
        }
    }
}
