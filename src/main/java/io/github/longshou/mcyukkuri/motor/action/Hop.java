package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
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
import io.github.longshou.mcyukkuri.motor.geom.BankStand;
import io.github.longshou.mcyukkuri.motor.geom.HopPath;
import io.github.longshou.mcyukkuri.motor.geom.HopHeading;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.motor.surface.HostFrame;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.motor.surface.Floor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/** One collision-clear ballistic path; a seat's world point is sampled at take-off and attached at contact. */
public final class Hop implements Action {
    public static final String ID = "hop";
    private static final String CLIP = "hop_leg";
    static final int SETTLE = 10;
    private static final double TOUCH = .05;
    private Vec3 target;
    private SurfaceHost seatHost;
    private Vec3 bank;
    private boolean brimOnly;
    private Vec3 entryNear;
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
    private String obstruction = "";
    private long startTick;
    private Face seatingTurn;
    private Face legTurn;
    private int recovery;
    private int completedLegs;
    private SeatTarget rest;

    public Hop(Vec3 target) { this.target = target; seatHost = null; }
    public Hop(SurfaceHost host) { seatHost = host; target = null; }
    /** A destination on the host already carrying the body; the flight stays in its live frame. */
    public Hop(SurfaceHost host, Vec3 local) { seatHost = host; target = local; }
    /** Leave through a supported host point, then hop to the admitted ground stand after settling there. */
    public static Hop off(SurfaceHost host, Vec3 local, Vec3 bank) {
        var hop = new Hop(host, local); hop.bank = bank; return hop;
    }
    /** One synchronous landing decision's memo: the hull's stops and its arcs are the same for every candidate. Nothing
     *  survives the decision and nothing here moves a body. */
    public static final class Search {
        private final Map<ArcKey, ArcAnswer> arcs = new HashMap<>();
        private final Map<SurfaceHost, List<Vec3>> stops = new HashMap<>();
        private final Map<SurfaceHost, List<Vec>> usable = new HashMap<>();
        /** The local stands this hull offers to a body of its size. */
        public List<Vec3> stops(SurfaceHost host, YukkuriEntity body) {
            return stops.computeIfAbsent(host, h -> h.hopStops(body));
        }
        /** Those of them this body may stand on now, in hull units for the arc solver. */
        private List<Vec> usable(SurfaceHost host, YukkuriEntity body) {
            return usable.computeIfAbsent(host, h -> stops(host, body).stream()
                    .filter(p -> new SeatTarget(host, p).available(body))
                    .map(p -> vec(p.scale(host.frame().unit()))).toList());
        }
        /** The same admission the takeoff judges, sharing this decision's arcs. */
        public boolean admits(YukkuriEntity body, SurfaceHost host, Vec3 local, Vec3 bank) {
            return off(host, local, bank).check(body, this).ok();
        }
    }
    /** Stop at a reachable supported entry nearest the requested work point. */
    public static Hop ontoBrim(SurfaceHost host, Vec3 near) {
        var hop = new Hop(host); hop.brimOnly = true; hop.entryNear = near; return hop;
    }
    @Override public SurfaceHost host() { return seatHost != null ? seatHost : departure; }
    @Override public String id() { return ID; }

    private record Flight(Feasibility ready, SeatTarget seat, ArcSolver.Plan plan, boolean more) {
        Flight(Feasibility ready, SeatTarget seat, ArcSolver.Plan plan) { this(ready, seat, plan, false); }
    }
    @Override public Feasibility check(YukkuriEntity body) {
        return check(body, new Search());
    }
    private Feasibility check(YukkuriEntity body, Search search) {
        if (onEntryFloor(body)) return Feasibility.pass();
        long started = System.nanoTime();
        var ready = solve(body, takeoffTicks() - 1, search).ready();
        timing(body, "check", started);
        if (bank != null && !ready.ok() && StatusEvents.trace()) GameplayLog.limited(body, "move", "exit." + ready.reason(), 200,
                "exit refused brim={} bank={} reason={} detail={} width={}", target, bank, ready.reason(), ready.detail(), body.getBbWidth());
        return ready;
    }

    /** The same boarding arc from a proposed dry stand, without moving the body. */
    public Feasibility checkFrom(YukkuriEntity body, Vec3 from) {
        return solve(body, from, takeoffTicks() - 1, new Search()).ready();
    }

