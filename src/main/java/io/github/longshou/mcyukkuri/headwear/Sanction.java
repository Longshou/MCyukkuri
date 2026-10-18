package io.github.longshou.mcyukkuri.headwear;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.SettlementSociety;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * "Sanction" (制裁) is bullying, not killing (user correction 2026-10-18, overruling the earlier reading of komicolle 40720
 * No.11518829): executors gang up on the accused, declare it, then push it about, bite it, take its food and finally chase
 * it away. The sanction never aims at death: bites stop while the accused is badly hurt ({@link #HEALTH_FLOOR}) and the last
 * act is always driving it off. It may still die of what it already had (old wounds, leaking filling); that is logged as a
 * death during a sanction, not as an execution.
 *
 * <p>Root cause: a yukkuri without its accessory is despised by everyone who cannot recognise it ({@link Contempt},
 * {@link Recognition}); the harsh among them sanction it ({@code hatless}), and a horribly hurt hatless one is driven off as a
 * monster ({@code monster}). Other grounds: the non-yukkuri frenzy, and the community rules ({@link #request}, through
 * {@code settlement.Sanction}).
 *
 * <p>Who takes part: real adults that see the accused, harsh enough, sound and free. For hatless grounds only those that
 * cannot recognise it (kin included: a parent that does not know its hatless child bullies it like any other); whoever does
 * recognise it stays out. For a rule offence by a recognised individual, its own close kin (parents, children, partner) stay
 * out. Pets, owned, house and predator yukkuri are never sanctioned. Numbers are project design.
 */
public final class Sanction extends Goal {
    public record Case(UUID target, String reason, UUID accuser, long since) {}
    private static final Map<ServerLevel, Map<UUID, Case>> CASES = new WeakHashMap<>();
    /** Per-dimension deadlines survive unloading and restarting, including a finish after the victim's last entity save. */
    private static final class Respite extends SavedData {
        private final Map<UUID, Long> until = new HashMap<>();
        static Respite get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new Factory<>(Respite::new, Respite::load), "mcyukkuri_sanction_respite");
        }
        void grant(UUID target, long deadline) { until.put(target, deadline); setDirty(); }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            var list = new ListTag();
            until.forEach((id, deadline) -> {
                var entry = new CompoundTag(); entry.putUUID("Target", id); entry.putLong("Until", deadline); list.add(entry);
            });
            tag.put("Respite", list); return tag;
        }
        static Respite load(CompoundTag tag, HolderLookup.Provider registries) {
            var data = new Respite();
            for (var value : tag.getList("Respite", 10)) {
                var entry = (CompoundTag) value;
                if (entry.hasUUID("Target")) data.until.put(entry.getUUID("Target"), entry.getLong("Until"));
            }
            return data;
        }
    }
    /** Reasons that come from not recognising a hatless individual (or a broken mind), not from a judged offence. */
    public static final Set<String> SPONTANEOUS = Set.of("hatless", "monster", "non_yukkuri");
    /** A bite: a small tear (InjuryModel.kind), severity 1, no leak by itself. */
    public static final float BITE = 1.5f;
    /** No bite while the accused has this share of health or less (or is weak, dying, or its core is hurt). */
    public static final float HEALTH_FLOOR = .5f;
    /** Ticks between acts; acts per executor before it drives the accused off; give up after; executors at most; chase range. */
    public static final int ACT_INTERVAL = 30, ACTS = 5, GIVE_UP_TICKS = 2400, MAX_EXECUTORS = 3, CHASE_RANGE = 24;
    public static final int RESPITE_TICKS = 6000;

    private final YukkuriEntity entity;
    private YukkuriEntity target;
    private String reason = "";
    private int ticks, actWait, declareTicks, scanWait, acts;
    private boolean declared, biting, snatched;

    public Sanction(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        scanWait = 100 + entity.getRandom().nextInt(200);
    }
    public static boolean enabled() { return YukkuriConfig.SANCTION.get(); }
    private static Map<UUID, Case> cases(ServerLevel level) { return CASES.computeIfAbsent(level, k -> new HashMap<>()); }
    /** The hatless / monster / frenzy grounds of {@code reason}. */
    public static boolean spontaneous(String reason) { return SPONTANEOUS.contains(reason); }

    // ---- interface for settlement rules and contempt -----------------------------------------------------------------
    /**
     * Ask for {@code target} to be sanctioned (bullied) for {@code reason}: {@code hatless}, {@code monster},
     * {@code non_yukkuri}, or a rule offence such as {@code theft}, {@code killed_kin}, {@code defied_leader}. The accuser, when
     * able, takes part; up to {@link #MAX_EXECUTORS} executors in all are drawn from adults that see the accused.
     * @return true when at least one executor took the case.
     */
    public static boolean request(YukkuriEntity target, String reason, YukkuriEntity accuser) {
        if (!enabled() || !(target.level() instanceof ServerLevel level) || !eligibleTarget(target)) return false;
        if (spontaneous(reason) && Respite.get(level).until.getOrDefault(target.getUUID(), 0L) > level.getGameTime()) return false;
        int taken = executorsOf(target);
        if (accuser != null && taken < MAX_EXECUTORS && executor(accuser, target, reason) && accuser.sanction().take(target, reason)) taken++;
        for (var other : level.getEntitiesOfClass(YukkuriEntity.class, target.getBoundingBox().inflate(10),
                e -> e != target && e != accuser && executor(e, target, reason) && e.hasLineOfSight(target)).stream()
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
        if (!(target.level() instanceof ServerLevel level)) return null;
        var c = cases(level).get(target.getUUID());
        // A case nobody carries out any more lapses (executors gave up or the accused escaped).
        if (c != null && level.getGameTime() - c.since() > GIVE_UP_TICKS + 200 && executorsOf(target) == 0) { cases(level).remove(target.getUUID()); return null; }
        return c;
    }
    /** Pets, owned, house and predator yukkuri are outside the community's law. */
    public static boolean eligibleTarget(YukkuriEntity t) {
        return t.isAlive() && !t.isRemoved() && YukkuriStatus.identity(t).free() && !t.getVariant().isPredator() && !t.getVariant().isHouse();
    }
    private static int executorsOf(YukkuriEntity target) {
        if (!(target.level() instanceof ServerLevel level)) return 0;
        return (int) level.getEntitiesOfClass(YukkuriEntity.class, target.getBoundingBox().inflate(24), e -> e.sanction().target == target).size();
    }
    /** Parent, child or partner. */
    private static boolean closeKin(YukkuriEntity e, YukkuriEntity target) {
        return e.level() instanceof ServerLevel level && (FamilyData.get(level).socialKin(e.getUUID(), target.getUUID())
                || target.getUUID().equals(e.family().partner()) || e.getUUID().equals(target.family().partner()));
    }
    /** A sound, free, harsh adult that would carry it out (see the class comment for who stays out). */
    static boolean executor(YukkuriEntity e, YukkuriEntity target, String reason) {
        if (e == target || !e.isAlive() || e.isNoAi() || !e.family().adult() || !YukkuriStatus.identity(e).free() || e.getVariant().isPredator()
                || e.getVariant().isHouse() || e.isPanicking() || e.life().isSleeping() || e.body().dying() || e.body().weak()
                || e.operations().active() || e.mind().nonYukkuri() || e.isPassenger() || e.getHealth() < e.getMaxHealth() * .6f
                || e.sanction().target != null && e.sanction().target != target) return false;
        if (!Contempt.harsh(e)) return false;
        boolean knows = Recognition.recognizes(e, target);
        if (reason.equals("hatless") || reason.equals("monster")) return !knows;
        if (reason.equals("non_yukkuri")) return target.settlement().groupId() == null || SettlementSociety.sameGroup(e, target);
        // A judged offence: the community carries it out; whoever knows the accused as its own close kin stays out.
        if (target.settlement().groupId() != null && !(SettlementSociety.sameGroup(e, target) && SettlementSociety.accepted(e))) return false;
        return !(knows && closeKin(e, target));
    }

    // ---- executor ------------------------------------------------------------------------------------------------
    public YukkuriEntity target() { return target; }
    public String reason() { return reason; }
    public boolean active() { return target != null; }
    /** True only inside a bite's hurt call, so the wound is a tear (InjuryModel.kind). */
    public boolean biting() { return biting; }
    public String activity() { return target == null ? Behaviors.IDLE : !declared ? Behaviors.SANCTION_APPROACH : declareTicks > 0 ? Behaviors.SANCTION_DECLARE : Behaviors.SANCTION_BULLY; }
    public String describe() {
        var c = judged(entity);
        return (target == null ? "none" : "bullying #" + target.getId() + " for " + reason + " acts=" + acts + "/" + ACTS + " ticks=" + ticks)
                + (c == null ? "" : " accused=" + c.reason());
    }
    boolean take(YukkuriEntity accused, String why) {
        if (target == accused) return true;
        if (target != null) return false;
        target = accused; reason = why; ticks = 0; actWait = 0; acts = 0; declared = false; declareTicks = 0; snatched = false;
        entity.social().stop(); entity.dailyLife().stop(); entity.groupInteraction().stop(); entity.playLife().stop();
        return true;
    }
    private boolean valid() {
        return target != null && target.isAlive() && !target.isRemoved() && target.level() == entity.level() && entity.isAlive()
                && !entity.isPanicking() && !entity.body().dying() && entity.getHealth() >= entity.getMaxHealth() * .3f
                && entity.distanceToSqr(target) < CHASE_RANGE * CHASE_RANGE && ticks < GIVE_UP_TICKS && eligibleTarget(target) && enabled();
    }
    @Override public boolean canUse() {
        if (target != null) return valid() || finish(false);
        if (!enabled() || --scanWait > 0 || !(entity.level() instanceof ServerLevel level)) return false;
        scanWait = 200 + entity.getRandom().nextInt(100);
        if (!entity.family().adult() || entity.isNoAi()) return false;
        // The hatless and the monstrous are noticed by Contempt; this scan only looks for the non-yukkuri frenzy.
        for (var peer : level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(8),
                p -> p != entity && eligibleTarget(p) && entity.hasLineOfSight(p) && p.mind().nonYukkuri()
                        && p.mind().course() == io.github.longshou.mcyukkuri.mind.MindLife.Course.FRENZY)) {
            if (!executor(entity, peer, "non_yukkuri") || entity.getRandom().nextInt(3) != 0) continue;
            if (request(peer, "non_yukkuri", entity)) return target != null;
        }
        return false;
    }
    @Override public boolean canContinueToUse() { return valid(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    private boolean hatlessReason() { return reason.equals("hatless") || reason.equals("monster"); }
    @Override public void tick() {
        if (!valid()) return;
        ticks++;
        entity.getLookControl().setLookAt(target, 30, 30);
        double reach = (entity.getBbWidth() + target.getBbWidth()) * .5 + .45;
        double distance = entity.distanceToSqr(target);
        if (!declared) {
            if (distance > 9) { if (ticks % 10 == 1) entity.life().navigate(target.blockPosition(), 1.15, false); return; }
            declared = true; declareTicks = 30; entity.getNavigation().stop();
            entity.showReaction("angry", reason.equals("monster") ? "hatless_monster" : hatlessReason() ? "hatless_scorn" : "settlement_drive", 80);
            Contempt.suffer(target, "cry", target.life().hasAccessory() ? "afraid" : "settlement_hurt", 80, 4, "sanctioned");
            return;
        }
        if (declareTicks > 0) { declareTicks--; entity.getNavigation().stop(); return; }
        if (distance > reach * reach) { if (ticks % 8 == 1) entity.life().navigate(target.blockPosition(), 1.25, false); return; }
        entity.getNavigation().stop();
        if (--actWait > 0) return;
        actWait = ACT_INTERVAL;
        act((ServerLevel) entity.level());
    }
    /** One act of bullying; the last one always drives the accused away. */
    private void act(ServerLevel level) {
        acts++;
        var relations = YukkuriWorldData.get(level);
        relations.relate(target.getUUID(), entity.getUUID(), -5);
        relations.relate(entity.getUUID(), target.getUUID(), -2);
        if (acts >= ACTS) { driveOff(); return; }
        String what;
        if (!snatched && (!target.life().mouth().isEmpty() || target.treasure().count() > 0 && !entity.treasure().full())) {
            snatched = true;
            boolean food = target.life().snatchedBy(entity);
            if (!food) entity.treasure().snatchFrom(target);
            what = food ? "snatch_food" : "snatch_treasure";
        } else if (mayBite() && entity.getRandom().nextBoolean()) {
            biting = true;
            try { target.hurt(level.damageSources().mobAttack(entity), BITE); } finally { biting = false; }
            if (target.life().hasAccessory() && entity.getRandom().nextInt(3) == 0) target.headwear().harm(1, "sanction", false);
            what = "bite";
        } else {
            target.knockback(.55, entity.getX() - target.getX(), entity.getZ() - target.getZ());
            target.hurtMarked = true;
            what = "push";
        }
        Contempt.suffer(target, entity.getRandom().nextBoolean() ? "cry" : "teary", "", 60, 1, "bullied");
        MCYukkuri.LOGGER.info("[sanction] {} #{} {} {} #{} ({}, act {}/{}, health {})", entity.getVariant().assetId(), entity.getId(), what,
                target.getVariant().assetId(), target.getId(), reason, acts, ACTS, Math.round(target.getHealth() * 10) / 10f);
        if (!target.isAlive()) finish(false);
    }
    /** Biting stops once the accused is badly hurt: the sanction stays bullying. */
    private boolean mayBite() {
        return target.getHealth() - BITE > target.getMaxHealth() * HEALTH_FLOOR && !target.body().weak() && !target.body().dying()
                && target.body().coreIntact();
    }
    private void driveOff() {
        entity.showReaction("angry", "settlement_drive", 80);
        target.frightenFrom(entity);
        Contempt.suffer(target, "teary", target.life().hasAccessory() ? "afraid" : "settlement_hurt", 100, 3, "driven_off");
        finish(true);
    }
    @Override public void stop() {
        if (target != null) finish(false);
        entity.getNavigation().stop();
    }
    /** {@code done}: the full round was carried out and the accused driven off. */
    private boolean finish(boolean done) {
        var victim = target; String why = reason; target = null; declared = false; acts = 0;
        if (victim == null) return false;
        if (entity.level() instanceof ServerLevel level) {
            if (executorsOf(victim) == 0) {
                cases(level).remove(victim.getUUID());
                Respite.get(level).grant(victim.getUUID(), level.getGameTime() + RESPITE_TICKS);
            }
            if (!victim.isAlive())
                MCYukkuri.LOGGER.info("[sanction] {} #{} died while being bullied for {} (not the aim; health was its own)", victim.getVariant().assetId(), victim.getId(), why);
            else if (done)
                MCYukkuri.LOGGER.info("[sanction] {} #{} bullied and drove off {} #{} for {}", entity.getVariant().assetId(), entity.getId(),
                        victim.getVariant().assetId(), victim.getId(), why);
        }
        if (done && entity.isAlive()) entity.showReaction("angry", "", 60);
        return false;
    }
}
