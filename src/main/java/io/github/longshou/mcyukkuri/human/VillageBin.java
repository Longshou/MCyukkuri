package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.JunkHeadwear;
import io.github.longshou.mcyukkuri.interaction.ItemContact;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Street residents right bins, bring their daily contribution, and sweep yukkuri litter back into them. */
public final class VillageBin {
    static final String DAY = "VillageBinDay";
    private static final String LITTER = "mcy_yukkuri_litter";
    private static final int REACH = 2, SWEEP_RANGE = 8;
    private static final double PASSING_SPEED_SQUARED = .0004;

    public static void residentTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof HumanNpc resident) || resident.tickCount % 20 != 7
                || !(resident.level() instanceof ServerLevel level) || !resident.streetObservable() || !resident.streetActive()
                || resident.walking() || level.isRaining() || !resident.getOffhandItem().isEmpty()
                || resident.getDeltaMovement().horizontalDistanceSqr() < PASSING_SPEED_SQUARED) return;
        var residentData = resident.getPersistentData();
        long day = day(level);
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
            if (untippable(resident, level, at)) continue;
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
    }

    /** Origin lives on the loose entity, so picking it up and throwing it again makes it a player's drop. */
    public static void markLitter(Entity source, ItemEntity drop) {
        if ((source instanceof YukkuriEntity || source instanceof RemainsEntity) && litter(drop.getItem())) markLitter(drop);
    }

    public static void markLitter(ItemEntity drop) { drop.addTag(LITTER); }
    public static boolean isLitter(ItemEntity item) { return item.getTags().contains(LITTER); }

    private static boolean litter(ItemStack stack) {
        return JunkHeadwear.isItem(stack) || stack.is(Items.PAPER) || stack.is(Items.STICK) || stack.is(Items.FEATHER);
    }

    public static void dropLitter(HumanNpc resident) { dropLitter(resident, false); }

    private static void dropLitter(HumanNpc resident, boolean interruptedTrip) {
        if (!(resident.level() instanceof ServerLevel level)) return;
        var held = resident.getOffhandItem();
        if (!litter(held)) return;
        var drop = ItemContact.release(resident, held, resident.position(), Vec3.ZERO);
        if (drop == null) {
            YukkuriWorldData.get(level).deferLitter(level, resident.position(), held);
            GameplayLog.event(resident, "npc-care", "action=litter_drop_deferred item={}", held.getItem());
        } else {
            markLitter(drop);
            if (interruptedTrip) drop.setThrower(resident);
            GameplayLog.event(resident, "npc-care", "action=litter_put_down item={} drop={}", held.getItem(), drop.getUUID());
        }
        resident.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
    }

    static final class Sweep extends Goal {
        private final HumanNpc resident;
        private ItemEntity piece;
        private BlockPos bin;
        private Path route;
        private int deadline, nextScan;
        private boolean done;

        Sweep(HumanNpc resident) {
            this.resident = resident;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private ServerLevel level() { return (ServerLevel) resident.level(); }

        private boolean onStreet() {
            return resident.streetActive() && resident.streetObservable() && !resident.walking() && !level().isRaining();
        }

        @Override public boolean canUse() {
            if (!onStreet() || resident.tickCount < nextScan || !resident.getOffhandItem().isEmpty()) return false;
            nextScan = resident.tickCount + 20;
            var candidates = level().getEntitiesOfClass(ItemEntity.class, resident.getBoundingBox().inflate(SWEEP_RANGE, 4, SWEEP_RANGE),
                    item -> loose(item) && resident.hasLineOfSight(item));
            candidates.sort(Comparator.comparingDouble(resident::distanceToSqr));
            for (var item : candidates) {
                if (awaited(item) || bins(item.getItem()).isEmpty()) continue;
                var stand = reachableStand(item.position().add(0, .1, 0),
                        BlockPos.withinManhattan(item.blockPosition(), 1, 1, 1), null);
                if (stand == null) continue;
                piece = item;
                route = stand;
                return true;
            }
            return false;
        }

        @Override public void start() {
            done = !walk(route);
            if (!done) GameplayLog.event(resident, "npc-care", "action=litter_approach item={} drop={}", piece.getItem().getItem(), piece.getUUID());
        }

        @Override public boolean canContinueToUse() { return onStreet() && !done; }
        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override public void tick() {
            if (done || !onStreet()) return;
            if (piece != null) {
                if (!loose(piece)) { done = true; return; }
                if (arrived() && resident.distanceToSqr(piece) <= REACH * REACH && resident.hasLineOfSight(piece)) {
                    if (awaited(piece) || !routeToBin(piece.getItem())) { done = true; return; }
                    var held = piece.getItem().copyWithCount(1);
                    resident.setItemInHand(InteractionHand.OFF_HAND, held);
                    var left = piece.getItem().copyWithCount(piece.getItem().getCount() - 1);
                    if (left.isEmpty()) piece.discard(); else piece.setItem(left);
                    resident.swing(InteractionHand.OFF_HAND);
                    GameplayLog.event(resident, "npc-care", "action=litter_pick_up item={} drop={}", held.getItem(), piece.getUUID());
                    GameplayLog.event(resident, "npc-care", "action=litter_carry item={} bin={}", held.getItem(), bin.toShortString());
                    piece = null;
                    return;
                }
            } else {
                var held = resident.getOffhandItem();
                if (slot(level(), bin, held) < 0) { endTrip("bin_unavailable"); return; }
                if (arrived() && visible(resident.getEyePosition(), bin.getCenter(), bin)) {
                    if (deposit(level(), bin, held)) {
                        resident.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                        resident.swing(InteractionHand.OFF_HAND);
                        var destination = (GarbageBinBlockEntity) level().getBlockEntity(bin);
                        GameplayLog.event(resident, "npc-care", "action=bin_refill bin={} day={} item={} litter=1 load={}",
                                bin.toShortString(), day(level()), held.getItem(), destination.load());
                    }
                    done = true;
                    return;
                }
            }
            if (resident.tickCount >= deadline || resident.getNavigation().isDone()) endTrip("route_ended");
        }

        private void endTrip(String reason) {
            GameplayLog.limited(resident, "npc-care", "litter_route_ended", 200,
                    "action=litter_route_ended reason={} drop={} bin={}", reason, piece == null ? null : piece.getUUID(), bin);
            done = true;
        }

        @Override public void stop() {
            resident.getNavigation().stop();
            dropLitter(resident, true);
            piece = null;
            bin = null;
            route = null;
            nextScan = resident.tickCount + 40;
        }

        private boolean loose(ItemEntity item) {
            return item.isAlive() && item.onGround() && !item.hasPickUpDelay() && isLitter(item)
                    && item.getOwner() != resident && litter(item.getItem());
        }

        private boolean awaited(ItemEntity item) {
            return !level().getEntitiesOfClass(YukkuriEntity.class, item.getBoundingBox().inflate(16, 4, 16),
                    y -> y.isAlive() && y.treasure().hunting(item)).isEmpty();
        }

        private List<BlockPos> bins(ItemStack held) {
            var candidates = new ArrayList<BlockPos>();
            var here = resident.blockPosition();
            for (int z = (here.getZ() - SWEEP_RANGE) >> 4; z <= (here.getZ() + SWEEP_RANGE) >> 4; z++) {
                for (int x = (here.getX() - SWEEP_RANGE) >> 4; x <= (here.getX() + SWEEP_RANGE) >> 4; x++) {
                    var chunk = level().getChunkSource().getChunkNow(x, z);
                    if (chunk == null) continue;
                    for (var candidate : chunk.getBlockEntities().values()) {
                        if (!(candidate instanceof GarbageBinBlockEntity target) || target.isRemoved() || target.tipped()) continue;
                        var at = target.getBlockPos();
                        if (here.distSqr(at) <= SWEEP_RANGE * SWEEP_RANGE && !untippable(resident, level(), at)
                                && slot(level(), at, held) >= 0) candidates.add(at);
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble(at -> resident.distanceToSqr(at.getCenter())));
            return candidates;
        }

        private boolean routeToBin(ItemStack held) {
            resident.getNavigation().stop();
            for (var target : bins(held)) {
                var sides = new ArrayList<BlockPos>();
                for (var direction : Direction.Plane.HORIZONTAL) sides.add(target.relative(direction));
                var stand = reachableStand(target.getCenter(), sides, target);
                if (stand != null && walk(stand)) {
                    bin = target;
                    route = stand;
                    return true;
                }
            }
            return false;
        }

        private Path reachableStand(Vec3 contact, Iterable<BlockPos> cells, BlockPos target) {
            var stands = new ArrayList<BlockPos>();
            for (var cell : cells) {
                var feet = Vec3.atBottomCenterOf(cell);
                var offset = feet.subtract(resident.position());
                if (feet.distanceToSqr(contact) <= REACH * REACH && level().hasChunkAt(cell)
                        && resident.isWithinRestriction(cell)
                        && level().getBlockState(cell.below()).isFaceSturdy(level(), cell.below(), Direction.UP)
                        && level().noCollision(resident, resident.getBoundingBox().move(offset))
                        && visible(resident.getEyePosition().add(offset), contact, target)) stands.add(cell.immutable());
            }
            stands.sort(Comparator.comparingDouble(cell -> resident.distanceToSqr(cell.getCenter())));
            for (var stand : stands) {
                var path = resident.getNavigation().createPath(stand, 0);
                if (path != null && path.canReach()) return path;
            }
            return null;
        }

        private boolean visible(Vec3 eyes, Vec3 contact, BlockPos target) {
            var hit = level().clip(new ClipContext(eyes, contact, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, resident));
            return target == null ? hit.getType() == HitResult.Type.MISS : target.equals(hit.getBlockPos());
        }

        private boolean arrived() { return resident.distanceToSqr(Vec3.atBottomCenterOf(route.getTarget())) <= .64; }

        private boolean walk(Path path) {
            deadline = resident.tickCount + HumanStreet.ROUTE_TICKS;
            return resident.getNavigation().moveTo(path, .65);
        }
    }

    private static boolean untippable(HumanNpc resident, ServerLevel level, BlockPos at) {
        if (GarbageBinBlock.reachable(level, at)) return false;
        GameplayLog.limited(resident, "npc-care", "bin_skip", 600,
                "action=bin_skip bin={} reason=untippable", at.toShortString());
        return true;
    }

    private static long day(ServerLevel level) { return Math.floorDiv(level.getDayTime(), HumanStreet.DAY); }

    public static boolean accepts(ServerLevel level, BlockPos at, ItemStack portion) {
        return slot(level, at, portion) >= 0;
    }

    public static boolean deposit(ServerLevel level, BlockPos at, ItemStack portion) {
        int index = slot(level, at, portion);
        if (index < 0) return false;
        var bin = (GarbageBinBlockEntity) level.getBlockEntity(at);
        bin.setItem(index, portion.copyWithCount(1));
        if (portion.is(ModItems.GRAIN_REGULAR.get())) bin.deposited(day(level));
        return true;
    }

    /** A standing, tippable bin needs a free slot and holds at most one of each item. Food alone uses the daily stamp. */
    private static int slot(ServerLevel level, BlockPos at, ItemStack portion) {
        if (!level.hasChunkAt(at) || !(level.getBlockEntity(at) instanceof GarbageBinBlockEntity bin) || bin.tipped()
                || portion.is(ModItems.GRAIN_REGULAR.get()) && bin.lastDeposit() == day(level)
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
