package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.EcologyMovement;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.EventHooks;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import java.util.ArrayList;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/** Low-priority, interruptible duties. Every waste portion keeps its real ItemStack and provenance. */
public final class SettlementDuties {
    private enum Task { NONE, RETREAT, PICKUP, TOILET, DUMP }
    private final YukkuriEntity entity;
    private ItemStack carrying = ItemStack.EMPTY;
    private Task task = Task.NONE;
    private ItemEntity item;
    private BlockPos destination, toilet, workCenter;
    private int elapsed, wait;
    /** A waterborne worker has accepted only a shore visit, never a remote toilet or item operation. */
    private boolean shorePending;
    private BlockPos shoreCenter;
    private long shoreDeadline;
    public SettlementDuties(YukkuriEntity entity) { this.entity = entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public ItemStack carrying() { return carrying; }
    public boolean active() { return task != Task.NONE; }
    public String activity() { return task == Task.RETREAT ? Behaviors.SETTLEMENT_WITHDRAW : Behaviors.SETTLEMENT_CLEAN; }
    public void clock() {
        if (wait > 0) wait--;
        if (entity.tickCount % 100 == 0 && !carrying.isEmpty() && assigner() == null) dropCarrying();
        if (shorePending) tickShorePending();
    }

    private boolean ready(boolean allowSupportedAfloat) {
        var brood = entity.family().brood();
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying()
                && !entity.operations().active() && (!entity.isPassenger() || allowSupportedAfloat && entity.profile().aquatic()
                        && EcologyMovement.captain(entity) && EcologyMovement.supported(entity))
                && !entity.isInWaterOrBubble() && !entity.isOnFire()
                && !(active() ? entity.life().nutrition() < 15 || entity.life().energy() < 15 : entity.life().hasUrgentNeed())
                && entity.life().nutrition() >= (active() ? 15 : 40) && entity.life().energy() >= (active() ? 15 : 35)
                && !entity.life().isSleeping() && entity.life().mealTicks() == 0 && !entity.family().active()
                && (brood == null || brood.cancelled || brood.complete()) && !entity.dailyLife().active()
                && !entity.groupInteraction().active() && !entity.playLife().active()
                && !entity.independence().householdOuting() && !entity.wintering().active()
                && !SettlementEvents.reserved(entity);
    }
    private boolean ready() { return ready(false) && entity.onGround(); }
    private YukkuriEntity assigner() {
        if (!entity.family().adult() && !entity.family().readyForOwnNest()) return null;
        if (entity.settlement().society().role() != SettlementData.Role.UNUN_SLAVE) return null;
        var record = entity.settlement().society().record();
        if (record == null || record.assignedBy == null || record.assignedUntil <= level().getGameTime()) return null;
        var candidate = level().getEntity(record.assignedBy);
        return candidate instanceof YukkuriEntity member && member.isAlive() && !member.isNoAi()
                && SettlementSociety.sameGroup(entity, member) && SettlementSociety.accepted(member)
                && entity.distanceToSqr(member) <= 32 * 32 ? member : null;
    }
    private boolean local(BlockPos pos) {
        var group = entity.settlement().group();
        return group != null && level().hasChunkAt(pos) && pos.distSqr(group.center) <= 32 * 32;
    }
    private boolean workArea(BlockPos pos) {
        return workCenter != null && local(pos) && pos.distSqr(workCenter) <= 6 * 6;
    }
    /** Land households work around their assigner's real shelter; afloat groups work around the public bank pile. */
    private BlockPos workCenter(YukkuriEntity assigner) {
        var home = assigner == null ? null : assigner.life().homeStand();
        if (home != null) return home;
        var group = entity.settlement().group();
        return group != null && group.waterCommons != null ? group.waterCommons : null;
    }

    /** There must be a real cleanup source on the bank before an aquatic captain requests a landing. */
    private boolean shoreWorkAvailable(BlockPos center) {
        if (center == null || !local(center)) return false;
        if (!carrying.isEmpty()) return true;
        for (var waste : level().getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(6, 2, 6), candidate -> candidate.isAlive()
                && candidate.getItem().is(ModItems.WASTE.get()) && !candidate.hasPickUpDelay()
                && candidate.getPersistentData().getLong("YukkuriClearedUntil") <= level().getGameTime()))
            if (waste.blockPosition().distSqr(center) <= 6 * 6) return true;
        for (var pos : BlockPos.betweenClosed(center.offset(-6, -2, -6), center.offset(6, 2, 6)))
            if (local(pos) && pos.distSqr(center) <= 6 * 6 && level().getBlockEntity(pos) instanceof CareBlockEntity care
                    && care.kind() == CareBlock.Kind.TOILET && care.waste() > 0) return true;
        return false;
    }

