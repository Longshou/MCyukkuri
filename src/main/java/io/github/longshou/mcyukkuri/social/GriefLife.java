package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * The living side of death (docs/INJURIES_AND_REMAINS.md, "临终遗言与献身"). Komicolle's overview ends on the dying wish "还想继续慢慢来……"
 * and the 'dying words' of a yukkuri; the rest below is project design. There is no grave visiting: nobody walks to a body or a grave to mourn
 * (user ruling 2026-10-18); the dead only reek (body/RemainsRot).
 * <ul>
 * <li>Last words: {@link #dying} hooks {@code BodyLife.DYING_LISTENERS}. A dying individual with kin within ten blocks says its last words to
 *     them ({@code dying_kin}); the kin gather, cry and are unsettled ({@code kin_dying}). A parent dying near a starving child instead
 *     gives that child its body ("please eat me", {@link SelfOffering}), a different event chain.</li>
 * <li>Protection: an adult whose own child is the target of a chasing predator may draw the chase onto itself. The choice is
 *     from its character, relation, fear and condition; changing the target only gives the real chase a different prey.</li>
 * </ul>
 */
public final class GriefLife {
    private final YukkuriEntity entity;
    private int sacrificeWait, sacrificeCheck;
    private record Danger(UUID predator, UUID child) {}
    private final Set<Danger> decidedDangers = new HashSet<>();
    private String danger = "none", decision = "waiting", physicalBlock = "none";
    private int chance;
    private boolean decided;
    private YukkuriEntity challenger, protectedChild;
    private int protectTicks, tauntTicks;
    private boolean protecting;
    public boolean protecting() { return challenger != null; }
    public String activity() { return protecting ? (tauntTicks > 0 ? Behaviors.PROTECT_TAUNT : Behaviors.PROTECT_APPROACH) : null; }

    public GriefLife(YukkuriEntity entity) { this.entity = entity; }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    /** Read-only status detail; a danger is rolled once, then retained until that exact chase ends. */
    public String describe() {
        return "danger=" + danger + " decision=" + decision + " chance=" + chance
                + " physical_block=" + physicalBlock + " cooldown=" + sacrificeWait + " decided=" + decided
                + " phase=" + activity() + " elapsed=" + protectTicks + " tauntTicks=" + tauntTicks;
    }

    // ---- last words ------------------------------------------------------------------------------------------------------------
    /** BodyLife.DYING_LISTENERS: called once when a body starts dying. */
    public static void dying(YukkuriEntity dying, String cause, int ticks) {
        if (!(dying.level() instanceof ServerLevel level)) return;
        var kin = level.getEntitiesOfClass(YukkuriEntity.class, dying.getBoundingBox().inflate(10, 3, 10),
                other -> other != dying && other.isAlive() && !other.body().dying() && !other.life().isSleeping() && kinOf(level, dying, other) && other.hasLineOfSight(dying))
                .stream().sorted(Comparator.comparingDouble(dying::distanceToSqr)).toList();
        if ("offering".equals(cause) || dying.offering().dedicate()) {
            // "Please eat me": the offering line replaces the ordinary last words; the other kin still grieve.
            dying.showReaction("teary", "offer_give", ticks);
            var recipient = dying.offering().recipient();
            int m = 0;
            for (var other : kin) {
                if (m++ >= 4) break;
                other.mind().add(other.getUUID().equals(recipient) ? 14 : 6, "kin_dying");
                if (!other.getUUID().equals(recipient)) other.showReactionDelayed("teary", "kin_dying", 100, 50 + m * 6);
            }
            GameplayLog.event(dying, "grief", "offering witnessed recipient={} kin={} cause={}",
                    recipient, kin.stream().limit(4).map(YukkuriEntity::getUUID).toList(), cause);
            return;
        }
        if (kin.isEmpty()) return;
        // The 'dying' line (wanting to take it easy a little longer) is already on its way; the words to the kin follow it.
        dying.showReactionDelayed("blank_cry", "dying_kin", ticks, 28);
        GameplayLog.event(dying, "grief", "last words kin={} cause={}",
                kin.stream().limit(4).map(YukkuriEntity::getUUID).toList(), cause);
        int n = 0;
        for (var other : kin) {
            if (n++ >= 4) break;
            other.mind().add(6, "kin_dying");
            other.showReactionDelayed("teary", "kin_dying", 100, 50 + n * 6);
            // A carried one can witness and grieve, but never walks off on its own to reach the dying body.
            if (other.distanceToSqr(dying) > 6 && !other.isPassenger() && !dying.isPassenger())
                other.getNavigation().moveTo(dying, 1.1);
        }
    }
    /** Partner, parent, child or sibling by recorded care relations. */
    static boolean kinOf(ServerLevel level, YukkuriEntity a, YukkuriEntity b) {
        return Bonds.careOrMate(a, b);
    }
    // ---- sacrifice and the grief clock ---------------------------------------------------------------------------------------------
    /** Every tick from the entity. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (challenger != null) {
            if (!protecting && (++protectTicks > 40 || !challenger.isAlive() || !challenger.predator().threatens(protectedChild))) {
                challenger = protectedChild = null; decision = "approach_unavailable";
            }
            return;
        }
        if (sacrificeWait > 0) { sacrificeWait--; return; }
        if (++sacrificeCheck < 10) return;
        sacrificeCheck = 0;
        if (!YukkuriConfig.SACRIFICE.get() || !entity.isAlive() || !entity.family().adult() || entity.body().dying() || entity.life().isSleeping()
                || entity.profile().dangerToYukkuri() || entity.profile().houseBody() || entity.isNoAi()) {
            danger = "none"; decision = "unavailable"; chance = 0; physicalBlock = protectBlock(); decided = false;
            return;
        }
        var predators = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(14, 4, 14), p -> p.profile().dangerToYukkuri()
                && p.predator().active() && entity.hasLineOfSight(p));
        Set<Danger> present = new HashSet<>();
        for (var predator : predators) {
            var prey = predator.predator().prey();
            if (prey != null && prey != entity && isMyChild(prey) && entity.life().canRecognize(prey) && entity.hasLineOfSight(prey))
                present.add(new Danger(predator.getUUID(), prey.getUUID()));
        }
        decidedDangers.retainAll(present);
        if (present.isEmpty()) {
            danger = "none"; decision = "waiting"; chance = 0; physicalBlock = protectBlock(); decided = false;
        }
        for (var predator : predators) {
            var prey = predator.predator().prey();
            if (prey == null) continue;
            var danger = new Danger(predator.getUUID(), prey.getUUID());
            if (!present.contains(danger)) continue;
            this.danger = "#" + predator.getId() + "->#" + prey.getId();
            physicalBlock = protectBlock();
            chance = protectChance(prey);
            if (!decidedDangers.add(danger)) { decided = true; continue; }
            decided = false; decision = "pending";
            int chance = protectChance(prey);
            int roll = entity.getRandom().nextInt(100);
            GameplayLog.event(entity, "grief", "protection decision predator={} child={} chance={} roll={} physicalBlock={} nutrition={} energy={} health={}",
                    predator.getUUID(), prey.getUUID(), chance, roll, physicalBlock,
                    entity.life().nutrition(), entity.life().energy(), entity.getHealth());
            if (roll >= chance) {
                entity.life().personality().decided("protect_child_declined");
                decision = "declined"; decided = true;
                sacrificeWait = 0;
                return;
            }
            if (physicalBlock.equals("none")) {
                challenger = predator; protectedChild = prey; protectTicks = tauntTicks = 0;
                entity.life().personality().decided("protect_child");
                decision = "approaching"; decided = true;
                GameplayLog.event(entity, "grief", "protection approach predator={} child={} chance={}",
                        predator.getUUID(), prey.getUUID(), chance);
                return;
            }
            decision = "divert_blocked"; decided = true;
            GameplayLog.event(entity, "grief", "protection diversion refused predator={} child={} chance={}",
                    predator.getUUID(), prey.getUUID(), chance);
        }
    }
    /**
     * Project initial values for a parent deciding whether to put itself between its child and a current hunt.
     * A panic is an immediate fear signal, but never a hard veto: a willing parent may still choose a physically possible diversion. The actual predator remains free to reject the new target or catch it afterwards.
     */
    private int protectChance(YukkuriEntity child) {
        var personality = entity.life().personality();
        if (!protectBlock().equals("none")) return 0;
        return Math.round(100 * personality.helpProbability(true, true));
    }
    private String protectBlock() {
        if (entity.life().nutrition() < 15) return "nutrition";
        if (entity.life().energy() < 15) return "energy";
        if (entity.body().healthRatio() < .35f) return "health";
        return "none";
    }
    private boolean isMyChild(YukkuriEntity other) {
        return Bonds.parentOf(entity, other);
    }
    public Goal goal() { return new ProtectionGoal(); }
    private final class ProtectionGoal extends Goal {
        ProtectionGoal() { setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK)); }
        @Override public boolean canUse() { return challenger != null && valid(); }
        private boolean valid() {
            return entity.isAlive() && !entity.isNoAi() && !entity.body().dying() && !entity.isPassenger()
                    && !entity.isOnFire() && !entity.isInWaterOrBubble() && !entity.isCommandPlaying() && !entity.operations().active()
                    && challenger != null && challenger.isAlive() && protectedChild != null && protectedChild.isAlive()
                    && (challenger.predator().threatens(protectedChild) || decision.equals("diverted") && challenger.predator().pursuing(entity)) && protectTicks < 160 && protectBlock().equals("none")
                    && (!protecting || !entity.isPanicking());
        }
        @Override public boolean canContinueToUse() { return valid(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() { protecting = true; entity.beginProtectionApproach(); entity.life().interrupt(); }
        @Override public void tick() {
            protectTicks++;
            entity.getLookControl().setLookAt(challenger,30,30);
            if (decision.equals("diverted")) {
                if (++tauntTicks >= 12) { entity.fleeFrom(challenger); protectTicks = 160; }
                return;
            }
            if (entity.distanceToSqr(challenger) > 36 || !entity.hasLineOfSight(challenger)) {
                tauntTicks = 0;
                if(protectTicks % 10 == 1) entity.getNavigation().moveTo(challenger,1.15);
                return;
            }
            SurfaceApproach.halt(entity);
            SurfaceApproach.face(entity,SurfaceApproach.yaw(entity.position(),challenger.position()));
            if (++tauntTicks == 1) {
                decision = "taunting";
                entity.showReaction("cheek_puff","protect_taunt",45);
                GameplayLog.event(entity,"grief","protection taunt predator={} child={} distance={}",challenger.getUUID(),protectedChild.getUUID(),entity.distanceTo(challenger));
            }
            if (challenger.predator().divert(entity,protectedChild)) {
                decision = "diverted"; sacrificeWait = 3600;
                challenger.showReaction("angry","protect_angered",60);
                int affinity = YukkuriWorldData.get(level()).affinity(protectedChild.getUUID(),entity.getUUID());
                int friendliness = protectedChild.life().personality().friendliness();
                String response = affinity >= 40 && friendliness >= 40 ? "protect_child_worry" : friendliness < 35 ? "protect_child_selfish" : "protect_child_flee";
                protectedChild.showReactionDelayed(affinity >= 40 ? "teary" : "surprise",response,80,12);
                GameplayLog.event(entity,"grief","protection diverted predator={} child={} affinity={} friendliness={} response={}",challenger.getUUID(),protectedChild.getUUID(),affinity,friendliness,response);
            } else {
                decision = "divert_blocked"; protectTicks = 160;
                GameplayLog.event(entity,"grief","taunt refused predator={} state={}",challenger.getUUID(),challenger.predator().describe());
            }
        }
        @Override public void stop() {
            if (!decision.equals("diverted")) {
                decision = "interrupted";
                GameplayLog.event(entity,"grief","protection interrupted ticks={} predator={} child={}",protectTicks,challenger==null?null:challenger.getUUID(),protectedChild==null?null:protectedChild.getUUID());
            }
            challenger = protectedChild = null; protecting = false; tauntTicks = 0;
            entity.getNavigation().stop();
        }
    }
}
