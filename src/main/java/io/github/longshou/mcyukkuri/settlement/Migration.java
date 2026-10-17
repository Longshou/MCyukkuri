package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.HomeClaim;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.AABB;

/**
 * Rumours and moving house (plan 3.4). Predator sightings, attacks and scout reports pile up as threat; a led community whose
 * leader believes them walks to a new site away from the danger. Stores, shelters and seasons are not carried over: the
 * food stays in the old burrows and the settlers must stock up again, so any hardship comes from the existing mechanisms.
 */
final class Migration {
    private static final int SIGHT_RANGE = 12, SIGHT_REPEAT = 2400, ATTACK_REPEAT = 200, RETRY_TICKS = 600, ARRIVE_RANGE = 8; // [original] pacing
    private static final int SITE_TRIES = 24, SITE_SHELTERS = 3, SITE_THREAT_GAP = 24;

    static boolean moving(SettlementData.Group group) { return group != null && group.migrateTo != null; }

    /** Reserved for the red-eyed visitor of plan 4.2: an omen weighs like an attack on a member. Not called yet. */
    static void omen(ServerLevel level, SettlementData.Group group, BlockPos pos) {
        SettlementData.get(level).reportThreat(group, pos, level.getGameTime(), 2);
    }

    /** A member was hurt (weight 2) or killed (weight 3) by a predator: strong evidence. */
    static void attacked(YukkuriEntity victim, DamageSource source, boolean fatal) {
        if (!(victim.level() instanceof ServerLevel level) || !(source.getEntity() instanceof YukkuriEntity attacker)
                || !attacker.getVariant().isPredator() || victim.getVariant().isPredator()) return;
        var group = victim.settlement().group();
        if (group == null || group.kind == SettlementData.Kind.BANDITS) return;
        long now = level.getGameTime();
        if (!fatal && now - group.attackedAt < ATTACK_REPEAT) return;
        group.attackedAt = now;
        SettlementData.get(level).reportThreat(group, attacker.blockPosition(), now, fatal ? 3 : 2);
    }

    /** About every 5 seconds per loaded member: decay, sightings, and (leader / anyone on timeout) the migration decisions. */
    static void tick(YukkuriEntity caller, SettlementData.Group group) {
        if (!(caller.level() instanceof ServerLevel level) || group.kind == SettlementData.Kind.BANDITS) return;
        var data = SettlementData.get(level);
        long now = level.getGameTime();
        decay(data, group, now);
        if (!caller.getVariant().isPredator() && !caller.getVariant().isHouse()) sight(data, caller, group, now);
        if (moving(group)) {
            if (now - group.migrateSince >= YukkuriConfig.MIGRATE_TIMEOUT.get()) complete(data, group, caller, now);
            else if (caller.getUUID().equals(group.leader) && arrived(level, group)) complete(data, group, caller, now);
            return;
        }
        if (caller.getUUID().equals(group.leader)) start(caller, group, false);
    }

    private static void decay(SettlementData data, SettlementData.Group group, long now) {
        if (group.threat <= 0) return;
        if (now - Math.max(group.threatTime, group.decayAt) >= YukkuriConfig.THREAT_DECAY_TICKS.get()) {
            group.threat--; group.decayAt = now; data.setDirty();
        }
    }

    /** One count per predator per group per {@link #SIGHT_REPEAT} ticks. */
    private static void sight(SettlementData data, YukkuriEntity caller, SettlementData.Group group, long now) {
        if (group.sighted.size() > 32) group.sighted.values().removeIf(time -> now - time >= SIGHT_REPEAT);
        var seen = caller.level().getEntitiesOfClass(YukkuriEntity.class, caller.getBoundingBox().inflate(SIGHT_RANGE),
                p -> p.isAlive() && p.getVariant().isPredator() && !group.id.equals(p.settlement().groupId()) && caller.hasLineOfSight(p));
        for (YukkuriEntity predator : seen) {
            Long last = group.sighted.get(predator.getUUID());
            if (last != null && now - last < SIGHT_REPEAT) continue;
            group.sighted.put(predator.getUUID(), now);
            data.reportThreat(group, predator.blockPosition(), now);
            caller.showReaction("surprise", "afraid", 60);
            return;
        }
    }

