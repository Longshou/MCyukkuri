package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.FlightContact;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The one contact test of a flight, shared by Hop and Launch: before vanilla integrates, it decides whether the segment lands on a host, glances off its edge or meets nothing. */
public final class ContactResolver {
    /** A landed body's host and its local spot in hat units; {@code refused} is a mount the host would not take. */
    public record Contact(SurfaceHost host, Vec3 local, boolean refused) {}

    /** How far from the segment a host may be looked for (blocks). */
    private static final double SEARCH = 3;
    /** Sample step and tolerated initial penetration when detecting a surface crossing (blocks). */
    private static final double STEP = .05, TOUCH = .05;
    /** Share of the horizontal speed left, negated, after the body hits the brim wall moving in. */
    private static final double RIM_BOUNCE = -.3;
    /** Rings and directions of the one deflection off an occupied spot, and how far past the two feet it looks. */
    private static final int RINGS = 5, AROUND = 12;
    private static final double DEFLECT_MARGIN = 1.25;

    /** Resolves the tick's segment: a contact is performed and returned, a glance off the edge bounces the speed and returns null; a refused mount sets {@link Contact#refused()}. */
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

    /** Walks the segment in host-local units: a downward crossing is a contact when the middle is over the hat and the host bears it,
     *  an edge otherwise; a sideways entry below the surface is the wall. */
    private static Hit hit(YukkuriEntity body, SurfaceHost host, Vec3 feet, Vec3 next, Envelope envelope) {
        var frame=host.frame();
        var hit=crossing(host,feet,next,p->envelope==null?host.support(frame.toLocal(p),body).orElse(Double.NaN):surface(host,p,envelope.at(body,p)));
        if(hit!=null){
            var p=hit.point();var spot=new Vec3(p.x(),p.y(),p.z());
            boolean stands=!hit.wall()&&host.support(spot,0).isPresent()&&host.bears(body);
            return new Hit(host,hit.fraction(),stands?spot:null);
        }
        if(envelope!=null&&body.isInWater())GameplayLog.limited(body,"body","wet_host_contact",200,
                "wet flight missed host={} from={} to={} surface={} velocity={}",host.entity().getUUID(),
                frame.toLocal(feet),frame.toLocal(next),surface(host,next,envelope.at(body,next)),body.getDeltaMovement());
        return null;
    }

    /** Same segment and envelope-height crossing for real flight and an unborn child's nominal preview. */
    public static FlightContact.Hit crossing(SurfaceHost host,Vec3 from,Vec3 to,ToDoubleFunction<Vec3> surface){
        return crossing(host,from,to,TOUCH,surface);
    }
    public static FlightContact.Hit crossing(SurfaceHost host,Vec3 from,Vec3 to,double touch,ToDoubleFunction<Vec3> surface){
        var frame=host.frame();var a=frame.toLocal(from);var b=frame.toLocal(to);
        return FlightContact.first(new Vec(a.x,a.y,a.z),new Vec(b.x,b.y,b.z),STEP/frame.unit(),touch/frame.unit(),
                p->surface.applyAsDouble(frame.toWorld(new Vec3(p.x(),p.y(),p.z()))));
    }

    /** A body's collision boxes at a feet position: its own body and what it wears (the birth launch passes one). */
    @FunctionalInterface
    public interface Envelope { List<AABB> at(YukkuriEntity body, Vec3 feet); }

    /** The feet height (hat units) at which the body meets the host at {@code feet}, or NaN off it: with an envelope every box is sampled 3x3 and lifted, so a worn brim decides. */
    public static double surface(SurfaceHost host, Vec3 feet, List<AABB> envelope) {
        HostFrame frame = host.frame();
        double need = Double.NaN;
        for (AABB box : envelope) for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
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

    /** Airborne → OnHost: on the crossing spot when it is free, else onto the nearest free spot around it, else where it fell; then move, mount, write the spot, zero velocity. */
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

    /** The nearest spot on the hat within the body's foot plus the widest foot aboard, where it has room up to {@code top}, or null. */
    private static Vec3 deflect(YukkuriEntity body, SurfaceHost host, Vec3 spot, double top) {
        double unit = Math.max(1E-6, host.frame().unit()), foot = Occupancy.foot(body.getBbWidth()), widest = foot;
        for (Entity rider : host.entity().getPassengers()) widest = Math.max(widest, Occupancy.foot(rider.getBbWidth()));
        double reach = DEFLECT_MARGIN * (foot + widest) / unit;
        for (int ring = 1; ring <= RINGS; ring++) for (int i = 0; i < AROUND; i++) {
            double d = reach * ring / RINGS, angle = i * 2 * Math.PI / AROUND;
            Vec3 place = new Vec3(spot.x + Math.cos(angle) * d, 0, spot.z + Math.sin(angle) * d);
            OptionalDouble height = host.support(place, body);
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
