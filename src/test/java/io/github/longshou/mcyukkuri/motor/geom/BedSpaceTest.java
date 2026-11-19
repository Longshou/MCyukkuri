package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedSpaceTest {
    private final BedSpace.Box floor=new BedSpace.Box(-1,-1,-1,1,0,1);
    private boolean fits(BedSpace.Box... boxes){
        return BedSpace.fits(List.of(boxes),0,0,.8,.6,.715,.649,1.33,true);
    }
    @Test void anInternalSlitBetweenRealFloorFacesCannotSupportTheWholeFoot(){
        var west=new BedSpace.Box(-1,-1,-1,.12,0,1);
        var east=new BedSpace.Box(.14,-1,-1,1,0,1);
        assertFalse(fits(west,east));
        assertTrue(fits(west,east,new BedSpace.Box(.12,-1,-1,.14,0,1)));
        assertFalse(fits(west,east,new BedSpace.Box(.12,-1,-1,.14,-.02,1)));
    }
    @Test void openFloorAndSideWallsFitTheSameWideBrim(){
        assertTrue(fits(floor));
        var west=new BedSpace.Box(-1,0,-1,-.5,1,1);
        var east=new BedSpace.Box(.5,0,-1,1,1,1);
        var eastUpper=new BedSpace.Box(.5,1,-1,1,2,1);
        assertTrue(fits(floor,west,east,eastUpper));
        assertFalse(BedSpace.fits(List.of(floor,west,east),0,0,.8,.6,.715,.649,1.33,false),
                "non-brim headwear of the same size still needs its full radius");
        assertFalse(fits(floor,west,eastUpper), "a floating block has no side face that presses the brim");
    }
    @Test void arrivalRechecksWholeSoleAndOverheadAtTheActualFeet(){
        var pad=new BedSpace.Box(-.45,-1,-1,.45,0,1);
        var ledge=new BedSpace.Box(.73,.8,-1,1,1,1);
        assertTrue(fits(pad,ledge));
        assertFalse(BedSpace.fits(List.of(pad),.10,0,.8,.6,.715,.649,1.33,true));
        assertFalse(BedSpace.fits(List.of(floor,ledge),.10,0,.8,.6,.715,.649,1.33,true));
    }
    @Test void bodyAndOverheadStillNeedClearance(){
        assertFalse(fits(floor,new BedSpace.Box(-1,1,-1,1,2,1)));
        assertFalse(fits(floor,new BedSpace.Box(.56,.7,-.1,.7,.9,.1)));
        assertFalse(fits(floor,new BedSpace.Box(.39,0,-.1,.5,.04,.1)));
        assertTrue(fits(floor,new BedSpace.Box(.56,0,-.1,.7,.4,.1)));
        assertTrue(fits(floor,new BedSpace.Box(-1,2,-1,1,3,1)));
    }
}