    /** Leader side. Without force the threat threshold, a retry gap and a personality roll apply; a failed site search keeps the threat. */
    static boolean start(YukkuriEntity leader, SettlementData.Group group, boolean force) {
        if (!(leader.level() instanceof ServerLevel level) || group.kind == SettlementData.Kind.BANDITS || moving(group)
                || !leader.getUUID().equals(SettlementData.get(level).activeLeader(group)) || !leader.isAlive() || !leader.family().adult()
                || leader.isPanicking() || leader.life().isSleeping() || !group.id.equals(leader.settlement().groupId())
                || !level.hasChunkAt(group.center)) return false;
        long now = level.getGameTime();
        if (!force) {
            int threshold = YukkuriConfig.MIGRATE_THRESHOLD.get();
            if (threshold <= 0 || group.threat < threshold || now < group.migrateRetry) return false;
            group.migrateRetry = now + RETRY_TICKS;
            // Patchouli believes rumours more readily; boldness resists them [original].
            double chance = .5 + ("patchouli".equals(leader.getVariant().dialogueSpecies()) ? .35 : 0)
                    - (leader.life().personality().trait(Trait.BOLDNESS) - 50) / 200.0;
            if (leader.getRandom().nextDouble() >= Math.max(.1, Math.min(1, chance))) return false;
        }
        BlockPos site = emptySite(level, group);
        if (site == null) site = pickSite(level, leader, group);
        if (site == null) return false;
        var data = SettlementData.get(level);
        group.migrateTo = site; group.migrateSince = now; data.setDirty();
        leader.showReaction("surprise", "afraid", 80);
        for (var player : level.players())
            if (!player.isSpectator() && player.distanceToSqr(leader) <= 24 * 24)
                player.sendSystemMessage(Component.translatable("message.mcyukkuri.settlement.migrate", leader.getName()));
        for (YukkuriEntity other : level.getEntitiesOfClass(YukkuriEntity.class, leader.getBoundingBox().inflate(32),
                e -> e != leader && e.isAlive() && group.id.equals(e.settlement().groupId())))
            other.showReaction("surprise", "heard", 60);
        return true;
    }

    /** A planned site nobody lives on, 24-80 blocks away, loaded and clear of the danger: the plaza is the new centre. */
    private static BlockPos emptySite(ServerLevel level, SettlementData.Group group) {
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (var site : SettlementData.get(level).sites()) {
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

    /** Away from the nearest threat (+-60 degrees), 32-48 blocks out, loaded, standable, with room to dig in. */
    private static BlockPos pickSite(ServerLevel level, YukkuriEntity leader, SettlementData.Group group) {
        BlockPos from = group.center, danger = group.threatPos;
        double base = leader.getRandom().nextDouble() * Math.PI * 2;
        if (danger != null && (danger.getX() != from.getX() || danger.getZ() != from.getZ())) base = Math.atan2(from.getZ() - danger.getZ(), from.getX() - danger.getX());
        int min = YukkuriConfig.MIGRATE_MIN_DISTANCE.get(), max = Math.max(min, YukkuriConfig.MIGRATE_MAX_DISTANCE.get());
        for (int tries = 0; tries < SITE_TRIES; tries++) {
            double angle = base + (leader.getRandom().nextDouble() - .5) * (Math.PI * 2 / 3), distance = min + leader.getRandom().nextDouble() * (max - min);
            BlockPos column = from.offset((int)Math.round(Math.cos(angle) * distance), 0, (int)Math.round(Math.sin(angle) * distance));
            if (!level.hasChunkAt(column)) continue;
            if (danger != null && column.distSqr(danger) < SITE_THREAT_GAP * SITE_THREAT_GAP) continue;
            for (int dy = 10; dy >= -12; dy--) {
                BlockPos spot = column.offset(0, dy, 0);
                if (!leader.life().dryStand(spot) || !roomToSettle(level, spot)) continue;
                return spot.immutable();
            }
        }
        return null;
    }
    private static boolean roomToSettle(ServerLevel level, BlockPos spot) {
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-8, -2, -8), spot.offset(8, 2, 8)))
            if (NaturalShelter.enclosed(level, pos) && ++found >= SITE_SHELTERS) return true;
        return false;
    }

    /** Adults who take part: free of a home claim and of a human owner, not outcasts or slaves, not the wild predators. */
    static boolean joins(YukkuriEntity e, SettlementData.Group group) {
        var role = group.role(e.getUUID());
        return e.isAlive() && e.family().adult() && (role == SettlementData.Role.MEMBER || role == SettlementData.Role.LEADER)
                && !e.getVariant().isPredator() && !e.getVariant().isHouse() && !HomeClaim.holds(e) && WildEvents.unowned(e);
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
        for (YukkuriEntity e : level.getEntitiesOfClass(YukkuriEntity.class, new AABB(group.migrateTo).inflate(72),
                m -> group.id.equals(m.settlement().groupId()) && joins(m, group))) {
            total++;
            if (e.distanceToSqr(group.migrateTo.getX() + .5, group.migrateTo.getY(), group.migrateTo.getZ() + .5) <= (ARRIVE_RANGE + 2) * (ARRIVE_RANGE + 2)) there++;
        }
        return there * 2 >= total;
    }

    /** The new site becomes the centre; stragglers remain members and walk back to it as usual. */
    static void complete(SettlementData data, SettlementData.Group group, YukkuriEntity caller, long now) {
        var arrived = SiteLife.bySpot(data, group.migrateTo);
        if (arrived != null) {
            // The old ground is left to the next settlers; the new one is lived in from now on.
            var old = data.site(group.siteId);
            if (old != null && old != arrived) { old.state = SettlementData.SiteState.ABANDONED; old.group = null; }
            arrived.state = SettlementData.SiteState.FOUNDED; arrived.group = group.id; group.siteId = arrived.id;
        }
        group.center = group.migrateTo; group.migrateTo = null; group.migrateSince = 0;
        group.threat = 0; group.threatPos = null; group.sighted.clear();
        group.gatherUntil = 0; group.snowing = false; group.migrateRetry = now + RETRY_TICKS;
        data.setDirty();
        caller.showReaction("happy", "hello", 80);
    }

    private Migration() {}
}
