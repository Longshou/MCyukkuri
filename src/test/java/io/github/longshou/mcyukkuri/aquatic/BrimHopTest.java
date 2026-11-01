package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HatSeats;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.HopPath;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrimHopTest {
    @Test void smallJuvenileReachesBraidSideWithSeveralSupportedHops() {
        var b = new HatSeats.Body("marisa", .3, .24, .18);
        var from = HatSeats.admit(1, b, false, List.of());
        var to = HullBounds.brim(.3, 0);
        var stops = IntStream.range(0, 24).mapToObj(i -> HullBounds.brim(.3, i * Math.PI / 12)).toList();
        var ability = AbilityMath.compute(new AbilityMath.Inputs(.3, 100, 20, 20, 0, false, true, false, false, 0),
                AbilityMath.Coefficients.DEFAULTS, AbilityMath.Multipliers.IDENTITY);
        assertTrue(to.sub(from).hlen() > ability.hopReach());
        int hops = 0;
        while (!from.equals(to) && hops < stops.size()) {
            var leg = HopPath.first(from, to, stops, (a, c) -> ArcSolver.lowest(a, c,
                    ability.hopHeight(), ability.hopReach(), ability.size(), points -> clear(a, points)));
            assertNotNull(leg);
            assertTrue(leg.at().sub(from).hlen() <= ability.hopReach());
            from = leg.at();
            hops++;
        }
        assertEquals(to, from);
        assertTrue(hops > 1);
    }

    @Test void blockedIntermediatePointsDoNotCreateAPath() {
        assertNull(HopPath.first(Vec.ZERO, new Vec(2, 0, 0), List.of(new Vec(1, 0, 0)),
                (a, b) -> new ArcSolver.Blocked(ArcSolver.Kind.TOO_HIGH, 1)));
    }

    private static boolean clear(Vec from, List<Vec> points) {
        return points.stream().map(from::add).allMatch(p -> p.y() >= HullBounds.surface(p.hlen()) - .05);
    }
}
