package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuoyancyTest {
    private static final Buoyancy.Plane HAT=Buoyancy.Plane.rect(.76,1.05);

    @Test void densityIsTheWaterDistressNeutralBall(){
        assertEquals(.5896,Buoyancy.BODY_DENSITY,1e-4);
        assertEquals(Math.PI/6*.75*.75*.53*Buoyancy.BODY_DENSITY,Buoyancy.bodyMass(.75,.53,Buoyancy.BODY_DENSITY),1e-12);
    }

    @Test void draftGrowsWithLoad(){
        assertEquals(0,Buoyancy.draft(0,HAT),1e-12);
        double one=Buoyancy.draft(.05,HAT),two=Buoyancy.draft(.1,HAT);
        assertEquals(2*one,two,1e-12);
        assertEquals(.05/(.76*1.05),one,1e-12);
    }

    @Test void heelFollowsTheMomentSide(){
        assertEquals(0,Buoyancy.rollHeel(0,HAT),1e-12);
        assertTrue(Buoyancy.rollHeel(.002,HAT)<0,"a load on +x lowers +x: negative roll");
        assertTrue(Buoyancy.pitchHeel(.002,HAT)>0,"a load on +z lowers +z: positive pitch");
        // the long axis resists pitch more than the narrow one resists roll
        assertTrue(Math.abs(Buoyancy.pitchHeel(.002,HAT))<Math.abs(Buoyancy.rollHeel(.002,HAT)));
    }

    @Test void floodingShrinksWithDraftAndVanishesWhenSwamped(){
        double light=Buoyancy.flooding(.25,.63),loaded=Buoyancy.flooding(.15,.63);
        assertTrue(loaded<light);
        assertEquals(0,Buoyancy.flooding(0,.63),1e-12);
        assertEquals(0,Buoyancy.flooding(-.1,.63),1e-12);
        assertEquals(1,Buoyancy.floodRatio(10,0,10,8),1e-12);
        assertEquals(Double.POSITIVE_INFINITY,Buoyancy.floodRatio(0,0,0,8));
    }

    @Test void restIsTheHeelUprightAndFlatCapsized(){
        assertEquals(3,Buoyancy.rest(20,3),1e-12);
        assertEquals(180,Buoyancy.rest(95,3),1e-12);
        assertEquals(-180,Buoyancy.rest(-120,0),1e-12);
        assertTrue(Buoyancy.up(0,0)>.999);
        assertTrue(Buoyancy.up(180,0)<-.999);
        assertTrue(Buoyancy.up(0,180)<-.999);
    }

    @Test void riseFloatsTheBottomUprightAndTheBrimCapsized(){
        assertEquals(-.1,Buoyancy.rise(1,.1,.28),1e-12);
        assertEquals(.18,Buoyancy.rise(-1,.1,.28),1e-12);
        assertEquals(0,Buoyancy.rise(1e-9,.1,.28),1e-6);
        assertEquals(0,Buoyancy.rise(-1e-9,.1,.28),1e-6);
    }

    @Test void dampedSpringSettlesOnItsRest(){
        double x=12,v=0;
        for(int t=0;t<300;t++){v=Buoyancy.spring(0-x,v,.1,.08);x+=v;}
        assertEquals(0,x,1e-3);
        assertEquals(0,v,1e-3);
    }

    @Test void wrapKeepsAnglesInOneTurn(){
        assertEquals(-170,Buoyancy.wrap(190),1e-12);
        assertEquals(180,Buoyancy.wrap(-180),1e-12);
        assertEquals(10,Buoyancy.wrap(370),1e-12);
    }
}
