package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedSpaceTest {
    private final BedSpace.Box floor=new BedSpace.Box(-1,-1,-1,1,0,1);
    private boolean fits(BedSpace.Box... boxes){
        return BedSpace.fits(List.of(boxes),0,0,.8,.6,.62,.44,.85);
    }
    @Test void anInternalSlitBetweenRealFloorFacesCannotSupportTheWholeFoot(){
        var west=new BedSpace.Box(-1,-1,-1,.12,0,1);
        var east=new BedSpace.Box(.14,-1,-1,1,0,1);
        assertFalse(fits(west,east));
        assertTrue(fits(west,east,new BedSpace.Box(.12,-1,-1,.14,0,1)));
        assertFalse(fits(west,east,new BedSpace.Box(.12,-1,-1,.14,-.02,1)));
    }
    @Test void headBrimBodyAndLowRimAllNeedClearanceAtTheProposedFeet(){
        assertTrue(fits(floor));
        assertFalse(fits(floor,new BedSpace.Box(-1,.8,-1,1,1,1)));
        assertFalse(fits(floor,new BedSpace.Box(.56,.45,-.1,.7,.5,.1)));
        assertFalse(fits(floor,new BedSpace.Box(.39,0,-.1,.5,.04,.1)));
        assertTrue(fits(floor,new BedSpace.Box(.56,0,-.1,.7,.4,.1)));
        assertTrue(fits(floor,new BedSpace.Box(.5,.45,.5,.7,.5,.7)));
    }
}
