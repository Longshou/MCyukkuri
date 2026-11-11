package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.action.Swim;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.phys.Vec3;

/** Locomotion clip time follows rendered body or ridden hull distance; a bare-braid stroke keeps the stick's beat at its slower pace. */
final class WaterMotionClock {
    private record Sample(long start, Vec3 at, float seconds) {}
    private static final Map<YukkuriView, Sample> samples = new WeakHashMap<>();

    static float seconds(YukkuriView body, float partial, float ordinary) {
        String action = body.motorAction();
        String clip = body.getAnimationId();
        boolean swimming = action.equals(Swim.ID) && clip.equals(YukkuriAction.WATER_SWIM.id());
        boolean paddling = action.equals(Paddle.ID) && (clip.equals(Paddle.CLIP) || clip.equals(Paddle.BRAID_CLIP));
        if (!swimming && !paddling) {
            samples.remove(body);
            return ordinary;
        }
        var hat = body.vehicleHat();
        Vec3 at = hat == null ? body.getPosition(partial) : hat.getPosition(partial);
        var previous = samples.get(body);
        float seconds = previous == null || previous.start != body.motorStart() ? 0 : previous.seconds
                + (float) (at.subtract(previous.at).horizontalDistance() / (20 * AbilityMath.Coefficients.DEFAULTS.swimSpeed() * Paddle.clipPace(clip)));
        samples.put(body, new Sample(body.motorStart(), at, seconds));
        return seconds;
    }

    private WaterMotionClock() {}
}
