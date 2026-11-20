package io.github.longshou.mcyukkuri.life;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The sand spit of the starving Patchouli: 3 x 5 columns at y 64 inside water, a full-block ledge on one side. */
class StrandingTest {
    private static final double WALK = .8, SCRAMBLE = 1.1, DROP = 2;

    /** Columns by (x, z) to their stand height; absent = water or void. */
    private static final class Map2 implements Stranding.Terrain {
        final Map<Long, Double> tops = new HashMap<>();
        void put(int x, int z, double y) { tops.put(((long) x << 32) ^ (z & 0xffffffffL), y); }
        @Override public double stand(int x, int z, double lo, double hi) {
            Double y = tops.get(((long) x << 32) ^ (z & 0xffffffffL));
            return y != null && y >= lo && y <= hi ? y : Double.NaN;
        }
    }

    /** The spit at x 624..626, z -340..-336 (y 64) with a bank row on z -335 at the given height, beyond it a wide field at that height. */
    private static Map2 spit(double ledge) {
        var map = new Map2();
        for (int x = 624; x <= 626; x++) for (int z = -340; z <= -336; z++) map.put(x, z, 64);
        for (int x = 610; x <= 640; x++) for (int z = -335; z <= -300; z++) map.put(x, z, 64 + ledge);
        return map;
    }

    @Test void spitWithAFullBlockLedgeIsStrandedAndHasOneExitOverIt() {
        var verdict = Stranding.assess(spit(1), new Stranding.Cell(625, -338, 64), WALK, SCRAMBLE, DROP);
        assertTrue(verdict.stranded());
        assertEquals(15, verdict.size());
        assertNotNull(verdict.exit());
        assertEquals(-336, verdict.exit().from().z());
        assertEquals(-335, verdict.exit().to().z());
        assertEquals(1.0, verdict.exit().rise(), 1e-9);
    }

    @Test void ledgeAboveTheScrambleRiseLeavesNoExit() {
        var verdict = Stranding.assess(spit(1.5), new Stranding.Cell(625, -338, 64), WALK, SCRAMBLE, DROP);
        assertTrue(verdict.stranded());
        assertNull(verdict.exit());
    }

    @Test void aLedgeWithinAHopIsNotALedgeAtAll() {
        var verdict = Stranding.assess(spit(.7), new Stranding.Cell(625, -338, 64), WALK, SCRAMBLE, DROP);
        assertFalse(verdict.stranded());
    }

    @Test void aBankThatLeadsOnlyToAnotherSmallSpitIsNoExit() {
        var map = new Map2();
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) map.put(x, z, 64);
        map.put(4, 1, 65); // a 1x1 knob no bigger than the spit it would leave
        var verdict = Stranding.assess(map, new Stranding.Cell(0, 0, 64), WALK, SCRAMBLE, DROP);
        assertTrue(verdict.stranded());
        assertNull(verdict.exit());
    }

    @Test void theLowestLedgeIsChosenWhenSeveralAreInReach() {
        var map = spit(1);
        for (int x = 618; x <= 623; x++) for (int z = -340; z <= -330; z++) map.put(x, z, 64.9);
        var verdict = Stranding.assess(map, new Stranding.Cell(625, -338, 64), WALK, SCRAMBLE, DROP);
        assertEquals(623, verdict.exit().to().x());
        assertEquals(.9, verdict.exit().rise(), 1e-9);
    }

    @Test void openGroundIsNeverStranded() {
        var map = new Map2();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) map.put(x, z, 64);
        assertTrue(Stranding.assess(map, new Stranding.Cell(0, 0, 64), WALK, SCRAMBLE, DROP).open());
    }
}
