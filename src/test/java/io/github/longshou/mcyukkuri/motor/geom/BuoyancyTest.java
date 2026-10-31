package io.github.longshou.mcyukkuri.motor.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuoyancyTest {
    @Test void densityIsTheWaterDistressNeutralBall(){
        assertEquals(.5896,Buoyancy.BODY_DENSITY,1e-4);
        assertEquals(Math.PI/6*.75*.75*.53*Buoyancy.BODY_DENSITY,Buoyancy.bodyMass(.75,.53,Buoyancy.BODY_DENSITY),1e-12);
    }

    @Test void climbingTransfersWeightContinuouslyAsWaterReleasesTheBody(){
        assertEquals(0,Buoyancy.supported(.56,0),1e-12);
        assertEquals(0,Buoyancy.supported(1,0),1e-12);
        assertEquals(1,Buoyancy.supported(0,0),1e-12);
        double previous=0;
        for(int i=0;i<=560;i++) {
            double weight=Buoyancy.supported(.56-i*.001,0);
            assertTrue(weight>=previous);assertTrue(weight-previous<.003);previous=weight;
        }
        assertEquals(.5, Buoyancy.supported(.36,0), .01);
        assertEquals(0,Buoyancy.overEdge(-1));assertEquals(.5,Buoyancy.overEdge(0));assertEquals(1,Buoyancy.overEdge(1));
        assertEquals(1,Buoyancy.supported(.56,Buoyancy.overEdge(1)),1e-12,"dry hollow takes the full load before mounting");
        previous=0;
        for(int i=0;i<=200;i++) {
            double weight=Buoyancy.supported(.56,Buoyancy.overEdge(-1+i*.01));
            assertTrue(weight>=previous);assertTrue(weight-previous<.007);previous=weight;
        }
    }

    @Test void restIsTheHeelUprightAndFlatCapsized(){
        assertEquals(3,Buoyancy.rest(20,3),1e-12);
        assertEquals(180,Buoyancy.rest(95,3),1e-12);
        assertEquals(-180,Buoyancy.rest(-120,0),1e-12);
        assertTrue(Buoyancy.up(0,0)>.999);
        assertTrue(Buoyancy.up(180,0)<-.999);
        assertTrue(Buoyancy.up(0,180)<-.999);
    }

    @Test void floodedRimTransfersCoveredSupportWithoutAJump(){
        double previous=1;
        for(int i=-100;i<=1000;i++){
            double covered=Buoyancy.covered(1,-i*.0006,.6);
            double load=Buoyancy.supported(.56,covered);
            assertTrue(load<=previous+1e-12);assertTrue(previous-load<.002);previous=load;
        }
        assertEquals(0,previous,1e-12);
        assertEquals(Buoyancy.covered(.7,1e-8,.6),Buoyancy.covered(.7,-1e-8,.6),1e-12);
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

    @Test void bankContactStopsTheDescendingBrimAndSuppliesItsSupportingMoment(){
        var load=new Buoyancy.Load(.1,Vec.ZERO,.03,.04);
        var point=new Vec(.6,.2,-.3);
        double roll=-12,pitch=8;
        var before=new Buoyancy.Motion(-.04,-3,-1);
        var after=Buoyancy.bank(load,point,roll,pitch,before);
        var posed=Buoyancy.rotate(point,roll,pitch);
        double normal=after.vertical()+posed.x()*Math.toRadians(after.roll())
                -posed.z()*Math.cos(Math.toRadians(roll))*Math.toRadians(after.pitch());
        assertEquals(0,normal,1e-12,"the bank supports the moving brim, not just the keel");
        assertTrue(after.roll()>before.roll(),"support lifts the descending +x brim");
        assertTrue(energy(load,after)<energy(load,before),"inelastic support cannot add energy");
        var leaving=new Buoyancy.Motion(.1,2,0);
        assertEquals(leaving,Buoyancy.bank(load,point,roll,pitch,leaving),"the bank cannot pull a separating brim down");
    }

    private static double energy(Buoyancy.Load load,Buoyancy.Motion motion){
        return .5*(load.mass()*motion.vertical()*motion.vertical()+load.rollInertia()*Math.pow(Math.toRadians(motion.roll()),2)
                +load.pitchInertia()*Math.pow(Math.toRadians(motion.pitch()),2));
    }
}
