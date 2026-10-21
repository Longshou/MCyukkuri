package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import static io.github.longshou.mcyukkuri.world.SearchBudget.generation;

import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
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
import io.github.longshou.mcyukkuri.status.StatusEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import net.minecraft.world.damagesource.DamageSource;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.AquaticLife.ShoreWork;

/** Membership is independent of kinship, personality and accessory recognition. */
public final class SettlementLife {
    /** A failed home search may be retried after nearby residents free or found a suitable burrow. */
    private static final int SETTLEMENT_RETRY_TICKS = 100;
    private final YukkuriEntity entity;
    private final SettlementSociety society;
    private final SettlementDuties duties;
    private UUID groupId;
    private GlobalPos origin;
    /** A new centre whose one-time old-home release has happened, but whose bounded home cursor has not finished. */
    private GlobalPos settlingAt;
    private boolean pendingNatural;
    private BlockPos target;
    /** The real calm-water berth currently held by a settlement migration or regroup route. */
    private BlockPos afloatTarget;
    private int wait, elapsed, navigationRetry;
    private BlockPos searchDestination;
    private long navigationGeneration;
    private long greeted = -1, arrivedFor = -1;
    /** Last outcome of the bounded settlement search; transient like its search cursor. */
    private Result settlementResult;
    /** Walking to a migration site lifts the ordinary 48-block return leash. */
    private boolean marching;
    /** The centre from which this member actually began the current march; retained until it has a settled outcome. */
    private GlobalPos migrationDeparture;
    /** The move (its start time) this member has already heard out at the gathering, and packed its mouthful for. */
    private long discussedFor = -1, packedFor = -1;
    /** A committed water-ration errand retains its verified afloat-hat identity through one ordinary landing. */
    private Rations.WaterFetch waterRationFetch;
    private final PileContact waterRationContact;

    public SettlementLife(YukkuriEntity entity) {
        this.entity = entity;
        waterRationContact = new PileContact(entity);
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
        settlingAt = null;
        settlementResult = null;
        migrationDeparture = null;
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
        return group == null ? (groupId == null ? "wanderer" : "away") : group.kind.name().toLowerCase(Locale.ROOT);
    }
    public boolean active() { return target != null; }
    public String activity() { return Behaviors.SETTLEMENT_RETURN; }
    /** Read-only migration diagnosis for the status command; the destination is committed only after {@code FOUND}. */
    public String settlementDetail() {
        var group = group();
        String phase = group == null ? "none" : Migration.phase(group, entity.level().getGameTime()).name().toLowerCase(Locale.ROOT);
        String retry = settlementResult == Result.DEFERRED ? "budget:" + wait
                : settlementResult == Result.NOT_FOUND ? "home:" + wait : "none";
        var baby = group == null ? null : Migration.caregivingBaby(entity,group);
        return "origin=" + position(origin) + " settlingAt=" + position(settlingAt) + " result="
                + (settlementResult == null ? "none" : settlementResult.name().toLowerCase(Locale.ROOT))
                + " retry=" + retry + " phase=" + phase + " caregiving=" + (baby == null ? "none" : baby)
                + " migration_blocked=" + (group != null && Migration.leaderBabyBlocker(entity,group) != null)
                + " water_ration=" + waterRationDetail();
    }
    private static String position(GlobalPos value) {
        return value == null ? "none" : value.dimension().location() + "@" + value.pos().toShortString();
    }
    /** Read-only water-ration diagnostic: a pending task still names the original verified hat after it has landed. */
    private String waterRationDetail() {
        if (waterRationFetch == null) return "none";
        return "pending,hat=" + waterRationFetch.boatId + ",store=" + waterRationFetch.pile().getBlockPos().toShortString()
                + ",left=" + waterRationFetch.ration.left + ",target=" + (target == null ? "landing" : target.toShortString());
    }
    /** The community has selected a destination, including members still at the old settlement. */
    public boolean migrating() { return Migration.moving(group()); }
    /** This member is a participating migrant still travelling to the selected destination. */
    public boolean underway() { return Migration.underway(entity); }

