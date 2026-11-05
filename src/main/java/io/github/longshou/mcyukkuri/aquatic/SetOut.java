package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.BraidFetch;
import io.github.longshou.mcyukkuri.family.FamilyRendezvous;
import io.github.longshou.mcyukkuri.family.ParentCarry;
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
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Grab;
import io.github.longshou.mcyukkuri.motor.action.Pick;
import io.github.longshou.mcyukkuri.motor.action.PlaceProp;
import io.github.longshou.mcyukkuri.motor.action.Release;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.social.Bonds;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Setting out (出航; HARD, a short commitment only danger breaks): with her stick (tucked in the hat, or
 * one picked up nearby, lying or afloat beside the bank; with none in reach she sets out without) she walks to a bank edge beside
 * calm water with room for the hull (the nearest one carrying a hull within {@link Waters#FAR} blocks when none is within berth
 * reach, calling for the young that follow on their own between strides of a few blocks), sets her hat down on the water
 * ({@code PlaceProp} in water mode, {@code hat_off_self_aq}), and the family gets aboard: a young that hops boards by its own
 * {@code board_parent} desire. A young ashore that cannot get to the water by itself is lifted by the shared Grab (the hold it
 * picks) and carried to that shore; she sets it down at the berth stand before she places the hat, and picks it up again from the
 * near brim and seats it after reaching her own seat.
 * Sleeping young, those in the water and those without a seat stay behind; a failed fetch ends this setting out. She gathers
 * responsive young at the bank first; the hat is set down at a dry stand with her own hop reach to its brim,
 * and she approaches a wandering child on foot before lifting it.
 */
final class SetOut implements Intent, FamilyRendezvous {
    /** The whole setting out (the walk to a far shore included), the post-launch boarding call, and the longer bank gather (ticks). */
    private static final int DEADLINE = 6000, WAIT_YOUNG = 300, GATHER_YOUNG = WAIT_YOUNG * 4;
    /** The longest stretch (blocks) she walks toward a far shore before she waits for her young. */
    private static final double STRIDE = 6;

    private final YukkuriEntity owner;
    private final long started;
    private final Set<UUID> sticks = new HashSet<>();
    /** The far shores she already walked to this setting out (the berth was still none there): not chosen again. */
    private final Set<BlockPos> shores = new HashSet<>();
    private int calls;
    /** End of the one gather call, or zero when no gather wait is running. */
    private long gatherUntil;
    /** The far shore she is heading for, or null. */
    private Waters.Edge heading;
    /** The young she already called to lift this setting out: one that did not come, or was let go on the way, is led from then on. */
    private final Set<UUID> asked = new HashSet<>();
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
        if (hat == null || AquaticLife.ashore(owner)) {
            var bank = hat == null ? Waters.berth(owner) : Waters.beside(hat, owner);
            return SurfaceApproach.beside(child, owner, (child.getBbWidth() + owner.getBbWidth()) / 2 + .05, 1.2,
                    Double.POSITIVE_INFINITY, at -> bank == null || at.subtract(owner.position()).dot(bank.normal()) < 0);
        }
        var edge = Waters.beside(hat, child);
        return edge == null ? null : Waters.boardingStand(edge, child);
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
        if (hat != null && hat.settled(owner) && !AquaticLife.aboard(y, hat) && hat.restSpot(y) == null) return "no_room";
        return y.life().isSleeping() ? "asleep" : y.isInWaterOrBubble() ? "in_water" : excused.get(y.getUUID());
    }

    @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive)); }

    @Override public void ended(IntentRunner.Result result) {
        var held = AquaticLife.carried(owner);
        // what an unfinished carry still holds goes to the carry's own set-down
        if (result != IntentRunner.Result.DONE && held != null) owner.family().carry().finish(held, id() + "_ended");
        if (result != IntentRunner.Result.DONE) return;
        var hat = AquaticLife.hat(owner);
        var stayed = new LinkedHashMap<UUID, String>();
        var young = AquaticLife.young(owner);
        for (var y : young) if (!AquaticLife.aboard(y, hat)) stayed.put(y.getUUID(), String.valueOf(staying(y)));
        GameplayLog.event(owner, "aquatic", "set_out complete hat={} aboard={}/{} children={}", hat.getUUID(), young.size() - stayed.size(),
                young.size(), young.stream().filter(y -> !stayed.containsKey(y.getUUID())).map(YukkuriEntity::getUUID).toList());
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
            if (edge == null) {
                // standing at a far shore that still has no berth: that one is done with
                if (heading != null && SurfaceApproach.arrived(owner, heading.stand(owner.getBbWidth()))) { shores.add(heading.stand()); heading = null; }
                if (heading == null) heading = Waters.farShore(owner, shores);
                if (heading != null) {
                    var carriable = carriable();
                    return carriable != null ? lift(carriable, heading) : toShore(heading);
                }
            }
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
                if (!ready && owner.level().getGameTime() >= gatherUntil) {
                    var carriable = carriable();
                    if (carriable != null) return lift(carriable, edge);
                    for (var y : fetch) if (!y.family().withinFollowDistance(owner) && !BraidFetch.withinReach(owner, y)) {
                        excused.put(y.getUUID(), "did_not_gather");
                        GameplayLog.event(owner, "aquatic", "set_out gather missed child={} at={} reason=did_not_gather", y.getUUID(), GameplayLog.at(y.position()));
                    }
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
        return List.of(() -> new Step.Wait(() -> satisfied() || !dependentAshore(hat).isEmpty(), WAIT_YOUNG, Behaviors.BOAT_CALL));
    }

    /** Launch and sit only; the parent runner consumes this result before rescue or family boarding continues. */
    static final class Launch implements Intent {
        private final YukkuriEntity owner;
        private final long deadline;
        /** Her hat afloat out of hop reach of every stand is drawn in with one braid stroke, as a landing family does. */
        private final Ferry.Beckoning beckoning = new Ferry.Beckoning();

        Launch(YukkuriEntity owner, long deadline) {
            this.owner = owner;
            this.deadline = deadline;
        }

        @Override public long deadline() { return deadline; }
        @Override public String id() { return "launch"; }
        @Override public Commitment commitment() { return Commitment.HARD; }
        @Override public boolean satisfied() {
            var hat = AquaticLife.hat(owner);
            return AquaticLife.afloat(hat) && hat.settled(owner);
        }
        @Override public List<Method> methods() { return List.of(Plan.of("launch", this::derive)); }
        private List<Supplier<Step>> derive() {
            var hat = AquaticLife.hat(owner);
            if (hat != null) return AquaticLife.afloat(hat) ? Ferry.hopAboard(owner, hat, beckoning) : null;
            return place(owner);
        }
    }

    private static List<Supplier<Step>> place(YukkuriEntity owner) {
        var edge = Waters.berth(owner);
        if (edge == null) {
            GameplayLog.limited(owner, "aquatic", "berth.none", 200, "berth none reason=no_launch_berth_with_hop_reach at={}",
                    GameplayLog.at(owner.position()));
            return List.of(() -> new Step.Wait(() -> Waters.berth(owner) != null, ClipMarkers.of(Behaviors.BOAT_CALL).length(), Behaviors.BOAT_CALL));
        }
        GameplayLog.event(owner, "aquatic", "berth stand={} water={} yaw={}", edge.stand().toShortString(), edge.water().toShortString(), edge.yaw());
        var stand = Waters.boardingStand(edge, owner);
        return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(new Walk(stand)),
                // a young carried here is set down at the stand: the hat never leaves her head with one on the brim or in the braid
                () -> {
                    var held = AquaticLife.carried(owner);
                    return held == null ? null : new Step.Do(new Release(held));
                },
                () -> new Step.Do(new Face(edge.yaw())),
                // Keep the whole hull clear of the bank; boarding reaches its supported brim first.
                () -> new Step.Do(new PlaceProp(new HatProp(owner, true, true), Waters.hatAt(edge, owner), edge.yaw() + (float) HullBounds.BOARDING_YAW)));
    }

    /** Too far from any water to set a hat down: walk to the nearest bank edge that carries one (the young follow her there), then
     *  set out from it. */
    private List<Supplier<Step>> toShore(Waters.Edge shore) {
        var stand = shore.stand(owner.getBbWidth());
        GameplayLog.limited(owner, "aquatic", "set_out.far_shore", 200, "set_out far shore stand={} water={} distance={} young={}",
                GameplayLog.at(stand), shore.water().toShortString(), Math.round(Math.sqrt(owner.distanceToSqr(stand))), strays(owner).size());
        return List.of(() -> SurfaceApproach.arrived(owner, stand) ? null : new Step.Do(new Walk(stride(stand))),
                () -> {
                    if (!lagging()) return null;
                    long until = owner.level().getGameTime() + GATHER_YOUNG;
                    return new Step.Wait(() -> !lagging() || owner.level().getGameTime() >= until, GATHER_YOUNG + 1, Behaviors.BOAT_CALL);
                });
    }

    /** Her nearest young ashore that cannot get to the water by itself and that her holds can carry now, or null. */
    private YukkuriEntity carriable() {
        return dependentAshore(null).stream().filter(y -> !asked.contains(y.getUUID()) && ParentCarry.capacityProblem(owner, y) == null)
                .findFirst().orElse(null);
    }

    /** Walk within the shared Grab's reach, call the young beside her, then carry it to the chosen bank. */
    private List<Supplier<Step>> lift(YukkuriEntity child, Waters.Edge shore) {
        asked.add(child.getUUID());
        return List.of(() -> {
                    if (reaches(child)) return null;
                    var stand = new Grab(child).contactStand(owner);
                    return stand == null ? null : new Step.Do(Walk.stand(stand, 1));
                }, () -> {
                    long until = owner.level().getGameTime() + WAIT_YOUNG;
                    return reaches(child) ? null : new Step.Wait(() -> reaches(child) || owner.level().getGameTime() >= until,
                            WAIT_YOUNG + 1, Behaviors.BOAT_CALL);
                },
                () -> {
                    if (!reaches(child)) {
                        GameplayLog.event(owner, "aquatic", "set_out carry skipped child={} reason=out_of_reach at={}", child.getUUID(),
                                GameplayLog.at(child.position()));
                        return null;
                    }
                    GameplayLog.event(owner, "aquatic", "set_out carry child={} purpose=to_water at={} shore={}", child.getUUID(),
                            GameplayLog.at(child.position()), shore.stand().toShortString());
                    return new Step.Do(new Grab(child));
                });
    }

    private boolean reaches(YukkuriEntity child) { return new Grab(child).check(owner).ok(); }

    /** The next stretch toward the stand: the whole way when no young follows her, else at most {@link #STRIDE} blocks on a floor she
     *  can walk to, so that the young (slow when small) keep up. */
    private Vec3 stride(Vec3 stand) {
        var to = stand.subtract(owner.position()).multiply(1, 0, 1);
        if (to.length() <= STRIDE || strays(owner).isEmpty()) return stand;
        for (double d = STRIDE; d >= 2; d -= 2) {
            var at = SurfaceApproach.floor(owner, owner.position().add(to.normalize().scale(d)));
            if (at != null && SurfaceApproach.canApproach(owner, at)) return at;
        }
        return stand;
    }

    /** Some young of hers on land has not yet come to her side. */
    private boolean lagging() { return strays(owner).stream().anyMatch(y -> !y.family().withinFollowDistance(owner)); }

    /** Walk beside the stick, face it, pick it up into her hat. */
    private List<Supplier<Step>> fetch(ItemEntity stick) {
        GameplayLog.event(owner, "aquatic", "stick target chosen item={} at={} afloat={}", stick.getUUID(), GameplayLog.at(stick.position()), stick.isInWater());
        return List.of(() -> {
                    var at = SurfaceApproach.beside(owner, stick, owner.getBbWidth() / 2 + .2, 1.2);
                    return at == null ? null : new Step.Do(Walk.stand(at, 1));
                },
                () -> new Step.Do(new Face(SurfaceApproach.yaw(owner.position(), stick.position()))),
                () -> new Step.Do(new Pick(stick, one -> {
                    boolean stowed = AquaticLife.stow(owner, one);
                    if (stowed) GameplayLog.event(owner, "aquatic", "stick taken item={} stowed_in={}", one.getItem(), AquaticLife.hat(owner) != null ? "hat" : "worn_hat");
                    return stowed;
                })));
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

    /** Her own young on land within the family search range that still need to follow her. */
    static List<YukkuriEntity> strays(YukkuriEntity parent) {
        return parent.level().getEntitiesOfClass(YukkuriEntity.class, parent.getBoundingBox().inflate(FamilyLife.FAMILY_SEARCH_RANGE),
                y -> y.isAlive() && !y.family().adult() && Bonds.parentOf(parent, y)
                        && y.childhood().shouldStayWithParents() && !y.isPassenger() && !y.isInWaterOrBubble());
    }

    /** A narrow horizon scan for an own young on land too far away for the ordinary aquatic family list. */
    static boolean dependentBeyondGatherRange(YukkuriEntity parent) {
        return strays(parent).stream().anyMatch(y -> parent.distanceToSqr(y) > AquaticLife.FAMILY_RANGE * AquaticLife.FAMILY_RANGE);
    }

}
