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
 * it decides whether this tick's segment meets a host's surface, meets only the brim wall, or meets nothing.
 *
 * <p>Only a landing inside the hollow ({@link Occupancy#INSIDE_SUPPORTED} for the host's container) with room for the
 * body counts as contact; everything else is left to vanilla, except a brim hit, which bounces the horizontal speed.
 * No consequence (fall damage, drowning) is decided here.
 */
public final class ContactResolver {
    /** A landed body's host and its local spot in hat units. */
    public record Contact(SurfaceHost host, Vec3 local) {}

    /** How far from the segment a host may be looked for (blocks). */
    private static final double SEARCH = 3;
    /** Sample step along the segment (blocks) and how far feet may sit under a surface before it counts as inside it. */
    private static final double STEP = .05, TOUCH = .05;
    /** Share of the horizontal speed left, negated, after the body hits the brim wall. */
    private static final double RIM_BOUNCE = -.3;

    /**
     * Resolves this tick's flight segment for {@code body}: a valid landing is performed here (move, mount, riders spot,
     * velocity zero) and returned; a brim hit bounces the horizontal speed and returns null.
     */
    public static Contact resolve(YukkuriEntity body) {
        if (body == null || body.level().isClientSide) return null;
        Vec3 feet = body.position();
        Vec3 next = feet.add(body.getDeltaMovement());
        Contact landing = null;
        double landingAt = Double.MAX_VALUE;
        boolean brim = false;
        for (SurfaceHost host : hosts(body, feet, next)) {
            Hit hit = hit(body, host, feet, next);
            if (hit == null) continue;
            if (hit.contact() == null) brim = true;
            else if (hit.at() < landingAt) { landing = hit.contact(); landingAt = hit.at(); }
        }
        if (landing != null) { land(body, landing); return landing; }
        if (brim) { bounce(body); return null; }
        return null;
    }

    /** The steady hosts whose box can meet this tick's segment. */
    private static List<SurfaceHost> hosts(YukkuriEntity body, Vec3 feet, Vec3 next) {
        List<SurfaceHost> out = new ArrayList<>();
        for (Entity entity : body.level().getEntities(body, new AABB(feet, next).inflate(SEARCH),
                e -> e instanceof SurfaceHost host && host.steady())) out.add((SurfaceHost) entity);
        return out;
    }

    /** One host's answer: a landing at {@code at} along the segment, or a brim hit when {@code contact} is null. */
    private record Hit(double at, Contact contact) {}

    /**
     * Walks the segment in host-local units. A downward crossing of the support height is a landing when the footprint
     * there is inside the hollow with room, and a brim hit otherwise; entering the hat under its surface is the brim wall.
     */
    private static Hit hit(YukkuriEntity body, SurfaceHost host, Vec3 feet, Vec3 next) {
        HostFrame frame = host.frame();
        double footRadius = Occupancy.FOOT * body.getBbWidth();
        // The height field is in hat units, so the tolerance in blocks has to be expressed in the same units.
        double touch = TOUCH / Math.max(1E-6, frame.unit());
        int steps = Math.max(2, (int) Math.ceil(feet.distanceTo(next) / STEP));
        Vec3 previous = frame.toLocal(feet);
        double previousHeight = surface(host, previous, footRadius);
        for (int i = 1; i <= steps; i++) {
            double share = i / (double) steps;
            Vec3 world = feet.lerp(next, share);
            Vec3 local = frame.toLocal(world);
            double height = surface(host, local, footRadius);
            boolean above = Double.isFinite(previousHeight) && previous.y >= previousHeight - touch;
            boolean under = Double.isFinite(height) && local.y < height - touch;
            if (under && above) {
                // Interpolate the crossing inside this step, so the contact point is where the feet actually meet the hat.
                double drop = previous.y - previousHeight, fall = height - local.y;
                double inside = drop + fall < 1E-9 ? 1 : Math.clamp(drop / (drop + fall), 0, 1);
                double at = (i - 1 + inside) / steps;
                Vec3 contact = feet.lerp(next, at);
                Vec3 spot = frame.toLocal(contact);
                boolean fits = Occupancy.at(host.container(), contact, body.getBbWidth(), true) == Occupancy.INSIDE_SUPPORTED
                        && host.room(body, spot);
                return new Hit(at, fits ? new Contact(host, spot) : null);
            }
            if (under) return new Hit(share, null);
            previous = local;
            previousHeight = height;
        }
        return null;
    }

    /** The hat surface height (hat units) at {@code local}, or NaN when the hat is not under the feet there. */
    private static double surface(SurfaceHost host, Vec3 local, double footRadius) {
        OptionalDouble height = host.support(local, footRadius);
        return height.isPresent() ? height.getAsDouble() : Double.NaN;
    }

    /** Airborne → OnHost (spec 5.2): move to the contact point, mount, write the local spot, zero the velocity. */
    private static void land(YukkuriEntity body, Contact contact) {
        Vec3 at = contact.host().frame().toWorld(contact.local());
        Motor.move(body, at.subtract(body.position()), "hop_contact");
        if (!Motor.mount(body, contact.host().entity(), "hop_contact")) {
            Motor.warn(body, "contact refused host={}", contact.host().entity().getUUID());
            return;
        }
        contact.host().riders().put(body.getUUID(), contact.local(), localYaw(body, contact.host()));
        Motor.velocity(body, Vec3.ZERO, "hop_contact");
    }

    /** The body's own facing in the host's frame, which is what the rider table stores. */
    private static float localYaw(YukkuriEntity body, SurfaceHost host) {
        return Mth.wrapDegrees(body.getYRot() - (float) Math.toDegrees(host.frame().yawRad()));
    }

    /** Only the brim was hit: keep the fall, reverse and damp the horizontal push, and let vanilla move. */
    private static void bounce(YukkuriEntity body) {
        Vec3 velocity = body.getDeltaMovement();
        Motor.velocity(body, new Vec3(velocity.x * RIM_BOUNCE, velocity.y, velocity.z * RIM_BOUNCE), "rim_bounce");
        GameplayLog.event(body, "body", "rim_bounce at={} v={}",
                GameplayLog.at(body.position()), GameplayLog.at(body.getDeltaMovement()));
    }

    private ContactResolver() {}
}