    /** A body that already settled into the interior resumes the same final seating move. */
    private boolean onEntryFloor(YukkuriEntity body) {
        return seatHost != null && target == null && !brimOnly && body.getVehicle() == seatHost.entity()
                && seatHost.insideFloor(seatHost.riders().local(body.getUUID()));
    }

    /** Whether the body's own ability makes one collision-clear hop from a planned stand to {@code target}, judged by the same
     *  arc the takeoff judges. */
    public static boolean reaches(YukkuriEntity body, Vec3 from, Vec3 target) {
        if (!ground(body, target)) return false;
        if (body.getVehicle() instanceof SurfaceHost host)
            from = host.frameAfter(takeoffTicks() - 1).toWorld(host.frame().toLocal(from));
        return new Hop(target).arc(body, vec(from), null, hosts(body, from, target), vec(target), 0) instanceof ArcSolver.Plan;
    }

    /** Nearest centre supported by the bank; the arc and vanilla contact still check the whole collision box. */
    public static Vec3 bankStand(Vec3 edge, Vec3 normal, Vec3 from) {
        return Floor.point(BankStand.nearest(vec(edge), vec(normal), TOUCH, vec(from)));
    }

    /** World blocks carry a landing when their collision top supports the centre, without a full-sole inset. */
    public static boolean ground(YukkuriEntity body, Vec3 feet) {
        var probe = body.getBoundingBox().move(feet.subtract(body.position())).move(0, -TOUCH, 0);
        for (var shape : body.level().getBlockCollisions(body, probe)) for (var box : shape.toAabbs())
            if (box.minX <= feet.x && feet.x < box.maxX && box.minZ <= feet.z && feet.z < box.maxZ
                    && Math.abs(box.maxY - feet.y) <= TOUCH) return true;
        return false;
    }

    /** Compare local horizontal points before applying a host's tilt to the bearing. */
    private static float heading(YukkuriEntity body, SurfaceHost host, Vec3 from, Vec3 to, float facing) {
        double unit = host == null ? 1 : host.frame().unit();
        double local = HopHeading.yaw(vec(from.scale(unit)), vec(to.scale(unit)), facing, TOUCH);
        if (from.subtract(to).horizontalDistance() * unit <= TOUCH) return facing;
        return host == null ? (float) local : SurfaceApproach.yaw(host.frame().toWorld(from), host.frame().toWorld(to));
    }

    private Face faceLeg(YukkuriEntity body) {
        if (seat != null && body.getVehicle() == seat.host().entity())
            return new Face(heading(body, seat.host(), seat.host().riders().local(body.getUUID()), seat.local(), body.yBodyRot));
        return new Face(heading(body, null, body.position(), seat == null ? target : seat.world(), body.yBodyRot));
    }

    /** One leg to a supported point on the current host, without routing through other stops. */
    public static boolean reaches(YukkuriEntity body, SurfaceHost host, Vec3 local) {
        var from = host.riders().local(body.getUUID()).scale(host.frame().unit());
        return new SeatTarget(host, local).available(body) && new Hop(host, local).arc(body, vec(from), host,
                hosts(body, body.position(), host.frame().toWorld(local)), vec(local.scale(host.frame().unit())), takeoffTicks() - 1) instanceof ArcSolver.Plan;
    }

    /** The same supported entry and arc admission for a planned host and an actual boarding hop. */
    public static Vec3 entry(YukkuriEntity body, Vec3 from, HostFrame frame, List<Vec3> stops) {
        return new Hop(from).entryFrom(body, from, frame, stops);
    }

