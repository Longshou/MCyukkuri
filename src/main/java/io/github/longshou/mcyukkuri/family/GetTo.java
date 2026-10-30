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
 * A young's own way off a host that asks to be unloaded: a capable young hops out over the
 * brim, a dependent one walks to the brim on the taker's side and waits to be fetched from there.
 *
 * <p>Off the host is what this intent promises: once the young is grabbed, put down or has hopped away, the rest is the
 * taker's ({@link Fetch}) or vanilla physics. Nothing here names a height or a duration; they come from {@link BodyAbility}
 * and the arc solver.
 */
public final class GetTo implements Intent {
    /** A hop, or a short walk to the brim and a wait for the taker. */
    static final int DEADLINE_TICKS = 400;
    /** The wait at the brim; the taker is fetching the same young in the same window. */
    static final int AWAIT_FETCH_TICKS = 200;
    /** How far inside the brim the walk stops, as a fraction of the brim reach (just inside, so a hop or a grab reaches). */
    private static final double EDGE_INSIDE = .85;
    /** Bounded landing scan over the brim directions, nearest to the young first. */
    private static final int LANDING_TRIES = 8;

    private final YukkuriEntity child;
    private final SurfaceHost host;
    private final long started;

    public GetTo(YukkuriEntity child, SurfaceHost host) {
        this.child = child;
        this.host = host;
        this.started = child.level().getGameTime();
    }

    @Override public String id() { return "get_to"; }

    /** Not riding this host any more: hopped off, fetched, or set down by someone else. */
    @Override public boolean satisfied() {
        return !(Attach.of(child) instanceof AttachState.OnHost on && on.host() == host);
    }

    @Override public long deadline() { return started + DEADLINE_TICKS; }

    @Override public List<Method> methods() {
        return List.of(new HopOff(), new WalkToEdge());
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

    /** Walk to the brim on the taker's side and wait there: a dependent young is carried out from that edge. */
    private final class WalkToEdge implements Method {
        private int state;
        private Vec3 edge;

        @Override public String id() { return "walk_to_edge"; }

        @Override public Feasibility applicable() {
            edge = null;
            var rim = edgePoint();
            if (rim.isEmpty()) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_rim");
            var inside = new Vec3(rim.get().x * EDGE_INSIDE, 0, rim.get().z * EDGE_INSIDE);
            var height = host.support(inside, Occupancy.foot(child.getBbWidth()));
            if (height.isEmpty()) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_surface");
            edge = new Vec3(inside.x, height.getAsDouble(), inside.z);
            return Feasibility.pass();
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.NO_ROOM, Reason.NO_CONTACT); }

        @Override public Step next() {
            if (edge == null) return null;
            if (state++ == 0) return new Step.Do(new Walk(host, edge));
            return new Step.Wait(() -> !Unload.onHost(child, host), AWAIT_FETCH_TICKS, "await_fetch");
        }
    }

    /**
     * The brim point on the taker's side, in hat units: the host names it as {@code exit} (the brim edge toward its owner,
     * which is the taker besides a catch hat); a host without one falls back to the brim nearest the young.
     */
    private Optional<Vec3> edgePoint() {
        var exit = host.spots().get("exit");
        if (exit != null) return Optional.of(exit);
        return ContactGeometry.nearestRim(child, host).map(host.frame()::toLocal);
    }
}
