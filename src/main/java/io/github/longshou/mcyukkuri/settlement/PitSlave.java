package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import io.github.longshou.mcyukkuri.life.Poops;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * An 嗯嗯奴隶 kept in its group's waste pit (project design; 嗯嗯奴隶 is also the word for a victim made to eat droppings, see
 * wiki/hakkei-reference/world/personality-social.md). While it has nothing to do for the one who assigned it, this
 * goal keeps it in the pit: it walks back when it is out, stands among the cells, and when hungry (nutrition 50 or less)
 * takes one real portion from the fullest heap in the pit and eats it. No heap, no meal: nothing is conjured, it goes
 * hungry like anybody else. Ordinary food, a bed and the common ration are not part of its life (see YukkuriLife.plan).
 * Eating droppings is poor food: {@code LifeMeal.PIT_MEAL_NUTRITION} nutrition, hygiene lost, no healing.
 */
public final class PitSlave {
    private enum Phase { NONE, RETURN, EAT, STAY }
    /** Nutrition at or below which it takes a portion. */
    public static final float HUNGRY_AT = 50;
    private static final float BEG_BELOW = 30;
    /** The existing distance to a heap. A stand can lean across the pit's one-block lip. */
    private static final double MEAL_REACH = .6;
    private static final int REFRESH_TICKS = 100, STAY_MOVE_TICKS = 160, RETRY_TICKS = 200, BEG_TICKS = 600;
    private final YukkuriEntity entity;
    private Phase phase = Phase.NONE;
    private List<BlockPos> cells = List.of();
    private long cellsAt = Long.MIN_VALUE, retryAt, begAt, begUntil;
    private BlockPos destination;
    private Vec3 mealStand;
    private BlockPos rejectedFrom;
    private final Set<BlockPos> rejectedHeaps = new HashSet<>();
    private int ticks;
    /** Whether the last look found it inside the pit: the arrival is a real fact, not a phase of the walk. */
    private boolean inside;

    public PitSlave(YukkuriEntity entity) { this.entity = entity; }
    /** Executed duty phase, without refreshing pit cells or evaluating eligibility. */
    public String observedPhase() { return phase.name(); }
    private ServerLevel level() { return (ServerLevel)entity.level(); }

    /** A grown member of a group whose standing is 嗯嗯奴隶. */
    public boolean slave() {
        return entity.settlement().group() != null && entity.settlement().society().slave() && entity.family().adult();
    }
    /** A slave eats the droppings it is given or finds in its pit. */
    public boolean eatsWaste() { return slave(); }
    /** A slave whose group has a waste pit: it lives there. */
    public boolean penned() {
        if (!slave()) return false;
        long now = level().getGameTime();
        if (cellsAt == Long.MIN_VALUE || now - cellsAt >= REFRESH_TICKS || now < cellsAt) {
            cellsAt = now;
            cells = GroupPits.cells(level(), entity.settlement().group());
        }
        return !cells.isEmpty();
    }
    public String activity() {
        return phase == Phase.RETURN ? Behaviors.SETTLEMENT_RETURN : level().getGameTime() < begUntil ? Behaviors.BEG_FOOD : null;
    }

    private boolean inPit(BlockPos pos) { return GroupPits.inCells(cells, pos); }
    private boolean ready() {
        // A slave's post is the pit: its own family errands (a child search, comfort) give way to it, while a birth it is
        // carrying still holds it. Without this the search for a child elsewhere kept it from ever eating again.
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isPassenger() && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.life().isSleeping()
                && !entity.settlement().underway() && !entity.family().laboring() && !entity.family().birthDuty() && !entity.body().dying();
    }
    public boolean canUse() {
        return penned() && level().getGameTime() >= retryAt && ready() && !entity.settlement().duties().workPending();
    }
    public boolean canContinue() {
        return penned() && level().getGameTime() >= retryAt && ready() && !entity.settlement().duties().workPending();
    }

    public void tick() {
        ticks++;
        if (!inPit(entity.blockPosition())) { inside = false; returnToPit(); return; }
        if (!inside) {
            // A real arrival, whether the walk was still under way or an interruption had already put it down in the pit.
            inside = true; phase = Phase.NONE; destination = null;
            GameplayLog.event(entity, "settlement", "pit slave back in pit at={} cells={}", entity.blockPosition(), cells.size());
        }
        if (entity.life().nutrition() <= HUNGRY_AT && entity.life().canEat() && eat()) return;
        if (phase == Phase.EAT) SurfaceApproach.halt(entity);
        stay();
    }

    private void returnToPit() {
        if (phase != Phase.RETURN) {
            if (phase == Phase.EAT) SurfaceApproach.stopWalking(entity);
            phase = Phase.RETURN; destination = null; ticks = 0;
            var nearest = nearestCell();
            GameplayLog.limited(entity, "settlement", "pit_return", 600, "pit slave return start pit={} distance={} cause=outside_pit nutrition={}",
                    nearest, String.format("%.1f", Math.sqrt(nearest.distSqr(entity.blockPosition()))), entity.life().nutrition());
        }
        if (destination == null || ticks % 100 == 0) destination = nearestCell();
        if (ticks % 10 == 1 && !entity.life().travel(destination, 1.0)) {
            retryAt = level().getGameTime() + RETRY_TICKS;
            GameplayLog.limited(entity, "settlement", "pit_return_failed", 600, "pit slave return failed reason=no_path pit={} distance={}",
                    destination, String.format("%.1f", Math.sqrt(destination.distSqr(entity.blockPosition()))));
        }
    }
    private BlockPos nearestCell() {
        var here = entity.blockPosition();
        return cells.stream().min(Comparator.comparingDouble(c -> c.distSqr(here))).orElseThrow();
    }

