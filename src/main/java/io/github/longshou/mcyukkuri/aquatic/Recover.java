package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.StepIn;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Her own hat's loose item where a hull came down on the floor (HEADWEAR; SOFT): she walks up to it on dry ground, or from the
 * bank edge nearest it steps in and swims to it, and takes it with the mouth ({@link Pick}) — the same loose-accessory receipt a
 * hatless body makes on dry ground, worn on touch, with no performance of its own. Done once it is worn; a hat she already has
 * nothing to do with ends it.
 */
final class Recover implements Intent {
    /** The whole way; the longest swim to it (the escape's own-hat swim). */
    private static final int RECOVER = 1600, SWIM = 600;
    /** Route-leg arrival distance. */
    private static final double ARRIVE = .6;

    private final YukkuriEntity owner;
    private final long started;
    /** The water way owns her body from the moment she is on her way to the bank: the escape goal leaves her to it. */
    private boolean waterTried;

    private Recover(YukkuriEntity owner) {
        this.owner = owner;
        this.started = owner.level().getGameTime();
    }

    static Recover of(YukkuriEntity owner) { return new Recover(owner); }

    /** Her own hat's loose item nearest her within the family range that nobody has picked up yet, or null. */
    static ItemEntity hat(YukkuriEntity owner) {
        return owner.level().getEntitiesOfClass(ItemEntity.class, owner.getBoundingBox().inflate(AquaticLife.FAMILY_RANGE),
                        item -> item.isAlive() && !item.getItem().isEmpty() && AccessoryItem.matches(owner, item.getItem()))
                .stream().min(Comparator.comparingDouble(owner::distanceToSqr)).orElse(null);
    }

    @Override public String id() { return "recover_hat"; }
    @Override public boolean ownsBodyInWater() { return waterTried; }
    @Override public long deadline() { return started + RECOVER; }
    @Override public Commitment commitment() { return Commitment.SOFT; }

    @Override public boolean satisfied() { return owner.life().custody().worn() || hat(owner) == null; }

    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        var item = hat(owner);
        if (item == null) return null;
        if (ContactGeometry.inMouthReach(owner, item))
            return List.of(() -> new Step.Do(new Pick(item, one -> AccessoryItem.receive(owner, one, null))));
        if (owner.life().dryStand(item.blockPosition())) return onLand(item);
        if (!AquaticLife.ashore(owner)) return List.of(() -> new Step.Do(Swim.to(() -> item.isAlive() ? item.position() : null, SWIM)));
        var edge = Waters.edges(owner, item.position(), 8, false).stream()
                .filter(e -> SurfaceApproach.canApproach(owner, e.stand(owner.getBbWidth()))).findFirst().orElse(null);
        if (edge == null) return null;
        var stand = edge.stand(owner.getBbWidth());
        waterTried = true;
        return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(Walk.stand(stand, 1)),
                () -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), item.position()))),
                () -> new Step.Do(new StepIn(Waters.surface(owner.level(), edge.water()))));
    }

    /** On dry ground: walk up beside it, face it, take it. */
    private List<Supplier<Step>> onLand(ItemEntity item) {
        var at = SurfaceApproach.beside(owner, item, owner.getBbWidth() / 2 + .3, 1.2);
        if (at == null) return null;
        var steps = new ArrayList<Supplier<Step>>();
        steps.add(() -> SurfaceApproach.arrived(owner, at) ? null : new Step.Do(Walk.stand(at, 1)));
        steps.add(() -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), item.position()))));
        return steps;
    }

    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.event(owner, "headwear", "recover {} worn={} result={}", result == IntentRunner.Result.DONE ? "done" : "ended",
                owner.life().custody().worn(), result);
    }
}
