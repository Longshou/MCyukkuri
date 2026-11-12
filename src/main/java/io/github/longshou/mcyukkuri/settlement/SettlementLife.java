package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import static io.github.longshou.mcyukkuri.world.SearchBudget.generation;

import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.status.StatusEvents;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.world.damagesource.DamageSource;

/** Membership is independent of kinship, personality and accessory recognition. */
public final class SettlementLife {
    static final int FAMILY_HOME_RANGE = 7, COLONY_HOME_RANGE = 14;
    private final YukkuriEntity entity;
    private final SettlementSociety society;
    private final SettlementDuties duties;
    private UUID groupId;
    private GlobalPos origin;
    private long arrivedFor = -1;
    private BlockPos arrivalHomeSearch;
    private boolean arrivalHomeStarted;
    private boolean pendingNatural;
    private BlockPos target;
    private int wait, elapsed, navigationRetry;
    private BlockPos searchDestination;
    private long navigationGeneration;
    private long greeted = -1;
    /** Walking to a migration site lifts the ordinary 48-block return leash. */
    private boolean marching;
    private Path marchRoute;
    private BlockPos marchStart;
    private boolean marchDetour;
    private record MarchRefusal(BlockPos from, BlockPos destination, long legAt, boolean detour, String reason) {}
    private MarchRefusal marchRefusal;
    /** The ordinary settlement Goal is attending a selected local succession meeting. */
    private boolean successionMeeting;
    /** The centre from which this member actually began the current march; retained until it has a settled outcome. */
    private GlobalPos migrationDeparture;
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
    private ServerLevel membershipLevel() {
        return entity.level() instanceof ServerLevel level && origin != null
                ? level.getServer().getLevel(origin.dimension()) : null;
    }
    public boolean settlementMember() {
        var group = recordedGroup();
        if (group == null || group.kind == SettlementData.Kind.FAMILY) return false;
        var role = group.role(entity.getUUID());
        return role != SettlementData.Role.FREE && role != SettlementData.Role.OUTCAST;
    }
    public SettlementData.Group recordedGroup() {
        var homeLevel = membershipLevel();
        return homeLevel == null ? null : SettlementData.get(homeLevel).group(groupId);
    }
    public SettlementData.Group group() {
        return entity.level().isClientSide || origin == null || !origin.dimension().equals(entity.level().dimension())
                ? null : SettlementData.get(level()).group(groupId);
    }

    public void join(SettlementData.Group group) {
        if (leaveRecordedGroup(group)) {
            stop();
            duties.cancelAssignment();
        }
        groupId = group.id;
        origin = GlobalPos.of(entity.level().dimension(), group.center);
        arrivedFor = -1;
        arrivalHomeSearch = null;
        migrationDeparture = null;
        pendingNatural = false;
        entity.setPersistenceRequired();
        if (!entity.level().isClientSide) SettlementData.get(level()).member(group, entity.getUUID());
        LoadedGroupMembers.changed(entity);
    }

    public boolean inherit(YukkuriEntity parent) {
        if (!parent.status().identity().wild()) return false;
        var group = parent.settlement().group();
        if (group == null) return false;
        join(group);
        if (group.kind != SettlementData.Kind.FAMILY) entity.pet().leaveTownLife();
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
        var household = new SettlementData.Group(ModRandom.identity(entity.getRandom()), SettlementData.Kind.FAMILY, home.getBlockPos(), null);
        data.add(household);
        join(household);
    }

