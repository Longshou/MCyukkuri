package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/**
 * One dependent young off a host and down to supported ground outside the host's brim: walk to a stand outside the brim on
 * its side, grab it (the braid first: it comes up over the brim), turn away from the host so it hangs clear of the brim and
 * release it there. Never puts it back into the hat, never moves it to a landing spot, and a refused step ends the method with
 * its reason so the runner picks the other method.
 */
public final class Fetch implements HostIntent {
    /** One young; the parent intent's own deadline bounds the whole unload. */
    static final int DEADLINE_TICKS = 600;
    /** The family pickup's navigation coefficient, so the taker walks at the speed its other pickups use. */
    private static final double STAND_SPEED = 1.1;
    /** How far the young (or its host) has to move before "let it come to the edge first" counts as satisfied. */
    private static final double MOVED = .25;
    /** The signature quantises a host to 0.5 blocks; the wait watches the same fact. */
    private static final double HOST_MOVED = .5;
    /** Longest single wait for the young to move closer, and it is the only wait in the intent. */
    static final int RETRY_WAIT_TICKS = 200;
    /** Bearings round the brim tried for a stand: the young's own first, then eighths either side. */
    private static final int STAND_TRIES = 8;

    private final YukkuriEntity child;
    private final YukkuriEntity taker;
    private final PlacedHatEntity hat;
    private final long started;

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
     * a head, so it is set down where it actually stands, through the shared motor path ("搬运被打断").
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

    /**
     * Where the taker stands to reach the young: outside the brim by its own footprint ({@link HatGeometry#outside}) on the
     * young's bearing from the hat's middle (the taker's for a young in the middle), else the next bearings either side; the
     * first with a floor the taker can get to. Nothing is reserved.
     */
    private Vec3 stand() {
        var centre = hat.position();
        var toward = child.position().subtract(centre).multiply(1, 0, 1);
        if (toward.lengthSqr() < .01) toward = taker.position().subtract(centre).multiply(1, 0, 1);
        for (int i = 0; i < STAND_TRIES; i++) {
            float turn = (float) ((i + 1) / 2 * (i % 2 == 0 ? 1 : -1) * Math.PI / 4);
            var at = SurfaceApproach.floor(taker, HatGeometry.outside(hat, taker, centre.add(toward.yRot(turn)), 0));
            if (at != null && (SurfaceApproach.arrived(taker, at) || SurfaceApproach.canApproach(taker, at))) return at;
        }
        return null;
    }

    private boolean held() { return child.getVehicle() == taker; }

    /** From a stand outside the brim the mouth or the braid reaches the young: walk there, take it, carry it out. */
    private final class GrabCarryRelease implements Method {
        private int state;
        private Vec3 stand;

        @Override public String id() { return "grab_carry_release"; }

        @Override public Feasibility applicable() {
            if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
            if (held()) return Feasibility.pass();
            if ((stand = stand()) == null) return Feasibility.of(Reason.NO_SUPPORT, 0, "no_stand");
            // Beyond the mouth's and the braid's reach from outside the brim, the young first comes to the edge (WaitThenRetry).
            return ContactGeometry.reachesFrom(taker, stand, child) ? Feasibility.pass() : Feasibility.of(Reason.NO_CONTACT, 0, "out_of_reach");
        }

        @Override public Set<Reason> reduces() {
            return Set.of(Reason.NO_CONTACT, Reason.NO_PATH, Reason.TOO_FAR, Reason.NO_SUPPORT);
        }

        @Override public Step next() {
            if (state == 0 && held()) state = 3;
            return carry(state++, stand);
        }
    }

    /** The five steps of one carry-out: walk to the stand, face the young, grab it, turn away from the host, release it. */
    private Step carry(int state, Vec3 stand) {
        switch (state) {
            case 0:
                return stand == null ? null : new Step.Do(Walk.stand(stand, STAND_SPEED));
            case 1:
                return new Step.Do(new Face(SurfaceApproach.yaw(taker.position(), child.position())));
            case 2:
                return new Step.Do(new Grab(child));
            case 3:
                // Lifting a young out of the hat wakes it; turned away from the host it hangs clear of the brim, over the ground.
                child.life().wakeInHat();
                return new Step.Do(new Face(SurfaceApproach.yaw(hat.position(), taker.position())));
            case 4:
                return new Step.Do(new Release(child));
            default:
                return null;
        }
    }

    /**
     * Lets the young walk to the brim on the taker's side by itself (its own {@link GetTo}) and then does the same carry from
     * the stand on its new bearing. The wait is the retry gate, not a movement test: it also ends once the young has stopped
     * walking, which is the normal way it stands at the brim. When the taker already holds the young, the wait is skipped.
     */
    private final class WaitThenRetry implements Method {
        private int state = -1;
        private Vec3 childAt, hatAt;

        @Override public String id() { return "wait_then_retry"; }

        @Override public Feasibility applicable() {
            if (!child.isAlive()) return Feasibility.of(Reason.TARGET_GONE, 0, "child_gone");
            return held() || stand() != null ? Feasibility.pass() : Feasibility.of(Reason.NO_SUPPORT, 0, "no_stand");
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
            return carry(state, state++ == 0 ? stand() : null);
        }

        /** The young walked closer, or the host itself moved: the same world the attempt failed in no longer holds. */
        private boolean moved() {
            return child.position().distanceTo(childAt) >= MOVED || hat.position().distanceTo(hatAt) >= HOST_MOVED;
        }
    }
}
