package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.GridPath;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Walk to a world target, or a host-local one: on the ground {@link SurfaceApproach} routes it, on a host a {@link GridPath} route is walked step by step against support and room. */
public final class Walk implements Action {
    public static final String ID = "walk";
    /** Navigation coefficient for a walk that names none. */
    private static final double SPEED = 1;
    /** Local distance (hat units) that counts as arrived on a host. */
    private static final double HOST_ARRIVED = .05;
    /** Horizontal distance (blocks) that counts as arrived at a stand beside another body. */
    private static final double STAND_ARRIVED = .1;
    /** Ticks between two {@code walk.sample} detail lines. */
    private static final int SAMPLE_TICKS = 20;
    /** What entering a cell beside the brim costs on a host route, in cells (the rim penalty). */
    private static final double RIM_WEIGHT = 1;

    private final Vec3 target;
    private final SurfaceHost host;
    private final Vec3 local;
    private final double speed;
    private final double arrive;
    /** A body the route must go around instead of through, or null (the shared {@code SurfaceApproach.walkAround}). */
    private final Entity avoid;
    private double pace;
    private Vec3 from;
    /** Where the ground walk actually goes (the target, or moved clear of a hat's brim), and the navigation's replans seen so far. */
    private Vec3 goal;
    private boolean navDone;
    private int nodesLeft, replans;
    /** The host route's waypoints (hat units, the target last) and the one being walked to. */
    private List<double[]> route = List.of();
    private int leg;
    /** A host walk replans once, around the other riders, when a step leaves the surface or meets a body. */
    private boolean replanned;

    /** Walks to a world point, arrived when {@link SurfaceApproach#arrived}. */
    public Walk(Vec3 target) {
        this(target, SPEED, 0, null);
    }

    private Walk(Vec3 target, double speed, double arrive) {
        this(target, speed, arrive, null);
    }

    private Walk(Vec3 target, double speed, double arrive, Entity avoid) {
        if (target == null) throw new IllegalArgumentException("Walk needs a target");
        this.target = target;
        this.speed = speed;
        this.arrive = Math.max(0, arrive);
        this.avoid = avoid;
        this.host = null;
        this.local = null;
    }

    /** Walks to a world point around another body (a partner at a rendezvous): the shared {@link SurfaceApproach#walkAround} plans the route around it instead of through it. */
    public static Walk around(Vec3 target, Entity other) {
        return new Walk(target, SPEED, 0, other);
    }

    /** Walks to a stand beside another body: a supported floor point, so the last 0.1 blocks horizontally count as arrived (the stand's height need not equal the other body's). */
    public static Walk stand(Vec3 target, double speed) {
        return new Walk(target, speed, STAND_ARRIVED);
    }

