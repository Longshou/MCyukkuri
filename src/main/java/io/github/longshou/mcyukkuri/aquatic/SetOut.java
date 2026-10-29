package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Setting out (refactor WP6a, design 8.3 出航; HARD, a short commitment only danger breaks): with her stick (tucked in the hat, or
 * one picked up nearby) she walks to a bank edge beside calm water with room for the hull, sets her hat down on the water
 * ({@code PlaceProp} in water mode, {@code hat_off_self_aq}), and the family gets aboard: a young that hops boards by its own
 * {@code board_parent} desire, one that cannot is carried in her mouth, hopped aboard with and put down in the hollow; she hops
 * aboard herself. Whether she carries the young before boarding first follows from the {@code protect_young} desire against
 * {@code water_life} (Q4: nothing fixes the captain last). Done once she rides her own hat afloat with every young of hers near
 * aboard and nothing in her mouth. A step that fails fails the intent; the hat left out is then her {@code resolve_own_hat}.
 */
final class SetOut implements Intent {
    /** The whole setting out (ticks), and the longest call for the young to come aboard. */
    private static final int DEADLINE = 2400, WAIT_YOUNG = 300;

    private final YukkuriEntity owner;
    private final long started;
    private final Set<UUID> sticks = new HashSet<>();
    private Waters.Edge berth;
    /** She has been aboard in this setting out: from then on a young left ashore is fetched, never a reason to board first. */
    private boolean boarded;

    SetOut(YukkuriEntity owner) {
        this.owner = owner;
        this.started = owner.level().getGameTime();
    }

    @Override public String id() { return "set_out"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public Commitment commitment() { return Commitment.HARD; }

    @Override public boolean satisfied() {
        var hat = AquaticLife.hat(owner);
        return AquaticLife.afloat(hat) && AquaticLife.aboard(owner, hat) && AquaticLife.carried(owner) == null
                && AquaticLife.young(owner).stream().allMatch(y -> AquaticLife.aboard(y, hat));
    }

    @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(owner);
        if (hat == null) return launch();
        if (!AquaticLife.afloat(hat)) return null;
        boolean aboard = AquaticLife.aboard(owner, hat);
        boarded |= aboard;
        var held = AquaticLife.carried(owner);
        if (held != null) return aboard ? Ferry.releaseAboard(owner, hat, held) : Ferry.hopAboard(owner, hat);
        var dependent = dependentAshore(hat);
        if (dependent != null) {
            if (aboard) return List.of(Ferry.hopAshore(owner));
            if (boarded || youngFirst()) return Ferry.grabAshore(owner, dependent);
        }
        if (!aboard) return Ferry.hopAboard(owner, hat);
        return List.of(() -> new Step.Wait(this::satisfied, WAIT_YOUNG, Behaviors.BOAT_CALL));
    }

    /** Still wearing the hat: a stick first when she has none and one lies near, then the berth and the hat onto the water. */
    private List<Supplier<Step>> launch() {
        if (!AquaticLife.stick(owner)) {
            var stick = Waters.stick(owner, 10, true);
            if (stick != null && sticks.add(stick.getUUID())) return fetch(stick);
        }
        if (berth == null) {
            berth = Waters.berth(owner);
            if (berth == null) {
                GameplayLog.limited(owner, "aquatic", "berth.none", 200, "berth none reason=no_roomy_bank_edge at={}", GameplayLog.at(owner.position()));
                return null;
            }
            GameplayLog.event(owner, "aquatic", "berth stand={} water={} yaw={}", berth.stand().toShortString(), berth.water().toShortString(), berth.yaw());
        }
        var edge = berth;
        var stand = edge.stand(owner.getBbWidth());
        double reach = HatGeometry.brim()[0] * HatScale.of(owner) / 16;
        return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(Walk.stand(stand, 1)),
                () -> new Step.Do(new Face(edge.yaw())),
                // the hat's short axis toward the bank: its near brim reaches the edge
                () -> new Step.Do(new PlaceProp(new HatProp(owner, true, true), edge.onWater(reach + .05), edge.yaw() + 90)));
    }

    /** Walk beside the stick, face it, pick it up into her hat. */
    private List<Supplier<Step>> fetch(ItemEntity stick) {
        return List.of(() -> {
                    var at = SurfaceApproach.beside(owner, stick, owner.getBbWidth() / 2 + .2, 1.2);
                    return at == null ? null : new Step.Do(Walk.stand(at, 1));
                },
                () -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), stick.position()))),
                () -> new Step.Do(new Pick(stick, one -> AquaticLife.stow(owner, one))));
    }

    /** Her nearest young ashore that cannot get aboard by itself: it cannot hop, or its own hop aboard just failed. */
    private YukkuriEntity dependentAshore(PlacedHatEntity hat) {
        for (var y : AquaticLife.young(owner))
            if ((!AquaticLife.jumper(y) || Drive.failed(y, "board_parent")) && AquaticLife.ashore(y) && !y.life().isSleeping()
                    && !AquaticLife.aboard(y, hat)) return y;
        return null;
    }

    /** {@code protect_young} (care for the young, with her attachment) against {@code water_life}: which she does first. */
    private boolean youngFirst() {
        double protect = AquaticGoal.num("protect_young", 40) + owner.life().personality().attachment() * AquaticGoal.num("protect_attachment", .5);
        return protect >= AquaticGoal.num("water_life", 60);
    }
}
