package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.util.Mth;

/** One accepted incident: set out if needed, chase from the captain seat, braid-fetch into the child's seat. */
final class Rescue implements Intent {
    private final YukkuriEntity helper, faller;
    private final String incident;
    private final long started;

    Rescue(YukkuriEntity helper, YukkuriEntity faller, String incident) {
        this.helper = helper;
        this.faller = faller;
        this.incident = incident;
        started = helper.level().getGameTime();
        // The one going for the faller says so before it moves (辫子救援：招呼落水的孩子).
        helper.showReaction("teary", "braid_rescue_offer", 40);
        GameplayLog.event(helper, "aquatic", "rescue start faller={} incident={}", faller.getUUID(), incident);
    }

    static String refusal(YukkuriEntity parent, YukkuriEntity child) {
        if (child.childhood().canSail()) return "can_sail";
        var hat = AquaticLife.hat(parent);
        if (hat == null && !parent.life().custody().worn()) return "no_hat";
        double scale = (hat == null ? HatScale.of(parent) : hat.hullScale()) / HullBounds.SCALE;
        var riders = hat == null ? new ArrayList<HatSeats.Occupant>() : new ArrayList<>(hat.seatOccupants());
        if (hat == null || !AquaticLife.aboard(parent, hat)) riders.add(captain(parent, scale));
        return HatSeats.admit(scale, PlacedHatEntity.seatBody(child, false), false, riders) == null ? "no_seat" : null;
    }

    /** The captain the rescue puts aboard before it lifts the child: the parent is the hat's owner. */
    private static HatSeats.Occupant captain(YukkuriEntity parent, double scale) {
        var body = PlacedHatEntity.seatBody(parent, true);
        var at = HatSeats.admit(scale, body, true, List.of());
        return new HatSeats.Occupant(body, new Vec(at.x(), at.y(), at.z()), true);
    }

    String incident() { return incident; }
    YukkuriEntity faller() { return faller; }
    @Override public String id() { return "rescue"; }
    @Override public long deadline() { return started + (long) DriveWeights.of(AquaticGoal.DOMAIN, "rescue_ticks"); }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() {
        return faller.isAlive() && (AquaticLife.aboard(faller, AquaticLife.hat(helper)) || AquaticLife.ashore(faller));
    }
    @Override public List<Method> methods() { return List.of(Plan.of("braid_fetch", this::derive)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(helper);
        if (!AquaticLife.aboard(helper, hat)) return hat == null && !helper.life().custody().worn() ? null
                : List.of(() -> new Step.Sub(new SetOut.Launch(helper, deadline(), List.of())));
        return Ferry.fetchAboard(helper, hat, faller);
    }

    @Override public void ended(IntentRunner.Result result) {
        boolean aboard = AquaticLife.aboard(faller, AquaticLife.hat(helper));
        String outcome = result == IntentRunner.Result.DONE ? aboard ? "rescued" : "self_climbed" : result.name();
        if (faller.getVehicle() == helper) Release.now(helper, faller, true, "rescue_ended");
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