    /** Walks to a target on {@code host}, in hat units of that host's frame. */
    public Walk(SurfaceHost host, Vec3 local) {
        if (host == null || local == null) throw new IllegalArgumentException("A host walk needs the host and its local target");
        this.host = host;
        this.local = local;
        this.target = null;
        this.speed = SPEED;
        this.arrive = 0;
        this.avoid = null;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        from = body.position();
        if (host != null) {
            if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host)
                return Feasibility.of(Reason.NO_SUPPORT, 0, "not_on_host");
            if (host.support(local, Occupancy.foot(body.getBbWidth())).isEmpty())
                return Feasibility.of(Reason.NO_SUPPORT, 0, "no_surface");
            return plan(body, false);
        }
        return SurfaceApproach.canApproach(body, target)
                ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_route");
    }

    @Override public void start(YukkuriEntity body) {
        pace = (host == null ? BodyAbility.of(body) : BodyAbility.of(body, host.load())).walkSpeed() * BodyAbility.carrying(body);
        from = body.position();
        goal = target;
        replans = replans(body);
    }

    @Override public Progress tick(YukkuriEntity body) {
        sample(body);
        return host != null ? hostTick(body) : groundTick(body);
    }

    @Override public void abort(YukkuriEntity body, String why) {
        if (host == null) SurfaceApproach.halt(body);
    }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "from=%s to=%s arrive=%.2f",
                GameplayLog.at(from), GameplayLog.at(host == null ? target : host.frame().toWorld(local)), arrive);
    }

    /** The ground walk's end line: whether navigation had finished and how many nodes were left, next to the outcome. */
    @Override public String endDetail(Outcome outcome) {
        if (host != null) return String.format(Locale.ROOT, "leg=%d/%d", Math.min(leg + 1, route.size()), route.size());
        return String.format(Locale.ROOT, "navDone=%b nodesLeft=%d", navDone, nodesLeft)
                + (goal == null || goal.equals(target) ? "" : " snapped=" + GameplayLog.at(goal));
    }

    private Progress groundTick(YukkuriEntity body) {
        double paceNow = speed * BodyAbility.carrying(body);
        boolean arrived = avoid == null
                ? SurfaceApproach.walk(body, target, paceNow)
                : SurfaceApproach.walkAround(body, target, avoid, paceNow);
        var moved = SurfaceApproach.goal(body, target);
        if (moved != null) goal = moved;
        double flat = body.position().subtract(goal).horizontalDistance();
        double left = remaining(body);
        readNavigation(body);
        if (arrived || arrive > 0 && flat <= arrive) {
            if (!arrived) SurfaceApproach.halt(body);
            return new Outcome.Done(String.format(Locale.ROOT, "at=%s flat=%.2f", GameplayLog.at(body.position()), flat));
        }
        if (SurfaceApproach.lastResult(body, target) == SurfaceApproach.Result.BLOCKED)
            return Outcome.of(Reason.NO_PATH, left, String.format(Locale.ROOT, "left=%.2f route=blocked", left));
        // A route planned round what held the body up is a new way, measured from here (each blocker once).
        int seen = replans(body);
        boolean fresh = seen != replans;
        replans = seen;
        return new Running(left, TravelMode.PASS, fresh);
    }

    private static int replans(YukkuriEntity body) {
        return body.getNavigation() instanceof YukkuriGroundNavigation ground ? ground.replans() : 0;
    }

    /** Plans the host route on its walk grid, both ends snapped to a supported cell: no route is {@code NO_PATH} with the straight distance; a replan keeps off other riders. */
    private Feasibility plan(YukkuriEntity body, boolean aroundRiders) {
        var spot = host.riders().local(body.getUUID());
        double unit = Math.max(1E-6, host.frame().unit());
        var grid = host.walkGrid(body, aroundRiders);
        int[] start = grid.nearest(spot.x, spot.z), goal = grid.nearest(local.x, local.z);
        var path = start == null || goal == null ? List.<double[]>of()
                : GridPath.find(grid, grid.centreX(start[0]), grid.centreZ(start[1]), grid.centreX(goal[0]), grid.centreZ(goal[1]),
                        BodyAbility.of(body, host.load()).stepUp() / unit, RIM_WEIGHT);
        if (path.isEmpty()) {
            double distance = Math.hypot(local.x - spot.x, local.z - spot.z) * unit;
            String detail = String.format(Locale.ROOT, "no_grid_path distance=%.2f from=%s to=%s grid=%dx%d", distance, spot, local,
                    grid.nx(), grid.nz());
            // Observation only: the refusal line of the driver carries no distance.
            GameplayLog.limited(body, "action", "walk.no_path", 100, "walk refused host={} {}", host.entity().getUUID(), detail);
            return Feasibility.of(Reason.NO_PATH, distance, detail);
        }
        var legs = new ArrayList<>(path.subList(1, path.size()));
        if (!legs.isEmpty()) legs.removeLast();
        legs.add(new double[]{local.x, local.z});
        route = legs;
        leg = 0;
        return Feasibility.pass();
    }

    private Progress hostTick(YukkuriEntity body) {
        // The body left the host (grabbed, put down, host gone): the walk is over and must not write a rider spot it no longer has.
        if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host) return new Outcome.Done("left_host");
        Vec3 spot = host.riders().local(body.getUUID());
        if (Math.hypot(local.x - spot.x, local.z - spot.z) <= HOST_ARRIVED) return new Outcome.Done(String.format(Locale.ROOT, "local=%s", local));
        double[] aim = route.get(leg);
        double dx = aim[0] - spot.x, dz = aim[1] - spot.z, d = Math.hypot(dx, dz);
        double step = Math.min(pace / Math.max(1E-6, host.frame().unit()), d);
        if (d <= step && leg < route.size() - 1) leg++;
        Vec3 next = d < 1E-9 ? spot : new Vec3(spot.x + dx / d * step, 0, spot.z + dz / d * step);
        var height = host.support(next, Occupancy.foot(body.getBbWidth()));
        if (height.isEmpty()) return replan(body, spot) ? new Running(routeLeft(spot), TravelMode.CONSUMED) : Outcome.of(Reason.NO_SUPPORT, 0, "off_surface");
        next = new Vec3(next.x, height.getAsDouble(), next.z);
        if (!host.room(body, next)) return replan(body, spot) ? new Running(routeLeft(spot), TravelMode.CONSUMED) : Outcome.of(Reason.NO_ROOM, 0, "occupied");
        host.riders().put(body.getUUID(), next, localYaw(body, host));
        return new Running(routeLeft(next), TravelMode.CONSUMED);
    }

    /** The one replan of a host walk, around the other riders' feet: true when it found a route to go on with. */
    private boolean replan(YukkuriEntity body, Vec3 spot) {
        if (replanned) return false;
        replanned = true;
        return plan(body, true).ok();
    }

    /** The host route still ahead of a local spot (hat units): to the current waypoint, then waypoint to waypoint. */
    private double routeLeft(Vec3 at) {
        double left = 0, x = at.x, z = at.z;
        for (int i = leg; i < route.size(); i++) {
            left += Math.hypot(route.get(i)[0] - x, route.get(i)[1] - z);
            x = route.get(i)[0];
            z = route.get(i)[1];
        }
        return left;
    }

    /** The route still ahead: the installed path's remaining nodes plus the last leg, or the straight line without one. */
    private double remaining(YukkuriEntity body) {
        Vec3 at = body.position();
        var path = body.getNavigation().getPath();
        if (path == null || path.isDone() || path.getNodeCount() <= 0) {
            // steering round a footprint, the way round is what is left, not the straight line
            double way = body.getNavigation() instanceof YukkuriGroundNavigation ground ? ground.way(goal) : Double.NaN;
            return Double.isFinite(way) ? way : at.distanceTo(goal);
        }
        double left = 0;
        for (int i = Math.max(0, path.getNextNodeIndex()); i < path.getNodeCount(); i++) {
            Vec3 node = path.getEntityPosAtNode(body, i);
            left += at.distanceTo(node);
            at = node;
        }
        return left + at.distanceTo(goal);
    }

    private void readNavigation(YukkuriEntity body) {
        var navigation = body.getNavigation();
        var path = navigation.getPath();
        navDone = navigation.isDone();
        nodesLeft = path == null ? 0 : Math.max(0, path.getNodeCount() - path.getNextNodeIndex());
    }

    /** The rider table's yaw convention: the body's own yaw relative to the host frame. */
    private static float localYaw(YukkuriEntity body, SurfaceHost host) {
        return Mth.wrapDegrees(body.getYRot() - (float) Math.toDegrees(host.frame().yawRad()));
    }

    /** {@code walk.sample}: position, navigation state, nodes left, owner and the metric, only with the detail switch on. */
    private void sample(YukkuriEntity body) {
        if (!GameplayLog.detailEnabled()) return;
        var navigation = body.getNavigation();
        var path = navigation.getPath();
        int nodes = path == null ? 0 : Math.max(0, path.getNodeCount() - path.getNextNodeIndex());
        var owner = body.motor().owner();
        GameplayLog.limited(body, "walk", "sample", SAMPLE_TICKS,
                "sample at={} from={} target={} host={} navDone={} nodesLeft={} owner={} metric={}",
                GameplayLog.at(body.position()), GameplayLog.at(from),
                GameplayLog.at(host == null ? target : host.frame().toWorld(local)),
                host == null ? "ground" : host.entity().getUUID(),
                navigation.isDone(), nodes, owner == null ? "none" : owner.ownerId(),
                String.format(Locale.ROOT, "%.2f", host == null ? remaining(body) : routeLeft(host.riders().local(body.getUUID()))));
    }
}
