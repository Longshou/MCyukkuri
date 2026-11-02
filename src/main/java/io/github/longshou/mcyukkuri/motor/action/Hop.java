package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactResolver;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.HopPath;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One collision-clear ballistic path; a seat's world point is sampled at take-off and attached at contact. */
public final class Hop implements Action {
    public static final String ID = "hop";
    private static final String CLIP = "hop";
    private static final int TAKEOFF_FALLBACK = 4;
    static final int SETTLE = 10;
    private static final double TOUCH = .05;
    private final Vec3 target;
    private final SurfaceHost seatHost;
    private boolean brimOnly;
    private SurfaceHost departure;
    private SeatTarget seat;
    private List<Vec> flight;
    private List<HopPath.Leg> path;
    private int leg;
    private Vec3 from;
    private int takeoff, crouch, air;
    private double peak;
    private boolean launched;
    private boolean aboard;
    private boolean more;
    private boolean bodyBlocked;
    private long startTick;

    public Hop(Vec3 target) { this.target = target; seatHost = null; }
    public Hop(SurfaceHost host) { seatHost = host; target = null; }
    /** A destination on the host already carrying the body; the flight stays in its live frame. */
    public Hop(SurfaceHost host, Vec3 local) { seatHost = host; target = local; }
    /** Stop at the supported entry instead of continuing to the admitted seat. */
    public static Hop ontoBrim(SurfaceHost host) { var hop = new Hop(host); hop.brimOnly = true; return hop; }
    @Override public SurfaceHost host() { return seatHost != null ? seatHost : departure; }
    @Override public String id() { return ID; }

    private record Flight(Feasibility ready, SeatTarget seat, ArcSolver.Plan plan, boolean more) {
        Flight(Feasibility ready, SeatTarget seat, ArcSolver.Plan plan) { this(ready, seat, plan, false); }
    }
    @Override public Feasibility check(YukkuriEntity body) { return solve(body).ready(); }

    /** Whether the body's own ability makes one collision-clear hop from a planned stand to {@code target}, judged by the same
     *  arc the takeoff judges. */
    public static boolean reaches(YukkuriEntity body, Vec3 from, Vec3 target) {
        return new Hop(target).arc(body, vec(from), null, hosts(body, from, target), vec(target)) instanceof ArcSolver.Plan;
    }

    /** The same supported entry and arc admission for a planned host and an actual boarding hop. */
    public static Vec3 entry(YukkuriEntity body, Vec3 from, HostFrame frame, List<Vec3> stops) {
        return new Hop(from).entryFrom(body, from, frame, stops);
    }

    private Vec3 entryFrom(YukkuriEntity body, Vec3 from, HostFrame frame, List<Vec3> stops) {
        return stops.stream().sorted(Comparator.comparingDouble(p -> frame.toWorld(p).distanceToSqr(from)))
                .filter(p -> !body.level().getBlockCollisions(body,
                        body.getBoundingBox().move(frame.toWorld(p).subtract(body.position())).deflate(.002)).iterator().hasNext())
                .filter(p -> arc(body, vec(from), null, hosts(body, from, frame.toWorld(p)), vec(frame.toWorld(p))) instanceof ArcSolver.Plan)
                .findFirst().orElse(null);
    }

    /** Whether no takeoff arc passes the shared sweep and another body is in a swept arc. The action's own check still has
     *  the last word after any wait; a body entering after takeoff is an ordinary flight failure. */
    public boolean blockedByBody(YukkuriEntity body) {
        bodyBlocked = false;
        return !check(body).ok() && bodyBlocked;
    }

    /** The steady hosts a hop from {@code from} to {@code to} sweeps: those it may come down on or clip. */
    private static List<SurfaceHost> hosts(YukkuriEntity body, Vec3 from, Vec3 to) {
        var ability = BodyAbility.of(body);
        return body.level().getEntities(body, new AABB(from, to).inflate(ability.hopHeight() + body.getBbHeight() + 1),
                e -> e instanceof SurfaceHost host && host.steady()).stream().map(e -> (SurfaceHost) e).toList();
    }

    /** The one arc answer: the lowest collision-clear hop from {@code from} to {@code to} past {@code hosts}. */
    private ArcSolver.Result arc(YukkuriEntity body, Vec from, SurfaceHost anchor, List<SurfaceHost> hosts, Vec to) {
        var ability = BodyAbility.of(body);
        var start = new Vec3(from.x(), from.y(), from.z());
        return ArcSolver.lowest(from, to, ability.hopHeight(), ability.hopReach(), ability.scale(),
                points -> clear(body, start, anchor, hosts, points));
    }

