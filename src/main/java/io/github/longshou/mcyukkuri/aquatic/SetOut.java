package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
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
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
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
 * {@code board_parent} desire. She boards first, then braid-fetches dependent young from beside the boat into their seats.
 * Sleeping young, those in the water and those without a seat stay behind; a failed fetch ends this setting out. The hat is set
 * down at a dry stand with her own hop reach to the planned captain seat and braid reach from there to every dependent young.
 */
final class SetOut implements Intent {
    /** The whole setting out (ticks), and the longest call for the young to come aboard. */
    private static final int DEADLINE = 2400, WAIT_YOUNG = 300;

    private final YukkuriEntity owner;
    private final long started;
    private final Set<UUID> sticks = new HashSet<>();
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

    /** Why this young stays ashore now, or null. */
    private String staying(YukkuriEntity y) {
        var hat = AquaticLife.hat(owner);
        if (hat != null && !AquaticLife.aboard(y, hat) && hat.seatDestination(y) == null) return "no_seat";
        return y.life().isSleeping() ? "asleep" : y.isInWaterOrBubble() ? "in_water" : excused.get(y.getUUID());
    }

    @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive)); }

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
        var young = AquaticLife.young(owner);
        for (var y : young) if (!AquaticLife.aboard(y, hat)) stayed.put(y.getUUID(), String.valueOf(staying(y)));
        GameplayLog.event(owner, "aquatic", "set_out complete hat={} aboard={}/{}", hat.getUUID(), young.size() - stayed.size(), young.size());
        if (stayed.isEmpty()) return;
        GameplayLog.event(owner, "aquatic", "set_out left_ashore={}", stayed);
        left.accept(stayed);
    }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(owner);
        if (hat == null && !AquaticLife.stick(owner)) {
            var stick = Waters.stick(owner, 10, true);
            if (stick != null && sticks.add(stick.getUUID())) return fetch(stick);
        }
        if (!AquaticLife.aboard(owner, hat)) {
            var fetch = dependentAshore(hat);
            return List.of(() -> new Step.Sub(new Launch(owner, deadline(), fetch)));
        }
        if (!AquaticLife.afloat(hat)) return null;
        var dependent = dependentAshore(hat);
        if (!dependent.isEmpty()) return Ferry.fetchAboard(owner, hat, dependent.getFirst());
        // a second call means the first went unanswered: those not aboard by now stay
        if (calls++ > 0) for (var y : AquaticLife.young(owner))
            if (!AquaticLife.aboard(y, hat) && staying(y) == null) excused.put(y.getUUID(), "did_not_board");
        return List.of(() -> new Step.Wait(this::satisfied, WAIT_YOUNG, Behaviors.BOAT_CALL));
    }

    /** Launch and sit only; the parent runner consumes this result before rescue or family boarding continues. */
    record Launch(YukkuriEntity owner, long deadline, List<YukkuriEntity> fetch) implements Intent {
        @Override public String id() { return "launch"; }
        @Override public Commitment commitment() { return Commitment.HARD; }
        @Override public boolean satisfied() {
            var hat = AquaticLife.hat(owner);
            return AquaticLife.afloat(hat) && AquaticLife.aboard(owner, hat);
        }
        @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive)); }
        private List<Supplier<Step>> derive() {
            var hat = AquaticLife.hat(owner);
            if (hat != null) return AquaticLife.afloat(hat) ? Ferry.hopAboard(owner, hat, null) : null;
            var edge = Waters.berth(owner, fetch);
            if (edge == null) {
                GameplayLog.limited(owner, "aquatic", "berth.none", 200, "berth none reason=no_launch_berth_with_hop_and_braid_reach wards={} at={}",
                        fetch.size(), GameplayLog.at(owner.position()));
                return List.of(() -> new Step.Do(new Await(() -> false, ClipMarkers.of(Behaviors.BOAT_CALL).length(), Behaviors.BOAT_CALL)));
            }
            GameplayLog.event(owner, "aquatic", "berth stand={} water={} yaw={}", edge.stand().toShortString(), edge.water().toShortString(), edge.yaw());
            var stand = edge.stand(owner.getBbWidth());
            return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(new Walk(stand)),
                    () -> new Step.Do(new Face(edge.yaw())),
                    // Moor diagonally: both seats clear the bank and remain within their riders' hop reach.
                    () -> new Step.Do(new PlaceProp(new HatProp(owner, true, true), Waters.hatAt(edge, owner), edge.yaw() + (float) HullBounds.BOARDING_YAW)));
        }
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
    private List<YukkuriEntity> dependentAshore(PlacedHatEntity hat) {
        return AquaticLife.young(owner).stream().filter(y -> (!AquaticLife.jumper(y) || Drive.failed(y, "board_parent"))
                && AquaticLife.ashore(y) && staying(y) == null && !AquaticLife.aboard(y, hat)).toList();
    }

}
