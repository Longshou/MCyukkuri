package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.IntFunction;

/** A zero-lift birth impulse that intercepts a moving cloth floor, using the same discrete motion as Launch. */
public final class BirthFlight {
    public record Drop(Vec aim, Vec velocity, int ticks) {}
    private BirthFlight() {}

    /** Intercept a moving floor during the falling tick; horizontal drag is interpolated within that same tick. */
    public static Drop drop(Vec start, IntFunction<Vec> target) {
        double y = start.y(), vy = 0, sum = 0, drag = 1;
        Vec before = target.apply(0);
        for (int tick = 1; tick <= Ballistic.MAX_TICKS; tick++) {
            Vec aim = target.apply(tick);
            double nextY = y + vy;
            if (y >= before.y() && nextY < aim.y()) {
                double fraction = (y - before.y()) / (y - before.y() + aim.y() - nextY);
                Vec at = before.add(aim.sub(before).scale(fraction));
                double distance = sum + drag * fraction;
                return new Drop(at, new Vec((at.x() - start.x()) / distance, 0, (at.z() - start.z()) / distance), tick);
            }
            y = nextY;
            vy = (vy - Ballistic.GRAVITY) * Ballistic.VERTICAL_DRAG;
            sum += drag;
            drag *= Ballistic.AIR_DRAG;
            before = aim;
        }
        return null;
    }
}
