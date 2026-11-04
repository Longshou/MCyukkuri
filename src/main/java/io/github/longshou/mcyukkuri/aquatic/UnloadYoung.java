package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every own young still riding the afloat hull is brought ashore, one after another ( ashore after a ferry or a rescue):
 * <ul>
 *   <li>a capable young leaves by itself (its own {@code follow_ashore} hop);</li>
 *   <li>a hull lying beyond the braid's reach from the bank is drawn in to the bank edge she stands at with the bank beckon
 *       ({@code Beckon}): a hull out on the water can lie outside the braid while its riders are asked off only from the shore she
 *       stands on;</li>
 *   <li>her asking the hull off ({@link #unloads()}) is what makes each young walk to the brim on her side, so she waits one window
 *       for the nearest to come within reach, takes it over the rim and sets it down on the bank, then the next one.</li>
 * </ul>
 * Nothing is retried unchanged: one stroke per run, and one window for a young to come to her; a run that brings none in ends, and
 * the drive asks again once the hull or its brood changed.
 */
final class UnloadYoung implements HostIntent {
    /** The whole unloading (ticks); the one window at the rim; how often a reason is said again. */
    private static final int DEADLINE = 2400, COME = 400, SAY = 300;

    private final YukkuriEntity owner;
    private final PlacedHatEntity hat;
    /** The one bank stroke of this run: a hull it did not bring into reach ends the run instead of paying strokes over and over. */
    private final Ferry.Beckoning beckoning = new Ferry.Beckoning();
    /** The one window of this run spent waiting for an asked-off young to come to the rim. */
    private boolean waited;
    private final long started;

    UnloadYoung(YukkuriEntity owner, PlacedHatEntity hat) {
        this.owner = owner;
        this.hat = hat;
        this.started = owner.level().getGameTime();
    }

    @Override public String id() { return "unload_young"; }
    @Override public PlacedHatEntity host() { return hat; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public Commitment commitment() { return Commitment.HARD; }
    @Override public boolean satisfied() { return AquaticLife.carried(owner) == null && AquaticGoal.youngAboard(owner, hat) == null; }
    @Override public List<Method> methods() { return List.of(Plan.of("unload", this::derive)); }

    private List<Supplier<Step>> derive() {
        if (!AquaticLife.afloat(hat)) return null;
        var held = AquaticLife.carried(owner);
        if (held != null) return List.of(() -> new Step.Sub(Ashore.carry(owner, new Release(held))));
        var child = reached();
        if (child != null) return List.of(() -> new Step.Do(grab(child)));
        final var next = AquaticGoal.youngAboard(owner, hat);
        if (next == null) return null;
        if (!AquaticLife.ashore(owner) || AquaticLife.aboard(owner, hat)) return noWay(next, "not_on_the_bank");
        var edge = Waters.bankEdge(owner, hat);
        if (edge == null) return noWay(next, "no_bank_edge");
        var approach = Ashore.bankApproach(owner, hat, edge);
        if (!approach.isEmpty()) return approach;
        // Facing it, from the bank: the stroke is admitted on this same reach and water test.
        Step stroke = beckoning.step(owner, hat);
        if (stroke != null) return List.of(() -> stroke);
        if (waited) return noWay(next, "no_young_at_rim");
        waited = true;
        int window = (int) Math.max(1, Math.min(COME, deadline() - owner.level().getGameTime()));
        GameplayLog.limited(owner, "aquatic", "unload.wait", SAY, "unload waiting child={} hat={} distance={} asked={} step={}",
                next.getUUID(), hat.getUUID(), Math.round(hat.distanceTo(owner) * 10) / 10.0, hat.unloadRequested(),
                next.motor().current() == null ? "idle" : next.motor().current().id());
        return List.of(() -> new Step.Wait(() -> !next.isAlive() || !AquaticLife.aboard(next, hat) || reached() != null,
                window, Behaviors.BOAT_CALL));
    }

    /** One of her young aboard the hull the mouth or the braid already reaches, or null. */
    private YukkuriEntity reached() {
        for (var y : AquaticLife.young(owner))
            if (y.getVehicle() == hat && ContactGeometry.reachesFrom(owner, owner.position(), y)) return y;
        return null;
    }

    private List<Supplier<Step>> noWay(YukkuriEntity child, String reason) {
        GameplayLog.limited(owner, "aquatic", "unload.no_way", SAY, "unload no way child={} reason={} hat={} at={} distance={} asked={}",
                child.getUUID(), reason, hat.getUUID(), GameplayLog.at(hat.position()),
                Math.round(hat.distanceTo(owner) * 10) / 10.0, hat.unloadRequested());
        return null;
    }

    private Action grab(YukkuriEntity child) { return new Grab(child, hat); }

    @Override public void ended(IntentRunner.Result result) {
        var held = AquaticLife.carried(owner);
        if (result != IntentRunner.Result.DONE && held != null) owner.family().carry().finish(held, "unload_young_ended");
    }
}
