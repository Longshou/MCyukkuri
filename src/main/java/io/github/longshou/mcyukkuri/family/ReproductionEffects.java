package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Short-lived release cue only: no item, food, conception or durable fluid state is created. */
public final class ReproductionEffects {
    private static final DustParticleOptions DROPLET=new DustParticleOptions(new Vector3f(1.0f,.88f,.66f),.45f);
    private ReproductionEffects() {}

    public static void release(ServerLevel level,YukkuriEntity receiver) {
        var forward=Vec3.directionFromRotation(0,receiver.getYRot());
        var at=receiver.position().subtract(forward.scale(receiver.getBbWidth()*.45)).add(0,.2*receiver.getBbHeight(),0);
        level.sendParticles(DROPLET,at.x,at.y,at.z,5,.045,.035,.045,.018);
    }
}
