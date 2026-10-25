package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Poops;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.component.CustomData;
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
    private static final int REFRESH_TICKS = 100, STAY_MOVE_TICKS = 160, RETRY_TICKS = 200;
    private final YukkuriEntity entity;
    private Phase phase = Phase.NONE;
    private List<BlockPos> cells = List.of();
    private long cellsAt = Long.MIN_VALUE, retryAt;
    private BlockPos destination;
    private int ticks;

    public PitSlave(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }

    /** A grown member of a group whose standing is 嗯嗯奴隶. */
    public boolean slave() {
        return !entity.level().isClientSide && entity.settlement().group() != null
                && entity.settlement().society().role() == SettlementData.Role.UNUN_SLAVE && entity.family().adult();
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
    public String activity() { return phase == Phase.RETURN ? Behaviors.SETTLEMENT_RETURN : null; }

    private boolean inPit(BlockPos pos) { return GroupPits.inCells(cells, pos); }
    private boolean ready() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && !entity.operations().active()
                && !entity.isPassenger() && !entity.isInWaterOrBubble() && !entity.isOnFire() && !entity.life().isSleeping()
                && !entity.settlement().underway() && !entity.family().active() && !entity.body().dying();
    }
    public boolean canUse() {
        return penned() && level().getGameTime() >= retryAt && ready() && !entity.settlement().duties().workPending();
    }
    public boolean canContinue() {
        return penned() && level().getGameTime() >= retryAt && ready() && !entity.settlement().duties().workPending();
    }

    public void tick() {
        ticks++;
        if (!inPit(entity.blockPosition())) { returnToPit(); return; }
        if (phase == Phase.RETURN) {
            GameplayLog.event(entity, "settlement", "pit slave back in pit at={} cells={}", entity.blockPosition(), cells.size());
            phase = Phase.NONE; destination = null;
        }
        if (entity.life().nutrition() <= HUNGRY_AT && entity.life().canEat() && eat()) return;
        stay();
    }

    private void returnToPit() {
        if (phase != Phase.RETURN) {
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

    /** True while the meal is under way (walking to the heap or taking the portion); false when there is nothing to eat. */
    private boolean eat() {
        var heap = Poops.richest(level(), cells, entity.position());
        if (heap == null) {
            GameplayLog.limited(entity, "settlement", "pit_hungry", 600, "pit slave hungry reason=no_droppings cells={} nutrition={}",
                    cells.size(), entity.life().nutrition());
            return false;
        }
        var cell = heap.getBlockPos();
        if (entity.position().subtract(Vec3.atBottomCenterOf(cell)).horizontalDistanceSqr() > .36) {
            phase = Phase.EAT;
            if (ticks % 10 == 1 && !entity.life().travel(cell, 1.0))
                GameplayLog.limited(entity, "settlement", "pit_eat_path", 600, "pit slave meal refused reason=no_path heap={}", cell);
            return true;
        }
        SurfaceApproach.halt(entity);
        int before = heap.count();
        var portion = heap.takeOne();
        if (portion.isEmpty()) return false;
        if (!entity.life().beginMeal(portion.copy(), null, false)) {
            // Not taken into the mouth: the very portion goes back on the cell it came from.
            Poops.place(level(), cell, portion, null);
            GameplayLog.limited(entity, "settlement", "pit_eat_refused", 600, "pit slave meal refused reason=mouth_unavailable heap={} nutrition={}",
                    cell, entity.life().nutrition());
            return false;
        }
        var recorded = portion.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        GameplayLog.event(entity, "settlement", "pit slave eats droppings heap={} portions={} -> {} nutrition={} producer={}", cell, before, before - 1,
                entity.life().nutrition(), recorded.hasUUID("Source") ? recorded.getUUID("Source") : "unknown");
        phase = Phase.NONE;
        return true;
    }

    private void stay() {
        phase = Phase.STAY;
        if (ticks % STAY_MOVE_TICKS == 0 && cells.size() > 1 && entity.getNavigation().isDone()) {
            var next = cells.get(entity.getRandom().nextInt(cells.size()));
            if (!next.equals(entity.blockPosition())) entity.life().travel(next, .7);
        }
    }

    public void stop() {
        phase = Phase.NONE; destination = null; ticks = 0;
        entity.getNavigation().stop();
    }
}
