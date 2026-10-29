package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The one contact test of a flight (spec 8.5), shared by Hop and Launch: run each airborne tick before vanilla integrates,
 * it decides whether this tick's segment comes down onto a host's surface, only glances off its edge, or meets nothing.
 *
 * <p>Nothing falls through a hat: a downward crossing of the surface with the body's middle over the hat is always a contact,
 * in the hollow or on the brim alike. A spot another body or store already takes at that height (and in the column it came
 * down through) deflects the body once to the nearest free spot around it; with none in reach it comes down where it fell and
 * the host's squeeze parts them. What happens on a brim afterwards (a tumble in or off) is the body's next action. Only a body
 * whose middle misses the hat (its side meets the brim's outer edge), one the host does not bear, or one entering under the
 * surface from beside it, glances off; vanilla moves it on. No consequence (fall damage, drowning) is decided here.
 */
public final class ContactResolver {
    /** A landed body's host and its local spot in hat units; {@code refused} is a mount the host would not take. */
    public record Contact(SurfaceHost host, Vec3 local, boolean refused) {}

    /** How far from the segment a host may be looked for (blocks). */
    private static final double SEARCH = 3;
    /** Sample step along the segment (blocks) and how far feet may sit under a surface before it counts as inside it. */
    private static final double STEP = .05, TOUCH = .05;
    /** Share of the horizontal speed left, negated, after the body hits the brim wall moving in. */
    private static final double RIM_BOUNCE = -.3;
    /** Rings and directions of the one deflection off an occupied spot, nearest ring first, and how far past the two feet it
     *  looks (the twelve directions miss the free side's nearest point by up to this share). */
    private static final int RINGS = 5, AROUND = 12;
    private static final double DEFLECT_MARGIN = 1.25;

    /**
     * Resolves this tick's flight segment for {@code body}: a contact is performed here (move, mount, riders spot, velocity
     * zero) and returned; a glance off the edge bounces the horizontal speed and returns null. A mount the host refuses is
     * returned with {@link Contact#refused()} set, so the flight can end with {@code NO_ROOM} where physics left it.
     */
    public static Contact resolve(YukkuriEntity body) { return resolve(body, null); }

    /** As {@link #resolve(YukkuriEntity)} with the body's real envelope: the lowest box decides where it first meets a host. */
    public static Contact resolve(YukkuriEntity body, Envelope envelope) {
        if (body == null || body.level().isClientSide) return null;
        Vec3 feet = body.position();
        Vec3 next = feet.add(body.getDeltaMovement());
        Hit landing = null;
        SurfaceHost edge = null;
        for (SurfaceHost host : hosts(body, feet, next)) {
            Hit hit = hit(body, host, feet, next, envelope);
            if (hit == null) continue;
            if (hit.spot() == null) { if (edge == null) edge = host; }
            else if (landing == null || hit.at() < landing.at()) landing = hit;
        }
        if (landing != null) return land(body, landing, feet);
        if (edge != null) glance(body, edge);
        return null;
    }

    /** The steady hosts whose box can meet this tick's segment. */
    private static List<SurfaceHost> hosts(YukkuriEntity body, Vec3 feet, Vec3 next) {
        List<SurfaceHost> out = new ArrayList<>();
        for (Entity entity : body.level().getEntities(body, new AABB(feet, next).inflate(SEARCH),
                e -> e instanceof SurfaceHost host && host.steady())) out.add((SurfaceHost) entity);
        return out;
    }

    /** One host's answer: a contact at {@code at} along the segment on {@code spot} (hat units), or an edge when it is null. */
    private record Hit(SurfaceHost host, double at, Vec3 spot) {}

    /**
     * Walks the segment in host-local units. A downward crossing of the support height is a contact when the body's middle is
     * over the hat and the host bears it, and an edge otherwise; entering under the surface from beside the hat is the wall.
     */
    private static Hit hit(YukkuriEntity body, SurfaceHost host, Vec3 feet, Vec3 next, Envelope envelope) {
        HostFrame frame = host.frame();
        double footRadius = Occupancy.foot(body.getBbWidth());
        // The height field is in hat units, so the tolerance in blocks has to be expressed in the same units.
        double touch = TOUCH / Math.max(1E-6, frame.unit());
        int steps = Math.max(2, (int) Math.ceil(feet.distanceTo(next) / STEP));
        Vec3 previous = frame.toLocal(feet);
        double previousHeight = surface(body, host, feet, footRadius, envelope);
        for (int i = 1; i <= steps; i++) {
            double share = i / (double) steps;
            Vec3 world = feet.lerp(next, share);
            Vec3 local = frame.toLocal(world);
            double height = surface(body, host, world, footRadius, envelope);
            boolean above = Double.isFinite(previousHeight) && previous.y >= previousHeight - touch;
            boolean under = Double.isFinite(height) && local.y < height - touch;
            if (under && above) {
                // Interpolate the crossing inside this step, so the contact point is where the feet actually meet the hat.
                double drop = previous.y - previousHeight, fall = height - local.y;
                double inside = drop + fall < 1E-9 ? 1 : Math.clamp(drop / (drop + fall), 0, 1);
                double at = (i - 1 + inside) / steps;
                Vec3 spot = frame.toLocal(feet.lerp(next, at));
                boolean stands = host.support(spot, 0).isPresent() && host.bears(body);
                return new Hit(host, at, stands ? spot : null);
            }
            // Only coming in from beside the hat under its surface is the wall; a body already under it is left to the host.
            if (under && !Double.isFinite(previousHeight)) return new Hit(host, share, null);
            previous = local;
            previousHeight = height;
        }
        return null;
    }

    /** A body's collision boxes at a feet position: its own body and what it wears (the birth launch passes one). */
    @FunctionalInterface
    public interface Envelope { List<AABB> at(YukkuriEntity body, Vec3 feet); }

    /**
     * The feet height (hat units) at which the body meets the host at {@code feet}, or NaN off the host: the surface under
     * the foot circle, or with an envelope every box's underside sampled 3x3 and lifted by its height above the feet, so a
     * worn brim that reaches the host first decides the contact (pose-aware support).
     */
    private static double surface(YukkuriEntity body, SurfaceHost host, Vec3 feet, double footRadius, Envelope envelope) {
        HostFrame frame = host.frame();
        if (envelope == null) return height(host, frame.toLocal(feet), footRadius);
        double need = Double.NaN;
        for (AABB box : envelope.at(body, feet)) for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
            Vec3 under = new Vec3(Mth.lerp(x / 2.0, box.minX, box.maxX), feet.y, Mth.lerp(z / 2.0, box.minZ, box.maxZ));
            double h = height(host, frame.toLocal(under), 0) - (box.minY - feet.y) / frame.unit();
            if (Double.isFinite(h) && !(h <= need)) need = h;
        }
        return need;
    }

    private static double height(SurfaceHost host, Vec3 local, double footRadius) {
        OptionalDouble height = host.support(local, footRadius);
        return height.isPresent() ? height.getAsDouble() : Double.NaN;
    }

    /**
     * Airborne → OnHost (spec 5.2): on the crossing spot when it is free at the body's height and in the column it came down
     * through this tick, else once onto the nearest free spot around it, else where it fell; move there, mount, write the
     * local spot, zero the velocity. A refused mount settles the body where the move left it and answers with the refusal.
     */
    private static Contact land(YukkuriEntity body, Hit hit, Vec3 feet) {
        SurfaceHost host = hit.host();
        HostFrame frame = host.frame();
        Vec3 spot = hit.spot();
        double top = frame.toLocal(feet).y + body.getBbHeight() / Math.max(1E-6, frame.unit());
        boolean free = host.room(body, spot, top);
        Vec3 aside = free ? null : deflect(body, host, spot, top);
        Vec3 local = aside != null ? aside : spot;
        Vec3 at = frame.toWorld(local);
        GameplayLog.event(body, "body", "contact host={} at={} occupancy={} room={}{}", host.entity().getUUID(), GameplayLog.at(at),
                Occupancy.at(host.container(), at, body.getBbWidth(), true), free,
                free ? "" : aside == null ? " deflected=none" : " deflected_from=" + GameplayLog.at(frame.toWorld(spot)));
        Motor.move(body, at.subtract(body.position()), "hop_contact");
        if (!Motor.mount(body, host.entity(), "hop_contact")) {
            Motor.warn(body, "contact refused host={}", host.entity().getUUID());
            Attach.settle(body, "hop_contact_refused");
            return new Contact(host, local, true);
        }
        host.riders().put(body.getUUID(), local, localYaw(body, host));
        Motor.velocity(body, Vec3.ZERO, "hop_contact");
        return new Contact(host, local, false);
    }

    /**
     * The one deflection off an occupied spot: the nearest spot on the hat (its middle over it) within the body's foot plus the
     * widest foot aboard (its own at least), the distance that clears a body it fell onto the middle of, of {@code spot} where
     * it has room at its height up to {@code top}, or null.
     */
    private static Vec3 deflect(YukkuriEntity body, SurfaceHost host, Vec3 spot, double top) {
        double unit = Math.max(1E-6, host.frame().unit()), foot = Occupancy.foot(body.getBbWidth()), widest = foot;
        for (Entity rider : host.entity().getPassengers()) widest = Math.max(widest, Occupancy.foot(rider.getBbWidth()));
        double reach = DEFLECT_MARGIN * (foot + widest) / unit;
        for (int ring = 1; ring <= RINGS; ring++) for (int i = 0; i < AROUND; i++) {
            double d = reach * ring / RINGS, angle = i * 2 * Math.PI / AROUND;
            Vec3 place = new Vec3(spot.x + Math.cos(angle) * d, 0, spot.z + Math.sin(angle) * d);
            OptionalDouble height = host.support(place, foot);
            if (height.isEmpty() || host.support(place, 0).isEmpty()) continue;
            Vec3 candidate = new Vec3(place.x, height.getAsDouble(), place.z);
            if (host.room(body, candidate, top)) return candidate;
        }
        return null;
    }

    /** The body's own facing in the host's frame, which is what the rider table stores. */
    private static float localYaw(YukkuriEntity body, SurfaceHost host) {
        return Mth.wrapDegrees(body.getYRot() - (float) Math.toDegrees(host.frame().yawRad()));
    }

    /** It met only the brim's edge or wall: moving in, its horizontal push reverses and damps; moving out it passes on. */
    private static void glance(YukkuriEntity body, SurfaceHost host) {
        Vec3 velocity = body.getDeltaMovement(), in = host.entity().position().subtract(body.position());
        if (velocity.x * in.x + velocity.z * in.z <= 0) return;
        Motor.velocity(body, new Vec3(velocity.x * RIM_BOUNCE, velocity.y, velocity.z * RIM_BOUNCE), "rim_bounce");
        GameplayLog.event(body, "body", "rim_bounce at={} v={}",
                GameplayLog.at(body.position()), GameplayLog.at(body.getDeltaMovement()));
    }

    private ContactResolver() {}
}
