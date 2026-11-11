package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Short-lived release cue only: no item, food, conception or durable fluid state is created. */
public final class ReproductionEffects {
    private static final DustParticleOptions DROPLET=new DustParticleOptions(new Vector3f(1.0f,.88f,.66f),.45f);
    private ReproductionEffects() {}

    public static void release(ServerLevel level,YukkuriEntity giver,YukkuriEntity receiver) {
        droplets(level,receiver,8);
        heart(level,giver);
        heart(level,receiver);
        releaseSound(level,receiver);
    }

    public static void droplets(ServerLevel level,YukkuriEntity receiver,int count) {
        var forward=Vec3.directionFromRotation(0,receiver.getYRot());
        var at=receiver.position().subtract(forward.scale(receiver.getBbWidth()*.45)).add(0,.2*receiver.getBbHeight(),0);
        level.sendParticles(DROPLET,at.x,at.y,at.z,count,.045,.035,.045,.018);
    }

    private static void heart(ServerLevel level,YukkuriEntity entity) {
        level.sendParticles(ParticleTypes.HEART,entity.getX(),entity.getY()+entity.getBbHeight()+.1,entity.getZ(),1,.08,.04,.08,0);
    }

    private static void releaseSound(ServerLevel level,YukkuriEntity receiver) {
        // Reserved for the release sound asset.
    }
}
