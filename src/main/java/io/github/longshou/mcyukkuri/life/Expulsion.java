package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * The one end every witnessed theft shares: the thief is marked off the ground it took from and runs from the guard.
 * The guard's own visible manner (frown, warning, chase, kick) belongs to the guard's own layer; this only makes sure
 * the theft itself ends and the stolen item leaves with the thief in its mouth.
 */
public final class Expulsion {
    /** {@code site} is the food source or stand the guard defended. */
    public static void driveOff(YukkuriEntity thief, LivingEntity guard, BlockPos site) {
        thief.life().avoidField(site);
        thief.status().reactions().threat().warnedBy(guard);
    }

    private Expulsion() {}
}