    public void tick() {
        if (entity.level().isClientSide || entity.isNoAi()) return;
        if (wait > 0) wait--;
        if (navigationRetry > 0) navigationRetry--;
        if (pendingNatural) {
            pendingNatural = false;
            SettlementSpawner.discover(entity);
        }
        society.tick();
        duties.clock();
        var group = group();
        if (group == null) return;
        SettlementTicker.submit(entity, group);
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
        var group = group();
        if (!entity.level().isClientSide && group != null) SettlementData.get(level()).leave(group, entity.getUUID(), level().getGameTime());
        stop();
        duties.cancelAssignment();
        groupId = null;
        origin = null;
        settlingAt = null;
        settlementResult = null;
        migrationDeparture = null;
        pendingNatural = false;
        LoadedGroupMembers.changed(entity);
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
        if (staysInFamilyNest() && !Migration.underway(entity)) return false;
        String blocked = entity.independence().householdOuting() ? "independence"
                : entity.wintering().active() ? "wintering" : society.role() == SettlementData.Role.OUTCAST ? "outcast"
                : entity.isPanicking() ? "panic" : entity.isCommandPlaying() ? "command"
                : entity.life().hasUrgentNeed() ? "urgent_need" : entity.life().mealTicks() > 0 ? "meal"
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
        if (Migration.moving(group) && !SettlementSeasons.confined(entity)) {
            if (waterRationFetch != null) abortWaterRation();
            return migrate(group);
        }
        if (group == null || !level().hasChunkAt(group.center)) return false;
        // A water household reserves its actual hat allocation first, then asks the boat's normal landing route for dry ground.
        // It never turns the bank pile into a virtual boat store or skips the pile-contact rule.
        if (waterRationStartOrContinue()) return true;
        // The active boat landing owns the movement flags. Do not fall through into a generic gathering or regroup route,
        // which would cancel the verified fetch before the captain reaches dry ground.
        if (waterRationFetch != null) return false;
        double distance = entity.distanceToSqr(Vec3.atBottomCenterOf(group.center));
        if (straggler(group) && !SettlementSeasons.confined(entity))
            return Migration.afloat(group) ? regroupAfloat(group) : regroup(group, distance);
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
        if (navigateToward(destination)!=Result.FOUND) return false;
        target = destination;
        elapsed = 0;
        return true;
    }

    /** Begin while aboard, then continue only once the boat's own landing left the captain on a dry bank. */
    private boolean waterRationStartOrContinue() {
        if (waterRationFetch == null) waterRationFetch = Rations.waterFetchStart(entity);
        if (waterRationFetch == null) return false;
        if (!Rations.waterFetchActive(waterRationFetch)) { abortWaterRation(); return false; }
        if (entity.isPassenger()) {
            if (!entity.aquaticLife().requestShoreWork(ShoreWork.SETTLEMENT)) abortWaterRation();
            return false;
        }
        var pile = waterRationFetch.pile();
        // A passenger can be dismounted one tick before its bank contact is complete.  Keep the same leased hat
        // identity through that transition; ground work starts only after the shore request has cleared.
        if (!entity.onGround() || entity.aquaticLife().shoreVisitRequested()) {
            entity.aquaticLife().holdShoreWork(ShoreWork.SETTLEMENT);
            return false;
        }
        if (pile == null || !level().hasChunkAt(pile.getBlockPos())
                || level().getBlockEntity(pile.getBlockPos()) != pile
                || entity.distanceToSqr(Vec3.atBottomCenterOf(pile.standCell())) > 48 * 48) {
            abortWaterRation();
            return false;
        }
        target = pile.standCell();
        elapsed = 0;
        waterRationContact.reset();
        entity.aquaticLife().holdShoreWork(ShoreWork.SETTLEMENT);
        return true;
    }

    private boolean waterRationCanContinue() {
        if (waterRationFetch == null || target == null || entity.isPassenger() || !entity.onGround()
                || entity.aquaticLife().shoreVisitRequested() || !Rations.waterFetchActive(waterRationFetch)
                || entity.independence().householdOuting() || entity.wintering().active()
                || society.role() == SettlementData.Role.OUTCAST || entity.isPanicking() || entity.isCommandPlaying()
                || entity.life().hasUrgentNeed() || entity.life().mealTicks() != 0 || elapsed >= 400) return false;
        var pile = waterRationFetch.pile();
        return pile != null && level().hasChunkAt(pile.getBlockPos()) && level().getBlockEntity(pile.getBlockPos()) == pile
                && entity.distanceToSqr(Vec3.atBottomCenterOf(target)) <= 48 * 48;
    }