    private Vec3 entryFrom(YukkuriEntity body, Vec3 from, HostFrame frame, List<Vec3> stops) {
        return stops.stream().sorted(Comparator.comparingDouble(p -> frame.toWorld(p).distanceToSqr(entryNear == null ? from : entryNear)))
                .filter(p -> !body.level().getBlockCollisions(body,
                        body.getBoundingBox().move(frame.toWorld(p).subtract(body.position()))).iterator().hasNext())
                .filter(p -> arc(body, vec(from), null, hosts(body, from, frame.toWorld(p)), vec(frame.toWorld(p)), 0) instanceof ArcSolver.Plan)
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
    private ArcSolver.Result arc(YukkuriEntity body, Vec from, SurfaceHost anchor, List<SurfaceHost> hosts, Vec to, int delay) {
        return arc(body, from, anchor, hosts, to, delay, true, false);
    }

    /** As above. {@code diagnose} only adds the rise a refused arc would need to the report; {@code visibleRise} spends more of
     *  the body's height budget the further a hop that ends on the ground goes, so that it reads as a jump. */
    private ArcSolver.Result arc(YukkuriEntity body, Vec from, SurfaceHost anchor, List<SurfaceHost> hosts, Vec to, int delay,
                                 boolean diagnose, boolean visibleRise) {
        long started = System.nanoTime();
        obstruction = "";
        var ability = BodyAbility.of(body);
        var start = new Vec3(from.x(), from.y(), from.z());
        var end = new Vec3(to.x(), to.y(), to.z());
        var frame = anchor == null ? null : anchor.frameAfter(delay);
        float yaw = heading(body, anchor, frame == null ? start : start.scale(1 / frame.unit()),
                frame == null ? end : end.scale(1 / frame.unit()), body.yBodyRot);
        var result = ArcSolver.lowest(from, to, ability.hopHeight(), ability.hopReach(), ability.scale(),
                points -> clear(body, start, anchor, hosts, points, delay, yaw), visibleRise, diagnose);
        timing(body, "arc", started);
        return result;
    }

    private static void timing(YukkuriEntity body, String operation, long started) {
        double millis = (System.nanoTime() - started) / 1_000_000.0;
        if (StatusEvents.trace() && millis >= 50) GameplayLog.event(body, "move",
                "hop admission operation={} millis={}", operation, millis);
    }

    private record ArcKey(Vec from, Vec to, SurfaceHost anchor, List<SurfaceHost> hosts, int delay, boolean diagnose,
                          boolean visibleRise) {}
    private record ArcAnswer(ArcSolver.Result result, String obstruction, boolean bodyBlocked) {}

    private ArcSolver.Result arc(YukkuriEntity body, Vec from, SurfaceHost anchor, List<SurfaceHost> hosts, Vec to, int delay,
                                 Search search, boolean diagnose, boolean visibleRise) {
        var key = new ArcKey(from, to, anchor, hosts, delay, diagnose, visibleRise);
        var answer = search.arcs.computeIfAbsent(key, ignored -> {
            var result = arc(body, from, anchor, hosts, to, delay, diagnose, visibleRise);
            return new ArcAnswer(result, obstruction, bodyBlocked);
        });
        obstruction = answer.obstruction();
        bodyBlocked |= answer.bodyBlocked();
        return answer.result();
    }

    /** Keeps the local stops; the same arc solver admits each check and each takeoff in the current frame. */
    private Flight solve(YukkuriEntity body, int delay) {
        return solve(body, body.position(), delay, new Search());
    }
    private Flight solve(YukkuriEntity body, int delay, Search search) {
        return solve(body, body.position(), delay, search);
    }
    private Flight solve(YukkuriEntity body, Vec3 from, int delay, Search search) {
        SeatTarget candidate = null;
        Vec3 to = target;
        if (seatHost == null && !ground(body, to))
            return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "ground_centre"), null, null);
        if (seatHost != null) {
            if (seatHost.entity().isRemoved()) return new Flight(Feasibility.of(Reason.TARGET_GONE, 0, "host_gone"), null, null);
            candidate = target == null ? seatHost.restEntry(body) : new SeatTarget(seatHost, target);
            if (candidate == null) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "no_spot size=" + body.growthScale() * body.getScale()), null, null);
            if (!seatHost.steady()) return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "host_unsteady"), null, null);
            if (!candidate.available(body)) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "destination_occupied"), null, null);
            to = candidate.world();
        }
        var ability = BodyAbility.of(body);
        SurfaceHost anchor = seatHost != null && body.getVehicle() == seatHost.entity() ? seatHost : null;
        boolean entering = seatHost != null && anchor == null && target == null && !search.stops(seatHost, body).isEmpty();
        if (entering) {
            var stop = entryFrom(body, from, seatHost.frame(), search.stops(seatHost, body).stream()
                    .filter(p -> new SeatTarget(seatHost, p).available(body)).toList());
            if (stop == null) return new Flight(Feasibility.of(Reason.TOO_FAR, 0, "no_reachable_brim"), null, null);
            candidate = new SeatTarget(seatHost, stop);
            to = candidate.world();
        }
        var origin = anchor == null ? from : anchor.riders().local(body.getUUID()).scale(anchor.frame().unit());
        if (anchor == null && delay > 0 && body.getVehicle() instanceof SurfaceHost carrier) {
            var local = carrier.frame().toLocal(body.position());
            float yaw = SurfaceApproach.yaw(body.position(), to);
            var height = carrier.turnSupport(local, body, yaw - carrier.entity().getYRot());
            if (height.isEmpty()) return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "takeoff_support"), null, null);
            origin = carrier.frameAfter(delay).toWorld(new Vec3(local.x, height.getAsDouble(), local.z));
        }
        var destination = anchor == null ? to : candidate.local().scale(anchor.frame().unit());
        var swept = hosts(body, from, to);
        boolean following = anchor != null && path != null;
        if (following) {
            var next = path.get(leg);
            destination = new Vec3(next.at().x(), next.at().y(), next.at().z());
            candidate = new SeatTarget(anchor, destination.scale(1 / anchor.frame().unit()));
            if (!candidate.available(body)) return new Flight(Feasibility.of(Reason.NO_ROOM, 0, "destination_occupied"), null, null);
        }
        if (anchor != null) {
            float yaw = heading(body, anchor, origin.scale(1 / anchor.frame().unit()), candidate.local(), body.yBodyRot);
            if (delay > 0) {
                var height = Floor.turnHeight(anchor, body, origin.scale(1 / anchor.frame().unit()),
                        body.yBodyRot - anchor.entity().getYRot(), yaw - anchor.entity().getYRot());
                if (height.isEmpty()) return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "takeoff_support"), null, null);
                origin = new Vec3(origin.x, height.getAsDouble() * anchor.frame().unit(), origin.z);
            }
            var height = anchor.turnSupport(candidate.local(), body, yaw - anchor.entity().getYRot());
            if (height.isEmpty()) return new Flight(Feasibility.of(Reason.NO_SUPPORT, 0, "landing_support"), null, null);
            candidate = new SeatTarget(anchor, new Vec3(candidate.local().x, height.getAsDouble(), candidate.local().z));
            destination = candidate.local().scale(anchor.frame().unit());
        }
        var result = arc(body, vec(origin), anchor, swept, vec(destination), delay, search, true, seatHost == null);
        if (result instanceof ArcSolver.Plan plan) {
            if (anchor != null && !following) path = List.of(new HopPath.Leg(vec(destination), plan));
            var ready = bank == null || exitClear(body, anchor, delay) ? Feasibility.pass()
                    : Feasibility.of(Reason.NO_SUPPORT, 0, "bank_handoff " + obstruction);
            return new Flight(ready, candidate, plan, following ? leg + 1 < path.size() : entering && !brimOnly);
        }
        String directObstruction = obstruction;
        if (anchor != null && !following) {
            var stops = search.usable(anchor, body);
            long started = System.nanoTime();
            path = HopPath.solve(vec(origin), vec(destination), stops,
                    (a, b) -> arc(body, a, anchor, swept, b, delay, search, false, false));
            timing(body, "path", started);
            if (path != null) {
                var next = path.getFirst();
                var ready = bank == null || exitClear(body, anchor, delay) ? Feasibility.pass()
                        : Feasibility.of(Reason.NO_SUPPORT, 0, "bank_handoff " + obstruction);
                return new Flight(ready, new SeatTarget(anchor,
                        new Vec3(next.at().x(), next.at().y(), next.at().z()).scale(1 / anchor.frame().unit())), next.flight(), path.size() > 1);
            }
        }
        var blocked = (ArcSolver.Blocked) result;
        var reason = blocked.kind() == ArcSolver.Kind.TOO_FAR ? Reason.TOO_FAR : Reason.TOO_HIGH;
        return new Flight(Feasibility.of(reason, blocked.shortfall(), String.format(Locale.ROOT,
                "dx=%.2f dy=%.2f hopHeight=%.2f hopReach=%.2f blocked=%s", origin.subtract(destination).horizontalDistance(),
                destination.y - origin.y, ability.hopHeight(), ability.hopReach(), directObstruction)), null, null);
    }

    private static int marker(String name) { return Math.round(ClipMarkers.of(CLIP).markers().get(name) * 20); }
    static int takeoffTicks() { return marker("takeoff"); }

    /** Admit the bank leg after the host's drift, each landing beat and the supported turn, not at the old brim point. */
    private boolean exitClear(YukkuriEntity body, SurfaceHost host, int delay) {
        if (!ground(body, bank)) { obstruction = "ground_centre"; return false; }
        var local = host.riders().local(body.getUUID());
        float yaw = body.yBodyRot;
        var swept = hosts(body, body.position(), bank);
        for (int i = leg; i < path.size(); i++) {
            var next = path.get(i);
            var at = new Vec3(next.at().x(), next.at().y(), next.at().z()).scale(1 / host.frame().unit());
            float bearing = heading(body, host, local, at, yaw);
            var turnHeight = Floor.turnHeight(host, body, local, yaw - host.entity().getYRot(), bearing - host.entity().getYRot());
            if (turnHeight.isEmpty()) { obstruction = "takeoff_turn"; return false; }
            if (delay > 0) delay += turnTicks(body, yaw, bearing);
            yaw = bearing;
            delay += (int) Math.ceil(next.flight().ticks());
            var height = host.turnSupport(at, body, yaw - host.entity().getYRot());
            if (height.isEmpty()) { obstruction = "landing_support"; return false; }
            local = new Vec3(at.x, height.getAsDouble(), at.z);
            for (int tick = 0; tick <= marker("settled") - marker("land"); tick++, delay++) {
                var stand = host.frameAfter(delay).toWorld(local);
                if (!clear(body, stand, null, swept, List.of(Vec.ZERO), delay, yaw)) return false;
            }
            if (i + 1 < path.size()) delay += takeoffTicks() - 1;
        }
        float bearing = SurfaceApproach.yaw(host.frameAfter(delay).toWorld(local), bank);
        if (Floor.turnHeight(host, body, local, yaw - host.entity().getYRot(), bearing - host.entity().getYRot()).isEmpty()) {
            obstruction = "bank_turn"; return false;
        }
        double angle = Mth.wrapDegrees(bearing - yaw);
        int turns = turnTicks(body, yaw, bearing);
        for (int tick = 1; tick <= turns; tick++, delay++) {
            float facing = (float) (yaw + angle * tick / turns);
            var height = host.turnSupport(local, body, facing - host.entity().getYRot());
            if (height.isEmpty()) { obstruction = "turn_support"; return false; }
            local = new Vec3(local.x, height.getAsDouble(), local.z);
            var stand = host.frameAfter(delay).toWorld(local);
            if (!clear(body, stand, null, swept, List.of(Vec.ZERO), delay, facing)) return false;
        }
        for (int tick = 0; tick < takeoffTicks(); tick++, delay++) {
            var stand = host.frameAfter(delay).toWorld(local);
            if (!clear(body, stand, null, swept, List.of(Vec.ZERO), delay, bearing)) return false;
        }
        var result = arc(body, vec(host.frameAfter(delay).toWorld(local)), null, swept, vec(bank), delay, true, true);
        if (result instanceof ArcSolver.Blocked blocked)
            obstruction = blocked.kind() + " shortfall=" + blocked.shortfall() + " " + obstruction;
        return result instanceof ArcSolver.Plan;
    }

    private static int turnTicks(YukkuriEntity body, float from, float to) {
        return (int) Math.ceil(Math.max(0, Math.abs(Mth.wrapDegrees(to - from)) - Face.FACE_ARRIVED) / BodyAbility.of(body).turnRate());
    }

    @Override public void start(YukkuriEntity body) {
        departure = body.getVehicle() instanceof SurfaceHost host ? host : null;
        aboard = departure != null && departure == seatHost;
        takeoff = takeoffTicks();
        from = body.position();
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (rest != null) return finishSeating(body);
        if (recovery > 0) {
            if (--recovery > 0) return new Running(recovery, TravelMode.CONSUMED);
            return settled(body);
        }
        if (!launched) {
            if (onEntryFloor(body)) {
                var entry = seatHost.restEntry(body);
                if (entry == null) return Outcome.of(Reason.NO_ROOM, 0, "entry_place_lost");
                if (!Floor.advance(seatHost, body, entry.local())) return Outcome.of(Reason.NO_ROOM, 0, "entry_place_blocked");
                double left = seatHost.riders().local(body.getUUID()).distanceTo(entry.local()) * seatHost.frame().unit();
                return left > SurfaceApproach.ARRIVAL ? new Running(left, TravelMode.CONSUMED) : seatFromEntry(body);
            }
            if (crouch == 0) {
                if (legTurn == null) {
                    var next = solve(body, takeoff - 1);
                    if (!next.ready().ok()) return Outcome.of(next.ready().reason(), next.ready().shortfall(), next.ready().detail());
                    seat = next.seat();
                    legTurn = faceLeg(body);
                    legTurn.start(body);
                }
                var turning = legTurn.tick(body);
                if (!(turning instanceof Outcome.Done)) return turning;
                legTurn = null;
                startTick = body.level().getGameTime();
            }
            if (++crouch < takeoff) return new Running(takeoff - crouch, TravelMode.CONSUMED);
            // The host can drift during preparation; face the live leg before committing its impulse.
            legTurn = faceLeg(body);
            legTurn.start(body);
            var turning = legTurn.tick(body);
            if (!(turning instanceof Outcome.Done)) return turning;
            legTurn = null;
            // A supported turn can change the sole height. Solve from the position it actually left behind.
            var launch = solve(body, 0);
            if (!launch.ready().ok()) return Outcome.of(launch.ready().reason(), launch.ready().shortfall(), launch.ready().detail());
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
            if (StatusEvents.trace()) GameplayLog.event(body, "move",
                    "hop leg={} from={} to={} yaw={} bearing={} airTicks={} width={} apex={} ability={}",
                    completedLegs + 1, GameplayLog.at(body.position()), GameplayLog.at(seat == null ? target : seat.world()),
                    body.yBodyRot, SurfaceApproach.yaw(body.position(), seat == null ? target : seat.world()), flight.size(),
                    body.getBbWidth(), plan.apex(), BodyAbility.of(body).detail());
            // Consumed travel must advance the first flight segment on the takeoff tick too.
        }
        startTick = body.level().getGameTime() - marker("land");
        if (seatHost != null && seatHost.entity().isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        if (aboard && body.getVehicle() != seatHost.entity()) return new Outcome.Interrupted("left_host");
        // A sampled endpoint is not ground contact. Let the final downward velocity meet the real floor before handing off.
        if (air >= flight.size() - 1) {
            var contact = ContactResolver.resolve(body);
            if (contact != null) return contact.refused() ? Outcome.of(Reason.NO_ROOM, 0, "mount_refused") : landed(body);
            if (body.onGround()) return landed(body);
            if (body.isInWaterOrBubble()) return Outcome.of(Reason.NO_SUPPORT, 0, "landed_in_water");
            if (++air >= flight.size() + SETTLE) return Outcome.of(Reason.NO_CONTACT, 0, "landing_contact_missing");
            return new Running(flight.size() + SETTLE - air, TravelMode.PASS);
        }
        var p = flight.get(++air);
        var aim = from.add(p.x(), p.y(), p.z());
        if (aboard) aim = seatHost.frame().toWorld(aim.scale(1 / seatHost.frame().unit()));
        var delta = aim.subtract(body.position());
        if (!carriedClear(body, body.position(), aim)) return Outcome.of(Reason.NO_SUPPORT, 0, "held_arc_blocked");
        Motor.velocity(body, aboard ? Vec3.ZERO : delta, "hop_flight");
        var contact = aboard ? null : ContactResolver.resolve(body);
        if (contact != null) return contact.refused() ? Outcome.of(Reason.NO_ROOM, 0, "mount_refused") : landed(body);
        // Cross a bank horizontally before descending, matching the clearance sweep.
        if (delta.y < 0) Motor.move(body, new Vec3(delta.x, 0, delta.z), "hop_flight");
        Motor.move(body, delta.y < 0 ? new Vec3(0, delta.y, 0) : delta, "hop_flight");
        if (aboard) Motor.spot(seatHost, body, seatHost.frame().toLocal(body.position()), seatHost.riders().yaw(body.getUUID()), "hop_start");
        else carriedFlight(body, air);
        peak = Math.max(peak, aboard ? p.y() : body.getY() - from.y);
        double blocked = body.position().distanceTo(aim);
        if (blocked > TOUCH) return Outcome.of(Reason.NO_SUPPORT, blocked, "arc_blocked at=" + GameplayLog.at(body.position()));
        if (air == flight.size() - 1) {
            if (seat != null && !seat.land(body, "hop_contact")) return Outcome.of(Reason.NO_ROOM, 0, "seat_landing_refused");
            if (seat != null) return landed(body);
            // The sampled horizontal travel is already consumed; vanilla only settles down onto the admitted ground point.
            Motor.velocity(body, new Vec3(0, delta.y, 0), "hop_ground_contact");
            return new Running(SETTLE, TravelMode.PASS);
        }
        return new Running(flight.size() - air, TravelMode.CONSUMED);
    }

    /** The carried young keeps its seat through the free flight; the sample is traced only. */
    private static void carriedFlight(YukkuriEntity body, int air) {
        for (var passenger : body.getPassengers()) if (passenger instanceof YukkuriEntity child) {
            body.positionRider(child);
            if (StatusEvents.trace()) GameplayLog.event(body, "family", "carry flight child={} hold={} air={} at={} expected={}",
                    child.getUUID(), ParentCarry.hold(body, child).key(), air, child.position(), ParentCarry.rawSeat(body, child, 1));
        }
    }

    private Progress landed(YukkuriEntity body) {
        launched = false;
        completedLegs++;
        recovery = marker("settled") - marker("land");
        startTick = body.level().getGameTime() - marker("land");
        return new Running(recovery, TravelMode.CONSUMED, true);
    }

    private Progress settled(YukkuriEntity body) {
        if (!more) {
            if (bank != null) {
                target = bank; bank = null; seatHost = null; aboard = false; path = null; leg = 0;
                crouch = air = 0;
                startTick = body.level().getGameTime();
                return new Running(takeoff, TravelMode.CONSUMED, true);
            }
            if (seatHost == null) return ground(body, body.position())
                    ? new Outcome.Done("at=" + GameplayLog.at(body.position()) + " centre_support=true")
                    : Outcome.of(Reason.NO_SUPPORT, 0, "ground_centre_lost");
            if (target != null || brimOnly) return new Outcome.Done("at=" + GameplayLog.at(body.position()));
            return seatFromEntry(body);
        }
        if (!aboard) { aboard = true; path = null; leg = 0; }
        else leg++;
        launched = false;
        crouch = air = 0;
        startTick = body.level().getGameTime();
        return new Running(takeoff, TravelMode.CONSUMED, true);
    }

    /** The shared final turn and supported floor move after a hop or climb reaches the interior entry. */
    Progress seatFromEntry(YukkuriEntity body) {
        rest = seatHost.restSpot(body);
        if (rest == null) return Outcome.of(Reason.NO_ROOM, 0, "rest_place_lost");
        seatingTurn = new Face(seatHost.restFacing(body));
        seatingTurn.start(body);
        return new Running(1, TravelMode.CONSUMED, true);
    }
    private Progress finishSeating(YukkuriEntity body) {
        if (seatingTurn != null) {
            var progress = seatingTurn.tick(body);
            if (!(progress instanceof Outcome.Done)) return progress;
            seatingTurn = null;
        }
        if (!Floor.advance(seatHost, body, rest.local())) return Outcome.of(Reason.NO_ROOM, 0, "rest_place_blocked");
        if (seatHost.settled(body)) {
            GameplayLog.event(body, "body", "boarding complete place=cabin local={} yaw={}", seatHost.riders().local(body.getUUID()), seatHost.riders().yaw(body.getUUID()));
            return new Outcome.Done("rest_place=" + rest.local());
        }
        return new Running(seatHost.riders().local(body.getUUID()).distanceTo(rest.local()), TravelMode.CONSUMED);
    }

    @Override public boolean interruptible() { return !launched && recovery == 0; }
    @Override public boolean airborne() { return launched; }
    @Override public MotionPose pose() {
        if (legTurn != null) return legTurn.pose();
        if (seatingTurn != null) return seatingTurn.pose();
        return rest != null ? MotionPose.NONE : new MotionPose(CLIP, startTick, false);
    }
    @Override public String startDetail() { return "from=" + GameplayLog.at(from) + " to=" + (seatHost == null ? GameplayLog.at(target) : seatHost.entity().getUUID())
            + (bank == null ? "" : " bank=" + GameplayLog.at(bank)); }
    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "legs=%d peak=%.2f airTicks=%d", completedLegs, peak, air); }

    private boolean clear(YukkuriEntity body, Vec3 from, SurfaceHost anchor, List<SurfaceHost> hosts, List<Vec> points, int delay, float yaw) {
        AABB base = body.getBoundingBox();
        Vec3 previous = anchor == null ? from : anchor.frameAfter(delay).toWorld(from.scale(1 / anchor.frame().unit()));
        for (int tick = 0; tick < points.size(); tick++) {
            var point = points.get(tick);
            Vec3 at = from.add(point.x(), point.y(), point.z());
            var frame = anchor == null ? null : anchor.frameAfter(delay + tick);
            if (frame != null) at = frame.toWorld(at.scale(1 / frame.unit()));
            if (anchor != null && !anchor.room(body, frame.toLocal(at))) {
                obstruction = "host_room at=" + GameplayLog.at(at); return false;
            }
            Vec3 corner = at.y < previous.y ? new Vec3(at.x, previous.y, at.z) : new Vec3(previous.x, at.y, previous.z);
            if (!carriedClear(body, previous, at, yaw)) {
                obstruction = "held_world at=" + GameplayLog.at(at); return false;
            }
            AABB startBox = base.move(previous.subtract(body.position()));
            AABB cornerBox = base.move(corner.subtract(body.position()));
            AABB atBox = base.move(at.subtract(body.position()));
            if (!body.level().noCollision(body, startBox.minmax(cornerBox)) || !body.level().noCollision(body, cornerBox.minmax(atBox))) {
                bodyBlocked |= bodyCollision(body, startBox.minmax(cornerBox), cornerBox.minmax(atBox));
                obstruction = "world at=" + GameplayLog.at(at) + " from=" + GameplayLog.at(previous);
                return false;
            }
            for (SurfaceHost host : hosts) {
                var contactFrame = host == anchor ? frame : host.frameAfter(delay + tick);
                Vec3 local = contactFrame.toLocal(at);
                var height = host.support(local, Floor.shape(host, body, yaw - host.entity().getYRot()));
                if (height.isPresent() && local.y < height.getAsDouble() - TOUCH / host.frame().unit()) {
                    obstruction = "surface at=" + GameplayLog.at(at); return false;
                }
                for (var passenger : body.getPassengers()) if (passenger instanceof YukkuriEntity child) {
                    var offset = carriedOffset(body, child, yaw);
                    var childAt = at.add(offset);
                    var childLocal = contactFrame.toLocal(childAt);
                    var childTop = host.support(childLocal, Floor.shape(host, child,
                            child.yBodyRot + yaw - body.yBodyRot - host.entity().getYRot()));
                    if (childTop.isPresent() && childLocal.y < childTop.getAsDouble() - TOUCH / host.frame().unit()) {
                        obstruction = "held_surface at=" + GameplayLog.at(childAt); return false;
                    }
                }
            }
            previous = at;
        }
        return true;
    }

    /** The carried boxes follow both legs of the same horizontal/vertical sweep as their carrier. */
    private boolean carriedClear(YukkuriEntity body, Vec3 from, Vec3 to) {
        return carriedClear(body, from, to, body.yBodyRot);
    }

    private boolean carriedClear(YukkuriEntity body, Vec3 from, Vec3 to, float yaw) {
        var corner = to.y < from.y ? new Vec3(to.x, from.y, to.z) : new Vec3(from.x, to.y, from.z);
        for (var passenger : body.getPassengers()) if (passenger instanceof YukkuriEntity child) {
            var base = child.getBoundingBox().move(body.position().add(carriedOffset(body, child, yaw)).subtract(child.position()));
            var start = base.move(from.subtract(body.position()));
            var bend = base.move(corner.subtract(body.position()));
            var end = base.move(to.subtract(body.position()));
            var first = start.minmax(bend);
            var second = bend.minmax(end);
            if (!body.level().noCollision(child, first) || !body.level().noCollision(child, second)) {
                bodyBlocked |= bodyCollision(child, first, second);
                return false;
            }
        }
        return true;
    }

    private static Vec3 carriedOffset(YukkuriEntity body, YukkuriEntity child, float yaw) {
        return child.position().subtract(body.position()).yRot((float) Math.toRadians(body.yBodyRot - yaw));
    }

    private static boolean bodyCollision(YukkuriEntity body, AABB corner, AABB at) {
        return !body.level().getEntitiesOfClass(YukkuriEntity.class, corner.minmax(at), other -> other != body && other.isAlive()
                && (other.getBoundingBox().intersects(corner) || other.getBoundingBox().intersects(at))).isEmpty();
    }

    private static Vec vec(Vec3 at) { return new Vec(at.x, at.y, at.z); }
}
