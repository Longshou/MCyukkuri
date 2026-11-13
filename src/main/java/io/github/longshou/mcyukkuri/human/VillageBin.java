package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.settlement.GarbageSearch;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** A passer-by leaves one real scrap in a nearby street bin. Both the resident and the bin remember the day. */
public final class VillageBin {
    private static final String DAY = "VillageBinDay";
    private static final int REACH = 2;
    private static final double PASSING_SPEED_SQUARED = .0004;

    public static void residentTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof HumanNpc resident) || resident.tickCount % 20 != 7
                || !(resident.level() instanceof ServerLevel level) || !resident.streetActive()
                || resident.attitude() != HumanNpc.Attitude.CARING || resident.careFood() == 0 || resident.walking()
                || resident.getDeltaMovement().horizontalDistanceSqr() < PASSING_SPEED_SQUARED
                || level.isRaining() || !resident.streetObservable()) return;
        long day = level.getDayTime() / 24000;
        var residentData = resident.getPersistentData();
        if (residentData.contains(DAY) && residentData.getLong(DAY) == day) return;
        for (var at : BlockPos.withinManhattan(resident.blockPosition(), REACH, 1, REACH)) {
            if (!level.hasChunkAt(at) || resident.distanceToSqr(at.getCenter()) > REACH * REACH
                    || !(level.getBlockEntity(at) instanceof BarrelBlockEntity barrel)) continue;
            var bin = barrel.getPersistentData();
            if (!bin.getBoolean(GarbageSearch.MARKER) || !bin.getBoolean("VillageBin") || bin.contains(DAY) && bin.getLong(DAY) == day) continue;
            var hit = level.clip(new ClipContext(resident.getEyePosition(), at.getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, resident));
            if (!at.equals(hit.getBlockPos())) continue;
            if (!addScrap(barrel)) continue;
            int before = resident.careFood();
            resident.drawCareFood(1);
            bin.putLong(DAY, day);
            barrel.setChanged();
            residentData.putLong(DAY, day);
            resident.swing(InteractionHand.OFF_HAND);
            GameplayLog.event(resident, "npc-care", "action=bin_refill bin={} day={} item=mcyukkuri:oil_grain_regular food={}->{}",
                    at.toShortString(), day, before, resident.careFood());
            return;
        }
    }

    private static boolean addScrap(BarrelBlockEntity barrel) {
        int empty = -1;
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
            var stack = barrel.getItem(slot);
            if (stack.is(ModItems.GRAIN_REGULAR.get())) return false;
            if (stack.isEmpty() && empty < 0) empty = slot;
        }
        if (empty < 0) return false;
        barrel.setItem(empty, new ItemStack(ModItems.GRAIN_REGULAR.get()));
        return true;
    }

    private VillageBin() {}
}
