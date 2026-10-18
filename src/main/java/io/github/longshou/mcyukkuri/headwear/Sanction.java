package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * "Sanction" (制裁) is killing (komicolle 40720 No.11518829): yukkuri kill those who cannot take it easy, and whether one can
 * depends on having a whole accessory. Executors are real adults that see the accused; they declare, then bite until the
 * accused dies, escapes or the executor itself gives way. Nothing is resolved remotely.
 *
 * <p>Built-in grounds (project design): a member of one's own settlement in the non-yukkuri frenzy, and a hatless outcast of
 * one's own settlement that keeps coming back. Further clan rules (theft, killing kin, defying the leader) are judged by the
 * settlement rules and handed in through {@link #request}. Pets, owned, house and predator yukkuri are never sanctioned.
 */
public final class Sanction extends Goal {
    public record Case(UUID target, String reason, UUID accuser, long since) {}
    private static final Map<ServerLevel, Map<UUID, Case>> CASES = new WeakHashMap<>();
    /** Bite strength and pacing; a bite tears the skin (InjuryModel.kind) so the body really empties. */
    public static final float BITE = 3f;
    public static final int BITE_INTERVAL = 25, GIVE_UP_TICKS = 2400, MAX_EXECUTORS = 2;

    private final YukkuriEntity entity;
    private YukkuriEntity target;
    private String reason = "";
    private int ticks, biteWait, declareTicks, scanWait;
    private boolean declared, biting;

    public Sanction(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        scanWait = 100 + entity.getRandom().nextInt(200);
    }
    public static boolean enabled() { return YukkuriConfig.SANCTION.get(); }
    private static Map<UUID, Case> cases(ServerLevel level) { return CASES.computeIfAbsent(level, k -> new HashMap<>()); }

    // ---- interface for settlement rules ------------------------------------------------------------------------------
    /**
     * Ask the community to sanction {@code target} for {@code reason} (e.g. "theft", "killed_kin", "defied_leader"). The accuser,
     * when able, takes part; up to {@link #MAX_EXECUTORS} executors in all are drawn from adults that see the accused.
     * @return true when at least one executor took the case.
     */
    public static boolean request(YukkuriEntity target, String reason, YukkuriEntity accuser) {
        if (!enabled() || !(target.level() instanceof ServerLevel level) || !eligibleTarget(target)) return false;
        int taken = executorsOf(target);
        if (accuser != null && taken < MAX_EXECUTORS && executor(accuser, target) && accuser.sanction().take(target, reason)) taken++;
        for (var other : level.getEntitiesOfClass(YukkuriEntity.class, target.getBoundingBox().inflate(10),
                e -> e != target && e != accuser && executor(e, target) && e.hasLineOfSight(target)).stream()
                .sorted(Comparator.comparingInt((YukkuriEntity e) -> -e.life().personality().aggression())).toList()) {
            if (taken >= MAX_EXECUTORS) break;
            if (other.sanction().take(target, reason)) taken++;
        }
        if (taken == 0) return false;
        cases(level).put(target.getUUID(), new Case(target.getUUID(), reason, accuser == null ? null : accuser.getUUID(), level.getGameTime()));
        MCYukkuri.LOGGER.info("[sanction] {} #{} sanctioned for {} ({} executors)", target.getVariant().assetId(), target.getId(), reason, taken);
        return true;
    }
    /** The open case against this individual, or null. */
    public static Case judged(YukkuriEntity target) {
        return target.level() instanceof ServerLevel level ? cases(level).get(target.getUUID()) : null;
    }
    /** Pets, owned, house and predator yukkuri are outside the community's law. */
    public static boolean eligibleTarget(YukkuriEntity t) {
        return t.isAlive() && !t.isRemoved() && YukkuriStatus.identity(t).free() && !t.getVariant().isPredator() && !t.getVariant().isHouse();
    }
    private static int executorsOf(YukkuriEntity target) {
        if (!(target.level() instanceof ServerLevel level)) return 0;
        return (int) level.getEntitiesOfClass(YukkuriEntity.class, target.getBoundingBox().inflate(24), e -> e.sanction().target == target).size();
    }
    /** A sound, free adult that would carry it out: harsh enough, and not kin that still knows the accused. */
    static boolean executor(YukkuriEntity e, YukkuriEntity target) {
        if (e == target || !e.isAlive() || e.isNoAi() || !e.family().adult() || !YukkuriStatus.identity(e).free() || e.getVariant().isPredator()
                || e.getVariant().isHouse() || e.isPanicking() || e.life().isSleeping() || e.body().dying() || e.body().weak()
                || e.operations().active() || e.mind().nonYukkuri() || e.isPassenger() || e.getHealth() < e.getMaxHealth() * .6f
                || e.sanction().target != null && e.sanction().target != target) return false;
        var p = e.life().personality();
        if (!(p.aggression() >= 55 || p.selfishness() >= 65 && p.selfControl() < 65)) return false;
        if (target.settlement().groupId() != null && !(SettlementSociety.sameGroup(e, target) && SettlementSociety.accepted(e))) return false;
        return !(e.life().canRecognize(target) && e.level() instanceof ServerLevel level && FamilyData.get(level).socialKin(e.getUUID(), target.getUUID()));
    }
    /** The grounds this community acts on by itself (see class comment); null when none. */
    static String grounds(YukkuriEntity observer, YukkuriEntity target) {
        if (target.mind().nonYukkuri() && target.mind().course() == io.github.longshou.mcyukkuri.mind.MindLife.Course.FRENZY
                && (target.settlement().groupId() == null || SettlementSociety.sameGroup(observer, target))) return "non_yukkuri";
        if (!target.life().hasAccessory() && SettlementSociety.sameGroup(observer, target)
                && target.settlement().society().role() == SettlementData.Role.OUTCAST && !observer.life().canRecognize(target)) return "hatless_outcast";
        return null;
    }

    // ---- executor ------------------------------------------------------------------------------------------------
    public YukkuriEntity target() { return target; }
    public String reason() { return reason; }
    public boolean active() { return target != null; }
    /** True only inside the bite's hurt call, so the wound is a tear (InjuryModel.kind). */
    public boolean biting() { return biting; }
    public String activity() { return target == null ? "idle" : !declared ? "sanction_approach" : declareTicks > 0 ? "sanction_declare" : "sanction_bite"; }
    public String describe() {
        var c = judged(entity);
        return (target == null ? "none" : "executing #" + target.getId() + " for " + reason + " ticks=" + ticks)
                + (c == null ? "" : " accused=" + c.reason());
    }
    boolean take(YukkuriEntity accused, String why) {
        if (target == accused) return true;
        if (target != null) return false;
        target = accused; reason = why; ticks = 0; biteWait = 0; declared = false; declareTicks = 0;
        entity.social().stop(); entity.dailyLife().stop(); entity.groupInteraction().stop(); entity.playLife().stop();
        return true;
    }
    private boolean valid() {
        return target != null && target.isAlive() && !target.isRemoved() && target.level() == entity.level() && entity.isAlive()
                && !entity.isPanicking() && !entity.body().dying() && entity.getHealth() >= entity.getMaxHealth() * .3f
                && entity.distanceToSqr(target) < 24 * 24 && ticks < GIVE_UP_TICKS && eligibleTarget(target) && enabled();
    }
    @Override public boolean canUse() {
        if (target != null) return valid() || finish(false);
        if (!enabled() || --scanWait > 0 || !(entity.level() instanceof ServerLevel level)) return false;
        scanWait = 200 + entity.getRandom().nextInt(100);
        if (!entity.family().adult() || entity.isNoAi()) return false;
        for (var peer : level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(8),
                p -> p != entity && eligibleTarget(p) && entity.hasLineOfSight(p))) {
            String why = grounds(entity, peer);
            if (why == null || !executor(entity, peer)) continue;
            if (entity.getRandom().nextInt(why.equals("non_yukkuri") ? 3 : 4) != 0) continue;
            if (request(peer, why, entity)) return target != null;
        }
        return false;
    }
    @Override public boolean canContinueToUse() { return valid(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void tick() {
        if (!valid()) return;
        ticks++;
        entity.getLookControl().setLookAt(target, 30, 30);
        double reach = (entity.getBbWidth() + target.getBbWidth()) * .5 + .45;
        double distance = entity.distanceToSqr(target);
        if (!declared) {
            if (distance > 9) { if (ticks % 10 == 1) entity.life().navigate(target.blockPosition(), 1.15, false); return; }
            declared = true; declareTicks = 30; entity.getNavigation().stop();
            entity.showReaction("angry", "sanction_declare", 80);
            target.showReaction("cry", "sanction_plea", 80);
            target.mind().add(10, "sanctioned");
            return;
        }
        if (declareTicks > 0) { declareTicks--; entity.getNavigation().stop(); return; }
        if (distance > reach * reach) { if (ticks % 8 == 1) entity.life().navigate(target.blockPosition(), 1.25, false); return; }
        entity.getNavigation().stop();
        if (--biteWait > 0) return;
        biteWait = BITE_INTERVAL;
        var level = (ServerLevel) entity.level();
        biting = true;
        try { target.hurt(level.damageSources().mobAttack(entity), BITE); } finally { biting = false; }
        if (target.life().hasAccessory() && entity.getRandom().nextInt(2) == 0) target.headwear().harm(1, "sanction", false);
        if (!target.isAlive()) finish(true);
    }
    @Override public void stop() {
        if (target != null && !target.isAlive()) finish(true);
        else if (target != null && !valid()) finish(false);
        entity.getNavigation().stop();
    }
    private boolean finish(boolean executed) {
        var victim = target; target = null; declared = false;
        if (victim == null) return false;
        if (entity.level() instanceof ServerLevel level) {
            var c = cases(level).get(victim.getUUID());
            if (executed || executorsOf(victim) == 0) cases(level).remove(victim.getUUID());
            if (executed && c != null) {
                MCYukkuri.LOGGER.info("[sanction] {} #{} executed {} #{} for {}", entity.getVariant().assetId(), entity.getId(),
                        victim.getVariant().assetId(), victim.getId(), c.reason());
            }
        }
        if (executed && entity.isAlive()) entity.showReaction("angry", "sanction_done", 80);
        return false;
    }
}
