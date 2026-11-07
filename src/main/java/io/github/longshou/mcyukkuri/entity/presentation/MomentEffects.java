package io.github.longshou.mcyukkuri.entity.presentation;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

/** Effects belong to the authoritative step edge, never to client render frames. */
final class MomentEffects {
    static void start(YukkuriEntity e, String id, Moments.Frame frame) {
        var level = (ServerLevel)e.level();
        String particle = frame.optional("particle"), sound = frame.optional("sound");
        if (!particle.isEmpty()) {
            var type = (SimpleParticleType)BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(particle));
            level.sendParticles(type, e.getX(), e.getY()+e.getBbHeight(), e.getZ(), 1, 0, 0, 0, 0);
        }
        if (!sound.isEmpty()) level.playSound(null, e.blockPosition(),
                BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(sound)), SoundSource.NEUTRAL, 1, 1);
        if (!particle.isEmpty() || !sound.isEmpty()) GameplayLog.event(e, "choreo",
                "moment={} step={} particle={} sound={}", id, frame.index(), particle, sound);
    }
    private MomentEffects() {}
}
