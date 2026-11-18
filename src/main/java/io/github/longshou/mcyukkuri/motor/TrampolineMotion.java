package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.TrampolineSpring;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** The ordinary hop starts a session. Thereafter the mat reflects each actual landing. */
public final class TrampolineMotion {
    private TrampolineMotion() {}

    public static void hop(YukkuriEntity body) {
        Motor.takeoff(body, new Vec3(0, ArcSolver.vyFor(BodyAbility.of(body).hopHeight()), 0), "trampoline_hop");
    }

    public static void steady(YukkuriEntity body) {
        Motor.velocity(body, new Vec3(0, body.getDeltaMovement().y, 0), "trampoline_balance");
    }

    public static boolean rebound(Entity entity, double impact) {
        return reflect(entity, impact, TrampolineSpring.RESTITUTION, false);
    }

    public static boolean rebound(YukkuriEntity body, double impact, boolean playing) {
        double weight = body.getBbWidth() * (double) body.getBbWidth() * body.getBbHeight();
        double fitness = BodyAbility.fitness(body);
        double restitution = TrampolineSpring.restitution(weight, fitness, body.isBaby());
        if (!reflect(body, impact, restitution, playing)) return false;
        if (playing) GameplayLog.event(body, "play", "trampoline rebound weight={} fitness={} energy={} young={} impact={} speed={} height={}",
                weight, fitness, body.life().energy(), body.isBaby(), impact, body.getDeltaMovement().y,
                TrampolineSpring.livingHeight(body.getDeltaMovement().y));
        return true;
    }

    private static boolean reflect(Entity entity, double impact, double restitution, boolean playing) {
        double speed = TrampolineSpring.rebound(impact, restitution, entity.getGravity());
        if (speed == 0) return false;
        var motion = entity.getDeltaMovement();
        Motor.velocity(entity, playing ? new Vec3(0, speed, 0) : new Vec3(motion.x, speed, motion.z), "trampoline_rebound");
        // A resting item only moves every fourth tick. Let its rebound take the normal airborne path.
        if (entity instanceof ItemEntity) entity.setOnGround(false);
        return true;
    }
}
