package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Walk to a world target on the ground, or to a host-local target on a {@link SurfaceHost} (spec 9.4).
 *
 * <p>On the ground the shared {@link SurfaceApproach} owns the route and the last step, and vanilla travel integrates it.
 * On a host the body moves in local coordinates only: at most {@code walkSpeed / unit} hat units per tick, checked against
 * the host's own height field and room rule and written into {@code riders()}, which {@code positionRider} turns into the
 * world position. Nowhere here picks a distance or a duration: they come from {@link BodyAbility}, and the carrying scale
 * is the one the spec's Carry row names.
 *
 * <p>The ground watchdog metric is the route still ahead of the body (the installed path's remaining nodes plus the last
 * leg, or the straight distance when no path is installed), and {@code walk.sample} prints it every 20 ticks with the
 * detail switch on, so a playtest log can tell a bad route from a body that is not being driven (spec 13.1, E11-3).
 */
public final class Walk implements Action {
    public static final String ID = "walk";
    /** Navigation coefficient for a walk that names none. */
    private static final double SPEED = 1;
    /** Local distance (hat units) that counts as arrived on a host. */
    private static final double HOST_ARRIVED = .05;
    /** Horizontal distance (blocks) that counts as arrived at a stand beside another body. */
    private static final double STAND_ARRIVED = .1;
    /** Ticks between two {@code walk.sample} detail lines (spec 13.1). */
    private static final int SAMPLE_TICKS = 20;

    private final Vec3 target;
    private final SurfaceHost host;
    private final Vec3 local;
    private final double speed;
    private final double arrive;
    /** A body the route must go around instead of through, or null (the shared {@code SurfaceApproach.walkAround}). */
    private final Entity avoid;
    private double pace;
    private Vec3 from;
    private boolean navDone;
    private int nodesLeft;

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

    /**
     * Walks to a world point around another body (a partner at a rendezvous): the shared
     * {@link SurfaceApproach#walkAround} plans the route around it instead of through it. Arrived when
     * {@link SurfaceApproach#arrived}.
     */
    public static Walk around(Vec3 target, Entity other) {
        return new Walk(target, SPEED, 0, other);
    }

