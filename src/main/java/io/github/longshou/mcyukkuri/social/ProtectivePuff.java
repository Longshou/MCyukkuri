package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * A parent's protective puff (docs/FEATURES.md, user 2026-11-04): on one accepted hit, every recognised parent of the
 * victim within the peer-protection witness range draws once with {@link YukkuriPersonality#helpProbability}; a success
 * turns it to the attacker and puffs through the existing cheek_puff face and puff motion. A parent hit itself while one
 * of its own young is near draws the same way. It never needs free walking and never forces a bout, so a parent that is
 * riding a hat, carrying a child or standing still answers too. One hit gives each parent exactly one draw. Project design.
 */
public final class ProtectivePuff {
    /** The existing peer-protection notification radius. */
    private static final int WITNESS_RANGE = 6;
    /** The authored protect taunt's reaction length; its face and clip are the puff that is already implemented. */
    private static final int REACTION_TICKS = 45;

    /** Records the draws for one accepted hit; true when the victim itself answered at the attacker. */
    public static boolean witnessedHit(YukkuriEntity victim, Entity attacker) {
        if (attacker == null || attacker == victim || !(victim.level() instanceof ServerLevel level)) return false;
        boolean puffed = puff(victim, victim, attacker);
        for (var parent : level.getEntitiesOfClass(YukkuriEntity.class,
                victim.getBoundingBox().inflate(WITNESS_RANGE), e -> e != attacker && parentOf(victim, e)))
            puff(parent, victim, attacker);
        return puffed;
    }

    /** Recorded, still acknowledged parenthood that the parent can actually see (its own accessory present). */
    public static boolean parentOf(YukkuriEntity victim, YukkuriEntity parent) {
        return parent != victim && Bonds.parentOf(parent, victim) && parent.life().canRecognize(victim);
    }

    private static boolean youngNear(YukkuriEntity parent) {
        return parent.level() instanceof ServerLevel level && !level.getEntitiesOfClass(YukkuriEntity.class,
                parent.getBoundingBox().inflate(WITNESS_RANGE), e -> parentOf(e, parent)).isEmpty();
    }

    private static boolean puff(YukkuriEntity parent, YukkuriEntity victim, Entity attacker) {
        if (!able(parent) || parent == victim && !youngNear(parent)) return false;
        var personality = parent.life().personality();
        float chance = personality.helpProbability(true, true);
        float roll = parent.getRandom().nextFloat();
        boolean accepted = roll < chance;
        GameplayLog.event(parent, "social", "protection puff victim={} attacker={} attackerType={} chance={} roll={} accepted={}",
                victim.getUUID(), attacker.getUUID(), attacker.getType().getDescriptionId(), chance, roll, accepted);
        if (!accepted) return false;
        personality.decided("protect_puff");
        parent.getLookControl().setLookAt(attacker, 30, 30);
        parent.showReaction("cheek_puff", "protect_taunt", REACTION_TICKS);
        return parent == victim;
    }

    /** Alive and able to answer while riding, holding or standing: this response never moves the body. */
    private static boolean able(YukkuriEntity parent) {
        return parent.isAlive() && !parent.body().dying() && !parent.isNoAi() && !parent.life().isSleeping();
    }

    private ProtectivePuff() {}
}
