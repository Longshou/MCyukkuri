package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.phys.Vec3;

/**
 * A walking or flapping clip beats at the pace the body really travels: a worn, weak body keeps its wobble and wingbeat
 * but takes slower strokes instead of gliding on the full-speed clip. The clip keeps its own clock while the body
 * travels at its ordinary pace.
 */
final class GaitClock {
    /** Ordinary ground walk at request 1.0, and the hop-run's pace (blocks per tick); see {@link Gait}. */
    private static final double WALK = .07, RUN = .10, FLY = .16;
    /** A very slow body still beats its clip at half rate; a fast one never beats more than 1.3 times. */
    private static final double LOWEST = .5, HIGHEST = 1.3;
    private record Sample(long start, Vec3 at, double time, float seconds) {}
    private static final Map<YukkuriView, Sample> samples = new WeakHashMap<>();

    static float seconds(YukkuriView body, float partial, float ordinary) {
        double reference = reference(body.getAnimationId());
        if (reference == 0 || body.isPassenger()) {
            samples.remove(body);
            return ordinary;
        }
        Vec3 at = body.getPosition(partial);
        double time = body.level().getGameTime() + partial;
        var previous = samples.get(body);
        if (previous == null || previous.start != body.getAnimationStartTick() || time < previous.time) {
            samples.put(body, new Sample(body.getAnimationStartTick(), at, time, ordinary));
            return ordinary;
        }
        double ticks = time - previous.time;
        if (ticks <= 0) return previous.seconds;
        double pace = at.subtract(previous.at).horizontalDistance() / ticks;
        float seconds = previous.seconds + (float) (ticks / 20 * Math.clamp(pace / reference, LOWEST, HIGHEST));
        samples.put(body, new Sample(previous.start, at, time, seconds));
        return seconds;
    }

    private static double reference(String clip) {
        if (clip.equals(YukkuriAction.WADDLE.id())) return WALK;
        if (clip.equals(YukkuriAction.RUN.id())) return RUN;
        if (clip.equals(Behaviors.PREDATOR_FLY)) return FLY;
        return 0;
    }

    private GaitClock() {}
}