    private void tickWaterRation() {
        if (!waterRationCanContinue()) { stop(); return; }
        entity.aquaticLife().holdShoreWork(ShoreWork.SETTLEMENT);
        var fetch = waterRationFetch;
        var pile = fetch.pile();
        if (!waterRationContact.tick(pile)) return;
        int moved = Rations.waterFetchTake(entity, pile, fetch);
        // The take path releases on success. Repeat release is harmless, and closes a claim if the source vanished
        // between the contact ticks and validation.
        Rations.waterFetchAbort(fetch);
        waterRationFetch = null;
        waterRationContact.reset();
        target = null;
        afloatTarget = null;
        entity.aquaticLife().releaseShoreWork(ShoreWork.SETTLEMENT);
        GameplayLog.event(entity, "settlement", "water ration fetched hat={} store={} moved={} provisions={}",
                fetch.boatId, pile.getBlockPos(), moved, entity.aquaticLife().provisions());
        wait = 100;
    }

    private void abortWaterRation() {
        if (waterRationFetch != null) Rations.waterFetchAbort(waterRationFetch);
        waterRationFetch = null;
        waterRationContact.reset();
        entity.aquaticLife().releaseShoreWork(ShoreWork.SETTLEMENT);
    }

    /**
     * Migration: everyone gathers at the old centre to hear the leader, packs a mouthful from its own store, then the
     * leader walks ahead, adult members follow, and arrivals give up the old burrow.
     */
    private boolean migrate(SettlementData.Group group) {
        BlockPos site = group.migrateTo;
        if (!Migration.joins(entity, group) || !level().hasChunkAt(site)) return false;
        if (Migration.afloat(group)) return migrateAfloat(group);
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
        Vec3 goal = Vec3.atBottomCenterOf(site);
        double distance = entity.distanceToSqr(goal);
        // A resident already assigned to this destination may walk to its new home outside the plaza radius.
        if (settledAt(site)) return false;
        if (distance <= 8 * 8) {
            if (arrivedFor != group.migrateSince) {
                if (settleAt(site) == Result.FOUND) arrivedFor = group.migrateSince;
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
        if (navigateToward(destination)!=Result.FOUND) return false;
        target = destination;
        elapsed = 0;
        marching = true;
        migrationDeparture = GlobalPos.of(entity.level().dimension(), group.center);
        GameplayLog.limited(entity, "settlement", "march", 1200,
                "march route accepted group={} origin={} destination={} nutrition={} carried={}",
                groupId, origin, target, entity.life().nutrition(), entity.habitat().carrying().getCount());
        return true;
    }

    /**
     * An afloat group never treats the dry bank marker as a path goal or proof of arrival. The settlement Goal keeps its
     * movement flags while the actual captain's hull follows water legs, and commits this member only after that hull
     * reaches the selected berth.
     */
    private boolean migrateAfloat(SettlementData.Group group) {
        long now = level().getGameTime();
        var phase = Migration.phase(group, now);
        if (phase == Migration.Phase.DISCUSS) {
            if (discussedFor != group.migrateSince && entity.distanceToSqr(Vec3.atBottomCenterOf(group.center)) <= 14 * 14) {
                discussedFor = group.migrateSince;
                Migration.discuss(entity, group);
            }
            return false;
        }
        if (phase == Migration.Phase.PREPARE) {
            // The only household store is the hat cargo. It is already aboard the family and is never copied into a land pile.
            packedFor = group.migrateSince;
            return false;
        }
        if (group.migrateWater == null || !level().hasChunkAt(group.migrateWater)) return false;
        if (!(entity.getVehicle() instanceof HatBoatEntity boat) || !boat.isAfloat()
                || !entity.getUUID().equals(boat.ownerYukkuri()) || !boat.protects(entity)) return false;
        // The leader commits the public shore anchor. Every other real hull uses a distinct berth beside that anchor,
        // so a full family flotilla cannot all claim one water cell and prevent its own arrival.
        BlockPos berth = entity.getUUID().equals(group.leader) ? group.migrateWater
                : Migration.waterMemberBerth(level(), group.migrateTo, group.migrateWater, boat);
        if (berth == null) return false;
        return beginAfloatRoute(group, boat, group.migrateTo, berth, true);
    }

    /** Start or retain one verified water route. Returning true keeps this Goal in charge of MOVE/LOOK through the voyage. */
    private boolean beginAfloatRoute(SettlementData.Group group, HatBoatEntity boat, BlockPos bank, BlockPos water, boolean moving) {
        if (Migration.waterArrived(boat, level(), water)) {
            settleAfloat(bank, water);
            return false;
        }
        if (!boat.paddling()) {
            BlockPos leg = waterLeg(boat, water);
            if (leg == null || !boat.paddleFor(entity, HatBoatRoute.surface(level(), leg))) {
                GameplayLog.limited(entity, "settlement", moving ? "water_march.wait" : "water_regroup.wait", 200,
                        "water route waiting group={} boat={} destination={} reason=no_verified_water_leg",
                        groupId, boat.getUUID(), water);
                return false;
            }
            GameplayLog.limited(entity, "settlement", moving ? "water_march" : "water_regroup", 200,
                    "water route accepted group={} boat={} origin={} leg={} destination={}",
                    groupId, boat.getUUID(), boat.blockPosition(), leg, water);
        }
        boolean newRoute = afloatTarget == null || !afloatTarget.equals(water);
        target = water;
        afloatTarget = water;
        if (newRoute) elapsed = 0;
        marching = moving;
        if (moving) migrationDeparture = GlobalPos.of(entity.level().dimension(), group.center);
        return true;
    }

    /** Pick a nearer calm surface only after the boat router has proved a path to it; this never draws a straight line over land. */
    private BlockPos waterLeg(HatBoatEntity boat, BlockPos destination) {
        BlockPos current = BlockPos.containing(boat.getX(), boat.getY() - .12, boat.getZ());
        if (HatBoatRoute.calmSurface(level(), destination)) {
            var surface = HatBoatRoute.surface(level(), destination);
            if (!HatBoatRoute.find(boat, surface).isEmpty() || boat.position().distanceToSqr(surface) <= 3.25) return destination;
        }
        var delta = HatBoatRoute.surface(level(), destination).subtract(boat.position());
        if (delta.horizontalDistanceSqr() <= 0.01) return null;
        var ahead = BlockPos.containing(boat.position().add(delta.normalize().scale(8)));
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(ahead.offset(-2, -2, -2), ahead.offset(2, 2, 2)))
            if (HatBoatRoute.calmSurface(level(), pos) && pos.distSqr(destination) + 1 < current.distSqr(destination)) candidates.add(pos.immutable());
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(destination)));
        for (var candidate : candidates.stream().limit(8).toList())
            if (!HatBoatRoute.find(boat, HatBoatRoute.surface(level(), candidate)).isEmpty()) return candidate;
        return null;
    }

    /** Commit a verified physical-hat arrival without claiming or releasing a land shelter. */
    void settleAfloat(BlockPos bank, BlockPos water) {
        var arrival = GlobalPos.of(entity.level().dimension(), bank);
        if (!arrival.equals(origin)) GameplayLog.event(entity, "settlement",
                "afloat settled group={} origin={} -> {} water={}", groupId, origin, arrival, water);
        origin = arrival;
        settlingAt = null;
        settlementResult = Result.FOUND;
        target = null;
        afloatTarget = null;
    }

    /** The centre moved after this member last settled: it still has to follow. */
    boolean straggler(SettlementData.Group group) {
        return group != null && origin != null && (!settledAt(group.center) || departedFrom(group)) && Migration.joins(entity, group);
    }

    boolean settledAt(BlockPos center) { return origin != null && origin.pos().equals(center) && settlingAt == null; }

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
        if (distance <= 8 * 8) {
            settleAt(group.center);
            return false;
        }
        if (distance > 96 * 96 && !returnEligible() || navigateToward(group.center)!=Result.FOUND) return false;
        target = group.center;
        elapsed = 0;
        marching = true;
        GameplayLog.limited(entity, "settlement", "regroup", 1200,
                "regroup route accepted group={} origin={} destination={} nutrition={} carried={}",
                groupId, origin, target, entity.life().nutrition(), entity.habitat().carrying().getCount());
        return true;
    }

    /** A late hat follows the same verified water-leg rule after the quorum has already moved the group centre. */
    private boolean regroupAfloat(SettlementData.Group group) {
        if (!(entity.getVehicle() instanceof HatBoatEntity boat) || !boat.isAfloat()
                || !entity.getUUID().equals(boat.ownerYukkuri()) || !boat.protects(entity)) return false;
        BlockPos water = Migration.waterDestination(level(), group.center, boat);
        if (water == null) return false;
        return beginAfloatRoute(group, boat, group.center, water, false);
    }

    private boolean canContinueAfloat() {
        var group = group();
        if (group == null || afloatTarget == null || target == null || !target.equals(afloatTarget)
                || !Migration.afloat(group) || entity.isPanicking() || entity.isCommandPlaying()) return false;
        if (Migration.moving(group)) {
            if (group.migrateWater == null) return false;
            if (entity.getUUID().equals(group.leader)) {
                if (!afloatTarget.equals(group.migrateWater)) return false;
            } else if (!Migration.waterMemberBerthValid(level(), group.migrateWater,
                    entity.getVehicle() instanceof HatBoatEntity boat ? boat : null, afloatTarget)) return false;
        }
        if (!Migration.moving(group) && !straggler(group)) return false;
        return entity.getVehicle() instanceof HatBoatEntity boat && boat.isAfloat()
                && entity.getUUID().equals(boat.ownerYukkuri()) && boat.protects(entity);
    }

    private void tickAfloatRoute() {
        if (!canContinueAfloat()) { stop(); return; }
        var group = group();
        var boat = (HatBoatEntity)entity.getVehicle();
        BlockPos bank = Migration.moving(group) ? group.migrateTo : group.center;
        BlockPos water = afloatTarget;
        if (water == null || bank == null) { stop(); return; }
        if (Migration.waterArrived(boat, level(), water)) { settleAfloat(bank, water); return; }
        // A different hat can reach this berth first.  Stop and retry a verified neighbouring surface rather than
        // accepting an overlapping hull or silently treating anchor proximity as arrival.
        if (Migration.moving(group) && !entity.getUUID().equals(group.leader)
                && !Migration.waterMemberBerthValid(level(), group.migrateWater, boat, water)) {
            boat.stopPaddling();
            BlockPos alternate = Migration.waterMemberBerth(level(), bank, group.migrateWater, boat);
            if (alternate == null) { stop(); return; }
            afloatTarget = target = alternate;
            water = alternate;
        }
        if (!boat.paddling() && elapsed % 20 == 1) {
            BlockPos leg = waterLeg(boat, water);
            if (leg != null) boat.paddleFor(entity, HatBoatRoute.surface(level(), leg));
        }
    }

    private Result settleAt(BlockPos site) {
        var arrival=GlobalPos.of(entity.level().dimension(), site);
        if(!arrival.equals(settlingAt)&&!arrival.equals(origin)) {
            // Preserve the old address until this first, bounded FIND_HOME lease has actually
            // been admitted. Later slices resume without releasing it again.
            var lease=entity.habitat().requestHomeSearch();
            if(lease==null) return settledAt(arrival,Result.DEFERRED);
            try(lease) {
                settlingAt=arrival;
                entity.habitat().release();
            }
            // The destination site's allocated homes are available during the march and to later stragglers.
            return settledAt(arrival,entity.habitat().findHome());
        }
        return settledAt(arrival,entity.habitat().findHome());
    }
    private Result settledAt(GlobalPos arrival,Result result) {
        settlementResult=result;
        if(result==Result.DEFERRED) { wait=20; return result; }
        // NOT_FOUND is not an arrival: retain the old origin and the post-release marker, then retry at a bounded rate.
        if(result==Result.NOT_FOUND) {
            GameplayLog.limited(entity,"settlement","home_not_found",200,"settle waiting group={} destination={} origin={} reason=no_home",groupId,arrival,origin);
            wait=SETTLEMENT_RETRY_TICKS; return result;
        }
        if(!arrival.equals(origin)||settlingAt!=null)GameplayLog.event(entity,"settlement",
                "settled group={} origin={} -> {} home={}",groupId,origin,arrival,entity.habitat().homeStand());
        origin=arrival;settlingAt=null;
        var group = group();
        if (group != null && !Migration.moving(group) && arrival.pos().equals(group.center)) migrationDeparture = null;
        return result;
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
        if (waterRationFetch != null) return waterRationCanContinue();
        if (afloatTarget != null) return canContinueAfloat();
        if (staysInFamilyNest() && !Migration.underway(entity)) return false;
        var group = group();
        if (marching && !Migration.moving(group) && (group == null || !straggler(group) || target == null || !target.equals(group.center))) return false;
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
        if (waterRationFetch != null) { tickWaterRation(); return; }
        if (afloatTarget != null) { tickAfloatRoute(); return; }
        if(target!=null&&(elapsed%20==1||entity.getNavigation().isDone())) {
            var result=navigateToward(target);
            if(result==Result.NOT_FOUND)stop();
            else if(result==Result.DEFERRED)elapsed--; // Budget waiting is not a failed route or elapsed walking time.
        }
    }

    public void stop() {
        abortWaterRation();
        if (marching && target != null) GameplayLog.limited(entity, "settlement", "march.stop", 1200,
                "march stopped group={} target={} origin={} elapsed={} distanceSqr={} nutrition={} energy={} panic={} mealTicks={}",
                groupId, target, origin, elapsed, entity.distanceToSqr(Vec3.atBottomCenterOf(target)),
                entity.life().nutrition(), entity.life().energy(), entity.isPanicking(), entity.life().mealTicks());
        cancelNavigation();
        Migration.cancelSearch(entity,group());
        target = null;
        afloatTarget = null;
        marching = false;
        wait = 100;
        entity.getNavigation().stop();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (groupId != null) tag.putUUID("Group", groupId);
        FamilyData.putPosition(tag, "Origin", origin);
        FamilyData.putPosition(tag, "MigrationDeparture", migrationDeparture);
        tag.putBoolean("PendingNatural", pendingNatural);
        tag.put("Duties", duties.save());
        return tag;
    }

    public void load(CompoundTag tag) {
        groupId = tag.hasUUID("Group") ? tag.getUUID("Group") : null;
        origin = FamilyData.position(tag, "Origin");
        settlingAt = null;
        settlementResult = null;
        if (origin == null) groupId = null;
        migrationDeparture = FamilyData.position(tag, "MigrationDeparture");
        recoverLegacyDeparture();
        pendingNatural = tag.getBoolean("PendingNatural") && groupId == null;
        waterRationFetch = null;
        waterRationContact.reset();
        target = null;
        afloatTarget = null;
        marching = false;
        wait = elapsed = 0;
        duties.load(tag.getCompound("Duties"));
        LoadedGroupMembers.changed(entity);
    }

    /** Old saves have no departure marker: recover only a loaded member visibly marching away from its still-active old centre. */
    private void recoverLegacyDeparture() {
        if (migrationDeparture != null || origin == null || entity.level().isClientSide) return;
        var group = group();
        if (group == null || Migration.phase(group, level().getGameTime()) != Migration.Phase.MARCH) return;
        recoverDepartureForAbandon(group);
    }

    /** Compatibility for a loaded old-save member when the still-active MARCH is abandoned before it can reload. */
    void recoverDepartureForAbandon(SettlementData.Group group) {
        if (migrationDeparture != null || origin == null || !origin.pos().equals(group.center) || !Migration.joins(entity, group)
                || entity.distanceToSqr(Vec3.atBottomCenterOf(group.center)) <= 48 * 48) return;
        migrationDeparture = origin;
    }
}