    public void naturalSpawn() { pendingNatural = true; }
    public String kind() {
        var group = group();
        return group == null ? (groupId == null ? "wanderer" : "away") : group.kind.name().toLowerCase(Locale.ROOT);
    }
    public boolean active() { return target != null; }
    public String activity() { return Behaviors.SETTLEMENT_RETURN; }
    /** Read-only migration diagnosis for the status command; the destination is committed only after {@code FOUND}. */
    public String settlementDetail() {
        var group = group();
        String phase = group == null ? "none" : Migration.phase(group, entity.level().getGameTime()).name().toLowerCase(Locale.ROOT);
        var member = group == null ? null : SettlementData.get(level()).existingMember(group, entity.getUUID());
        return "origin=" + position(origin) + " phase=" + phase + " reason=" + (group == null ? "none" : Migration.reason(group))
                + " vanguard=" + (group != null && Migration.isVanguard(entity, group)) + " migration_care=" + entity.family().migrationCareActive()
                + " succession_meeting=" + successionMeeting
                + " leader_loss_known=" + (group != null && group.leaderDied && member != null && member.knownLeaderLoss >= group.leaderLossAt);
    }
    private static String position(GlobalPos value) {
        return value == null ? "none" : value.dimension().location() + "@" + value.pos().toShortString();
    }
    /** The community has selected a destination, including members still at the old settlement. */
    public boolean migrating() { return Migration.moving(group()); }
    /** This member is a participating migrant still travelling to the selected destination. */
    public boolean underway() { return Migration.underway(entity); }
    /**
     * Family movement may use this while a group is truly marching, including a baby which deliberately has no independent
     * MigrationGoal. A failed move keeps departed parents and dependents with a different settled address on the return path.
     */
    public boolean familyMarching() {
        var group = group();
        if (group == null) return false;
        if (Migration.moving(group)) return Migration.onJourney(group, entity.level().getGameTime()) && needsMarch(group);
        return entity.childhood().shouldStayWithParents() && !settledAt(group.center)
                || (entity.family().adult() || entity.family().stage() == FamilyLife.Stage.JUVENILE) && straggler(group);
    }

    public boolean migrationLanded() {
        var group = group();
        if (group == null) return false;
        return Migration.moving(group) ? reached(group) : settledAt(group.center) && !straggler(group);
    }

    boolean reached(SettlementData.Group group) {
        return arrivedFor == group.migrateSince && settledAt(group.migrateTo);
    }
    boolean needsMarch(SettlementData.Group group) {
        var carry = entity.family().carry();
        return !reached(group) || carry.purpose() == ParentCarry.Purpose.MIGRATE && carry.carrying()
                && !onSiteGround(entity, group.migrateTo);
    }

    /** A walking dependent can use the group's dry route while a recognised parent is part of the move. */
    public boolean canCatchUpOnFoot() {
        var group = group();
        if (group == null || !Migration.onJourney(group, level().getGameTime()) || reached(group) || entity.isPassenger()
                || !entity.childhood().shouldStayWithParents() || entity.family().stage() != FamilyLife.Stage.JUVENILE
                || ParentCarry.needsCarry(entity) || !entity.status().identity().wild()
                || group.role(entity.getUUID()) != SettlementData.Role.MEMBER) return false;
        var destination = Migration.walkTarget(group);
        if (destination == null || sameMarchGround(group, destination) && marchRefusal.detour()) return false;
        for (var id : new UUID[]{entity.family().mother(), entity.family().father()})
            if (id != null && level().getEntity(id) instanceof YukkuriEntity parent
                    && group.id.equals(parent.settlement().groupId()) && Migration.joins(parent, group)
                    && entity.life().canRecognize(parent)) return true;
        return false;
    }

    /** A parent can be on its way back from fetching a child after it first arrived. */
    public boolean atMigrationReleaseGround() {
        var group = group();
        return group != null && (Migration.moving(group)
                ? reached(group) && onSiteGround(entity, group.migrateTo) : migrationLanded());
    }

    void logMarchDelay(SettlementData.Group group) {
        GameplayLog.limited(entity, "settlement", "march.due", 600,
                "migration member still due group={} destination={} goals={} target={} refusal={} passenger={} nutrition={} energy={}",
                groupId, group.migrateTo, entity.runningGoalIds(), target,
                marchRefusal == null ? null : marchRefusal.reason(), entity.isPassenger(),
                entity.life().nutrition(), entity.life().energy());
    }
    /** Plain tiredness does not stop a marching carrier from holding its child; every other urgent need does. */
    public boolean marchTiredOnly() { return Migration.underway(entity) && tiredOnlyUrgent(); }

