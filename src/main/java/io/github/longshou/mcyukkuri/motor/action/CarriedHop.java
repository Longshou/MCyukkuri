package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Hop's retained-passenger execution, composed by the grip that placed the body on its carrier.
 * The carry record is the sole seat authority; the server commits each real arc there. */
final class CarriedHop {
    private final YukkuriEntity carrier;
    private final Vec3 destination;
    private Vec3 from, to;
    private ArcSolver.Plan plan;
    private float bearing;
    private int hops;
    private long start;
    private int span;
    private String obstruction;

    CarriedHop(YukkuriEntity carrier, Vec3 destination) {
        this.carrier = carrier;
        this.destination = destination;
    }

    private double unit() { return carrier.growthScale() * carrier.getScale() / 16; }
    private static Vec vec(Vec3 v) { return new Vec(v.x, v.y, v.z); }

    Feasibility check(YukkuriEntity body) {
        if (body.getVehicle() != carrier) return Feasibility.of(Reason.DROPPED, 0, "carrier_lost");
        var ability = BodyAbility.of(body);
        if (!ability.canJump()) return Feasibility.of(Reason.TOO_HIGH, 0, "cannot_jump");
        if (ability.turnRate() <= 0) return Feasibility.of(Reason.NO_PROGRESS, 0, "cannot_turn");
        from = position(ParentCarry.brimMotion(carrier, body), carrier.level().getGameTime());
        double distance = from.distanceTo(destination) * unit();
        int legs = Math.max(1, (int) Math.ceil(distance / ability.hopReach()));
        to = legs == 1 ? destination : from.lerp(destination, 1.0 / legs);
        var result = ArcSolver.lowest(vec(from.scale(unit())), vec(to.scale(unit())), ability.hopHeight(),
                ability.hopReach(), ability.scale(), points -> {
                    Vec3 previous = world(from);
                    for (var p : points) {
                        var at = world(from.add(p.x() / unit(), p.y() / unit(), p.z() / unit()));
                        if (!clear(body, previous, at)) return false;
                        previous = at;
                    }
                    return true;
                }, true, false);
        if (result instanceof ArcSolver.Blocked blocked) {
            GameplayLog.event(carrier, "family", "brim hop refused child={} blocker={} from={} to={}",
                    body.getUUID(), obstruction, from, to);
            return Feasibility.of(Reason.NO_ROOM, blocked.shortfall(), "carried_hop_sweep blocker=" + obstruction);
        }
        plan = (ArcSolver.Plan) result;
        return Feasibility.pass();
    }

    private Vec3 world(Vec3 local) {
        return carrier.position().add(local.scale(unit()).yRot((180 - carrier.bodyYaw()) * (float) Math.PI / 180));
    }

    private boolean clear(YukkuriEntity body, Vec3 previous, Vec3 at) {
        var box = body.getBoundingBox().move(previous.subtract(body.position())).expandTowards(at.subtract(previous));
        if (body.level().getBlockCollisions(body, box).iterator().hasNext()) {
            obstruction = "world_blocks@" + GameplayLog.at(at);
            return false;
        }
        // Same-host riders already have non-overlapping planned seats. Only the host itself
        // and world blocks can refuse this move across its brim.
        if (box.intersects(carrier.getBoundingBox())) {
            obstruction = "host:" + carrier.getUUID();
            return false;
        }
        return true;
    }

    void start(YukkuriEntity body) {
        // The braid holds the child at local yaw zero. All legs lie on the same line to its spot.
        var direction = destination.subtract(from);
        bearing = (float) Math.toDegrees(Math.atan2(-direction.x, -direction.z));
        beginLeg(body, carrier.level().getGameTime());
    }

    private void beginLeg(YukkuriEntity body, long tick) {
        start = tick;
        double rate = BodyAbility.of(body).turnRate();
        float outward = ParentCarry.brimSeat(carrier, body)[3];
        int turnIn = hops == 0 ? turnTicks(0, bearing, rate) : 0;
        int turnOut = to.equals(destination) ? turnTicks(bearing, outward, rate) : 0;
        span = turnIn + Hop.takeoffTicks() + (int) Math.ceil(plan.ticks()) + Math.max(Hop.recoveryTicks(), turnOut);
        var motion = new CompoundTag();
        motion.putDouble("X", from.x); motion.putDouble("Y", from.y); motion.putDouble("Z", from.z);
        motion.putDouble("TX", to.x); motion.putDouble("TY", to.y); motion.putDouble("TZ", to.z);
        motion.putDouble("VX", plan.vh() * plan.dir().x() / unit());
        motion.putDouble("VY", plan.vy() / unit());
        motion.putDouble("VZ", plan.vh() * plan.dir().z() / unit());
        motion.putDouble("Unit", unit()); motion.putDouble("Air", plan.ticks()); motion.putLong("Start", start);
        motion.putFloat("Bearing", bearing);
        motion.putInt("TurnIn", turnIn);
        motion.putInt("TurnOut", turnOut);
        ParentCarry.brimMotion(carrier, body, motion);
    }