    /** Keeps only the solved local route; admission and clearance are checked again at every takeoff. */
    private Flight solve(YukkuriEntity body) {
        SeatTarget candidate = null;
        Vec3 to = target;
        if (seatHost != null) {
            if (seatHost.entity().isRemoved()) return new Flight(Feasibility.of(Reason.TARGET_GONE, 0, "host_gone"), null, null);
            candidate = target == null ? seatHost.seatDestination(body) : new SeatTarget(seatHost, target);
            if (candidate == null) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "no_seat size=" + body.growthScale() * body.getScale()), null, null);
            if (!seatHost.steady()) return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "host_unsteady"), null, null);
            if (!candidate.available(body)) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "destination_occupied"), null, null);
            to = candidate.world();
        }
        var ability = BodyAbility.of(body);
        SurfaceHost anchor = seatHost != null && body.getVehicle() == seatHost.entity() ? seatHost : null;
        boolean entering = seatHost != null && anchor == null && target == null && !seatHost.hopStops(body).isEmpty();
        if (entering) {
            var stop = entryFrom(body, body.position(), seatHost.frame(), seatHost.hopStops(body).stream()
                    .filter(p -> new SeatTarget(seatHost, p).available(body)).toList());
            if (stop == null) return new Flight(Feasibility.of(Reason.TOO_FAR, 0, "no_reachable_brim"), null, null);
            candidate = new SeatTarget(seatHost, stop);
            to = candidate.world();
        }
        var origin = anchor == null ? body.position() : anchor.riders().local(body.getUUID()).scale(anchor.frame().unit());
        var destination = anchor == null ? to : candidate.local().scale(anchor.frame().unit());
        var swept = hosts(body, body.position(), to);
        if (anchor != null && path != null) {
            var next = path.get(leg);
            var landing = new SeatTarget(anchor, new Vec3(next.at().x(), next.at().y(), next.at().z()).scale(1 / anchor.frame().unit()));
            if (!landing.available(body)) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "destination_occupied"), null, null);
            var plan = next.flight();
            if (!clear(body, origin, anchor, swept, Ballistic.samples(new Vec(plan.vh() * plan.dir().x(), plan.vy(), plan.vh() * plan.dir().z()), plan.ticks())))
                return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "route_obstructed"), null, null);
            return new Flight(Feasibility.pass(), landing, plan, leg + 1 < path.size());
        }
        var result = arc(body, vec(origin), anchor, swept, vec(destination));
        if (result instanceof ArcSolver.Plan plan) {
            if (anchor != null) path = List.of(new HopPath.Leg(vec(destination), plan));
            return new Flight(Feasibility.pass(), candidate, plan, entering && !brimOnly);
        }
        if (anchor != null) {
            var stops = anchor.hopStops(body).stream().filter(p -> new SeatTarget(anchor, p).available(body))
                    .map(p -> vec(p.scale(anchor.frame().unit()))).toList();
            path = HopPath.solve(vec(origin), vec(destination), stops, (a, b) -> arc(body, a, anchor, swept, b));
            if (path != null) {
                var next = path.getFirst();
                return new Flight(Feasibility.pass(), new SeatTarget(anchor,
                        new Vec3(next.at().x(), next.at().y(), next.at().z()).scale(1 / anchor.frame().unit())), next.flight(), path.size() > 1);
            }
        }
        var blocked = (ArcSolver.Blocked) result;
        var reason = blocked.kind() == ArcSolver.Kind.TOO_FAR ? Reason.TOO_FAR : Reason.TOO_HIGH;
        return new Flight(Feasibility.of(reason, blocked.shortfall(), String.format(Locale.ROOT,
                "dx=%.2f dy=%.2f hopHeight=%.2f hopReach=%.2f", origin.subtract(destination).horizontalDistance(),
                destination.y - origin.y, ability.hopHeight(), ability.hopReach())), null, null);
    }

    static int takeoffTicks() { return ClipMarkers.of(CLIP).ticks("takeoff", TAKEOFF_FALLBACK); }
    @Override public void start(YukkuriEntity body) {
        departure = body.getVehicle() instanceof SurfaceHost host ? host : null;
        aboard = departure != null && departure == seatHost;
        takeoff = takeoffTicks();
        from = body.position();
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!launched) {
            if (++crouch < takeoff) return new Running(takeoff - crouch, TravelMode.CONSUMED);
            var launch = solve(body);
            var ready = launch.ready();
            if (!ready.ok()) return Outcome.of(ready.reason(), ready.shortfall(), ready.detail());
            seat = launch.seat();
            more = launch.more();
            from = aboard ? seatHost.riders().local(body.getUUID()).scale(seatHost.frame().unit()) : body.position();
            var plan = launch.plan();
            var impulse = new Vec(plan.vh() * plan.dir().x(), plan.vy(), plan.vh() * plan.dir().z());
            flight = Ballistic.samples(impulse, plan.ticks());
            if (!aboard) {
                if (Attach.of(body) instanceof AttachState.OnHost) Motor.dismount(body, from, Vec3.ZERO, "hop_takeoff");
                Motor.takeoff(body, new Vec3(impulse.x(), impulse.y(), impulse.z()), "hop_takeoff");
            }
            launched = true;
            return new Running(flight.size(), TravelMode.CONSUMED);
        }
        if (seatHost != null && seatHost.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        if (aboard && body.getVehicle() != seatHost.entity()) return new Outcome.Interrupted("left_host");
        var p = flight.get(++air);
        var aim = from.add(p.x(), p.y(), p.z());
        if (aboard) aim = seatHost.frame().toWorld(aim.scale(1 / seatHost.frame().unit()));
        var delta = aim.subtract(body.position());
        Motor.velocity(body, aboard ? Vec3.ZERO : delta, "hop_flight");
        var contact = aboard ? null : ContactResolver.resolve(body);
        if (contact != null) return contact.refused() ? Outcome.of(Reason.NO_ROOM, 0, "mount_refused") : landed(body);
        // Cross a bank horizontally before descending, matching the clearance sweep.
        if (delta.y < 0) Motor.move(body, new Vec3(delta.x, 0, delta.z), "hop_flight");
        Motor.move(body, delta.y < 0 ? new Vec3(0, delta.y, 0) : delta, "hop_flight");
        if (aboard) seatHost.riders().put(body.getUUID(), seatHost.frame().toLocal(body.position()), seatHost.riders().yaw(body.getUUID()));
        peak = Math.max(peak, aboard ? p.y() : body.getY() - from.y);
        double blocked = body.position().distanceTo(aim);
        if (blocked > TOUCH) return Outcome.of(Reason.NO_SUPPORT, blocked, "arc_blocked at=" + GameplayLog.at(body.position()));
        if (air == flight.size() - 1) {
            if (seat != null && !seat.land(body, "hop_contact")) return Outcome.of(Reason.NO_ROOM, 0, "seat_landing_refused");
            return landed(body);
        }
        return new Running(flight.size() - air, TravelMode.CONSUMED);
    }

    private Progress landed(YukkuriEntity body) {
        if (!more) return new Outcome.Done("at=" + GameplayLog.at(body.position()));
        if (!aboard) { aboard = true; path = null; leg = 0; }
        else leg++;
        launched = false;
        crouch = air = 0;
        startTick = body.level().getGameTime();
        return new Running(takeoff, TravelMode.CONSUMED, true);
    }

    @Override public boolean interruptible() { return !launched; }
    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
    @Override public String startDetail() { return "from=" + GameplayLog.at(from) + " to=" + (seatHost == null ? GameplayLog.at(target) : seatHost.entity().getUUID()); }
    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "peak=%.2f airTicks=%d", peak, air); }

    private boolean clear(YukkuriEntity body, Vec3 from, SurfaceHost anchor, List<SurfaceHost> hosts, List<Vec> points) {
        AABB base = body.getBoundingBox();
        Vec3 previous = anchor == null ? from : anchor.frame().toWorld(from.scale(1 / anchor.frame().unit()));
        for (Vec point : points) {
            Vec3 at = from.add(point.x(), point.y(), point.z());
            if (anchor != null) at = anchor.frame().toWorld(at.scale(1 / anchor.frame().unit()));
            if (anchor != null && !anchor.room(body, anchor.frame().toLocal(at))) return false;
            Vec3 corner = at.y < previous.y ? new Vec3(at.x, previous.y, at.z) : new Vec3(previous.x, at.y, previous.z);
            AABB cornerBox = base.move(corner.subtract(body.position())).deflate(.002);
            AABB atBox = base.move(at.subtract(body.position())).deflate(.002);
            if (!body.level().noCollision(body, cornerBox) || !body.level().noCollision(body, atBox)) {
                bodyBlocked |= bodyCollision(body, cornerBox, atBox);
                return false;
            }
            for (SurfaceHost host : hosts) {
                Vec3 local = host.frame().toLocal(at);
                var height = host.support(local, 0);
                if (height.isPresent() && local.y < height.getAsDouble() - TOUCH / host.frame().unit()) return false;
            }
            previous = at;
        }
        return true;
    }

    private static boolean bodyCollision(YukkuriEntity body, AABB corner, AABB at) {
        return !body.level().getEntitiesOfClass(YukkuriEntity.class, corner.minmax(at), other -> other != body && other.isAlive()
                && (other.getBoundingBox().intersects(corner) || other.getBoundingBox().intersects(at))).isEmpty();
    }

    private static Vec vec(Vec3 at) { return new Vec(at.x, at.y, at.z); }
}
