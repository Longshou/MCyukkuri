package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/**
 * A young's own way on a host. Asked off ({@code leave}, the host's unload ask): a capable young hops out over the brim, a
 * dependent one walks to the brim on the taker's side and waits to be fetched from there; off the host is what it promises,
 * the rest is the taker's ({@link Fetch}) or vanilla physics. Attended ({@code attended()}, a catcher watching): a crawler
 * crawls to a free place at the brim on the attendant's side, out of the hollow's landing zone, and stays there; nobody hops
 * off. Nothing here names a height or a duration; they come from {@link BodyAbility} and the arc solver.
 */
public final class GetTo implements Intent {
    /** A hop, or a short walk to the brim and a wait for the taker. */
    static final int DEADLINE_TICKS = 400;
    /** The wait at the brim; the taker is fetching the same young in the same window. */
    static final int AWAIT_FETCH_TICKS = 200;
    /** How far inside the brim the walk stops, as a fraction of the brim reach (just inside, so a hop or a grab reaches). */
    private static final double EDGE_INSIDE = .85;
    /** Bounded landing scan over the brim directions, nearest to the young first; the same bound for a free place at the edge. */
    private static final int LANDING_TRIES = 8;
    /** The edge places tried along each brim direction, as fractions of the brim reach. */
    private static final double[] EDGE_FRACTIONS = {EDGE_INSIDE, .7};
    /** The hollow's landing zone, where the next newborn comes down (a catch hat fills from its middle): a waiting crawler keeps
     *  this many of its own foot radii from the middle (the next one's foot, its own, one of spread; project design). */
    private static final double LANDING_KEEP = 3;
    /** How far round from the attendant's side (degrees) a place still counts as its side of the brim. */
    private static final double SIDE = 60;

    private final YukkuriEntity child;
    private final SurfaceHost host;
    private final boolean leave;
    private final long started;

    /** @param leave asked off the host (its unload ask), else gathering at the attendant's side */
    public GetTo(YukkuriEntity child, SurfaceHost host, boolean leave) {
        this.child = child;
        this.host = host;
        this.leave = leave;
        this.started = child.level().getGameTime();
    }

    @Override public String id() { return leave ? "get_to" : "gather_at_edge"; }

    /** Not riding this host any more (hopped off, fetched, set down by someone else), or, gathering, waiting at the brim. */
    @Override public boolean satisfied() {
        return !(Attach.of(child) instanceof AttachState.OnHost on && on.host() == host) || !leave && gathered(child, host);
    }

    @Override public long deadline() { return started + DEADLINE_TICKS; }

    @Override public List<Method> methods() {
        return leave ? List.of(new HopOff(), new WalkToEdge()) : List.of(new WalkToEdge());
    }

    /** Its place on the host lies at the brim on the side of the host's exit (its owner's side), out of the landing zone. */
    static boolean gathered(YukkuriEntity child, SurfaceHost host) {
        var exit = edgePoint(child, host);
        return Attach.of(child) instanceof AttachState.OnHost on && on.host() == host && exit.isPresent()
                && edge(child, host, host.riders().local(child.getUUID()), exit.get());
    }

    /** A place (hat units) out of the landing zone round the middle and within {@link #SIDE} of the exit's bearing. */
    private static boolean edge(YukkuriEntity child, SurfaceHost host, Vec3 at, Vec3 exit) {
        var centre = host.spots().getOrDefault("centre", Vec3.ZERO);
        double ax = at.x - centre.x, az = at.z - centre.z, ex = exit.x - centre.x, ez = exit.z - centre.z;
        double keep = LANDING_KEEP * Occupancy.foot(child.getBbWidth()) / host.frame().unit(), r = Math.hypot(ax, az);
        return r >= keep && (ax * ex + az * ez) >= Math.cos(Math.toRadians(SIDE)) * r * Math.hypot(ex, ez);
    }

