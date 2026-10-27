package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/** Physical limits and the swept executor shared by deliberate water, deck and carried movement. */
public final class YukkuriLocomotion {
    public enum Kind { HOP, CLIMB, LIFT, SWIM, TOW }
    /** Heights/reaches are blocks; speeds are blocks per server tick. No client state owns these limits. */
    public record Limits(double maxHopHeight, double maxHopReach, double maxHorizSpeed,
                         double maxClimbRise, double swimSpeed, double towSpeed) {}
    public record Plan(Kind kind, List<Vec3> points, int ticks, YukkuriAction action, double speed, String reason) {
        public Plan { points = List.copyOf(points); }
        public boolean ok() { return reason.isEmpty(); }
    }

    public static Limits limits(YukkuriEntity body) {
        double size = Math.sqrt(Math.clamp(body.growthScale() * body.getScale(), .06, 1.5));
        double energy = Math.clamp(body.life().energy() / 80.0, 0, 1);
        var condition = body.body().condition();
        double health = Math.clamp(body.getHealth() / Math.max(1, body.getMaxHealth()), .1, 1);
        double injury = 1 / (1 + .18 * condition.painSeverity() + (condition.footBurned() ? .3 : 0));
        double load = body.getVehicle() instanceof HatBoatEntity hat ? 1 / (1 + hat.deck().load() / 12.0) : 1;
        double fitness = (.35 + .65 * energy) * Math.sqrt(health) * injury;
        boolean jumps = (body.family().adult() || body.childhood().canJump()) && body.life().energy() >= 8
                && !condition.dying() && !body.life().isSleeping();
        double swim = .055 * size * fitness * load;
        return new Limits(jumps ? .70 * size * fitness : 0, jumps ? 1.8 * size * fitness : 0,
                .10 * size * fitness * load, .75 * size * (.65 + .35 * fitness), swim, swim * .65);
    }

    public static double towSpeed(YukkuriEntity carrier, YukkuriEntity body) {
        return Math.min(limits(carrier).towSpeed(), limits(body).towSpeed());
    }

    /** Duration is independent of arc height: going slowly can never buy a higher jump. */
    public static Plan hop(YukkuriEntity body, Vec3 from, Vec3 to, List<HatBoatEntity> hulls) {
        var limits = limits(body);
        double flat = from.subtract(to).horizontalDistance();
        if (limits.maxHopHeight() <= 0 || flat > limits.maxHopReach() || Math.abs(to.y - from.y) > limits.maxHopHeight())
            return refused(Kind.HOP, "hop_exceeds_body");
        double radius = HatVessel.radius(body.getBbWidth());
        double available = limits.maxHopHeight() - Math.max(0, to.y - from.y);
        for (int attempt = 1; attempt <= 20; attempt++) {
            double rise = available * attempt / 20;
            var points = new ArrayList<Vec3>();
            for (int i = 0; i <= 32; i++) {
                double u = i / 32.0;
                points.add(from.lerp(to, u).add(0, 4 * rise * u * (1 - u), 0));
            }
            boolean clear = true;
            for (var hull : hulls) if (!HatVessel.clears(points, hull, radius)) { clear = false; break; }
            if (!clear || !clearBlocks(body, points)) continue;
            int ticks = Math.max(12, (int) Math.ceil(length(points) * 1.7 / Math.max(.003, limits.maxHorizSpeed())));
            return new Plan(Kind.HOP, points, ticks, YukkuriAction.HAT_HOP_UP, limits.maxHorizSpeed(), "");
        }
        return refused(Kind.HOP, "hop_clearance_exceeds_body");
    }

    public static int duration(YukkuriEntity body, YukkuriEntity carrier, Kind kind, double length, int pullWindow) {
        var actor = carrier == null ? body : carrier;
        double speed = limits(actor).maxHorizSpeed() * (kind == Kind.CLIMB ? .32 : .28);
        if (carrier != null) {
            double relativeLoad = Math.pow(body.growthScale() * body.getScale()
                    / Math.max(.06, carrier.growthScale() * carrier.getScale()), 3);
            speed /= 1 + relativeLoad * 4;
        }
        return Math.max(pullWindow, (int) Math.ceil(length * 1.7 / Math.max(.003, speed)));
    }

