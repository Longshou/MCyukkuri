package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Quiet vanilla sounds and particles for a live birth (project original staging; the source material has none).
 * Server only and triggered from the delivery's tick thresholds, never from client frames; nothing here changes
 * the delivery rules, the child's identity or the hat catch.
 */
public final class BirthStaging {
    private BirthStaging() {}

    private static ServerLevel server(YukkuriEntity entity) {
        return entity.level() instanceof ServerLevel level ? level : null;
    }

    /** A bead of sweat while she labours. */
    public static void sweat(YukkuriEntity mother) {
        var level = server(mother);
        if (level == null) return;
        level.sendParticles(ParticleTypes.FALLING_WATER, mother.getX(), mother.getY() + mother.getBbHeight() * .85, mother.getZ(), 2, .14, .04, .14, 0);
    }

    /** Soft cloth sound as she settles into place. */
    public static void settle(YukkuriEntity mother) {
        var level = server(mother);
        if (level == null) return;
        level.playSound(null, mother.getX(), mother.getY(), mother.getZ(), SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, .35f, .9f);
    }

    /** A push: a low, soft squish. */
    public static void push(YukkuriEntity mother, float pitch) {
        var level = server(mother);
        if (level == null) return;
        level.playSound(null, mother.getX(), mother.getY() + .1, mother.getZ(), SoundEvents.SLIME_SQUISH_SMALL, SoundSource.NEUTRAL, .3f, pitch);
    }

    /** The release: a small squish at the outlet and a few sparks of relief where the newborn appears. */
    public static void emerge(YukkuriEntity mother, Vec3 outlet) {
        var level = server(mother);
        if (level == null) return;
        level.playSound(null, outlet.x, outlet.y, outlet.z, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.NEUTRAL, .35f, 1.5f);
        level.sendParticles(ParticleTypes.POOF, outlet.x, outlet.y + .1, outlet.z, 3, .08, .04, .08, .01);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, outlet.x, outlet.y + .25, outlet.z, 3, .15, .1, .15, 0);
    }

    /** The newborn's first greeting, spoken once its eyes are open and smiling. */
    public static void greeting(YukkuriEntity newborn) {
        var level = server(newborn);
        if (level == null) return;
        level.sendParticles(ParticleTypes.NOTE, newborn.getX(), newborn.getEyeY() + .15, newborn.getZ(), 1, .1, .05, .1, 0);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, newborn.getX(), newborn.getY() + newborn.getBbHeight() * .7, newborn.getZ(), 3, .15, .1, .15, 0);
    }

    /** Affection from the mother or the family toward the newborn. */
    public static void affection(YukkuriEntity actor) {
        var level = server(actor);
        if (level == null) return;
        level.sendParticles(ParticleTypes.HEART, actor.getX(), actor.getY() + actor.getBbHeight() + .1, actor.getZ(), 2, .12, .05, .12, 0);
    }
}
