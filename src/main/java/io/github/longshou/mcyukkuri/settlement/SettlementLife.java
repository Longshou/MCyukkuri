package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Membership is independent of kinship, personality and accessory recognition. */
public final class SettlementLife {
    private final YukkuriEntity entity;
    private final SettlementSociety society;
    private final SettlementDuties duties;
    private UUID groupId;
    private GlobalPos origin;
    private boolean pendingNatural;
    private BlockPos target;
    private int wait, elapsed;
    public SettlementLife(YukkuriEntity entity) {
        this.entity = entity; society = new SettlementSociety(entity); duties = new SettlementDuties(entity);
    }
    public SettlementSociety society() { return society; }
    public SettlementDuties duties() { return duties; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public UUID groupId() { return groupId; }
    public SettlementData.Group group() {
        return entity.level().isClientSide || origin == null || !origin.dimension().equals(entity.level().dimension())
                ? null : SettlementData.get(level()).group(groupId);
    }
    public void join(SettlementData.Group group) {
        groupId = group.id; origin = GlobalPos.of(entity.level().dimension(), group.center);
        pendingNatural = false; entity.setPersistenceRequired();
        if (!entity.level().isClientSide) SettlementData.get(level()).member(group, entity.getUUID());
    }
    public boolean inherit(YukkuriEntity parent) {
        var group = parent.settlement().group();
        if (group == null) return false;
        join(group); return true;
    }
    public void naturalSpawn() { pendingNatural = true; }
    public String kind() { var group = group(); return group == null ? (groupId == null ? "wanderer" : "away") : group.kind.name().toLowerCase(java.util.Locale.ROOT); }
    public boolean active() { return target != null; }
    public String activity() { return "settlement_return"; }
    public void tick() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (wait > 0) wait--;
        if (pendingNatural) {
            pendingNatural = false;
            SettlementSpawner.discover(entity);
        }
        var group = group();
        if (group != null && entity.tickCount % 100 == 0) SettlementData.get(level()).refreshLeader(level(), group);
        society.tick(); duties.clock();
    }
    public boolean canUse() {
        if (society.role() == SettlementData.Role.OUTCAST || entity.isPanicking() || entity.isCommandPlaying() || entity.life().hasUrgentNeed()
                || entity.life().mealTicks() > 0 || entity.life().isSleeping() || entity.isInWaterOrBubble()) return false;
        if (active()) return true;
        if (wait > 0) return false;
        wait = 80;
        var group = group();
        if (group == null || !level().hasChunkAt(group.center)) return false;
        double distance = entity.distanceToSqr(Vec3.atBottomCenterOf(group.center));
        // Transporting a member never causes cross-world or long-distance forced navigation.
        if (distance > 48 * 48) return false;
        int radius = group.kind == SettlementData.Kind.FAMILY ? 7 : 14;
        UUID leaderId = SettlementData.get(level()).activeLeader(group);
        boolean gathering = SettlementSociety.accepted(entity) && level().getDayTime() % 24000 >= 11000 && level().getDayTime() % 24000 < 12500;
        if (distance <= radius * radius && !(gathering && leaderId != null)) return false;
        BlockPos destination = entity.life().homeStand();
        if (gathering && leaderId != null && !leaderId.equals(entity.getUUID())) {
            var leader = level().getEntity(leaderId);
            if (!(leader instanceof YukkuriEntity y) || !y.isAlive() || y.isPanicking() || !entity.life().canRecognize(y)
                    || !entity.hasLineOfSight(y) || !SettlementSociety.sameGroup(entity, y)) return false;
            destination = y.blockPosition();
        }
        if (destination == null || entity.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 4) return false;
        if (!navigateToward(destination)) return false;
        target = destination; elapsed = 0; return true;
    }
    private boolean navigateToward(BlockPos destination) {
        Vec3 goal = Vec3.atBottomCenterOf(destination);
        if (entity.distanceToSqr(goal) <= 16 * 16 && entity.life().navigate(destination, 1, false)) return true;
        // Vanilla's local path search has a 16-block follow range. Walk bounded segments home.
        Vec3 delta = goal.subtract(entity.position());
        BlockPos waypoint = BlockPos.containing(entity.position().add(delta.normalize().scale(Math.min(8, delta.length()))));
        var candidates = new java.util.ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(waypoint.offset(-2,-2,-2), waypoint.offset(2,2,2))) {
            if (entity.life().dryStand(pos) && pos.distSqr(destination) < entity.blockPosition().distSqr(destination)) candidates.add(pos.immutable());
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(destination)));
        for (BlockPos pos : candidates.stream().limit(12).toList()) if (entity.life().navigate(pos, 1, false)) return true;
        return false;
    }
    public boolean canContinue() {
        return active() && society.role() != SettlementData.Role.OUTCAST && elapsed < 400 && !entity.isPanicking()
                && !entity.isCommandPlaying() && !entity.life().hasUrgentNeed() && entity.life().mealTicks() == 0
                && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 48 * 48
                && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > 2.25;
    }
    public void tickGoal() {
        elapsed++;
        if (target != null && (elapsed % 20 == 1 || entity.getNavigation().isDone()) && !navigateToward(target)) stop();
    }
    public void stop() { target = null; wait = 100; entity.getNavigation().stop(); }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (groupId != null) tag.putUUID("Group", groupId);
        FamilyData.putPosition(tag, "Origin", origin); tag.putBoolean("PendingNatural", pendingNatural);
        tag.put("Duties", duties.save());
        return tag;
    }
    public void load(CompoundTag tag) {
        groupId = tag.hasUUID("Group") ? tag.getUUID("Group") : null;
        origin = FamilyData.position(tag, "Origin");
        if (origin == null) groupId = null;
        pendingNatural = tag.getBoolean("PendingNatural") && groupId == null;
        target = null; wait = elapsed = 0;
        duties.load(tag.getCompound("Duties"));
    }
}
