package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.phys.Vec3;

/** Locomotion clip time follows rendered distance, including the ridden hull; no gameplay state or frame-rate clock. */
final class WaterMotionClock {
    private record Sample(long start, Vec3 at, float seconds) {}
    private static final Map<YukkuriView, Sample> samples = new WeakHashMap<>();

    static float seconds(YukkuriView body, float partial, float ordinary) {
        String action = body.motorAction();
        if (!action.equals(Swim.ID) && !(action.equals(Paddle.ID) && body.getAnimationId().equals("boat_paddle"))) {
            samples.remove(body);
            return ordinary;
        }
        var hat = body.vehicleHat();
        Vec3 at = hat == null ? body.getPosition(partial) : hat.getPosition(partial);
        var previous = samples.get(body);
        float seconds = previous == null || previous.start != body.motorStart() ? 0 : previous.seconds
                + (float) (at.subtract(previous.at).horizontalDistance() / (20 * AbilityMath.Coefficients.DEFAULTS.swimSpeed()));
        samples.put(body, new Sample(body.motorStart(), at, seconds));
        return seconds;
    }

    private WaterMotionClock() {}
}
