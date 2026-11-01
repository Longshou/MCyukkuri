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
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
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
    private SurfaceHost departure;
    private SeatTarget seat;
    private List<Vec> flight;
    private Vec3 from;
    private int takeoff, crouch, air;
    private double peak;
    private boolean launched;
    private long startTick;

    public Hop(Vec3 target) { this.target = target; seatHost = null; }
    public Hop(SurfaceHost host) { seatHost = host; target = null; }
    @Override public SurfaceHost host() { return seatHost != null ? seatHost : departure; }
    @Override public String id() { return ID; }

    private record Flight(Feasibility ready, SeatTarget seat, ArcSolver.Plan plan) {}
    @Override public Feasibility check(YukkuriEntity body) { return solve(body).ready(); }

    /** Pure probe: neither the action nor the host keeps a candidate seat or plan. */
    private Flight solve(YukkuriEntity body) {
        SeatTarget candidate = null;
        Vec3 to = target;
        if (seatHost != null) {
            if (seatHost.entity().isRemoved()) return new Flight(Feasibility.of(Reason.TARGET_GONE, 0, "host_gone"), null, null);
            candidate = seatHost.seatDestination(body);
            if (candidate == null) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "no_seat size=" + body.growthScale() * body.getScale()), null, null);
            if (!seatHost.steady()) return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "host_unsteady"), null, null);
            to = candidate.world();
        }
        var ability = BodyAbility.of(body);
        var from = body.position();
        var hosts = body.level().getEntities(body, new AABB(from, to).inflate(ability.hopHeight() + body.getBbHeight() + 1),
                e -> e instanceof SurfaceHost host && host.steady()).stream().map(e -> (SurfaceHost)e).toList();
        var result = ArcSolver.lowest(vec(from), vec(to), ability.hopHeight(), ability.hopReach(), ability.scale(),
                points -> clear(body, from, hosts, points));
        if (result instanceof ArcSolver.Plan plan) return new Flight(Feasibility.pass(), candidate, plan);
        var blocked = (ArcSolver.Blocked) result;
        var reason = blocked.kind() == ArcSolver.Kind.TOO_FAR ? Reason.TOO_FAR : Reason.TOO_HIGH;
        return new Flight(Feasibility.of(reason, blocked.shortfall(), String.format(Locale.ROOT,
                "dx=%.2f dy=%.2f hopHeight=%.2f hopReach=%.2f", from.subtract(to).horizontalDistance(),
                to.y - from.y, ability.hopHeight(), ability.hopReach())), null, null);
    }

    static int takeoffTicks() { return ClipMarkers.of(CLIP).ticks("takeoff", TAKEOFF_FALLBACK); }
    @Override public void start(YukkuriEntity body) {
        departure = body.getVehicle() instanceof SurfaceHost host ? host : null;
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
            from = body.position();
            var plan = launch.plan();
            var impulse = new Vec(plan.vh() * plan.dir().x(), plan.vy(), plan.vh() * plan.dir().z());
            flight = Ballistic.samples(impulse, plan.ticks());
            if (Attach.of(body) instanceof AttachState.OnHost) Motor.dismount(body, from, Vec3.ZERO, "hop_takeoff");
            Motor.takeoff(body, new Vec3(impulse.x(), impulse.y(), impulse.z()), "hop_takeoff");
            launched = true;
            return new Running(flight.size(), TravelMode.CONSUMED);
        }
        if (seatHost != null && seatHost.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        var p = flight.get(++air);
        var aim = from.add(p.x(), p.y(), p.z());
        var delta = aim.subtract(body.position());
        Motor.velocity(body, delta, "hop_flight");
        var contact = ContactResolver.resolve(body);
        if (contact != null) return contact.refused() ? Outcome.of(Reason.NO_ROOM, 0, "mount_refused")
                : new Outcome.Done("contact at=" + GameplayLog.at(body.position()));
        // Cross a bank horizontally before descending, matching the clearance sweep.
        if (delta.y < 0) Motor.move(body, new Vec3(delta.x, 0, delta.z), "hop_flight");
        Motor.move(body, delta.y < 0 ? new Vec3(0, delta.y, 0) : delta, "hop_flight");
        peak = Math.max(peak, body.getY() - from.y);
        double blocked = body.position().distanceTo(aim);
        if (blocked > TOUCH) return Outcome.of(Reason.NO_SUPPORT, blocked, "arc_blocked at=" + GameplayLog.at(body.position()));
        if (air == flight.size() - 1) {
            if (seat != null && !seat.land(body, "hop_contact")) return Outcome.of(Reason.NO_ROOM, 0, "seat_landing_refused");
            return new Outcome.Done("at=" + GameplayLog.at(body.position()));
        }
        return new Running(flight.size() - air, TravelMode.CONSUMED);
    }

    @Override public boolean interruptible() { return !launched; }
    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }
    @Override public String startDetail() { return "from=" + GameplayLog.at(from) + " to=" + (seatHost == null ? GameplayLog.at(target) : seatHost.entity().getUUID()); }
    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "peak=%.2f airTicks=%d", peak, air); }

    private static boolean clear(YukkuriEntity body, Vec3 from, List<SurfaceHost> hosts, List<Vec> points) {
        AABB base = body.getBoundingBox();
        Vec3 previous = from;
        for (Vec point : points) {
            Vec3 at = from.add(point.x(), point.y(), point.z());
            Vec3 corner = at.y < previous.y ? new Vec3(at.x, previous.y, at.z) : new Vec3(previous.x, at.y, previous.z);
            if (!body.level().noCollision(body, base.move(corner.subtract(from)).deflate(.002))
                    || !body.level().noCollision(body, base.move(at.subtract(from)).deflate(.002))) return false;
            for (SurfaceHost host : hosts) {
                Vec3 local = host.frame().toLocal(at);
                var height = host.support(local, 0);
                if (height.isPresent() && local.y < height.getAsDouble() - TOUCH / host.frame().unit()) return false;
            }
            previous = at;
        }
        return true;
    }
    private static Vec vec(Vec3 at) { return new Vec(at.x, at.y, at.z); }
}