    private static int turnTicks(float from, float to, double rate) {
        return (int) Math.ceil(Math.abs(Mth.wrapDegrees(to - from)) / rate);
    }

    Progress tick(YukkuriEntity body) {
        if (body.getVehicle() != carrier) return Outcome.of(Reason.DROPPED, 0, "carrier_lost");
        var motion = ParentCarry.brimMotion(carrier, body);
        long now = carrier.level().getGameTime();
        if (!clear(body, world(position(motion, now - 1)), world(position(motion, now)))) {
            freeze(body, now - 1);
            GameplayLog.event(carrier, "family", "brim hop refused child={} blocker={} during=leg", body.getUUID(), obstruction);
            return Outcome.of(Reason.NO_ROOM, 0, "carried_hop_obstructed blocker=" + obstruction);
        }
        if (now - start < span) return new Running(span - (now - start), TravelMode.CONSUMED);
        hops++;
        GameplayLog.event(carrier, "family",
                "brim hop leg child={} leg={} start={} turnInTicks={} windUpTicks={} airTicks={} recoveryTicks={} turnOutTicks={} bearing={} elapsedTicks={}",
                body.getUUID(), hops, start, motion.getInt("TurnIn"), Hop.takeoffTicks(), motion.getDouble("Air"),
                Hop.recoveryTicks(), motion.getInt("TurnOut"), bearing, now - start);
        if (to.equals(destination)) {
            motion.putBoolean("Done", true);
            ParentCarry.brimMotion(carrier, body, motion);
            GameplayLog.event(carrier, "family", "brim hop child={} hops={}", body.getUUID(), hops);
            return new Outcome.Done("brim_spot");
        }
        var ready = check(body);
        if (!ready.ok()) { freeze(body, now); return Outcome.of(ready.reason(), ready.shortfall(), ready.detail()); }
        beginLeg(body, now + 1);
        return new Running(span + 1, TravelMode.CONSUMED, true);
    }

    void freeze(YukkuriEntity body, double now) {
        var motion = ParentCarry.brimMotion(carrier, body);
        var at = position(motion, now);
        motion.putDouble("X", at.x); motion.putDouble("Y", at.y); motion.putDouble("Z", at.z);
        motion.remove("Start");
        ParentCarry.brimMotion(carrier, body, motion);
    }

    /** Sample only the server-admitted arc; feet stay still through wind-up and landing recovery. */
    static Vec3 position(CompoundTag motion, double now) {
        var from = new Vec3(motion.getDouble("X"), motion.getDouble("Y"), motion.getDouble("Z"));
        if (!motion.contains("Start")) return from;
        if (motion.getBoolean("Walk")) return from.lerp(
                new Vec3(motion.getDouble("TX"), motion.getDouble("TY"), motion.getDouble("TZ")),
                Math.clamp((now - motion.getLong("Start")) / motion.getInt("Ticks"), 0, 1));
        double age = now - motion.getLong("Start") - motion.getInt("TurnIn") - Hop.takeoffTicks();
        if (age <= 0) return from;
        double air = motion.getDouble("Air");
        if (age >= air) return new Vec3(motion.getDouble("TX"), motion.getDouble("TY"), motion.getDouble("TZ"));
        double unit = motion.getDouble("Unit");
        var points = Ballistic.samples(new Vec(motion.getDouble("VX") * unit, motion.getDouble("VY") * unit,
                motion.getDouble("VZ") * unit), air);
        int tick = (int) age;
        var a = points.get(tick); var b = points.get(tick + 1);
        double t = (age - tick) / Math.min(1, air - tick);
        return from.add((a.x() + (b.x() - a.x()) * t) / unit,
                (a.y() + (b.y() - a.y()) * t) / unit, (a.z() + (b.z() - a.z()) * t) / unit);
    }

    static float clock(CompoundTag motion, double now) {
        if (!motion.contains("Start")) return 0;
        double age = Math.max(0, now - motion.getLong("Start") - motion.getInt("TurnIn"));
        int takeoff = Hop.takeoffTicks(), land = Hop.landingMarker();
        double air = motion.getDouble("Air");
        return (float) (age <= takeoff ? age : age < takeoff + air
                ? takeoff + (age - takeoff) / air * (land - takeoff)
                : land + Math.min(age - takeoff - air, Hop.recoveryTicks())) / 20;
    }

    /** The same committed timeline turns the rider on its stationary feet before takeoff and after contact. */
    static float facing(CompoundTag motion, double now, float outward) {
        double age = now - motion.getLong("Start");
        float bearing = motion.getFloat("Bearing");
        int turnIn = motion.getInt("TurnIn"), turnOut = motion.getInt("TurnOut");
        if (turnIn > 0 && age < turnIn)
            return Mth.rotLerp((float) Math.max(0, age / turnIn), 0, bearing);
        double landed = age - turnIn - Hop.takeoffTicks() - motion.getDouble("Air");
        return turnOut > 0 && landed > 0
                ? Mth.rotLerp((float) Math.min(1, landed / turnOut), bearing, outward) : bearing;
    }
}
