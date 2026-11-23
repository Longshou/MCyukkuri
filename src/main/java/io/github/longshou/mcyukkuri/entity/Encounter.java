package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.life.NestDoorway;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.BodyContact;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Passage;
import io.github.longshou.mcyukkuri.motor.geom.YieldRule;
import io.github.longshou.mcyukkuri.social.SocialDisposition;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Two walking bodies whose lines cross are one encounter: decided once at first contact, stored once for both, ended by a physical
 * fact (the keeper has passed). The yielder turns round, walks to the nearest passing place behind it (never backwards) and watches
 * the keeper pass; the keeper keeps walking and only stops while the yielder is still on its line. The record claims nothing from a
 * third body: the place is re-checked when the yielder arrives and, if taken, the next station is used. The shared occupancy query
 * ({@link #standFree}) and the place search ({@link #place}) are public for other users of "somebody stands where I want to stand".
 */
public final class Encounter {
    /** First-contact gap (blocks between the boxes), how far behind a body a passing place is looked for, the station spacing. */
    static final double TRIGGER_GAP = 3.5, REACH = YieldRule.REACH, STATION = YieldRule.STATION;
    /** A detour shorter than this is a shift aside (a veer while walking on), not a yield. */
    private static final double MINOR = 1.2;
    /** A yielder stands and turns until the way to its next waypoint is within this many degrees of its facing, then walks on while it finishes the turn (never off backwards). */
    static final double TURN_FIRST = 55;
    /** Keeper stops at this gap to a yielder still on its line and goes on once it is off the line or past RELEASE (a band in distance, one stop). */
    static final double HOLD = .6, RELEASE = 2;
    /** Failure bounds (ticks): a yielder that does not move, a keeper that stopped walking or does not move; ticks a body keeps a spoken line to itself. */
    private static final int STUCK = 20, IDLE = 10, STALL = 30, LINE_REST = 200;
    /** The whole encounter's hard bound follows the walk: the slowest honest pace (blocks/tick, a tired body measured 0.034) over the way the yielder walks
     *  and the keeper closes, plus a turn in place per leg (ticks). A far place or a retreat is given the time it takes. */
    private static final double FLOOR_PACE = .03;
    private static final int TURN_ALLOWANCE = 40;
    private static final double ARRIVED = .05, ABANDON = 8;

    /** The way to a passing place: waypoints (the last is the stand) and their length. */
    public record Place(List<Vec3> legs, double length) {}
    private record Found(Place place, Vec3 farthest, double farthestLength) {}
    private enum Phase { NOTICE, WALK, WATCH }

    private final YukkuriEntity keeper, yielder;
    private final YukkuriGroundNavigation keeperNav, yielderNav;
    /** Unit vector from the keeper to the yielder at the decision: the lane direction. */
    private final Vec3 axis;
    private final long start;
    private final String reason;
    private final boolean deadEnd;
    private final boolean minor;
    private final int notice;
    /** The walk speed request the yielder gives way at, and the tick after which the encounter is failed. */
    private final double pace, length;
    private long deadline;
    /** What ended it (a name for the end line). */
    private String cause = "";
    private List<Vec3> legs;
    private int leg;
    private Vec3 legFrom;
    private Phase phase = Phase.NOTICE;
    private float watchYaw;
    private boolean still = true, released;
    private boolean retreating, researched, stuck;
    private long checkedAt = -1, sampleTick;
    private Vec3 samplePos;
    private int keeperIdle, holds;
    private long keeperTick;
    private Vec3 keeperAt;

    private Encounter(YukkuriGroundNavigation keeperNav, YukkuriEntity keeper, YukkuriGroundNavigation yielderNav, YukkuriEntity yielder,
                      Vec3 axis, Found found, String reason, boolean deadEnd) {
        this.keeper = keeper; this.yielder = yielder; this.keeperNav = keeperNav; this.yielderNav = yielderNav;
        this.axis = axis; this.reason = reason; this.deadEnd = deadEnd; this.start = yielder.level().getGameTime();
        this.retreating = found.place() == null;
        this.pace = Math.min(1.4, Math.max(1.2 + .2 * eagerness(yielder), yielderNav.requestedSpeed()));
        List<Vec3> way = retreating ? (found.farthestLength() > 0 ? List.of(found.farthest()) : List.of()) : found.place().legs();
        // A short detour is a veer on the way it was walking: aside and a little farther ahead (under 40 degrees, inside the turn limit), no turn round, no pause.
        Vec3 heading = yielderNav.heading();
        boolean veer = !retreating && heading != null && found.place().length() < MINOR && found.place().legs().size() == 1;
        if (veer) {
            Vec3 stand = found.place().legs().getFirst(), ahead = stand.add(heading.scale(Math.max(.8, 1.25 * stand.distanceTo(yielder.position()))));
            if (standFree(yielder, yielder.position(), ahead)) way = List.of(ahead); else veer = false;
        }
        this.legs = way;
        this.minor = veer;
        this.notice = minor ? 0 : 2 + (int) Math.round(3 * (1 - eagerness(yielder)));
        this.length = retreating ? found.farthestLength() : found.place().length();
        this.deadline = start + budget(length);
    }

    private static long budget(double length) { return (long) Math.ceil((length + TRIGGER_GAP + 1.5) / FLOOR_PACE) + 3 * TURN_ALLOWANCE; }

    // ---------------------------------------------------------------------------------------------------------- shared queries

    /** A stand a body can be at: supported floor reachable by a clear step from {@code from}, no block in the way, no other living body in it. */
    public static boolean standFree(YukkuriEntity body, Vec3 from, Vec3 stand) {
        return SurfaceApproach.clearLastStep(body, from, stand) && body.level().noCollision(body, box(body, stand)) && !occupied(body, stand);
    }

    public static boolean standFree(YukkuriEntity body, Vec3 stand) { return body.onGround() && standFree(body, body.position(), stand); }

    /** Another living body (not a rider or the mount) stands where {@code body} would stand at {@code stand}. */
    public static boolean occupied(YukkuriEntity body, Vec3 stand) {
        return !body.level().getEntitiesOfClass(LivingEntity.class, box(body, stand), other -> other != body && other.isAlive()
                && !other.isSpectator() && !body.hasPassenger(other) && body.getVehicle() != other).isEmpty();
    }

    private static AABB box(YukkuriEntity body, Vec3 stand) { return body.getBoundingBox().move(stand.subtract(body.position())); }

    /**
     * The nearest stand where {@code body} leaves {@code other}'s line clear, looking back from where it stands along {@code away}
     * (a unit horizontal vector pointing away from {@code other}) up to {@code reach} blocks; the station-0 sidestep is the first
     * candidate. Null when there is none. A body walks the {@link Place#legs()} in order.
     */
    public static Place place(YukkuriEntity body, Vec3 away, Entity other, double reach) {
        return search(body, away, other.position(), Passage.halfWidth(other, new Vec3(-away.z, 0, away.x)), reach).place();
    }

    private static Found search(YukkuriEntity body, Vec3 away, Vec3 otherAt, double otherHalf, double reach) {
        Vec3 side = new Vec3(-away.z, 0, away.x);
        if (body.position().subtract(otherAt).multiply(1, 0, 1).dot(side) < 0) side = side.reverse();
        double width = Passage.halfWidth(body, side) + otherHalf, margin = Passage.MARGIN + .4 * eagerness(body);
        Vec3 previous = body.position(), farthest = previous;
        double reached = 0;
        for (double s = 0; s <= reach; s += STATION) {
            Vec3 at = body.position().add(away.scale(s)), station = at;
            if (s > 0) {
                if (!standFree(body, previous, at)) break;
                previous = at; farthest = at; reached = s;
            }
            Vec3 stand = Passage.aside(at, at.subtract(otherAt).multiply(1, 0, 1), side, width, margin, to -> standFree(body, station, to));
            if (stand != null) {
                var legs = new ArrayList<Vec3>();
                if (s > 0) legs.add(at);
                legs.add(stand);
                return new Found(new Place(List.copyOf(legs), s + at.distanceTo(stand)), farthest, reached);
            }
        }
        return new Found(null, farthest, reached);
    }

    // ------------------------------------------------------------------------------------------------------------- the decision

    /** Positive: {@code a} keeps its line against {@code b} (heavier, carrying a child, more urgent, bolder, surer of itself). */
    static double score(YukkuriEntity a, YukkuriEntity b) {
        return YieldRule.score(BodyContact.mass(a), BodyContact.mass(b), BodyAbility.carrying(a) < 1, BodyAbility.carrying(b) < 1, urgency(a), urgency(b),
                a.life().personality().boldness(), b.life().personality().boldness(),
                SocialDisposition.confidence(SocialDisposition.selfEsteem(a)), SocialDisposition.confidence(SocialDisposition.selfEsteem(b)));
    }

    private static double urgency(YukkuriEntity body) { return (body.isPanicking() ? 1 : 0) + (body.life().nutrition() < 30 ? .5 : 0); }

    /** How readily a body gives way: timid and unsure of itself yield sooner and with more of a start. */
    static double eagerness(YukkuriEntity body) {
        return ((100 - body.life().personality().boldness()) / 100.0 + 1 - SocialDisposition.confidence(SocialDisposition.selfEsteem(body))) / 2;
    }

    /** Awake, on its feet and free to walk: what a body must be to give way at all. */
    private static boolean able(YukkuriEntity body) {
        return body.isAlive() && !body.isNoAi() && !body.isPassenger() && body.onGround() && !body.life().isSleeping() && !body.isCommandPlaying()
                && !body.pairedContact() && body.directNavigation() instanceof YukkuriGroundNavigation;
    }

    /** Able, and not at a nest's doorway (those keep their own queue rules). */
    private static boolean mobile(YukkuriEntity body) {
        return able(body) && NestDoorway.at(body, body.position()) == null && NestDoorway.doorstep(body) == null;
    }

    private static boolean canYield(YukkuriEntity body) { return !body.isLeashed() && !body.isPanicking(); }

    /** Looks for oncoming bodies round a walker; the first whose line crosses starts an encounter (or makes the walker wait for one running). */
    static void scan(YukkuriGroundNavigation nav, YukkuriEntity me, List<Entity> near) {
        if (nav.encounter != null || !nav.walking() || !mobile(me)) return;
        for (var e : near) if (e instanceof YukkuriEntity other && consider(nav, me, other)) return;
    }

    /** True when {@code a} met {@code b} head-on (an encounter began, or one of {@code b}'s is running and {@code a} waits for it). */
    static boolean consider(YukkuriGroundNavigation an, YukkuriEntity a, YukkuriEntity b) {
        if (a == b || !b.isAlive() || an.encounter != null || !mobile(a) || !mobile(b) || a.careApproach().cooperatingWith(b)
                || !(b.directNavigation() instanceof YukkuriGroundNavigation bn)) return false;
        if (an.walksTo(b) || bn.walksTo(a)) return false;
        Vec3 ha = an.heading(), hb = bn.heading();
        if (ha == null || hb == null || ha.dot(hb) > -.5 || Math.abs(a.getY() - b.getY()) >= 1) return false;
        Vec3 d = b.position().subtract(a.position()).multiply(1, 0, 1), closing = ha.subtract(hb);
        // The lane axis is the way the two close on each other (not the offset between them: side by side that would be the wrong axis).
        Vec3 u = Math.abs(closing.x) >= Math.abs(closing.z) ? new Vec3(Math.signum(closing.x), 0, 0) : new Vec3(0, 0, Math.signum(closing.z)), perp = new Vec3(-u.z, 0, u.x);
        double gap = d.dot(u) - Passage.halfWidth(a, u) - Passage.halfWidth(b, u);
        boolean crossing = d.dot(ha) > 0 && d.dot(hb) < 0 && d.dot(u) > 0 && gap <= TRIGGER_GAP
                && Math.abs(d.dot(perp)) < Passage.halfWidth(a, perp) + Passage.halfWidth(b, perp) + Passage.MARGIN;
        // A pair whose encounter failed is left to the ordinary blocker rules until their lines no longer cross.
        if (!crossing) { if (an.shunned == b) an.shunned = null; if (bn.shunned == a) bn.shunned = null; return false; }
        if (an.shunned == b || bn.shunned == a) return false;
        if (bn.encounter != null) {
            // b is passing someone: when it is the keeper nobody stops it, so this body gives way to it; when it is the yielder this body waits for it to settle.
            if (bn.encounter.keeper != b) { an.waitFor(b); return true; }
            if (!canYield(a)) return false;
            var found = search(a, u.reverse(), b.position(), Passage.halfWidth(b, perp), REACH);
            new Encounter(bn, b, an, a, u.reverse(), found, "busy_keeper", true).open(cost(found), 0, gap);
            return true;
        }
        if (!bn.walking()) return false;
        boolean ay = canYield(a), by = canYield(b);
        if (!ay && !by) return false;
        Found fa = ay ? search(a, u.reverse(), b.position(), Passage.halfWidth(b, perp), REACH) : null;
        Found fb = by ? search(b, u, a.position(), Passage.halfWidth(a, perp), REACH) : null;
        double ca = ay ? YieldRule.cost(cost(fa), score(a, b)) : Double.POSITIVE_INFINITY, cb = by ? YieldRule.cost(cost(fb), score(b, a)) : Double.POSITIVE_INFINITY;
        boolean tie = YieldRule.tie(ca, cb), aYields = YieldRule.aYields(ca, cb, a.getId(), b.getId());
        var found = aYields ? fa : fb;
        var keeper = aYields ? b : a;
        var yielder = aYields ? a : b;
        var other = aYields ? fb : fa;
        String reason = found.place() == null ? "no_place" : tie ? "tie"
                : other == null || other.place() == null || found.place().length() <= other.place().length() ? "nearer_place" : "stronger";
        var e = new Encounter(aYields ? bn : an, keeper, aYields ? an : bn, yielder, aYields ? u.reverse() : u, found, reason, false);
        e.open(aYields ? ca : cb, aYields ? cb : ca, gap);
        return true;
    }

    /** The walk's cost: its length, or with no place the way back's: open (clear to the search's end) or a known dead end (a wall short of it, costs most). */
    private static double cost(Found found) {
        return YieldRule.walkCost(found.place() != null, found.place() == null ? 0 : found.place().length(), found.farthestLength());
    }

    private void open(double yielderCost, double keeperCost, double gap) {
        yielderNav.encounter = this;
        // A keeper already passing someone keeps that encounter and its wait; this one only adds a yielder.
        if (keeperNav.encounter == null) { keeperNav.encounter = this; keeperNav.waitFor(yielder); }
        yielderNav.restarted(); keeperNav.restarted();
        GameplayLog.event(yielder, "move", "encounter begin keeper=#{} yielder=#{} place={} cost=({},{}) reason={} minor={} legs={} gap={} len={}",
                keeper.getId(), yielder.getId(), retreating ? "none" : GameplayLog.at(legs.getLast()), format(yielderCost), format(keeperCost), reason, minor, legs.size(), format(gap), format(length));
        long now = yielder.level().getGameTime();
        if (minor) {
            beat(yielder, yielderNav, "yielder", "shift_aside", eagerness(yielder) > .5 ? "surprise" : "pursed", "", 14, now);
        } else {
            boolean timid = eagerness(yielder) > .5;
            beat(yielder, yielderNav, "yielder", "yield_notice", timid ? "surprise" : "pursed", timid ? "busy" : "wait_turn", 30, now);
            if (keeper.life().personality().boldness() >= 60) beat(keeper, keeperNav, "keeper", "keep_notice", "pursed", "puff", 20, now);
        }
    }

    private static String format(double value) { return Double.isInfinite(value) ? "inf" : String.format(Locale.ROOT, "%.2f", value); }

    /** One visible beat: the face always, the line unless this body already spoke one lately (a busy street does not repeat it). */
    private void beat(YukkuriEntity body, YukkuriGroundNavigation nav, String role, String name, String face, String line, int ticks, long now) {
        boolean speak = !line.isEmpty() && now >= nav.lineAfter;
        boolean shown = body.showReaction(face, speak ? line : "", ticks);
        if (shown && speak) nav.lineAfter = now + LINE_REST;
        GameplayLog.event(body, "move", "encounter beat role={} beat={} face={} line={} keeper=#{} yielder=#{}",
                role, name, shown ? face : "none", shown && speak ? line : "none", keeper.getId(), yielder.getId());
    }

    // ----------------------------------------------------------------------------------------------------------------- the tick

    YukkuriEntity yielder() { return yielder; }
    /** The yielder is still driven by the encounter (a veer hands it back to its own walk once it is aside). */
    boolean drives() { return !released; }
    /** The yielder is standing in place on the way (noticing, turning), not walking away or settled: the keeper must not walk into it. */
    boolean yielderStill() { return still && !released; }
    void holdStarted() { holds++; }

    /** Called by each of the two navigations every tick. True when {@code me} is the yielder and drove itself: its route's own step is skipped. */
    boolean tick(YukkuriGroundNavigation nav, YukkuriEntity me) {
        long now = me.level().getGameTime();
        if (checkedAt != now) {
            checkedAt = now;
            String result = verdict(now);
            if (result != null) { end(result, now); return false; }
        }
        return me == yielder && !released && drive(now);
    }

    /** Null while it goes on, else how it ended ({@link #cause} says why). */
    private String verdict(long now) {
        if (!keeper.isAlive() || !yielder.isAlive()) return fail("aborted", "dead");
        if (keeper.isPassenger() || yielder.isPassenger()) return fail("aborted", "carried");
        if (keeper.life().isSleeping() || yielder.life().isSleeping()) return fail("aborted", "asleep");
        if (keeper.isCommandPlaying() || yielder.isCommandPlaying()) return fail("aborted", "command");
        if (yielder.isPanicking()) return fail("aborted", "panic");
        if (keeper.level() != yielder.level() || keeper.distanceToSqr(yielder) > ABANDON * ABANDON) return fail("aborted", "apart");
        Vec3 apart = keeper.position().subtract(yielder.position()).multiply(1, 0, 1), keeps = keeperNav.heading();
        double passed = apart.dot(axis), widths = Passage.halfWidth(keeper, axis) + Passage.halfWidth(yielder, axis);
        // Passed: beyond the yielder along the lane, or heading away from it whichever way the keeper has turned.
        if (passed > widths + Passage.MARGIN) { cause = "axis"; return "passed"; }
        if (keeps != null && apart.dot(keeps) > widths + Passage.MARGIN) { cause = "heading"; return "passed"; }
        keeperIdle = keeperNav.walking() ? 0 : keeperIdle + 1;
        if (keeperIdle >= IDLE) {
            // A keeper that stopped for good (its goal is here) beside the yielder, clear of its line, is no longer in the way: the pass is done.
            Vec3 perp = new Vec3(-axis.z, 0, axis.x);
            if (Math.abs(apart.dot(perp)) >= Passage.halfWidth(keeper, perp) + Passage.halfWidth(yielder, perp) + Passage.MARGIN) { cause = "keeper_arrived"; return "passed"; }
            return fail("aborted", "keeper_idle");
        }
        // A keeper that walks but does not move (and is not holding for us) is not coming.
        if (keeperAt == null || now - keeperTick >= STALL) {
            if (keeperAt != null && !keeperNav.holding() && keeper.position().subtract(keeperAt).multiply(1, 0, 1).length() < .1) return fail("aborted", "keeper_stalled");
            keeperAt = keeper.position(); keeperTick = now;
        }
        if (stuck) return fail("stuck", "yielder_blocked");
        return now >= deadline ? fail("timeout", "deadline") : null;
    }

    private String fail(String result, String why) { cause = why; return result; }

    private void end(String result, long now) {
        if (yielderNav.encounter == this) yielderNav.encounter = null;
        if (keeperNav.encounter == this) keeperNav.encounter = null;
        keeperNav.stopWaiting(yielder);
        if (!released) yielderNav.resumed();
        if (!result.equals("passed")) { keeperNav.shunned = yielder; yielderNav.shunned = keeper; }
        GameplayLog.event(yielder, "move", "encounter end keeper=#{} yielder=#{} result={} cause={} ticks={} holds={} reason={}",
                keeper.getId(), yielder.getId(), result, cause, now - start, holds, reason);
        if (result.equals("no_place") && !deadEnd) handOver();
    }

    /** Nowhere to pass behind the yielder: the other body, whose way back is the open side, yields in one new encounter. */
    private void handOver() {
        // The one hand-over may pull a body out of a nest's doorway: the dead end is the stub its own nest sits in.
        if (!canYield(keeper) || !able(keeper) || !able(yielder)) return;
        Vec3 u = axis.reverse(), perp = new Vec3(-u.z, 0, u.x);
        var found = search(keeper, u, yielder.position(), Passage.halfWidth(yielder, perp), REACH);
        double gap = keeper.position().subtract(yielder.position()).multiply(1, 0, 1).dot(u) - Passage.halfWidth(keeper, u) - Passage.halfWidth(yielder, u);
        new Encounter(yielderNav, yielder, keeperNav, keeper, u, found, "dead_end", true).open(cost(found), 0, gap);
    }

    private boolean drive(long now) {
        var me = yielder;
        if (phase == Phase.NOTICE) {
            if (now - start < notice) { still = true; SurfaceApproach.haltInput(me); me.getLookControl().setLookAt(keeper, 30, 30); return true; }
            phase = Phase.WALK;
        }
        if (phase == Phase.WATCH) {
            // The head follows the keeper all the way; the body only turns as far as 90 degrees from how it arrived.
            still = false;
            SurfaceApproach.haltInput(me);
            me.getLookControl().setLookAt(keeper, 30, 30);
            float toward = SurfaceApproach.yaw(me.position(), keeper.position());
            Motor.face(me, watchYaw + Mth.clamp(Mth.wrapDegrees(toward - watchYaw), -90, 90));
            return true;
        }
        if (leg < legs.size() && !retreating && leg == legs.size() - 1 && occupied(me, legs.getLast())) replace(now);
        if (leg >= legs.size()) {
            if (minor) {
                // The veer is done: the yielder walks on by itself; the encounter stays (no second decision for the pair) until the keeper has passed.
                released = true;
                yielderNav.resumed();
                return false;
            }
            if (retreating) {
                // The farthest clear station is a new position: one more search from here is a changed condition; none left is a dead end.
                if (!researched) {
                    researched = true;
                    var found = search(me, axis, keeper.position(), Passage.halfWidth(keeper, new Vec3(-axis.z, 0, axis.x)), REACH);
                    if (found.place() != null) {
                        legs = found.place().legs(); leg = 0; legFrom = null; retreating = false;
                        deadline += budget(found.place().length());
                        GameplayLog.event(me, "move", "encounter place found from={} place={}", GameplayLog.at(me.position()), GameplayLog.at(legs.getLast()));
                        return true;
                    }
                }
                cause = "dead_end";
                end("no_place", now);
                return false;
            }
            phase = Phase.WATCH;
            watchYaw = me.yBodyRot;
            return true;
        }
        Vec3 target = legs.get(leg);
        if (legFrom == null) legFrom = me.position();
        // A leg is done when the body is on its end or has carried past it (a step's momentum): turning back for a few centimetres would be a backward walk.
        Vec3 left = target.subtract(me.position()).multiply(1, 0, 1);
        if (left.length() < ARRIVED || left.dot(target.subtract(legFrom).multiply(1, 0, 1)) <= 0) { leg++; legFrom = null; return true; }
        float error = Mth.wrapDegrees(SurfaceApproach.yaw(me.position(), target) - me.yBodyRot);
        me.getLookControl().setLookAt(target.x, me.getEyeY(), target.z);
        // Never a step more than TURN_FIRST off the facing (a veer is at most 45 degrees by construction): the body turns first, then walks.
        if (Math.abs(error) > TURN_FIRST) {
            still = true;
            SurfaceApproach.haltInput(me);
            Motor.face(me, SurfaceApproach.yaw(me.position(), target));
            samplePos = null;
            return true;
        }
        still = false;
        if (samplePos == null) { samplePos = me.position(); sampleTick = now; }
        else if (now - sampleTick >= STUCK) {
            stuck = me.position().subtract(samplePos).multiply(1, 0, 1).length() < .1;
            samplePos = me.position(); sampleTick = now;
        }
        Motor.step(me, target, pace);
        return true;
    }

    /** A third body took the place before the yielder got there: the next station behind it, or the retreat when none is left. */
    private void replace(long now) {
        var me = yielder;
        var found = search(me, axis, keeper.position(), Passage.halfWidth(keeper, new Vec3(-axis.z, 0, axis.x)), REACH);
        GameplayLog.event(me, "move", "encounter place taken was={} next={}", GameplayLog.at(legs.getLast()), found.place() == null ? "none" : GameplayLog.at(found.place().legs().getLast()));
        leg = 0; legFrom = null;
        if (found.place() != null) { legs = found.place().legs(); deadline += budget(found.place().length()); }
        else { retreating = true; researched = false; legs = found.farthestLength() > 0 ? List.of(found.farthest()) : List.of(); deadline += budget(found.farthestLength()); }
    }
}
