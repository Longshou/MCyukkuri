package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NestFootingTest {
    @Test void afterPracticeTheBranchHoldsBothOrdinaryTravelAndAShove() {
        var below=new NestFooting.Surface[]{NestFooting.Surface.AIR,NestFooting.Surface.BRANCH,NestFooting.Surface.PAD};
        assertEquals(1,NestFooting.depth(i->below[i],true));
        assertEquals(-1,NestFooting.depth(i->below[i],false));
        below[1]=NestFooting.Surface.OTHER;
        assertEquals(-1,NestFooting.depth(i->below[i],true));
        assertFalse(NestFooting.confines(true,false,false,true,false));
        assertTrue(NestFooting.confines(true,false,false,false,false));
        int branch=-307;
        double sole=.37*.8*.42;
        for (double push : new double[]{.015,.25,-.015,-.25}) {
            double rim=push>0 ? branch+1-sole : branch+sole;
            assertEquals(0,NestFooting.step(rim,push,branch,sole),1e-9);
            assertEquals(-push,NestFooting.step(rim,-push,branch,sole),1e-9);
        }
        assertFalse(NestFooting.confines(true,false,false,false,true));
        assertFalse(NestFooting.confines(false,false,false,false,false));
        assertFalse(NestFooting.confines(true,true,false,false,false));
        assertFalse(NestFooting.confines(true,false,true,false,false));
    }
}
