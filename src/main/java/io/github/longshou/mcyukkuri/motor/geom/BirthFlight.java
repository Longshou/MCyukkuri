package io.github.longshou.mcyukkuri.motor.geom;

import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.ToDoubleFunction;

/** A zero-lift birth impulse and its first cloth contact, using the same discrete motion as Launch. */
public final class BirthFlight {
    public record Drop(Vec aim, Vec velocity, int ticks) {}
    public record Contact(Vec point, int tick, boolean wall) {}
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

    /** Sweep between the current and next host frames, with the exact cloth contact used by an enveloped launch. */
    public static Contact contact(Vec start, Vec velocity, int ticks, BiFunction<Integer, Vec, Vec> local,
                                  double unit, ToDoubleFunction<Vec> surface) {
        var path = Ballistic.samples(velocity, ticks);
        for (int tick = 1; tick < path.size(); tick++) {
            var hit = FlightContact.first(local.apply(tick - 1, start.add(path.get(tick - 1))),
                    local.apply(tick, start.add(path.get(tick))), FlightContact.STEP / unit, 0, surface);
            if (hit != null) return new Contact(hit.point(), tick, hit.wall());
        }
        return null;
    }
}
