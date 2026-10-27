package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * The only writer of a hat boat's own motion and heading. Everything that wants the hull to move, stay or turn asks here with a
 * {@link HelmLease} (who, why, how important, how long) and one {@link HelmOrder}; a higher priority displaces the holder, who
 * then reads {@link State#PREEMPTED}. Every order carries a deadline, a route that makes no progress ends as BLOCKED with a reason,
 * and nothing waits forever. The hull is the authority: a captain aboard follows its heading, never the reverse.
 * Deciding whether the captain should be paddling at all (sleeping, panicking, braid busy ...) is the captain side's job and ends
 * a trip through {@link #cancelTravel}; the helm only checks the physical preconditions (a captain aboard, a stick for a paddle route).
 */
public final class HullHelm {
    /** What the hull is currently doing, synchronized read-only for the client. */
    public enum Kind { NONE, HOLD, DRIFT, ROUTE, BERTH, HAND }
    /** Per-tick result of the order a holder asked for. */
    public enum State { IDLE, MOVING, ARRIVED, BLOCKED, PREEMPTED }
    public record Status(State state, String reason) {
        public static final Status IDLE = new Status(State.IDLE, "");
        public static final Status MOVING = new Status(State.MOVING, "");
        public static final Status ARRIVED = new Status(State.ARRIVED, "");
        public static Status blocked(String reason) { return new Status(State.BLOCKED, reason); }
        public static Status preempted(String by) { return new Status(State.PREEMPTED, by); }
        public boolean moving() { return state == State.MOVING; }
        public boolean arrived() { return state == State.ARRIVED; }
        public boolean blocked() { return state == State.BLOCKED || state == State.PREEMPTED; }
    }

    /** Why the helm is wanted; the priority orders purposes (rescue first, routine last). */
    public enum Purpose {
        RESCUE(90), SAFETY(70), LANDING(70), BOARDING(50), CARE(50), MOORING(40), CARGO(30), ROUTINE(10);
        final int priority;
        Purpose(int priority) { this.priority = priority; }
        public int priority() { return priority; }
    }

    /** {@code ttl} is the longest this order may stand without being renewed; 0 takes the order's default. */
    public record HelmLease(UUID holder, Purpose purpose, int priority, int ttl) {
        public static HelmLease of(Entity holder, Purpose purpose) { return new HelmLease(holder.getUUID(), purpose, purpose.priority, 0); }
    }

    public sealed interface HelmOrder {
        /** Stop travelling and keep still (damped); a hull without a stick only drifts. */
        record Hold() implements HelmOrder {}
        /** Let go of the helm: cancel whatever this holder (or a lower priority) was doing and drift. */
        record Drift() implements HelmOrder {}
        /** Paddle along checked waypoints with the stick; the last waypoint is reached within {@code toleranceSquared}. */
        record Route(List<Vec3> path, double toleranceSquared) implements HelmOrder {}
        /** Ease the hull toward a pose by bounded steps (never a teleport); call every tick the pose has to be kept. */
        record Berth(Vec3 center, float yaw, double maxStep, float maxTurn) implements HelmOrder {}
        /** Paddle along waypoints with the braid when no stick is at hand: slow and tiring (project design ruling). */
        record HandPaddle(List<Vec3> path, double toleranceSquared) implements HelmOrder {}
    }

    public static final int HOLD_TTL = 5, BERTH_TTL = 3, ROUTE_TTL = 1200, STALL_TICKS = 60, RESULT_KEEP_TICKS = 60;
    /** Fraction of the paddle's thrust and speed cap a braid stroke reaches, and its stamina cost multiple. */
    public static final double HAND_SPEED = .35;
    private static final double STROKE = .022, TOP_SPEED = .08;

    private final HatBoatEntity boat;
    private final ArrayDeque<Vec3> route = new ArrayDeque<>();
    private double toleranceSquared = .12;
    private Active active;
    private final Map<UUID, Ended> ended = new HashMap<>();
    private final Map<Object, Counted> holds = new HashMap<>();
    private Vec3 mooring;
    private int syncedKind = -1, syncedState = -1;
    private double bestGap;
    private long lastProgress;
    private long hullBackoffUntil;
    private int hullBackoffCount;

    private static final class Active {
        final HelmLease lease; final HelmOrder order; final Kind kind; final long began; long expires; Status status;
        Active(HelmLease lease, HelmOrder order, Kind kind, long now, int ttl, Status status) {
            this.lease = lease; this.order = order; this.kind = kind; this.began = now; this.expires = now + ttl; this.status = status;
        }
    }
    private record Ended(Status status, long at) {}
    private static final class Counted { int count; long until; }

    public HullHelm(HatBoatEntity boat) { this.boat = boat; }

    // ---------------------------------------------------------------- queries

    public Kind kind() { return active == null ? Kind.NONE : active.kind; }
    /** The route or braid trip is running (what the boat shows as paddling). */
    public boolean travelling() { return active != null && (active.kind == Kind.ROUTE || active.kind == Kind.HAND); }
    public boolean driving() { return active != null && active.kind == Kind.BERTH; }
    public boolean held() { return active != null && active.kind == Kind.HOLD || !liveHolds().isEmpty(); }
    public UUID holder() { return active == null ? null : active.lease.holder; }
    public Purpose purpose() { return active == null ? null : active.lease.purpose; }
    public Status status() { return active == null ? Status.IDLE : active.status; }
    public Vec3 mooring() { return mooring; }
    public void moor(Vec3 at) { mooring = at; }
    /** Horizontal pull of a hat floated at the bank toward its mooring point. */
    public Vec3 mooringPull() { return mooring == null ? Vec3.ZERO : mooring.subtract(boat.position()).multiply(.2, 0, .2); }

    // ---------------------------------------------------------------- requests

    public Status request(HelmLease lease, HelmOrder order) {
        if (boat.level().isClientSide) return Status.blocked("client");
        long now = now();
        expire(now);
        if (order instanceof HelmOrder.Drift) {
            if (displaces(lease, order)) end(lease, "drift", Status.IDLE, false);
            return Status.IDLE;
        }
        if (order instanceof HelmOrder.Hold && active != null && active.kind == Kind.HOLD && !displaces(lease, order)) {
            return new Status(State.ARRIVED, "already_held");
        }
        if (!displaces(lease, order)) {
            var by = active.lease;
            var status = Status.preempted(by.purpose + ":" + by.holder);
            note(lease, status, now);
            GameplayLog.limited(boat, "aquatic", "helm.refused." + lease.purpose + "." + by.purpose, 100,
                    "helm request refused holder={} purpose={}/{} order={} by holder={} purpose={}/{}", lease.holder, lease.purpose,
                    lease.priority, order.getClass().getSimpleName(), by.holder, by.purpose, by.priority);
            return status;
        }
        return switch (order) {
            case HelmOrder.Hold hold -> accept(lease, order, Kind.HOLD, HOLD_TTL, now, null);
            case HelmOrder.Route r -> travel(lease, order, Kind.ROUTE, r.path(), r.toleranceSquared(), now);
            case HelmOrder.HandPaddle h -> travel(lease, order, Kind.HAND, h.path(), h.toleranceSquared(), now);
            case HelmOrder.Berth b -> berth(lease, b, now);
            case HelmOrder.Drift d -> Status.IDLE;
        };
    }

    public void hold(HelmLease lease) { request(lease, new HelmOrder.Hold()); }
    public void drift(HelmLease lease) { request(lease, new HelmOrder.Drift()); }
    /** A stick route; true while the order stands accepted and moving. */
    public boolean route(HelmLease lease, List<Vec3> path, double toleranceSquared) {
        return request(lease, new HelmOrder.Route(path, toleranceSquared)).moving();
    }
    public boolean route(HelmLease lease, List<Vec3> path) { return route(lease, path, .12); }
    public Status berth(HelmLease lease, Vec3 centre, float yaw, double maxStep, float maxTurn) {
        return request(lease, new HelmOrder.Berth(centre, yaw, maxStep, maxTurn));
    }
    /** Travel to a point by the best means at hand: the stick when there is one, otherwise braid strokes. */
    public boolean travel(HelmLease lease, Vec3 destination) {
        if (destination == null || !boat.isAfloat() || boat.unsteadyMotion() || boat.hasCarryTrip()) return false;
        var path = HatBoatRoute.find(boat, destination);
        if (path.isEmpty()) return false;
        return request(lease, boat.cargo().paddle() ? new HelmOrder.Route(path, .12) : new HelmOrder.HandPaddle(path, .12)).moving();
    }
    /** A planned course from {@link HatBoatEntity#courseFor}; false when there is none. */
    public boolean sail(HelmLease lease, YukkuriEntity actor, Vec3 destination) {
        var course = boat.courseFor(actor, destination);
        return course != null && route(lease, course.path(), course.toleranceSquared());
    }
    // Short forms: the holder entity and why it wants the hull (the purpose's default priority).
    public void hold(Entity holder, Purpose why) { hold(HelmLease.of(holder, why)); }
    public void drift(Entity holder, Purpose why) { drift(HelmLease.of(holder, why)); }
    public boolean route(Entity holder, Purpose why, List<Vec3> path) { return route(HelmLease.of(holder, why), path, .12); }
    public boolean route(Entity holder, Purpose why, List<Vec3> path, double toleranceSquared) { return route(HelmLease.of(holder, why), path, toleranceSquared); }
    public Status berth(Entity holder, Purpose why, Vec3 centre, float yaw, double maxStep, float maxTurn) { return berth(HelmLease.of(holder, why), centre, yaw, maxStep, maxTurn); }
    public boolean travel(Entity holder, Purpose why, Vec3 destination) { return travel(HelmLease.of(holder, why), destination); }
    public boolean sail(YukkuriEntity actor, Purpose why, Vec3 destination) { return sail(HelmLease.of(actor, why), actor, destination); }
    /** The holder lets go; its own order ends, others are untouched. */
    public void release(UUID holder) {
        if (active != null && active.lease.holder.equals(holder)) end(active.lease, "released", Status.IDLE, false);
    }

    /** The captain side found she cannot paddle right now; a stick or braid trip ends and its holder reads BLOCKED(reason). */
    public void cancelTravel(String reason) {
        if (travelling()) end(active.lease, reason, Status.blocked(reason), true);
    }
    /** Physical events (capsize, loss, shove, hand-off) drop every order and hold. */
    public void stop(String reason) {
        if (active != null && active.kind != Kind.HOLD) end(active.lease, reason, Status.blocked(reason), false);
        else active = null;
        holds.clear();
        clearRoute();
    }
    /** A shove or knock keeps the hull still for a moment on top of ending the trip. */
    public void shaken(int ticks) {
        cancelTravel("shaken");
        hold(this, ticks);
    }

    /** Counted hold: the hull stays still while any holder's count is above zero, and never longer than {@code maxTicks} past the last hold. */
    public void hold(Object id, int maxTicks) {
        var counted = holds.computeIfAbsent(id, k -> new Counted());
        counted.count++;
        counted.until = Math.max(counted.until, now() + maxTicks);
    }
    public void unhold(Object id) {
        var counted = holds.get(id);
        if (counted != null && --counted.count <= 0) holds.remove(id);
    }

    // ---------------------------------------------------------------- tick

    /** Before the physics step: deadlines, and a hull that left the water cannot keep paddling. */
    public void tick() {
        long now = now();
        expire(now);
        if (travelling() && !boat.isAfloat()) end(active.lease, "not_afloat", Status.blocked("not_afloat"), true);
        // Nobody holds the helm for an entity that is gone: a dead or unloaded holder's order ends here instead of lingering.
        if (active != null && !active.lease.holder.equals(boat.getUUID()) && boat.level() instanceof ServerLevel server
                && !(server.getEntity(active.lease.holder) instanceof Entity holder && holder.isAlive()))
            end(active.lease, "holder_gone", Status.blocked("holder_gone"), true);
        ended.values().removeIf(e -> now - e.at > RESULT_KEEP_TICKS);
        publish();
    }

    /** The helm's contribution to the motion of an afloat hull this tick (strokes, damping); heading and captain follow included. */
    public Vec3 steer(Vec3 motion) {
        if (travelling()) motion = stroke(motion);
        if (boat.isAfloat() && (activeHold() || !liveHolds().isEmpty())) motion = new Vec3(motion.x * .2, motion.y, motion.z * .2);
        if (driving()) motion = new Vec3(0, motion.y, 0);
        return motion;
    }

    private boolean activeHold() {
        return active != null && (active.kind == Kind.HOLD && boat.cargo().paddle() || active.kind == Kind.BERTH && boat.cargo().paddle());
    }

    private Vec3 stroke(Vec3 motion) {
        var captain = boat.captain();
        boolean hand = active.kind == Kind.HAND;
        if (captain == null || !captain.isAlive() || captain.getVehicle() != boat || captain.isNoAi())
            return blocked("no_captain", motion);
        if (!hand && !boat.cargo().paddle()) return blocked("no_paddle", motion);
        var next = route.peekFirst();
        if (next == null) return arrived(motion);
        if (boat.position().subtract(next).horizontalDistanceSqr() < (route.size() == 1 ? toleranceSquared : .12)) route.removeFirst();
        if (route.isEmpty()) return arrived(motion);
        next = route.peekFirst();
        long now = now();
        var forward = next.subtract(boat.position()).multiply(1, 0, 1).normalize();
        float heading = (float) (Math.toDegrees(Math.atan2(forward.z, forward.x)) - 90);
        if (boat.tickCount % 4 == 0 && boat.level() instanceof ServerLevel server) {
            var step = BlockPos.containing(next.x, next.y - .12, next.z);
            if (!HatBoatRoute.calmSurface(server, step)) return blocked("water_not_calm", motion);
            var fit = HullFootprint.fits(server, boat, boat, next, boat.getYRot(), boat.hullScale(), 0);
            if (!fit.ok()) return blocked(fit.reason(), motion);
        }
        double gap = next.subtract(boat.position()).horizontalDistance();
        if (gap < bestGap - .01) { bestGap = gap; lastProgress = now; }
        else if (now - lastProgress > STALL_TICKS) return blocked("stalled", motion);
        double power = Math.max(0, Math.sin(boat.paddleCycle(0) * Math.PI * 2));
        double scale = hand ? HAND_SPEED : 1;
        // The recovery half of the stroke supplies no thrust; existing momentum and current remain.
        motion = motion.add(forward.scale(STROKE * power * scale));
        double speed = motion.horizontalDistance(), cap = TOP_SPEED * scale;
        if (speed > cap) motion = new Vec3(motion.x * cap / speed, motion.y, motion.z * cap / speed);
        if (route.size() == 1) {
            // Ease into the last waypoint: unchecked momentum coasts roughly half a block past a tight docking tolerance.
            double remaining = gap, slow = Math.max(.004, remaining * .25), now2 = motion.horizontalDistance();
            if (now2 > slow) motion = new Vec3(motion.x * slow / now2, motion.y, motion.z * slow / now2);
        }
        float turn = Mth.clamp(Mth.wrapDegrees(heading - boat.getYRot()), -4, 4);
        boat.setYRot(boat.getYRot() + turn);
        follow(captain, boat.getYRot());
        if (boat.tickCount % (hand ? 40 : 80) == 0 && !captain.life().spendEnergy(1) && hand) return blocked("exhausted", motion);
        if (boat.tickCount % 8 == 0 && boat.level() instanceof ServerLevel server)
            server.sendParticles(ParticleTypes.SPLASH, boat.getX(), boat.getY(), boat.getZ(), 2, .35, .01, .35, .01);
        return motion;
    }

    private Vec3 arrived(Vec3 motion) {
        hullBackoffCount = 0;
        end(active.lease, "arrived", Status.ARRIVED, false);
        return motion;
    }
    private Vec3 blocked(String reason, Vec3 motion) {
        var lease = active.lease;
        end(active.lease, reason, Status.blocked(reason), true);
        if ("hull_boat".equals(reason)) {
            int delay = Math.min(400, 40 << hullBackoffCount);
            hullBackoffCount = Math.min(3, hullBackoffCount + 1);
            hullBackoffUntil = now() + delay;
            accept(lease, new HelmOrder.Hold(), Kind.HOLD, delay, now(), Status.blocked("hull_boat_backoff"));
            return new Vec3(motion.x * .2, motion.y, motion.z * .2);
        }
        return motion;
    }

    private static void follow(YukkuriEntity captain, float yaw) {
        if (captain.family().crew().busy() || captain.family().laboring()) return;
        captain.setYRot(yaw); captain.yBodyRot = yaw; captain.setYHeadRot(yaw);
    }

    // ---------------------------------------------------------------- accepting orders

    private Status travel(HelmLease lease, HelmOrder order, Kind kind, List<Vec3> path, double tolerance, long now) {
        if (now < hullBackoffUntil && lease.purpose == Purpose.ROUTINE)
            return accept(lease, new HelmOrder.Hold(), Kind.HOLD, (int) (hullBackoffUntil - now), now, Status.blocked("hull_boat_backoff"));
        String why = !boat.isAfloat() ? "not_afloat" : boat.unsteadyMotion() ? "unsteady" : kind == Kind.ROUTE && !boat.cargo().paddle() ? "no_paddle"
                : path.isEmpty() ? "empty_route" : null;
        if (why != null) {
            var status = Status.blocked(why);
            note(lease, status, now);
            return status;
        }
        boolean continuing = travelling();
        route.clear();
        route.addAll(path);
        toleranceSquared = Double.isFinite(tolerance) ? Math.clamp(tolerance, .0004, .12) : .12;
        if (!continuing) { bestGap = Double.MAX_VALUE; lastProgress = now; }
        return accept(lease, order, kind, ROUTE_TTL, now, !continuing);
    }

    private Status berth(HelmLease lease, HelmOrder.Berth order, long now) {
        String why = !boat.isAfloat() && boat.state() != HatBoatEntity.State.BEACHED ? "not_afloat" : boat.unsteadyMotion() ? "unsteady"
                : boat.capsizing() ? "capsizing" : null;
        if (why != null) {
            var status = Status.blocked(why);
            note(lease, status, now);
            return status;
        }
        clearRoute();
        float diff = Mth.wrapDegrees(order.yaw() - boat.getYRot());
        if (Math.abs(diff) > .05f) boat.setYRot(boat.getYRot() + Mth.clamp(diff, -order.maxTurn(), order.maxTurn()));
        var gap = new Vec3(order.center().x - boat.getX(), 0, order.center().z - boat.getZ());
        double distance = gap.horizontalDistance();
        boolean moved = distance <= 1e-4;
        if (!moved) {
            var before = boat.position();
            boat.move(MoverType.SELF, gap.scale(Math.min(distance, order.maxStep()) / distance));
            moved = before.distanceToSqr(boat.position()) > 1e-7;
        }
        var captain = boat.captain();
        // A captain whose braid is lifting or rescuing a child keeps the heading that braid was planned for.
        // Afterwards she turns back with the hull at a body's pace rather than snapping round.
        if (captain != null && captain.getVehicle() == boat && !captain.family().crew().busy()) {
            float heading = Mth.approachDegrees(captain.getYRot(), boat.getYRot(), 15f);
            follow(captain, heading);
        }
        double remaining = new Vec3(order.center().x - boat.getX(), 0, order.center().z - boat.getZ()).horizontalDistance();
        boolean turned = Math.abs(Mth.wrapDegrees(order.yaw() - boat.getYRot())) <= .6f;
        Status status = remaining <= .04 && turned ? Status.ARRIVED : moved || !turned ? Status.MOVING : Status.blocked("no_progress");
        return accept(lease, order, Kind.BERTH, BERTH_TTL, now, status);
    }

    private Status accept(HelmLease lease, HelmOrder order, Kind kind, int defaultTtl, long now, Object extra) {
        Status status = extra instanceof Status s ? s : kind == Kind.HOLD ? Status.ARRIVED : Status.MOVING;
        if (kind != Kind.ROUTE && kind != Kind.HAND && kind != Kind.BERTH) clearRoute();
        if (active != null && !active.lease.holder.equals(lease.holder))
            displaced(active.lease, lease, now);
        int ttl = lease.ttl > 0 ? lease.ttl : defaultTtl;
        var previous = active;
        active = new Active(lease, order, kind, previous != null && previous.lease.holder.equals(lease.holder) && previous.kind == kind ? previous.began : now, ttl, status);
        if (kind == Kind.ROUTE || kind == Kind.HAND) {
            boolean restart = extra instanceof Boolean b && b;
            boat.syncPaddling(true, restart);
        } else boat.syncPaddling(false, false);
        if (previous == null || !previous.lease.holder.equals(lease.holder) || previous.kind != kind)
            GameplayLog.limited(boat, "aquatic", "helm.order." + lease.purpose + "." + kind, 100,
                    "helm order holder={} purpose={} priority={} kind={} ttl={} captain={}", lease.holder, lease.purpose, lease.priority, kind, ttl, boat.ownerYukkuri());
        if (status.state == State.BLOCKED) note(lease, status, now);
        return status;
    }

    // ---------------------------------------------------------------- internals

    private boolean displaces(HelmLease lease, HelmOrder order) {
        if (active == null || active.lease.holder.equals(lease.holder) || lease.priority > active.lease.priority) return true;
        // Equal priority: whoever needs the hull pinned outranks a plain trip (berth over hold over route).
        return lease.priority == active.lease.priority && rank(order) > rank(active.order);
    }
    private static int rank(HelmOrder order) {
        return order instanceof HelmOrder.Berth ? 3 : order instanceof HelmOrder.Hold ? 2 : order instanceof HelmOrder.Drift ? 0 : 1;
    }

    private void displaced(HelmLease old, HelmLease by, long now) {
        var status = Status.preempted(by.purpose + ":" + by.holder);
        ended.put(old.holder, new Ended(status, now));
        GameplayLog.limited(boat, "aquatic", "helm.preempt." + old.purpose + "." + by.purpose, 100,
                "helm preempted holder={} purpose={}/{} by holder={} purpose={}/{}", old.holder, old.purpose, old.priority, by.holder, by.purpose, by.priority);
    }

    private void note(HelmLease lease, Status status, long now) {
        ended.put(lease.holder, new Ended(status, now));
        if (status.state == State.BLOCKED)
            GameplayLog.limited(boat, "aquatic", "helm.blocked." + status.reason, 100,
                    "helm blocked holder={} purpose={} reason={} captain={}", lease.holder, lease.purpose, status.reason, boat.ownerYukkuri());
    }

    private void end(HelmLease lease, String why, Status result, boolean log) {
        if (active == null) return;
        boolean wasTravel = travelling();
        var closing = active;
        active = null;
        if (wasTravel || closing.kind == Kind.BERTH) clearRoute();
        if (wasTravel) boat.syncPaddling(false, false);
        ended.put(closing.lease.holder, new Ended(result, now()));
        if (log && result.state == State.BLOCKED)
            GameplayLog.limited(boat, "aquatic", "helm.blocked." + why, 100, "helm blocked holder={} purpose={} reason={} kind={} captain={}",
                    closing.lease.holder, closing.lease.purpose, why, closing.kind, boat.ownerYukkuri());
    }

    private void expire(long now) {
        if (active != null && now >= active.expires) {
            if (travelling()) end(active.lease, "ttl", Status.blocked("ttl"), true);
            else { ended.put(active.lease.holder, new Ended(Status.IDLE, now)); active = null; }
        }
        if (!holds.isEmpty()) holds.values().removeIf(h -> h.until <= now);
    }

    private Map<Object, Counted> liveHolds() { return holds; }

    private void clearRoute() {
        route.clear();
        toleranceSquared = .12;
    }

    private void publish() {
        int kind = kind().ordinal(), state = (active == null ? State.IDLE : active.status.state).ordinal();
        if (kind != syncedKind || state != syncedState) { syncedKind = kind; syncedState = state; boat.syncHelm(kind, state); }
    }

    private long now() { return boat.level().getGameTime(); }
}
