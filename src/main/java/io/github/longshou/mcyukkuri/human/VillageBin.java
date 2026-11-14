package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Street life fills the bins, and the street's own litter goes back into them. A passer-by who walks past a knocked-over bin
 * stands it up; once a day each resident drops one real thing into a bin in reach: a caring resident a portion of the grain they
 * carry, anyone else the day's litter (a cone, cap or snack box, the generic junk headwear item). A bin takes one deposit a day
 * and never two of the same item.
 *
 * <p>Litter that a yukkuri dropped (scraps it carried off, or the junk headwear item it shook off) is the same loop's other
 * half: a resident out on the street picks up one piece it walks past and sweeps it into a bin it walks past, so the ground does
 * not fill up for ever. A bin no yukkuri could ever shove over is not filled by anyone ({@link GarbageBinBlock#reachable}).
 */
public final class VillageBin {
    static final String DAY = "VillageBinDay";
    /** The one loose piece a resident is carrying to a bin, saved with the resident so it is never lost. */
    private static final String LITTER = "VillageBinLitter";
    private static final int REACH = 2;
    /** How far a resident walks with a piece in its hands before it gives up and keeps carrying it. */
    private static final int CARRY_RANGE = 8;
    private static final double PASSING_SPEED_SQUARED = .0004;

    public static void residentTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof HumanNpc resident) || resident.tickCount % 20 != 7
                || !(resident.level() instanceof ServerLevel level) || !resident.streetObservable()) return;
        var residentData = resident.getPersistentData();
        // Off the street (night, rain, a walk on the lead) whatever it picked up goes down where it stands.
        if (!resident.streetActive() || resident.walking() || level.isRaining()) { putDownLitter(resident, level, residentData); return; }
        if (resident.getDeltaMovement().horizontalDistanceSqr() < PASSING_SPEED_SQUARED) return;
        long day = level.getDayTime() / 24000;
        var litter = carriedLitter(level, residentData);
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
            if (!GarbageBinBlock.reachable(level, at)) {
                // Nobody could ever get at this one: a bin wedged between a bench and a wall takes nothing.
                GameplayLog.limited(resident, "npc-care", "bin_skip", 600,
                        "action=bin_skip bin={} reason=untippable", at.toShortString());
                continue;
            }
            if (!litter.isEmpty()) {
                // Sweeping takes nothing new into the street, so only a free slot and a second of the same kind are refused.
                if (!sweep(level, at, litter)) continue;
                residentData.remove(LITTER);
                resident.swing(InteractionHand.OFF_HAND);
                GameplayLog.event(resident, "npc-care", "action=bin_refill bin={} day={} item={} litter=1 load={}",
                        at.toShortString(), day, litter.getItem(), bin.load());
                return;
            }
            if (residentData.contains(DAY) && residentData.getLong(DAY) == day) continue;
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
        if (litter.isEmpty()) {
            var picked = pickUpLitter(resident, level, residentData);
            if (!picked.isEmpty()) { carryToBin(resident, level, picked); return; }
            approachLitter(resident, level);
            return;
        }
        if (resident.getNavigation().isDone()) carryToBin(resident, level, litter);
    }

    /** Nothing in its hands: the nearest piece of litter it can see is what the resident walks to next. */
    private static void approachLitter(HumanNpc resident, ServerLevel level) {
        if (!resident.getNavigation().isDone()) return;
        var piece = level.getEntitiesOfClass(ItemEntity.class, resident.getBoundingBox().inflate(CARRY_RANGE, 4, CARRY_RANGE),
                        item -> item.isAlive() && !item.hasPickUpDelay() && item.onGround()
                                && litter(item.getItem()) && resident.hasLineOfSight(item) && !awaited(level, item))
                .stream().min(Comparator.comparingDouble(resident::distanceToSqr)).orElse(null);
        if (piece == null) return;
        resident.getNavigation().moveTo(piece.getX(), piece.getY(), piece.getZ(), .65);
        GameplayLog.limited(resident, "npc-care", "litter_approach", 200,
                "action=litter_approach item={} drop={}", piece.getItem().getItem(), piece.getUUID());
    }

    /** In its hands, the piece goes to the nearest bin that can take it: the resident walks there instead of waiting to pass one. */
    private static void carryToBin(HumanNpc resident, ServerLevel level, ItemStack piece) {
        var bin = nearestBin(resident, level, piece);
        if (bin == null) return;
        resident.getNavigation().moveTo(bin.getX() + .5, bin.getY() + .5, bin.getZ() + .5, .65);
        GameplayLog.limited(resident, "npc-care", "litter_carry", 200,
                "action=litter_carry item={} bin={}", piece.getItem(), bin.toShortString());
    }

    /** The closest bin that would take this piece, within a short walk; null when there is none. */
    private static BlockPos nearestBin(HumanNpc resident, ServerLevel level, ItemStack piece) {
        var here = resident.blockPosition();
        BlockPos best = null;
        double bestDistance = 1e9;
        for (int z = (here.getZ() - CARRY_RANGE) >> 4; z <= (here.getZ() + CARRY_RANGE) >> 4; z++)
            for (int x = (here.getX() - CARRY_RANGE) >> 4; x <= (here.getX() + CARRY_RANGE) >> 4; x++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var candidate : chunk.getBlockEntities().values()) {
                    if (!(candidate instanceof GarbageBinBlockEntity bin) || bin.isRemoved()) continue;
                    var at = bin.getBlockPos();
                    double distance = here.distSqr(at);
                    if (distance >= bestDistance || distance > (double) CARRY_RANGE * CARRY_RANGE || slot(level, at, piece, false) < 0) continue;
                    best = at;
                    bestDistance = distance;
                }
            }
        return best;
    }

    /** One loose piece of street litter within reach comes up into the resident's hands, on its way to a bin; empty when none. */
    private static ItemStack pickUpLitter(HumanNpc resident, ServerLevel level, CompoundTag residentData) {
        var piece = level.getEntitiesOfClass(ItemEntity.class, resident.getBoundingBox().inflate(1.25, .25, 1.25),
                        item -> item.isAlive() && !item.hasPickUpDelay() && item.onGround()
                                && litter(item.getItem()) && resident.hasLineOfSight(item) && !awaited(level, item))
                .stream().min(Comparator.comparingDouble(resident::distanceToSqr)).orElse(null);
        if (piece == null) return ItemStack.EMPTY;
        var held = piece.getItem().copyWithCount(1);
        residentData.put(LITTER, held.save(level.registryAccess()));
        var left = piece.getItem().copyWithCount(piece.getItem().getCount() - 1);
        if (left.isEmpty()) piece.discard(); else piece.setItem(left);
        resident.swing(InteractionHand.MAIN_HAND);
        GameplayLog.event(resident, "npc-care", "action=litter_pick_up item={} drop={}", held.getItem(), piece.getUUID());
        return held;
    }

    /** A loose piece a free yukkuri is already walking to is left to it: residents tidy the street, not a stray's find. */
    private static boolean awaited(ServerLevel level, ItemEntity piece) {
        return !level.getEntitiesOfClass(YukkuriEntity.class, piece.getBoundingBox().inflate(16, 4, 16),
                y -> y.isAlive() && y.treasure().hunting(piece)).isEmpty();
    }

    /** Loose street litter: the scraps a yukkuri carried off and the junk headwear item it took off again. */
    private static boolean litter(ItemStack stack) {
        return JunkHeadwear.isItem(stack) || stack.is(Items.PAPER) || stack.is(Items.STICK) || stack.is(Items.FEATHER);
    }

    private static ItemStack carriedLitter(ServerLevel level, CompoundTag residentData) {
        if (!residentData.contains(LITTER)) return ItemStack.EMPTY;
        return ItemStack.parseOptional(level.registryAccess(), residentData.getCompound(LITTER));
    }

    private static void putDownLitter(HumanNpc resident, ServerLevel level, CompoundTag residentData) {
        var held = carriedLitter(level, residentData);
        if (held.isEmpty()) return;
        if (ItemContact.release(resident, held, resident.position(), Vec3.ZERO) == null) return;
        residentData.remove(LITTER);
        GameplayLog.event(resident, "npc-care", "action=litter_put_down item={} count={}", held.getItem(), held.getCount());
    }

    /** A resident that dies with a piece of street litter in its hands leaves it where the body fell. */
    public static void dropLitter(HumanNpc resident) {
        if (resident.level() instanceof ServerLevel level) putDownLitter(resident, level, resident.getPersistentData());
    }

    public static boolean accepts(ServerLevel level, BlockPos at, ItemStack portion) {
        return slot(level, at, portion, true) >= 0;
    }

    public static boolean deposit(ServerLevel level, BlockPos at, ItemStack portion) {
        int index = slot(level, at, portion, true);
        if (index < 0) return false;
        var bin = (GarbageBinBlockEntity) level.getBlockEntity(at);
        bin.setItem(index, portion.copyWithCount(1));
        bin.deposited(Math.floorDiv(level.getDayTime(), HumanStreet.DAY));
        return true;
    }

    /** Litter swept off the street: the same free slot and no second of the same kind, with no daily refill limit in the way. */
    private static boolean sweep(ServerLevel level, BlockPos at, ItemStack piece) {
        int index = slot(level, at, piece, false);
        if (index < 0) return false;
        ((GarbageBinBlockEntity) level.getBlockEntity(at)).setItem(index, piece.copyWithCount(1));
        return true;
    }

    /**
     * A free slot in a standing bin that holds none of this item, and that a yukkuri could actually reach; else -1. A resident's
     * own daily deposit also needs a bin that has had none today. An upright bin wedged shut is nobody's place to put anything.
     */
    private static int slot(ServerLevel level, BlockPos at, ItemStack portion, boolean onceADay) {
        if (!level.hasChunkAt(at) || !(level.getBlockEntity(at) instanceof GarbageBinBlockEntity bin) || bin.tipped()
                || onceADay && bin.lastDeposit() == Math.floorDiv(level.getDayTime(), HumanStreet.DAY)
                || !GarbageBinBlock.reachable(level, at)) return -1;
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
