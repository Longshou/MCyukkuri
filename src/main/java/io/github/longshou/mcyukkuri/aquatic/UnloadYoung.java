package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.HostIntent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every own young still riding the afloat hull is brought ashore, one after another ( ashore after a ferry or a rescue):
 * <ul>
 *   <li>a capable young leaves by itself (its own {@code follow_ashore} hop);</li>
 *   <li>a young that cannot comes to the brim on her side (its own crawl, asked off by this host intent), is taken over the rim
 *       with the shared braid from the bank, and is set down there, then the next one.</li>
 * </ul>
 * Nothing is retried unchanged: a young that does not come within this intent's own clock ends it, and the drive asks again once
 * the hull or its brood changed.
 */
final class UnloadYoung implements HostIntent {
    /** The whole unloading, and the longest wait for one young to come to the brim (ticks). */
    private static final int DEADLINE = 2400, WAIT = 300;

    private final YukkuriEntity owner;
    private final PlacedHatEntity hat;
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
        final var child = next();
        if (child == null) return null;
        if (ContactGeometry.reachesFrom(owner, owner.position(), child)) return List.of(() -> new Step.Do(grab(child)));
        var edge = Waters.beside(hat, owner);
        var stand = edge == null ? null : Waters.boardingStand(edge, owner);
        int wait = (int) Math.max(1, deadline() - owner.level().getGameTime());
        GameplayLog.limited(owner, "aquatic", "unload.wait", WAIT, "unload waiting child={} stand={} at={}",
                child.getUUID(), GameplayLog.at(stand), GameplayLog.at(child.position()));
        return List.of(() -> stand == null || SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(new Walk(stand)),
                () -> new Step.Wait(() -> !child.isAlive() || !AquaticLife.aboard(child, hat)
                        || ContactGeometry.reachesFrom(owner, owner.position(), child), wait, Behaviors.BOAT_CALL),
                () -> ContactGeometry.reachesFrom(owner, owner.position(), child) ? new Step.Do(grab(child)) : null);
    }

    /** The young to take now: one the mouth or the braid already reaches, else the nearest still aboard. */
    private YukkuriEntity next() {
        var nearest = AquaticGoal.youngAboard(owner, hat);
        for (var y : AquaticLife.young(owner))
            if (y.getVehicle() == hat && ContactGeometry.reachesFrom(owner, owner.position(), y)) return y;
        return nearest;
    }

    private Action grab(YukkuriEntity child) { return new Grab(child, hat); }
}
