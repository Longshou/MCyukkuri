package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShoreExitTest {
    private static final UUID HAT = UUID.fromString("c284b7a8-a3c8-46fe-b241-a71ff149b94c");
    private static final UUID EARLY2 = UUID.fromString("9e710557-ce02-7358-ebca-1d916e084dc9");
    private static final Vec BERTH = new Vec(-153.091314, 63.919125, 309.113600);
    private static final float YAW = -4.542778f;
    private static final Vec RIDER = new Vec(5.305397, -.107456, -7.576893).scale(HullBounds.UNIT);
    private static final Vec FAR_BRIM = new Vec(-7.486269, -.079528, -5.241942);

    private static Waters.BrimStops stops(Vec... places) {
        // The simulated bank supplies each handoff result directly, without a Minecraft edge lookup.
        return new Waters.BrimStops(null, new ArrayList<>(List.of(places)));
    }

    private static Waters.ExitPose pose(Vec origin, float yaw, Vec rider, double reach) {
        var child = new Waters.Occupant(EARLY2, rider, 0, .16947, List.of());
        return new Waters.ExitPose(HAT, origin, HullBounds.RADIUS, yaw, Vec.ZERO, true, false,
                true, reach, .23, List.of(child), List.of());
    }

    @Test void rockingOnlyRechecksTheBankLegOfAReachableBrim() {
        // PD-13 Early2 at t7162: the opposite brim was 1.14 blocks away with .29 of reach.
        var far = FAR_BRIM;
        var near = RIDER.scale(1 / HullBounds.UNIT);
        var arc = assertInstanceOf(ArcSolver.Blocked.class,
                ArcSolver.lowest(RIDER, far.scale(HullBounds.UNIT), .23, .29, .21, points -> true));
        assertEquals(ArcSolver.Kind.TOO_FAR, arc.kind());
        var probes = new ArrayList<List<Vec>>();
        Waters.ExitSearch last = null;
        for (int tick = 7162; tick <= 7363; tick++) {
            var tilt = new Waters.Tilt(HullPresentation.roll(tick), HullPresentation.pitch(tick));
            var berth = pose(BERTH.add(new Vec(0, HullPresentation.bob(tick), 0)), YAW, RIDER, tick == 7162 ? .29 : .28);
            last = Waters.ExitSearch.observe(last, berth, tilt, tick, at -> 0, () -> stops(far, near), stops -> {
                probes.add(List.copyOf(stops.remaining()));
                if (stops.remaining().contains(far))
                    assertEquals(Waters.Landing.NEXT_BRIM, stops.check(far, Feasibility.of(Reason.TOO_FAR, arc.shortfall(), "")));
                assertEquals(Waters.Landing.NEXT_BANK, stops.check(near, Feasibility.of(Reason.NO_SUPPORT, 0, "bank_handoff TOO_FAR")));
                return null;
            });
        }
        assertEquals(List.of(List.of(far, near), List.of(near), List.of(near), List.of(near), List.of(near)), probes);
        assertEquals(Waters.Landing.ACCEPT, last.stops().check(near, Feasibility.pass()));
    }

    @Test void exhaustedStopsReopenOnChangedBerthYawRiderReachOrBank() {
        var berth = pose(BERTH, YAW, RIDER, .29);
        var tilt = new Waters.Tilt(-2, -1);
        var rocked = new Waters.Tilt(2, 1);
        Function<Waters.BrimStops, Waters.Exit> refuse = stops -> {
            assertEquals(Waters.Landing.NEXT_BRIM, stops.check(FAR_BRIM, Feasibility.of(Reason.TOO_FAR, .8, "")));
            return null;
        };
        var last = Waters.ExitSearch.observe(null, berth, tilt, 0, p -> 0, () -> stops(FAR_BRIM), refuse);
        assertSame(last, Waters.ExitSearch.observe(last, berth, rocked, 1, p -> 0,
                () -> fail("rocking reopened the brim search"), stops -> fail("no stops remain")));
        Function<Waters.BrimStops, Waters.Exit> reopened = stops -> {
            assertEquals(List.of(FAR_BRIM), List.copyOf(stops.remaining()));
            return null;
        };
        for (var changed : List.of(pose(BERTH.add(new Vec(.2, 0, 0)), YAW, RIDER, .29),
                pose(BERTH, YAW + .2f, RIDER, .29), pose(BERTH, YAW, RIDER.add(new Vec(0, 0, .06)), .29),
                pose(BERTH, YAW, RIDER, .35))) {
            var next = Waters.ExitSearch.observe(last, changed, tilt, 1, p -> 0, () -> stops(FAR_BRIM), reopened);
            assertFalse(next.stops().remaining().isEmpty());
        }
        var edited = Waters.ExitSearch.observe(last, berth, tilt, 20, p -> 1, () -> stops(FAR_BRIM), reopened);
        assertFalse(edited.stops().remaining().isEmpty());
    }
}
