package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HatProp;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Commitment;
import io.github.longshou.mcyukkuri.intent.Drive;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Setting out (出航; HARD, a short commitment only danger breaks): with her stick (tucked in the hat, or
 * one picked up nearby) she walks to a bank edge beside calm water with room for the hull, sets her hat down on the water
 * ({@code PlaceProp} in water mode, {@code hat_off_self_aq}), and the family gets aboard: a young that hops boards by its own
 * {@code board_parent} desire, one that cannot is carried in her mouth, hopped aboard with and put down in the hollow; she hops
 * aboard herself. Whether she carries the young before boarding first follows from the {@code protect_young} desire against
 * {@code water_life} (Q4: nothing fixes the captain last). Done once she rides her own hat afloat with nothing in her mouth and
 * every young of hers near aboard, or asleep, in the water, not fetched (the same carry failed twice) or not come aboard (the
 * same call failed twice): who stayed and why is logged and reported to the goal. Otherwise a step that fails twice fails the
 * intent; the hat left out is then her {@code resolve_own_hat}.
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
    /** How often a carry of each young was begun (a grab, a hop ashore to fetch it) and the call for the young was made: a second
     *  of the same means the first failed, and that young stays. */
    private final Map<UUID, Integer> grabs = new HashMap<>(), landings = new HashMap<>();
    private int calls;
    /** Young she sets out without, with the reason. */
    private final Map<UUID, String> excused = new LinkedHashMap<>();
    private final Consumer<Map<UUID, String>> left;

    SetOut(YukkuriEntity owner, Consumer<Map<UUID, String>> left) {
        this.owner = owner;
        this.started = owner.level().getGameTime();
        this.left = left;
    }

    @Override public String id() { return "set_out"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public Commitment commitment() { return Commitment.HARD; }

    @Override public boolean satisfied() {
        var hat = AquaticLife.hat(owner);
        return AquaticLife.afloat(hat) && AquaticLife.aboard(owner, hat) && AquaticLife.carried(owner) == null
                && AquaticLife.young(owner).stream().allMatch(y -> AquaticLife.aboard(y, hat) || staying(y) != null);
    }

    /** Why this young stays ashore now, or null: asleep, in the water, or a carry or call that failed twice. */
    private String staying(YukkuriEntity y) {
        return y.life().isSleeping() ? "asleep" : y.isInWaterOrBubble() ? "in_water" : excused.get(y.getUUID());
    }

    /** The same way twice: a failed step leaves the first, and the second goes on without the young it failed for. */
    @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive), Plan.of("launch_leaving", this::derive)); }

    @Override public void ended(IntentRunner.Result result) {
        var held = AquaticLife.carried(owner);
        if (result != IntentRunner.Result.DONE && held != null) {
            // what an unfinished carry still holds is put down where it hangs (as Fetch does)
            Motor.dismount(held, held.position(), Vec3.ZERO, "set_out_ended");
            GameplayLog.event(owner, "aquatic", "set_out release child={} at={} result={}", held.getUUID(), GameplayLog.at(held.position()), result);
        }
        if (result != IntentRunner.Result.DONE) return;
        var hat = AquaticLife.hat(owner);
        var stayed = new LinkedHashMap<UUID, String>();
        for (var y : AquaticLife.young(owner)) if (!AquaticLife.aboard(y, hat)) stayed.put(y.getUUID(), String.valueOf(staying(y)));
        if (stayed.isEmpty()) return;
        GameplayLog.event(owner, "aquatic", "set_out left_ashore={}", stayed);
        left.accept(stayed);
    }

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
            var id = dependent.getUUID();
            if (aboard ? landings.merge(id, 1, Integer::sum) > 1 : (boarded || youngFirst()) && grabs.merge(id, 1, Integer::sum) > 1) {
                excused.put(id, aboard ? "unreachable" : "not_fetched");
                return derive();
            }
            if (aboard) return List.of(Ferry.hopAshore(owner));
            if (boarded || youngFirst()) return Ferry.grabAshore(owner, dependent);
        }
        if (!aboard) return Ferry.hopAboard(owner, hat);
        // a second call means the first went unanswered: those not aboard by now stay
        if (calls++ > 0) for (var y : AquaticLife.young(owner))
            if (!AquaticLife.aboard(y, hat) && staying(y) == null) excused.put(y.getUUID(), "did_not_board");
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
        return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(Walk.stand(stand, 1)),
                () -> new Step.Do(new Face(edge.yaw())),
                // the hat's short axis toward the bank, its near brim over the edge
                () -> new Step.Do(new PlaceProp(new HatProp(owner, true, true), Waters.hatAt(edge, owner), edge.yaw() + 90)));
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
            if ((!AquaticLife.jumper(y) || Drive.failed(y, "board_parent")) && AquaticLife.ashore(y) && staying(y) == null
                    && !AquaticLife.aboard(y, hat)) return y;
        return null;
    }

    /** {@code protect_young} (care for the young, with her attachment) against {@code water_life}: which she does first. */
    private boolean youngFirst() {
        double protect = AquaticGoal.num("protect_young", 40) + owner.life().personality().attachment() * AquaticGoal.num("protect_attachment", .5);
        return protect >= AquaticGoal.num("water_life", 60);
    }
}
