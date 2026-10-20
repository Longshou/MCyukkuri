package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
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

    public GriefLife(YukkuriEntity entity) { this.entity = entity; }

    private ServerLevel level() { return (ServerLevel) entity.level(); }

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
            MCYukkuri.LOGGER.info("[grief] {} #{} offers its body to {}", dying.getVariant().assetId(), dying.getId(), recipient);
            return;
        }
        if (kin.isEmpty()) return;
        // The 'dying' line (wanting to take it easy a little longer) is already on its way; the words to the kin follow it.
        dying.showReactionDelayed("blank_cry", "dying_kin", ticks, 28);
        MCYukkuri.LOGGER.info("[grief] {} #{} says last words to {} kin ({})", dying.getVariant().assetId(), dying.getId(), kin.size(), cause);
        int n = 0;
        for (var other : kin) {
            if (n++ >= 4) break;
            other.mind().add(6, "kin_dying");
            other.showReactionDelayed("teary", "kin_dying", 100, 50 + n * 6);
            if (other.distanceToSqr(dying) > 6) other.getNavigation().moveTo(dying, 1.1);
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
        if (sacrificeWait > 0) { sacrificeWait--; return; }
        if (++sacrificeCheck < 10) return;
        sacrificeCheck = 0;
        if (!YukkuriConfig.SACRIFICE.get() || !entity.isAlive() || !entity.family().adult() || entity.body().dying() || entity.life().isSleeping()
                || entity.profile().dangerToYukkuri() || entity.profile().houseBody() || entity.isNoAi()) return;
        var predators = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(14, 4, 14), p -> p.profile().dangerToYukkuri()
                && p.predator().active() && entity.hasLineOfSight(p));
        Set<Danger> present = new HashSet<>();
        for (var predator : predators) {
            var prey = predator.predator().prey();
            if (prey != null && prey != entity && isMyChild(prey) && entity.life().canRecognize(prey) && entity.hasLineOfSight(prey))
                present.add(new Danger(predator.getUUID(), prey.getUUID()));
        }
        decidedDangers.retainAll(present);
        for (var predator : predators) {
            var prey = predator.predator().prey();
            if (prey == null) continue;
            var danger = new Danger(predator.getUUID(), prey.getUUID());
            if (!present.contains(danger) || !decidedDangers.add(danger)) continue;
            int chance = protectChance(prey);
            if (entity.getRandom().nextInt(100) >= chance) {
                entity.life().personality().decided("protect_child_declined");
                sacrificeWait = 0;
                return;
            }
            if (predator.predator().divert(entity)) {
                sacrificeWait = 3600;
                entity.life().personality().decided("protect_child");
                entity.showReaction("angry", "sacrifice", 100);
                MCYukkuri.LOGGER.info("[grief] {} #{} draws {} #{} away from its child #{} (protect {}%)", entity.getVariant().assetId(), entity.getId(),
                        predator.getVariant().assetId(), predator.getId(), prey.getId(), chance);
                return;
            }
        }
    }
    /**
     * Project initial values for a parent deciding whether to put itself between its child and a current hunt.
     * A panic is an immediate fear signal, but never a hard veto: a willing parent may still choose a physically possible diversion. The actual predator remains free to reject the new target or catch it afterwards.
     */
    private int protectChance(YukkuriEntity child) {
        var personality = entity.life().personality();
        if (entity.life().nutrition() < 15 || entity.life().energy() < 15
                || entity.body().healthRatio() < .35f) return 0;
        return Math.round(100 * personality.helpProbability(true, true));
    }
    private boolean isMyChild(YukkuriEntity other) {
        return Bonds.parentOf(entity, other);
    }
}
