package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;
import io.github.longshou.mcyukkuri.status.GameplayLog;

import java.util.Map;
import java.util.WeakHashMap;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriGroundNavigation;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.family.FamilyLife;

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
    /** The others leave the old centre this long after the march begins, so the scout is really ahead. */
    static final int VANGUARD_HEAD_START = 160;
    static final long DEATH_WINDOW = 72000;

    private static final int REGROUP_TICKS = 600;
    enum Phase { NONE, DISCUSS, PREPARE, MARCH, REGROUP, PICK_SITE }
    enum Leg { DIRECT, REGROUP, DETOUR, PICK_SITE, REPLACEMENT, PICK_NEARBY, NEARBY }
    private record LoggedPhase(long startedAt, Phase phase) {}
    private static final Map<SettlementData.Group, LoggedPhase> LOGGED_PHASES = new WeakHashMap<>();

    static boolean moving(SettlementData.Group group) { return group != null && group.migrateTo != null; }
    static Phase phase(SettlementData.Group group, long now) {
        if (!moving(group)) return searching(group) ? Phase.PICK_SITE : Phase.NONE;
        long t = now - group.migrateSince;
        if (t < DISCUSS_TICKS) return Phase.DISCUSS;
        if (t < DISCUSS_TICKS + PREPARE_TICKS) return Phase.PREPARE;
        return switch (group.migrateLeg) {
            case REGROUP -> Phase.REGROUP;
            case PICK_SITE, PICK_NEARBY -> Phase.PICK_SITE;
            default -> Phase.MARCH;
        };
    }

    static boolean onJourney(SettlementData.Group group, long now) {
        return moving(group) && now >= group.migrateSince + DISCUSS_TICKS + PREPARE_TICKS;
    }

    static BlockPos walkTarget(SettlementData.Group group) {
        return switch (group.migrateLeg) {
            case REGROUP -> group.migrateRegroupAt;
            case PICK_SITE, PICK_NEARBY -> null;
            default -> group.migrateTo;
        };
    }

    static List<YukkuriEntity> members(ServerLevel level, SettlementData.Group group) {
        var area = new AABB(group.center);
        if (moving(group)) area = area.minmax(new AABB(group.migrateTo));
        return LoadedGroupMembers.query(level, group.id, area.inflate(96), e -> e.isAlive()
                && (joins(e, group) || e.childhood().shouldStayWithParents()));
    }

    static boolean walks(YukkuriEntity entity, SettlementData.Group group) {
        return !entity.isPassenger() && (joins(entity, group) || !ParentCarry.needsCarry(entity));
    }

    static boolean readyToPlan(YukkuriEntity entity) {
        return ((YukkuriGroundNavigation)entity.getNavigation()).canPlanApproach();
    }

    /** The second leg asks for a different approach to the same ground. */
    static Set<BlockPos> approaches(YukkuriEntity entity, BlockPos site, boolean detour) {
        var stands = new LinkedHashSet<BlockPos>();
        if (!detour && entity.life().dryStand(site)) stands.add(site);
        int radius = detour ? 5 : 2;
        for (var direction : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) {
            var pos = site.relative(direction, radius).above(dy);
            if (entity.life().dryStand(pos)) stands.add(pos);
        }
        return stands;
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
            var phase = phase(group, now);
            if (phase == Phase.DISCUSS || phase == Phase.PREPARE || vanguard(level, group) == null) refreshVanguard(level, group);
            logPhase(level, group, phase, now);
            if (leader && phase == Phase.DISCUSS && (now - group.migrateSince) % 300 < 100) caller.showReaction("teary", "migrate_discuss", 80);
            if (!onJourney(group, now) || phase == Phase.PICK_SITE) return;
            var members = members(level, group);
            if (phase == Phase.REGROUP) {
                if (now - group.migrateLegAt >= REGROUP_TICKS || members.stream().filter(m -> walks(m, group))
                        .allMatch(m -> near(m, group.migrateRegroupAt))) setLeg(data, group, Leg.DETOUR, now, "alternate_approach");
                return;
            }
            var site = SiteLife.bySpot(data, group.migrateTo);
            if (!level.hasChunkAt(group.migrateTo) || site != null && !group.migrateTo.equals(group.center)
                    && (!group.id.equals(site.group) || !site.reservable())) {
                recover(level, data, group, caller, members, now, "destination_unavailable");
                return;
            }
            trackPioneer(level, group, phase, now);
            if (arrived(level, members, group)) {
                complete(data, group, caller, now, Homes.migrationHouseholds(members, group));
                return;
            }
            if (expired(group, now)) recover(level, data, group, caller, members, now, "march_deadline");
            return;
        }
        if (leader) review(caller, group, now, true);
    }

    private static void logPhase(ServerLevel level, SettlementData.Group group, Phase phase, long now) {
        var logged = new LoggedPhase(group.migrateSince, phase);
        if (logged.equals(LOGGED_PHASES.put(group, logged))) return;
        GameplayLog.world(level, "settlement", "migration phase={} group={} origin={} destination={} reason={} elapsed={}",
                phase, group.id, group.center, group.migrateTo, reason(group), moving(group) ? now - group.migrateSince : 0);
    }

    static boolean expired(SettlementData.Group group, long now) {
        long deadline = Math.max(group.migrateLegAt, group.migrateSince + DISCUSS_TICKS + PREPARE_TICKS) + YukkuriConfig.MIGRATE_TIMEOUT.get();
        return now >= deadline;
    }

    private static boolean near(YukkuriEntity member, BlockPos point) {
        return member.distanceToSqr(Vec3.atBottomCenterOf(point)) <= ARRIVE_RANGE * ARRIVE_RANGE;
    }

    private static void setLeg(SettlementData data, SettlementData.Group group, Leg leg, long now, String reason) {
        group.migrateLeg = leg;
        group.migrateLegAt = now;
        group.migrateArrivedAt = 0;
        group.commons.migrateReason = reason;
        LOGGED_PHASES.remove(group);
        data.setDirty();
    }

    private static void recover(ServerLevel level, SettlementData data, SettlementData.Group group, YukkuriEntity caller,
                                List<YukkuriEntity> members, long now, String reason) {
        GameplayLog.world(level, "settlement", "migration leg ended group={} leg={} destination={} reason={}",
                group.id, group.migrateLeg, group.migrateTo, reason);
        switch (group.migrateLeg) {
            case DIRECT -> {
                var last = members.stream().filter(m -> walks(m, group) && !m.settlement().reached(group))
                        .max(Comparator.comparingDouble(m -> m.distanceToSqr(Vec3.atBottomCenterOf(group.migrateTo)))).orElse(caller);
                group.migrateRegroupAt = last.blockPosition();
                setLeg(data, group, Leg.REGROUP, now, reason);
                caller.showReaction("teary", "wait_family", 80);
            }
            case DETOUR -> setLeg(data, group, Leg.PICK_SITE, now, reason);
            case REPLACEMENT -> setLeg(data, group, Leg.PICK_NEARBY, now, reason);
            case NEARBY -> abandon(data, group, caller, now, reason);
            default -> throw new IllegalStateException("No march to recover from " + group.migrateLeg);
        }
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

    /** The vanguard (leader if none) has reached destination ground. */
    private static boolean pivotAtDestination(ServerLevel level, SettlementData.Group group) {
        var pivot = vanguard(level, group);
        if (pivot == null && group.leader != null && level.getEntity(group.leader) instanceof YukkuriEntity chief && chief.isAlive()
                && group.id.equals(chief.settlement().groupId())) pivot = chief;
        if (pivot == null || group.migrateTo == null) return false;
        return pivot.settlement().reached(group);
    }

    private static void trackPioneer(ServerLevel level, SettlementData.Group group, Phase phase, long now) {
        refreshVanguard(level, group);
        if (phase != Phase.MARCH || group.migrateArrivedAt > 0 || !pivotAtDestination(level, group)) return;
        group.migrateArrivedAt = now;
        SettlementData.get(level).setDirty();
        GameplayLog.world(level, "settlement", "migration pivot arrived group={} destination={} vanguard={} leader={} elapsed={}",
                group.id, group.migrateTo, group.migrateVanguard, group.leader, now - group.migrateSince);
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
            if (home == null || !home.usable(m)) homeless++;
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
    static String reason(SettlementData.Group group) {
        var search = SEARCHES.get(group);
        return search == null ? group.commons.migrateReason : search.reason;
    }
    static void cancelSearch(YukkuriEntity entity, SettlementData.Group group) {
        var search = SEARCHES.get(group);
        // A leaving member must not discard the leader's in-progress search.
        if (search != null && search.ownedBy(entity)) {
            search.cancel(); SEARCHES.remove(group);
            LOGGED_PHASES.remove(group);
        }
    }
    static void continueSearch(YukkuriEntity leader, SettlementData.Group group, long now) {
        if (searching(group)) review(leader, group, now, false);
    }

    static void continueRecovery(ServerLevel level, SettlementData.Group group, YukkuriEntity caller, long now) {
        var data = SettlementData.get(level);
        boolean nearby = group.migrateLeg == Leg.PICK_NEARBY;
        logPhase(level, group, Phase.PICK_SITE, now);
        var search = SEARCHES.get(group);
        if (search != null && !search.ownedBy(caller)) {
            search.cancel();
            SEARCHES.remove(group);
            search = null;
        }
        if (search == null) {
            var excluded = new HashSet<BlockPos>();
            excluded.add(group.migrateTo);
            excluded.add(group.migrateFirstSite);
            var points = nearby ? members(level, group).stream().filter(member -> walks(member, group))
                    .map(YukkuriEntity::blockPosition).distinct().filter(point -> point.distSqr(group.center) > ARRIVE_RANGE * ARRIVE_RANGE)
                    .sorted(Comparator.comparingDouble(group.migrateTo::distSqr)).toList() : null;
            search = new MigrationSearch(level, caller, group, true, group.commons.migrateReason, excluded, points);
            SEARCHES.put(group, search);
        }
        var result = search.advance(level, caller, group, group.migrateLegAt + MigrationSearch.SEARCH_TICKS);
        if (result == Result.DEFERRED) return;
        SEARCHES.remove(group);
        search.cancel();
        if (result == Result.FOUND && reserveTarget(data, group, search.result())) {
            releaseTarget(data, group, group.migrateTo);
            group.migrateTo = search.result();
            setLeg(data, group, nearby ? Leg.NEARBY : Leg.REPLACEMENT, now, nearby ? "nearby_ground" : "replacement_site");
        } else if (nearby) abandon(data, group, caller, now, search.failure());
        else setLeg(data, group, Leg.PICK_NEARBY, now, search.failure());
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
            if (sampled && !pending.forced && leader.level() instanceof ServerLevel level) {
                var p = recordPressure(level, group, leader);
                if (p.total() < p.threshold()) {
                    pending.cancel(); SEARCHES.remove(group); group.migrateRetry = now + REVIEW_TICKS;
                    return;
                }
            }
            var result = start(leader, group, pending.forced, pending.reason);
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
            search = new MigrationSearch(level, leader, group, force, reason, Set.of(), null);
            SEARCHES.put(group, search);
            logPhase(level, group, Phase.PICK_SITE, level.getGameTime());
        }
        var result = search.advance(level, leader, group, Long.MAX_VALUE);
        if (result == Result.DEFERRED) return result;
        SEARCHES.remove(group); search.cancel();
        if (result == Result.NOT_FOUND) {
            LOGGED_PHASES.remove(group);
            group.commons.migrateReason = search.failure();
            GameplayLog.world(level, "settlement", "migration site refused group={} reason={} {}", group.id, search.failure(), search.progress());
            SettlementData.get(level).setDirty();
            return result;
        }
        BlockPos site = search.result();
        reason = search.reason; force = search.forced;
        long now = level.getGameTime();
        var data = SettlementData.get(level);
        if (!reserveTarget(data, group, site)) return Result.NOT_FOUND;
        var c = group.commons;
        group.migrateTo = site; group.migrateSince = now;
        group.migrateVanguard = null;
        group.migrateFirstSite = site;
        group.migrateRegroupAt = null;
        group.migrateParty.clear();
        for (var member : members(level, group)) group.migrateParty.add(member.getUUID());
        setLeg(data, group, Leg.DIRECT, now, reason);
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
        return e.isAlive() && (e.family().adult() || e.family().stage() == FamilyLife.Stage.JUVENILE && e.family().readyForOwnNest())
                && (role == SettlementData.Role.MEMBER || role == SettlementData.Role.LEADER)
                && !e.profile().dangerToYukkuri() && e.status().identity().wild() && !SettlementEvents.homeClaimHeld(e);
    }

    /** Old-home chores wait until the member has reached the destination. */
    static boolean underway(YukkuriEntity e) {
        var group = e.settlement().group();
        if (group == null) return false;
        return moving(group) ? (joins(e, group) || e.settlement().canCatchUpOnFoot()) && e.settlement().needsMarch(group)
                : e.settlement().straggler(group);
    }

    /** A living participant remains due until it reaches the ground or actually leaves the party. */
    private static boolean arrived(ServerLevel level, List<YukkuriEntity> members, SettlementData.Group group) {
        var family = FamilyData.get(level);
        for (var party = group.migrateParty.iterator(); party.hasNext();) {
            var id = party.next();
            var person = family.person(id);
            String dropped = person != null && person.status == FamilyData.Status.DEAD ? "dead"
                    : person != null && person.status == FamilyData.Status.REMOVED ? "removed"
                    : group.role(id) == SettlementData.Role.FREE ? "left_group"
                    : level.getEntity(id) instanceof YukkuriEntity member && !joins(member, group)
                    && !member.childhood().shouldStayWithParents() ? "no_longer_eligible" : null;
            if (dropped == null) continue;
            party.remove();
            SettlementData.get(level).setDirty();
            GameplayLog.world(level, "settlement", "migration party dropped group={} member={} reason={}", group.id, id, dropped);
        }
        boolean allArrived = true;
        for (var member : members) {
            if (member.settlement().reached(group)) continue;
            allArrived = false;
            member.settlement().logMarchDelay(group);
        }
        for (var id : group.migrateParty) {
            if (level.getEntity(id) instanceof YukkuriEntity member && member.settlement().reached(group)) continue;
            allArrived = false;
        }
        return allArrived;
    }

    /** A failed march leaves the current community and its pressure intact; time passing is not arrival. */
    private static void abandon(SettlementData data, SettlementData.Group group, YukkuriEntity caller, long now, String reason) {
        String census = "";
        if (onJourney(group, now) && caller.level() instanceof ServerLevel level) {
            var travelledArea = new AABB(group.migrateTo).minmax(new AABB(group.center)).inflate(72);
            double gx = group.migrateTo.getX() + .5, gy = group.migrateTo.getY(), gz = group.migrateTo.getZ() + .5;
            int total = 0, near = 0, settled = 0, unsettledNear = 0;
            for (YukkuriEntity member : LoadedGroupMembers.query(level, group.id, travelledArea,
                    e -> group.id.equals(e.settlement().groupId()) && joins(e, group))) {
                member.settlement().recoverDepartureForAbandon(group);
                total++;
                boolean isSettled = member.settlement().reached(group);
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
        group.migrateTo = null; group.migrateSince = 0; group.migrateRetry = now + NO_SITE_TICKS;
        group.migrateVanguard = null; group.migrateArrivedAt = 0;
        group.commons.migrateReason = reason;
        group.migrateParty.clear();
        data.setDirty();
    }

    /** Claim an existing empty plan before announcing it; natural ground has no shared site record to reserve. */
    private static boolean reserveTarget(SettlementData data, SettlementData.Group group, BlockPos target) {
        if (target.equals(group.center)) return true;
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

    /** Every remaining participant has stood on the destination ground. */
    private static void complete(SettlementData data, SettlementData.Group group, YukkuriEntity caller, long now, int households) {
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
        var c = group.commons;
        c.note(now, "migrate_done", caller.getUUID(), c.migrateReason + " at " + group.migrateTo.toShortString()
                + " after " + (now - group.migrateSince) + " ticks households=" + households);
        LOGGED_PHASES.remove(group);
        group.center = group.migrateTo; group.migrateTo = null; group.migrateSince = 0;
        group.landCommons = null;
        group.migrateVanguard = null; group.migrateArrivedAt = 0;
        group.migrateParty.clear();
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
    }

    private Migration() {}
}
