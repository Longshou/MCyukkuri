package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import java.util.Map;
import java.util.WeakHashMap;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.core.Direction;

/**
 * Group-owned migration after member deaths or sustained foraging failure.
 *
 * <p>The leader weighs a <b>pressure</b> made of several causes ({@link #pressure}), each scored from 0 and summed:
 * <pre>
 *   deaths     0..1.2  weighted member deaths of the last 3 days / max(3, 30 % of the population)   (alone enough)
 *   famine     0..1    two checks in famine, or famine after two days of failed foraging            (alone enough)
 *   predators  0..0.7  the threat counter (sightings 1, attacks 2, deaths 3; decays daily); x1.5 for a Patchouli leader
 *   homeless   0..0.4  share of adult members without a usable home (burrows destroyed or washed in)
 *   worn out   0..0.4  consecutive communal hunts that found no meadow or brought nothing
 *   crowded    0..0.2  more than four loaded members per household home
 * </pre>
 * A move is decided at 1.0, moved up or down by the leader's boldness (&plusmn;0.2). So a massacre or a real famine is
 * each enough on its own; predators, lost burrows and a grazed-out territory only together (or predators plus a
 * credulous Patchouli leader, the TM03/TM04 story). Deaths weigh by cause: killed by a predator 1.5, starved 1.2, by a
 * player or another cause 1, by its own community (a sanction, a quarrel) 0.5. Nobody moves in winter (the burrows are
 * closed; the pressure waits for spring) or within two days of the last move.
 *
 * <p>The move itself has four visible phases: the leader calls everyone to the old centre and they <b>discuss</b> it
 * ({@value #DISCUSS_TICKS} ticks: the leader pleads, the bold and the attached object, the rest agree); they
 * <b>prepare</b> ({@value #PREPARE_TICKS} ticks: every adult takes a mouthful of up to {@link Rations#FETCH_MAX} portions
 * from its own store, the leader of a cave community from the commons, see {@link SettlementLife}); they <b>march</b>
 * behind the vanguard to a site away from the danger, preferring one with meadows; and they <b>settle</b>: each finds or
 * digs a home and puts its mouthful on the new pile, the leader's food check, the death record and the counters start
 * afresh. What the mouths could not carry stays in the old burrows.
 *
 * <p>Lecture and wiki basis: TM03/TM04 (a community takes a red-eyed visitor for a predator's omen and, on Patchouli's
 * judgement, leaves its nest); komicolle 40720 No.11518794 (without rules they use up the stores). The causes, weights
 * and phases are project design.
 */
final class Migration {
    private static final int SIGHT_RANGE = 12, SIGHT_REPEAT = 2400, OMEN_REPEAT = 12000, ATTACK_REPEAT = 200, ARRIVE_RANGE = 8; // [original] pacing
    /** The leader reviews the pressure once a minute; a failed site search waits two minutes. */
    private static final int REVIEW_TICKS = 1200, NO_SITE_TICKS = 2400;
    static final int DISCUSS_TICKS = 600, PREPARE_TICKS = 600, SETTLE_TICKS = 48000;
    /**
     * Once the pivot (vanguard, or the leader when there is none) stands at the destination the group gets this long from that
     * moment to dig or claim its homes; a slow march must not eat the building time.
     */
    static final int SETTLE_BUILD_TICKS = 6000;
    /** The others leave the old centre this long after the march begins, so the scout is really ahead. */
    static final int VANGUARD_HEAD_START = 160;
    static final long DEATH_WINDOW = 72000;

    enum Phase { NONE, DISCUSS, PREPARE, MARCH }
    private record LoggedPhase(long startedAt, Phase phase) {}
    private static final Map<SettlementData.Group, LoggedPhase> LOGGED_PHASES = new WeakHashMap<>();

    static boolean moving(SettlementData.Group group) { return group != null && group.migrateTo != null; }
    /** A bank-side public pile marks the community whose members really live aboard physical hats. */
    static boolean afloat(SettlementData.Group group) { return group != null && group.waterCommons != null; }
    static Phase phase(SettlementData.Group group, long now) {
        if (!moving(group)) return Phase.NONE;
        long t = now - group.migrateSince;
        return t < DISCUSS_TICKS ? Phase.DISCUSS : t < DISCUSS_TICKS + PREPARE_TICKS ? Phase.PREPARE : Phase.MARCH;
    }

