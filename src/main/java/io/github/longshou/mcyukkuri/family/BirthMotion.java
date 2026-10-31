package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.Objects;

/** Consecutive known positions in one frame; a missing rider or a changed host starts a new baseline. */
public final class BirthMotion {
    private Object frame;
    private Vec last;

    public double sample(Object frame, Vec at) {
        double distance = 0;
        if (at != null && last != null && Objects.equals(this.frame, frame)) {
            var delta = at.sub(last);
            distance = delta.x()*delta.x() + delta.y()*delta.y() + delta.z()*delta.z();
        }
        this.frame = frame;
        last = at;
        return distance;
    }
}
