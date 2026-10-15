package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.cognition.Cognition;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A led community that lost its leader mourns, then a loaded adult member of good standing takes the seat. */
final class Succession {
    // [original] scoring weights. Body size is deliberately absent: neither a large body nor a DOS earns the seat.
    private static final double ORDER_WEIGHT = 1.0, ABILITY_WEIGHT = .3, AGE_DAY_WEIGHT = 2, AGE_CAP_DAYS = 10, PATCHOULI_BONUS = 10;
    private static final int RADIUS = 48, RETRY_TICKS = 400;

    /** Called by any loaded member about every 5 seconds; one election per group is decided synchronously. */
    static void tick(YukkuriEntity caller, SettlementData.Group group) {
        if (!group.vacant() || !(caller.level() instanceof ServerLevel level)) return;
        var data = SettlementData.get(level);
        long now = level.getGameTime();
        if (group.vacantSince == 0) { group.vacantSince = Math.max(1, now); data.setDirty(); }
        if (now - group.vacantSince < YukkuriConfig.MOURNING_TICKS.get()) {
            if (caller.family().adult() && caller.distanceToSqr(Vec3.atBottomCenterOf(group.center)) <= RADIUS * RADIUS
                    && caller.settlement().orders().mourn(group.vacantSince)) caller.showReaction("teary", "lost_parent", 80);
            return;
        }
        if (now < group.electionRetry) return;
        group.electionRetry = now + RETRY_TICKS;
        var box = new AABB(group.center).inflate(RADIUS);
        var members = level.getEntitiesOfClass(YukkuriEntity.class, box, e -> e.isAlive() && !e.isRemoved()
                && group.id.equals(e.settlement().groupId()) && e.family().adult() && !e.getVariant().isPredator() && !e.getVariant().isHouse());
        YukkuriEntity best = null; double bestScore = -1;
        for (YukkuriEntity candidate : members) {
            var role = group.role(candidate.getUUID());
            if (role == SettlementData.Role.OUTCAST || role == SettlementData.Role.SLAVE) continue;
            double score = score(candidate);
            if (best == null || score > bestScore) { best = candidate; bestScore = score; }
        }
        if (best == null) return; // nobody qualified: stay vacant and try again later
        data.setLeader(group, best.getUUID());
        best.showReaction("happy", "hello", 80);
        for (YukkuriEntity other : members) {
            if (other != best && other.distanceToSqr(best) <= 16 * 16) other.showReaction("surprise", "heard", 60);
        }
    }
    static double score(YukkuriEntity e) {
        double age = Math.min(AGE_CAP_DAYS, e.family().age() / 24000.0);
        return e.life().personality().trait(Trait.ORDER) * ORDER_WEIGHT + Cognition.get(e).ability() * ABILITY_WEIGHT
                + age * AGE_DAY_WEIGHT + (e.getVariant() == YukkuriVariant.PATCHOULI ? PATCHOULI_BONUS : 0);
    }
    private Succession() {}
}
