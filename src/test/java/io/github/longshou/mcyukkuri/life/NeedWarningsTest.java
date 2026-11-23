package io.github.longshou.mcyukkuri.life;

import static io.github.longshou.mcyukkuri.life.NeedWarnings.Need.*;
import static org.junit.jupiter.api.Assertions.*;
import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;

class NeedWarningsTest {
    private NeedWarnings sample(int urine, int stool, int thirst, int energy, int food, double felt, boolean breeding) {
        return NeedWarnings.sample(urine, stool, thirst, energy, food, felt, 0, 10, breeding, 0);
    }

    @Test void allEdgesClearAsSoonAsTheirOwnNeedIsRelieved() {
        var simultaneous = sample(30, 30, 30, 40, 50, 1, true);
        assertEquals(URINE.bit() | STOOL.bit() | THIRST.bit() | SLEEP.bit() | HUNGER.bit() | COLD.bit() | BREEDING.bit(),
                simultaneous.mask());
        assertEquals(THIRST, simultaneous.leading());
        assertEquals(0, sample(29, 29, 29, 41, 51, 1.01, false).mask());
        assertEquals(OVERHEATING.bit(), sample(0, 0, 0, 80, 70, 9, false).mask());
        assertEquals(BREEDING.bit(), sample(0, 0, 0, 80, 70, 8.99, true).mask());
        assertNull(sample(0, 0, 0, 80, 70, 8.99, true).leading());
        assertEquals(0, sample(0, 0, 0, 80, 70, 8.99, false).mask());
    }

    @Test void severityKeepsIncreasingBeyondTheActionPoint() {
        assertEquals(THIRST, sample(40, 40, 49, 25, 30, 0, false).leading());
        assertEquals(SLEEP, sample(40, 40, 40, 12, 30, 0, false).leading());
        assertEquals(HUNGER, sample(40, 40, 40, 25, 14, 0, false).leading());
        assertEquals(COLD, sample(40, 40, 40, 25, 30, -1, false).leading());
        assertEquals(OVERHEATING, sample(40, 40, 40, 25, 30, 11, true).leading());
    }

    @Test void quietAndDemandingIndividualsShowTheSameNeedDifferently() {
        assertEquals(0, NeedWarnings.insistence(new Temperament(50, 95, 10, 50, 95)));
        assertEquals(1, NeedWarnings.insistence(new Temperament(50, 5, 95, 50, 5)));
    }

    @Test void ongoingWarningsStayOnTheHudAndYieldTheNextTell() {
        int told = THIRST.bit();
        var mixed = NeedWarnings.sample(69, 40, 40, 80, 70, 5, 0, 10, false, told);
        assertEquals(URINE.bit() | STOOL.bit() | THIRST.bit(), mixed.mask());
        assertNull(mixed.leading());
        assertEquals(SLEEP, NeedWarnings.sample(69, 40, 40, 20, 70, 5, 0, 10, false, told).leading());
    }

    @Test void theToiletUrgeHasNoStandAloneTell() {
        var full = sample(100, 100, 0, 80, 70, 5, false);
        assertEquals(URINE.bit() | STOOL.bit(), full.mask());
        assertNull(full.leading());
    }
}