    /**
     * Walks to a stand beside another body: a supported floor point, so the last 0.1 blocks horizontally count as arrived
     * (the stand's height need not equal the other body's).
     */
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
            if (host.support(local, Occupancy.FOOT * body.getBbWidth()).isEmpty())
                return Feasibility.of(Reason.NO_SUPPORT, 0, "no_surface");
            return Feasibility.pass();
        }
        return SurfaceApproach.canApproach(body, target)
                ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_route");
    }

    @Override public void start(YukkuriEntity body) {
        pace = BodyAbility.of(body).walkSpeed() * carrying(body);
        from = body.position();
    }

    @Override public Progress tick(YukkuriEntity body) {
        sample(body);
        return host != null ? hostTick(body) : groundTick(body);
    }

    @Override public boolean interruptible() { return true; }

    @Override public void abort(YukkuriEntity body, String why) {
        if (host == null) SurfaceApproach.halt(body);
    }

    @Override public MotionPose pose() { return MotionPose.NONE; }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "from=%s to=%s arrive=%.2f",
                GameplayLog.at(from), GameplayLog.at(host == null ? target : host.frame().toWorld(local)), arrive);
    }

    /** Spec 13.1's walk end line: whether navigation had finished and how many nodes were left, next to the outcome. */
    @Override public String endDetail(Outcome outcome) {
        if (host != null) return "";
        return String.format(Locale.ROOT, "navDone=%b nodesLeft=%d", navDone, nodesLeft);
    }

    private Progress groundTick(YukkuriEntity body) {
        double paceNow = speed * carrying(body);
        boolean arrived = avoid == null
                ? SurfaceApproach.walk(body, target, paceNow)
                : SurfaceApproach.walkAround(body, target, avoid, paceNow);
        double flat = body.position().subtract(target).horizontalDistance();
        double left = remaining(body);
        readNavigation(body);
        if (arrived || arrive > 0 && flat <= arrive) {
            if (!arrived) SurfaceApproach.halt(body);
            return new Outcome.Done(String.format(Locale.ROOT, "at=%s flat=%.2f", GameplayLog.at(body.position()), flat));
        }
        if (SurfaceApproach.lastResult(body, target) == SurfaceApproach.Result.BLOCKED)
            return Outcome.of(Reason.NO_PATH, left, String.format(Locale.ROOT, "left=%.2f route=blocked", left));
        return new Running(left, TravelMode.PASS);
    }

    private Progress hostTick(YukkuriEntity body) {
        // The body left the host (grabbed, put down, host gone): the walk is over and must not write a rider spot it no
        // longer has (that spot is the host's only key set, spec 5.3 I2).
        if (!(Attach.of(body) instanceof AttachState.OnHost on) || on.host() != host) return new Outcome.Done("left_host");
        Vec3 spot = host.riders().local(body.getUUID());
        double dx = local.x - spot.x, dz = local.z - spot.z, left = Math.hypot(dx, dz);
        if (left <= HOST_ARRIVED) return new Outcome.Done(String.format(Locale.ROOT, "local=%s", local));
        double unit = Math.max(1E-6, host.frame().unit());
        double step = Math.min(pace / unit, left);
        Vec3 next = new Vec3(spot.x + dx / left * step, 0, spot.z + dz / left * step);
        var height = host.support(next, Occupancy.FOOT * body.getBbWidth());
        if (height.isEmpty()) return Outcome.of(Reason.NO_SUPPORT, 0, "off_surface");
        next = new Vec3(next.x, height.getAsDouble(), next.z);
        if (!host.room(body, next)) return Outcome.of(Reason.NO_ROOM, 0, "occupied");
        host.riders().put(body.getUUID(), next, localYaw(body, host));
        return new Running(left, TravelMode.CONSUMED);
    }

    /** The route still ahead: the installed path's remaining nodes plus the last leg, or the straight line without one. */
    private double remaining(YukkuriEntity body) {
        Vec3 at = body.position();
        var path = body.getNavigation().getPath();
        if (path == null || path.isDone() || path.getNodeCount() <= 0) return at.distanceTo(target);
        double left = 0;
        for (int i = Math.max(0, path.getNextNodeIndex()); i < path.getNodeCount(); i++) {
            Vec3 node = path.getEntityPosAtNode(body, i);
            left += at.distanceTo(node);
            at = node;
        }
        return left + at.distanceTo(target);
    }

    private void readNavigation(YukkuriEntity body) {
        var navigation = body.getNavigation();
        var path = navigation.getPath();
        navDone = navigation.isDone();
        nodesLeft = path == null ? 0 : Math.max(0, path.getNodeCount() - path.getNextNodeIndex());
    }

    /** Spec 9.4 Carry row: carrying a Held body scales the speed by {@code 1 - .5 * heldWidth / carrierWidth}. */
    private static double carrying(YukkuriEntity body) {
        for (var passenger : body.getPassengers())
            if (passenger instanceof YukkuriEntity held)
                return 1 - .5 * held.getBbWidth() / Math.max(1E-6, body.getBbWidth());
        return 1;
    }

    /** The rider table's yaw convention: the body's own yaw relative to the host frame (spec 12). */
    private static float localYaw(YukkuriEntity body, SurfaceHost host) {
        return Mth.wrapDegrees(body.getYRot() - (float) Math.toDegrees(host.frame().yawRad()));
    }

    /** {@code walk.sample} (spec 13.1): position, navigation state, nodes left, owner and the metric, only with detail on. */
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
                host == null ? String.format(Locale.ROOT, "%.2f", remaining(body)) : "-");
    }
}