    /** Ground points just outside the brim, nearest to the young first (the brim samples already carry the tilt). */
    private List<Vec3> outsideBrim() {
        var origin = host.entity().position();
        var directions = new ArrayList<Vec3>(host.rim());
        directions.sort(Comparator.comparingDouble(point -> point.distanceToSqr(child.position())));
        var out = new ArrayList<Vec3>();
        for (var point : directions) {
            if (out.size() >= LANDING_TRIES) break;
            var away = point.subtract(origin).multiply(1, 0, 1);
            double reach = away.horizontalDistance();
            if (reach <= 1E-6) continue;
            // Probe at the host's own base height: the ground around a set-down host lies there, whatever the brim height is.
            var near = new Vec3(point.x + away.x / reach * (child.getBbWidth() * .5 + .15), origin.y + .1,
                    point.z + away.z / reach * (child.getBbWidth() * .5 + .15));
            var ground = SurfaceApproach.floor(child, near);
            if (ground != null) out.add(ground);
        }
        return out;
    }

    /** Hop over the brim to the first landing the arc solver accepts. */
    private final class HopOff implements Method {
        private Vec3 landing;
        private Reason refusal = Reason.NO_SUPPORT;
        private double shortfall;

        @Override public String id() { return "hop_off"; }

        @Override public Feasibility applicable() {
            landing = null;
            shortfall = 0;
            if (!BodyAbility.of(child).canJump()) return Feasibility.of(Reason.TOO_HIGH, 0, "cannot_jump");
            for (var at : outsideBrim()) {
                var check = new Hop(at).check(child);
                if (check.ok()) { landing = at; return Feasibility.pass(); }
                refusal = check.reason();
                shortfall = check.shortfall();
            }
            return Feasibility.of(refusal == null ? Reason.NO_SUPPORT : refusal, shortfall, "no_landing");
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.TOO_FAR, Reason.TOO_HIGH, Reason.NO_SUPPORT); }

        @Override public Step next() { return landing == null ? null : new Step.Do(new Hop(landing)); }
    }

    /** Walk (a crawler crawls) to the brim on the taker's side and wait there: a dependent young is carried out from that edge. */
    private final class WalkToEdge implements Method {
        private int state;
        private Vec3 edge;

        @Override public String id() { return "walk_to_edge"; }

        @Override public Feasibility applicable() {
            edge = null;
            var exit = edgePoint(child, host);
            if (exit.isEmpty()) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_rim");
            // Just inside the brim on the taker's side, or the free place nearest it along the brim (the siblings wait there too),
            // never in the hollow's landing zone.
            var frame = host.frame();
            var aim = frame.toWorld(exit.get());
            for (var rim : host.rim().stream().sorted(Comparator.comparingDouble(p -> p.distanceToSqr(aim))).limit(LANDING_TRIES).toList()) {
                var local = frame.toLocal(rim);
                for (double share : EDGE_FRACTIONS) {
                    var inside = new Vec3(local.x * share, 0, local.z * share);
                    var height = host.support(inside, child);
                    if (height.isEmpty() || !edge(child, host, inside, exit.get())) continue;
                    var at = new Vec3(inside.x, height.getAsDouble(), inside.z);
                    if (host.room(child, at)) { edge = at; return Feasibility.pass(); }
                }
            }
            return Feasibility.of(Reason.NO_ROOM, 0, "edge_taken");
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.NO_ROOM, Reason.NO_CONTACT); }

        @Override public Step next() {
            if (edge == null) return null;
            if (state++ == 0) return new Step.Do(new Walk(host, edge));
            // Gathering ends at the edge (satisfied); asked off, it waits there to be fetched.
            return leave ? new Step.Wait(() -> !Unload.onHost(child, host), AWAIT_FETCH_TICKS, "await_fetch") : null;
        }
    }

    /**
     * The brim point on the taker's side, in hat units: the host names it as {@code exit} (the brim edge toward its owner,
     * which is the taker besides a catch hat); a host without one falls back to the brim nearest the young.
     */
    private static Optional<Vec3> edgePoint(YukkuriEntity child, SurfaceHost host) {
        var exit = host.spots().get("exit");
        if (exit != null) return Optional.of(exit);
        return ContactGeometry.nearestRim(child, host).map(host.frame()::toLocal);
    }
}
