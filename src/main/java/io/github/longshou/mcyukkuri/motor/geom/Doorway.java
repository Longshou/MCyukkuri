package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;

/** The rim of a bed, with a low opening on its north or south side. Units are blocks. */
public record Doorway(double inner, double outer, double opening, int side) {
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
