package io.github.longshou.mcyukkuri.house;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Lifecycle of loose house materials (lint, food scraps, waste) that exist as item entities, such as ones thrown with Q or
 * dropped by an interrupted resident. A loose item is only an interim state: once it has settled it must lie on a visible,
 * reachable floor (otherwise it is moved to the nearest one); if nobody takes it within {@link #SETTLE_TICKS} it becomes a
 * thin ground pile, which has budgets, can be sought out by residents, and rots when neglected. State is derived from the
 * loaded entity itself, so chunk unloads and save reloads need no extra bookkeeping.
 */
public final class DropSettling {
    public static final int CHECK_TICKS = 20, SETTLE_TICKS = 600, RETRY_TICKS = 1200, UNFIT_TICKS = 40;
    public static final int MAX_TRACKED = 64, REACH = 2;
    private static final Map<ServerLevel, Map<UUID, Entry>> TRACKED = new WeakHashMap<>();
    private static final class Entry { long restSince = -1, unfitSince = -1, retryAt; }

    public static void joining(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)
                || !SupplyPiles.material(item.getItem())) return;
        var map = TRACKED.computeIfAbsent(level, key -> new LinkedHashMap<>());
        // Beyond the cap the extra items simply keep vanilla item behaviour (five minute despawn).
        if (map.size() < MAX_TRACKED) map.putIfAbsent(item.getUUID(), new Entry());
    }
    public static void leaving(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        var map = TRACKED.get(level);
        if (map != null) map.remove(item.getUUID());
    }
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CHECK_TICKS != 0) return;
        for (var level : event.getServer().getAllLevels()) {
            var map = TRACKED.get(level);
            if (map != null && !map.isEmpty()) check(level, map);
        }
    }
    private static void check(ServerLevel level, Map<UUID, Entry> map) {
        long now = level.getGameTime();
        var iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            var tracked = iterator.next(); var entry = tracked.getValue();
            if (!(level.getEntity(tracked.getKey()) instanceof ItemEntity item) || !item.isAlive()
                    || !SupplyPiles.material(item.getItem())) { iterator.remove(); continue; }
            // Still flying or freshly thrown: it is not a resting object yet.
            if (item.hasPickUpDelay() || !item.onGround() && item.getDeltaMovement().lengthSqr() > .0004) {
                entry.restSince = entry.unfitSince = -1; continue;
            }
            var cell = item.blockPosition();
            boolean fit = item.onGround() && level.getFluidState(cell).isEmpty() && level.noCollision(item);
            if (!fit) {
                entry.restSince = -1;
                if (entry.unfitSince < 0) { entry.unfitSince = now; continue; }
                if (now - entry.unfitSince < UNFIT_TICKS) continue;
                // Inside a block, in a fluid or floating: bring it to the nearest open floor where it can be seen and reached.
                var ground = HouseholdEcology.groundNear(level, cell, REACH);
                if (ground != null) {
                    item.setPos(ground.getX() + .5, ground.getY() + .02, ground.getZ() + .5);
                    item.setDeltaMovement(Vec3.ZERO); entry.unfitSince = -1;
                }
                continue;
            }
            entry.unfitSince = -1;
            if (entry.restSince < 0) entry.restSince = now;
            if (now < entry.retryAt || now - entry.restSince < SETTLE_TICKS) continue;
            var stack = item.getItem();
            int accepted = HouseholdEcology.residual(level, cell, stack, true);
            if (accepted <= 0) { entry.retryAt = now + RETRY_TICKS; continue; }
            var rest = stack.copy(); rest.shrink(accepted);
            if (rest.isEmpty()) { item.discard(); iterator.remove(); } else item.setItem(rest);
        }
    }
    private DropSettling() {}
}
