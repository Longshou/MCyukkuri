package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
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
    private long greeted = -1, arrivedFor = -1;
    /** Walking to a migration site lifts the ordinary 48-block return leash. */
    private boolean marching;
    /** The move (its start time) this member has already heard out at the gathering, and packed its mouthful for. */
    private long discussedFor = -1, packedFor = -1;

    public SettlementLife(YukkuriEntity entity) {
        this.entity = entity;
        wait = entity.getRandom().nextInt(80);
        society = new SettlementSociety(entity);
        duties = new SettlementDuties(entity);
    }

    public SettlementSociety society() { return society; }
    public SettlementDuties duties() { return duties; }
    public SettlementData.Role role() {
        var group = group();
        return group == null ? SettlementData.Role.FREE : SettlementData.get(level()).role(group, entity.getUUID());
    }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public UUID groupId() { return groupId; }
    public SettlementData.Group group() {
        return entity.level().isClientSide || origin == null || !origin.dimension().equals(entity.level().dimension())
                ? null : SettlementData.get(level()).group(groupId);
    }

    public void join(SettlementData.Group group) {
        var previous = group();
        if (!entity.level().isClientSide && previous != null && !previous.id.equals(group.id)) {
            SettlementData.get(level()).leave(previous, entity.getUUID(), level().getGameTime());
            stop();
            duties.cancelAssignment();
        }
        groupId = group.id;
        origin = GlobalPos.of(entity.level().dimension(), group.center);
        pendingNatural = false;
        entity.setPersistenceRequired();
        if (!entity.level().isClientSide) SettlementData.get(level()).member(group, entity.getUUID());
        LoadedGroupMembers.changed(entity);
    }

    public boolean inherit(YukkuriEntity parent) {
        var group = parent.settlement().group();
        if (group == null) return false;
        join(group);
        return true;
    }

    /** Called only after a completed shelter has accepted this same resident. */
    public void establishHousehold(ShelterBlockEntity home) {
        if (!home.owns(entity.getUUID()) || !home.usable(entity)) {
            throw new IllegalStateException("New household requires an occupied usable shelter");
        }
        var data = SettlementData.get(level());
        stop();
        duties.stop();
        var household = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FAMILY, home.getBlockPos(), null);
        data.add(household);
        join(household);
    }

    public void naturalSpawn() { pendingNatural = true; }
    public String kind() {
        var group = group();
        return group == null ? (groupId == null ? "wanderer" : "away") : group.kind.name().toLowerCase(java.util.Locale.ROOT);
    }
    public boolean active() { return target != null; }
    public String activity() { return Behaviors.SETTLEMENT_RETURN; }
    /** The community has selected a destination, including members still at the old settlement. */
    public boolean migrating() { return Migration.moving(group()); }
    /** This member is a participating migrant still travelling to the selected destination. */
    public boolean underway() { return Migration.underway(entity); }

    public void tick() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (wait > 0) wait--;
        if (pendingNatural) {
            pendingNatural = false;
            SettlementSpawner.discover(entity);
        }
        society.tick();
        duties.clock();
        var group = group();
        if (group == null) return;
        var data = SettlementData.get(level());
        if (entity.tickCount % 100 == 0) {
            data.refreshLeader(level(), group);
            // Seasonal gatherings and migration yield to an ongoing winter stay or household outing.
            if (!entity.wintering().active() && !entity.independence().householdOuting()) {
                if (!Migration.moving(group)) seasonal(group);
                Migration.tick(entity, group);
            }
            commons(data, group);
            // On the march a hungry migrant eats from the mouthful it carries rather than drop it.
            if (Migration.underway(entity) && entity.life().nutrition() < 30) entity.habitat().eatCarried();
        }
        LeaderSuccession.tick(level(), data, group);
    }

    /** Package 12: the leader judges, shares out and calls hunts; every accepted member watches over the territory. */
    private void commons(SettlementData data, SettlementData.Group group) {
        if (!CommunityRules.ruled(group)) return;
        if (entity.getUUID().equals(data.activeLeader(group)) && entity.isAlive()) {
            CommunityRules.tickLeader(entity, group);
            Rations.tickLeader(entity, group);
            CommonHunt.tickLeader(entity, group);
        }
        // Patrol every other sample, staggered by identity, to keep the area queries cheap.
        if (((entity.tickCount / 100) + (entity.getUUID().hashCode() & 1)) % 2 == 0) CommunityRules.patrol(entity, group);
    }

    /** Expulsion by the community rules: no longer a member (the family ledger and kinship stay). */
    public void exile() {
        var group = group();
        if (!entity.level().isClientSide && group != null) SettlementData.get(level()).leave(group, entity.getUUID(), level().getGameTime());
        stop();
        duties.cancelAssignment();
        groupId = null;
        origin = null;
        pendingNatural = false;
        LoadedGroupMembers.changed(entity);
    }

    /** Winter: the first fair daytime after snowfall; spring: each morning. Either opens a short gathering at the centre. */
    private void seasonal(SettlementData.Group group) {
        if (!Seasons.enabled()) return;
        var season = SettlementSeasons.of(level(), group);
        long time = level().getDayTime() % 24000, now = level().getGameTime();
        if (season == Season.WINTER) {
            if (level().isRaining()) group.snowing = true;
            else if (group.snowing && level().isDay()) {
                group.snowing = false;
                group.gatherUntil = now + YukkuriConfig.ASSEMBLY_TICKS.get();
            }
        } else if (season == Season.SPRING && time < 1500 && group.gatherDay != level().getDayTime() / 24000) {
            group.gatherDay = level().getDayTime() / 24000;
            group.gatherUntil = now + YukkuriConfig.ASSEMBLY_TICKS.get();
        }
    }

    public boolean canUse() {
        if (staysInFamilyNest() && !Migration.underway(entity)) return false;
        if (entity.independence().householdOuting() || entity.wintering().active() || society.role() == SettlementData.Role.OUTCAST
                || entity.isPanicking() || entity.isCommandPlaying() || entity.life().hasUrgentNeed()
                || entity.life().mealTicks() > 0 || entity.life().isSleeping() || entity.isInWaterOrBubble()) return false;
        if (active()) return true;
        if (wait > 0) return false;
        wait = 80;
        var group = group();
        if (Migration.moving(group) && !SettlementSeasons.confined(entity)) return migrate(group);
        if (group == null || !level().hasChunkAt(group.center)) return false;
        double distance = entity.distanceToSqr(Vec3.atBottomCenterOf(group.center));
        if (straggler(group) && !SettlementSeasons.confined(entity)) return regroup(group, distance);
        // Transporting a member never causes cross-world or long-distance forced navigation.
        if (distance > 48 * 48 || SettlementSeasons.confined(entity)) return false;
        if (SettlementSeasons.assembling(level(), group)) {
            if (distance <= 6 * 6) {
                if (greeted != group.gatherUntil) {
                    greeted = group.gatherUntil;
                    entity.showReaction("happy", "hello", 60);
                }
                return false;
            }
            if (!navigateToward(group.center)) return false;
            target = group.center;
            elapsed = 0;
            return true;
        }
        UUID leaderId = SettlementData.get(level()).activeLeader(group);
        int radius = group.kind == SettlementData.Kind.FAMILY ? 7 : 14;
        if (SettlementSeasons.is(entity, Season.WINTER)) radius /= 2;
        boolean gathering = SettlementSociety.accepted(entity) && level().getDayTime() % 24000 >= 11000 && level().getDayTime() % 24000 < 12500;
        if (distance <= radius * radius && !(gathering && leaderId != null)) return false;
        BlockPos destination = entity.life().homeStand();
        // On a planned site the evening gathering is at the speaker's stump on the plaza, leader included.
        boolean plaza = gathering && leaderId != null && SiteLife.of(level(), group) != null;
        if (plaza) destination = group.center;
        else if (gathering && leaderId != null && !leaderId.equals(entity.getUUID())) {
            var leader = level().getEntity(leaderId);
            if (!(leader instanceof YukkuriEntity y) || !y.isAlive() || y.isPanicking() || !entity.life().canRecognize(y)
                    || !entity.hasLineOfSight(y) || !SettlementSociety.sameGroup(entity, y)) return false;
            destination = y.blockPosition();
        }
        if (destination == null || entity.distanceToSqr(Vec3.atBottomCenterOf(destination)) < 4) return false;
        if (!navigateToward(destination)) return false;
        target = destination;
        elapsed = 0;
        return true;
    }

    /**
     * Migration: everyone gathers at the old centre to hear the leader, packs a mouthful from its own store, then the
     * leader walks ahead, adult members follow, and arrivals give up the old burrow.
     */
    private boolean migrate(SettlementData.Group group) {
        BlockPos site = group.migrateTo;
        if (!Migration.joins(entity, group) || !level().hasChunkAt(site)) return false;
        long now = level().getGameTime();
        var phase = Migration.phase(group, now);
        if (phase == Migration.Phase.DISCUSS) {
            if (!level().hasChunkAt(group.center)) return false;
            double d = entity.distanceToSqr(Vec3.atBottomCenterOf(group.center));
            if (d <= 6 * 6) {
                if (discussedFor != group.migrateSince) { discussedFor = group.migrateSince; Migration.discuss(entity, group); }
                return false;
            }
            wait = 40;
            if (d > 48 * 48 || !navigateToward(group.center)) return false;
            target = group.center;
            elapsed = 0;
            return true;
        }
        if (phase == Migration.Phase.PREPARE) {
            // Only the grown-ups carry food; children wait by the gathering place.
            if (packedFor == group.migrateSince || !entity.family().adult()) return false;
            var home = entity.habitat().shelter();
            var pile = home == null ? null : home.pile();
            if (pile == null || pile.food() == 0 || !entity.habitat().carrying().isEmpty()) { packedFor = group.migrateSince; return false; }
            BlockPos stand = pile.standCell();
            // Close enough to put the mouth to the store (inside the burrow or at its feeding side).
            if (entity.distanceToSqr(Vec3.atCenterOf(pile.getBlockPos())) <= 3.5 * 3.5 || home.contains(entity.blockPosition()) && entity.distanceToSqr(Vec3.atCenterOf(pile.getBlockPos())) <= 6 * 6) {
                packedFor = group.migrateSince;
                int packed = entity.habitat().packForMove(pile, Rations.FETCH_MAX);
                if (packed > 0) {
                    entity.showReaction("teary", "migrate_pack", 60);
                    io.github.longshou.mcyukkuri.status.StatusEvents.note(entity, "migrate packed " + packed);
                }
                return false;
            }
            wait = 20;
            if (!navigateToward(stand)) { packedFor = group.migrateSince; return false; }
            target = stand;
            elapsed = 0;
            return true;
        }
        Vec3 goal = Vec3.atBottomCenterOf(site);
        double distance = entity.distanceToSqr(goal);
        if (distance <= 8 * 8) {
            if (arrivedFor != group.migrateSince) {
                arrivedFor = group.migrateSince;
                settleAt(site);
            }
            return false;
        }
        BlockPos destination = site;
        if (group.leader != null && !group.leader.equals(entity.getUUID())
                && level().getEntity(group.leader) instanceof YukkuriEntity chief && chief.isAlive() && group.id.equals(chief.settlement().groupId())
                && chief.distanceToSqr(goal) > distance + 36) {
            if (entity.distanceToSqr(chief) <= 36) return false;
            destination = chief.blockPosition();
        }
        wait = 40;
        if (!navigateToward(destination)) return false;
        target = destination;
        elapsed = 0;
        marching = true;
        return true;
    }

    /** The centre moved after this member last settled: it still has to follow. */
    private boolean straggler(SettlementData.Group group) {
        return origin != null && !origin.pos().equals(group.center) && Migration.joins(entity, group);
    }

    private boolean regroup(SettlementData.Group group, double distance) {
        if (distance <= 8 * 8) {
            settleAt(group.center);
            return false;
        }
        if (distance > 96 * 96 || !navigateToward(group.center)) return false;
        target = group.center;
        elapsed = 0;
        marching = true;
        return true;
    }

    private void settleAt(BlockPos site) {
        origin = GlobalPos.of(entity.level().dimension(), site);
        entity.habitat().release();
        entity.habitat().findHome();
    }

    /** A member was hurt or killed: predators feed the threat counter, every death the community's death record. */
    public void attackedBy(net.minecraft.world.damagesource.DamageSource source, boolean fatal) {
        Migration.attacked(entity, source, fatal);
        if (fatal) Migration.died(entity, source);
        CommunityRules.attacked(entity, source, fatal);
    }

    boolean navigateToward(BlockPos destination) {
        Vec3 goal = Vec3.atBottomCenterOf(destination);
        if (entity.distanceToSqr(goal) <= 16 * 16 && entity.life().navigate(destination, 1, false)) return true;
        // Vanilla's local path search has a 16-block follow range. Walk bounded segments home.
        Vec3 delta = goal.subtract(entity.position());
        BlockPos waypoint = BlockPos.containing(entity.position().add(delta.normalize().scale(Math.min(8, delta.length()))));
        var candidates = new java.util.ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(waypoint.offset(-2, -2, -2), waypoint.offset(2, 2, 2))) {
            if (entity.life().dryStand(pos) && pos.distSqr(destination) < entity.blockPosition().distSqr(destination)) candidates.add(pos.immutable());
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(destination)));
        for (BlockPos pos : candidates.stream().limit(12).toList()) if (entity.life().navigate(pos, 1, false)) return true;
        return false;
    }

    public boolean canContinue() {
        if (staysInFamilyNest() && !Migration.underway(entity)) return false;
        if (marching && !Migration.moving(group()) && (group() == null || !straggler(group()))) return false;
        return active() && !entity.independence().householdOuting() && !entity.wintering().active() && society.role() != SettlementData.Role.OUTCAST
                && elapsed < 400 && !entity.isPanicking() && !SettlementSeasons.confined(entity) && !entity.isCommandPlaying()
                && !entity.life().hasUrgentNeed() && entity.life().mealTicks() == 0
                && (marching || entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 48 * 48)
                && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > 2.25;
    }

    private boolean staysInFamilyNest() {
        return !entity.family().adult() && !entity.family().readyForOwnNest() && entity.life().homeStand() != null;
    }

    public void tickGoal() {
        elapsed++;
        if (target != null && (elapsed % 20 == 1 || entity.getNavigation().isDone()) && !navigateToward(target)) stop();
    }

    public void stop() {
        target = null;
        marching = false;
        wait = 100;
        entity.getNavigation().stop();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (groupId != null) tag.putUUID("Group", groupId);
        FamilyData.putPosition(tag, "Origin", origin);
        tag.putBoolean("PendingNatural", pendingNatural);
        tag.put("Duties", duties.save());
        return tag;
    }

    public void load(CompoundTag tag) {
        groupId = tag.hasUUID("Group") ? tag.getUUID("Group") : null;
        origin = FamilyData.position(tag, "Origin");
        if (origin == null) groupId = null;
        pendingNatural = tag.getBoolean("PendingNatural") && groupId == null;
        target = null;
        marching = false;
        wait = elapsed = 0;
        duties.load(tag.getCompound("Duties"));
        LoadedGroupMembers.changed(entity);
    }
}
