package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** The rim of a bed, with a low opening on its north or south side. Units are blocks. */
public record Doorway(double inner, double outer, double opening, int side) {
    /** A body already overlapping the rim retreats on its current side before resuming its walk. Never crosses the rim. */
    public double[] retreat(double x, double z, double radius) {
        if (walls(0, 0, 0).stream().noneMatch(wall -> wall.gap(x, z) < radius)) return null;
        if (Math.max(Math.abs(x), Math.abs(z)) < inner) {
            double room = Math.max(0, inner - radius);
            return new double[]{Math.clamp(x, -room, room), Math.clamp(z, -room, room)};
        }
        double beyond = outer + radius;
        return Math.abs(x) > Math.abs(z) ? new double[]{Math.copySign(beyond, x), z}
                : new double[]{x, Math.copySign(beyond, z)};
    }

    public List<Detour.Box> walls(long id, double x, double z) {
        double front = side * (inner + outer) * .5, back = -front, thick = (outer - inner) * .5;
        return List.of(
                new Detour.Box(id, x - (inner + outer) * .5, z, thick, outer, 1, 0),
                new Detour.Box(id + 1, x + (inner + outer) * .5, z, thick, outer, 1, 0),
                new Detour.Box(id + 2, x, z + back, inner, thick, 1, 0),
                new Detour.Box(id + 3, x - (inner + opening) * .5, z + front, (inner - opening) * .5, thick, 1, 0),
                new Detour.Box(id + 4, x + (inner + opening) * .5, z + front, (inner - opening) * .5, thick, 1, 0));
    }
}
