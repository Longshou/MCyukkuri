package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.social.Bonds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The catch hat's cleanup as one intent (E11-1): every young this taker must unload leaves the hat,
 * then the empty hat is taken back by the caller. Run by the taker's existing FETCH_HAT duty.
 *
 * <p>Satisfaction is the vehicle fact: no live rider of this hat is left that this taker must unload, and the
 * taker holds no young either — a young grabbed out of the hat leaves the rider list while it is still in the taker's
 * mouth, so the hold is the second half of the same fact ({@code Held → Free} only by {@code Release}). A
 * capable young is expected to hop off by itself ({@link GetTo}, triggered by the host's derived
 * {@code unloadRequested()}); a dependent one is fetched by {@link Fetch}, which carries it to supported ground outside
 * the brim.
 */
public final class Unload implements HostIntent {
    /** The whole duty, from the first decision to the last young off; the caller ends the duty when it passes. */
    public static final int DEADLINE_TICKS = 1200;
    /** Ticks a capable young gets to hop off by itself before the taker fetches it. */
    static final int HOP_WAIT_TICKS = 100;
    /** Ticks the last carried young gets to be set down before waiting for it counts as no progress. */
    static final int SET_DOWN_WAIT_TICKS = 60;

    private final PlacedHatEntity hat;
    private final YukkuriEntity taker;
    private final boolean onlyOwnYoung;
    private final long started;

    /** @param onlyOwnYoung a surviving parent of a dead owner's hat may unload exactly their own dependent young */
    public Unload(PlacedHatEntity hat, YukkuriEntity taker, boolean onlyOwnYoung) {
        this.hat = hat;
        this.taker = taker;
        this.onlyOwnYoung = onlyOwnYoung;
        this.started = hat.level().getGameTime();
    }

    @Override public String id() { return "unload"; }

    @Override public SurfaceHost host() { return hat; }

    @Override public boolean satisfied() { return riders().isEmpty() && !carrying(); }

    @Override public long deadline() { return started + DEADLINE_TICKS; }

    @Override public List<Method> methods() {
        var nearest = nearest();
        if (nearest == null) return List.of();
        return BodyAbility.of(nearest).canJump()
                ? List.of(new WaitForHops(), new FetchEveryRider())
                : List.of(new FetchEveryRider());
    }

    /** The young still riding this hat that this taker must unload, nearest first; read from the riders, never counted. */
    List<YukkuriEntity> riders() {
        var out = new ArrayList<YukkuriEntity>();
        for (var passenger : hat.getPassengers()) {
            if (!(passenger instanceof YukkuriEntity child) || !child.isAlive()) continue;
            if (Objects.equals(child.getUUID(), hat.ownerYukkuri())) continue;
            if (onlyOwnYoung && (child.family().adult() || !Bonds.parentOf(taker, child))) continue;
            out.add(child);
        }
        out.sort(Comparator.comparingDouble(child -> child.distanceToSqr(taker)));
        return out;
    }

    private YukkuriEntity nearest() {
        var all = riders();
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * A live young in the taker's mouth is not unloaded yet: read from the vanilla passenger relation ({@code Attach.of}
     * is {@code Held} exactly when this holds), never from a stored flag.
     */
    private boolean carrying() {
        for (var passenger : taker.getPassengers()) {
            if (passenger instanceof YukkuriEntity young && young.isAlive()) return true;
        }
        return false;
    }

    /** {@code Attach.of(child)} is {@code OnHost(host)}: the vehicle fact, not a counter. */
    static boolean onHost(YukkuriEntity child, SurfaceHost host) {
        return Attach.of(child) instanceof AttachState.OnHost on && on.host() == host;
    }

    /**
     * One way of emptying the hat. Each step targets whatever young is nearest now, so the method keeps working until the
     * intent is satisfied; a method that returned early would be recorded as NO_PROGRESS while the work was still going.
     */
    private abstract class Riders implements Method {
        @Override public Step next() {
            var child = nearest();
            // The last young leaves the rider list the moment it is grabbed, while the taker still carries it: wait for it
            // to be set down instead of reporting no progress, and the runner's own satisfaction check ends this intent.
            if (child == null) return new Step.Wait(Unload.this::satisfied, SET_DOWN_WAIT_TICKS, "young_set_down");
            return stepFor(child);
        }

        protected Step stepFor(YukkuriEntity child) {
            return BodyAbility.of(child).canJump()
                    ? new Step.Wait(() -> !onHost(child, hat), HOP_WAIT_TICKS, "child_hop")
                    : new Step.Sub(new Fetch(child, taker, hat));
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.NO_CONTACT, Reason.NO_PROGRESS); }
    }

    /** Lets the capable young hop off by themselves, then the next young. */
    private final class WaitForHops extends Riders {
        @Override public String id() { return "wait_child_hops"; }

        @Override public Feasibility applicable() {
            var all = riders();
            if (all.isEmpty()) return Feasibility.of(Reason.NO_CONTACT, 0, "no_riders");
            for (var child : all) if (BodyAbility.of(child).canJump()) return Feasibility.pass();
            return Feasibility.of(Reason.NO_CONTACT, 0, "no_jumper");
        }
    }

    /**
     * Fetches the remaining young one at a time. It also covers a capable young whose own hop was refused: after the wait
     * for it has failed, the same body is carried out instead of leaving the duty to retry for ever.
     */
    private final class FetchEveryRider extends Riders {
        @Override public String id() { return "fetch_riders"; }

        @Override public Feasibility applicable() {
            return riders().isEmpty() ? Feasibility.of(Reason.NO_CONTACT, 0, "no_riders") : Feasibility.pass();
        }

        @Override protected Step stepFor(YukkuriEntity child) { return new Step.Sub(new Fetch(child, taker, hat)); }

        @Override public Set<Reason> reduces() {
            return Set.of(Reason.NO_ROOM, Reason.NO_SUPPORT, Reason.NO_PATH, Reason.TOO_FAR, Reason.TOO_HIGH,
                    Reason.NO_CONTACT, Reason.TARGET_MOVED);
        }
    }
}
