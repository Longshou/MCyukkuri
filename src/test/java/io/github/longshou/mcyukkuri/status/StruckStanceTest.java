package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.social.Temperament;
import org.junit.jupiter.api.Test;
import static io.github.longshou.mcyukkuri.status.StruckStance.*;
import static org.junit.jupiter.api.Assertions.*;

class StruckStanceTest {
    private Inputs hit(int bold, int control, int friendly, int attached, int uneasy, int fear, double hp,
                       boolean baby, boolean open, boolean owner, boolean frozen) {
        return new Inputs(new Temperament(50, friendly, bold, attached, control), uneasy, fear, hp, 1, 10,
                owner ? Identity.PET : Identity.WILD_FAMILY, baby, 0, open, owner, true, frozen);
    }

    @Test void ordinaryWildFleesAndDefiantBeggarChangesItsMind() {
        assertEquals(FLEE, decide(hit(45,50,50,35,25,18,.9,false,true,false,false)));
        assertEquals(PUFF_FIGHT, decide(hit(78,45,20,15,15,11,.95,false,true,false,false)));
        assertEquals(FLEE, decide(hit(78,45,20,15,35,24,.75,false,true,false,false)));
        assertEquals(FLEE, decide(hit(15,100,50,50,68,31,.9,false,true,false,false)));
    }

    @Test void babyUsuallyRunsButTimidBabyCanFreezeOnce() {
        assertEquals(FLEE, decide(hit(40,45,50,35,30,18,.9,true,true,false,false)));
        var timid = hit(12,18,50,35,65,18,.9,true,true,false,false);
        assertEquals(FREEZE, decide(timid));
        assertTrue(timid.freezeTicks() >= 40 && timid.freezeTicks() <= 100);
        assertEquals(FLEE, decide(hit(12,18,50,35,65,30,.8,true,true,false,true)));
        assertTrue(breaksFreeze(6, 2, 5, true));
        assertFalse(breaksFreeze(5, 2, 5, true));
        assertFalse(breaksFreeze(6, 4, 5, true));
        assertFalse(breaksFreeze(6, 2, 5, false));
    }

    @Test void softOwnerAndCorneredStalls() {
        assertEquals(PLEAD, decide(hit(30,35,75,80,55,20,.9,false,true,true,false)));
        assertEquals(ENDURE, decide(hit(30,80,75,80,20,20,.9,false,true,true,false)));
        assertEquals(FREEZE, decide(hit(30,57,65,50,49,30,.9,false,false,false,false)));
        assertNotEquals(FREEZE, decide(hit(30,57,65,50,49,60,.8,false,false,false,true)));
        assertEquals(PLEAD, decide(hit(25,30,65,50,83,31,.7,false,false,false,false)));
        assertEquals(PLEAD, decide(hit(25,30,65,50,83,62,.6,false,false,false,false)));
        assertEquals(PUFF_FIGHT, decide(hit(80,68,15,50,0,19,.9,false,false,false,false)));
        assertEquals(FREEZE, decide(hit(80,68,15,50,0,38,.8,false,false,false,false)));
    }
}