    /** True while a portion is being approached or taken. */
    private boolean eat() {
        if (!entity.onGround()) return true;
        if (!entity.blockPosition().equals(rejectedFrom)) {
            rejectedFrom = entity.blockPosition();
            rejectedHeaps.clear();
        }
        var heap = phase == Phase.EAT && cells.contains(destination)
                && level().getBlockEntity(destination) instanceof PoopBlockEntity current
                && current.count() > 0 ? current : chooseMeal();
        if (heap == null) return false;
        var cell = heap.getBlockPos();
        var heapCentre = Vec3.atBottomCenterOf(cell);
        if (!canReachHeap(entity.position(), heapCentre)) {
            var move = SurfaceApproach.walkResult(entity, mealStand, null, 1.0);
            if (move == SurfaceApproach.Result.MOVING || move == SurfaceApproach.Result.WAITING) return true;
            refuseHeap(cell);
            SurfaceApproach.halt(entity);
            phase = Phase.NONE;
            return false;
        }
        SurfaceApproach.halt(entity);
        double before = heap.count();
        if (!heap.eat(bite -> entity.life().beginMeal(bite, null, false))) {
            GameplayLog.limited(entity, "settlement", "pit_eat_refused", 600, "pit slave meal refused reason=mouth_unavailable heap={} nutrition={}",
                    cell, entity.life().nutrition());
            return false;
        }
        GameplayLog.event(entity, "settlement", "pit slave eats droppings heap={} volume={} -> {} nutrition={}",
                cell, before, heap.count(), entity.life().nutrition());
        phase = Phase.NONE;
        return true;
    }

    private PoopBlockEntity chooseMeal() {
        var here = entity.position();
        var richest = Poops.richest(level(), cells, here);
        if (richest == null) {
            beg();
            GameplayLog.limited(entity, "settlement", "pit_hungry", 600, "pit slave hungry reason=no_droppings cells={} nutrition={}",
                    cells.size(), entity.life().nutrition());
            return null;
        }
        for (var heap = richest; heap != null; heap = Poops.richest(level(),
                cells.stream().filter(cell -> !rejectedHeaps.contains(cell)).toList(), here)) {
            var cell = heap.getBlockPos();
            var centre = Vec3.atBottomCenterOf(cell);
            var stand = canReachHeap(here, centre) ? here : null;
            if (stand == null && rejectedHeaps.contains(cell)) continue;
            if (stand == null) {
                stand = SurfaceApproach.floor(entity, centre);
                if (stand == null || !mealStandFits(stand, centre) || !SurfaceApproach.canApproach(entity, stand))
                    stand = SurfaceApproach.contactStands(entity, centre.add(0, .5, 0), MEAL_REACH - SurfaceApproach.ARRIVAL, null,
                                    at -> mealStandFits(at, centre)).stream()
                            .filter(at -> SurfaceApproach.canApproach(entity, at)).findFirst().orElse(null);
            }
            if (stand != null) {
                phase = Phase.EAT;
                destination = cell;
                mealStand = stand;
                return heap;
            }
            refuseHeap(cell);
        }
        return null;
    }

    private boolean mealStandFits(Vec3 feet, Vec3 centre) {
        return inPit(BlockPos.containing(feet)) && canReachHeap(feet, centre);
    }

    private boolean canReachHeap(Vec3 feet, Vec3 centre) {
        return feet.subtract(centre).horizontalDistanceSqr() <= MEAL_REACH * MEAL_REACH
                && Math.abs(feet.y - centre.y) <= 1
                && level().clip(new ClipContext(feet.add(0, entity.getEyeHeight(), 0), centre.add(0, .01, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
    }

    private void refuseHeap(BlockPos cell) {
        if (rejectedHeaps.add(cell)) {
            entity.showReaction("teary", "", 60);
            GameplayLog.limited(entity, "settlement", "pit_heap_unreachable", 200,
                    "pit slave contact blocked reason=no_reachable_stand heap={} nutrition={}", cell, entity.life().nutrition());
        }
    }

    /** Starving with nothing to eat, it pleads with a member standing at the bank (the outcome is unchanged). */
    private void beg() {
        long now = level().getGameTime();
        if (entity.life().nutrition() >= BEG_BELOW || now < begAt) return;
        begAt = now + 40;
        var box = entity.getBoundingBox().inflate(6);
        var member = LoadedGroupMembers.minimum(level(), box, LoadedGroupMembers.query(level(), entity.settlement().group().id, box,
                peer -> peer != entity && SettlementSociety.accepted(peer) && peer.hasLineOfSight(entity)), entity::distanceToSqr);
        if (member == null) return;
        begAt = now + BEG_TICKS;
        entity.getLookControl().setLookAt(member, 30, 30);
        begUntil = now + 60;
        GameplayLog.event(entity, "settlement", "pit slave begs member={} nutrition={}", member.getUUID(), entity.life().nutrition());
    }

    private void stay() {
        phase = Phase.STAY;
        if (ticks % STAY_MOVE_TICKS == 0 && cells.size() > 1 && entity.getNavigation().isDone() && !entity.dailyLife().active()) {
            var next = cells.get(entity.getRandom().nextInt(cells.size()));
            if (!next.equals(entity.blockPosition())) entity.life().travel(next, .7);
        }
    }

    public void stop() {
        phase = Phase.NONE; destination = null; ticks = 0;
        SurfaceApproach.stopWalking(entity);
    }
}
