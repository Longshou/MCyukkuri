package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import static io.github.longshou.mcyukkuri.world.SearchBudget.generation;

import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.rare.RareForms;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.HashSet;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * Moving house (plan 3.4, reworked 2026-10-18 after the user's correction: "migration happens mainly when too many members
 * die or when the group's foraging keeps failing into a famine").
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
 * behind the leader to a site away from the danger, preferring one with meadows; and they <b>settle</b>: each finds or
 * digs a home and puts its mouthful on the new pile, the leader's food check, the death record and the counters start
 * afresh. What the mouths could not carry stays in the old burrows.
 *
 * <p>Lecture and wiki basis: TM03/TM04 (a community takes a red-eyed visitor for a predator's omen and, on Patchouli's
 * judgement, leaves its nest); komicolle 40720 No.11518794 (without rules they use up the stores). The causes, weights
 * and phases are project design.
 */
final class Migration {
    private static final int SIGHT_RANGE = 12, SIGHT_REPEAT = 2400, OMEN_REPEAT = 12000, ATTACK_REPEAT = 200, ARRIVE_RANGE = 8; // [original] pacing
    private static final int SITE_TRIES = 24, SITE_SHELTERS = 3, SITE_THREAT_GAP = 24;
    /** The leader reviews the pressure once a minute; a failed site search waits two minutes. */
    private static final int REVIEW_TICKS = 1200, NO_SITE_TICKS = 2400;
    static final int DISCUSS_TICKS = 600, PREPARE_TICKS = 600, SETTLE_TICKS = 48000;
    static final long DEATH_WINDOW = 72000;

    enum Phase { NONE, DISCUSS, PREPARE, MARCH }

    static boolean moving(SettlementData.Group group) { return group != null && group.migrateTo != null; }
    static Phase phase(SettlementData.Group group, long now) {
        if (!moving(group)) return Phase.NONE;
        long t = now - group.migrateSince;
        return t < DISCUSS_TICKS ? Phase.DISCUSS : t < DISCUSS_TICKS + PREPARE_TICKS ? Phase.PREPARE : Phase.MARCH;
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

    /** About every 5 seconds per loaded member: decay, sightings, the move in progress, and the leader's review. */
    static void tick(YukkuriEntity caller, SettlementData.Group group) {
        if (!(caller.level() instanceof ServerLevel level) || group.kind == SettlementData.Kind.BANDITS) return;
        var data = SettlementData.get(level);
        long now = level.getGameTime();
        decay(data, group, now);
        if (!caller.profile().dangerToYukkuri() && !caller.profile().houseBody()) sight(data, caller, group, now);
        boolean leader = caller.getUUID().equals(group.leader);
        if (moving(group)) {
            var phase = phase(group, now);
            if (leader && phase == Phase.DISCUSS && (now - group.migrateSince) % 300 < 100) caller.showReaction("teary", "migrate_discuss", 80);
            if (now - group.migrateSince >= DISCUSS_TICKS + PREPARE_TICKS + YukkuriConfig.MIGRATE_TIMEOUT.get()) complete(data, group, caller, now);
            else if (leader && phase == Phase.MARCH && arrived(level, group)) complete(data, group, caller, now);
            return;
        }
        if (leader) review(caller, group, now);
    }

    private static void decay(SettlementData data, SettlementData.Group group, long now) {
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
    private static final Map<SettlementData.Group,Long> SEARCH_GENERATIONS = new WeakHashMap<>();
    private static long searchGeneration(SettlementData.Group group) {
        return SEARCH_GENERATIONS.computeIfAbsent(group, ignored->generation());
    }
    static void cancelSearch(YukkuriEntity entity, SettlementData.Group group) {
        if(group!=null&&entity.level() instanceof ServerLevel level) {
            var generation=SEARCH_GENERATIONS.get(group);
            if(generation!=null)SearchBudget.cancel(level,entity,Purpose.PICK_SITE,generation);
        }
    }
    private static void review(YukkuriEntity leader, SettlementData.Group group, long now) {
        if (now < group.migrateRetry || !(leader.level() instanceof ServerLevel level)) return;
        group.migrateRetry = now + REVIEW_TICKS;
        var p = pressure(level, group, leader);
        var c = group.commons;
        c.pressure100 = Math.round(p.total() * 100);
        c.pressureMain = p.main();
        c.pressureText = p.text();
        if (YukkuriConfig.MIGRATE_THRESHOLD.get() <= 0 || p.total() < p.threshold() || now < c.settledUntil
                || Seasons.on(level, group.center, Season.WINTER)) {cancelSearch(leader,group);return;}
        var result=start(leader, group, false, p.main());
        if(result==Result.DEFERRED){group.migrateRetry=now+20;return;}
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
        var lease=SearchBudget.request(level,leader,Purpose.PICK_SITE,searchGeneration(group),SearchBudget.PICK_SITE.plus(new Cost(SettlementData.get(level).sites().size(),0,0)));
        if(lease==null)return Result.DEFERRED;
        try(lease;var scope=lease.bind(leader)) {
            long now = level.getGameTime();
            BlockPos site = emptySite(level, group);
            boolean hungry = "famine".equals(reason) || "worn_out".equals(reason);
            if (site == null) site = pickSite(level, leader, group, hungry);
            if (site == null) return Result.NOT_FOUND;
            var data = SettlementData.get(level);
            var c = group.commons;
            group.migrateTo = site; group.migrateSince = now;
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

    /** A planned site nobody lives on, 24-80 blocks away, loaded and clear of the danger: the plaza is the new centre. */
    private static BlockPos emptySite(ServerLevel level, SettlementData.Group group) {
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (var site : SettlementData.get(level).sites()) {
            SearchBudget.probe(level);
            if (!site.vacant() || site.id.equals(group.siteId)) continue;
            var plaza = SiteLife.anchor(site, io.github.longshou.mcyukkuri.settlement.site.SiteSlot.Kind.PLAZA);
            var plan = site.plan();
            if (plaza == null || plan == null) continue;
            double distance = Math.sqrt(plaza.distSqr(group.center));
            if (distance < 24 || distance > 80 || !io.github.longshou.mcyukkuri.settlement.site.SiteBuilder.loaded(level, plan)) continue;
            if (group.threatPos != null && plaza.distSqr(group.threatPos) < SITE_THREAT_GAP * SITE_THREAT_GAP) continue;
            if (distance < nearest) { nearest = distance; best = plaza; }
        }
        return best;
    }

    /**
     * Away from the nearest threat (+-60 degrees) when there is one, otherwise any bearing; 32-48 blocks out, loaded,
     * standable, with room to dig in. Of the first three such spots the one with most grazing around it wins, so a
     * community fleeing a famine walks towards meadows.
     */
    private static BlockPos pickSite(ServerLevel level, YukkuriEntity leader, SettlementData.Group group, boolean hungry) {
        BlockPos from = group.center, danger = group.threatPos;
        boolean fleeing = danger != null && group.threat > 0;
        double base = leader.getRandom().nextDouble() * Math.PI * 2;
        if (fleeing && (danger.getX() != from.getX() || danger.getZ() != from.getZ())) base = Math.atan2(from.getZ() - danger.getZ(), from.getX() - danger.getX());
        double spread = fleeing ? Math.PI * 2 / 3 : Math.PI * 2;
        int min = YukkuriConfig.MIGRATE_MIN_DISTANCE.get(), max = Math.max(min, YukkuriConfig.MIGRATE_MAX_DISTANCE.get());
        BlockPos best = null;
        int bestGrass = -1, found = 0;
        for (int tries = 0; tries < SITE_TRIES && found < 3; tries++) {
            SearchBudget.probe(level);
            double angle = base + (leader.getRandom().nextDouble() - .5) * spread, distance = min + leader.getRandom().nextDouble() * (max - min);
            BlockPos column = from.offset((int)Math.round(Math.cos(angle) * distance), 0, (int)Math.round(Math.sin(angle) * distance));
            if (!level.hasChunkAt(column)) continue;
            if (danger != null && column.distSqr(danger) < SITE_THREAT_GAP * SITE_THREAT_GAP) continue;
            for (int dy = 10; dy >= -12; dy--) {
                BlockPos spot = column.offset(0, dy, 0);
                SearchBudget.probe(level);
                if (!leader.life().dryStand(spot) || !roomToSettle(level, spot)) continue;
                found++;
                int grass = hungry ? grazing(level, spot) : 0;
                if (grass > bestGrass) { bestGrass = grass; best = spot.immutable(); }
                break;
            }
        }
        return best;
    }
    private static int grazing(ServerLevel level, BlockPos spot) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-10, -3, -10), spot.offset(10, 3, 10))) {
            SearchBudget.probe(level);
            if (Grazing.edible(level.getBlockState(pos))) count++;
        }
        return count;
    }
    private static boolean roomToSettle(ServerLevel level, BlockPos spot) {
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-8, -2, -8), spot.offset(8, 2, 8)))
            if (NaturalShelter.enclosed(level, pos) && ++found >= SITE_SHELTERS) return true;
        return false;
    }

    /**
     * Who takes part: adults and children (子油) free of a home claim and of a human owner, not outcasts or slaves, not the
     * wild predators. Babies (赤油) stay with the family-nest logic.
     */
    static boolean joins(YukkuriEntity e, SettlementData.Group group) {
        var role = group.role(e.getUUID());
        return e.isAlive() && (e.family().adult() || e.family().stage() == io.github.longshou.mcyukkuri.family.FamilyLife.Stage.JUVENILE)
                && (role == SettlementData.Role.MEMBER || role == SettlementData.Role.LEADER)
                && !e.profile().dangerToYukkuri() && !e.profile().houseBody() && !SettlementEvents.homeClaimHeld(e) && SettlementEvents.unowned(e);
    }

    /** A taking-part member who has not reached the new site yet: household chores at the old burrow wait. */
    static boolean underway(YukkuriEntity e) {
        var group = e.settlement().group();
        return moving(group) && joins(e, group) && e.distanceToSqr(group.migrateTo.getX() + .5, group.migrateTo.getY(), group.migrateTo.getZ() + .5) > ARRIVE_RANGE * ARRIVE_RANGE;
    }

    /** The leader stands at the site and at least half of the loaded settlers around it have arrived. */
    private static boolean arrived(ServerLevel level, SettlementData.Group group) {
        if (!(level.getEntity(group.leader) instanceof YukkuriEntity chief) || chief.distanceToSqr(group.migrateTo.getX() + .5, group.migrateTo.getY(), group.migrateTo.getZ() + .5) > ARRIVE_RANGE * ARRIVE_RANGE) return false;
        int total = 0, there = 0;
        for (YukkuriEntity e : LoadedGroupMembers.query(level, group.id, new AABB(group.migrateTo).inflate(72),
                m -> group.id.equals(m.settlement().groupId()) && joins(m, group))) {
            total++;
            if (e.distanceToSqr(group.migrateTo.getX() + .5, group.migrateTo.getY(), group.migrateTo.getZ() + .5) <= (ARRIVE_RANGE + 2) * (ARRIVE_RANGE + 2)) there++;
        }
        return there * 2 >= total;
    }

    /** The new site becomes the centre; stragglers remain members and walk back to it as usual. A new start for the counters. */
    static void complete(SettlementData data, SettlementData.Group group, YukkuriEntity caller, long now) {
        var arrived = SiteLife.bySpot(data, group.migrateTo);
        // The old ground is left to the next settlers; the new one is lived in from now on. Moving onto natural ground
        // leaves the planned site behind too: before, the group kept its old siteId and arrivals re-homed into the old burrows.
        var old = data.site(group.siteId);
        if (old != null && old != arrived) { old.state = SettlementData.SiteState.ABANDONED; old.group = null; }
        if (arrived != null) { arrived.state = SettlementData.SiteState.FOUNDED; arrived.group = group.id; group.siteId = arrived.id; }
        else group.siteId = null;
        var c = group.commons;
        c.note(now, "migrate_done", caller.getUUID(), c.migrateReason + " at " + group.migrateTo.toShortString() + " after " + (now - group.migrateSince) + " ticks");
        group.center = group.migrateTo; group.migrateTo = null; group.migrateSince = 0;
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