    /** A returning dependent may finish following a parent who has already reclaimed the real home. */
    public boolean returningToSettledParent(YukkuriEntity parent) {
        var group=group();
        if(group==null||!entity.childhood().shouldStayWithParents()||!group.id.equals(parent.settlement().groupId()))return false;
        return Migration.moving(group) ? !reached(group)&&parent.settlement().reached(group)
                : !settledAt(group.center)&&parent.settlement().settledAt(group.center);
    }

    public void tick() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (wait > 0) wait--;
        if (navigationRetry > 0) navigationRetry--;
        if (pendingNatural) {
            pendingNatural = false;
            SettlementSpawner.discover(entity);
        }
        boolean wild = entity.status().identity().wild();
        if (wild) society.tick();
        duties.clock();
        if (!wild) return;
        var group = group();
        if (group == null) return;
        SettlementTicker.submit(entity, group);
        arrive(group);
        if (arrivalHomeSearch != null && entity.life().homeStand() != null) homeEstablished();
        if (entity.tickCount % 100 == 0) {
            if (CommunityRules.ruled(group)) patrol(group);
            // On the march a hungry migrant eats from the mouthful it carries rather than drop it.
            if (Migration.underway(entity) && entity.life().nutrition() < 30) entity.habitat().eatCarried();
        }
    }

    private void patrol(SettlementData.Group group) {
        // Patrol every other sample, staggered by identity, to keep the area queries cheap.
        if (((entity.tickCount / 100) + (entity.getUUID().hashCode() & 1)) % 2 == 0) CommunityRules.patrol(entity, group);
    }

    /** Expulsion by the community rules: no longer a member (the family ledger and kinship stay). */
    public void exile() {
        var before = entity.status().identity();
        leaveRecordedGroup(null);
        stop();
        duties.cancelAssignment();
        groupId = null;
        origin = null;
        arrivedFor = -1;
        arrivalHomeSearch = null;
        migrationDeparture = null;
        pendingNatural = false;
        LoadedGroupMembers.changed(entity);
        YukkuriStatus.identityChanged(entity, before, "settlement_departure");
    }
    private boolean leaveRecordedGroup(SettlementData.Group replacement) {
        var homeLevel = membershipLevel();
        if (homeLevel == null) return false;
        var data = SettlementData.get(homeLevel);
        var previous = data.group(groupId);
        if (previous == null || previous == replacement) return false;
        data.leave(previous, entity.getUUID(), homeLevel.getGameTime());
        return true;
    }

    /** Winter: the first fair daytime after snowfall; spring: each morning. Either opens a short gathering at the centre. */
    static void seasonal(ServerLevel level, SettlementData.Group group) {
        if (!Seasons.enabled()) return;
        var season = SettlementSeasons.of(level, group);
        long time = level.getDayTime() % 24000, now = level.getGameTime();
        if (season == Season.WINTER) {
            if (level.isRaining()) group.snowing = true;
            else if (group.snowing && level.isDay()) {
                group.snowing = false;
                group.gatherUntil = now + YukkuriConfig.ASSEMBLY_TICKS.get();
            }
        } else if (season == Season.SPRING && time < 1500 && group.gatherDay != level.getDayTime() / 24000) {
            group.gatherDay = level.getDayTime() / 24000;
            group.gatherUntil = now + YukkuriConfig.ASSEMBLY_TICKS.get();
        }
    }

    public boolean canUse() {
        if (!entity.status().identity().wild()) return false;
        if (staysInFamilyNest() && !Migration.underway(entity)) return false;
        if (yieldsToMigrationCare()) return false;
        String blocked = entity.independence().householdOuting() ? "independence"
                : entity.wintering().active() ? "wintering" : society.role() == SettlementData.Role.OUTCAST ? "outcast"
                : entity.isPanicking() ? "panic" : entity.isCommandPlaying() ? "command"
                : urgentBlocks() ? "urgent_need" : entity.life().mealTicks() > 0 ? "meal"
                : entity.life().isSleeping() ? "sleep" : entity.isInWaterOrBubble() ? "water" : null;
        if (blocked != null) {
            var blockedGroup = group();
            // Observe existing group/address state only; do not rerun migration eligibility or caregiver selection for logging.
            if (blockedGroup != null && (Migration.moving(blockedGroup)
                    || origin != null && (!origin.pos().equals(blockedGroup.center) || migrationDeparture != null)))
                GameplayLog.limited(entity, "settlement", "migration.blocked." + blocked, 1200,
                        "settlement goal refused in migration context reason={} group={} origin={} destination={} marching={} nutrition={} energy={} dryness={} bladder={} carried={}",
                        blocked, groupId, origin, blockedGroup.migrateTo == null ? blockedGroup.center : blockedGroup.migrateTo,
                        marching, entity.life().nutrition(), entity.life().energy(), entity.life().dryness(),
                        entity.life().bladder(), entity.habitat().carrying().getCount());
            return false;
        }
        if (active()) return true;
        if (wait > 0) return false;
        wait = 80;
        var group = group();
        clearCompletedDeparture(group);
        var meeting = LeaderSuccession.meetingTarget(entity);
        if (meeting != null) {
            target = meeting;
            elapsed = 0;
            successionMeeting = true;
            return true;
        }
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
            if (navigateToward(group.center)!=Result.FOUND) return false;
            target = group.center;
            elapsed = 0;
            return true;
        }
        UUID leaderId = SettlementData.get(level()).activeLeader(group);
        int radius = group.kind == SettlementData.Kind.FAMILY ? FAMILY_HOME_RANGE : COLONY_HOME_RANGE;
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
        if (navigateToward(destination)!=Result.FOUND) return false;
        target = destination;
        elapsed = 0;
        return true;
    }

    /** A parent yields MOVE to the existing family feeding/search action while its dependent catches up. */
    private boolean yieldsToMigrationCare() {
        return ParentCarry.awaitsCarrier(entity) || familyMarching()
                && (entity.family().migrationCareActive() || entity.family().prepareMigrationCare());
    }

    /**
     * Plain tiredness (energy 12 to 25) does not stop a migrant that is still walking: it keeps going and rests in place at the
     * next stop. Hunger, a full bladder, wetness, water and fire still do, and below 12 it has to sleep.
     */
    private boolean tiredOnlyUrgent() {
        var life = entity.life();
        return life.energy() < 25 && life.energy() >= 12 && life.nutrition() >= 30
                && (life.bladder() < 90 || life.constipated())
                && !entity.isInWaterOrBubble() && !entity.isOnFire() && life.wetness() <= 40;
    }

    private boolean urgentBlocks() {
        return entity.life().hasUrgentNeed() && !(Migration.underway(entity) && tiredOnlyUrgent());
    }

    /** The centre moved after this member last settled: it still has to follow. */
    boolean straggler(SettlementData.Group group) {
        return group != null && origin != null && (!settledAt(group.center) || departedFrom(group)) && Migration.joins(entity, group);
    }

    boolean settledAt(BlockPos center) { return origin != null && origin.pos().equals(center); }

    private boolean departedFrom(SettlementData.Group group) {
        return migrationDeparture != null && migrationDeparture.dimension().equals(entity.level().dimension()) && migrationDeparture.pos().equals(group.center);
    }

    /** The marker belongs only to a member that successfully started a march, and keeps the bounded long-distance return route. */
    private boolean returnEligible() {
        return migrationDeparture != null && migrationDeparture.dimension().equals(entity.level().dimension());
    }

    /** A completed move is evident from the new group centre; a failed one must keep the return permission until arrival. */
    private void clearCompletedDeparture(SettlementData.Group group) {
        if (migrationDeparture != null && group != null && !Migration.moving(group) && settledAt(group.center)
                && !migrationDeparture.pos().equals(group.center)) migrationDeparture = null;
    }

    private boolean regroup(SettlementData.Group group, double distance) {
        if (distance <= 8 * 8) return false; // tick() records ground arrival.
        if (distance > 96 * 96 && !returnEligible() || !march(group, group.center)) return false;
        GameplayLog.limited(entity, "settlement", "regroup", 1200,
                "regroup route accepted group={} origin={} destination={} nutrition={} carried={}",
                groupId, origin, target, entity.life().nutrition(), entity.habitat().carrying().getCount());
        return true;
    }

    /**
     * Migration: everyone gathers at the old centre to hear the leader, packs a mouthful from its own store, then the
     * leader walks ahead, adult members follow, and arrivals give up the old burrow.
     */
    private boolean migrate(SettlementData.Group group) {
        BlockPos site = group.migrateTo;
        if ((!Migration.joins(entity, group) && !canCatchUpOnFoot()) || !level().hasChunkAt(site)) return false;
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
            if (d > 48 * 48 || navigateToward(group.center)!=Result.FOUND) return false;
            target = group.center;
            elapsed = 0;
            return true;
        }
        if (phase == Migration.Phase.PREPARE) {
            // Only the grown-ups carry food; children wait by the gathering place.
            if (packedFor == group.migrateSince || !entity.family().adult()) return false;
            if (ParentCarry.reservesMouth(entity)) {
                packedFor = group.migrateSince;
                GameplayLog.event(entity, "settlement", "packing skipped group={} migration={} reason=mouth_reserved_for_child", groupId, group.migrateSince);
                return false;
            }
            var home = entity.habitat().shelter();
            var pile = home == null ? null : home.pile();
            if (pile == null || pile.food() == 0 || !entity.habitat().carrying().isEmpty()) {
                packedFor = group.migrateSince;
                GameplayLog.event(entity, "settlement", "packing skipped group={} migration={} reason={} carried={} stock={}",
                        groupId, group.migrateSince, pile == null ? "no_store" : pile.food() == 0 ? "empty_store" : "already_carrying",
                        entity.habitat().carrying().getCount(), pile == null ? 0 : pile.food());
                return false;
            }
            BlockPos stand = pile.standCell();
            // Close enough to put the mouth to the store (inside the burrow or at its feeding side).
            if (entity.distanceToSqr(Vec3.atCenterOf(pile.getBlockPos())) <= 3.5 * 3.5 || home.contains(entity.blockPosition()) && entity.distanceToSqr(Vec3.atCenterOf(pile.getBlockPos())) <= 6 * 6) {
                packedFor = group.migrateSince;
                int packed = entity.habitat().packForMove(pile, Rations.FETCH_MAX);
                if (packed > 0) {
                    entity.showReaction("teary", "migrate_pack", 60);
                    StatusEvents.note(entity, "migrate packed " + packed);
                }
                return false;
            }
            wait = 20;
            var route=navigateToward(stand);
            if(route!=Result.FOUND){if(route==Result.NOT_FOUND)packedFor=group.migrateSince;return false;}
            target = stand;
            elapsed = 0;
            return true;
        }
        var destination = Migration.walkTarget(group);
        if (destination == null) return false;
        Vec3 goal = Vec3.atBottomCenterOf(destination);
        double distance = entity.distanceToSqr(goal);
        // A resident already assigned to this destination may walk to its new home outside the plaza radius.
        if (!needsMarch(group) && site.equals(destination)) return false;
        if (distance <= 8 * 8) return false;
        // The vanguard leaves first; the rest follow after a short head start. Nobody waits for the leader: a frail leader
        // trails behind as an ordinary straggler instead of holding the whole column.
        var scout = Migration.vanguard(level(), group);
        if (scout != null && scout != entity
                && now < group.migrateSince + Migration.DISCUSS_TICKS + Migration.PREPARE_TICKS + Migration.VANGUARD_HEAD_START
                && entity.distanceToSqr(Vec3.atBottomCenterOf(group.center)) <= 14 * 14) {
            wait = 20;
            return false;
        }
        if (!march(group, destination)) return false;
        migrationDeparture = GlobalPos.of(entity.level().dimension(), group.center);
        GameplayLog.limited(entity, "settlement", "march", 1200,
                "march route accepted group={} origin={} destination={} nutrition={} carried={}",
                groupId, origin, target, entity.life().nutrition(), entity.habitat().carrying().getCount());
        return true;
    }

    private boolean march(SettlementData.Group group, BlockPos destination) {
        wait = 20;
        if (!Migration.readyToPlan(entity)) return false;
        boolean sameGround = sameMarchGround(group, destination);
        if (sameGround && marchRefusal.detour()) return false;
        marchDetour = sameGround || group.migrateLeg == Migration.Leg.DETOUR;
        if (searchDestination == null) navigationGeneration = generation();
        searchDestination = destination;
        var lease = SearchBudget.request(level(), entity, Purpose.NAVIGATE, navigationGeneration, SearchBudget.NAVIGATE);
        if (lease == null) return false;
        try (lease; var scope = lease.bind(entity)) {
            SearchBudget.path(entity);
            marchRoute = entity.life().dryTravelPath(Migration.approaches(entity, destination, marchDetour));
            if (marchRoute == null) {
                marchBlocked(group, destination, "no_dry_route");
                return false;
            }
        }
        target = destination;
        marchStart = entity.blockPosition();
        elapsed = 0;
        marching = true;
        return true;
    }

    private boolean sameMarchGround(SettlementData.Group group, BlockPos destination) {
        return marchRefusal != null && destination.equals(marchRefusal.destination())
                && group.migrateLegAt == marchRefusal.legAt() && entity.blockPosition().equals(marchRefusal.from());
    }

    /** The previous goal may clear navigation in stop(); install the chosen path once this goal owns MOVE. */
    public void startGoal() {
        if (marching) entity.getNavigation().moveTo(marchRoute, 1);
    }

    private void marchBlocked(SettlementData.Group group, BlockPos destination, String reason) {
        marchRefusal = new MarchRefusal(entity.blockPosition(), destination, group.migrateLegAt, marchDetour, reason);
        GameplayLog.limited(entity, "settlement", "march." + reason, 1200,
                "march route blocked group={} destination={} leg={} reason={}", groupId, destination, group.migrateLeg, reason);
    }

    private void arrive(SettlementData.Group group) {
        boolean moving = Migration.moving(group);
        var site = moving ? group.migrateTo : group.center;
        if (moving ? !Migration.onJourney(group, level().getGameTime()) || !site.equals(Migration.walkTarget(group))
                || reached(group) || !Migration.joins(entity, group) && !entity.childhood().shouldStayWithParents()
                : !straggler(group) && (!entity.childhood().shouldStayWithParents() || settledAt(site))) return;
        if (entity.isPassenger() || !entity.onGround() || entity.isInWaterOrBubble()
                || !onSiteGround(entity, site) && !besideArrivedParent(group, site)) return;
        var previous = origin;
        if (!settledAt(site)) {
            entity.habitat().release();
            entity.life().releaseHome();
        }
        origin = GlobalPos.of(level().dimension(), site);
        if (moving) arrivedFor = group.migrateSince;
        else migrationDeparture = null;
        if (!entity.childhood().shouldStayWithParents()) {
            arrivalHomeSearch = site;
            arrivalHomeStarted = false;
            if (entity.life().homeStand() == null) {
                entity.habitat().seekHomeNow();
                entity.life().seekHomeNow();
            }
        }
        GameplayLog.event(entity, "settlement", "migration reached group={} origin={} destination={} dependent={}",
                groupId, previous, site, entity.childhood().shouldStayWithParents());
    }

    private boolean besideArrivedParent(SettlementData.Group group, BlockPos site) {
        if (!entity.childhood().shouldStayWithParents()) return false;
        for (var id : new UUID[]{entity.family().mother(), entity.family().father()})
            if (id != null && level().getEntity(id) instanceof YukkuriEntity parent && parent.isAlive()
                    && group.id.equals(parent.settlement().groupId()) && parent.settlement().migrationLanded()) {
            if (entity.family().sharesHome(parent) && entity.family().atHome()) return true;
            if ((onSiteGround(parent, site) || parent.onGround() && parent.family().atHome())
                    && entity.family().withinFollowDistance(parent)) return true;
        }
        return false;
    }

    private static boolean onSiteGround(YukkuriEntity member, BlockPos site) {
        return member.onGround() && !member.isInWaterOrBubble() && member.distanceToSqr(Vec3.atBottomCenterOf(site)) <= 8 * 8;
    }

    void homeSearchStarted() {
        if (arrivalHomeSearch == null || arrivalHomeStarted) return;
        arrivalHomeStarted = true;
        GameplayLog.event(entity, "settlement", "arrival home search started group={} destination={}", groupId, arrivalHomeSearch);
    }

    private void homeEstablished() {
        var shelter = entity.habitat().shelter();
        var home = shelter == null ? entity.life().homeStand() : shelter.getBlockPos();
        var partnerId = entity.family().partner();
        if (partnerId == null || !(level().getEntity(partnerId) instanceof YukkuriEntity partner)
                || partner.settlement().arrivedFor != arrivedFor || partner.settlement().arrivalHomeSearch != null
                || !partner.settlement().settledAt(arrivalHomeSearch) || partner.life().homeStand() == null)
            GameplayLog.event(entity, "settlement", "migration family housed group={} destination={} home={} family={}",
                    groupId, arrivalHomeSearch, home, partnerId != null && partnerId.compareTo(entity.getUUID()) < 0 ? partnerId : entity.getUUID());
        arrivalHomeSearch = null;
    }

    /** A member was hurt or killed: predators feed the threat counter, every death the community's death record. */
    public void attackedBy(DamageSource source, boolean fatal) {
        Migration.attacked(entity, source, fatal);
        if (fatal) Migration.died(entity, source);
        CommunityRules.attacked(entity, source, fatal);
    }

    Result navigateToward(BlockPos destination) {
        if(searchDestination==null)navigationGeneration=generation();
        searchDestination=destination.immutable();
        if(navigationRetry>0){wait=20;return Result.DEFERRED;}
        var lease=SearchBudget.request(level(),entity,Purpose.NAVIGATE,navigationGeneration,SearchBudget.NAVIGATE);
        if(lease==null){navigationRetry=20;wait=20;return Result.DEFERRED;}
        try(lease;var scope=lease.bind(entity)){
            boolean found=navigateNow(destination);
            if(!found)GameplayLog.limited(entity,"settlement","route_failed",200,"route failed group={} destination={} marching={}",groupId,destination,marching);
            return Result.of(found);
        }
    }
    private boolean navigateNow(BlockPos destination) {
        Vec3 goal = Vec3.atBottomCenterOf(destination);
        if (entity.distanceToSqr(goal) <= 16 * 16 && route(destination)) return true;
        // Vanilla's local path search has a 16-block follow range. Walk bounded segments home.
        Vec3 delta = goal.subtract(entity.position());
        BlockPos waypoint = BlockPos.containing(entity.position().add(delta.normalize().scale(Math.min(8, delta.length()))));
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(waypoint.offset(-2, -2, -2), waypoint.offset(2, 2, 2))) {
            SearchBudget.probe(level());
            if (entity.life().dryStand(pos) && pos.distSqr(destination) < entity.blockPosition().distSqr(destination)) candidates.add(pos.immutable());
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(destination)));
        for (BlockPos pos : candidates.stream().limit(12).toList()) if (route(pos)) return true;
        return false;
    }

    private boolean route(BlockPos pos){SearchBudget.path(entity);return entity.life().navigate(pos,1,false);}
    private void cancelNavigation() {
        if(entity.level() instanceof ServerLevel level)SearchBudget.cancel(level,entity,Purpose.NAVIGATE,navigationGeneration);
        searchDestination=null;navigationRetry=0;
    }
    public boolean canContinue() {
        if (!entity.status().identity().wild()) return false;
        if (successionMeeting) return target != null && target.equals(LeaderSuccession.meetingTarget(entity)) && elapsed < 400;
        if (staysInFamilyNest() && !Migration.underway(entity)) return false;
        var group = group();
        if (yieldsToMigrationCare()) return false;
        if (marching && (group == null || !(Migration.moving(group) ? target.equals(Migration.walkTarget(group)) && needsMarch(group)
                : straggler(group) && target.equals(group.center)))) return false;
        return active() && !entity.independence().householdOuting() && !entity.wintering().active() && society.role() != SettlementData.Role.OUTCAST
                && elapsed < (marching ? 1200 : 400) && !entity.isPanicking() && !SettlementSeasons.confined(entity) && !entity.isCommandPlaying()
                && !urgentBlocks() && entity.life().mealTicks() == 0
                && (marching || entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 48 * 48)
                && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) > (marching ? 8 * 8 : 2.25);
    }

    private boolean staysInFamilyNest() {
        return !entity.family().adult() && !entity.family().readyForOwnNest() && entity.life().homeStand() != null;
    }

    public void tickGoal() {
        elapsed++;
        if (marching) {
            if (entity.getNavigation().isDone() && Migration.readyToPlan(entity)) {
                var nav = entity.getNavigation();
                if (nav.isStuck() || nav.getPath() != null && marchStart.equals(entity.blockPosition()))
                    marchBlocked(group(), target, "no_progress");
                stop();
            }
            return;
        }
        if (successionMeeting && target != null && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 2.25) {
            entity.getNavigation().stop();
            return;
        }
        if(target!=null&&(elapsed%20==1||entity.getNavigation().isDone())) {
            var result=navigateToward(target);
            if(result==Result.NOT_FOUND)stop();
            else if(result==Result.DEFERRED)elapsed--; // Budget waiting is not a failed route or elapsed walking time.
        }
    }

    public void stop() {
        if (marching && target != null) GameplayLog.limited(entity, "settlement", "march.stop", 1200,
                "march stopped group={} target={} origin={} elapsed={} distanceSqr={} nutrition={} energy={} panic={} mealTicks={}",
                groupId, target, origin, elapsed, entity.distanceToSqr(Vec3.atBottomCenterOf(target)),
                entity.life().nutrition(), entity.life().energy(), entity.isPanicking(), entity.life().mealTicks());
        cancelNavigation();
        boolean wasMarching = marching;
        target = null;
        marching = false;
        marchRoute = null;
        successionMeeting = false;
        wait = wasMarching ? 20 : 100;
        entity.getNavigation().stop();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (groupId != null) tag.putUUID("Group", groupId);
        FamilyData.putPosition(tag, "Origin", origin);
        FamilyData.putPosition(tag, "MigrationDeparture", migrationDeparture);
        if (arrivedFor >= 0) tag.putLong("ArrivedFor", arrivedFor);
        tag.putBoolean("PendingNatural", pendingNatural);
        tag.put("Duties", duties.save());
        entity.moldExile().save(tag);
        return tag;
    }

    public void load(CompoundTag tag) {
        groupId = tag.hasUUID("Group") ? tag.getUUID("Group") : null;
        origin = FamilyData.position(tag, "Origin");
        arrivedFor = tag.contains("ArrivedFor") ? tag.getLong("ArrivedFor") : -1;
        arrivalHomeSearch = null;
        if (origin == null) groupId = null;
        migrationDeparture = FamilyData.position(tag, "MigrationDeparture");
        recoverLegacyDeparture();
        pendingNatural = tag.getBoolean("PendingNatural") && groupId == null;
        target = null;
        marching = false;
        successionMeeting = false;
        wait = elapsed = 0;
        duties.load(tag.getCompound("Duties"));
        entity.moldExile().load(tag);
        LoadedGroupMembers.changed(entity);
    }

    /** Old saves have no departure marker: recover only a loaded member visibly marching away from its still-active old centre. */
    private void recoverLegacyDeparture() {
        if (migrationDeparture != null || origin == null || entity.level().isClientSide) return;
        var group = group();
        if (!Migration.onJourney(group, level().getGameTime())) return;
        recoverDepartureForAbandon(group);
    }

    /** Compatibility for a loaded old-save member when the still-active MARCH is abandoned before it can reload. */
    void recoverDepartureForAbandon(SettlementData.Group group) {
        if (migrationDeparture != null || origin == null || !origin.pos().equals(group.center) || !Migration.joins(entity, group)
                || entity.distanceToSqr(Vec3.atBottomCenterOf(group.center)) <= 48 * 48) return;
        migrationDeparture = origin;
    }
}