    /** A usable destination has both an actual calm berth for the leader's full hull and a distinct dry cell for the next public pile. */
    static boolean waterBankReady(ServerLevel level, BlockPos bank, YukkuriEntity captain) {
        var boat = waterBoat(captain);
        var water = waterDestination(level, bank, boat);
        return water != null && waterCommons(level, bank, water) != null;
    }
    /** A captain may choose only a berth whose complete existing hull fits at the destination surface. */
    static BlockPos waterDestination(ServerLevel level, BlockPos bank, HatBoatEntity boat) {
        if (boat == null) return null;
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(bank.offset(-12, -3, -12), bank.offset(12, 3, 12))) {
            if (!HatBoatRoute.calmSurface(level, pos)) continue;
            if (waterHullFits(level, boat, pos)) candidates.add(pos.immutable());
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(bank))).orElse(null);
    }

    /**
     * A flotilla keeps the leader's chosen arrival surface for the public shore marker.  Other physical hats must take
     * a distinct, actually unoccupied calm surface nearby; they never all steer into the leader's one grid point.
     */
    static BlockPos waterMemberBerth(ServerLevel level, BlockPos bank, BlockPos anchor, HatBoatEntity boat) {
        if (boat == null || anchor == null) return null;
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-ARRIVE_RANGE, -2, -ARRIVE_RANGE),
                anchor.offset(ARRIVE_RANGE, 2, ARRIVE_RANGE))) {
            if (pos.equals(anchor) || pos.distSqr(anchor) > ARRIVE_RANGE * ARRIVE_RANGE
                    || !HatBoatRoute.calmSurface(level, pos) || !waterHullFits(level, boat, pos)) continue;
            candidates.add(pos.immutable());
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(bank))).orElse(null);
    }

    /** A member berth remains valid only while it stays on the anchor's real water area and no other hull occupies it. */
    static boolean waterMemberBerthValid(ServerLevel level, BlockPos anchor, HatBoatEntity boat, BlockPos berth) {
        return boat != null && anchor != null && berth != null && !berth.equals(anchor) && berth.distSqr(anchor) <= ARRIVE_RANGE * ARRIVE_RANGE
                && HatBoatRoute.calmSurface(level, berth) && waterHullFits(level, boat, berth);
    }

    /** Full-hull clearance includes other afloat hats; block-only checks let multiple boats claim the same berth. */
    private static boolean waterHullFits(ServerLevel level, HatBoatEntity boat, BlockPos pos) {
        var surface = HatBoatRoute.surface(level, pos);
        var bounds = boat.getBoundingBox().move(surface.subtract(boat.position()));
        if (!level.noCollision(boat, bounds)) return false;
        return level.getEntitiesOfClass(HatBoatEntity.class, bounds.inflate(.01), other -> other != boat && other.isAfloat())
                .stream().noneMatch(other -> other.getBoundingBox().intersects(bounds));
    }
    /** A boat reaches the concrete water destination only after its position, not its passenger's old bank distance, is close to it. */
    static boolean waterArrived(HatBoatEntity boat, ServerLevel level, BlockPos water) {
        return boat != null && water != null && boat.isAfloat() && HatBoatRoute.calmSurface(level, water)
                && boat.position().distanceToSqr(HatBoatRoute.surface(level, water)) <= .16;
    }
    private static HatBoatEntity waterBoat(YukkuriEntity captain) {
        return captain != null && captain.getVehicle() instanceof HatBoatEntity boat && boat.isAfloat()
                && captain.getUUID().equals(boat.ownerYukkuri()) && boat.protects(captain) ? boat : null;
    }
    /** The next public pile sits on a real dry bank beside the selected future afloat address. */
    private static BlockPos waterCommons(ServerLevel level, BlockPos bank, BlockPos water) {
        if (water == null) return null;
        var candidates = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(bank.offset(-5, -2, -5), bank.offset(5, 2, 5))) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
            if (pos.distSqr(water) <= 12 * 12) candidates.add(pos.immutable());
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> pos.distSqr(bank))).orElse(null);
    }
    private static Direction dryApproach(ServerLevel level, BlockPos pile) {
        for (var side : Direction.Plane.HORIZONTAL) {
            var stand = pile.relative(side);
            if (level.hasChunkAt(stand) && level.getFluidState(stand).isEmpty()
                    && level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), Direction.UP)) return side;
        }
        return Direction.SOUTH;
    }

    /** The red-eyed visitor of plan 4.2 (a red-eyed Myon seen by a member, see sight): an omen weighs like an attack on a member. */
    static void omen(ServerLevel level, SettlementData.Group group, BlockPos pos) {
        SettlementData.get(level).reportThreat(group, pos, level.getGameTime(), 2);
    }

    /** A member was hurt (weight 2) or killed (weight 3) by a predator: evidence for the threat counter. */
    static void attacked(YukkuriEntity victim, DamageSource source, boolean fatal) {
        if (!(victim.level() instanceof ServerLevel level) || !(source.getEntity() instanceof YukkuriEntity attacker)
                || !attacker.profile().dangerToYukkuri() || victim.profile().dangerToYukkuri()) return;
        var group = victim.settlement().group();
        if (group == null || group.kind == SettlementData.Kind.BANDITS) return;
        long now = level.getGameTime();
        if (!fatal && now - group.attackedAt < ATTACK_REPEAT) return;
        group.attackedAt = now;
        SettlementData.get(level).reportThreat(group, attacker.blockPosition(), now, fatal ? 3 : 2);
    }

    /** Any member death (called once per death from YukkuriEntity.die): recorded with its cause; the leader reviews soon after. */
    static void died(YukkuriEntity victim, DamageSource source) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        var group = victim.settlement().group();
        if (group == null || group.kind == SettlementData.Kind.BANDITS) return;
        if (victim.getUUID().equals(group.leader)) {
            CommonStore.remember(level, group);
            LeaderSuccession.died(victim, group);
        }
        String cause;
        if (source.getEntity() instanceof YukkuriEntity killer && killer.profile().dangerToYukkuri()) cause = "predator";
        else if (source.getEntity() instanceof YukkuriEntity killer && group.id.equals(killer.settlement().groupId())) cause = "kin";
        else if (source.getEntity() instanceof Player) cause = "player";
        else if (source.is(DamageTypes.STARVE)) cause = "starve";
        else cause = "other";
        long now = level.getGameTime();
        group.commons.death(now, cause);
        group.migrateRetry = Math.min(group.migrateRetry, now + 200);
        SettlementData.get(level).setDirty();
    }
    private static float deathWeight(String cause) {
        return switch (cause) { case "predator" -> 1.5f; case "starve" -> 1.2f; case "kin" -> .5f; default -> 1f; };
    }

    static void observe(SettlementData data, YukkuriEntity caller, SettlementData.Group group, long now) {
        if (!caller.profile().dangerToYukkuri() && !caller.profile().houseBody()) sight(data, caller, group, now);
    }

    /** Called once per sampled group by its ticker; the caller supplies a real loaded participant. */
    static void advance(ServerLevel level, SettlementData data, YukkuriEntity caller, SettlementData.Group group, long now) {
        boolean leader = caller.getUUID().equals(group.leader);
        if (moving(group)) {
            if (!reserveTarget(data, group, group.migrateTo)) {
                abandon(data, group, caller, now, "destination_unavailable");
                return;
            }
            var phase = phase(group, now);
            if (afloat(group)) refreshWaterVanguard(level, group, now);
            else trackPioneer(level, group, phase, now);
            var logged=LOGGED_PHASES.get(group);
            if(logged==null||logged.startedAt()!=group.migrateSince||logged.phase()!=phase) {
                GameplayLog.world(level,"settlement","migration phase={} group={} origin={} destination={} elapsed={}",
                        phase,group.id,group.center,group.migrateTo,now-group.migrateSince);
                LOGGED_PHASES.put(group,new LoggedPhase(group.migrateSince,phase));
            }
            if (leader && phase == Phase.DISCUSS && (now - group.migrateSince) % 300 < 100) caller.showReaction("teary", "migrate_discuss", 80);
            boolean ready = phase == Phase.MARCH && arrived(level, group);
            if (ready && complete(data, group, caller, now)) return;
            if (expired(group, now)) abandon(data, group, caller, now, ready ? "arrival_store_unavailable" : "deadline");
            return;
        }
        if (leader) review(caller, group, now, true);
    }

    static boolean expired(SettlementData.Group group, long now) {
        long deadline = group.migrateSince + DISCUSS_TICKS + PREPARE_TICKS + YukkuriConfig.MIGRATE_TIMEOUT.get();
        if (group.migrateArrivedAt > 0) deadline = Math.max(deadline, group.migrateArrivedAt + SETTLE_BUILD_TICKS);
        return now >= deadline;
    }

    // ---- vanguard ---------------------------------------------------------------------------------------------------

    /** The scout or own-hat captain of the current move, still loaded, living and participating. */
    static YukkuriEntity vanguard(ServerLevel level, SettlementData.Group group) {
        if (group.migrateVanguard == null) return null;
        return level.getEntity(group.migrateVanguard) instanceof YukkuriEntity scout && scout.isAlive()
                && group.id.equals(scout.settlement().groupId()) && joins(scout, group) ? scout : null;
    }
    static boolean isVanguard(YukkuriEntity entity, SettlementData.Group group) {
        return group.migrateVanguard != null && group.migrateVanguard.equals(entity.getUUID());
    }

    /** A physical captain can take the route, never another member's hat or the vacant office. */
    private static boolean waterCaptain(YukkuriEntity member, SettlementData.Group group) {
        var boat = waterBoat(member);
        return member != null && member.isAlive() && member.family().adult() && joins(member, group)
                && !member.isNoAi() && !member.isPanicking() && !member.life().hasUrgentNeed()
                && !member.life().isSleeping() && !member.isCommandPlaying() && !member.operations().active()
                && boat != null && !boat.unsteadyMotion() && boat.flooding() == 0 && !boat.landing() && !boat.hasIncoming();
    }

    private static void refreshWaterVanguard(ServerLevel level, SettlementData.Group group, long now) {
        var current = vanguard(level, group);
        if (current != null && waterCaptain(current, group)) return;
        if (now < group.migrateGuideRetry || !level.hasChunkAt(group.migrateTo)) return;
        group.migrateGuideRetry = now + 200;
        var area = new AABB(group.center).minmax(new AABB(group.migrateTo)).inflate(72);
        var captains = LoadedGroupMembers.query(level, group.id, area, member -> waterCaptain(member, group)).stream()
                .sorted(Comparator.comparingDouble((YukkuriEntity member) -> member.distanceToSqr(Vec3.atCenterOf(group.migrateTo)))
                        .thenComparing(member -> member.getUUID().toString())).toList();
        if (captains.isEmpty()) return;
        // Prefer a hull that fits the existing anchor; at most one new bank search per retry.
        var captain = captains.stream().filter(member -> group.migrateWater != null
                && HatBoatRoute.calmSurface(level, group.migrateWater)
                && waterHullFits(level, waterBoat(member), group.migrateWater)).findFirst().orElse(captains.getFirst());
        var boat = waterBoat(captain);
        var water = group.migrateWater;
        if (water == null || !HatBoatRoute.calmSurface(level, water) || !waterHullFits(level, boat, water))
            water = waterDestination(level, group.migrateTo, boat);
        if (water == null || waterCommons(level, group.migrateTo, water) == null) return;
        UUID previous = group.migrateVanguard;
        group.migrateVanguard = captain.getUUID();
        group.migrateWater = water;
        SettlementData.get(level).setDirty();
        GameplayLog.event(captain, "settlement", "water migration handoff group={} previous={} captain={} boat={} anchor={}",
                group.id, previous, captain.getUUID(), boat.getUUID(), water);
    }

    /** Marisa goes first (bold, sturdy, not frail); without one the sturdiest healthy adult; a frail Patchouli only if nobody else can. */
    private static double scoutScore(YukkuriEntity member, SettlementData.Group group) {
        var traits = member.species().traits();
        double score = member.getVariant() == YukkuriVariant.MARISA ? 100 : 0;
        score += 10 * traits.might() - 10 * traits.fatigue() + 20 * member.getHealth() / Math.max(1f, member.getMaxHealth());
        if (traits.fatigue() > 1.1f) score -= 40;
        if (member.life().nutrition() >= 30) score += 5;
        if (member.life().energy() >= 40) score += 5;
        if (member.getUUID().equals(group.leader)) score -= 5;
        // A scout without a partner or young to wait for can really go ahead.
        score -= 4 * Math.max(0, member.habitat().householdNeed() - 1);
        return score;
    }

    /** Keeps a still-fit scout; otherwise picks the best loaded adult. Logged when the choice changes. */
    private static void refreshVanguard(ServerLevel level, SettlementData.Group group) {
        var current = vanguard(level, group);
        if (current != null && current.getHealth() >= .4f * current.getMaxHealth() && current.life().nutrition() >= 20) return;
        YukkuriEntity best = null;
        double bestScore = -1e9;
        var area = new AABB(group.center).minmax(new AABB(group.migrateTo)).inflate(72);
        for (var member : LoadedGroupMembers.query(level, group.id, area,
                e -> e.isAlive() && group.id.equals(e.settlement().groupId()) && joins(e, group) && e.family().adult() && !e.isPassenger())) {
            double score = scoutScore(member, group);
            if (best == null || score > bestScore || score == bestScore && member.getUUID().compareTo(best.getUUID()) < 0) {
                best = member; bestScore = score;
            }
        }
        UUID chosen = best == null ? null : best.getUUID();
        if (Objects.equals(chosen, group.migrateVanguard)) return;
        UUID previous = group.migrateVanguard;
        group.migrateVanguard = chosen;
        SettlementData.get(level).setDirty();
        GameplayLog.world(level, "settlement", "migration vanguard group={} vanguard={} species={} score={} previous={} leader={}",
                group.id, chosen, best == null ? null : best.getVariant().assetId(), String.format(Locale.ROOT, "%.1f", bestScore),
                previous, group.leader);
    }

    /** The vanguard (leader if none) standing at the destination, for the arrival test and the build-time extension. */
    private static boolean pivotAtDestination(ServerLevel level, SettlementData.Group group) {
        var pivot = vanguard(level, group);
        if (pivot == null && group.leader != null && level.getEntity(group.leader) instanceof YukkuriEntity chief && chief.isAlive()
                && group.id.equals(chief.settlement().groupId())) pivot = chief;
        if (pivot == null || group.migrateTo == null) return false;
        return pivot.settlement().settledAt(group.migrateTo)
                || pivot.distanceToSqr(group.migrateTo.getX() + .5, group.migrateTo.getY(), group.migrateTo.getZ() + .5) <= ARRIVE_RANGE * ARRIVE_RANGE;
    }

    private static void trackPioneer(ServerLevel level, SettlementData.Group group, Phase phase, long now) {
        refreshVanguard(level, group);
        if (phase != Phase.MARCH || group.migrateArrivedAt > 0 || !pivotAtDestination(level, group)) return;
        group.migrateArrivedAt = now;
        SettlementData.get(level).setDirty();
        GameplayLog.world(level, "settlement", "migration pivot arrived group={} destination={} vanguard={} leader={} elapsed={} build_deadline={}",
                group.id, group.migrateTo, group.migrateVanguard, group.leader, now - group.migrateSince, now + SETTLE_BUILD_TICKS);
    }

    static void decay(SettlementData data, SettlementData.Group group, long now) {
        if (group.threat <= 0) return;
        if (now - Math.max(group.threatTime, group.decayAt) >= YukkuriConfig.THREAT_DECAY_TICKS.get()) {
            group.threat--; group.decayAt = now; data.setDirty();
        }
    }

    /**
     * One count per predator per group per {@link #SIGHT_REPEAT} ticks. The fright itself is shown by the threat
     * reaction ({@code status.Reactions}); this only counts, so a sighting no longer cries out twice.
     */
    private static void sight(SettlementData data, YukkuriEntity caller, SettlementData.Group group, long now) {
        if (group.sighted.size() > 32) group.sighted.values().removeIf(time -> now - time >= OMEN_REPEAT);
        var seen = caller.level().getEntitiesOfClass(YukkuriEntity.class, caller.getBoundingBox().inflate(SIGHT_RANGE),
                p -> p.isAlive() && (p.profile().dangerToYukkuri() || RareForms.redEye(p))
                        && !group.id.equals(p.settlement().groupId()) && caller.hasLineOfSight(p));
        for (YukkuriEntity predator : seen) {
            if (!predator.profile().dangerToYukkuri()) continue;
            Long last = group.sighted.get(predator.getUUID());
            if (last != null && now - last < SIGHT_REPEAT) continue;
            group.sighted.put(predator.getUUID(), now);
            data.reportThreat(group, predator.blockPosition(), now);
            return;
        }
        // A red-eyed Myon from outside the group is taken for a bringer of disaster (wiki species/myon, L05《赤い眼のみょん》).
        for (YukkuriEntity visitor : seen) {
            if (!RareForms.redEye(visitor)) continue;
            Long last = group.sighted.get(visitor.getUUID());
            if (last != null && now - last < OMEN_REPEAT) continue;
            group.sighted.put(visitor.getUUID(), now);
            omen((ServerLevel) caller.level(), group, visitor.blockPosition());
            caller.mind().felt(EmotionEvent.FRIGHTENED); caller.showReaction("surprise", "afraid", 60);
            return;
        }
    }

    // ---- pressure --------------------------------------------------------------------------------------------------

    record Pressure(float deaths, float famine, float predators, float homeless, float wornOut, float crowded, float threshold, int recentDeaths) {
        float total() { return deaths + famine + predators + homeless + wornOut + crowded; }
        String main() {
            String best = "deaths"; float value = deaths;
            if (famine > value) { best = "famine"; value = famine; }
            if (predators > value) { best = "predators"; value = predators; }
            if (homeless > value) { best = "homeless"; value = homeless; }
            if (wornOut > value) { best = "worn_out"; value = wornOut; }
            if (crowded > value) best = "crowded";
            return best;
        }
        String text() {
            return String.format(Locale.ROOT, "total %.2f/%.2f deaths %.2f (%d) famine %.2f predators %.2f homeless %.2f worn_out %.2f crowded %.2f",
                    total(), threshold, deaths, recentDeaths, famine, predators, homeless, wornOut, crowded);
        }
    }

    static Pressure pressure(ServerLevel level, SettlementData.Group group, YukkuriEntity leader) {
        long now = level.getGameTime();
        var c = group.commons;
        var members = Rations.members(level, group);
        int adults = 0, homeless = 0;
        var homes = new HashSet<BlockPos>();
        for (var m : members) {
            var home = m.habitat().shelter();
            if (home != null) homes.add(home.getBlockPos());
            if (!joins(m, group)) continue;
            adults++;
            // An afloat community's residence is its current physical hat.  A boat rider with that
            // address must not create artificial homeless pressure just because it has no burrow.
            boolean afloatHome = afloat(group) && m.habitat().waterHome() != null;
            if (home == null ? !afloatHome : !home.usable(m)) homeless++;
        }
        float weighted = 0;
        int recent = 0;
        for (var d : c.deaths) {
            long age = now - d.getLong("T");
            if (age < 0 || age > DEATH_WINDOW) continue;
            recent++;
            weighted += deathWeight(d.getString("C")) * (1 - .5f * age / DEATH_WINDOW);
        }
        float deaths = Math.min(1.2f, weighted / Math.max(3f, .3f * (members.size() + recent)));
        float famine;
        if (c.famineDays >= 2 || c.famineDays >= 1 && c.failDays >= 2) famine = 1f;
        else if (c.famineDays == 1) famine = .5f;
        else if (c.shortDays >= 3) famine = .3f;
        else famine = 0;
        int scale = Math.max(1, YukkuriConfig.MIGRATE_THRESHOLD.get()) * 3;
        float predators = .7f * Math.min(1f, group.threat / (float)scale);
        // Patchouli believes rumours more readily (TM03/TM04: the community left its nest on her judgement).
        if (leader != null && "patchouli".equals(leader.getVariant().dialogueSpecies())) predators *= 1.5f;
        float lost = adults == 0 || now < c.settledUntil ? 0 : .4f * Math.clamp((homeless / (float)adults - .25f) / .5f, 0f, 1f);
        float wornOut = c.huntFails >= 3 ? .4f : c.huntFails == 2 ? .2f : 0;
        float crowded = !homes.isEmpty() && members.size() > homes.size() * 4 ? .2f : 0;
        float threshold = 1f + (leader == null ? 0 : (leader.life().personality().trait(Trait.BOLDNESS) - 50) / 250f);
        return new Pressure(deaths, famine, predators, lost, wornOut, crowded, threshold, recent);
    }

    /** The leader's review: records the pressure and decides to move when it reaches the threshold. */
    private static final Map<SettlementData.Group, MigrationSearch> SEARCHES = new WeakHashMap<>();
    static boolean searching(SettlementData.Group group) { return SEARCHES.containsKey(group); }
    static void cancelSearch(YukkuriEntity entity, SettlementData.Group group) {
        var search = SEARCHES.get(group);
        // A leaving member must not discard the leader's in-progress search.
        if (search != null && search.ownedBy(entity)) {
            search.cancel(); SEARCHES.remove(group);
        }
    }
    static void continueSearch(YukkuriEntity leader, SettlementData.Group group, long now) {
        if (searching(group)) review(leader, group, now, false);
    }
    private static Pressure recordPressure(ServerLevel level, SettlementData.Group group, YukkuriEntity leader) {
        var p = pressure(level, group, leader);
        group.commons.pressure100 = Math.round(p.total() * 100);
        group.commons.pressureMain = p.main();
        group.commons.pressureText = p.text();
        return p;
    }
    private static void review(YukkuriEntity leader, SettlementData.Group group, long now, boolean sampled) {
        var pending = SEARCHES.get(group);
        if (pending != null) {
            if (!pending.forced && leader.level() instanceof ServerLevel level
                    && (YukkuriConfig.MIGRATE_THRESHOLD.get() <= 0 || now < group.commons.settledUntil
                    || Seasons.on(level, group.center, Season.WINTER))) {
                pending.cancel(); SEARCHES.remove(group); group.migrateRetry = now + REVIEW_TICKS;
                return;
            }
            String reason = pending.reason;
            if (sampled && !pending.forced && leader.level() instanceof ServerLevel level) {
                var p = recordPressure(level, group, leader);
                if (p.total() < p.threshold()) {
                    pending.cancel(); SEARCHES.remove(group); group.migrateRetry = now + REVIEW_TICKS;
                    return;
                }
                reason = p.main();
            }
            var result = start(leader, group, pending.forced, reason);
            if (result == Result.NOT_FOUND) {
                group.migrateRetry = now + NO_SITE_TICKS;
                group.commons.note(now, "migrate_no_site", leader.getUUID(), group.commons.pressureText);
            }
            return;
        }
        if (now < group.migrateRetry || !(leader.level() instanceof ServerLevel level)) return;
        group.migrateRetry = now + REVIEW_TICKS;
        var p = recordPressure(level, group, leader);
        var c = group.commons;
        if (YukkuriConfig.MIGRATE_THRESHOLD.get() <= 0 || p.total() < p.threshold() || now < c.settledUntil
                || Seasons.on(level, group.center, Season.WINTER)) {cancelSearch(leader,group);return;}
        var result=start(leader, group, false, p.main());
        if(result==Result.DEFERRED){group.migrateRetry=now+1;return;}
        if(result==Result.NOT_FOUND) {
            group.migrateRetry = now + NO_SITE_TICKS;
            c.note(now, "migrate_no_site", leader.getUUID(), p.text());
        }
    }
    /** Test door and the leader's decision. {@code force} skips the pressure (the site search still applies). */
    static Result start(YukkuriEntity leader, SettlementData.Group group, boolean force) {
        String reason = "command";
        if (leader.level() instanceof ServerLevel level) {
            var p = pressure(level, group, leader);
            if (p.total() > 0) reason = p.main();
        }
        return start(leader, group, force, reason);
    }

    private static Result start(YukkuriEntity leader, SettlementData.Group group, boolean force, String reason) {
        if (!(leader.level() instanceof ServerLevel level) || group.kind == SettlementData.Kind.BANDITS || moving(group)
                || !leader.getUUID().equals(SettlementData.get(level).activeLeader(group)) || !leader.isAlive() || !leader.family().adult()
                || leader.isPanicking() || leader.life().isSleeping() || !group.id.equals(leader.settlement().groupId())
                || !level.hasChunkAt(group.center)) {cancelSearch(leader,group);return Result.NOT_FOUND;}
        var search = SEARCHES.get(group);
        if (search != null && (!search.current(level, leader, group) || force && !search.forced || !search.reason.equals(reason))) {
            search.cancel(); SEARCHES.remove(group); search = null;
        }
        if (search == null) {
            search = new MigrationSearch(level, leader, group, force, reason);
            SEARCHES.put(group, search);
        }
        var result = search.advance(level, leader);
        if (result == Result.DEFERRED) return result;
        SEARCHES.remove(group); search.cancel();
        if (result == Result.NOT_FOUND) return result;
        BlockPos site = search.result();
        reason = search.reason; force = search.forced;
        long now = level.getGameTime();
        var data = SettlementData.get(level);
        if (!reserveTarget(data, group, site)) return Result.NOT_FOUND;
        BlockPos water = afloat(group) ? waterDestination(level, site, waterBoat(leader)) : null;
        if (afloat(group) && (water == null || waterCommons(level, site, water) == null)) { releaseTarget(data, group, site); return Result.NOT_FOUND; }
        var c = group.commons;
        group.migrateTo = site; group.migrateWater = water; group.migrateSince = now;
        group.migrateVanguard = afloat(group) ? leader.getUUID() : null; group.migrateArrivedAt = 0;
        group.migrateGuideRetry = 0;
        c.migrateReason = reason;
        c.note(now, "migrate_decided", leader.getUUID(), reason + " to " + site.toShortString() + (force ? " (command)" : "") + " " + c.pressureText);
        data.setDirty();
        leader.showReaction("teary", "migrate_discuss", 80);
        String key = switch (reason) {
            case "deaths", "famine", "predators" -> "message.mcyukkuri.settlement.migrate." + reason;
            default -> "message.mcyukkuri.settlement.migrate";
        };
        for (var player : level.players())
            if (!player.isSpectator() && player.distanceToSqr(leader) <= 24 * 24)
                player.sendSystemMessage(Component.translatable(key, leader.getName()));
        var newsBox = leader.getBoundingBox().inflate(32);
        var informed = LoadedGroupMembers.query(level, group.id, newsBox,
                e -> e != leader && e.isAlive() && group.id.equals(e.settlement().groupId()));
        boolean competing = false;
        for (int i = 0; !competing && i < informed.size(); i++)
            for (int j = i + 1; j < informed.size(); j++)
                if (informed.get(i).distanceToSqr(informed.get(j)) <= 16 * 16) { competing = true; break; }
        // Ordinary "heard" speech competes for the nearby quiet window: keep the original first speaker.
        if (competing) informed = LoadedGroupMembers.encounterOrder(level, newsBox, informed);
        for (YukkuriEntity other : informed) {
            other.showReaction("surprise", "heard", 60);
            other.mind().add(3, "migrate_news");
        }
        return Result.FOUND;
    }

    /** A member at the gathering hears the leader out (once per move): the bold and attached object, the others agree. */
    static void discuss(YukkuriEntity member, SettlementData.Group group) {
        if (member.getUUID().equals(group.leader)) return;
        var p = member.life().personality();
        if (p.trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_60 && p.attachment() >= Temperament.ATTACHMENT_AT_65) {
            member.showReaction("angry", "migrate_object", 80);
            member.mind().add(2, "migrate_object");
        } else member.showReaction("teary", "migrate_agree", 80);
    }

    /**
     * Who takes part: adults and children (子油) free of a home claim and of a human owner, not outcasts or slaves, not the
     * wild predators. Babies (赤油) remain on their existing family FOLLOW/care path and never receive an independent
     * settlement route.
     */
    static boolean joins(YukkuriEntity e, SettlementData.Group group) {
        return migrant(e,group);
    }
    private static boolean migrant(YukkuriEntity e, SettlementData.Group group) {
        var role = group.role(e.getUUID());
        return e.isAlive() && (e.family().adult() || e.family().stage() == FamilyLife.Stage.JUVENILE)
                && (role == SettlementData.Role.MEMBER || role == SettlementData.Role.LEADER)
                && !e.profile().dangerToYukkuri() && !e.profile().houseBody() && !SettlementEvents.homeClaimHeld(e) && SettlementEvents.unowned(e);
    }

    /** Old-home chores wait until the member has reached the destination and finished its bounded home search. */
    static boolean underway(YukkuriEntity e) {
        var group = e.settlement().group();
        if (group == null || !joins(e, group)) return false;
        return moving(group) ? !e.settlement().settledAt(group.migrateTo) : e.settlement().straggler(group);
    }

    /** Reads physical arrival and member-owned home handoffs without advancing either. */
    private static boolean arrived(ServerLevel level, SettlementData.Group group) {
        BlockPos arrival = afloat(group) ? group.migrateWater : group.migrateTo;
        if (arrival == null) return false;
        if (afloat(group)) {
            var captain = vanguard(level, group);
            if (captain == null || !waterArrived(waterBoat(captain), level, arrival)) return false;
            int total = 0, there = 0;
            var travelledArea = new AABB(group.migrateTo).minmax(new AABB(group.center)).inflate(72);
            for (YukkuriEntity e : LoadedGroupMembers.query(level, group.id, travelledArea,
                    m -> group.id.equals(m.settlement().groupId()) && joins(m, group))) {
                total++;
                var hat = e.habitat().waterHome();
                if (hat != null && hat.isAfloat() && hat.protects(e)
                        && hat.position().distanceToSqr(HatBoatRoute.surface(level, arrival)) <= (ARRIVE_RANGE + 2) * (ARRIVE_RANGE + 2)) there++;
            }
            return total > 0 && there * 2 >= total;
        }
        // A frail leader may trail behind: the scout (or the leader when there is none) is the arrival pivot, and the leader
        // counts in the census like everyone else, then follows as a straggler if she is still on the way.
        if (!pivotAtDestination(level, group)) return false;
        int total = 0, there = 0;
        var travelledArea = new AABB(group.migrateTo).minmax(new AABB(group.center)).inflate(72);
        for (YukkuriEntity e : LoadedGroupMembers.query(level, group.id, travelledArea,
                m -> group.id.equals(m.settlement().groupId()) && joins(m, group))) {
            total++;
            if (e.settlement().settledAt(group.migrateTo) || e.distanceToSqr(arrival.getX() + .5, arrival.getY(), arrival.getZ() + .5)
                    <= (ARRIVE_RANGE + 2) * (ARRIVE_RANGE + 2)) there++;
        }
        return total > 0 && there * 2 >= total && dependentYoungSettled(level, group, arrival, travelledArea);
    }

    /**
     * A dependent child does not get a settlement route of its own.  It must nevertheless reach the new ground beside a
     * parent who has actually settled, inherit that real shelter, and only then receive the parent's new settlement
     * address. Unloaded children are intentionally absent from this loaded-world census; no age, death or arrival is inferred.
     */
    private static boolean dependentYoungSettled(ServerLevel level, SettlementData.Group group, BlockPos arrival, AABB travelledArea) {
        for (YukkuriEntity baby : LoadedGroupMembers.query(level, group.id, travelledArea,
                e -> e.isAlive() && group.id.equals(e.settlement().groupId()) && e.childhood().shouldStayWithParents()))
            if (!baby.settlement().settledAt(arrival)) return false;
        return true;
    }

    /** A failed march leaves the current community and its pressure intact; time passing is not arrival. */
    private static void abandon(SettlementData data, SettlementData.Group group, YukkuriEntity caller, long now, String reason) {
        String census = "";
        if (phase(group, now) == Phase.MARCH && caller.level() instanceof ServerLevel level) {
            var travelledArea = new AABB(group.migrateTo).minmax(new AABB(group.center)).inflate(72);
            double gx = group.migrateTo.getX() + .5, gy = group.migrateTo.getY(), gz = group.migrateTo.getZ() + .5;
            int total = 0, near = 0, settled = 0, unsettledNear = 0;
            for (YukkuriEntity member : LoadedGroupMembers.query(level, group.id, travelledArea,
                    e -> group.id.equals(e.settlement().groupId()) && joins(e, group))) {
                member.settlement().recoverDepartureForAbandon(group);
                total++;
                boolean isSettled = member.settlement().settledAt(group.migrateTo);
                boolean isNear = member.distanceToSqr(gx, gy, gz) <= (ARRIVE_RANGE + 2) * (ARRIVE_RANGE + 2);
                if (isSettled) settled++;
                if (isNear) near++;
                if (isNear && !isSettled) unsettledNear++;
            }
            var scout = vanguard(level, group);
            var chief = group.leader == null ? null : level.getEntity(group.leader);
            census = String.format(Locale.ROOT, " members %d near %d settled %d unsettled_near %d vanguard %s vanguard_dist %s leader_dist %s pivot_arrived_at %d",
                    total, near, settled, unsettledNear, group.migrateVanguard,
                    scout == null ? "-" : String.format(Locale.ROOT, "%.0f", Math.sqrt(scout.distanceToSqr(gx, gy, gz))),
                    chief == null ? "-" : String.format(Locale.ROOT, "%.0f", Math.sqrt(chief.distanceToSqr(gx, gy, gz))),
                    group.migrateArrivedAt);
        }
        group.commons.note(now, "migrate_failed", caller.getUUID(), "reason " + reason + " destination " + group.migrateTo.toShortString()
                + " phase " + phase(group,now) + " elapsed " + (now-group.migrateSince) + census);
        releaseTarget(data, group, group.migrateTo);
        LOGGED_PHASES.remove(group);
        group.migrateTo = null; group.migrateWater = null; group.migrateSince = 0; group.migrateRetry = now + NO_SITE_TICKS;
        group.migrateVanguard = null; group.migrateArrivedAt = 0;
        data.setDirty();
    }

    /** Claim an existing empty plan before announcing it; natural ground has no shared site record to reserve. */
    private static boolean reserveTarget(SettlementData data, SettlementData.Group group, BlockPos target) {
        var site = SiteLife.bySpot(data, target);
        if (site == null) return true;
        if (group.id.equals(site.group)) return site.reservable();
        if (!site.vacant()) return false;
        site.group = group.id;
        data.setDirty();
        return true;
    }

    /** Only an empty-site reservation is released; a founded site keeps its owner. */
    private static void releaseTarget(SettlementData data, SettlementData.Group group, BlockPos target) {
        var site = SiteLife.bySpot(data, target);
        if (site != null && group.id.equals(site.group) && site.reservable()) {
            site.group = null;
            data.setDirty();
        }
    }

    /** The new site becomes the centre; stragglers remain members and walk back to it as usual. A new start for the counters. */
    /**
     * Commit only after the new public shore has a real pile.  Reaching a water cell is not enough to move the ledger
     * if the bank changed after the destination scan and can no longer host the shared store.
     */
    static boolean complete(SettlementData data, SettlementData.Group group, YukkuriEntity caller, long now) {
        BlockPos newWaterCommons = null;
        ServerLevel afloatLevel = null;
        if (afloat(group)) {
            if (!(caller.level() instanceof ServerLevel level)) return false;
            afloatLevel = level;
            newWaterCommons = waterCommons(level, group.migrateTo, group.migrateWater);
            if (newWaterCommons == null || !level.setBlock(newWaterCommons, ModBlocks.FORAGE_PILE.get().defaultBlockState(), 3)
                    || !(level.getBlockEntity(newWaterCommons) instanceof ForagePileBlockEntity pile)) return false;
            pile.setApproach(dryApproach(level, newWaterCommons));
        }
        var arrived = SiteLife.bySpot(data, group.migrateTo);
        // The old ground is left to the next settlers; the new one is lived in from now on. Moving onto natural ground
        // leaves the planned site behind too: before, the group kept its old siteId and arrivals re-homed into the old burrows.
        var old = data.site(group.siteId);
        if (old != null && old != arrived) { old.state = SettlementData.SiteState.ABANDONED; old.group = null; }
        if (arrived != null) {
            arrived.state = SettlementData.SiteState.FOUNDED; arrived.group = group.id; group.siteId = arrived.id;
            if(caller.level() instanceof ServerLevel level)SiteLife.orientExistingStores(level,arrived);
        }
        else group.siteId = null;
        if (afloat(group)) {
            // The old bank pile remains a real abandoned stock, like food left in old burrows.  The new group address
            // is a fresh physical pile at the bank reached by its boats.  It was placed before this ledger commit;
            // no invisible cargo transfer occurs.
            group.waterCommons = newWaterCommons;
            // A dependent child shares the parent's physical hat for the arrival census, but cannot drive a second
            // settlement route itself.  Stamp every loaded group member actually protected by a hat at this berth;
            // otherwise a child counted toward the quorum keeps the old origin forever and later becomes an
            // unrouteable "straggler" when it grows up.
            var arrivedWater = group.migrateWater;
            for (YukkuriEntity member : LoadedGroupMembers.query(afloatLevel, group.id,
                    new AABB(arrivedWater).inflate(ARRIVE_RANGE + 2),
                    e -> e.isAlive() && group.id.equals(e.settlement().groupId()))) {
                var hat = member.habitat().waterHome();
                if (hat != null && hat.isAfloat() && hat.protects(member)
                        && hat.position().distanceToSqr(HatBoatRoute.surface(afloatLevel, arrivedWater))
                                <= (ARRIVE_RANGE + 2) * (ARRIVE_RANGE + 2))
                    member.settlement().settleAfloat(group.migrateTo, arrivedWater);
            }
        }
        var c = group.commons;
        c.note(now, "migrate_done", caller.getUUID(), c.migrateReason + " at " + group.migrateTo.toShortString() + " after " + (now - group.migrateSince) + " ticks");
        LOGGED_PHASES.remove(group);
        group.center = group.migrateTo; group.migrateTo = null; group.migrateWater = null; group.migrateSince = 0;
        group.landCommons = null;
        group.migrateVanguard = null; group.migrateArrivedAt = 0;
        group.threat = 0; group.threatPos = null; group.sighted.clear();
        group.gatherUntil = 0; group.snowing = false; group.migrateRetry = now + REVIEW_TICKS;
        // Settling in: the old griefs and failures belong to the old ground; a hunt may be called at once.
        c.deaths.clear(); c.famineDays = c.shortDays = c.failDays = c.huntFails = 0; c.outings = c.fruitful = 0;
        c.settledUntil = now + SETTLE_TICKS; c.pressure100 = 0; c.pressureMain = ""; c.pressureText = "";
        c.hunt.calledAt = 0; c.clearRations();
        data.setDirty();
        caller.showReaction("happy", "hello", 80);
        if (caller.level() instanceof ServerLevel level)
            for (YukkuriEntity m : LoadedGroupMembers.query(level, group.id, new AABB(group.center).inflate(16),
                    e -> e.isAlive() && group.id.equals(e.settlement().groupId()))) m.mind().add(-4, "migrate_settled");
        return true;
    }

    private Migration() {}
}
