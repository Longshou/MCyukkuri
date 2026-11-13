package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Street life refills the bins. A passer-by who walks past a knocked-over bin stands it up; once a day each resident drops
 * one real thing into a bin in reach: a caring resident a portion of the grain they carry, anyone else the day's litter (a cone,
 * cap or snack box, the generic junk headwear item). A bin takes one deposit a day and never two of the same item.
 */
public final class VillageBin {
    static final String DAY = "VillageBinDay";
    private static final int REACH = 2;
    private static final double PASSING_SPEED_SQUARED = .0004;

    public static void residentTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof HumanNpc resident) || resident.tickCount % 20 != 7
                || !(resident.level() instanceof ServerLevel level) || !resident.streetActive() || resident.walking()
                || resident.getDeltaMovement().horizontalDistanceSqr() < PASSING_SPEED_SQUARED
                || level.isRaining() || !resident.streetObservable()) return;
        long day = level.getDayTime() / 24000;
        var residentData = resident.getPersistentData();
        for (var at : BlockPos.withinManhattan(resident.blockPosition(), REACH, 1, REACH)) {
            if (!level.hasChunkAt(at) || resident.distanceToSqr(at.getCenter()) > REACH * REACH) continue;
            var candidate = level.getBlockEntity(at);
            if (!(candidate instanceof GarbageBinBlockEntity) && !GarbageBinBlock.legacy(candidate)) continue;
            var hit = level.clip(new ClipContext(resident.getEyePosition(), at.getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, resident));
            if (!at.equals(hit.getBlockPos())) continue;
            if (candidate instanceof BarrelBlockEntity barrel) {
                GarbageBinBlock.adopt(level, barrel);
                candidate = level.getBlockEntity(at);
            }
            if (!(candidate instanceof GarbageBinBlockEntity bin)) continue;
            if (bin.tipped()) {
                if (!GarbageBinBlock.standUp(level, at)) continue;
                resident.swing(InteractionHand.MAIN_HAND);
                GameplayLog.event(resident, "npc-care", "action=bin_righted bin={}", at.toShortString());
                return;
            }
            if (residentData.contains(DAY) && residentData.getLong(DAY) == day) return;
            boolean caring = resident.attitude() == HumanNpc.Attitude.CARING && resident.careFood() > 0;
            var portion = caring ? new ItemStack(ModItems.GRAIN_REGULAR.get()) : JunkHeadwear.item();
            if (!deposit(level, at, portion)) continue;
            int before = resident.careFood();
            if (caring) resident.drawCareFood(1);
            residentData.putLong(DAY, day);
            resident.swing(InteractionHand.OFF_HAND);
            GameplayLog.event(resident, "npc-care", "action=bin_refill bin={} day={} item={} food={}->{} load={}",
                    at.toShortString(), day, portion.getItem(), before, resident.careFood(), bin.load());
            return;
        }
    }

    public static boolean accepts(ServerLevel level, BlockPos at, ItemStack portion) {
        return slot(level, at, portion) >= 0;
    }

    public static boolean deposit(ServerLevel level, BlockPos at, ItemStack portion) {
        int index = slot(level, at, portion);
        if (index < 0) return false;
        var bin = (GarbageBinBlockEntity) level.getBlockEntity(at);
        bin.setItem(index, portion.copyWithCount(1));
        bin.deposited(Math.floorDiv(level.getDayTime(), HumanStreet.DAY));
        return true;
    }

    /** A free slot in a standing bin that has had no deposit today and holds none of this item; else -1. */
    private static int slot(ServerLevel level, BlockPos at, ItemStack portion) {
        if (!level.hasChunkAt(at) || !(level.getBlockEntity(at) instanceof GarbageBinBlockEntity bin) || bin.tipped()
                || bin.lastDeposit() == Math.floorDiv(level.getDayTime(), HumanStreet.DAY)) return -1;
        int empty = -1;
        for (int slot = 0; slot < bin.getContainerSize(); slot++) {
            var stack = bin.getItem(slot);
            if (stack.is(portion.getItem())) return -1;
            if (stack.isEmpty() && empty < 0) empty = slot;
        }
        return empty;
    }

    private VillageBin() {}
}
