package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.family.FamilyRendezvous;
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
 * {@code board_parent} desire. She loads dependent young from the near brim, then moves to her seat.
 * Sleeping young, those in the water and those without a seat stay behind; a failed fetch ends this setting out. She gathers
 * responsive young at the bank first; the hat is set down at a dry stand with her own hop reach to its brim,
 * and she approaches a wandering child on foot before lifting it.
 */
final class SetOut implements Intent, FamilyRendezvous {
    /** The whole setting out, the post-launch boarding call, and the longer bank gather (ticks). */
    private static final int DEADLINE = 2400, WAIT_YOUNG = 300, GATHER_YOUNG = WAIT_YOUNG * 4;

    private final YukkuriEntity owner;
    private final long started;
    private final Set<UUID> sticks = new HashSet<>();
    private int calls;
    /** End of the one gather call, or zero when no gather wait is running. */
    private long gatherUntil;
    private final Ferry.Beckoning beckoning = new Ferry.Beckoning();
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
    @Override public Vec3 gatherAt(YukkuriEntity child) {
        var hat = AquaticLife.hat(owner);
        return hat == null ? SurfaceApproach.beside(child, owner, (child.getBbWidth() + owner.getBbWidth()) / 2 + .05, 1.2)
                : SurfaceApproach.beside(child, hat, (child.getBbWidth() + hat.getBoundingBox().getXsize()) / 2 + .05, 1.2);
    }

    @Override public boolean satisfied() {
        var hat = AquaticLife.hat(owner);
        return AquaticLife.afloat(hat) && hat.settled(owner) && AquaticLife.carried(owner) == null
                && AquaticLife.young(owner).stream().allMatch(y -> hat.settled(y) || staying(y) != null);
    }

    /** Why this young stays ashore now, or null. */
    private String staying(YukkuriEntity y) {
        if (y.family().stage() == FamilyLife.Stage.BABY && y.childhood().isNewborn() && y.childhood().shouldStayWithParents()) return null;
        var hat = AquaticLife.hat(owner);
        if (hat != null && !AquaticLife.aboard(y, hat) && hat.restSpot(y) == null) return "no_room";
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
        if (hat == null) {
            var fetch = dependentAshore(hat);
            var edge = Waters.berth(owner);
            if (edge != null && gatherUntil == 0 && !fetch.isEmpty()) {
                GameplayLog.event(owner, "aquatic", "set_out gather start children={}", fetch.stream()
                        .map(y -> y.getUUID() + "@" + GameplayLog.at(y.position())).toList());
                var stand = Waters.boardingStand(edge, owner);
                return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(new Walk(stand)), () -> {
                            var current = dependentAshore(AquaticLife.hat(owner));
                            return current.isEmpty() ? null : new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), current.getFirst().position())));
                        }, () -> {
                            gatherUntil = owner.level().getGameTime() + GATHER_YOUNG;
                            return new Step.Wait(() -> gathered() || owner.level().getGameTime() >= gatherUntil, GATHER_YOUNG + 1, Behaviors.BOAT_CALL);
                        });
            }
            if (edge != null && gatherUntil != 0) {
                boolean ready = gathered();
                if (!ready && owner.level().getGameTime() >= gatherUntil)
                    for (var y : fetch) if (!y.family().withinFollowDistance(owner) && !BraidFetch.withinReach(owner, y)) {
                        excused.put(y.getUUID(), "did_not_gather");
                        GameplayLog.event(owner, "aquatic", "set_out gather missed child={} at={} reason=did_not_gather", y.getUUID(), GameplayLog.at(y.position()));
                    }
                gatherUntil = 0;
                if (ready) GameplayLog.event(owner, "aquatic", "set_out gathered children={}", fetch.stream()
                        .map(y -> y.getUUID() + "@" + GameplayLog.at(y.position())).toList());
            }
            return place(owner);
        }
        if (!AquaticLife.afloat(hat)) return null;
        var dependent = dependentAshore(hat);
        if (!dependent.isEmpty()) return Ferry.loadFromBank(owner, hat, dependent.getFirst(), beckoning);
        if (!hat.settled(owner)) return Ferry.hopAboard(owner, hat, beckoning);
        // a second call means the first went unanswered: those not aboard by now stay
        if (calls++ > 0) for (var y : AquaticLife.young(owner))
            if (!AquaticLife.aboard(y, hat) && staying(y) == null) excused.put(y.getUUID(), "did_not_board");
        return List.of(() -> new Step.Wait(this::satisfied, WAIT_YOUNG, Behaviors.BOAT_CALL));
    }

    /** Launch and sit only; the parent runner consumes this result before rescue or family boarding continues. */
    record Launch(YukkuriEntity owner, long deadline) implements Intent {
        @Override public String id() { return "launch"; }
        @Override public Commitment commitment() { return Commitment.HARD; }
        @Override public boolean satisfied() {
            var hat = AquaticLife.hat(owner);
            return AquaticLife.afloat(hat) && hat.settled(owner);
        }
        @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive)); }
        private List<Supplier<Step>> derive() {
            var hat = AquaticLife.hat(owner);
            if (hat != null) return AquaticLife.afloat(hat) ? Ferry.hopAboard(owner, hat, null) : null;
            return place(owner);
        }
    }

    private static List<Supplier<Step>> place(YukkuriEntity owner) {
        var edge = Waters.berth(owner);
        if (edge == null) {
            GameplayLog.limited(owner, "aquatic", "berth.none", 200, "berth none reason=no_launch_berth_with_hop_reach at={}",
                    GameplayLog.at(owner.position()));
            return List.of(() -> new Step.Wait(() -> false, ClipMarkers.of(Behaviors.BOAT_CALL).length(), Behaviors.BOAT_CALL));
        }
        GameplayLog.event(owner, "aquatic", "berth stand={} water={} yaw={}", edge.stand().toShortString(), edge.water().toShortString(), edge.yaw());
        var stand = Waters.boardingStand(edge, owner);
        return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(new Walk(stand)),
                () -> new Step.Do(new Face(edge.yaw())),
                // Keep the whole hull clear of the bank; boarding reaches its supported brim first.
                () -> new Step.Do(new PlaceProp(new HatProp(owner, true, true), Waters.hatAt(edge, owner), edge.yaw() + (float) HullBounds.BOARDING_YAW)));
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

    /** Whether the current responsive wards have reached her braid on the bank. */
    private boolean gathered() {
        var fetch = dependentAshore(AquaticLife.hat(owner));
        return fetch.stream().allMatch(y -> y.family().withinFollowDistance(owner));
    }

    /** A narrow horizon scan for an own young on land too far away for the ordinary aquatic family list. */
    static boolean dependentBeyondGatherRange(YukkuriEntity parent) {
        var id = parent.getUUID();
        return !parent.level().getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(FamilyLife.FAMILY_SEARCH_RANGE),
                y -> y.isAlive() && !y.family().adult() && (id.equals(y.family().mother()) || id.equals(y.family().father()))
                        && y.childhood().shouldStayWithParents() && AquaticLife.ashore(y)
                        && parent.distanceToSqr(y) > AquaticLife.FAMILY_RANGE * AquaticLife.FAMILY_RANGE).isEmpty();
    }

}
