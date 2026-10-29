package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/**
 * One dependent young off a host and down to supported ground outside the host's brim (spec 14 WP1 step 7): walk to the
 * stand beside it, grab it, carry it out and release it there. Never puts it back into the hat, never moves it to a
 * landing spot, and a refused {@code Release} ends the method with that reason so the runner picks the other method.
 */
public final class Fetch implements HostIntent {
    /** One young; the parent intent's own deadline bounds the whole unload. */
    static final int DEADLINE_TICKS = 600;
    /** The family pickup's navigation coefficient, so the taker walks at the speed its other pickups use. */
    private static final double STAND_SPEED = 1.1;
    /** How far the young (or its host) has to move before "let it come to the edge first" counts as satisfied. */
    private static final double MOVED = .25;
    /** The spec 11.2 signature quantises a host to 0.5 blocks; the wait watches the same fact. */
    private static final double HOST_MOVED = .5;
    /** Longest single wait for the young to move closer, and it is the only wait in the intent. */
    static final int RETRY_WAIT_TICKS = 200;
    /** Bounded landing scan: the nearest brim directions only, so the route plans stay cheap. */
    private static final int LANDING_TRIES = 8;

    private final YukkuriEntity child;
    private final YukkuriEntity taker;
    private final PlacedHatEntity hat;
    private final long started;
    /** Landings this young already failed to be released at; the next method goes somewhere else. */
    private final List<Vec3> tried = new ArrayList<>();

    public Fetch(YukkuriEntity child, YukkuriEntity taker, PlacedHatEntity hat) {
        this.child = child;
        this.taker = taker;
        this.hat = hat;
        this.started = hat.level().getGameTime();
    }

    @Override public String id() { return "fetch"; }

    /** The host this fetch empties; {@code unloadRequested()} therefore stays derivable while the taker carries. */
    @Override public SurfaceHost host() { return hat; }

    /** Free on supported ground: not on the host, not in a hold, and something carries its feet. */
    @Override public boolean satisfied() {
        if (!(Attach.of(child) instanceof AttachState.Free free) || free.inWater()) return false;
        return child.onGround() || SurfaceApproach.supported(child, child.position());
    }

    @Override public long deadline() { return started + DEADLINE_TICKS; }

    /**
     * The intent ended without the young down — stopped by its parent, interrupted, out of time or out of attempts — while
     * the taker still carries it. Left alone it would ride on into the next beat and end up on the hat as it goes back on
     * a head, so it is set down where it actually stands, through the shared motor path (spec 5.2 "搬运被打断").
     */
    @Override public void ended(IntentRunner.Result result) {
        if (result == IntentRunner.Result.DONE || !held()) return;
        Vec3 at = child.position();
        Release.now(taker, child, true, "fetch_ended");
        GameplayLog.event(taker, "family", "fetch release child={} uuid={} at={} result={} cause=fetch_ended",
                child.getId(), child.getUUID(), GameplayLog.at(at), result);
    }

    @Override public List<Method> methods() {
        return List.of(new GrabCarryRelease(), new WaitThenRetry());
    }

    /** Where the taker stands to reach the young: the same stand rule the family pickup uses (spec 9.4 Grab). */
    private Vec3 stand() {
        return SurfaceApproach.beside(taker, child, (taker.getBbWidth() + child.getBbWidth()) * .5 + .15, 1.2);
    }

