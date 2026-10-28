package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/** Physical limits and the swept executor shared by deliberate water and carried movement. */
public final class YukkuriLocomotion {
    public enum Kind { HOP, CLIMB, LIFT, SWIM, TOW }
    /** Heights/reaches are blocks; speeds are blocks per server tick. No client state owns these limits. */
    public record Limits(double maxHopHeight, double maxHopReach, double maxHorizSpeed,
                         double maxClimbRise, double swimSpeed, double towSpeed) {}
    public record Plan(Kind kind, List<Vec3> points, int ticks, YukkuriAction action, double speed, String reason) {
        public Plan { points = List.copyOf(points); }
        public boolean ok() { return reason.isEmpty(); }
    }

    /** Nothing carried: the host load factor L of spec 8.1 is 1. */
    public static Limits limits(YukkuriEntity body) { return limits(body, 0); }

    /**
     * The body's limits from {@link BodyAbility}. {@code load} is the host's load factor, supplied by the caller that knows
     * the host: this class never reads a host, so {@code life/} stays free of host state (spec 8.1 L, WP2 step 1).
     */
    public static Limits limits(YukkuriEntity body, double load) {
        var ability = BodyAbility.of(body, load);
        double swim = ability.swimSpeed();
        return new Limits(ability.hopHeight(), ability.hopReach(), ability.walkSpeed(), ability.climbRise(),
                swim, swim * .65);
    }

    public static double towSpeed(YukkuriEntity carrier, YukkuriEntity body) {
        return Math.min(limits(carrier).towSpeed(), limits(body).towSpeed());
    }

    /** Duration is independent of arc height: going slowly can never buy a higher jump. */
    public static Plan hop(YukkuriEntity body, Vec3 from, Vec3 to) {
        var limits = limits(body);
        double flat = from.subtract(to).horizontalDistance();
        if (limits.maxHopHeight() <= 0 || flat > limits.maxHopReach() || Math.abs(to.y - from.y) > limits.maxHopHeight())
            return refused(Kind.HOP, "hop_exceeds_body");
        double available = limits.maxHopHeight() - Math.max(0, to.y - from.y);
        for (int attempt = 1; attempt <= 20; attempt++) {
            double naturalRise = Math.min(available, .12 * Math.sqrt(body.growthScale() * body.getScale()) + flat * .10);
            double rise = naturalRise + (available - naturalRise) * (attempt - 1) / 19;
            var points = new ArrayList<Vec3>();
            for (int i = 0; i <= 32; i++) {
                double u = i / 32.0;
                points.add(from.lerp(to, u).add(0, 4 * rise * u * (1 - u), 0));
            }
            if (!clearBlocks(body, points)) continue;
            // Uniform flight time keeps take-off decisive and preserves the parabola's vertical acceleration.
            // Budget the largest real step, rather than stretching the whole jump with walking ease-in/out.
            double largestStep = 0;
            for (int i = 1; i < points.size(); i++) largestStep = Math.max(largestStep, points.get(i).distanceTo(points.get(i - 1)));
            int ticks = Math.max(6, (int) Math.ceil(largestStep * 32 / Math.max(.003, limits.maxHorizSpeed())));
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
        double at = u * (points.size() - 1);
        int index = Math.min(points.size() - 1, (int) at);
        return index + 1 >= points.size() ? points.getLast() : points.get(index).lerp(points.get(index + 1), at - index);
    }

    public static boolean step(YukkuriEntity body, Plan plan, double tick) {
        return plan.ok() && step(body, sample(plan.points(), tick / Math.max(1, plan.ticks())), plan.speed());
    }

    /** A move is never replaced with setPos: collision or a speed cap leaves the real residual distance for the caller. */
    public static boolean step(YukkuriEntity body, Vec3 target, double maxSpeed) {
        return advance(body, target, maxSpeed).arrived();
    }

    /** A speed-limited partial move is progress, not evidence of a collision. */
    public record Step(boolean arrived, boolean blocked, double requested, double allowed, double moved, double remaining) {}
    public static Step advance(YukkuriEntity body, Vec3 target, double maxSpeed) {
        var before = body.position();
        var delta = target.subtract(before);
        double requested = delta.length();
        double speed = Math.max(0, maxSpeed);
        if (delta.length() > speed) delta = delta.normalize().scale(speed);
        if (delta.lengthSqr() > 1e-10) body.move(MoverType.SELF, delta);
        double moved = body.position().distanceTo(before);
        body.setDeltaMovement(Vec3.ZERO);
        body.hasImpulse = true;
        return new Step(body.position().distanceToSqr(target) <= .0004,
                delta.lengthSqr() > 1e-10 && body.position().distanceToSqr(before.add(delta)) > 1e-8,
                requested, delta.length(), moved, body.position().distanceTo(target));
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
