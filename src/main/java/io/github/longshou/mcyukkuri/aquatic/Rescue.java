package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.intent.AskLedger;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;

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
        var hat = AquaticLife.hat(parent);
        if (hat != null) return hat.seatDestination(child) == null ? "no_seat" : null;
        if (!parent.life().custody().worn()) return "no_hat";
        var size = new HatSeats.Body(child.assetId(), child.growthScale() * child.getScale(), child.getBbWidth(), child.getBbHeight());
        return HatSeats.admit(parent.growthScale() * parent.getScale(), size, false, List.of()) == null ? "no_seat" : null;
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
}
