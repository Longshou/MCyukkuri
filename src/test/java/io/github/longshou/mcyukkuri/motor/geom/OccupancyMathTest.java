package io.github.longshou.mcyukkuri.motor.geom;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccupancyMathTest {
    private static final double FLOOR_Y=0,RIM_Y=.5,FOOT=.42;
    private static final FloorShape BOWL=new FloorShape.Circle(1);
    private static final FloorShape SQUARE=new FloorShape.RoundedRect(1,.3);

    private static OccupancyMath.Result classify(double x,double z,double y,FloorShape floor,boolean supported){
        return OccupancyMath.classify(x,z,y,FOOT,floor,FLOOR_Y,RIM_Y,supported);
    }

    @Test void centreOnTheFloorIsInsideAndSupported(){
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,classify(0,0,FLOOR_Y,BOWL,true));
    }

    @Test void huggingTheInnerWallIsStillInside(){
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,classify(.5,0,FLOOR_Y,BOWL,true));
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,classify(.58,0,FLOOR_Y,BOWL,true));
    }

    @Test void straddlingTheRimIsOnTheRim(){
        assertEquals(OccupancyMath.Result.ON_RIM,classify(.5,0,RIM_Y,BOWL,true));
        assertEquals(OccupancyMath.Result.ON_RIM,classify(0,0,RIM_Y-.05,BOWL,true));
    }

    @Test void footprintHalfOutOfTheFloorIsPartial(){
        assertEquals(OccupancyMath.Result.PARTIAL,classify(.95,0,FLOOR_Y,BOWL,true));
    }

    @Test void insideWithoutSupportIsPartial(){
        assertEquals(OccupancyMath.Result.PARTIAL,classify(0,0,FLOOR_Y,BOWL,false));
        assertEquals(OccupancyMath.Result.PARTIAL,classify(0,0,FLOOR_Y+.4,BOWL,true));
    }

    @Test void feetAboveTheRimAreOnTheRim(){
        assertEquals(OccupancyMath.Result.ON_RIM,classify(.3,0,RIM_Y+.4,BOWL,true));
    }

    @Test void awayFromTheFloorIsOutside(){
        assertEquals(OccupancyMath.Result.OUTSIDE,classify(3,0,FLOOR_Y,BOWL,true));
        assertEquals(OccupancyMath.Result.OUTSIDE,classify(1.35,0,FLOOR_Y,BOWL,true));
    }

    @Test void footprintInTheRimRingIsOnTheRim(){
        assertEquals(OccupancyMath.Result.ON_RIM,classify(1.35,0,RIM_Y,BOWL,true));
    }

    @Test void roundedRectCornersAreBoundedByTheCornerArc(){
        double corner=.7+.29*Math.cos(Math.PI/4);
        for(double sx:new double[]{-1,1})for(double sz:new double[]{-1,1}){
            assertTrue(SQUARE.contains(sx*corner,sz*corner),"inside corner arc");
            assertFalse(SQUARE.contains(sx*.95,sz*.95),"beyond corner arc");
        }
        assertTrue(SQUARE.contains(.99,.5));
        assertFalse(SQUARE.contains(1.01,.5));
        assertTrue(SQUARE.containsGrown(1.2,.5,.2));
        assertFalse(SQUARE.containsGrown(1.2,.5,.1));
    }

    @Test void roundedRectOccupancyFollowsTheSameTable(){
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,classify(0,0,FLOOR_Y,SQUARE,true));
        assertEquals(OccupancyMath.Result.PARTIAL,classify(.7,.7,FLOOR_Y,SQUARE,true));
        assertEquals(OccupancyMath.Result.ON_RIM,classify(0,.99,RIM_Y,SQUARE,true));
        assertEquals(OccupancyMath.Result.OUTSIDE,classify(0,1.6,FLOOR_Y,SQUARE,true));
    }

    @Test void polygonFloorAndItsGrownRing(){
        FloorShape triangle=new FloorShape.Polygon(List.of(new Vec(0,0,1),new Vec(-1,0,-1),new Vec(1,0,-1)));
        assertTrue(triangle.contains(0,0));
        assertFalse(triangle.contains(0,1.5));
        assertTrue(triangle.containsGrown(0,1.5,.6));
        assertFalse(triangle.containsGrown(0,1.5,.3));
        assertEquals(OccupancyMath.Result.INSIDE_SUPPORTED,classify(0,0,FLOOR_Y,triangle,true));
        assertEquals(OccupancyMath.Result.OUTSIDE,classify(0,2,FLOOR_Y,triangle,true));
    }
}