    /** The synchronized points contain the selected curve; rendering only samples them. */
    public static Vec3 sample(List<Vec3> points, double progress) {
        if (points.isEmpty()) return Vec3.ZERO;
        double u = Math.clamp(progress, 0, 1);
        double distance = length(points) * u * u * (3 - 2 * u);
        for (int i = 1; i < points.size(); i++) {
            double segment = points.get(i - 1).distanceTo(points.get(i));
            if (distance <= segment || i == points.size() - 1)
                return points.get(i - 1).lerp(points.get(i), segment < 1e-9 ? 1 : Math.clamp(distance / segment, 0, 1));
            distance -= segment;
        }
        return points.getLast();
    }

    public static boolean step(YukkuriEntity body, Plan plan, double tick) {
        return plan.ok() && step(body, sample(plan.points(), tick / Math.max(1, plan.ticks())), plan.speed());
    }

    /** A move is never replaced with setPos: collision or a speed cap leaves the real residual distance for the caller. */
    public static boolean step(YukkuriEntity body, Vec3 target, double maxSpeed) {
        var before = body.position();
        var delta = target.subtract(before);
        var budget = body.family().crew();
        double speed = budget.motionAllowance(body.level().getGameTime(), Math.max(0, maxSpeed));
        if (delta.length() > speed) delta = delta.normalize().scale(speed);
        if (delta.lengthSqr() > 1e-10) body.move(MoverType.SELF, delta);
        budget.moved(body.position().distanceTo(before));
        body.setDeltaMovement(Vec3.ZERO);
        body.hasImpulse = true;
        return body.position().distanceToSqr(target) <= .0004;
    }

    /** Undo vanilla's automatic dismount placement; the body's subsequent trip still uses the swept executor. */
    public static void detach(YukkuriEntity body) {
        var at = body.position();
        body.stopRiding();
        body.setPos(at);
    }

    /** Water contact/separation retains the previous float and current velocity after the collision sweep. */
    public static boolean waterStep(YukkuriEntity body, Vec3 target, double maxSpeed) {
        var velocity = body.getDeltaMovement();
        boolean arrived = step(body, target, Math.min(maxSpeed, limits(body).swimSpeed()));
        waterImpulse(body, velocity);
        return arrived;
    }

    /** Swim effort changes bounded horizontal velocity; vanilla fluid physics retains responsibility for buoyancy. */
    public static boolean swim(YukkuriEntity body, Vec3 target, double requestedSpeed) {
        var toward = target.subtract(body.position()).multiply(1, 0, 1);
        if (toward.lengthSqr() < .0001) return true;
        double speed = Math.min(Math.max(0, requestedSpeed), limits(body).swimSpeed());
        var desired = toward.normalize().scale(Math.min(speed, toward.length()));
        if (!body.level().noCollision(body, body.getBoundingBox().move(desired))) return false;
        var velocity = body.getDeltaMovement();
        var horizontal = new Vec3(velocity.x, 0, velocity.z).lerp(desired, .25);
        if (horizontal.length() > speed) horizontal = horizontal.normalize().scale(speed);
        body.setDeltaMovement(horizontal.x, velocity.y, horizontal.z);
        body.hasImpulse = true;
        return true;
    }

    /** A real push remains an impulse, but cannot stack unbounded horizontal speed on a swimmer. */
    public static void waterImpulse(YukkuriEntity body, Vec3 impulse) {
        var next = body.getDeltaMovement().add(impulse);
        double speed = limits(body).swimSpeed();
        var horizontal = new Vec3(next.x, 0, next.z);
        if (horizontal.length() > speed) horizontal = horizontal.normalize().scale(speed);
        body.setDeltaMovement(horizontal.x, next.y, horizontal.z);
        body.hasImpulse = true;
    }

    public static double length(List<Vec3> points) {
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i - 1).distanceTo(points.get(i));
        return length;
    }
    private static Plan refused(Kind kind, String reason) {
        return new Plan(kind, List.of(), 0, YukkuriAction.WATER_STRUGGLE, 0, reason);
    }
    private static boolean clearBlocks(YukkuriEntity body, List<Vec3> points) {
        for (var point : points) {
            if (!body.level().hasChunkAt(BlockPos.containing(point))
                    || !body.level().getWorldBorder().isWithinBounds(BlockPos.containing(point))
                    || body.level().getBlockCollisions(body, body.getBoundingBox().move(point.subtract(body.position())).deflate(.002)).iterator().hasNext()) return false;
        }
        return true;
    }
    private YukkuriLocomotion() {}
}
