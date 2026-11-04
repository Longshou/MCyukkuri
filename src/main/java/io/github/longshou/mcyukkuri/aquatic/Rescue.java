package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Right;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.util.Mth;

/** One accepted incident: close distance from the captain seat, then carry to an admitted seat or reachable bank. */
final class Rescue implements Intent {
    private final YukkuriEntity helper, faller;
    private final String incident;
    private final long started;
    private final Release shoreRelease;

    Rescue(YukkuriEntity helper, YukkuriEntity faller, String incident) {
        this.helper = helper;
        this.faller = faller;
        this.incident = incident;
        shoreRelease = new Release(faller);
        started = helper.level().getGameTime();
        // The one going for the faller says so before it moves (辫子救援：招呼落水的孩子).
        helper.showReaction("teary", "braid_rescue_offer", 40);
        GameplayLog.event(helper, "aquatic", "rescue start faller={} incident={}", faller.getUUID(), incident);
    }

    /** Why she cannot accept this rescue, or null. A missing seat does not refuse a dependent child;
     *  the accepted rescue still needs a reachable destination and physically feasible actions. */
    static String refusal(YukkuriEntity parent, YukkuriEntity child) {
        if (child.childhood().canSail()) return "can_sail";
        if (AquaticLife.hat(parent) == null && !parent.life().custody().worn()) return "no_hat";
        return null;
    }

    String incident() { return incident; }
    YukkuriEntity faller() { return faller; }
    @Override public String id() { return "rescue"; }
    @Override public long deadline() { return started + (long) DriveWeights.of(AquaticGoal.DOMAIN, "rescue_ticks"); }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() {
        return faller.isAlive() && (shoreRelease.released() || AquaticLife.aboard(faller, AquaticLife.hat(helper)) || AquaticLife.ashore(faller));
    }
    @Override public List<Method> methods() {
        return List.of(Plan.of("braid_fetch", this::derive), new Plan("carry_ashore",
                () -> faller.getVehicle() != helper ? Feasibility.of(Reason.NO_CONTACT, 0, "not_held")
                        : AquaticLife.hat(helper).restSpot(faller) == null ? Feasibility.pass()
                        : Feasibility.of(Reason.NO_SUPPORT, 0, "hull_has_place"),
                Set.of(Reason.NO_ROOM, Reason.NO_SUPPORT, Reason.NO_CONTACT),
                this::carryAshore));
    }
    /** Her own hull in the water belongs to this rescue: a body aboard its hat is not swimming, whatever the water state says. */
    @Override public boolean ownsBodyInWater() { return AquaticLife.aboard(helper, AquaticLife.hat(helper)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(helper);
        if (faller.getVehicle() == helper) return AquaticLife.ashore(helper) ? List.of(() -> new Step.Do(shoreRelease))
                : List.of(() -> hat.settled(helper) || hat.places(faller, 1).isEmpty() ? null : new Step.Do(new Hop(hat)),
                        () -> new Step.Do(new Release(faller, hat)));
        if (!AquaticLife.aboard(helper, hat)) {
            // An upside-down hull is righted first, then she boards and lifts from it.
            if (hat != null && hat.upsideDown()) return List.of(() -> new Step.Do(new Right(hat, "rescue")));
            return hat == null && !helper.life().custody().worn() ? null
                    : List.of(() -> new Step.Sub(new SetOut.Launch(helper, deadline())));
        }
        if (!BraidFetch.withinReach(helper, faller)) return Ferry.chase(helper, faller);
        return BraidFetch.lift(helper, faller, hat);
    }

    private List<Supplier<Step>> carryAshore() {
        return AquaticLife.ashore(helper) ? List.of(() -> new Step.Do(shoreRelease))
                : List.of(() -> new Step.Sub(Ashore.carry(helper, shoreRelease)));
    }

    @Override public void ended(IntentRunner.Result result) {
        boolean aboard = AquaticLife.aboard(faller, AquaticLife.hat(helper));
        String outcome = result == IntentRunner.Result.DONE ? aboard ? "rescued" : shoreRelease.released() ? "set_down" : "self_climbed"
                : result.name();
        if (faller.getVehicle() == helper && !aboard) helper.family().carry().finish(faller, "rescue_ended");
        AskLedger.server().ended(incident, helper.getUUID(), outcome);
        GameplayLog.event(helper, "aquatic", "rescue end result={} outcome={} faller={} incident={} aboard={} last={}",
                result, outcome, faller.getUUID(), incident, aboard, helper.motor().lastDetail());
    }

    /** A protecting parent stays facing an independent child and cries; she never accepts its rescue. */
    static final class Watch implements Intent {
        private static final int REFACE = 20, TEARS = 40;
        private final YukkuriEntity parent, faller;
        private final String incident;
        private final long started;

        Watch(YukkuriEntity parent, YukkuriEntity faller, String incident) {
            this.parent = parent; this.faller = faller; this.incident = incident;
            started = parent.level().getGameTime();
            parent.showReaction("teary", null, TEARS);
            GameplayLog.event(parent, "aquatic", "watch start faller={} incident={} reason=can_sail", faller.getUUID(), incident);
        }
        YukkuriEntity faller() { return faller; }
        String incident() { return incident; }
        @Override public String id() { return "watch_water"; }
        @Override public long deadline() { return started + (long) DriveWeights.of(AquaticGoal.DOMAIN, "rescue_ticks"); }
        @Override public boolean satisfied() {
            return !faller.isInWaterOrBubble() || !incident.equals(AskLedger.server().openOf(faller.getUUID(), "water"));
        }
        @Override public List<Method> methods() { return List.of(Plan.of("tearful_watch", this::derive)); }
        private float yaw() { return SurfaceApproach.yaw(parent.position(), faller.position()); }
        private List<Supplier<Step>> derive() {
            return List.of(() -> new Step.Do(new Face(yaw())), () -> new Step.Wait(() -> {
                if ((parent.level().getGameTime() - started) % TEARS == 0) parent.showReaction("teary", null, TEARS);
                return satisfied() || Math.abs(Mth.wrapDegrees(yaw() - parent.yBodyRot)) > REFACE;
            }, (int) Math.max(1, deadline() - parent.level().getGameTime()), ""));
        }
        @Override public void ended(IntentRunner.Result result) {
            AskLedger.server().ended(incident, parent.getUUID(), result == IntentRunner.Result.DONE ? "watched" : result.name());
            GameplayLog.event(parent, "aquatic", "watch end result={} faller={} incident={}", result, faller.getUUID(), incident);
        }
    }
}