    /**
     * The nearest supported ground point outside the hat's footprint the taker can reach: the brim directions in order of
     * distance to the taker, each stepped just past the brim by the young's own half width. Nothing is reserved.
     */
    private Vec3 landing() {
        Vec3 origin = hat.position();
        var directions = new ArrayList<Vec3>(hat.rim());
        directions.sort(Comparator.comparingDouble(point -> point.distanceToSqr(taker.position())));
        int tries = 0;
        for (var point : directions) {
            if (tries++ >= LANDING_TRIES) break;
            var out = point.subtract(origin).multiply(1, 0, 1);
            double reach = out.horizontalDistance();
            if (reach <= 1E-6) continue;
            // Probe at the hat's own base height: the ground around a set-down hat lies there, whatever the brim height is.
            var outside = new Vec3(point.x + out.x / reach * (child.getBbWidth() * .5 + .15), origin.y + .1,
                    point.z + out.z / reach * (child.getBbWidth() * .5 + .15));
            var at = SurfaceApproach.floor(taker, outside);
            if (at == null || tried.contains(at) || !SurfaceApproach.canApproach(taker, at)) continue;
            return at;
        }
        return null;
    }

    private boolean held() { return child.getVehicle() == taker; }

    private boolean reachable() {
        return child.isAlive() && (held() || stand() != null);
    }

    /** Walk to the young, face it, grab it, carry it out of the hat and release it on supported ground. */
    private final class GrabCarryRelease implements Method {
        private int state;

        @Override public String id() { return "grab_carry_release"; }

        @Override public Feasibility applicable() {
            if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
            if (!reachable()) return Feasibility.of(Reason.NO_CONTACT, 0, "no_stand");
            if (landing() == null) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_landing");
            return Feasibility.pass();
        }

        @Override public Set<Reason> reduces() {
            return Set.of(Reason.NO_CONTACT, Reason.NO_PATH, Reason.TOO_FAR, Reason.NO_SUPPORT);
        }

        @Override public Step next() { return carry(state++); }
    }

    /** The five steps of one carry-out: walk to the stand, face the young, grab it, walk to a landing, release it. */
    private Step carry(int state) {
        switch (state) {
            case 0: {
                var at = stand();
                return at == null ? null : new Step.Do(Walk.stand(at, STAND_SPEED));
            }
            case 1:
                return new Step.Do(new Face(SurfaceApproach.yaw(taker.position(), child.position())));
            case 2:
                return new Step.Do(new Grab(child));
            case 3: {
                // Lifting a young out of the hat wakes it; a carried young wakes too.
                child.life().wakeInHat();
                var at = landing();
                if (at == null) return null;
                tried.add(at);
                return new Step.Do(new Walk(at));
            }
            case 4:
                return new Step.Do(new Release(child));
            default:
                return null;
        }
    }

    /**
     * Lets the young walk to the brim on the taker's side by itself (its own {@link GetTo}) and then does the same carry.
     * The wait is the retry gate, not a movement test: it also ends once the young has stopped walking, which is the
     * normal way it stands at the brim. When the taker already holds the young, the wait is skipped.
     */
    private final class WaitThenRetry implements Method {
        private int state = -1;
        private Vec3 childAt, hatAt;

        @Override public String id() { return "wait_then_retry"; }

        @Override public Feasibility applicable() {
            if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
            if (!reachable()) return Feasibility.of(Reason.NO_CONTACT, 0, "no_stand");
            if (landing() == null) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_landing");
            return Feasibility.pass();
        }

        @Override public Set<Reason> reduces() {
            return Set.of(Reason.NO_CONTACT, Reason.NO_ROOM, Reason.NO_SUPPORT, Reason.TOO_FAR, Reason.TOO_HIGH,
                    Reason.TARGET_MOVED);
        }

        @Override public Step next() {
            if (state < 0) {
                state = held() ? 3 : 0;
                if (state == 0) {
                    childAt = child.position();
                    hatAt = hat.position();
                    return new Step.Wait(() -> moved() || !(child.motor().current() instanceof Walk),
                            RETRY_WAIT_TICKS, "child_to_edge");
                }
            }
            return carry(state++);
        }

        /** The young walked closer, or the host itself moved: the same world the attempt failed in no longer holds. */
        private boolean moved() {
            return child.position().distanceTo(childAt) >= MOVED || hat.position().distanceTo(hatAt) >= HOST_MOVED;
        }
    }
}