    /** Reserve a normal boat landing only when this captain can then walk to a real cleanup source. */
    private boolean requestShoreDuty() {
        var assigner = assigner();
        var center = workCenter(assigner);
        var boat = EcologyMovement.boat(entity);
        if (assigner == null || center == null || boat == null || !EcologyMovement.captain(entity)
                || !EcologyMovement.supported(entity) || boat.nearestBank() == null
                || boat.nearestBank().distSqr(center) > 48 * 48 || !shoreWorkAvailable(center)
                || !EventHooks.canEntityGrief(level(), entity)
                || !entity.aquaticLife().requestShoreWork(ShoreWork.SETTLEMENT)) return false;
        shorePending = true;
        shoreCenter = center.immutable();
        shoreDeadline = level().getGameTime() + 1200;
        GameplayLog.event(entity, "settlement", "shore duty requested assigner={} center={} deadline={}",
                assigner.getUUID(), center, shoreDeadline);
        return true;
    }

    /** Pending means only that a physical landing has been requested; the work itself is selected after the ground recheck. */
    private void tickShorePending() {
        long now = level().getGameTime();
        var assigner = assigner();
        if (!ready(true) || assigner == null || now >= shoreDeadline || shoreCenter == null || !shoreCenter.equals(workCenter(assigner))
                || entity.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(shoreCenter)) > 48 * 48) {
            cancelShorePending("timeout_or_state");
            return;
        }
        if (entity.isPassenger()) {
            if (!entity.aquaticLife().shoreVisitRequested()) { cancelShorePending("landing_unavailable"); return; }
            entity.aquaticLife().holdShoreWork(ShoreWork.SETTLEMENT);
            return;
        }
        if (!entity.onGround() || entity.aquaticLife().shoreVisitRequested()) return;
        shorePending = false;
        shoreCenter = null;
        // Keep the boat grounded until the ordinary duty goal can choose a reachable source on this actual bank.
        entity.aquaticLife().holdShoreWork(ShoreWork.SETTLEMENT);
        GameplayLog.event(entity, "settlement", "shore duty landed");
    }

    private void cancelShorePending(String reason) {
        boolean pending = shorePending;
        if (pending) GameplayLog.event(entity, "settlement", "shore duty cancelled reason={}", reason);
        shorePending = false;
        shoreCenter = null;
        shoreDeadline = 0;
        entity.aquaticLife().releaseShoreWork(ShoreWork.SETTLEMENT);
        if (pending) wait = Math.max(wait, 100);
    }
    public boolean canUse() {
        if (shorePending || wait > 0) return false;
        if (entity.isPassenger()) {
            if (!ready(true)) return false;
            requestShoreDuty();
            return false;
        }
        if (!ready()) return false;
        wait = 80;
        var record = entity.settlement().society().record();
        if (record == null) return false;
        if (record.role == SettlementData.Role.OUTCAST) {
            var source = record.observer == null ? null : level().getEntity(record.observer);
            if (!(source instanceof YukkuriEntity observer) || !observer.isAlive() || !SettlementSociety.rejects(observer, entity)
                    || !observer.hasLineOfSight(entity) || entity.distanceToSqr(observer) > 36) return false;
            Vec3 away = DefaultRandomPos.getPosAway(entity, 10, 2, observer.position());
            if (away == null || !local(BlockPos.containing(away)) || !entity.life().dryStand(BlockPos.containing(away))
                    || !entity.life().reachable(BlockPos.containing(away), false)) return false;
            destination = BlockPos.containing(away); task = Task.RETREAT; elapsed = 0; return true;
        }
        var assigner = assigner();
        if (assigner == null || !EventHooks.canEntityGrief(level(), entity) || !entity.habitat().carrying().isEmpty()) return false;
        workCenter = workCenter(assigner);
        if (workCenter == null || !local(workCenter)) return false;
        destination = dumpSite();
        if (destination == null) return false;
        if (!carrying.isEmpty()) { task = Task.DUMP; elapsed = 0; return true; }
        item = level().getEntitiesOfClass(ItemEntity.class, entity.getBoundingBox().inflate(8), waste -> waste.isAlive()
                && waste.getItem().is(ModItems.WASTE.get()) && !waste.hasPickUpDelay() && workArea(waste.blockPosition())
                && waste.getPersistentData().getLong("YukkuriClearedUntil") <= level().getGameTime() && entity.hasLineOfSight(waste))
                .stream().sorted(Comparator.comparingDouble(entity::distanceToSqr))
                .filter(waste -> entity.life().dryStand(waste.blockPosition()) && entity.life().reachable(waste.blockPosition(), false))
                .findFirst().orElse(null);
        if (item != null) { task = Task.PICKUP; elapsed = 0; return true; }
        for (var pos : BlockPos.betweenClosed(workCenter.offset(-6,-2,-6), workCenter.offset(6,2,6))) {
            if (!workArea(pos) || entity.distanceToSqr(Vec3.atCenterOf(pos)) > 12 * 12) continue;
            if (level().getBlockEntity(pos) instanceof CareBlockEntity care && care.kind() == CareBlock.Kind.TOILET && care.waste() > 0) {
                var stand = standBeside(pos);
                if (stand != null) { toilet = pos.immutable(); task = Task.TOILET; elapsed = 0; return true; }
            }
        }
        return false;
    }
    private BlockPos dumpSite() {
        // Outside the six-block cleaning area; bounded local dry paths only, never a teleport.
        // An independent resident may live here while currently roaming outside the work radius.
        // A planned site has one latrine ground at its downwind edge: everything is carried there.
        var site = SiteLife.of(level(), entity.settlement().group());
        if (site != null) for (var slot : site.slots(SiteSlot.Kind.LATRINE)) {
            var front = slot.anchor().relative(slot.facing());
            if (local(front) && entity.life().dryStand(front) && entity.life().reachable(front, false)) return front;
        }
        var homes = new ArrayList<BlockPos>();
        for (var loaded : level().getAllEntities()) if (loaded instanceof YukkuriEntity resident && resident.isAlive()) {
            var home = resident.life().homeStand();
            if (home != null) homes.add(home);
        }
        for (var direction : Direction.Plane.HORIZONTAL) {
            BlockPos base = workCenter.relative(direction, 9);
            for (int rise = -2; rise <= 2; rise++) {
                var pos = base.above(rise);
                if (local(pos) && homes.stream().noneMatch(home -> home.distSqr(pos) < 36)
                        && entity.life().dryStand(pos) && entity.life().reachable(pos, false)) return pos;
            }
        }
        return null;
    }
    private BlockPos standBeside(BlockPos pos) {
        for (var direction : Direction.Plane.HORIZONTAL) {
            var stand = pos.relative(direction);
            if (local(stand) && entity.life().dryStand(stand) && entity.life().reachable(stand, false)) return stand;
        }
        return null;
    }
    public boolean canContinue() {
        if (!active() || !ready() || elapsed >= 400 || destination == null || !local(destination)) return false;
        if (task == Task.RETREAT) {
            var record = entity.settlement().society().record();
            var source = record == null || record.observer == null ? null : level().getEntity(record.observer);
            return entity.settlement().society().role() == SettlementData.Role.OUTCAST
                    && source instanceof YukkuriEntity observer && observer.isAlive() && SettlementSociety.rejects(observer, entity)
                    && observer.hasLineOfSight(entity) && entity.distanceToSqr(observer) < 12 * 12;
        }
        return assigner() != null && EventHooks.canEntityGrief(level(), entity);
    }
    public void tickGoal() {
        if (!canContinue()) { stop(); return; }
        entity.aquaticLife().holdShoreWork(ShoreWork.SETTLEMENT);
        elapsed++;
        if (task == Task.RETREAT) { if (approach(destination)) stop(); return; }
        if (task == Task.PICKUP) {
            if (item == null || !item.isAlive() || item.hasPickUpDelay() || !item.getItem().is(ModItems.WASTE.get())
                    || !workArea(item.blockPosition()) || !entity.hasLineOfSight(item)) { stop(); return; }
            if (approach(item.blockPosition()) && entity.distanceToSqr(item) < 2.25) {
                carrying = item.getItem().copyWithCount(1);
                var remaining = item.getItem().copy(); remaining.shrink(1);
                if (remaining.isEmpty()) item.discard(); else item.setItem(remaining);
                item = null; task = Task.DUMP; elapsed = 0;
            }
        } else if (task == Task.TOILET) {
            if (toilet == null || !workArea(toilet) || !(level().getBlockEntity(toilet) instanceof CareBlockEntity care)
                    || care.kind() != CareBlock.Kind.TOILET || care.waste() == 0) { stop(); return; }
            var stand = standBeside(toilet);
            if (stand == null) { stop(); return; }
            if (approach(stand) && entity.distanceToSqr(Vec3.atCenterOf(toilet)) < 2.25 && clearContact(toilet)) {
                carrying = care.takeWaste(); task = carrying.isEmpty() ? Task.NONE : Task.DUMP; elapsed = 0;
            }
        } else if (task == Task.DUMP && approach(destination)) {
            if (dropCarrying()) { entity.life().personality().decided("settlement_cleaned"); stop(); }
        }
    }
    private boolean clearContact(BlockPos pos) {
        var hit = level().clip(new ClipContext(entity.getEyePosition(), Vec3.atCenterOf(pos),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }
    private boolean approach(BlockPos pos) {
        if (!local(pos) || !entity.life().dryStand(pos)) { stop(); return false; }
        if (entity.distanceToSqr(Vec3.atBottomCenterOf(pos)) < .64) { entity.getNavigation().stop(); return true; }
        if (elapsed % 20 == 1 && !entity.life().navigate(pos, 1, false)) stop();
        return false;
    }
    public boolean dropCarrying() {
        if (carrying.isEmpty()) return true;
        var drop = new ItemEntity(level(), entity.getX(), entity.getY(), entity.getZ(), carrying.copy());
        if (!level().addFreshEntity(drop)) return false;
        drop.setPickUpDelay(100); drop.getPersistentData().putLong("YukkuriClearedUntil", level().getGameTime() + 1200);
        carrying = ItemStack.EMPTY; return true;
    }
    public void cancelAssignment() { if (active()) stop(); else cancelShorePending("assignment_cancelled"); dropCarrying(); }
    public void stop() {
        task = Task.NONE; item = null; toilet = destination = workCenter = null; elapsed = 0; wait = 80;
        cancelShorePending("stopped");
        entity.aquaticLife().releaseShoreWork(ShoreWork.SETTLEMENT);
        entity.getNavigation().stop();
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        if (!carrying.isEmpty()) tag.put("Carrying", carrying.save(entity.registryAccess()));
        return tag;
    }
    public void load(CompoundTag tag) {
        carrying = tag.contains("Carrying") ? ItemStack.parseOptional(entity.registryAccess(), tag.getCompound("Carrying")) : ItemStack.EMPTY;
        task = Task.NONE; item = null; toilet = destination = workCenter = shoreCenter = null; elapsed = 0; wait = 80;
        shorePending = false; shoreDeadline = 0;
    }
}
