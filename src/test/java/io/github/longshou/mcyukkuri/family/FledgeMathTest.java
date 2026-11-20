package io.github.longshou.mcyukkuri.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class FledgeMathTest {
    @Test void growingOlderOnTheBranchDoesNotCompleteAFirstFlight() {
        double hop=FledgeMath.practise(0,true);
        assertTrue(FledgeMath.learning(true,hop,false));
        assertTrue(FledgeMath.learning(false,hop,false));
        assertFalse(FledgeMath.learning(false,hop,true));
        assertFalse(FledgeMath.learning(false,0,false));
    }
    @Test void boldYoungAttemptFlightEarlier() {
        assertEquals(.75, FledgeMath.threshold(0), 1e-9);
        assertEquals(.495, FledgeMath.threshold(85), 1e-9);
        assertEquals(.675, FledgeMath.threshold(25), 1e-9);
        assertEquals(.45, FledgeMath.threshold(100), 1e-9);
    }
    @Test void landedHopsEarnTwiceAFlapAndProgressCloses() {
        assertEquals(.04, FledgeMath.practise(0, false));
        assertEquals(.08, FledgeMath.practise(0, true));
        assertEquals(1, FledgeMath.practise(.97, true));
    }
    @Test void practiceSoftensARealFall() {
        assertEquals(12, FledgeMath.effectiveFall(12, 0));
        assertEquals(7.2f, FledgeMath.effectiveFall(12, .5), 1e-5);
        assertEquals(2.4f, FledgeMath.effectiveFall(12, 1), 1e-5);
    }
}
